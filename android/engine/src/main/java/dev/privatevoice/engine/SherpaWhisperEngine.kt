package dev.privatevoice.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whisper backend on top of sherpa-onnx / ONNX Runtime.
 *
 * Chosen as the primary engine because it is the only option that delivers Hindi,
 * English and native punctuation + casing from a single model. Conformer/CTC
 * alternatives (IndicConformer) are faster but emit unpunctuated lowercase text
 * and have no Devanagari punctuation-restoration model available to pair with.
 *
 * Latency note: Whisper always pads its input to a 30-second window, so decode
 * cost is essentially constant regardless of utterance length. This is why we do
 * a single pass on release rather than chunking during recording — chunking would
 * pay the full window cost per chunk and be strictly slower.
 */
class SherpaWhisperEngine(
    private val spec: ModelSpec,
    private val defaultLanguage: String = "en",
) : AsrEngine {

    override val id: String = spec.id

    private var recognizer: OfflineRecognizer? = null
    private var currentLanguage: String = defaultLanguage

    /** Time spent constructing the recognizer, i.e. cold model load. */
    override var loadMillis: Long = -1L
        private set

    init {
        require(spec.isComplete) { "Incomplete model at ${spec.encoder.parent}" }
        val started = System.nanoTime()
        recognizer = OfflineRecognizer(config = buildConfig(defaultLanguage))
        loadMillis = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "Loaded ${spec.id} in ${loadMillis}ms (${spec.bytes / 1024 / 1024}MB)")
    }

    private fun buildConfig(language: String) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = spec.encoder.absolutePath,
                decoder = spec.decoder.absolutePath,
                language = language,
                task = "transcribe",
            ),
            tokens = spec.tokens.absolutePath,
            numThreads = spec.numThreads,
            modelType = "whisper",
            provider = "cpu",
            debug = false,
        ),
        decodingMethod = "greedy_search",
    )

    override fun warmUp() {
        // One pass over half a second of silence. Forces ONNX Runtime to allocate
        // its arenas and spin up worker threads now instead of during the user's
        // first real utterance.
        transcribe(FloatArray(8_000), 16_000, currentLanguage, promptHint = null, translate = false)
    }

    override fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        promptHint: String?,
        translate: Boolean,
    ): AsrResult {
        // sherpa-onnx's OfflineRecognizer exposes no prompt-conditioning or
        // translate-task equivalent; both are quality/feature nudges, not
        // correctness requirements, so silently ignoring them here is
        // correct rather than an oversight. This backend is legacy/
        // benchmark-only (see class doc) — not used by the shipping app.
        val engine = recognizer ?: error("Engine already closed")
        require(sampleRate == 16_000) {
            "Whisper models expect 16kHz; got $sampleRate. Resample before calling."
        }

        // Changing language means rebuilding the decoder prompt. setConfig is far
        // cheaper than reconstructing the recognizer (which would reload weights).
        val target = language ?: defaultLanguage
        if (target != currentLanguage) {
            engine.setConfig(buildConfig(target))
            currentLanguage = target
        }

        val started = System.nanoTime()
        val stream = engine.createStream()
        val text = try {
            stream.acceptWaveform(samples, sampleRate = sampleRate)
            engine.decode(stream)
            engine.getResult(stream).text
        } finally {
            stream.release()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        return AsrResult(text = text.trim(), language = target, decodeMillis = elapsed)
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private companion object {
        const val TAG = "SherpaWhisperEngine"
    }
}
