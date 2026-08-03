package dev.privatevoice.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for benchmark and test fixtures.
 *
 * Handles 16-bit PCM mono only, which is what every model here consumes and what
 * AudioRecord produces. It walks the chunk list rather than assuming `fmt ` is
 * immediately followed by `data`, because writers routinely interleave LIST/INFO
 * chunks and a fixed-offset reader silently produces noise when they do.
 */
object WavIo {

    data class Wav(val samples: FloatArray, val sampleRate: Int) {
        val durationSeconds: Float get() = samples.size / sampleRate.toFloat()

        // Value semantics on a FloatArray need explicit equals/hashCode.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Wav) return false
            return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
        }

        override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
    }

    fun read(file: File): Wav = read(file.readBytes())

    fun read(bytes: ByteArray): Wav {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44) { "Not a WAV file: too short (${bytes.size} bytes)" }
        require(tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a RIFF/WAVE file" }

        var sampleRate = -1
        var channels = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataLength = -1

        // Chunks start at byte 12, each with an 8-byte header and word-aligned payload.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = tag(buf, pos)
            val chunkSize = buf.getInt(pos + 4)
            val body = pos + 8
            if (chunkSize < 0 || body + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = chunkSize
                }
            }
            pos = body + chunkSize + (chunkSize and 1) // pad byte on odd sizes
        }

        require(dataOffset >= 0) { "WAV has no data chunk" }
        require(bitsPerSample == 16) { "Only 16-bit PCM supported; got $bitsPerSample-bit" }
        require(channels == 1) { "Only mono supported; got $channels channels" }

        val count = dataLength / 2
        val samples = FloatArray(count)
        for (i in 0 until count) {
            samples[i] = buf.getShort(dataOffset + i * 2) / 32768.0f
        }
        return Wav(samples, sampleRate)
    }

    private fun tag(buf: ByteBuffer, offset: Int): String =
        String(ByteArray(4) { buf.get(offset + it) }, Charsets.US_ASCII)
}
