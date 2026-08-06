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
     * @param translate if true, ask Whisper's translate task for an English
     *   rendering of whatever language was spoken, instead of transcribing
     *   it in its own script/language. Forcing `language="en"` with this
     *   left false on non-English audio is a mismatched instruction (decode
     *   as English text when the audio isn't English) and produces garbled
     *   output, not a translation — pair this with [language]`= null` (let
     *   the model detect the actual source language) rather than forcing
     *   "en" alongside it.
     */
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        language: String? = null,
        promptHint: String? = null,
        translate: Boolean = false,
    ): AsrResult

    /**
     * Cheap language identification — an encoder pass and a single decode
     * step, not a full transcription. Returns null if unsupported or
     * detection failed. Used to route AUTO-mode dictation to the right
     * model/tier without paying a full decode on the wrong one first.
     * Default is unsupported.
     */
    fun detectLanguage(samples: FloatArray, sampleRate: Int = 16_000): LanguageDetection? = null

    /**
     * Ask an in-flight [transcribe] call to stop early. Safe to call from
     * any thread — implementations must not route this through whatever
     * confines their decode work, since that would just queue it behind the
     * call it's meant to interrupt. Default is a no-op for backends with no
     * such lever; [transcribe] returning is still guaranteed either way, a
     * cancel request just isn't guaranteed to make that happen sooner.
     */
    fun cancel() {}
}

/**
 * Result of [AsrEngine.detectLanguage]. [englishProb]/[hindiProb] are
 * exposed alongside [topLanguage] specifically so callers can spot
 * code-switched (Hinglish) audio — whisper's language set has no such
 * category, so it can only ever report one top language, but a Hindi
 * probability that's meaningfully non-trivial even when English wins the
 * top slot is a good proxy for "there's real Hindi content in here too."
 */
data class LanguageDetection(
    val topLanguage: String,
    val englishProb: Float,
    val hindiProb: Float,
)
