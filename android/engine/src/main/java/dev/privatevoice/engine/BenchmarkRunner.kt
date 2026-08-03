package dev.privatevoice.engine

import android.os.Debug
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * M0 — the device feasibility gate.
 *
 * The whole project rests on one unknown: can a Samsung A35 (Exynos 1380, no
 * accessible NPU, so pure CPU inference) run a Whisper model that is accurate
 * enough to be worth using, fast enough to not feel broken? Nothing above this
 * layer is worth building until that is answered with real numbers.
 *
 * Sweeps {model directory} x {precision} x {thread count} over a corpus of WAVs
 * and reports wall-clock per utterance plus peak PSS.
 *
 * GATE: pick the largest model whose median total stays under [TARGET_MILLIS].
 */
class BenchmarkRunner(
    private val modelsDir: File,
    private val audioDir: File,
    private val threadCounts: List<Int> = listOf(2, 4, 6),
    private val repeats: Int = 3,
    private val language: String? = null,
) {

    fun interface Progress {
        fun onLine(text: String)
    }

    /**
     * @return the benchmark report as JSON. Also written to disk by the caller so
     *   tools/bench_device.py can pull and tabulate it.
     */
    fun run(progress: Progress = Progress { }): JSONObject {
        val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        val wavs = audioDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        if (modelDirs.isEmpty()) {
            progress.onLine("No models in ${modelsDir.absolutePath}")
            progress.onLine("Run: python tools/fetch_models.py --push")
        }
        if (wavs.isEmpty()) {
            progress.onLine("No .wav files in ${audioDir.absolutePath}")
            progress.onLine("Record some Hindi/English/Hinglish samples first.")
        }

        val results = JSONArray()

        for (modelDir in modelDirs) {
            for (preferInt8 in listOf(true, false)) {
                for (threads in threadCounts) {
                    val spec = ModelSpec.discover(modelDir, threads, preferInt8) ?: continue
                    // discover() falls back to whichever precision exists, so the
                    // int8/full loop can yield the same spec twice. Skip the repeat.
                    if (results.alreadyHas(spec.id)) continue

                    progress.onLine("── ${spec.id}")
                    val entry = benchmarkOne(spec, wavs, progress) ?: continue
                    results.put(entry)
                }
            }
        }

        return JSONObject().apply {
            put("device", JSONObject().apply {
                put("model", android.os.Build.MODEL)
                put("soc", socModel())
                put("cpus", Runtime.getRuntime().availableProcessors())
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
            put("targetMillis", TARGET_MILLIS)
            put("results", results)
        }
    }

    private fun benchmarkOne(spec: ModelSpec, wavs: List<File>, progress: Progress): JSONObject? {
        val engine = try {
            SherpaWhisperEngine(spec, defaultLanguage = language ?: "en")
        } catch (t: Throwable) {
            // A model that fails to load is a data point, not a crash. Record and move on.
            Log.e(TAG, "Failed to load ${spec.id}", t)
            progress.onLine("   load FAILED: ${t.message}")
            return JSONObject().apply {
                put("id", spec.id)
                put("error", t.message ?: t::class.java.simpleName)
            }
        }

        return engine.use {
            it.warmUp()

            val perUtterance = JSONArray()
            val allTimings = mutableListOf<Long>()

            for (wav in wavs) {
                val audio = try {
                    WavIo.read(wav)
                } catch (t: Throwable) {
                    progress.onLine("   skip ${wav.name}: ${t.message}")
                    continue
                }

                val timings = mutableListOf<Long>()
                var text = ""
                repeat(repeats) {
                    val result = engine.transcribe(audio.samples, audio.sampleRate, language)
                    timings += result.decodeMillis
                    text = result.text
                }
                allTimings += timings

                val median = timings.median()
                // Real-time factor < 1.0 means faster than the audio it transcribed.
                val rtf = median / 1000.0 / audio.durationSeconds
                progress.onLine("   ${wav.name}  ${median}ms  rtf=%.2f".format(rtf))
                progress.onLine("      \"$text\"")

                perUtterance.put(JSONObject().apply {
                    put("file", wav.name)
                    put("durationSec", audio.durationSeconds)
                    put("medianMillis", median)
                    put("rtf", rtf)
                    put("text", text)
                })
            }

            val overall = allTimings.median()
            val verdict = when {
                allTimings.isEmpty() -> "no-data"
                overall <= TARGET_MILLIS -> "PASS"
                else -> "too-slow"
            }
            progress.onLine("   => median ${overall}ms  [$verdict]")

            JSONObject().apply {
                put("id", spec.id)
                put("encoder", spec.encoder.name)
                put("numThreads", spec.numThreads)
                put("sizeMB", spec.bytes / 1024 / 1024)
                put("loadMillis", engine.loadMillis)
                put("peakPssMB", Debug.getPss() / 1024)
                put("medianMillis", overall)
                put("verdict", verdict)
                put("utterances", perUtterance)
            }
        }
    }

    private fun List<Long>.median(): Long {
        if (isEmpty()) return -1
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    private fun JSONArray.alreadyHas(id: String): Boolean =
        (0 until length()).any { optJSONObject(it)?.optString("id") == id }

    companion object {
        private const val TAG = "BenchmarkRunner"

        /** Build.SOC_MODEL is API 31+; minSdk here is 26, so fall back to HARDWARE. */
        fun socModel(): String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.os.Build.SOC_MODEL
            } else {
                android.os.Build.HARDWARE
            }

        /**
         * Felt-latency budget for a hold-to-talk release. Beyond roughly this,
         * dictation stops feeling like typing and starts feeling like waiting.
         */
        const val TARGET_MILLIS = 2_500L
    }
}
