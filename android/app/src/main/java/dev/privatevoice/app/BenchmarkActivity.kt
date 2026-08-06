package dev.privatevoice.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.privatevoice.app.databinding.ActivityBenchmarkBinding
import dev.privatevoice.engine.BenchmarkRunner
import dev.privatevoice.engine.TranslationEngineHolder
import dev.privatevoice.engine.WavIo
import dev.privatevoice.engine.WhisperCppEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * M0 harness. Deliberately ugly — it exists to produce numbers, not to be a UI.
 *
 * Expects, under the app's INTERNAL storage (filesDir, not external):
 *   files/models/<model-name>/{encoder, decoder, tokens}.onnx-or-txt
 *   files/eval/ - WAV files, 16kHz mono 16-bit
 *
 * Internal, not external: this device's FUSE-mediated external/shared storage
 * (both the app's own Android/data/ dir and public Download/) blocks reads from
 * a different UID even via `run-as` — plain `adb push` into Android/data/<pkg>
 * leaves files invisible to the app's own File.listFiles(). Internal storage
 * is a regular Linux directory with plain UNIX permissions, so `run-as <pkg> cp
 * ...` reliably lands files where the app can actually see them. See
 * docs/SETUP.md for the staging recipe.
 *
 * Populate both with: python tools/fetch_models.py --push
 * Drive headlessly and tabulate with: python tools/bench_device.py
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBenchmarkBinding
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.runButton.setOnClickListener { startBenchmark() }

        val autoRun = intent?.getBooleanExtra(EXTRA_AUTORUN, false) ?: false
        if (autoRun) startBenchmark()

        val testTranslation = intent?.getBooleanExtra(EXTRA_TEST_TRANSLATION, false) ?: false
        if (testTranslation) startTranslationSmokeTest()

        val testConfidence = intent?.getBooleanExtra(EXTRA_TEST_CONFIDENCE, false) ?: false
        if (testConfidence) startConfidenceSmokeTest()
    }

    /**
     * Headless smoke test for the new fullTranscribeWithConfidence JNI path
     * (jni_whisper.c) against real recorded speech (the M0 eval corpus's
     * WAV files under files/eval/) rather than synthetic audio — this exercises the
     * actual token-walk/word-grouping C code and the JNI String[] boundary,
     * which nothing else on-device does yet. Uses `base` directly rather
     * than going through AsrEngineHolder/VoiceImeService, so a failure here
     * points straight at the new native code, not at routing logic.
     */
    private fun startConfidenceSmokeTest() {
        binding.runButton.isEnabled = false
        log.clear()
        append("Confidence smoke test")

        val modelFile = File(File(filesDir, "ggml"), "ggml-base-q8_0.bin")
        val wavs = File(filesDir, "eval").listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.take(5)
            .orEmpty()
        append("Model: ${modelFile.absolutePath} (exists=${modelFile.isFile})")
        append("WAVs: ${wavs.size}")
        append("")

        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                if (!modelFile.isFile || wavs.isEmpty()) {
                    lifecycleScope.launch { append("FAILED: missing model or eval audio") }
                    Log.e(TAG, "CONFIDENCE_TEST_COMPLETE FAILED missing-input")
                    return@withContext
                }
                val engine = runCatching { WhisperCppEngine(modelFile) }.getOrElse {
                    lifecycleScope.launch { append("FAILED to load engine: ${it.message}") }
                    Log.e(TAG, "CONFIDENCE_TEST_COMPLETE FAILED load", it)
                    return@withContext
                }
                try {
                    for (wavFile in wavs) {
                        val wav = runCatching { WavIo.read(wavFile) }
                        val result = wav.mapCatching { engine.transcribe(it.samples, it.sampleRate) }
                        val line = result.fold(
                            onSuccess = { r ->
                                "${wavFile.name}: \"${r.text}\" lowConf=${r.lowConfidenceWords}"
                            },
                            onFailure = { "${wavFile.name}: ERROR ${it.message}" },
                        )
                        Log.i(TAG, line)
                        lifecycleScope.launch { append(line) }
                    }
                } finally {
                    engine.close()
                }
            }
            append("Done.")
            Log.i(TAG, "CONFIDENCE_TEST_COMPLETE")
            binding.runButton.isEnabled = true
        }
    }

    /**
     * Headless smoke test for EnglishToHindiTranslator on the real device
     * Java/JNI stack (custom-op registration, tensor creation, KV-cache
     * loop) — none of that was exercised by the Python verification this
     * class was ported from. No mic/audio involved, unlike the full
     * VoiceImeService path: this drives the MT model directly with fixed
     * English strings so it's triggerable over adb without real speech.
     */
    private fun startTranslationSmokeTest() {
        binding.runButton.isEnabled = false
        log.clear()
        append("Translation smoke test")
        append("Model present: ${TranslationEngineHolder.hasModel(this)}")
        append("")

        val samples = listOf(
            "How are you?",
            "I am going to the market",
            "What time is the meeting",
            "Where is the nearest hospital",
        )

        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                val translator = TranslationEngineHolder.getOrLoad(this@BenchmarkActivity)
                if (translator == null) {
                    lifecycleScope.launch { append("FAILED: could not load translator") }
                    Log.e(TAG, "TRANSLATION_TEST_COMPLETE FAILED load")
                    return@withContext
                }
                for (s in samples) {
                    val result = runCatching { translator.translate(s) }
                    lifecycleScope.launch {
                        append("EN: $s")
                        append("HI: ${result.getOrElse { "ERROR: ${it.message}" }}")
                        append("")
                    }
                    Log.i(TAG, "EN: $s -> HI: ${result.getOrElse { "ERROR: ${it.message}" }}")
                }
            }
            append("Done.")
            // bench_device.py-style marker for a headless `adb shell am start` +
            // logcat grep drive, matching BENCHMARK_COMPLETE's convention.
            Log.i(TAG, "TRANSLATION_TEST_COMPLETE")
            binding.runButton.isEnabled = true
        }
    }

    private fun startBenchmark() {
        binding.runButton.isEnabled = false
        log.clear()
        append("Device: ${android.os.Build.MODEL} (${BenchmarkRunner.socModel()})")
        append("Cores: ${Runtime.getRuntime().availableProcessors()}")
        append("Budget: ${BenchmarkRunner.TARGET_MILLIS}ms per utterance")
        append("")

        val base = filesDir
        val modelsDir = File(base, "models").apply { mkdirs() }
        // GGML weights for the whisper.cpp backend — one .bin per model, vs
        // the ONNX layout's encoder/decoder/tokens triple per subdirectory.
        val ggmlDir = File(base, "ggml").apply { mkdirs() }
        val audioDir = File(base, "eval").apply { mkdirs() }
        val outFile = File(base, "benchmark.json")

        // 4 threads only. 6 measured worse than 2 or 4 across the board (the
        // Exynos 1380's four little A55 cores cost more than their added
        // parallelism returns), and 4 consistently beat 2 on every model tried
        // since. Sweeping the already-answered options just spends device
        // minutes; override here if that assumption needs re-testing.
        val threadCounts = listOf(4)

        lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                BenchmarkRunner(
                    modelsDir = modelsDir,
                    audioDir = audioDir,
                    ggmlDir = ggmlDir,
                    threadCounts = threadCounts,
                    includeFullPrecision = false,
                    outFile = outFile,
                ).run { line ->
                    Log.i(TAG, line)
                    lifecycleScope.launch { append(line) }
                }
            }

            withContext(Dispatchers.IO) {
                outFile.writeText(report.toString(2))
            }
            append("")
            append("Wrote ${outFile.absolutePath}")
            // bench_device.py greps for this marker to know the run finished.
            Log.i(TAG, "BENCHMARK_COMPLETE ${outFile.absolutePath}")
            binding.runButton.isEnabled = true
        }
    }

    private fun append(line: String) {
        log.appendLine(line)
        binding.output.text = log
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "M0Benchmark"
        const val EXTRA_AUTORUN = "autorun"
        const val EXTRA_TEST_TRANSLATION = "test_translation"
        const val EXTRA_TEST_CONFIDENCE = "test_confidence"
    }
}
