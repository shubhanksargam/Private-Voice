package dev.privatevoice.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
        /** Fires instead of [Space] when two space taps land within [DOUBLE_TAP_MS] — the
         *  service replaces the space it already committed with ". " rather than this view
         *  reaching into the input connection itself. */
        object SpacePeriod : KeyAction()
        object Enter : KeyAction()
        object SymbolsToggle : KeyAction()
        object Mic : KeyAction()
        data class Emoji(val glyph: String) : KeyAction()
        /** Fully handled inside this view (page switch) — never reaches [onKey]. */
        object EmojiToggle : KeyAction()
        /** Tapping a saved phrase row — the service commits [text] as-is. */
        data class Phrase(val text: String) : KeyAction()
        /** Trash icon on a phrase row — service removes it from [PhrasebookStore] and refreshes. */
        data class DeletePhrase(val id: String) : KeyAction()
        /** "Record" header action on the phrasebook page — service switches to the voice panel and records into the store instead of the field. */
        object RecordPhrase : KeyAction()
        /** "Manage" header action on the phrasebook page — service opens [PhrasebookActivity] for typed add/edit/copy. */
        object ManagePhrasebook : KeyAction()
        /** Toggles [boldActive]. Fully handled inside this view — never reaches [onKey]; the service reads [boldActive] directly when committing. */
        object BoldToggle : KeyAction()
        /** "=\<" / "?123" toggle between the two symbol pages — fully handled inside this view, never reaches [onKey]. */
        object MoreSymbols : KeyAction()
    }

    private data class Key(val action: KeyAction, val label: String, val flex: Float = 1f, val accent: Boolean = false)

    /** Fires once per committed key — the service owns what happens to text. */
    var onKey: ((KeyAction) -> Unit)? = null
    var onLongPressSpace: (() -> Unit)? = null

    /** Long-press ?123/ABC — this keyboard's settings entry point. */
    var onLongPressSettings: (() -> Unit)? = null

    /**
     * Fires after a confirmed single tap of "B" has already toggled
     * [boldActive] — mirrors [dev.privatevoice.app.VoiceKeyboardView.onToggleBold]
     * in name and purpose. The service uses this to also bold the field's
     * current text selection in place, if there is one, so pressing B with
     * a selection formats it immediately rather than only affecting text
     * typed/dictated afterward.
     */
    var onToggleBold: (() -> Unit)? = null

    /**
     * Fires on a confirmed double-tap of "B". @return true if the field had
     * a text selection and it was saved to the phrasebook; false (nothing
     * selected) makes the view fall back to just opening the phrasebook
     * page itself ([showPhrasebookPage]), so double-tap-B is a useful
     * shortcut either way. The single tap's own bold-toggle is deliberately
     * delayed until it's clear no second tap is coming — applying it
     * immediately would consume the selection (via commitText) before this
     * double-tap handler ever got a chance to read it.
     */
    var onDoubleTapBoldSave: (() -> Boolean)? = null

    private var shiftState = ShiftState.NONE
    private var showSymbols = false
    /** Which of the two symbol pages is showing, when [showSymbols] — toggled by [KeyAction.MoreSymbols]. */
    private var symbolsPage = 1
    private var lastShiftTapAt = 0L
    private var lastSpaceTapAt = 0L

    /**
     * Whether newly typed/dictated text should commit as bold. Read
     * directly by [dev.privatevoice.app.VoiceImeService] — it's the one
     * that actually owns the InputConnection and wraps commits in a
     * [android.text.style.StyleSpan], this view only tracks the toggle
     * state and draws it. Defaults to `false`: text typed before B is ever
     * touched is plain, exactly like caps-lock's own "off" starting state
     * — bold only arms once the user actually taps B. See
     * [resetToLetters].
     */
    var boldActive = false
        private set

    /**
     * Mirrors the B key's own toggle logic for the voice panel's B button,
     * which shares this same bold state (read by
     * [dev.privatevoice.app.VoiceImeService] regardless of which panel is
     * visible) but has no rows of its own to dispatch a [KeyAction.BoldToggle]
     * through.
     */
    fun toggleBold() {
        boldActive = !boldActive
        rebuildRows()
    }

    private val dark: Boolean get() = KeyboardSettings.isDark(context)
    private val black: Boolean get() = KeyboardSettings.isPureBlack(context)

    // Burnt-paper beige / warm dark grey (or pure black) / shared with
    // VoiceKeyboardView — see KeyboardPalette.
    private val bg get() = KeyboardPalette.bg(dark, black)
    private val fg get() = KeyboardPalette.fg(dark)
    private val muted get() = KeyboardPalette.muted(dark, black)
    private val keyPressedBg get() = KeyboardPalette.keyPressedBg(dark, black)
    // Red marks "active toggle state" (shift-lock) — same meaning as the
    // recording red on the voice panel. Enter gets its own green: a
    // confirm/go action reads better as green than as the same red used for
    // "on," and keeps the two meanings visually distinct.
    private val accent = Color.parseColor("#FF453A")
    private val enterAccent = Color.parseColor("#34C759")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = KeyboardPalette.typeface
    }
    private val bgPath = Path()
    // Always-bold rendering of the "B" glyph itself, regardless of toggle
    // state — the icon reads as "this is the bold button" unambiguously;
    // active/inactive is signalled by colour (same red-for-"on" convention
    // as shift-lock), not by the glyph's own weight changing.
    private val boldTypeface: Typeface = Typeface.create(KeyboardPalette.typeface, Typeface.BOLD)

    private val dp = resources.displayMetrics.density
    private fun dp(v: Float) = v * dp

    // --- layout: rows of keys, laid out left-to-right by flex weight ---

    private var rows: List<List<Key>> = letterRows()
    private var keyRects: List<List<RectF>> = emptyList()
    private var showEmoji = false
    private var showPhrases = false
    private var phrases: List<PhrasebookStore.Phrase> = emptyList()

    // --- emoji page: scrollable + categorized, deliberately NOT built on
    // the fixed-grid rows/keyRects machinery every other page uses (that
    // assumes a small, constant number of same-height rows; EMOJI_CATEGORIES
    // runs to hundreds of glyphs per category). Own draw/touch handling,
    // gated at the top of onDraw/onTouchEvent.
    private var emojiCategoryIndex = 0
    private var emojiScrollY = 0f
    private var emojiDragging = false
    private var emojiDownX = 0f
    private var emojiDownY = 0f
    private var emojiLastTouchY = 0f

    // --- phrasebook page: same scrollable-list approach as the emoji page,
    // for the same reason — a real "directory of all saved phrases" doesn't
    // fit the fixed-row-count assumption the rest of this view relies on.
    private var phraseScrollY = 0f
    private var phraseDragging = false
    private var phraseDownX = 0f
    private var phraseDownY = 0f
    private var phraseLastTouchY = 0f

    private var pressedRow = -1
    private var pressedCol = -1

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var longPressSpaceRunnable: Runnable? = null
    private var spaceLongPressed = false
    private var longPressSettingsRunnable: Runnable? = null
    private var settingsLongPressed = false
    private var longPressEmojiRunnable: Runnable? = null
    private var emojiLongPressed = false
    /** Timestamp of B's last tap-release, for double-tap detection — see [onBoldTapped]. */
    private var lastBoldTapUpAtMs = 0L

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val total = ROWS * ROW_HEIGHT_DP + (ROWS - 1) * ROW_GAP_DP + 2 * PADDING_DP
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(total).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    // Gaps between keys are real dead zones, not just a drawing inset — that's
    // what makes adjacent keys read as separate tap targets instead of one
    // continuous strip, which is what "the keyboard feels small" comes down to
    // as much as raw row height does.
    private fun relayout() {
        val pad = dp(PADDING_DP)
        val rowGap = dp(ROW_GAP_DP)
        val colGap = dp(COL_GAP_DP)
        val rowH = (height - 2 * pad - (ROWS - 1) * rowGap) / ROWS
        keyRects = rows.mapIndexed { r, row ->
            val totalFlex = row.sumOf { it.flex.toDouble() }.toFloat()
            val totalGap = (row.size - 1) * colGap
            var x = pad
            val y = pad + r * (rowH + rowGap)
            row.map { k ->
                val w = (width - 2 * pad - totalGap) * (k.flex / totalFlex)
                RectF(x, y, x + w, y + rowH).also { x += w + colGap }
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
            listOf(Key(KeyAction.MoreSymbols, "=\\<", 1.1f)) +
                "*\"':;!?".map(::s) + listOf(Key(KeyAction.Backspace, "⌫", 1.5f)),
            bottomRow("ABC"),
        )
    }

    /**
     * Second symbols page — brackets, currency, math and misc symbols that
     * don't fit on [symbolRows]'s single page. Reached via "=\<", mirroring
     * Gboard's own two-symbol-page convention; "?123" here returns to
     * [symbolRows] specifically, while "ABC" on the shared [bottomRow]
     * still jumps straight back to letters from either page.
     */
    private fun symbolRows2(): List<List<Key>> {
        fun s(c: Char) = Key(KeyAction.Symbol(c), c.toString())
        return listOf(
            "~`|°¶∆√∞≈÷".map(::s),
            "£¢€¥^={}[]".map(::s),
            listOf(Key(KeyAction.MoreSymbols, "?123", 1.1f)) +
                "©®™§•\\%✓".map(::s) + listOf(Key(KeyAction.Backspace, "⌫", 1.5f)),
            bottomRow("ABC"),
        )
    }

    /** Called by the service whenever the saved-phrase list changes (added, removed, edited, or on page open). */
    fun setPhrases(list: List<PhrasebookStore.Phrase>) {
        phrases = list
        if (showPhrases) invalidate()
    }

    /** Jumps straight to the phrasebook page — used by the voice panel's long-press-emoji access point, and by double-tap-B on either panel when nothing was selected. */
    fun showPhrasebookPage() {
        showSymbols = false
        showEmoji = false
        showPhrases = true
        phraseScrollY = 0f
        rebuildRows()
    }

    /** Jumps straight to the emoji page — used by the voice panel's own emoji button, which has no room for a full emoji grid of its own. */
    fun showEmojiPage() {
        showSymbols = false
        showPhrases = false
        showEmoji = true
        emojiScrollY = 0f
        rebuildRows()
    }

    private fun bottomRow(togglePageLabel: String) = listOf(
        Key(KeyAction.SymbolsToggle, togglePageLabel, 1.1f),
        Key(KeyAction.Mic, "🎤", 0.7f),
        // Bold typeface on the label itself (see drawKey) is the only
        // active-state indicator — no accent fill, which would otherwise
        // paint the key red like Shift/Enter do for a state that isn't
        // actually urgent or exclusive.
        Key(KeyAction.BoldToggle, "B", 0.7f),
        Key(KeyAction.EmojiToggle, "☺", 0.7f),
        Key(KeyAction.Space, "", 3.0f),
        Key(KeyAction.Symbol('.'), ".", 0.7f),
        Key(KeyAction.Symbol(','), ",", 0.7f),
        Key(KeyAction.Enter, "⏎", 1.3f, accent = true),
    )

    private fun rebuildRows() {
        // showEmoji/showPhrases deliberately absent here — both are fully
        // separate scrollable widgets (see drawEmojiPage/handleEmojiTouch
        // and drawPhrasePage/handlePhraseTouch) that never touch
        // rows/keyRects at all. Keeping `rows` pointed at whatever page is
        // "underneath" means it's already correct the instant either
        // closes, with no extra rebuild.
        rows = when {
            showSymbols && symbolsPage == 2 -> symbolRows2()
            showSymbols -> symbolRows()
            else -> letterRows()
        }
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
        symbolsPage = 1
        showEmoji = false
        showPhrases = false
        phraseScrollY = 0f
        boldActive = false // fresh field: plain by default, until B is actually touched
        rebuildRows()
    }

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        if (showEmoji) {
            drawEmojiPage(canvas)
            return
        }
        if (showPhrases) {
            drawPhrasePage(canvas)
            return
        }
        for ((r, row) in rows.withIndex()) {
            for ((c, key) in row.withIndex()) {
                val rect = keyRects.getOrNull(r)?.getOrNull(c) ?: continue
                drawKey(canvas, key, rect, pressed = r == pressedRow && c == pressedCol)
            }
        }
    }

    // --- emoji page: category tabs (fixed) + scrollable glyph grid + exit footer ---

    private fun drawEmojiPage(canvas: Canvas) {
        val tabH = dp(EMOJI_TAB_HEIGHT_DP)
        val footerH = dp(EMOJI_FOOTER_HEIGHT_DP)
        val gridTop = tabH
        val gridBottom = height - footerH

        drawEmojiTabs(canvas, tabH)

        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        drawEmojiGrid(canvas, gridTop, gridBottom)
        canvas.restore()

        drawEmojiFooter(canvas, gridBottom, footerH)
    }

    private fun drawEmojiTabs(canvas: Canvas, tabH: Float) {
        val cols = EMOJI_CATEGORIES.size
        val w = width / cols.toFloat()
        label.textSize = dp(18f)
        val metrics = label.fontMetrics
        for ((i, cat) in EMOJI_CATEGORIES.withIndex()) {
            label.color = if (i == emojiCategoryIndex) fg else muted
            val cx = w * i + w / 2f
            canvas.drawText(cat.icon, cx, tabH / 2f - (metrics.ascent + metrics.descent) / 2, label)
        }
        stroke.color = accent
        stroke.strokeWidth = dp(2f)
        val selLeft = w * emojiCategoryIndex + dp(10f)
        val selRight = w * (emojiCategoryIndex + 1) - dp(10f)
        canvas.drawLine(selLeft, tabH - dp(2f), selRight, tabH - dp(2f), stroke)
    }

    private fun emojiCell(): Float = width / EMOJI_COLUMNS.toFloat()

    private fun emojiContentHeight(): Float {
        val count = EMOJI_CATEGORIES[emojiCategoryIndex].emojis.size
        val rowsCount = (count + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS
        return rowsCount * emojiCell()
    }

    private fun drawEmojiGrid(canvas: Canvas, top: Float, bottom: Float) {
        val cell = emojiCell()
        val emojis = EMOJI_CATEGORIES[emojiCategoryIndex].emojis
        label.color = fg
        label.textSize = cell * 0.5f
        val metrics = label.fontMetrics
        for ((i, glyph) in emojis.withIndex()) {
            val col = i % EMOJI_COLUMNS
            val row = i / EMOJI_COLUMNS
            val cy = top + row * cell + cell / 2f - emojiScrollY
            if (cy < top - cell || cy > bottom + cell) continue // cheap viewport cull
            val cx = col * cell + cell / 2f
            canvas.drawText(glyph, cx, cy - (metrics.ascent + metrics.descent) / 2, label)
        }
    }

    private fun drawEmojiFooter(canvas: Canvas, top: Float, footerH: Float) {
        label.color = muted
        label.textSize = dp(13f)
        label.letterSpacing = 0f
        val metrics = label.fontMetrics
        canvas.drawText("ABC", width / 2f, top + footerH / 2f - (metrics.ascent + metrics.descent) / 2, label)
    }

    /**
     * Drag-to-scroll + tap dispatch for the emoji page, entirely separate
     * from [keyAt]/the generic key-grid touch handling below — this page
     * has variable content height (hundreds of glyphs per category) that
     * doesn't fit the fixed-row assumption the rest of this view relies on.
     */
    private fun handleEmojiTouch(event: MotionEvent): Boolean {
        val tabH = dp(EMOJI_TAB_HEIGHT_DP)
        val footerH = dp(EMOJI_FOOTER_HEIGHT_DP)
        val gridTop = tabH
        val gridBottom = height - footerH

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                emojiDownX = event.x
                emojiDownY = event.y
                emojiLastTouchY = event.y
                emojiDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!emojiDragging && kotlin.math.abs(event.y - emojiDownY) > dp(6f)) {
                    emojiDragging = true
                }
                if (emojiDragging && emojiDownY in gridTop..gridBottom) {
                    val dy = event.y - emojiLastTouchY
                    val maxScroll = (emojiContentHeight() - (gridBottom - gridTop)).coerceAtLeast(0f)
                    emojiScrollY = (emojiScrollY - dy).coerceIn(0f, maxScroll)
                    emojiLastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!emojiDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val y = event.y
                    when {
                        y <= tabH -> {
                            val cols = EMOJI_CATEGORIES.size
                            val idx = (event.x / (width / cols.toFloat())).toInt().coerceIn(0, cols - 1)
                            if (idx != emojiCategoryIndex) {
                                emojiCategoryIndex = idx
                                emojiScrollY = 0f
                            }
                            haptic()
                            invalidate()
                        }
                        y >= gridBottom -> {
                            haptic()
                            showEmoji = false
                            rebuildRows()
                        }
                        else -> {
                            val cell = emojiCell()
                            val col = (event.x / cell).toInt().coerceIn(0, EMOJI_COLUMNS - 1)
                            val row = ((y - gridTop + emojiScrollY) / cell).toInt()
                            val idx = row * EMOJI_COLUMNS + col
                            val list = EMOJI_CATEGORIES[emojiCategoryIndex].emojis
                            if (idx in list.indices) {
                                haptic()
                                onKey?.invoke(KeyAction.Emoji(list[idx]))
                            }
                        }
                    }
                }
                emojiDragging = false
                return true
            }
        }
        return true
    }

    private fun haptic() {
        if (KeyboardSettings.hapticEnabled(context)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // --- phrasebook page: header (Record/Manage) + scrollable directory of
    // ALL saved phrases, each with an inline delete + footer exit. Same
    // drag-to-scroll approach as the emoji page, for the same reason: a
    // real directory of every saved phrase doesn't fit the fixed-row-count
    // grid the rest of this view assumes.

    private fun drawPhrasePage(canvas: Canvas) {
        val headerH = dp(PHRASE_HEADER_HEIGHT_DP)
        val footerH = dp(PHRASE_FOOTER_HEIGHT_DP)
        val listTop = headerH
        val listBottom = height - footerH

        drawPhraseHeader(canvas, headerH)

        canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), listBottom)
        drawPhraseList(canvas, listTop, listBottom)
        canvas.restore()

        drawPhraseFooter(canvas, listBottom, footerH)
    }

    private fun drawPhraseHeader(canvas: Canvas, headerH: Float) {
        label.textAlign = Paint.Align.CENTER
        label.textSize = dp(13f)
        label.letterSpacing = 0f
        label.color = muted
        val metrics = label.fontMetrics
        val ty = headerH / 2f - (metrics.ascent + metrics.descent) / 2
        canvas.drawText("🎙 Record", width / 4f, ty, label)
        canvas.drawText("📋 Manage", width * 3f / 4f, ty, label)
        stroke.color = muted
        stroke.alpha = 70
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(width / 2f, dp(8f), width / 2f, headerH - dp(8f), stroke)
        canvas.drawLine(0f, headerH, width.toFloat(), headerH, stroke)
        stroke.alpha = 255
    }

    private fun phraseRowHeight() = dp(PHRASE_ROW_HEIGHT_DP)
    private fun phraseTrashZoneWidth() = dp(44f)
    private fun phraseContentHeight() = phrases.size * phraseRowHeight()

    private fun drawPhraseList(canvas: Canvas, top: Float, bottom: Float) {
        if (phrases.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            label.color = muted
            label.textSize = dp(13f)
            val metrics = label.fontMetrics
            canvas.drawText(
                context.getString(R.string.phrasebook_empty),
                width / 2f,
                (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2,
                label,
            )
            label.textAlign = Paint.Align.CENTER
            return
        }

        val rowH = phraseRowHeight()
        val trashW = phraseTrashZoneWidth()
        label.textAlign = Paint.Align.LEFT
        for ((i, phrase) in phrases.withIndex()) {
            val rowTop = top + i * rowH - phraseScrollY
            if (rowTop > bottom || rowTop + rowH < top) continue
            val rowCenter = rowTop + rowH / 2f

            label.textSize = dp(14.5f)
            label.color = fg
            val maxTextWidth = width - trashW - dp(32f)
            val metrics = label.fontMetrics
            canvas.drawText(
                ellipsize(phrase.text, maxTextWidth),
                dp(16f),
                rowCenter - (metrics.ascent + metrics.descent) / 2,
                label,
            )

            // Trash icon, right edge — its own tap zone (see handlePhraseTouch).
            val tx = width - trashW / 2f
            stroke.color = muted
            stroke.strokeWidth = dp(1.4f)
            val s = dp(6f)
            canvas.drawLine(tx - s, rowCenter - s, tx + s, rowCenter + s, stroke)
            canvas.drawLine(tx + s, rowCenter - s, tx - s, rowCenter + s, stroke)

            stroke.alpha = 40
            stroke.strokeWidth = dp(1f)
            canvas.drawLine(dp(16f), rowTop + rowH, width - dp(16f), rowTop + rowH, stroke)
            stroke.alpha = 255
        }
        label.textAlign = Paint.Align.CENTER
    }

    private fun drawPhraseFooter(canvas: Canvas, top: Float, footerH: Float) {
        label.color = muted
        label.textSize = dp(13f)
        label.letterSpacing = 0f
        val metrics = label.fontMetrics
        canvas.drawText("ABC", width / 2f, top + footerH / 2f - (metrics.ascent + metrics.descent) / 2, label)
    }

    /** Manual ellipsis — Canvas.drawText doesn't wrap or truncate on its own. */
    private fun ellipsize(text: String, maxWidth: Float): String {
        if (label.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && label.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun handlePhraseTouch(event: MotionEvent): Boolean {
        val headerH = dp(PHRASE_HEADER_HEIGHT_DP)
        val footerH = dp(PHRASE_FOOTER_HEIGHT_DP)
        val listTop = headerH
        val listBottom = height - footerH

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                phraseDownX = event.x
                phraseDownY = event.y
                phraseLastTouchY = event.y
                phraseDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!phraseDragging && kotlin.math.abs(event.y - phraseDownY) > dp(6f)) {
                    phraseDragging = true
                }
                if (phraseDragging && phraseDownY in listTop..listBottom) {
                    val dy = event.y - phraseLastTouchY
                    val maxScroll = (phraseContentHeight() - (listBottom - listTop)).coerceAtLeast(0f)
                    phraseScrollY = (phraseScrollY - dy).coerceIn(0f, maxScroll)
                    phraseLastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!phraseDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val y = event.y
                    when {
                        y <= headerH -> {
                            haptic()
                            onKey?.invoke(if (event.x < width / 2f) KeyAction.RecordPhrase else KeyAction.ManagePhrasebook)
                        }
                        y >= listBottom -> {
                            haptic()
                            showPhrases = false
                            rebuildRows()
                        }
                        else -> {
                            val rowH = phraseRowHeight()
                            val idx = ((y - listTop + phraseScrollY) / rowH).toInt()
                            val phrase = phrases.getOrNull(idx)
                            if (phrase != null) {
                                haptic()
                                if (event.x >= width - phraseTrashZoneWidth()) {
                                    onKey?.invoke(KeyAction.DeletePhrase(phrase.id))
                                } else {
                                    onKey?.invoke(KeyAction.Phrase(phrase.text))
                                }
                            }
                        }
                    }
                }
                phraseDragging = false
                return true
            }
        }
        return true
    }

    /**
     * Rounded only at the top corners — the bottom edge sits flush against
     * the screen/nav bar, where a curve would just look clipped, but the
     * top edge is where this surface visibly meets the host app.
     */
    private fun drawBackground(canvas: Canvas) {
        val r = dp(KeyboardPalette.TOP_CORNER_RADIUS_DP)
        bgPath.reset()
        bgPath.addRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        fill.color = bg
        canvas.drawPath(bgPath, fill)
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF, pressed: Boolean) {
        val disabled = key.action is KeyAction.Mic && !micEnabled

        val isEnter = key.action == KeyAction.Enter
        val keyAccent = if (isEnter) enterAccent else accent

        if (pressed && !disabled) {
            fill.color = if (key.accent) keyAccent else keyPressedBg
            fill.alpha = if (key.accent) 255 else 255
            val inset = dp(3f)
            canvas.drawRoundRect(
                rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                dp(8f), dp(8f), fill,
            )
        } else if (key.accent && isEnter) {
            // Enter stays a quiet accent outline at rest, filled only when pressed —
            // color marks "this key is different" without shouting on an idle keyboard.
            fill.color = keyAccent
            fill.alpha = 28
            val inset = dp(3f)
            canvas.drawRoundRect(
                rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                dp(8f), dp(8f), fill,
            )
        } else if (key.action == KeyAction.Space) {
            // Space has no label — without some boundary it doesn't read as
            // a key at all, just empty keyboard background. A low-contrast
            // outline (not a fill, to stay quiet like every other key at
            // rest) is enough to mark it as a distinct tap target.
            stroke.color = muted
            stroke.alpha = 90
            stroke.strokeWidth = dp(1.3f)
            val inset = dp(3f)
            canvas.drawRoundRect(
                rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                dp(8f), dp(8f), stroke,
            )
        }

        label.color = when {
            disabled -> muted
            key.action is KeyAction.Shift && key.accent -> accent
            key.accent -> if (pressed) Color.WHITE else keyAccent
            // Static colour, same as the other utility glyphs — B's
            // function depends entirely on the current selection (bold vs
            // un-bold) or the toggle for text typed/dictated afterward,
            // neither of which a fixed key colour could represent
            // meaningfully anyway. Matches VoiceKeyboardView's B exactly.
            key.action is KeyAction.SymbolsToggle || key.action is KeyAction.Mic || key.action is KeyAction.EmojiToggle
                || key.action is KeyAction.MoreSymbols || key.action == KeyAction.BoldToggle
            -> muted
            else -> fg
        }
        label.textSize = when (key.action) {
            is KeyAction.SymbolsToggle, is KeyAction.MoreSymbols -> dp(15f)
            is KeyAction.Shift, is KeyAction.Backspace, is KeyAction.Enter, is KeyAction.Mic, is KeyAction.EmojiToggle -> dp(21f)
            else -> dp(19.5f)
        }
        label.alpha = if (disabled) 120 else 255
        // Always bold, active or not — that's B's natural glyph, not a
        // state indicator; only the colour above (muted/fg) shows the
        // toggle state. Mirrors VoiceKeyboardView's B exactly.
        label.typeface = if (key.action == KeyAction.BoldToggle) boldTypeface else KeyboardPalette.typeface
        val metrics = label.fontMetrics
        val ty = rect.centerY() - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(key.label, rect.centerX(), ty, label)
    }

    // --- touch ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showEmoji) return handleEmojiTouch(event)
        if (showPhrases) return handlePhraseTouch(event)
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
                if (KeyboardSettings.hapticEnabled(context)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                when (action) {
                    is KeyAction.Backspace -> {
                        commit(action)
                        startBackspaceRepeat()
                    }
                    is KeyAction.Space -> {
                        spaceLongPressed = false
                        armLongPressSpace()
                    }
                    is KeyAction.SymbolsToggle -> {
                        settingsLongPressed = false
                        armLongPressSettings()
                    }
                    is KeyAction.EmojiToggle -> {
                        emojiLongPressed = false
                        armLongPressEmoji()
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_MOVE -> setPressed(row, col)

            MotionEvent.ACTION_UP -> {
                val action = rows[row][col].action
                cancelLongPressSpace()
                cancelLongPressSettings()
                cancelLongPressEmoji()
                stopBackspaceRepeat()
                when (action) {
                    is KeyAction.Backspace -> Unit // already handled on DOWN + repeat
                    is KeyAction.Space -> if (!spaceLongPressed) onSpaceTapped()
                    is KeyAction.SymbolsToggle -> if (!settingsLongPressed) commit(action)
                    is KeyAction.EmojiToggle -> if (!emojiLongPressed) commit(action)
                    // Double-tap-aware — see onBoldTapped.
                    is KeyAction.BoldToggle -> onBoldTapped()
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
                cancelLongPressSettings()
                cancelLongPressEmoji()
                stopBackspaceRepeat()
                clearPress()
            }
        }
        return true
    }

    private fun commit(action: KeyAction) {
        when (action) {
            is KeyAction.Shift -> onShiftTapped()
            is KeyAction.SymbolsToggle -> {
                // "?123" (from letters) opens the symbols page; "ABC" (shown
                // on the symbols, emoji, and phrasebook pages) always
                // returns straight to letters, regardless of which alt page
                // it was tapped from.
                if (showSymbols || showEmoji || showPhrases) {
                    showSymbols = false
                    symbolsPage = 1
                    showEmoji = false
                    showPhrases = false
                } else {
                    showSymbols = true
                }
                rebuildRows()
            }
            is KeyAction.MoreSymbols -> {
                symbolsPage = if (symbolsPage == 1) 2 else 1
                rebuildRows()
            }
            is KeyAction.EmojiToggle -> {
                showEmoji = !showEmoji
                if (showEmoji) {
                    showSymbols = false
                    showPhrases = false
                    emojiScrollY = 0f
                }
                rebuildRows()
            }
            is KeyAction.BoldToggle -> {
                boldActive = !boldActive
                rebuildRows() // Key.accent baked in per-row, so the highlight needs a rebuild, not just invalidate()
            }
            else -> {
                onKey?.invoke(action)
                if (shiftState == ShiftState.ONE_SHOT && action is KeyAction.Letter) {
                    setShiftState(ShiftState.NONE)
                }
            }
        }
    }

    /**
     * Two space taps landing within [DOUBLE_TAP_MS] of each other replace
     * the space just committed with ". " and one-shot-capitalize the next
     * letter, matching Gboard. Timing-based on the space key alone rather
     * than tracking committed text — since this is the same key both times,
     * whatever was committed just before the second tap can only have been
     * the first tap's space.
     */
    private fun onSpaceTapped() {
        val now = System.currentTimeMillis()
        val doubleTapped = (now - lastSpaceTapAt) < DOUBLE_TAP_MS
        if (doubleTapped) {
            lastSpaceTapAt = 0L // consumed — a third rapid tap starts fresh, not a chained period
            onKey?.invoke(KeyAction.SpacePeriod)
            setShiftState(ShiftState.ONE_SHOT)
        } else {
            lastSpaceTapAt = now
            commit(KeyAction.Space)
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

    private fun armLongPressSettings() {
        cancelLongPressSettings()
        val r = Runnable {
            settingsLongPressed = true
            onLongPressSettings?.invoke()
        }
        longPressSettingsRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelLongPressSettings() {
        longPressSettingsRunnable?.let { handler.removeCallbacks(it) }
        longPressSettingsRunnable = null
    }

    /** Long-pressing "☺" opens the phrasebook page instead of toggling emoji — a normal tap still toggles emoji. */
    private fun armLongPressEmoji() {
        cancelLongPressEmoji()
        val r = Runnable {
            emojiLongPressed = true
            if (KeyboardSettings.hapticEnabled(context)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            showEmoji = false
            showSymbols = false
            showPhrases = true
            rebuildRows()
        }
        longPressEmojiRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelLongPressEmoji() {
        longPressEmojiRunnable?.let { handler.removeCallbacks(it) }
        longPressEmojiRunnable = null
    }

    /**
     * "B" tap-release handler — every tap toggles bold immediately (and
     * bolds the current selection, if any) with no delay, exactly like any
     * other key; a following second tap within [DOUBLE_TAP_MS] additionally
     * fires [onDoubleTapBoldSave] first (using the selection text the
     * service cached from *this* tap's own bolding — the live selection is
     * already gone by then, replaced by the bolded text). An earlier
     * version delayed every single tap to disambiguate the double-tap
     * first, which made bold visibly lag on ordinary typing — worth
     * avoiding since a delay here would misread as "bold doesn't work."
     */
    private fun onBoldTapped() {
        val now = System.currentTimeMillis()
        val doubleTapped = now - lastBoldTapUpAtMs < DOUBLE_TAP_MS
        lastBoldTapUpAtMs = now
        if (doubleTapped) {
            val saved = onDoubleTapBoldSave?.invoke() ?: false
            if (!saved) showPhrasebookPage()
            haptic()
        }
        commit(KeyAction.BoldToggle)
        onToggleBold?.invoke()
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
        cancelLongPressSettings()
        cancelLongPressEmoji()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ROWS = 4
        const val ROW_HEIGHT_DP = 58f
        const val PADDING_DP = 9f
        const val ROW_GAP_DP = 6f
        const val COL_GAP_DP = 5f
        const val DOUBLE_TAP_MS = 350L
        const val LONG_PRESS_MS = 400L
        const val BACKSPACE_REPEAT_INITIAL_MS = 450L
        const val BACKSPACE_REPEAT_MS = 60L

        const val EMOJI_TAB_HEIGHT_DP = 38f
        const val EMOJI_FOOTER_HEIGHT_DP = 34f
        const val EMOJI_COLUMNS = 8

        const val PHRASE_HEADER_HEIGHT_DP = 38f
        const val PHRASE_FOOTER_HEIGHT_DP = 34f
        const val PHRASE_ROW_HEIGHT_DP = 50f
    }
}
