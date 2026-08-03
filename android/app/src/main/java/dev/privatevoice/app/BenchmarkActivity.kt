package dev.privatevoice.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.privatevoice.app.databinding.ActivityBenchmarkBinding
import dev.privatevoice.engine.BenchmarkRunner
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
    }
}
