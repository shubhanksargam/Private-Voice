package dev.privatevoice.engine

/**
 * A speech-to-text backend.
 *
 * This interface exists from day one because M5 (the evaluation milestone) is
 * fundamentally about swapping models and backends. Keeping the boundary narrow
 * is what makes that cheap: everything above this line — the IME, the
 * RecognitionService, the text post-processor — is backend-agnostic.
 *
 * Implementations are NOT thread-safe. Own one per worker thread, or serialise
 * access. They are expensive to construct (seconds of cold load), so hold them
 * warm across utterances rather than rebuilding per invocation.
 */
interface AsrEngine : AutoCloseable {

    /** Stable identifier used in benchmark output, e.g. "whisper-base-int8". */
    val id: String

    /**
     * Wall-clock milliseconds spent loading the model, or -1 if not measured.
     * Cold load runs to seconds for larger models, so it is reported separately
     * rather than folded into per-utterance latency.
     */
    val loadMillis: Long get() = -1L

    /**
     * Run any first-inference work up front (graph allocation, thread pool spin-up,
     * lazy weight paging). Calling this off the critical path keeps the first real
     * utterance from paying a one-off cost the user would read as a stutter.
     */
    fun warmUp()

    /**
     * Transcribe a complete utterance.
     *
     * @param samples mono PCM in [-1.0, 1.0]
     * @param sampleRate must be 16000 for every model we currently ship
     * @param language BCP-47-ish hint ("en", "hi") or null to let the model decide.
     *   Whisper's auto-detect operates on the first 30s window; for short
     *   code-switched utterances an explicit hint is usually more reliable.
     * @param promptHint optional vocabulary hint — text conditioning that
     *   biases the decoder toward particular words on ambiguous audio (e.g.
     *   preferring "WhatsApp" over the near-homophone "what's up"). Backend
     *   support varies; a backend with no equivalent lever ignores it rather
     *   than erroring, since this is a quality nudge, not a correctness
     *   requirement.
     */
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        language: String? = null,
        promptHint: String? = null,
    ): AsrResult
}
