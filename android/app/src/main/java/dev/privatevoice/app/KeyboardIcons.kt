package dev.privatevoice.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Shared vector glyphs for the keyboard's utility keys — mic, emoji, lock —
 * replacing the raw emoji characters ("🎤", "☺", "🔒") both panels used to
 * draw as text. Emoji glyphs render inconsistently across devices/fonts
 * (different weight, different color treatment per platform emoji set) and
 * read as an odd mix with the rest of the keyboard's own flat, monochrome
 * icon language (the backspace pentagon in [VoiceKeyboardView], the ↺ undo
 * arrow) — these are hand-drawn instead, one tint, same stroke language as
 * the rest of the keyboard.
 *
 * Every function is zero-allocation: callers pass in their own scratch
 * [Path]/[RectF] and reusable fill/stroke [Paint]s (both views already keep
 * these as fields for exactly this reason) rather than this object owning
 * any state itself. [color]/[alpha] are applied to both paints before
 * drawing, so a single call site controls tint the same way the old
 * `label.color`/`label.alpha` pair did for the text glyphs these replace.
 */
object KeyboardIcons {

    /**
     * Capsule + cradle arc + stem — the soft, rounded mic silhouette.
     * [size] is the icon's overall height in px. This is
     * [VoiceKeyboardView]'s central mic button only — kept deliberately
     * soft/rounded there per explicit design feedback ("the mic icon in the
     * voice panel was great"). [TextKeyboardView]'s small utility-row mic
     * uses [drawMicAngular] instead, a distinct, more geometric shape.
     */
    fun drawMic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        fill: Paint,
        stroke: Paint,
        path: Path,
        rect: RectF,
    ) {
        val capsuleH = size * 0.62f
        val capsuleW = size * 0.40f
        val capsuleCy = cy - size * 0.10f

        path.reset()
        rect.set(cx - capsuleW / 2f, capsuleCy - capsuleH / 2f, cx + capsuleW / 2f, capsuleCy + capsuleH / 2f)
        path.addRoundRect(rect, capsuleW / 2f, capsuleW / 2f, Path.Direction.CW)
        fill.color = color
        fill.alpha = alpha
        canvas.drawPath(path, fill)

        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.09f
        stroke.strokeCap = Paint.Cap.ROUND
        val cradleR = capsuleW * 0.95f
        val cradleCy = capsuleCy + capsuleH / 2f - size * 0.02f
        rect.set(cx - cradleR, cradleCy - cradleR, cx + cradleR, cradleCy + cradleR)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        val stemBottom = cradleCy + cradleR + size * 0.16f
        canvas.drawLine(cx, cradleCy + cradleR, cx, stemBottom, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * Angular body + a straight-sided bracket stand + stem — a more
     * geometric mic than [drawMic]'s soft rounded capsule, for
     * [TextKeyboardView]'s small utility-row mic specifically (the flat,
     * straight-edged glyph language the rest of that row already uses —
     * backspace's pentagon, the lock's rectangular body). The top of the
     * body keeps a full curve (a dome, same radius as the old capsule)
     * while the bottom corners and the stand below stay sharp — an
     * intentional curved-top/angular-elsewhere hybrid, not a fully soft or
     * fully angular shape.
     */
    fun drawMicAngular(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        fill: Paint,
        stroke: Paint,
        path: Path,
        rect: RectF,
    ) {
        val bodyH = size * 0.58f
        val bodyW = size * 0.36f
        val bodyCy = cy - size * 0.09f
        val domeR = bodyW / 2f
        val bottomR = bodyW * 0.14f

        path.reset()
        rect.set(cx - bodyW / 2f, bodyCy - bodyH / 2f, cx + bodyW / 2f, bodyCy + bodyH / 2f)
        path.addRoundRect(
            rect,
            floatArrayOf(domeR, domeR, domeR, domeR, bottomR, bottomR, bottomR, bottomR),
            Path.Direction.CW,
        )
        fill.color = color
        fill.alpha = alpha
        canvas.drawPath(path, fill)

        // Stand: a straight-sided bracket (open top) instead of a curved
        // cradle — two verticals and a floor, sharp corners.
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.09f
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
        val standHalfW = bodyW * 0.8f
        val standTop = bodyCy + bodyH / 2f - size * 0.03f
        val standBottom = standTop + size * 0.22f
        path.reset()
        path.moveTo(cx - standHalfW, standTop)
        path.lineTo(cx - standHalfW, standBottom)
        path.lineTo(cx + standHalfW, standBottom)
        path.lineTo(cx + standHalfW, standTop)
        canvas.drawPath(path, stroke)

        val stemBottom = standBottom + size * 0.14f
        canvas.drawLine(cx, standBottom, cx, stemBottom, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * A closed book with a tiny smiley on the cover — stands in for the old
     * "☺" glyph on both the emoji-panel toggle ([TextKeyboardView]) and the
     * voice panel's emoji/phrasebook key ([VoiceKeyboardView]). The book
     * shape carries the *other* half of what this key does on both panels
     * (long-press opens the phrasebook) rather than being a plain face with
     * no connection to that gesture.
     */
    fun drawEmoji(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        stroke: Paint,
        fill: Paint,
        rect: RectF,
    ) {
        val bookW = size * 0.64f
        val bookH = size * 0.78f
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.065f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND

        // Cover.
        rect.set(cx - bookW / 2f, cy - bookH / 2f, cx + bookW / 2f, cy + bookH / 2f)
        canvas.drawRoundRect(rect, size * 0.06f, size * 0.06f, stroke)

        // Spine, just inside the left edge.
        val spineX = cx - bookW / 2f + bookW * 0.24f
        canvas.drawLine(spineX, cy - bookH / 2f + size * 0.05f, spineX, cy + bookH / 2f - size * 0.05f, stroke)

        // Smiley, centred in the page area to the right of the spine.
        val faceCx = cx + bookW * 0.09f
        val faceCy = cy + size * 0.02f
        fill.color = color
        fill.alpha = alpha
        val eyeR = size * 0.03f
        val eyeDx = size * 0.085f
        val eyeCy = faceCy - size * 0.07f
        canvas.drawCircle(faceCx - eyeDx, eyeCy, eyeR, fill)
        canvas.drawCircle(faceCx + eyeDx, eyeCy, eyeR, fill)

        val smileR = size * 0.1f
        rect.set(faceCx - smileR, faceCy - smileR * 0.1f, faceCx + smileR, faceCy + smileR * 0.9f)
        canvas.drawArc(rect, 20f, 140f, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    /**
     * A shaft + open chevron arrowhead, rotatable — one shared base shape
     * for directional/action glyphs that read better as a plain arrow than
     * a pictogram: [TextKeyboardView]'s caps/shift key (rotated to point
     * up) and [VoiceKeyboardView]'s Enter key (pointing right). [angleDeg]
     * follows [Canvas.rotate]'s convention: clockwise degrees, 0 points
     * along +x (right).
     */
    fun drawArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        stroke: Paint,
        path: Path,
        angleDeg: Float,
    ) {
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.13f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND

        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        val len = size * 0.32f
        path.reset()
        path.moveTo(cx - len, cy)
        path.lineTo(cx + len, cy)
        canvas.drawPath(path, stroke)

        val headLen = size * 0.2f
        path.reset()
        path.moveTo(cx + len - headLen, cy - headLen)
        path.lineTo(cx + len, cy)
        path.lineTo(cx + len - headLen, cy + headLen)
        canvas.drawPath(path, stroke)
        canvas.restore()

        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    /**
     * The classic backspace glyph: a rectangle with its left edge pulled to
     * a point (reads as "arrow pointing left," the direction backspace
     * deletes in) with an "×" inside it — not a plain [drawArrow], since
     * "arrow" alone doesn't read as *delete* the way the arrow-plus-cross
     * combination does. Same stroke language (weight, rounded joins) as
     * the rest of this object's icons, replacing the old thin hand-drawn
     * pentagon-and-lines version each panel used to draw separately.
     * Outline and cross are both [color] — same fixed colour as B (muted,
     * from both call sites), not two-tone.
     */
    fun drawBackspace(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        stroke: Paint,
        path: Path,
    ) {
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.09f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND

        val halfW = size * 0.36f
        val halfH = size * 0.26f
        val taper = size * 0.22f

        path.reset()
        path.moveTo(cx - halfW + taper, cy - halfH)
        path.lineTo(cx + halfW, cy - halfH)
        path.lineTo(cx + halfW, cy + halfH)
        path.lineTo(cx - halfW + taper, cy + halfH)
        path.lineTo(cx - halfW, cy)
        path.close()
        canvas.drawPath(path, stroke)

        val crossR = size * 0.1f
        val crossCx = cx + halfW * 0.2f
        path.reset()
        path.moveTo(crossCx - crossR, cy - crossR)
        path.lineTo(crossCx + crossR, cy + crossR)
        canvas.drawPath(path, stroke)
        path.reset()
        path.moveTo(crossCx + crossR, cy - crossR)
        path.lineTo(crossCx - crossR, cy + crossR)
        canvas.drawPath(path, stroke)

        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    /**
     * Shackle + body + keyhole. Stands in for the old "🔒" glyph — the voice
     * panel's live privacy-permissions readout ([VoiceKeyboardView.drawPrivacyGlyph]).
     */
    fun drawLock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        alpha: Int,
        fill: Paint,
        stroke: Paint,
        rect: RectF,
    ) {
        val bodyW = size * 0.62f
        val bodyH = size * 0.46f
        val bodyTop = cy + size * 0.02f

        val shackleR = bodyW * 0.36f
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.1f
        stroke.strokeCap = Paint.Cap.ROUND
        val shackleCy = bodyTop - size * 0.02f
        rect.set(cx - shackleR, shackleCy - shackleR, cx + shackleR, shackleCy + shackleR)
        canvas.drawArc(rect, 180f, 180f, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        fill.color = color
        fill.alpha = alpha
        rect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        canvas.drawRoundRect(rect, size * 0.08f, size * 0.08f, fill)

        // Keyhole: a dim dot at reduced alpha rather than full body colour,
        // so it reads as a shadowed detail instead of a second solid shape.
        fill.alpha = (alpha * 0.35f).toInt()
        canvas.drawCircle(cx, bodyTop + bodyH * 0.5f, size * 0.045f, fill)
    }
}
