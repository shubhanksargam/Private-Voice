package dev.privatevoice.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A real typing keyboard: QWERTY, shift/caps-lock, backspace with repeat, a
 * symbols page, and an enter key that respects the field's requested action.
 *
 * Deliberately not a HeliBoard/Gboard competitor — no autocorrect, no
 * dictionary, no swipe-typing, no multi-language layouts. It types
 * correctly and predictably, which is the actual bar for "an actual text
 * keyboard" here; the more elaborate features are a different, much larger
 * project.
 *
 * Canvas-drawn like [VoiceKeyboardView], same near-monochrome palette, same
 * reason: no XML/drawables to keep in sync, and colour stays reserved for
 * one signal (the accent key backgrounds) rather than decorating everything.
 */
class TextKeyboardView(context: Context) : View(context) {

    enum class ShiftState { NONE, ONE_SHOT, LOCKED }

    sealed class KeyAction {
        data class Letter(val char: Char) : KeyAction()
        data class Symbol(val char: Char) : KeyAction()
        object Shift : KeyAction()
        object Backspace : KeyAction()
        object Space : KeyAction()
        object Enter : KeyAction()
        object SymbolsToggle : KeyAction()
        object Mic : KeyAction()
    }

    private data class Key(val action: KeyAction, val label: String, val flex: Float = 1f, val accent: Boolean = false)

    /** Fires once per committed key — the service owns what happens to text. */
    var onKey: ((KeyAction) -> Unit)? = null
    var onLongPressSpace: (() -> Unit)? = null

    private var shiftState = ShiftState.NONE
    private var showSymbols = false
    private var lastShiftTapAt = 0L

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val bg get() = if (dark) Color.parseColor("#0E0E11") else Color.parseColor("#FAFAFA")
    private val fg get() = if (dark) Color.parseColor("#F2F2F5") else Color.parseColor("#17171A")
    private val muted get() = if (dark) Color.parseColor("#77777F") else Color.parseColor("#8A8A93")
    private val keyPressedBg get() = if (dark) Color.parseColor("#232328") else Color.parseColor("#ECECEF")
    private val accent = Color.parseColor("#FF453A")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val dp = resources.displayMetrics.density
    private fun dp(v: Float) = v * dp

    // --- layout: rows of keys, laid out left-to-right by flex weight ---

    private var rows: List<List<Key>> = letterRows()
    private var keyRects: List<List<RectF>> = emptyList()

    private var pressedRow = -1
    private var pressedCol = -1

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var longPressSpaceRunnable: Runnable? = null
    private var spaceLongPressed = false

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(ROWS * ROW_HEIGHT_DP + 2 * PADDING_DP).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        val pad = dp(PADDING_DP)
        val rowH = (height - 2 * pad) / ROWS
        keyRects = rows.mapIndexed { r, row ->
            val totalFlex = row.sumOf { it.flex.toDouble() }.toFloat()
            var x = pad
            val y = pad + r * rowH
            row.map { k ->
                val w = (width - 2 * pad) * (k.flex / totalFlex)
                RectF(x, y, x + w, y + rowH).also { x += w }
            }
        }
    }

    // --- key sets ---

    private fun letterRows(): List<List<Key>> {
        val shiftUp = shiftState != ShiftState.NONE
        fun l(c: Char) = Key(KeyAction.Letter(if (shiftUp) c.uppercaseChar() else c), (if (shiftUp) c.uppercaseChar() else c).toString())

        val shiftLabel = if (shiftState == ShiftState.LOCKED) "⇪" else "⇧"
        return listOf(
            "qwertyuiop".map(::l),
            "asdfghjkl".map(::l),
            listOf(Key(KeyAction.Shift, shiftLabel, 1.5f, accent = shiftState != ShiftState.NONE)) +
                "zxcvbnm".map(::l) +
                listOf(Key(KeyAction.Backspace, "⌫", 1.5f)),
            bottomRow("?123"),
        )
    }

    private fun symbolRows(): List<List<Key>> {
        fun s(c: Char) = Key(KeyAction.Symbol(c), c.toString())
        return listOf(
            "1234567890".map(::s),
            "@#\$_&-+()/".map(::s),
            "*\"':;!?".map(::s) + listOf(Key(KeyAction.Backspace, "⌫", 1.5f)),
            bottomRow("ABC"),
        )
    }

    private fun bottomRow(togglePageLabel: String) = listOf(
        Key(KeyAction.SymbolsToggle, togglePageLabel, 1.2f),
        Key(KeyAction.Symbol(','), ",", 0.9f),
        Key(KeyAction.Mic, "🎤", 0.9f),
        Key(KeyAction.Space, "", 3.6f),
        Key(KeyAction.Symbol('.'), ".", 0.9f),
        Key(KeyAction.Enter, "⏎", 1.4f, accent = true),
    )

    private fun rebuildRows() {
        rows = if (showSymbols) symbolRows() else letterRows()
        relayout()
        invalidate()
    }

    // --- external state control (autocapitalize, field-specific behaviour) ---

    /** So callers (autocapitalize) don't clobber a manual caps-lock. */
    val shiftLocked: Boolean get() = shiftState == ShiftState.LOCKED

    fun setShiftState(state: ShiftState) {
        if (shiftState == state) return
        shiftState = state
        if (!showSymbols) rebuildRows() else invalidate()
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        invalidate()
    }

    private var micEnabled = true

    fun resetToLetters() {
        showSymbols = false
        rebuildRows()
    }

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        for ((r, row) in rows.withIndex()) {
            for ((c, key) in row.withIndex()) {
                val rect = keyRects.getOrNull(r)?.getOrNull(c) ?: continue
                drawKey(canvas, key, rect, pressed = r == pressedRow && c == pressedCol)
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF, pressed: Boolean) {
        val disabled = key.action is KeyAction.Mic && !micEnabled

        if (pressed && !disabled) {
            fill.color = if (key.accent) accent else keyPressedBg
            fill.alpha = if (key.accent) 255 else 255
            val inset = dp(3f)
            canvas.drawRoundRect(
                rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                dp(8f), dp(8f), fill,
            )
        } else if (key.accent && key.action == KeyAction.Enter) {
            // Enter stays a quiet accent outline at rest, filled only when pressed —
            // color marks "this key is different" without shouting on an idle keyboard.
            fill.color = accent
            fill.alpha = 28
            val inset = dp(3f)
            canvas.drawRoundRect(
                rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                dp(8f), dp(8f), fill,
            )
        }

        label.color = when {
            disabled -> muted
            key.action is KeyAction.Shift && key.accent -> accent
            key.accent -> if (pressed) Color.WHITE else accent
            key.action is KeyAction.SymbolsToggle || key.action is KeyAction.Mic -> muted
            else -> fg
        }
        label.textSize = when (key.action) {
            is KeyAction.SymbolsToggle -> dp(13f)
            is KeyAction.Shift, is KeyAction.Backspace, is KeyAction.Enter, is KeyAction.Mic -> dp(17f)
            else -> dp(16.5f)
        }
        label.alpha = if (disabled) 120 else 255
        val metrics = label.fontMetrics
        val ty = rect.centerY() - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(key.label, rect.centerX(), ty, label)
    }

    // --- touch ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (row, col) = keyAt(event.x, event.y) ?: run {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                clearPress()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPressed(row, col)
                val action = rows[row][col].action
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (action) {
                    is KeyAction.Backspace -> {
                        commit(action)
                        startBackspaceRepeat()
                    }
                    is KeyAction.Space -> {
                        spaceLongPressed = false
                        armLongPressSpace()
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_MOVE -> setPressed(row, col)

            MotionEvent.ACTION_UP -> {
                val action = rows[row][col].action
                cancelLongPressSpace()
                stopBackspaceRepeat()
                when (action) {
                    is KeyAction.Backspace -> Unit // already handled on DOWN + repeat
                    is KeyAction.Space -> if (!spaceLongPressed) commit(action)
                    // Mic always navigates to the voice view, even when the
                    // dimmed label hints it's unavailable — that view already
                    // explains why (missing permission, no model) and offers a
                    // way to fix it, which is more useful than a dead key.
                    else -> commit(action)
                }
                clearPress()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressSpace()
                stopBackspaceRepeat()
                clearPress()
            }
        }
        return true
    }

    private fun commit(action: KeyAction) {
        when (action) {
            is KeyAction.Shift -> onShiftTapped()
            is KeyAction.SymbolsToggle -> { showSymbols = !showSymbols; rebuildRows() }
            else -> {
                onKey?.invoke(action)
                if (shiftState == ShiftState.ONE_SHOT && action is KeyAction.Letter) {
                    setShiftState(ShiftState.NONE)
                }
            }
        }
    }

    private fun onShiftTapped() {
        val now = System.currentTimeMillis()
        val doubleTapped = (now - lastShiftTapAt) < DOUBLE_TAP_MS
        lastShiftTapAt = now
        val next = when {
            shiftState == ShiftState.LOCKED -> ShiftState.NONE
            shiftState == ShiftState.ONE_SHOT && doubleTapped -> ShiftState.LOCKED
            shiftState == ShiftState.NONE -> ShiftState.ONE_SHOT
            else -> ShiftState.NONE
        }
        setShiftState(next)
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        val r = object : Runnable {
            override fun run() {
                onKey?.invoke(KeyAction.Backspace)
                handler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, BACKSPACE_REPEAT_INITIAL_MS)
    }

    private fun stopBackspaceRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun armLongPressSpace() {
        cancelLongPressSpace()
        val r = Runnable {
            spaceLongPressed = true
            onLongPressSpace?.invoke()
        }
        longPressSpaceRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelLongPressSpace() {
        longPressSpaceRunnable?.let { handler.removeCallbacks(it) }
        longPressSpaceRunnable = null
    }

    private fun keyAt(x: Float, y: Float): Pair<Int, Int>? {
        for ((r, row) in keyRects.withIndex()) {
            for ((c, rect) in row.withIndex()) {
                if (rect.contains(x, y)) return r to c
            }
        }
        return null
    }

    private fun setPressed(r: Int, c: Int) {
        if (pressedRow != r || pressedCol != c) {
            pressedRow = r; pressedCol = c
            invalidate()
        }
    }

    private fun clearPress() {
        pressedRow = -1; pressedCol = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        cancelLongPressSpace()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ROWS = 4
        const val ROW_HEIGHT_DP = 52f
        const val PADDING_DP = 6f
        const val DOUBLE_TAP_MS = 350L
        const val LONG_PRESS_MS = 400L
        const val BACKSPACE_REPEAT_INITIAL_MS = 450L
        const val BACKSPACE_REPEAT_MS = 60L
    }
}
