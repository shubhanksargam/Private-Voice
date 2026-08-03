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
 * Expects, under the app's external files dir (adb-pushable, no permission needed):
 *   files/models/<model-name>/{*-encoder*.onnx, *-decoder*.onnx, *-tokens.txt}
 *   files/eval/*.wav        16kHz mono 16-bit
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

        val base = getExternalFilesDir(null) ?: filesDir
        val modelsDir = File(base, "models").apply { mkdirs() }
        val audioDir = File(base, "eval").apply { mkdirs() }
        val outFile = File(base, "benchmark.json")

        // Thread sweep covers "big cores only" (4) and over-subscription (6).
        // The Exynos 1380 pairs 4xA78 with 4xA55; scheduling onto the little
        // cores usually costs more than the extra parallelism returns, and this
        // is the cheapest way to confirm that rather than assume it.
        val threadCounts = listOf(2, 4, 6)

        lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                BenchmarkRunner(
                    modelsDir = modelsDir,
                    audioDir = audioDir,
                    threadCounts = threadCounts,
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
