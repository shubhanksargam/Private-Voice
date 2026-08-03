package dev.privatevoice.engine

/**
 * Raw JNI surface for whisper.cpp. Package-private by convention — everything
 * outside this file should go through [WhisperCppEngine], which owns the
 * threading contract.
 *
 * The native names must stay in sync with `jni_whisper.c`
 * (`Java_dev_privatevoice_engine_WhisperLib_*`). Renaming or moving this class
 * breaks JNI lookup at runtime, not compile time.
 */
internal object WhisperLib {

    init {
        // One library, built for arm64-v8a with -march=armv8.2-a+fp16.
        // Upstream ships several variants plus /proc/cpuinfo sniffing to pick
        // between them; we target a known device and skip that machinery.
        System.loadLibrary("whispercpp_jni")
    }

    /** @return native context pointer, or 0 on failure. */
    external fun initContext(modelPath: String): Long

    external fun freeContext(contextPtr: Long)

    /**
     * Decode [audioData] (mono float PCM in [-1, 1] at 16kHz) and return all
     * segments joined.
     *
     * @param language BCP-47-ish tag ("en", "hi"), or null to auto-detect.
     * @param timeoutMs wall-clock ceiling for the decode, or 0 for unbounded.
     *   On timeout whatever was decoded so far is returned rather than an
     *   error. This is what stops a degenerate decode from running forever;
     *   a per-segment token cap was tried first and caused a worse failure
     *   (see jni_whisper.c).
     * @return decoded text, or null if the native decode failed outright.
     */
    external fun fullTranscribeToString(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String?,
        translate: Boolean,
        timeoutMs: Int,
    ): String?

    external fun getSystemInfo(): String
}
