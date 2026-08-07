package dev.privatevoice.app

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A single scrollable how-to-use guide — directions, tips, and the reasoning
 * behind a few non-obvious gestures (the ones a first-time user would never
 * discover by tapping around, like double-tap-B or long-press-emoji).
 * Reachable from Settings ([SetupActivity]) and from a dedicated button on
 * the voice panel ([VoiceKeyboardView]) — the two places a user is actually
 * looking when they'd want this.
 *
 * Built the same zero-XML, programmatic-views way as [SetupActivity] and
 * [PhrasebookActivity] — one restrained style across every screen in the
 * app, not a mix of XML layouts and Kotlin-built ones.
 */
class GuideActivity : AppCompatActivity() {

    private val dark: Boolean get() = KeyboardSettings.isDark(this)
    private val bg get() = if (dark) Color.parseColor("#0E0E11") else Color.parseColor("#FAFAFA")
    private val fg get() = if (dark) Color.parseColor("#F2F2F5") else Color.parseColor("#17171A")
    private val mutedColor get() = if (dark) Color.parseColor("#8A8A93") else Color.parseColor("#6E6E76")
    private val accent get() = 0xFFFF453A.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(28)
            setPadding(p, dp(56), p, dp(56))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)

        root.addView(backArrow())
        root.addView(h1(getString(R.string.guide_title)))
        root.addView(p(getString(R.string.guide_tagline), topDp = 8, muted = true))

        section(root, "Getting started") {
            p(
                root,
                "The keyboard opens straight into voice mode — tap the mic and " +
                    "start talking. Tap \"ABC\" (bottom-left of the voice panel) any " +
                    "time to switch to a full typing keyboard instead. Both are the " +
                    "same keyboard; voice is just one mode inside it, not a separate app.",
            )
            p(
                root,
                "If nothing happens when you enable Private Voice, it's usually one " +
                    "of three things: microphone permission not granted, no speech " +
                    "model imported yet, or the keyboard not selected as your active " +
                    "input method. The Settings screen's checklist (Settings → Private " +
                    "Voice) walks through all three and tells you exactly which is " +
                    "still outstanding.",
            )
        }

        section(root, "Voice dictation") {
            p(
                root,
                "Tap the mic once to start recording, tap it again to stop and " +
                    "transcribe. If a transcription is taking a while, tapping the mic " +
                    "again cancels it instead of waiting — useful on longer or trickier " +
                    "utterances.",
            )
            p(
                root,
                "Top-left of the voice panel is the language toggle: Auto / EN / HI. " +
                    "Auto detects what you're speaking automatically. If you know you're " +
                    "about to speak entirely in English or entirely in Hindi, forcing " +
                    "that explicitly is usually more reliable than Auto — especially " +
                    "for short sentences, where automatic detection has less audio to " +
                    "work with.",
            )
            p(
                root,
                "When Hindi is forced, a second toggle appears top-right: \"A\" vs " +
                    "\"अ\" — whether dictated Hindi comes out romanized (Latin letters) " +
                    "or in Devanagari script. This only appears when it's relevant.",
            )
            p(
                root,
                "To replace text instead of inserting it: select the text in your " +
                    "field first, then dictate — the recording replaces exactly what " +
                    "was selected. To undo the last thing you dictated, tap the ↺ icon " +
                    "in the utility row while idle.",
            )
        }

        section(root, "The \"B\" key — bold, and more") {
            p(
                root,
                "Tap B with nothing selected to arm or disarm bold for whatever you " +
                    "type or dictate next — like caps lock, but for bold. It starts " +
                    "off; tapping turns it on, tapping again turns it off.",
            )
            p(
                root,
                "Select some text first, then tap B: that selection toggles bold in " +
                    "place immediately — bold text un-bolds, plain text bolds. Tap it " +
                    "again and it flips back. This works on text from other apps too, " +
                    "not just text this keyboard typed.",
            )
            p(
                root,
                "Double-tap B fast: if you had text selected, it's saved to your " +
                    "phrasebook (and the field's formatting is left exactly as it was " +
                    "— the save doesn't leave a stray bold/un-bold change behind). If " +
                    "nothing was selected, double-tap just opens the phrasebook page.",
            )
        }

        section(root, "Emoji and the phrasebook") {
            p(
                root,
                "The book icon opens an emoji picker on tap. Long-press it instead to " +
                    "jump straight to your phrasebook — saved phrases you can reuse " +
                    "with one tap, on either the typing or voice panel.",
            )
            p(
                root,
                "Three ways to add a phrase: double-tap B with text selected (saves " +
                    "that selection), tap \"🎙 New phrase\" on the phrasebook page and " +
                    "speak it, or open \"Phrasebook\" from Settings and type one " +
                    "directly — that same screen also handles editing, deleting, and " +
                    "copying.",
            )
        }

        section(root, "Typing keyboard") {
            p(
                root,
                "\"?123\" opens symbols; from there, \"=\\<\" opens a second symbols " +
                    "page (brackets, currency, math). \"ABC\" from either page jumps " +
                    "straight back to letters.",
            )
            p(
                root,
                "If you're typing Hinglish in Latin letters, a suggestion strip above " +
                    "the keys offers a Devanagari version of the word you're on — tap " +
                    "it to swap.",
            )
            p(
                root,
                "The lock icon (top row) shows this app's actual declared Android " +
                    "permissions, read live — not a claim, a runtime check. Same " +
                    "control exists on the voice panel; long-pressing it there opens " +
                    "full Settings.",
            )
        }

        section(root, "Tips & tricks") {
            tip(root, "Short, unambiguous sentences transcribe more reliably than long run-on ones — Whisper decodes in one pass, not incrementally.")
            tip(root, "Add your name in Settings — it's used as a vocabulary hint, so it (and names like it) get recognised correctly instead of misheard.")
            tip(root, "Turning on \"Recognize contact names\" (Settings, opt-in) feeds your starred/frequent contacts into that same hint — useful in chat and dialer apps. Names never leave the device.")
            tip(root, "Code-switching (English words inside a Hindi sentence, or vice versa) is the hardest case for any speech model, including this one — expect it to need more correction than single-language speech.")
            tip(root, "Low-confidence words in a dictation may show a subtle underline — a hint to double-check that specific word, not a claim the rest is guaranteed correct.")
            tip(root, "Cycle themes from Settings: System → Light → Dark → Black (true AMOLED black), one tap per step.")
            tip(root, "On low battery or when the phone is running hot, dictation automatically falls back to a faster, slightly less accurate model rather than stalling — you'll see a small \"battery saver\" note while transcribing.")
        }

        section(root, "Privacy") {
            p(
                root,
                "This app cannot send audio anywhere: it declares no internet " +
                    "permission at all, and the build itself fails if one is ever " +
                    "accidentally introduced by a dependency. Every model runs on your " +
                    "phone. The lock icon on either panel shows the actual declared " +
                    "permissions live, any time you want to check.",
            )
        }
    }

    // --- tiny view helpers, same restrained style as SetupActivity ---

    private fun h1(s: String) = TextView(this).apply {
        text = s
        textSize = 26f
        setTextColor(fg)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private inline fun section(root: LinearLayout, title: String, body: () -> Unit) {
        root.addView(
            TextView(this).apply {
                text = title.uppercase()
                textSize = 13f
                letterSpacing = 0.04f
                setTextColor(accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(36), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            },
        )
        body()
    }

    private fun p(root: LinearLayout, s: String, topDp: Int = 10, muted: Boolean = false) {
        root.addView(p(s, topDp, muted))
    }

    private fun p(s: String, topDp: Int = 10, muted: Boolean = false) = TextView(this).apply {
        text = s
        textSize = 15f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(if (muted) mutedColor else fg)
        setPadding(0, dp(topDp), 0, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun tip(root: LinearLayout, s: String) {
        root.addView(
            TextView(this).apply {
                text = "→  $s"
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(mutedColor)
                setPadding(0, dp(10), 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            },
        )
    }

    /**
     * Explicit back affordance — this screen has no action bar, so without
     * this the only way out is the system back gesture. Same 16sp as the
     * screen's body paragraphs/tips rather than a larger icon-sized glyph.
     */
    private fun backArrow(): View {
        val v = TextView(this)
        v.text = "←"
        v.textSize = 16f
        v.setTextColor(fg)
        v.setPadding(0, 0, 0, dp(4))
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        v.setOnClickListener { finish() }
        return v
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
