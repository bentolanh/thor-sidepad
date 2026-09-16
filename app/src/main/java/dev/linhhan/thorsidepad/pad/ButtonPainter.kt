package dev.linhhan.thorsidepad.pad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint

/** Draws one round pad button. Shared by the per-button windows, the shield, and the editor. */
class ButtonPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val dash = DashPathEffect(floatArrayOf(12f, 9f), 0f)

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
