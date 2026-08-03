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
) {
    val isBlank: Boolean get() = text.isBlank()
}
