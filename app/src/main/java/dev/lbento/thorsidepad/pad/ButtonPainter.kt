package dev.lbento.thorsidepad.pad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

/** Draws one round pad button. Shared by the per-button windows, the shield, and the editor. */
class ButtonPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val dash = DashPathEffect(floatArrayOf(12f, 9f), 0f)

    private val shieldPath = Path()

    /** The shield toggle: a shield-shaped badge, filled blue with a tick when on, outlined when off. */
    fun drawShieldToggle(c: Canvas, cx: Float, cy: Float, r: Float, on: Boolean, selected: Boolean = false) {
        // Soft round base so it reads as a button.
        fill.color = if (on) 0xDD2E7DFF.toInt() else 0xAA202020.toInt()
        ring.pathEffect = null
        ring.strokeWidth = r * (if (selected) 0.14f else 0.08f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawCircle(cx, cy, r * 0.92f, fill)
        c.drawCircle(cx, cy, r * 0.92f, ring)
        // Shield glyph.
        val w = r * 0.55f; val top = cy - r * 0.5f; val bottom = cy + r * 0.55f
        shieldPath.reset()
        shieldPath.moveTo(cx - w, top)
        shieldPath.lineTo(cx + w, top)
        shieldPath.lineTo(cx + w, cy)
        shieldPath.quadTo(cx + w, bottom - r * 0.15f, cx, bottom)
        shieldPath.quadTo(cx - w, bottom - r * 0.15f, cx - w, cy)
        shieldPath.close()
        fill.color = if (on) Color.WHITE else 0x00000000
        ring.strokeWidth = r * 0.07f; ring.color = Color.WHITE
        if (on) c.drawPath(shieldPath, fill)
        c.drawPath(shieldPath, ring)
        if (on) {
            // Tick inside the shield.
            ring.color = 0xFF2E7DFF.toInt(); ring.strokeWidth = r * 0.1f
            c.drawLine(cx - w * 0.5f, cy, cx - w * 0.1f, cy + r * 0.22f, ring)
            c.drawLine(cx - w * 0.1f, cy + r * 0.22f, cx + w * 0.55f, cy - r * 0.25f, ring)
        }
    }

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }

    /** A Home/Back style button: round, label, and a small tag saying which screen it acts on. */
    fun drawSysButton(c: Canvas, cx: Float, cy: Float, r: Float, label: String, tag: String?, down: Boolean, selected: Boolean = false) {
        draw(c, cx, cy, r, "", true, down, selected)
        text.textSize = r * 0.42f
        c.drawText(label, cx, cy - (if (tag != null) r * 0.02f else 0f) - (text.descent() + text.ascent()) / 2 - (if (tag != null) r * 0.12f else 0f), text)
        if (tag != null) { small.textSize = r * 0.28f; c.drawText(tag, cx, cy + r * 0.42f, small) }
    }

    /**
     * A vertical slider: a track of height 2r (r = half the element size), a filled part up to
     * [level] (0..1), a knob, the symbol at the bottom and the screen tag under it.
     */
    fun drawSlider(c: Canvas, cx: Float, cy: Float, r: Float, symbol: String, tag: String?, level: Float, active: Boolean, selected: Boolean = false) {
        val top = cy - r * 0.9f; val bottom = cy + r * 0.55f
        val w = r * 0.34f
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, fill)
        val lv = level.coerceIn(0f, 1f)
        val ky = bottom - (bottom - top) * lv
        fill.color = if (active) 0xDD2E7DFF.toInt() else 0xAA6A8FBF.toInt()
        c.drawRoundRect(cx - w / 2, ky, cx + w / 2, bottom, w / 2, w / 2, fill)
        ring.pathEffect = null; ring.strokeWidth = r * (if (selected) 0.1f else 0.06f); ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, ring)
        fill.color = Color.WHITE
        c.drawCircle(cx, ky, w * 0.62f, fill)
        text.textSize = r * 0.42f
        c.drawText(symbol, cx, bottom + r * 0.42f, text)
        if (tag != null) { small.textSize = r * 0.26f; c.drawText(tag, cx, bottom + r * 0.7f, small) }
    }

    /** A stick: outer ring, knob offset by (kx, ky) in -1..1 of the ring radius. */
    fun drawStick(c: Canvas, cx: Float, cy: Float, r: Float, label: String, enabled: Boolean, kx: Float, ky: Float, selected: Boolean = false) {
        ring.strokeWidth = r * (if (selected) 0.10f else 0.05f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        ring.pathEffect = if (enabled) null else dash
        fill.color = if (enabled) 0x66202020 else 0x44444444
        c.drawCircle(cx, cy, r * 0.95f, fill)
        c.drawCircle(cx, cy, r * 0.95f, ring)
        val kr = r * 0.42f
        fill.color = if (kx != 0f || ky != 0f) 0xDD2E7DFF.toInt() else 0xCC303030.toInt()
        ring.pathEffect = null; ring.strokeWidth = r * 0.05f; ring.color = Color.WHITE
        val kcx = cx + kx * (r - kr) * 0.95f; val kcy = cy + ky * (r - kr) * 0.95f
        c.drawCircle(kcx, kcy, kr, fill)
        c.drawCircle(kcx, kcy, kr, ring)
        text.textSize = kr * 0.7f
        c.drawText(label, kcx, kcy - (text.descent() + text.ascent()) / 2, text)
    }

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, label: String, enabled: Boolean, down: Boolean, selected: Boolean = false) {
        ring.strokeWidth = r * (if (selected) 0.14f else 0.08f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        ring.pathEffect = if (enabled) null else dash
        fill.color = when {
            !enabled -> 0x66444444
            down -> 0xDD2E7DFF.toInt()
            else -> 0xAA202020.toInt()
        }
        c.drawCircle(cx, cy, r * 0.92f, fill)
        c.drawCircle(cx, cy, r * 0.92f, ring)
        text.textSize = if (label.length > 2) r * 0.5f else r * 0.8f
        c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }
}
