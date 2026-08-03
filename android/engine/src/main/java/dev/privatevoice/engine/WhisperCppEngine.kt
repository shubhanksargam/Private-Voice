package dev.privatevoice.engine

import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Whisper backend on top of whisper.cpp / GGML.
 *
 * This exists because sherpa-onnx — the original backend — mangles non-ASCII
 * output. It converts each Whisper token to a string individually and
 * concatenates; since Whisper uses byte-level BPE, a token holds bytes rather
 * than whole characters, and Devanagari (3 bytes/char) is split freely across
 * token boundaries. Partial-UTF-8 tokens decode to empty strings and vanish,
 * destroying roughly 60% of Hindi characters while leaving ASCII untouched.
 * whisper.cpp accumulates bytes across tokens and decodes once, which is
 * correct. Full measurements in docs/M0_RESULTS.md.
 *
 * **Threading:** whisper.cpp is not thread-safe — a `whisper_context` must be
 * touched by one thread at a time. Every native call, including load and free,
 * is confined to a single-threaded executor owned by this instance. The public
 * API stays synchronous (blocking the caller) to match [AsrEngine]; callers are
 * already expected to be off the main thread.
 */
class WhisperCppEngine(
    private val modelFile: File,
    private val numThreads: Int = 4,
    override val id: String = modelFile.nameWithoutExtension + "-t" + numThreads,
    private val defaultLanguage: String = "en",
) : AsrEngine {

    // Single thread, not a pool: this *is* the thread-safety mechanism, not an
    // optimisation. numThreads above is whisper.cpp's internal compute
    // parallelism, which is unrelated.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whispercpp-$id").apply { isDaemon = true }
    }

    private var contextPtr: Long = 0L

    /** Time spent in whisper_init_from_file, i.e. cold model load. */
    override var loadMillis: Long = -1L
        private set

    init {
        require(modelFile.isFile) { "No GGML model at ${modelFile.absolutePath}" }
        onWorker {
            val started = System.nanoTime()
            contextPtr = WhisperLib.initContext(modelFile.absolutePath)
            loadMillis = (System.nanoTime() - started) / 1_000_000
            if (contextPtr == 0L) {
                error("whisper.cpp failed to load ${modelFile.name}")
            }
            Log.i(TAG, "Loaded $id in ${loadMillis}ms (${modelFile.length() / 1024 / 1024}MB)")
        }
    }

    override fun warmUp() {
        // Forces GGML to allocate its compute buffers and spin up worker
        // threads now, rather than during the first real utterance where the
        // user would read it as a stutter.
        //
        // Deliberately low-amplitude NOISE, not digital silence. Feeding
        // whisper.cpp an all-zero buffer sends its greedy decoder into a
        // hallucination loop that emits tokens until it hits internal limits —
        // observed hanging a warm-up for minutes on-device, with the model
        // itself having loaded in under a second. Real microphone input always
        // carries a noise floor, so this is also closer to production input.
        val noise = FloatArray(WARMUP_SAMPLES) { i ->
            // Deterministic, tiny (~-60 dBFS): enough to break the all-zero
            // degenerate case without being loud enough to decode as speech.
            (((i * 1103515245 + 12345) ushr 16 and 0xFF) - 128) * (0.001f / 128f)
        }
        transcribe(noise, 16_000, defaultLanguage)
    }

    override fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
    ): AsrResult {
        require(sampleRate == 16_000) {
            "Whisper expects 16kHz; got $sampleRate. Resample before calling."
        }
        val target = language ?: defaultLanguage

        return onWorker {
            check(contextPtr != 0L) { "Engine already closed" }
            val started = System.nanoTime()
            val text = WhisperLib.fullTranscribeToString(
                contextPtr = contextPtr,
                numThreads = numThreads,
                audioData = samples,
                language = target,
                translate = false,
                timeoutMs = DECODE_TIMEOUT_MS,
            ) ?: error("whisper_full failed for $id")
            val elapsed = (System.nanoTime() - started) / 1_000_000

            // whisper.cpp prefixes segments with a space; harmless for WER but
            // it would show up as a stray leading space when committed to a
            // text field.
            AsrResult(text = text.trim(), language = target, decodeMillis = elapsed)
        }
    }

    override fun close() {
        try {
            onWorker {
                if (contextPtr != 0L) {
                    WhisperLib.freeContext(contextPtr)
                    contextPtr = 0L
                }
            }
        } finally {
            worker.shutdown()
        }
    }

    /**
     * Run [block] on the confinement thread and block until it returns.
     * Unwraps ExecutionException so callers see the real cause rather than a
     * wrapper that hides which model or utterance failed.
     */
    private fun <T> onWorker(block: () -> T): T =
        try {
            worker.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    companion object {
        private const val TAG = "WhisperCppEngine"

        /** 0.5s at 16kHz — enough to force buffer allocation, short to run. */
        private const val WARMUP_SAMPLES = 8_000

        /**
         * Wall-clock ceiling for a single decode. Generous relative to the
         * ~1s a healthy `base` decode takes, because it is a safety net for
         * pathological input, not a latency target — exceeding it means
         * something has gone wrong, not that the user spoke for a long time.
         *
         * On timeout, whatever was decoded is returned rather than an error.
         * A per-segment token cap was tried instead and made things worse: it
         * can truncate before a timestamp token, leaving whisper.cpp's seek
         * position stuck and re-decoding the same window forever.
         */
        private const val DECODE_TIMEOUT_MS = 30_000

        /** ggml/BLAS/NEON feature flags the native build actually enabled. */
        fun systemInfo(): String = WhisperLib.getSystemInfo()

        /**
         * Discover GGML models in [dir]. Each is a single `.bin`, unlike the
         * ONNX layout's encoder/decoder/tokens triple.
         */
        fun discover(dir: File, numThreads: Int = 4): List<WhisperCppEngineSpec> =
            dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.map { WhisperCppEngineSpec(it, numThreads) }
                .orEmpty()
    }
}

/** A GGML model file plus the thread count to run it at. */
data class WhisperCppEngineSpec(
    val modelFile: File,
    val numThreads: Int,
) {
    /** e.g. "ggml-small-q5_1-t4" */
    val id: String get() = "${modelFile.nameWithoutExtension}-t$numThreads"
    val bytes: Long get() = modelFile.length()
}
