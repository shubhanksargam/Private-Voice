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
    /**
     * Directory of GGML `.bin` weights for the whisper.cpp backend. Separate
     * from [modelsDir] because the two backends use incompatible layouts: ONNX
     * needs an encoder/decoder/tokens triple per subdirectory, GGML is one file.
     */
    private val ggmlDir: File? = null,
    private val threadCounts: List<Int> = listOf(2, 4),
    private val repeats: Int = 3,
    /** Overrides per-file language detection for every WAV. Leave null for the
     *  normal case: language is derived per file from its eval-corpus prefix
     *  (en_/hi_/mix_), since a fixed language forces Whisper to decode Hindi
     *  speech as English — it doesn't transcribe, it mistranslates. */
    private val languageOverride: String? = null,
    /** Test the full-precision (fp32) weights alongside int8. Off by default:
     *  fp32-on-phone-CPU runs 3-5x slower than int8 for no accuracy most
     *  shipping decisions would trade for, so it burns hours of device time
     *  without changing which model gets chosen. */
    private val includeFullPrecision: Boolean = false,
    /** Written after every completed config, not just at the end, so killing
     *  the app mid-sweep (or a crash) never loses configs already measured. */
    private val outFile: File? = null,
) {

    fun interface Progress {
        fun onLine(text: String)
    }

    /**
     * @return the benchmark report as JSON. Also incrementally written to
     *   [outFile] if provided, so tools/bench_device.py can pull partial
     *   results even from an interrupted run.
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
        val precisions = if (includeFullPrecision) listOf(true, false) else listOf(true)

        fun report() = JSONObject().apply {
            put("device", JSONObject().apply {
                put("model", android.os.Build.MODEL)
                put("soc", socModel())
                put("cpus", Runtime.getRuntime().availableProcessors())
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
            put("targetMillis", TARGET_MILLIS)
            put("results", results)
        }

        // Build one flat candidate list so both backends sweep identically and
        // land in the same report, directly comparable.
        val candidates = mutableListOf<Candidate>()

        for (modelDir in modelDirs) {
            for (preferInt8 in precisions) {
                for (threads in threadCounts) {
                    val spec = ModelSpec.discover(modelDir, threads, preferInt8) ?: continue
                    // discover() falls back to whichever precision exists, so the
                    // int8/full loop can yield the same spec twice. Skip the repeat.
                    if (candidates.any { it.id == spec.id }) continue
                    candidates += Candidate(
                        id = spec.id,
                        backend = "sherpa-onnx",
                        sizeMB = spec.bytes / 1024 / 1024,
                        numThreads = threads,
                    ) { SherpaWhisperEngine(spec, defaultLanguage = languageOverride ?: "en") }
                }
            }
        }

        if (ggmlDir != null && ggmlDir.isDirectory) {
            for (threads in threadCounts) {
                for (spec in WhisperCppEngine.discover(ggmlDir, threads)) {
                    if (candidates.any { it.id == spec.id }) continue
                    candidates += Candidate(
                        id = spec.id,
                        backend = "whisper.cpp",
                        sizeMB = spec.bytes / 1024 / 1024,
                        numThreads = threads,
                    ) {
                        WhisperCppEngine(
                            modelFile = spec.modelFile,
                            numThreads = spec.numThreads,
                            id = spec.id,
                            defaultLanguage = languageOverride ?: "en",
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            progress.onLine("No usable models found.")
        }

        for (c in candidates) {
            progress.onLine("── ${c.id}  [${c.backend}]")
            val entry = benchmarkOne(c, wavs, progress) ?: continue
            results.put(entry)
            outFile?.writeText(report().toString(2))
        }

        return report()
    }

    /** One model/backend/thread-count combination to measure. */
    private class Candidate(
        val id: String,
        val backend: String,
        val sizeMB: Long,
        val numThreads: Int,
        val create: () -> AsrEngine,
    )

    private fun benchmarkOne(c: Candidate, wavs: List<File>, progress: Progress): JSONObject? {
        val engine = try {
            c.create()
        } catch (t: Throwable) {
            // A model that fails to load is a data point, not a crash. Record and move on.
            Log.e(TAG, "Failed to load ${c.id}", t)
            progress.onLine("   load FAILED: ${t.message}")
            return JSONObject().apply {
                put("id", c.id)
                put("backend", c.backend)
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

                val lang = languageOverride ?: languageForFile(wav.name)
                val timings = mutableListOf<Long>()
                var text = ""
                repeat(repeats) {
                    val result = engine.transcribe(audio.samples, audio.sampleRate, lang)
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
                    put("language", lang)
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
                put("id", c.id)
                put("backend", c.backend)
                put("numThreads", c.numThreads)
                put("sizeMB", c.sizeMB)
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

    /**
     * Maps the eval corpus's naming convention (en_/hi_/mix_, see
     * eval/prompts.jsonl) to a Whisper language tag. Whisper has no
     * "code-switched" tag, so mix_ (Hinglish) uses "hi" — the closer of the two
     * available options, since its Hindi decoder tolerates embedded English
     * words far better than its English decoder tolerates Hindi ones.
     */
    private fun languageForFile(name: String): String = when {
        name.startsWith("hi_") -> "hi"
        name.startsWith("mix_") -> "hi"
        else -> "en"
    }

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
