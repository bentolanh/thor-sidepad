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
