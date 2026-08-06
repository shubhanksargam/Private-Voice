package dev.privatevoice.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full CRUD for [PhrasebookStore] — add (typed), edit, delete, copy.
 *
 * The keyboard's own phrasebook page (in [TextKeyboardView]) stays a
 * lightweight browse/insert/delete surface reachable mid-typing; this is
 * the fuller management screen for everything that doesn't fit comfortably
 * on a phone-keyboard-sized canvas — typing a brand new entry with a real
 * text field, editing one in place, copying to the clipboard. Reachable
 * from [SetupActivity] and from the keyboard's phrasebook page itself.
 *
 * Built the same programmatic-views way as [SetupActivity] — no XML to
 * keep in sync, same reasoning.
 */
class PhrasebookActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private val dark: Boolean get() = KeyboardSettings.isDark(this)
    private val bg get() = if (dark) Color.parseColor("#0E0E11") else Color.parseColor("#FAFAFA")
    private val fg get() = if (dark) Color.parseColor("#F2F2F5") else Color.parseColor("#17171A")
    private val mutedColor get() = if (dark) Color.parseColor("#8A8A93") else Color.parseColor("#6E6E76")
    private val accent = 0xFFFF453A.toInt()

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
        root.setBackgroundColor(bg)
        scroll.setBackgroundColor(bg)
        root.removeAllViews()

        root.addView(text(getString(R.string.phrasebook_title), 26f, bold = true))
        root.addView(text(getString(R.string.phrasebook_tagline), 13f, muted = true, topDp = 6))
        root.addView(button(getString(R.string.phrasebook_add)) { promptAdd() })

        val phrases = PhrasebookStore.list(this)
        if (phrases.isEmpty()) {
            root.addView(text(getString(R.string.phrasebook_empty), 14f, muted = true, topDp = 28))
        } else {
            for (phrase in phrases) root.addView(phraseRow(phrase))
        }
    }

    private fun phraseRow(phrase: PhrasebookStore.Phrase): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        container.addView(text(phrase.text, 15f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(actionLink(getString(R.string.phrasebook_edit)) { promptEdit(phrase) })
        actions.addView(actionLink(getString(R.string.phrasebook_copy)) { copyToClipboard(phrase.text) })
        actions.addView(actionLink(getString(R.string.phrasebook_delete)) { confirmDelete(phrase) })
        container.addView(actions)
        return container
    }

    private fun actionLink(label: String, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(accent)
            setPadding(0, 0, dp(24), 0)
            setOnClickListener { onClick() }
        }

    private fun promptAdd() {
        promptText(title = getString(R.string.phrasebook_new_title), initial = "") { typed ->
            PhrasebookStore.add(this, typed)
            render()
        }
    }

    private fun promptEdit(phrase: PhrasebookStore.Phrase) {
        promptText(title = getString(R.string.phrasebook_edit_title), initial = phrase.text) { typed ->
            PhrasebookStore.update(this, phrase.id, typed)
            render()
        }
    }

    private fun promptText(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setTextColor(fg)
            setHintTextColor(mutedColor)
            setSelection(text.length)
        }
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            setPadding(pad, dp(12), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isNotEmpty()) onSave(typed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(phrase: PhrasebookStore.Phrase) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.phrasebook_delete_confirm_title))
            .setMessage(phrase.text)
            .setPositiveButton(getString(R.string.phrasebook_delete)) { _, _ ->
                PhrasebookStore.remove(this, phrase.id)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phrase", text))
        Toast.makeText(this, R.string.phrasebook_copied, Toast.LENGTH_SHORT).show()
    }

    // --- tiny view helpers, matching SetupActivity's — no XML to keep in sync ---

    private fun text(s: String, sizeSp: Float, muted: Boolean = false, bold: Boolean = false, topDp: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(if (muted) mutedColor else fg)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(topDp), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun button(s: String, onClick: () -> Unit): View =
        text(s, 16f, topDp = 20).apply {
            setTextColor(accent)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
