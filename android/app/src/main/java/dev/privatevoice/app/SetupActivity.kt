package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.privatevoice.engine.AsrEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

/**
 * First-run setup. Three things must be true before the keyboard works:
 * microphone permission, a model on disk, and the IME enabled and selected.
 *
 * Built programmatically in the same restrained style as the keyboard — a
 * numbered checklist, no cards, no icons, colour used only to mark "done".
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var importing = false

    // Same restrained near-monochrome palette as VoiceKeyboardView, and the
    // same reason it's resolved by hand rather than via a theme attr: this
    // activity is built with zero XML, so there's no theme resource chain to
    // lean on for correctness — explicit beats implicit here.
    private val dark: Boolean get() = KeyboardSettings.isDark(this)
    private val bg get() = if (dark) Color.parseColor("#0E0E11") else Color.parseColor("#FAFAFA")
    private val fg get() = if (dark) Color.parseColor("#F2F2F5") else Color.parseColor("#17171A")
    private val mutedColor get() = if (dark) Color.parseColor("#8A8A93") else Color.parseColor("#6E6E76")

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importModel(it) } }

    private val requestContacts = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(28)
            setPadding(p, dp(56), p, p)
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        // Re-applied every render, not just onCreate: the theme can change
        // at runtime (the Theme row below toggles it), and onCreate only
        // runs once — without this the row's label would update but the
        // screen would stay stuck on whatever theme was active at launch.
        root.setBackgroundColor(bg)
        scroll.setBackgroundColor(bg)
        root.removeAllViews()

        root.addView(backArrow())
        root.addView(text(getString(R.string.app_name), 30f, bold = true))
        root.addView(text(getString(R.string.setup_tagline), 14f, muted = true, topDp = 8))

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val modelOk = AsrEngineHolder.hasModel(this)
        val imeOk = isImeEnabled()

        step(1, getString(R.string.step_mic), micOk) { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        step(2, getString(R.string.step_model), modelOk) {
            if (!importing) pickModel.launch(arrayOf("*/*"))
        }
        step(3, getString(R.string.step_enable), imeOk) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        if (micOk && modelOk && imeOk) {
            root.addView(text(getString(R.string.setup_ready), 15f, topDp = 32))
            root.addView(button(getString(R.string.action_switch_keyboard)) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            })
            root.addView(button(getString(R.string.action_manage_phrasebook)) {
                startActivity(Intent(this, PhrasebookActivity::class.java))
            })
            root.addView(button(getString(R.string.action_open_guide)) {
                startActivity(Intent(this, GuideActivity::class.java))
            })
        }

        if (modelOk) {
            val names = AsrEngineHolder.installedModels(this)
                .joinToString("\n") { "  ${it.name}  ·  ${it.length() / 1024 / 1024} MB" }
            root.addView(text(getString(R.string.installed_models) + "\n" + names, 12f, muted = true, topDp = 28))
        }

        root.addView(text(getString(R.string.settings_section), 13f, muted = true, topDp = 40))
        root.addView(settingRow(getString(R.string.setting_haptics), onOffLabel(KeyboardSettings.hapticEnabled(this))) {
            KeyboardSettings.setHapticEnabled(this, !KeyboardSettings.hapticEnabled(this))
            render()
        })
        root.addView(settingRow(getString(R.string.setting_sound), onOffLabel(KeyboardSettings.soundEnabled(this))) {
            KeyboardSettings.setSoundEnabled(this, !KeyboardSettings.soundEnabled(this))
            render()
        })
        root.addView(settingRow(getString(R.string.setting_theme), themeLabel(KeyboardSettings.theme(this))) {
            KeyboardSettings.setTheme(this, nextTheme(KeyboardSettings.theme(this)))
            render()
        })
        root.addView(settingRow(getString(R.string.setting_language), languageLabel(KeyboardSettings.defaultLanguageHint(this))) {
            KeyboardSettings.setDefaultLanguageHint(this, nextLanguage(KeyboardSettings.defaultLanguageHint(this)))
            render()
        })
        val name = KeyboardSettings.userName(this)
        root.addView(settingRow(getString(R.string.setting_your_name), name?.takeIf { it.isNotBlank() } ?: getString(R.string.not_set)) {
            promptForName()
        })
        val contactsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        root.addView(settingRow(getString(R.string.setting_contacts), onOffLabel(contactsOk)) {
            if (!contactsOk) requestContacts.launch(Manifest.permission.READ_CONTACTS)
        })

        root.addView(text(getString(R.string.privacy_note), 12f, muted = true, topDp = 32))
    }

    private fun onOffLabel(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun themeLabel(t: KeyboardSettings.Theme) = getString(
        when (t) {
            KeyboardSettings.Theme.SYSTEM -> R.string.theme_system
            KeyboardSettings.Theme.LIGHT -> R.string.theme_light
            KeyboardSettings.Theme.DARK -> R.string.theme_dark
            KeyboardSettings.Theme.BLACK -> R.string.theme_black
        }
    )

    private fun nextTheme(t: KeyboardSettings.Theme) = KeyboardSettings.Theme.entries[
        (t.ordinal + 1) % KeyboardSettings.Theme.entries.size
    ]

    private fun languageLabel(h: KeyboardSettings.LanguageHint) = getString(
        when (h) {
            KeyboardSettings.LanguageHint.AUTO -> R.string.language_auto
            KeyboardSettings.LanguageHint.ENGLISH -> R.string.language_english
            KeyboardSettings.LanguageHint.HINDI -> R.string.language_hindi
        }
    )

    private fun nextLanguage(h: KeyboardSettings.LanguageHint) = KeyboardSettings.LanguageHint.entries[
        (h.ordinal + 1) % KeyboardSettings.LanguageHint.entries.size
    ]

    private fun promptForName() {
        val input = android.widget.EditText(this).apply {
            setText(KeyboardSettings.userName(this@SetupActivity).orEmpty())
            setTextColor(fg)
            setHintTextColor(mutedColor)
            hint = getString(R.string.setting_your_name)
            setSingleLine()
        }
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            setPadding(pad, dp(12), pad, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_your_name)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KeyboardSettings.setUserName(this, input.text.toString())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tappable "Title  Value" row — same restrained style as [step], no cards. */
    private fun settingRow(title: String, value: String, onClick: () -> Unit) =
        text("$title   ·   $value", 16f, topDp = 18).apply {
            setOnClickListener { onClick() }
        }

    private fun step(n: Int, title: String, done: Boolean, onClick: () -> Unit) {
        val mark = if (done) "✓" else "$n"
        val v = text("$mark   $title", 16f, topDp = 26)
        if (done) {
            v.alpha = 0.45f
        } else {
            v.setOnClickListener { onClick() }
        }
        root.addView(v)
    }

    /**
     * Copy a model the user picked into internal storage. Import rather than
     * download: the app has no INTERNET permission by design, so weights arrive
     * through the file picker or adb, never over the network.
     */
    private fun importModel(uri: Uri) {
        importing = true
        val label = text(getString(R.string.importing_model), 14f, muted = true, topDp = 20)
        root.addView(label)

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri) ?: "model.bin"
                    require(name.endsWith(".bin")) { "Not a GGML .bin file" }
                    val dest = File(AsrEngineHolder.modelsDir(this@SetupActivity), name)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    dest.length() > 0
                }.getOrDefault(false)
            }
            importing = false
            if (!ok) label.text = getString(R.string.import_failed) else render()
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    // --- tiny view helpers, so there is no XML to keep in sync ---

    private fun text(s: String, sizeSp: Float, muted: Boolean = false, bold: Boolean = false, topDp: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(if (muted) mutedColor else fg)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(topDp), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun button(s: String, onClick: () -> Unit): View =
        text(s, 16f, topDp = 20).apply {
            setTextColor(0xFFFF453A.toInt())
            setOnClickListener { onClick() }
        }

    /**
     * Explicit back affordance — this screen has no action bar, so without
     * this the only way out is the system back gesture. Same 16sp as the
     * rest of this screen's rows/buttons (see [step]/[settingRow]/[button])
     * rather than a larger icon-sized glyph, so it reads as part of the
     * same text hierarchy, not an oversized outlier.
     */
    private fun backArrow(): View =
        text("←", 16f).apply {
            setPadding(0, 0, 0, dp(4))
            setOnClickListener { finish() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
