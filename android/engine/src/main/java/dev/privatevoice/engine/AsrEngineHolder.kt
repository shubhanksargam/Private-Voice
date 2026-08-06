package dev.privatevoice.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Process-wide owner of the loaded ASR model(s).
 *
 * Both entry points — the IME and the RecognitionService — live in one process
 * and must share engines. Loading costs a few hundred milliseconds and a few
 * hundred MB; doing it per invocation would put that on the critical path of
 * every single utterance, which is exactly the latency the user feels.
 *
 * Holds up to two engines at once, one per [Tier], loaded lazily and cached
 * independently: `base` is fast but proved unreliable on Hindi (translating
 * or Urdu-script-confusing it even with the language forced correctly —
 * see docs/STATUS.md), while `small` fixes that but is ~3x slower per
 * decode. Rather than pick one compromise for every utterance, the caller
 * says which tier it wants based on the language hint, and each tier's
 * engine stays warm across calls once loaded. A Hindi-only user pays
 * `small`'s cold-load once; an English-only user never loads it at all.
 *
 * Models live in internal storage rather than external: this device's
 * FUSE-mediated external storage hides files written by another UID from the
 * app itself. See docs/SETUP.md.
 */
object AsrEngineHolder {

    enum class Tier { BASE, SMALL }

    private const val TAG = "AsrEngineHolder"
    private const val MODELS_SUBDIR = "ggml"

    @Volatile
    private var baseEngine: AsrEngine? = null

    @Volatile
    private var smallEngine: AsrEngine? = null

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
     * Load the engine for [tier] if needed and return it, or null if no
     * suitable model is installed. Blocking and safe to call repeatedly;
     * callers should already be off the main thread.
     *
     * Falls back to whichever tier *is* installed if the requested one
     * isn't — e.g. a HI request on a device that only ever imported `base`
     * still gets a working (if less accurate) engine rather than nothing.
     */
    fun getOrLoad(context: Context, tier: Tier = Tier.BASE): AsrEngine? {
        cachedEngine(tier)?.let { return it }
        synchronized(this) {
            cachedEngine(tier)?.let { return it }

            val model = findModel(context, tier) ?: findModel(context, otherTier(tier)) ?: run {
                Log.w(TAG, "No model in ${modelsDir(context)}")
                return null
            }
            return try {
                WhisperCppEngine(
                    modelFile = model,
                    numThreads = preferredThreads(),
                    defaultLanguage = null, // auto-detect: this app dictates both English and Hindi
                ).also {
                    setCachedEngine(tier, it)
                    Log.i(TAG, "Loaded ${model.name} in ${it.loadMillis}ms for tier $tier")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load ${model.name}", t)
                null
            }
        }
    }

    private fun cachedEngine(tier: Tier): AsrEngine? = when (tier) {
        Tier.BASE -> baseEngine
        Tier.SMALL -> smallEngine
    }

    private fun setCachedEngine(tier: Tier, engine: AsrEngine) {
        when (tier) {
            Tier.BASE -> baseEngine = engine
            Tier.SMALL -> smallEngine = engine
        }
    }

    private fun otherTier(tier: Tier) = if (tier == Tier.BASE) Tier.SMALL else Tier.BASE

    /**
     * Matches GGML's own naming convention (`ggml-base-q8_0.bin`,
     * `ggml-small-q8_0.bin`, ...) rather than picking by file size, so the
     * tier a caller asks for is the tier it actually gets.
     */
    private fun findModel(context: Context, tier: Tier): File? {
        val keyword = when (tier) { Tier.BASE -> "base"; Tier.SMALL -> "small" }
        return installedModels(context).firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    /**
     * 4 threads. The M0 sweep measured 4 as consistently better than 2, and 6
     * as worse than either — the Exynos 1380's four little A55 cores cost more
     * than the parallelism they add. Clamped for devices with fewer cores.
     */
    private fun preferredThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Release both models. Called when the IME's process is going away. */
    fun release() {
        synchronized(this) {
            baseEngine?.let { runCatching { it.close() } }
            smallEngine?.let { runCatching { it.close() } }
            baseEngine = null
            smallEngine = null
        }
    }
}
