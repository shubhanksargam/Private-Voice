package dev.privatevoice.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * status text, and two low-contrast utility glyphs. Nothing has a border, a
 * shadow, or a container. Colour is used for exactly one thing — signalling
 * that recording is live — so it reads instantly without any label.
 *
 * The pulse tracks real microphone amplitude rather than running on a timer,
 * which is what makes it feel responsive instead of decorative.
 */
class VoiceKeyboardView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, TRANSCRIBING, BLOCKED }

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /** Left utility glyph. Returns to the typing keyboard within this same
     *  IME — system-IME switching lives on the text keyboard now (long-press
     *  space), since that's the surface a user reaches for a different app's
     *  keyboard from, not this one. */
    var onSwitchToText: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    /** Live mic level, 0..1. Set by the service while recording. */
    var amplitude: Float = 0f

    private var state = State.IDLE
    private var message: String = ""

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // Near-monochrome palette; a single accent carries all the signal.
    private val bg get() = if (dark) Color.parseColor("#0E0E11") else Color.parseColor("#FAFAFA")
    private val fg get() = if (dark) Color.parseColor("#F2F2F5") else Color.parseColor("#17171A")
    private val muted get() = if (dark) Color.parseColor("#77777F") else Color.parseColor("#8A8A93")
    private val ring get() = if (dark) Color.parseColor("#26262C") else Color.parseColor("#E6E6EA")
    private val accent get() = Color.parseColor("#FF453A")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

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

    private var pressedInMic = false
    private var downX = 0f
    private var downY = 0f

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
        super.onDetachedFromWindow()
    }

    fun showIdle() = setStateInternal(State.IDLE, "")
    fun showListening() = setStateInternal(State.LISTENING, "")
    fun showTranscribing() = setStateInternal(State.TRANSCRIBING, "")
    fun showBlocked(text: String) = setStateInternal(State.BLOCKED, text)

    private fun setStateInternal(s: State, text: String) {
        state = s
        message = text
        invalidate()
    }

    // Geometry, recomputed per draw so it survives rotation without state.
    private val micCx get() = width / 2f
    private val micCy get() = height / 2f - dp(8f)
    private val micR get() = dp(38f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)

        drawUtilityGlyphs(canvas)

        if (state == State.BLOCKED) {
            drawBlocked(canvas)
            return
        }

        drawMic(canvas)
        drawStatus(canvas)
    }

    private fun drawMic(canvas: Canvas) {
        val listening = state == State.LISTENING
        val working = state == State.TRANSCRIBING

        // Amplitude-driven halo. Two offset rings so quiet speech still shows
        // motion without the loud case looking frantic.
        if (listening) {
            val level = amplitude.coerceIn(0f, 1f)
            fill.color = accent
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
            stroke.color = accent
            stroke.strokeWidth = dp(2f)
            stroke.strokeCap = Paint.Cap.ROUND
            tmpRect.set(micCx - micR, micCy - micR, micCx + micR, micCy + micR)
            val sweep = 70f
            canvas.drawArc(tmpRect, Math.toDegrees(phase.toDouble()).toFloat(), sweep, false, stroke)
            stroke.strokeCap = Paint.Cap.BUTT
        }

        // Solid disc only while live — the one moment colour appears.
        if (listening) {
            fill.color = accent
            fill.alpha = 255
            canvas.drawCircle(micCx, micCy, micR - dp(6f), fill)
        }

        drawMicGlyph(
            canvas,
            micCx,
            micCy,
            tint = if (listening) Color.WHITE else fg,
            scale = if (pressedInMic && !listening) 0.92f else 1f,
        )
    }

    private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, tint: Int, scale: Float) {
        val h = dp(19f) * scale
        val w = dp(12f) * scale
        micPath.reset()

        // Capsule body.
        tmpRect.set(cx - w / 2, cy - h / 2 - dp(2f), cx + w / 2, cy + h / 2 - dp(6f))
        micPath.addRoundRect(tmpRect, w / 2, w / 2, Path.Direction.CW)

        fill.color = tint
        fill.alpha = 255
        canvas.drawPath(micPath, fill)

        // Cradle arc + stem.
        stroke.color = tint
        stroke.strokeWidth = dp(1.8f) * scale
        stroke.strokeCap = Paint.Cap.ROUND
        val cradle = w * 0.95f
        tmpRect.set(cx - cradle, cy - dp(4f), cx + cradle, cy + dp(9f))
        canvas.drawArc(tmpRect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + dp(9f), cx, cy + dp(14f) * scale, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawStatus(canvas: Canvas) {
        val text = when (state) {
            State.LISTENING -> context.getString(R.string.status_listening)
            State.TRANSCRIBING -> context.getString(R.string.status_transcribing)
            else -> context.getString(R.string.status_hold_to_talk)
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
        for (l in lines) {
            canvas.drawText(l, width / 2f, y, label)
            y += lineHeight
        }
    }

    /** Keyboard-switch (left) and backspace (right). Low contrast on purpose. */
    private fun drawUtilityGlyphs(canvas: Canvas) {
        val y = height - dp(26f)
        stroke.color = muted
        stroke.strokeWidth = dp(1.6f)
        stroke.strokeCap = Paint.Cap.ROUND

        // "ABC": text reads unambiguously where an icon (globe? keyboard?)
        // would need a label anyway — this IS the label.
        val lx = dp(34f)
        label.color = muted
        label.textSize = dp(12.5f)
        label.letterSpacing = 0f
        canvas.drawText("ABC", lx, y + dp(4f), label)

        // Backspace: pentagon outline with an x.
        val rx = width - dp(34f)
        micPath.reset()
        micPath.moveTo(rx - dp(11f), y)
        micPath.lineTo(rx - dp(4f), y - dp(7f))
        micPath.lineTo(rx + dp(10f), y - dp(7f))
        micPath.lineTo(rx + dp(10f), y + dp(7f))
        micPath.lineTo(rx - dp(4f), y + dp(7f))
        micPath.close()
        canvas.drawPath(micPath, stroke)
        canvas.drawLine(rx - dp(1f), y - dp(3f), rx + dp(5f), y + dp(3f), stroke)
        canvas.drawLine(rx + dp(5f), y - dp(3f), rx - dp(1f), y + dp(3f), stroke)

        stroke.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                if (state == State.BLOCKED) return true

                if (hypot(x - micCx, y - micCy) <= micR + dp(10f)) {
                    pressedInMic = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onHoldStart?.invoke()
                    invalidate()
                    return true
                }
                if (inUtilityRow(y)) return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Slide well away from the mic to abort — the standard escape
                // gesture for push-to-talk, so a mis-press isn't committed.
                if (pressedInMic && hypot(x - micCx, y - micCy) > micR + dp(72f)) {
                    pressedInMic = false
                    onCancel?.invoke()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (pressedInMic) {
                    pressedInMic = false
                    onHoldEnd?.invoke()
                    invalidate()
                    return true
                }
                if (inUtilityRow(y) && inUtilityRow(downY)) {
                    if (x < width / 2f) onSwitchToText?.invoke() else onBackspace?.invoke()
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pressedInMic) {
                    pressedInMic = false
                    onCancel?.invoke()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private fun inUtilityRow(y: Float) = y > height - dp(52f)

    private companion object {
        const val SURFACE_HEIGHT_DP = 250f
    }
}
