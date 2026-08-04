package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import dev.privatevoice.engine.AsrEngineHolder
import dev.privatevoice.engine.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Implements Android's speech-recognition API on top of the local engine.
 *
 * This is what makes the work reusable beyond our own keyboard: HeliBoard's mic
 * key, and any app that calls [SpeechRecognizer], routes through here once this
 * is selected under Settings → Voice input. So a user can keep the keyboard they
 * already like and still get fully local dictation.
 *
 * Unlike the IME, callers control the session lifecycle — they call
 * `stopListening()` when the user is done, so there is no held key to bound the
 * recording. A hard cap prevents a caller that never stops from recording
 * indefinitely.
 */
class VoiceRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = AudioRecorder()
    private var session: Job? = null
    private var active: Callback? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        if (active != null) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!AsrEngineHolder.hasModel(this)) {
            Log.w(TAG, "No model installed")
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        active = callback
        try {
            recorder.start()
            callback.readyForSpeech(Bundle())
            callback.beginningOfSpeech()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start recording", t)
            active = null
            callback.error(SpeechRecognizer.ERROR_AUDIO)
            return
        }

        session = scope.launch {
            var elapsed = 0L
            while (recorder.isRecording && elapsed < MAX_RECORD_MS) {
                delay(RMS_POLL_MS)
                elapsed += RMS_POLL_MS
                // Callers commonly drive a level meter off this.
                runCatching { callback.rmsChanged(recorder.amplitude * RMS_SCALE) }
            }
            if (recorder.isRecording) {
                Log.w(TAG, "Hit the ${MAX_RECORD_MS}ms cap; finishing")
                finish(callback)
            }
        }
    }

    override fun onStopListening(callback: Callback) {
        finish(callback)
    }

    override fun onCancel(callback: Callback) {
        session?.cancel()
        session = null
        if (recorder.isRecording) recorder.cancel()
        active = null
    }

    private fun finish(callback: Callback) {
        session?.cancel()
        session = null
        if (!recorder.isRecording) {
            active = null
            return
        }

        val samples = recorder.stop()
        runCatching { callback.endOfSpeech() }

        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    AsrEngineHolder.getOrLoad(this@VoiceRecognitionService)
                        ?.transcribe(samples, AudioRecorder.SAMPLE_RATE, null)?.text
                }.onFailure { Log.e(TAG, "Transcription failed", it) }.getOrNull()
            }

            active = null
            when {
                text == null -> runCatching { callback.error(SpeechRecognizer.ERROR_SERVER) }
                text.isBlank() -> runCatching { callback.error(SpeechRecognizer.ERROR_NO_MATCH) }
                else -> {
                    val results = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(text),
                        )
                        // Single hypothesis, so a flat confidence. Reporting a
                        // fabricated spread would mislead callers that rank.
                        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                    }
                    // Some callers block until they see partials; emit the final
                    // text as a partial first so those don't hang.
                    runCatching { callback.partialResults(results) }
                    runCatching { callback.results(results) }
                }
            }
        }
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VoiceRecognition"

        /** Whisper only sees the first 30s anyway; stop well before wasting more. */
        const val MAX_RECORD_MS = 30_000L
        const val RMS_POLL_MS = 100L

        /** SpeechRecognizer's rmsdB range is roughly -2..10. */
        const val RMS_SCALE = 10f
    }
}
