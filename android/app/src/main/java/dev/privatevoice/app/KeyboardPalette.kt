package dev.privatevoice.app

import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils

/**
 * Shared look for [TextKeyboardView] and [VoiceKeyboardView] — centralised
 * so the two canvas-drawn surfaces stay pixel-identical rather than each
 * keeping its own copy of the same hex constants and drifting apart over
 * time.
 *
 * Palette: light mode is a warm "burnt paper" beige, not a neutral white,
 * with dark warm-brown text. Dark mode trades that warm grey for a cool,
 * desaturated blue-grey — a "brushed metal" hue rather than flat black —
 * with plain white text; beige-on-grey read as too low-contrast/muddy in
 * practice, and plain white still contrasts cleanly against the blue-grey.
 * A third, pure-AMOLED-black variant ([KeyboardSettings.Theme.BLACK]) swaps
 * just the background to true black; [muted]/[ring]/[keyPressedBg] all
 * derive from [bg] via blend, so they shift darker with it automatically.
 */
object KeyboardPalette {

    private val LIGHT_BG = Color.parseColor("#EDE3CE")
    private val LIGHT_FG = Color.parseColor("#2B2620")
    private val DARK_BG = Color.parseColor("#242B33")
    private val BLACK_BG = Color.parseColor("#000000")
    private val DARK_FG = Color.parseColor("#FFFFFF")

    fun bg(dark: Boolean, black: Boolean = false): Int = when {
        dark && black -> BLACK_BG
        dark -> DARK_BG
        else -> LIGHT_BG
    }
    fun fg(dark: Boolean): Int = if (dark) DARK_FG else LIGHT_FG

    /** Secondary/low-emphasis text and strokes — [fg] blended toward [bg], not a separate hand-picked grey, so it always reads as "quieter fg" rather than an arbitrary tone. */
    fun muted(dark: Boolean, black: Boolean = false): Int = ColorUtils.blendARGB(fg(dark), bg(dark, black), 0.45f)

    /** Resting stroke for outline-only elements (mic ring, space bar outline) — subtler than [muted]. */
    fun ring(dark: Boolean, black: Boolean = false): Int = ColorUtils.blendARGB(fg(dark), bg(dark, black), 0.82f)

    /** Pressed/highlighted key fill — [bg] lifted slightly toward [fg]. */
    fun keyPressedBg(dark: Boolean, black: Boolean = false): Int = ColorUtils.blendARGB(bg(dark, black), fg(dark), 0.12f)

    /**
     * A classic serif — no bundled font file: the platform's built-in
     * "serif" family (Noto Serif/Droid Serif depending on OS version) reads
     * as a traditional book/newspaper serif and keeps zero asset/licensing
     * overhead.
     */
    val typeface: Typeface = Typeface.create("serif", Typeface.NORMAL)

    /**
     * Rounded only where the keyboard actually has a visible corner — the
     * top edge, where it meets the host app rather than the screen's own
     * bottom edge/nav bar. A flat rectangle read as a plain overlay; this
     * is the one shape cue that says "this is its own surface."
     */
    const val TOP_CORNER_RADIUS_DP = 18f
}
