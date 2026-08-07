package dev.privatevoice.app

import android.content.Context
import android.content.res.Configuration

/**
 * User-facing keyboard preferences — the "similar to Google or Samsung
 * keyboards" settings surface: haptic feedback and a theme override. Reached
 * via long-press on the text keyboard's ?123/ABC key, or through Android's
 * own Settings > Language & input > Private Voice entry (both point at
 * [SetupActivity], which renders this alongside first-run setup).
 *
 * Deliberately small. Autocorrect/dictionary/gesture-typing settings don't
 * exist here because those features don't exist in [TextKeyboardView] —
 * there's nothing to configure that isn't already covered.
 */
object KeyboardSettings {

    /** BLACK is a pure-AMOLED-black variant of DARK — see [isDark]/[isPureBlack]. */
    enum class Theme { SYSTEM, LIGHT, DARK, BLACK }

    /**
     * AUTO lets whisper.cpp's language auto-detect decide per utterance —
     * fine for clean English, but auto-detect runs once over the first
     * window and a small model's language-ID is not that reliable, so a
     * Hindi utterance can get misdetected as English and then decoded (not
     * translated — the decoder just runs believing it's English) into
     * something that reads like a translation. ENGLISH/HINDI force the
     * language token explicitly, removing that failure mode for whichever
     * language the user knows they're about to speak.
     */
    enum class LanguageHint { AUTO, ENGLISH, HINDI }

    private const val PREFS = "privatevoice_prefs"
    private const val KEY_HAPTIC = "haptic_enabled"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_THEME = "theme_override"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_DEVANAGARI_MODE = "devanagari_mode"
    private const val KEY_LANGUAGE_HINT = "language_hint"
    private const val KEY_DEFAULT_LANGUAGE_HINT = "default_language_hint"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hapticEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTIC, true)

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC, enabled).apply()
    }

    /**
     * Governs every synthesized sound effect this app plays — the mic's
     * start/accept chimes, Enter, and each key tap (see
     * `VoiceImeService.playPcm`) — one switch, same as [hapticEnabled]
     * covers every vibration rather than each gesture having its own.
     */
    fun soundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    /**
     * The speaker's own name, used as a vocabulary hint so it's recognised
     * reliably (proper nouns are exactly what Whisper's language-model prior
     * tends to mangle on a small model — the "WhatsApp"/"what's up" bug was
     * the same failure mode). Stored locally only, same as every other
     * preference here — never leaves the device.
     */
    fun userName(context: Context): String? = prefs(context).getString(KEY_USER_NAME, null)

    fun setUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    /**
     * When on, dictated text is rendered fully in Devanagari script —
     * including English words, phonetically approximated — instead of the
     * default (Hindi romanized to Latin, English left as-is). Toggled from
     * the small script glyph on the voice panel, so it's a quick per-session
     * switch rather than a settings-screen trip.
     */
    fun devanagariMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVANAGARI_MODE, false)

    fun setDevanagariMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVANAGARI_MODE, enabled).apply()
    }

    /**
     * The *active* language hint — see [LanguageHint]. Toggled from the
     * voice panel for quick per-field/per-utterance overrides. Reset back
     * to [defaultLanguageHint] every time the keyboard opens on a fresh
     * field (see `VoiceImeService.onStartInputView`), so a temporary switch
     * to HI for one message doesn't silently stick around for everything
     * typed afterwards — only the Settings-screen default is "sticky"
     * across sessions.
     */
    fun languageHint(context: Context): LanguageHint =
        LanguageHint.entries.getOrElse(
            prefs(context).getInt(KEY_LANGUAGE_HINT, LanguageHint.ENGLISH.ordinal)
        ) { LanguageHint.ENGLISH }

    fun setLanguageHint(context: Context, hint: LanguageHint) {
        prefs(context).edit().putInt(KEY_LANGUAGE_HINT, hint.ordinal).apply()
    }

    /**
     * The language hint every fresh keyboard session starts from — set via
     * the settings screen's "Language" row, distinct from the live
     * [languageHint] the voice panel toggles per-session.
     */
    fun defaultLanguageHint(context: Context): LanguageHint =
        LanguageHint.entries.getOrElse(
            prefs(context).getInt(KEY_DEFAULT_LANGUAGE_HINT, LanguageHint.ENGLISH.ordinal)
        ) { LanguageHint.ENGLISH }

    fun setDefaultLanguageHint(context: Context, hint: LanguageHint) {
        prefs(context).edit().putInt(KEY_DEFAULT_LANGUAGE_HINT, hint.ordinal).apply()
    }

    fun theme(context: Context): Theme =
        Theme.entries.getOrElse(prefs(context).getInt(KEY_THEME, 0)) { Theme.SYSTEM }

    fun setTheme(context: Context, theme: Theme) {
        prefs(context).edit().putInt(KEY_THEME, theme.ordinal).apply()
    }

    /** Resolves the SYSTEM setting into an actual light/dark boolean — true for both DARK and BLACK. */
    fun isDark(context: Context): Boolean = when (theme(context)) {
        Theme.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode
            (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        Theme.LIGHT -> false
        Theme.DARK, Theme.BLACK -> true
    }

    /** True only for the pure-black AMOLED variant of dark mode ([Theme.BLACK]) — SYSTEM never resolves to it, since the OS has no equivalent signal to opt into. */
    fun isPureBlack(context: Context): Boolean = theme(context) == Theme.BLACK

    /**
     * The live evidence behind the "fully offline" claim: the permissions
     * this app has genuinely declared, read from [android.content.pm.PackageManager]
     * rather than restating a claim from a settings screen — a real runtime
     * check, not copy. Shared by both keyboard surfaces' lock glyph
     * ([VoiceKeyboardView]'s privacy-info overlay, [TextKeyboardView]'s
     * lock key), so there is exactly one place this logic can drift from
     * what the build actually ships.
     */
    fun privacyProofText(context: Context): String {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions?.toList().orEmpty()
        val networkPerms = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE,
        )
        val hasNetwork = declared.any { it in networkPerms }
        val names = declared.map { it.substringAfterLast('.') }
        return buildString {
            if (hasNetwork) {
                append(context.getString(R.string.privacy_proof_warning))
            } else {
                append(context.getString(R.string.privacy_proof_ok))
            }
            append(" ")
            append(context.getString(R.string.privacy_proof_permissions, names.joinToString(", ").ifEmpty { "none" }))
        }
    }
}
