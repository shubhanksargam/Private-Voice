package dev.privatevoice.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Process-wide owner of the loaded ASR model.
 *
 * Both entry points — the IME and the RecognitionService — live in one process
 * and must share a single engine. Loading costs a few hundred milliseconds and
 * a few hundred MB; doing it per invocation would put that on the critical path
 * of every single utterance, which is exactly the latency the user feels.
 *
 * Models live in internal storage rather than external: this device's
 * FUSE-mediated external storage hides files written by another UID from the
 * app itself. See docs/SETUP.md.
 */
object AsrEngineHolder {

    private const val TAG = "AsrEngineHolder"
    private const val MODELS_SUBDIR = "ggml"

    @Volatile
    private var engine: AsrEngine? = null

    /** Directory the app reads models from. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, MODELS_SUBDIR).apply { mkdirs() }

    /** Every GGML model currently installed, newest-looking first. */
    fun installedModels(context: Context): List<File> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun hasModel(context: Context): Boolean = installedModels(context).isNotEmpty()

    /**
     * Load the engine if needed and return it, or null if no model is
     * installed. Blocking and safe to call repeatedly; callers should already
     * be off the main thread.
     */
    fun getOrLoad(context: Context): AsrEngine? {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }

            val model = pickModel(context) ?: run {
                Log.w(TAG, "No model in ${modelsDir(context)}")
                return null
            }
            return try {
                WhisperCppEngine(
                    modelFile = model,
                    numThreads = preferredThreads(),
                    defaultLanguage = "en",
                ).also {
                    engine = it
                    Log.i(TAG, "Loaded ${model.name} in ${it.loadMillis}ms")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load ${model.name}", t)
                null
            }
        }
    }

    /**
     * Prefer the largest installed model. Bigger is consistently more accurate
     * here, and the M0 sweep showed the practical ceiling is set by latency
     * rather than by choosing badly among what is installed — so provisioning
     * decides the trade-off, not this.
     */
    private fun pickModel(context: Context): File? =
        installedModels(context).maxByOrNull { it.length() }

    /**
     * 4 threads. The M0 sweep measured 4 as consistently better than 2, and 6
     * as worse than either — the Exynos 1380's four little A55 cores cost more
     * than the parallelism they add. Clamped for devices with fewer cores.
     */
    private fun preferredThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Release the model. Called when the IME's process is going away. */
    fun release() {
        synchronized(this) {
            engine?.let { runCatching { it.close() } }
            engine = null
        }
    }
}
