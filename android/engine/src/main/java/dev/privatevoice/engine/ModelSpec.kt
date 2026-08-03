package dev.privatevoice.engine

import java.io.File

/**
 * A Whisper model laid out on disk in the shape sherpa-onnx expects.
 *
 * Models are never bundled in the APK and never downloaded by the app — the app
 * holds no INTERNET permission. They are placed on the device by
 * tools/fetch_models.py (during development) or imported by the user through a
 * SAF file picker (in the shipping build).
 *
 * Expected directory contents:
 *   <dir>/<prefix>-encoder[.int8].onnx
 *   <dir>/<prefix>-decoder[.int8].onnx
 *   <dir>/<prefix>-tokens.txt
 */
data class ModelSpec(
    val id: String,
    val encoder: File,
    val decoder: File,
    val tokens: File,
    val numThreads: Int = 4,
) {
    val isComplete: Boolean
        get() = encoder.isFile && decoder.isFile && tokens.isFile

    /** Total on-disk weight, useful for correlating latency with model size. */
    val bytes: Long
        get() = encoder.length() + decoder.length() + tokens.length()

    companion object {
        /**
         * Discover a model inside [dir] by pattern rather than fixed filenames,
         * because sherpa-onnx's published archives vary the prefix per model
         * ("tiny-encoder.onnx", "base-encoder.int8.onnx", and so on).
         *
         * Prefers int8 weights when both precisions are present; pass
         * [preferInt8] = false to force the fp32/fp16 pair.
         */
        fun discover(dir: File, numThreads: Int = 4, preferInt8: Boolean = true): ModelSpec? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles()?.toList().orEmpty()

            fun pick(role: String): File? {
                val candidates = files.filter {
                    it.name.endsWith(".onnx") && it.name.contains("$role")
                }
                if (candidates.isEmpty()) return null
                val int8 = candidates.filter { it.name.contains(".int8.") }
                val full = candidates.filterNot { it.name.contains(".int8.") }
                return when {
                    preferInt8 && int8.isNotEmpty() -> int8.first()
                    full.isNotEmpty() -> full.first()
                    else -> candidates.first()
                }
            }

            val encoder = pick("encoder") ?: return null
            val decoder = pick("decoder") ?: return null
            val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") } ?: return null

            val precision = if (encoder.name.contains(".int8.")) "int8" else "full"
            return ModelSpec(
                id = "${dir.name}-$precision-t$numThreads",
                encoder = encoder,
                decoder = decoder,
                tokens = tokens,
                numThreads = numThreads,
            )
        }
    }
}
