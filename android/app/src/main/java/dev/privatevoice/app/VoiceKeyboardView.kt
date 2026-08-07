package dev.privatevoice.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The keyboard surface. Drawn entirely on canvas — no XML, no drawables, no
 * nine-patches.
 *
 * Design intent is minimal and quiet: one large mic target, a single line of
 * status text, and a row of low-contrast utility glyphs (switch-to-text,
 * undo, backspace). Nothing has a border, a shadow, or a container. Colour
 * is used for exactly one thing — signalling that recording is live — so
 * it reads instantly without any label.
 *
 * The pulse tracks real microphone amplitude rather than running on a timer,
 * which is what makes it feel responsive instead of decorative.
 *
 * Tap-to-talk, not hold-to-talk: one tap starts recording, a second tap
 * (while [State.LISTENING]) stops it and transcribes. Switching to text
 * mode mid-recording (the "ABC" glyph) still cancels it — that remains the
 * escape hatch for a stray or unwanted recording, in place of the old
 * slide-away-to-cancel gesture that only made sense while a finger was held
 * down.
 *
 * While [State.LISTENING] specifically (recording started, not yet
 * stopped), the language/script toggles stop responding to taps — changing
 * either mid-recording doesn't affect the recording already in progress, so
 * it would just be a confusing no-op — and the backspace glyph switches
 * from deleting committed text to cancelling the recording ([onCancelRecording]),
 * matching the "ABC" glyph's existing cancel-on-escape behaviour instead of
 * silently eating a character from whatever's already in the field.
 */
class VoiceKeyboardView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, TRANSCRIBING, BLOCKED, INFO }

    /** Fires on the tap that starts recording. */
    var onHoldStart: (() -> Unit)? = null

    /** Fires on the tap that stops recording and triggers transcription. */
    var onHoldEnd: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /**
     * Fires when the dedicated undo glyph (utility row, third quarter) is
     * tapped — attempts to remove the last voice commit and reports
     * whether it actually did. Previously this was a long-press on the
     * mic itself, which shared a touch surface with tap-to-start-recording
     * and caused a real bug: an ordinary tap that lingered a moment (e.g.
     * starting to speak before lifting the finger) routinely exceeded the
     * long-press threshold and silently undid the *previous* dictation
     * right as a new recording should have started. A dedicated, always-
     * visible glyph has no such collision — if there's nothing eligible to
     * undo, the tap is just a no-op (see VoiceImeService.undoLastDictation).
     */
    var onUndoLastDictation: (() -> Boolean)? = null

    /**
     * Fires when the backspace glyph is tapped while [State.LISTENING] —
     * discards the in-progress recording instead of deleting committed
     * text. Distinct from [onCancel], which aborts an already-running
     * transcription ([State.TRANSCRIBING]); this one never reaches the ASR
     * engine at all.
     */
    var onCancelRecording: (() -> Unit)? = null

    /** Left utility glyph. Returns to the typing keyboard within this same
     *  IME — system-IME switching lives on the text keyboard now (long-press
     *  space), since that's the surface a user reaches for a different app's
     *  keyboard from, not this one. Plain tap only. */
    var onSwitchToText: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    /**
     * Tap "☺" switches to the text keyboard's emoji page — the voice panel
     * has no room for a full emoji grid of its own, so this borrows the
     * text keyboard's existing scrollable one rather than building a second.
     */
    var onOpenEmojiPanel: (() -> Unit)? = null

    /**
     * Long-pressing "☺" opens the phrasebook page instead — mirrors
     * TextKeyboardView's EmojiToggle key exactly (long-press, not
     * double-tap, since a plain tap here has no reason to be delayed the
     * way B's does; see [onDoubleTapBoldSave]).
     */
    var onOpenPhrasebook: (() -> Unit)? = null

    /** Tapping the privacy glyph opens full settings; long-pressing it shows the privacy info panel instead. Same mapping as TextKeyboardView's lock key, exactly. */
    var onOpenSettings: (() -> Unit)? = null

    /** "?" in the utility row — opens [GuideActivity], the how-to-use guide. Plain tap, no long-press behaviour. */
    var onOpenGuide: (() -> Unit)? = null

    /** Green arrow in the utility row — same action as [TextKeyboardView]'s Enter key. Plain tap, no long-press behaviour. */
    var onEnter: (() -> Unit)? = null

    /**
     * Fires on every tap of "B" — toggles bold for subsequently
     * dictated/typed text (same underlying state as the text keyboard's B
     * key, [TextKeyboardView.boldActive]) and toggles bold/un-bold on the
     * field's current text selection in place, if there is one.
     */
    var onToggleBold: (() -> Unit)? = null

    /**
     * Fires on a confirmed double-tap of "B" — the voice panel's
     * counterpart to [TextKeyboardView.onDoubleTapBoldSave]. @return true
     * if the field had a text selection and it was saved to the
     * phrasebook; false makes this view fall back to [onOpenPhrasebook]
     * (just opening the phrasebook page), so double-tap-B is a useful
     * shortcut either way.
     */
    var onDoubleTapBoldSave: (() -> Boolean)? = null

    /** Live mic level, 0..1. Set by the service while recording. */
    var amplitude: Float = 0f

    /**
     * Set by the service right before [showTranscribing] when the device is
     * battery/thermal-throttled enough that `small` got downgraded to
     * `base` for this decode (or would be, if the language routing had
     * picked `small` at all) — surfaced as a quiet status-text suffix
     * rather than a persistent icon, since it's only relevant for the
     * duration of one decode.
     */
    var batterySaver: Boolean = false

    /**
     * Set by the service right before [showListening] when there's an
     * active text selection in the target field — the coming transcription
     * will replace it rather than insert alongside it (see
     * VoiceImeService.commitTranscript). Surfaced as a status-text swap so
     * that's obvious before speaking, not just a silent behaviour change.
     */
    var replacingSelection: Boolean = false

    /** Set by the service right before [showListening] when this recording is for the phrasebook, not the target field. */
    var recordingPhrase: Boolean = false

    private var state = State.IDLE
    private var message: String = ""

    private val dark: Boolean get() = KeyboardSettings.isDark(context)
    private val black: Boolean get() = KeyboardSettings.isPureBlack(context)

    // Burnt-paper beige / warm dark grey (or pure black), shared with
    // TextKeyboardView — see KeyboardPalette. A single accent still carries
    // the one signal colour (recording live).
    private val bg get() = KeyboardPalette.bg(dark, black)
    private val fg get() = KeyboardPalette.fg(dark)
    private val muted get() = KeyboardPalette.muted(dark, black)
    private val ring get() = KeyboardPalette.ring(dark, black)
    private val accent get() = Color.parseColor("#FF453A")
    private val enterAccent get() = Color.parseColor("#34C759")
    private val micAccent get() = Color.parseColor("#0A84FF")
    private val bookAccent get() = Color.parseColor("#FF9F0A")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = KeyboardPalette.typeface
    }
    private val boldTypeface: Typeface = Typeface.create(KeyboardPalette.typeface, Typeface.BOLD)

    private val micPath = Path()
    private val tmpRect = RectF()

    private var phase = 0f
    private val ticker = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1_600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            if (state == State.LISTENING || state == State.TRANSCRIBING) invalidate()
        }
    }

    /** Whether the current touch sequence's DOWN landed on the mic — gates the UP into a valid tap. */
    private var micTouchDown = false
    private var downX = 0f
    private var downY = 0f
    private var backspacePressed = false
    private var backspaceRepeatRunnable: Runnable? = null
    private var privacyLongPressed = false
    private var privacyLongPressRunnable: Runnable? = null
    private var emojiLongPressed = false
    private var emojiLongPressRunnable: Runnable? = null

    /** Timestamp of B's last tap-release, for double-tap detection — see the B handling in [onTouchEvent]. */
    private var lastBoldTapUpAtMs = 0L

    private val dp = resources.displayMetrics.density
    private fun dp(v: Float) = v * dp

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthSpec),
            dp(SURFACE_HEIGHT_DP).toInt(),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        revertRunnable?.let { handler.removeCallbacks(it) }
        stopBackspaceRepeat()
        cancelPrivacyLongPress()
        cancelEmojiLongPress()
        super.onDetachedFromWindow()
    }

    fun showIdle() = setStateInternal(State.IDLE, "")
    fun showListening() = setStateInternal(State.LISTENING, "")
    fun showTranscribing() = setStateInternal(State.TRANSCRIBING, "")

    /** Persistent block — missing permission/model, password field. Stays until something external changes it. */
    fun showBlocked(text: String) = setStateInternal(State.BLOCKED, text)

    /**
     * One-off failure (a single decode that didn't produce text) rather
     * than a standing condition — auto-reverts to idle after a couple of
     * seconds instead of sitting there until the user does something,
     * since there's nothing for them to actually do about it.
     */
    fun showTransientBlocked(text: String) {
        setStateInternal(State.BLOCKED, text)
        val r = Runnable { showIdle() }
        revertRunnable = r
        handler.postDelayed(r, TRANSIENT_BLOCKED_MS)
    }

    /** The privacy-glyph tap target — dismisses back to idle on the next tap anywhere. */
    fun showInfo(text: String) = setStateInternal(State.INFO, text)

    private val handler = Handler(Looper.getMainLooper())
    private var revertRunnable: Runnable? = null

    private fun setStateInternal(s: State, text: String) {
        revertRunnable?.let { handler.removeCallbacks(it) }
        revertRunnable = null
        state = s
        message = text
        invalidate()
    }

    // Geometry, recomputed per draw so it survives rotation without state.
    private val micCx get() = width / 2f
    private val micCy get() = height / 2f - dp(8f)
    private val micR get() = dp(38f)

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)

        drawUtilityGlyphs(canvas)
        drawScriptToggle(canvas)
        drawLanguageToggle(canvas)
        drawPrivacyGlyph(canvas)

        if (state == State.BLOCKED || state == State.INFO) {
            drawBlocked(canvas)
            return
        }

        drawMic(canvas)
        drawStatus(canvas)
    }

    /**
     * Rounded only at the top corners — mirrors TextKeyboardView's own
     * [drawBackground] exactly, so the two surfaces read as one continuous
     * shape when switching between them.
     */
    private fun drawBackground(canvas: Canvas) {
        val r = dp(KeyboardPalette.TOP_CORNER_RADIUS_DP)
        micPath.reset()
        micPath.addRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        fill.color = bg
        canvas.drawPath(micPath, fill)
    }

    private fun drawMic(canvas: Canvas) {
        val listening = state == State.LISTENING
        val working = state == State.TRANSCRIBING

        // Amplitude-driven halo. Two offset rings so quiet speech still shows
        // motion without the loud case looking frantic.
        if (listening) {
            val level = amplitude.coerceIn(0f, 1f)
            fill.color = micAccent
            for (i in 0 until 2) {
                val spread = dp(14f) + dp(30f) * level
                val wobble = (sin(phase + i * 2.1f) + 1f) / 2f
                val r = micR + spread * (0.45f + 0.55f * wobble)
                fill.alpha = ((28 + 46 * level) * (1f - i * 0.45f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(micCx, micCy, r, fill)
            }
        }

        // Resting ring; becomes the progress arc while transcribing.
        stroke.strokeWidth = dp(1.5f)
        stroke.color = ring
        canvas.drawCircle(micCx, micCy, micR, stroke)

        if (working) {
            stroke.color = micAccent
            stroke.strokeWidth = dp(2f)
            stroke.strokeCap = Paint.Cap.ROUND
            tmpRect.set(micCx - micR, micCy - micR, micCx + micR, micCy + micR)
            val sweep = 70f
            canvas.drawArc(tmpRect, Math.toDegrees(phase.toDouble()).toFloat(), sweep, false, stroke)
            stroke.strokeCap = Paint.Cap.BUTT
        }

        // Solid disc only while live — the one moment colour appears.
        if (listening) {
            fill.color = micAccent
            fill.alpha = 255
            canvas.drawCircle(micCx, micCy, micR - dp(6f), fill)
        }

        drawMicGlyph(
            canvas,
            micCx,
            micCy,
            tint = if (listening) Color.WHITE else fg,
            scale = if (micTouchDown && !listening) 0.92f else 1f,
        )
    }

    private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, tint: Int, scale: Float) {
        // Shared with TextKeyboardView's small utility-row mic — see
        // KeyboardIcons — so the two panels' mic glyphs are the same shape
        // at different sizes, not two hand-tuned lookalikes.
        KeyboardIcons.drawMic(canvas, cx, cy, dp(31f) * scale, tint, 255, fill, stroke, micPath, tmpRect)
    }

    private fun drawStatus(canvas: Canvas) {
        var text = when (state) {
            State.LISTENING -> when {
                recordingPhrase -> context.getString(R.string.status_recording_phrase)
                replacingSelection -> context.getString(R.string.status_replacing_selection)
                else -> context.getString(R.string.status_listening)
            }
            State.TRANSCRIBING -> context.getString(R.string.status_transcribing)
            else -> context.getString(R.string.status_tap_to_talk)
        }
        if (state == State.TRANSCRIBING && batterySaver) {
            text += " · " + context.getString(R.string.status_battery_saver)
        }
        label.color = if (state == State.LISTENING) fg else muted
        label.textSize = dp(12.5f)
        label.letterSpacing = 0.04f
        canvas.drawText(text, width / 2f, micCy + micR + dp(30f), label)
    }

    private fun drawBlocked(canvas: Canvas) {
        label.color = muted
        label.textSize = dp(13f)
        label.letterSpacing = 0.01f

        // Wrap by hand; a StaticLayout would be heavier than this needs to be.
        val maxWidth = width - dp(64f)
        val words = message.split(" ")
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (label.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString()); line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())

        val lineHeight = dp(19f)
        var y = height / 2f - (lines.size - 1) * lineHeight / 2f
        if (state == State.INFO) y -= lineHeight / 2f // room for the close hint below
        for (l in lines) {
            canvas.drawText(l, width / 2f, y, label)
            y += lineHeight
        }
        if (state == State.INFO) {
            label.textSize = dp(11f)
            canvas.drawText(context.getString(R.string.tap_to_close), width / 2f, y + dp(10f), label)
        }
    }

    /**
     * Keyboard-switch, bold toggle, undo, phrasebook, backspace, help,
     * enter — seven equal sevenths. Low contrast on purpose except Enter.
     *
     * All seven share one [centerY]: vector icons ([KeyboardIcons] calls)
     * take it directly since they treat their `cy` argument as a true
     * geometric centre, while every text glyph computes its own baseline
     * from `centerY - (metrics.ascent + metrics.descent) / 2` — the same
     * technique [drawKey] already uses to centre key labels — instead of a
     * hand-tuned per-glyph offset. Mixing baseline-positioned text with
     * centre-positioned vectors under separate ad-hoc offsets (the previous
     * version of this function) is exactly what drifted them out of
     * alignment; this keeps every glyph's *optical* centre, not just its
     * anchor point, on the same line regardless of its own font size.
     */
    private fun drawUtilityGlyphs(canvas: Canvas) {
        val centerY = height - dp(22f)
        val seventh = width / 7f
        stroke.color = muted
        stroke.strokeWidth = dp(1.6f)
        stroke.strokeCap = Paint.Cap.ROUND

        // "ABC": text reads unambiguously where an icon (globe? keyboard?)
        // would need a label anyway — this IS the label. Plain tap-only —
        // the phrasebook entry point lives on "☺" now (see onOpenPhrasebook).
        label.color = muted
        label.textSize = dp(12.5f)
        label.letterSpacing = 0f
        var m = label.fontMetrics
        canvas.drawText("ABC", seventh / 2f, centerY - (m.ascent + m.descent) / 2, label)

        // B: tap toggles the selection between bold/un-bold (or, with
        // nothing selected, arms bold for whatever's typed/dictated next),
        // double-tap saves the selection to the phrasebook — mirrors
        // TextKeyboardView's B key exactly. Static muted colour like the
        // other utility glyphs and always drawn bold — B's function
        // depends entirely on the current selection, not a fixed on/off
        // state a key colour could represent.
        label.color = muted
        label.typeface = boldTypeface
        label.textSize = dp(15f)
        m = label.fontMetrics
        canvas.drawText("B", seventh * 1.5f, centerY - (m.ascent + m.descent) / 2, label)
        label.typeface = KeyboardPalette.typeface

        // Undo: always tappable, same muted styling as ABC/backspace — if
        // there's nothing eligible to undo the service just no-ops (see
        // VoiceImeService.undoLastDictation). Counter-clockwise arrow glyph
        // reads as "undo" without needing a text label the way ABC does.
        label.color = muted
        label.textSize = dp(21f)
        m = label.fontMetrics
        canvas.drawText("↺", seventh * 2.5f, centerY - (m.ascent + m.descent) / 2, label)

        // Emoji glyph: tap switches to the text keyboard's emoji page,
        // long-press opens the phrasebook page instead (see
        // onOpenEmojiPanel/onOpenPhrasebook). Same vector shape as
        // TextKeyboardView's emoji-toggle key — see KeyboardIcons.
        KeyboardIcons.drawEmoji(canvas, seventh * 3.5f, centerY, dp(21f), bookAccent, 255, stroke, fill, tmpRect)
        // drawEmoji resets strokeCap to BUTT internally; the backspace
        // glyph below needs it back to ROUND, same as the rest of this row.
        stroke.strokeCap = Paint.Cap.ROUND

        // Backspace: same muted arrow-plus-cross shape as TextKeyboardView's
        // — see KeyboardIcons.drawBackspace — so both panels' backspace
        // glyphs are the identical shape, not two hand-drawn lookalikes.
        KeyboardIcons.drawBackspace(canvas, seventh * 4.5f, centerY, dp(22f), muted, 255, stroke, micPath)
        stroke.strokeCap = Paint.Cap.BUTT

        // Help: opens the how-to-use guide (GuideActivity). Plain tap only.
        label.color = muted
        label.textSize = dp(16f)
        m = label.fontMetrics
        canvas.drawText("?", seventh * 5.5f, centerY - (m.ascent + m.descent) / 2, label)

        // Enter: same action as TextKeyboardView's Enter key
        // (VoiceImeService.performEnterAction), same green — the one
        // non-muted glyph in this row, matching how Enter is the one
        // accent-coloured key on the text keyboard's own bottom row.
        KeyboardIcons.drawArrow(canvas, seventh * 6.5f, centerY, dp(22f), enterAccent, 255, stroke, micPath, 0f)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * Privacy glyph, top-centre — tapping it (only from [State.IDLE], same
     * as the other toggles) opens full Settings ([onOpenSettings]).
     * Long-pressing it instead shows the actual runtime evidence behind the
     * "fully offline" claim: the permissions this app has genuinely
     * declared, read live from [android.content.pm.PackageManager] rather
     * than restating a claim from a settings screen. The manifest strips
     * INTERNET/ACCESS_NETWORK_STATE/ACCESS_WIFI_STATE via
     * `tools:node="remove"` (see `checkDebugNoInternet` in
     * app/build.gradle.kts), so if this ever showed a network permission
     * present, that would mean the build genuinely regressed — not just a
     * copy change away from being true. Same tap/long-press mapping as
     * TextKeyboardView's lock key, exactly.
     */
    private fun drawPrivacyGlyph(canvas: Canvas) {
        KeyboardIcons.drawLock(canvas, width / 2f, dp(21f), dp(20f), muted, 255, fill, stroke, tmpRect)
    }

    private fun inPrivacyGlyph(x: Float, y: Float) =
        x >= width / 2f - dp(20f) && x <= width / 2f + dp(20f) && y <= dp(44f)

    /**
     * Script-output toggle (top-right, "A" / "अ") — switches whether
     * dictated text renders in Latin (the default) or Devanagari
     * (everything, including English words, phonetically approximated
     * into the script). Only meaningful when the language toggle is forced
     * to HI — Devanagari rendering only makes sense for Hindi content — so
     * it's only drawn (and only tappable) in that state; EN and Auto hide
     * it entirely rather than show a control with no effect. A quick
     * per-session switch, so it lives on the voice panel itself rather
     * than the settings screen.
     */
    private fun isHindiForced() = KeyboardSettings.languageHint(context) == KeyboardSettings.LanguageHint.HINDI

    private fun drawScriptToggle(canvas: Canvas) {
        if (!isHindiForced()) return
        val devanagari = KeyboardSettings.devanagariMode(context)
        // Same static muted colour as every other utility glyph on this
        // panel (mic, emoji, lock, ABC, undo, backspace, help) — a
        // brighter "active" colour here read as a different shade next to
        // the rest of the row rather than a meaningful state indicator.
        label.color = muted
        label.textSize = dp(15f)
        label.letterSpacing = 0f
        canvas.drawText(if (devanagari) "अ" else "A", width - dp(28f), dp(29f), label)
    }

    private fun inScriptToggle(x: Float, y: Float) =
        isHindiForced() && x >= width - dp(56f) && y <= dp(44f)

    /**
     * Language toggle (top-left, "Auto" / "EN" / "HI") — forces the decode
     * language instead of trusting whisper.cpp's auto-detect, which is
     * unreliable enough on a short utterance with a small model that a
     * misdetected Hindi utterance can come out reading like an English
     * translation rather than a transcription. Mirrors the script toggle's
     * position/style on the opposite corner.
     */
    private fun drawLanguageToggle(canvas: Canvas) {
        val hintLabel = when (KeyboardSettings.languageHint(context)) {
            KeyboardSettings.LanguageHint.AUTO -> "Auto"
            KeyboardSettings.LanguageHint.ENGLISH -> "EN"
            KeyboardSettings.LanguageHint.HINDI -> "HI"
        }
        label.textAlign = Paint.Align.LEFT
        // Same static muted colour as every other utility glyph — see
        // drawScriptToggle for why this no longer brightens when active.
        label.color = muted
        label.textSize = dp(13f)
        label.letterSpacing = 0f
        canvas.drawText(hintLabel, dp(16f), dp(29f), label)
        label.textAlign = Paint.Align.CENTER
    }

    private fun inLanguageToggle(x: Float, y: Float) = x <= dp(56f) && y <= dp(44f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                // Toggling either mid-recording wouldn't affect the
                // recording already in progress — it'd just be a confusing
                // no-op — so both stop responding to taps for the duration
                // of State.LISTENING specifically (not TRANSCRIBING, which
                // has its own cancel path via the mic tap).
                if (state != State.LISTENING) {
                    if (inScriptToggle(x, y)) {
                        KeyboardSettings.setDevanagariMode(context, !KeyboardSettings.devanagariMode(context))
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        invalidate()
                        return true
                    }
                    if (inLanguageToggle(x, y)) {
                        val next = KeyboardSettings.LanguageHint.entries[
                            (KeyboardSettings.languageHint(context).ordinal + 1) % KeyboardSettings.LanguageHint.entries.size
                        ]
                        KeyboardSettings.setLanguageHint(context, next)
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        invalidate()
                        return true
                    }
                    if (state == State.IDLE && inPrivacyGlyph(x, y)) {
                        privacyLongPressed = false
                        armPrivacyLongPress()
                        return true
                    }
                }
                if (state == State.INFO) {
                    showIdle()
                    return true
                }
                if (state == State.BLOCKED) return true

                if (hypot(x - micCx, y - micCy) <= micR + dp(10f)) {
                    micTouchDown = true
                    invalidate()
                    return true
                }
                if (inUtilityRow(y)) {
                    val seventh = width / 7f
                    if (x < seventh) {
                        // ABC fires on release — plain tap, nothing to arm on DOWN.
                    } else if (x < 2f * seventh) {
                        // B fires on release (single- or double-tap) — nothing to arm on DOWN.
                    } else if (x < 3f * seventh) {
                        // Undo fires on release, same as ABC/B — nothing to do on DOWN.
                    } else if (x < 4f * seventh) {
                        emojiLongPressed = false
                        armEmojiLongPress()
                    } else if (x < 5f * seventh) {
                        if (state == State.LISTENING) {
                            // Mid-recording, backspace reads as "cancel"
                            // rather than "delete a character" — deleting
                            // already-committed text isn't a sensible
                            // target while a recording is live, and this
                            // gives the same one-tap escape hatch the "ABC"
                            // glyph already provides.
                            onCancelRecording?.invoke()
                            if (KeyboardSettings.hapticEnabled(context)) {
                                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        } else {
                            // Backspace: delete once immediately, then speed
                            // up if held — same pattern as the text
                            // keyboard's backspace key, so long-pressing
                            // here isn't a dead end for clearing more than
                            // one character.
                            backspacePressed = true
                            onBackspace?.invoke()
                            if (KeyboardSettings.hapticEnabled(context)) {
                                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            startBackspaceRepeat()
                        }
                    }
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> return true

            MotionEvent.ACTION_UP -> {
                if (micTouchDown) {
                    micTouchDown = false
                    invalidate()
                    // Tap completes only if the finger is still over the mic
                    // on release — sliding off before lifting cancels the
                    // tap without touching recording state, same as any
                    // normal button.
                    if (hypot(x - micCx, y - micCy) <= micR + dp(24f)) {
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        when (state) {
                            State.LISTENING -> onHoldEnd?.invoke()
                            // small's decode can run 7+ seconds; tapping the
                            // mic again while it's working cancels rather
                            // than starting a new recording underneath it.
                            State.TRANSCRIBING -> onCancel?.invoke()
                            else -> onHoldStart?.invoke()
                        }
                    }
                    return true
                }
                if (backspacePressed) {
                    stopBackspaceRepeat()
                    return true
                }
                if (state == State.IDLE && inPrivacyGlyph(downX, downY)) {
                    cancelPrivacyLongPress()
                    if (privacyLongPressed) {
                        // The long-press runnable already fired showInfo —
                        // this release shouldn't also open Settings
                        // underneath it.
                        privacyLongPressed = false
                    } else if (inPrivacyGlyph(x, y)) {
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onOpenSettings?.invoke()
                    }
                    return true
                }
                if (inUtilityRow(y) && inUtilityRow(downY)) {
                    // Backspace already fired on DOWN (see above) — only
                    // "ABC", "B", "☺", "undo", "?", and Enter are
                    // tap-on-release here.
                    val seventh = width / 7f
                    if (x < seventh) {
                        onSwitchToText?.invoke()
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (x < 2f * seventh) {
                        // B: every tap toggles bold immediately (no delay —
                        // see TextKeyboardView.onBoldTapped for why a delay
                        // here would make bold feel broken on ordinary
                        // typing); a following double-tap additionally
                        // fires onDoubleTapBoldSave, using the selection
                        // text the service cached from this tap's own
                        // bolding.
                        val now = System.currentTimeMillis()
                        val doubleTapped = now - lastBoldTapUpAtMs < DOUBLE_TAP_MS
                        lastBoldTapUpAtMs = now
                        if (doubleTapped) {
                            val saved = onDoubleTapBoldSave?.invoke() ?: false
                            if (!saved) onOpenPhrasebook?.invoke()
                            if (KeyboardSettings.hapticEnabled(context)) {
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                        onToggleBold?.invoke()
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (x < 3f * seventh) {
                        onUndoLastDictation?.invoke()
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (x < 4f * seventh) {
                        cancelEmojiLongPress()
                        if (emojiLongPressed) {
                            // The long-press runnable already fired
                            // onOpenPhrasebook — this release shouldn't
                            // also open the emoji page underneath it.
                            emojiLongPressed = false
                        } else {
                            onOpenEmojiPanel?.invoke()
                            if (KeyboardSettings.hapticEnabled(context)) {
                                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                    } else if (x < 5f * seventh) {
                        // Backspace already fired on DOWN — nothing to do
                        // here for that column.
                    } else if (x < 6f * seventh) {
                        onOpenGuide?.invoke()
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else {
                        onEnter?.invoke()
                        if (KeyboardSettings.hapticEnabled(context)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                micTouchDown = false
                privacyLongPressed = false
                emojiLongPressed = false
                cancelPrivacyLongPress()
                cancelEmojiLongPress()
                stopBackspaceRepeat()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun armPrivacyLongPress() {
        cancelPrivacyLongPress()
        val r = Runnable {
            privacyLongPressed = true
            if (KeyboardSettings.hapticEnabled(context)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            showInfo(KeyboardSettings.privacyProofText(context))
        }
        privacyLongPressRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelPrivacyLongPress() {
        privacyLongPressRunnable?.let { handler.removeCallbacks(it) }
        privacyLongPressRunnable = null
    }

    private fun armEmojiLongPress() {
        cancelEmojiLongPress()
        val r = Runnable {
            emojiLongPressed = true
            if (KeyboardSettings.hapticEnabled(context)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            onOpenPhrasebook?.invoke()
        }
        emojiLongPressRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelEmojiLongPress() {
        emojiLongPressRunnable?.let { handler.removeCallbacks(it) }
        emojiLongPressRunnable = null
    }

    private fun inUtilityRow(y: Float) = y > height - dp(52f)

    private fun startBackspaceRepeat() {
        // NOT stopBackspaceRepeat() — that also clears backspacePressed,
        // which the caller (ACTION_DOWN) just set true. Clobbering it back
        // to false here meant ACTION_UP's `if (backspacePressed)` guard
        // never fired, so the repeat runnable armed below never got
        // cancelled on release — it fired once on its own 450ms after any
        // tap, however brief, and then repeated every 60ms forever, with
        // nothing left to stop it. Only cancel the previous runnable here;
        // leave the pressed flag alone.
        backspaceRepeatRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                onBackspace?.invoke()
                handler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        backspaceRepeatRunnable = r
        handler.postDelayed(r, BACKSPACE_REPEAT_INITIAL_MS)
    }

    private fun stopBackspaceRepeat() {
        backspacePressed = false
        backspaceRepeatRunnable?.let { handler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    private companion object {
        // Matches TextKeyboardView's total row height so switching between
        // typing and voice mode doesn't resize the IME window.
        const val SURFACE_HEIGHT_DP = 268f

        const val TRANSIENT_BLOCKED_MS = 2_000L

        // Matches TextKeyboardView's backspace-repeat timing exactly.
        const val BACKSPACE_REPEAT_INITIAL_MS = 450L
        const val BACKSPACE_REPEAT_MS = 60L

        // Longer than TextKeyboardView's LONG_PRESS_MS (400L) — opening
        // settings or the phrasebook is a more consequential action than
        // opening a symbols page, worth a slightly more deliberate hold to
        // avoid firing by accident.
        const val LONG_PRESS_MS = 550L

        // Window "B"'s single tap waits before toggling bold, in case a
        // second tap follows and redirects to the double-tap-save-selection
        // action instead (see onToggleBold/onDoubleTapBoldSave).
        const val DOUBLE_TAP_MS = 300L
    }
}
