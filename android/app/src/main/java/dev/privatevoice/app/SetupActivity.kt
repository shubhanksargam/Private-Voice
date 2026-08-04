package dev.privatevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importModel(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(28)
            setPadding(p, dp(56), p, p)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()

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
        }

        if (modelOk) {
            val names = AsrEngineHolder.installedModels(this)
                .joinToString("\n") { "  ${it.name}  ·  ${it.length() / 1024 / 1024} MB" }
            root.addView(text(getString(R.string.installed_models) + "\n" + names, 12f, muted = true, topDp = 28))
        }

        root.addView(text(getString(R.string.privacy_note), 12f, muted = true, topDp = 32))
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
            setTextColor(if (muted) 0xFF8A8A93.toInt() else ContextCompat.getColor(this@SetupActivity, android.R.color.primary_text_light))
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
