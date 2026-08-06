package dev.privatevoice.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * English -> Hindi text translation via a quantized `opus-mt-en-hi`
 * (Helsinki-NLP, Apache 2.0), exported to ONNX and verified end-to-end in
 * Python before this port (see tools/verify_onnx_final.py) — this class is
 * a direct Kotlin translation of that already-proven logic, not a fresh
 * design. Two non-obvious MarianMT architecture facts drove the model
 * export itself (see tools/build_onnx_tokenizer3.py for the full story):
 *
 * 1. The `.spm` SentencePiece model's own internal piece-ids do NOT match
 *    Marian's actual model vocabulary — a separate `vocab.json` remaps
 *    piece-strings to the real ids. [encoder] already has this baked in
 *    as a `Gather` node, so callers here never see raw SPM ids.
 * 2. MarianMT's *decoding* is not a real SentencePiece decode at all, just
 *    a `vocab.json` reverse lookup + "▁" -> " " join — which is why
 *    [detokenize] is plain string logic, no ONNX/SentencePiece involved.
 *
 * Not thread-safe, same contract as [WhisperCppEngine] — the caller must
 * confine use to one thread at a time (or hold a lock), and callers should
 * already be off the main thread given a translation involves a full
 * autoregressive decode loop.
 */
class EnglishToHindiTranslator(
    encoderFile: File,
    decoderFile: File,
    decoderWithPastFile: File,
    vocabFile: File,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val decoderPastSession: OrtSession
    private val idToPiece: Map<Long, String>

    init {
        // Only the encoder's graph has the fused SentencePieceTokenizer
        // custom op; the decoder graphs are plain token-id in/out, no
        // extensions needed there.
        val encoderOpts = OrtSession.SessionOptions()
        encoderOpts.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        encoderSession = env.createSession(encoderFile.absolutePath, encoderOpts)
        decoderSession = env.createSession(decoderFile.absolutePath, OrtSession.SessionOptions())
        decoderPastSession = env.createSession(decoderWithPastFile.absolutePath, OrtSession.SessionOptions())

        val vocab = JSONObject(vocabFile.readText())
        val map = HashMap<Long, String>(vocab.length() * 2)
        val keys = vocab.keys()
        while (keys.hasNext()) {
            val piece = keys.next()
            map[vocab.getLong(piece)] = piece
        }
        idToPiece = map
    }

    fun translate(text: String, maxLen: Int = 64): String {
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(text), longArrayOf(1, 1))
        val encoderResult = encoderSession.run(mapOf("input_text" to inputTensor))
        inputTensor.close()

        val hiddenStates = (encoderResult[0] as OnnxTensor)
        val hiddenShape = hiddenStates.info.shape // [1, seq, hidden]
        val seqLen = hiddenShape[1]
        val attnMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(seqLen.toInt()) { 1L }), longArrayOf(1, seqLen),
        )

        val generated = mutableListOf(DECODER_START_ID)
        var decoderKv: Map<String, OnnxTensor>? = null
        var encoderKv: Map<String, OnnxTensor>? = null

        try {
            for (step in 0 until maxLen) {
                val result: OrtSession.Result
                val idsTensor: OnnxTensor
                if (decoderKv == null) {
                    idsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(generated.toLongArray()),
                        longArrayOf(1, generated.size.toLong()),
                    )
                    result = decoderSession.run(
                        mapOf(
                            "input_ids" to idsTensor,
                            "encoder_attention_mask" to attnMaskTensor,
                            "encoder_hidden_states" to hiddenStates,
                        )
                    )
                } else {
                    idsTensor = OnnxTensor.createTensor(
                        env, LongBuffer.wrap(longArrayOf(generated.last())), longArrayOf(1, 1),
                    )
                    val feed = HashMap<String, OnnxTensor>()
                    feed["input_ids"] = idsTensor
                    feed["encoder_attention_mask"] = attnMaskTensor
                    feed.putAll(decoderKv)
                    feed.putAll(encoderKv!!)
                    result = decoderPastSession.run(feed)
                }
                idsTensor.close()

                // First output is always logits; the rest are present.*
                // (self-attention cache always, cross-attention cache only
                // on the first, no-past call — see class doc point 1's
                // sibling fact in docs/STATUS.md: the with-past decoder
                // doesn't re-emit encoder K/V since it never changes).
                var outIndex = 0
                var logitsTensor: OnnxTensor? = null
                val newKv = HashMap<String, OnnxTensor>()
                for ((name, value) in result) {
                    val tensor = value as OnnxTensor
                    if (outIndex == 0) {
                        logitsTensor = tensor
                    } else {
                        newKv[name.replace("present", "past_key_values")] = tensor
                    }
                    outIndex++
                }
                val logits = logitsTensor!!
                val nextId = argmaxLastPosition(logits)
                generated.add(nextId)

                decoderKv?.values?.forEach { it.close() }
                if (encoderKv == null) {
                    encoderKv = newKv.filterKeys { ".encoder." in it }
                    decoderKv = newKv.filterKeys { ".decoder." in it }
                } else {
                    decoderKv = newKv // with-past decoder only emits decoder.* keys
                }
                logits.close()

                if (nextId == EOS_ID) break
            }
        } finally {
            hiddenStates.close()
            attnMaskTensor.close()
            decoderKv?.values?.forEach { it.close() }
            encoderKv?.values?.forEach { it.close() }
        }

        return detokenize(generated)
    }

    /** Greedy argmax over the vocab dimension at the last sequence position. */
    private fun argmaxLastPosition(logits: OnnxTensor): Long {
        val shape = logits.info.shape // [1, seqSoFar, vocabSize]
        val vocabSize = shape[2].toInt()
        val lastPosOffset = (shape[1] - 1).toInt() * vocabSize
        val buf: FloatBuffer = logits.floatBuffer
        var bestId = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (v in 0 until vocabSize) {
            val value = buf.get(lastPosOffset + v)
            if (value > bestVal) {
                bestVal = value
                bestId = v
            }
        }
        return bestId.toLong()
    }

    /**
     * Not a SentencePiece decode — see class doc point 2. Just a reverse
     * vocab.json lookup per id, joined, with SentencePiece's "▁" word-start
     * marker turned back into a literal space.
     */
    private fun detokenize(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == DECODER_START_ID || id == EOS_ID) continue
            sb.append(idToPiece[id].orEmpty())
        }
        return sb.toString().replace('▁', ' ').trim()
    }

    override fun close() {
        encoderSession.close()
        decoderSession.close()
        decoderPastSession.close()
    }

    companion object {
        private const val DECODER_START_ID = 61949L
        private const val EOS_ID = 0L
    }
}
