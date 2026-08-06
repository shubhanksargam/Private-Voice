package dev.privatevoice.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Process-wide owner of the loaded [EnglishToHindiTranslator], same
 * reasoning as [AsrEngineHolder]: expensive to construct (three ONNX
 * sessions), so load once and hold warm rather than per-utterance.
 *
 * Models live in internal storage under `files/mt/`, parallel to
 * `files/ggml/` for the whisper models — see [AsrEngineHolder] for why
 * internal (not external/shared) storage.
 */
object TranslationEngineHolder {

    private const val TAG = "TranslationEngineHolder"
    private const val MODELS_SUBDIR = "mt"

    private const val ENCODER_FILE = "encoder_with_tokenizer.onnx"
    private const val DECODER_FILE = "decoder_model.onnx"
    private const val DECODER_PAST_FILE = "decoder_with_past_model.onnx"
    private const val VOCAB_FILE = "vocab.json"

    @Volatile
    private var translator: EnglishToHindiTranslator? = null

    fun modelsDir(context: Context): File =
        File(context.filesDir, MODELS_SUBDIR).apply { mkdirs() }

    fun hasModel(context: Context): Boolean {
        val dir = modelsDir(context)
        return listOf(ENCODER_FILE, DECODER_FILE, DECODER_PAST_FILE, VOCAB_FILE)
            .all { File(dir, it).isFile }
    }

    /**
     * Load the translator if needed and return it, or null if the model
     * files aren't installed. Blocking (three ONNX session constructions);
     * callers should already be off the main thread.
     */
    fun getOrLoad(context: Context): EnglishToHindiTranslator? {
        translator?.let { return it }
        synchronized(this) {
            translator?.let { return it }
            if (!hasModel(context)) {
                Log.w(TAG, "No translation model in ${modelsDir(context)}")
                return null
            }
            val dir = modelsDir(context)
            return try {
                EnglishToHindiTranslator(
                    encoderFile = File(dir, ENCODER_FILE),
                    decoderFile = File(dir, DECODER_FILE),
                    decoderWithPastFile = File(dir, DECODER_PAST_FILE),
                    vocabFile = File(dir, VOCAB_FILE),
                ).also {
                    translator = it
                    Log.i(TAG, "Loaded EnglishToHindiTranslator")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load translation model", t)
                null
            }
        }
    }

    fun release() {
        synchronized(this) {
            translator?.let { runCatching { it.close() } }
            translator = null
        }
    }
}
