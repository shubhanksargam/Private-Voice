package dev.privatevoice.engine

/**
 * Outcome of a single transcription pass.
 *
 * [decodeMillis] is total wall-clock inside the backend. Note that sherpa-onnx
 * exposes decoding as one opaque call, so we cannot split encoder from decoder
 * time at this layer — and for judging felt latency, the total is the number
 * that matters anyway.
 */
data class AsrResult(
    val text: String,
    val language: String?,
    val decodeMillis: Long,
    /**
     * Raw ASR words the backend itself flagged as low-confidence (see
     * `WhisperCppEngine` / `jni_whisper.c`'s `fullTranscribeWithConfidence`),
     * in decode order. Empty for backends with no equivalent signal — this
     * is a quality nudge for the caller, not a correctness guarantee, and
     * words here are in whatever script/casing the backend emitted them in
     * (not necessarily [text]'s final form after any caller-side
     * post-processing).
     */
    val lowConfidenceWords: List<String> = emptyList(),
) {
    val isBlank: Boolean get() = text.isBlank()
}
