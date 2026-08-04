package dev.privatevoice.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture for hold-to-talk dictation.
 *
 * Produces exactly what [AsrEngine] consumes: 16kHz mono float samples in
 * [-1, 1]. Whisper is trained at 16kHz, so capturing natively at that rate
 * avoids a resampling step and the bugs that come with one.
 *
 * No VAD. Whisper pads every input to a 30-second window internally, so
 * trimming silence off the ends buys nothing in decode time — and the user
 * already tells us when to start and stop by holding the key. VAD becomes
 * worthwhile only if auto-stop-on-silence is added later.
 */
class AudioRecorder {

    private var record: AudioRecord? = null
    private val recording = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Captured samples, appended by the reader thread while recording. */
    private val samples = ArrayList<Float>(SAMPLE_RATE * 8)

    val isRecording: Boolean get() = recording.get()

    /**
     * Smoothed RMS level of the most recent audio, roughly 0..1. Drives the
     * UI's live reaction to the voice; read from the main thread, written from
     * the capture thread, hence @Volatile.
     */
    @Volatile
    var amplitude: Float = 0f
        private set

    /**
     * Begin capture. Caller must hold RECORD_AUDIO.
     *
     * @throws IllegalStateException if the mic can't be opened — typically
     *   another app holds it, or the permission was revoked mid-session.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        check(!recording.get()) { "Already recording" }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord.getMinBufferSize failed ($minBuffer)" }

        // VOICE_RECOGNITION asks the platform for the un-beautified path: no
        // AGC or aggressive noise suppression tuned for telephony, both of
        // which distort what an ASR model expects.
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * BUFFER_MULTIPLIER,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("Could not open the microphone")
        }

        synchronized(samples) { samples.clear() }
        amplitude = 0f
        record = r
        recording.set(true)
        r.startRecording()

        thread = Thread({ readLoop(r, minBuffer) }, "audio-capture").apply {
            priority = Thread.MAX_PRIORITY  // dropped frames are unrecoverable
            start()
        }
    }

    private fun readLoop(r: AudioRecord, minBuffer: Int) {
        val buf = ShortArray(minBuffer)
        while (recording.get()) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) {
                // ERROR_INVALID_OPERATION/ERROR_DEAD_OBJECT surface here; there
                // is no recovering mid-utterance, so stop and let stop() return
                // whatever was captured.
                if (n < 0) Log.w(TAG, "AudioRecord.read returned $n")
                continue
            }
            var sumSquares = 0.0
            synchronized(samples) {
                for (i in 0 until n) {
                    val s = buf[i] / 32768.0f
                    samples.add(s)
                    sumSquares += (s * s).toDouble()
                }
            }

            // RMS, then a perceptual-ish curve so normal speech occupies a
            // useful part of the range instead of hugging zero. Smoothed so the
            // UI breathes rather than strobing at buffer rate.
            val rms = kotlin.math.sqrt(sumSquares / n).toFloat()
            val scaled = (rms * AMPLITUDE_GAIN).coerceIn(0f, 1f)
            amplitude += (scaled - amplitude) * AMPLITUDE_SMOOTHING
        }
    }

    /**
     * Stop capture and return everything recorded.
     *
     * @return mono float samples at [SAMPLE_RATE], or an empty array if
     *   nothing was captured.
     */
    fun stop(): FloatArray {
        if (!recording.getAndSet(false)) return FloatArray(0)

        thread?.join(STOP_JOIN_MS)
        thread = null

        record?.let {
            runCatching { it.stop() }.onFailure { t -> Log.w(TAG, "stop failed", t) }
            it.release()
        }
        record = null

        return synchronized(samples) { samples.toFloatArray() }
    }

    /** Abandon capture and discard audio — used when the user cancels. */
    fun cancel() {
        stop()
        synchronized(samples) { samples.clear() }
    }

    /** Seconds captured so far; for driving a duration readout while held. */
    fun durationSeconds(): Float =
        synchronized(samples) { samples.size / SAMPLE_RATE.toFloat() }

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000

        // Headroom over the minimum so a scheduling hiccup on a busy phone
        // doesn't drop frames mid-word.
        private const val BUFFER_MULTIPLIER = 4
        private const val STOP_JOIN_MS = 1_000L

        // Conversational speech sits around 0.05-0.15 RMS; this maps that into
        // most of 0..1 so the UI has something to show without clipping shouts.
        private const val AMPLITUDE_GAIN = 6f
        private const val AMPLITUDE_SMOOTHING = 0.35f
    }
}
