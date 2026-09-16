package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * A one-screen guide drawn over the pad's screen: an arrow at the top edge for the panel, an
 * arrow at the bottom edge for show/hide. Any tap dismisses it.
 */
class GuideView(ctx: Context, private val onDismiss: () -> Unit) : View(ctx) {
    private val scrim = Paint().apply { color = 0xE0101418.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7DFF.toInt() }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val path = Path()

    private fun arrow(c: Canvas, cx: Float, fromY: Float, toY: Float, r: Float) {
        stroke.strokeWidth = r * 0.16f
        c.drawLine(cx, fromY, cx, toY, stroke)
        val dir = if (toY > fromY) 1f else -1f
        path.reset()
        path.moveTo(cx - r * 0.55f, toY - dir * r * 0.55f)
        path.lineTo(cx, toY)
        path.lineTo(cx + r * 0.55f, toY - dir * r * 0.55f)
        c.drawPath(path, stroke)
        c.drawCircle(cx, fromY, r * 0.32f, fill)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(scrim.color)
        val w = width.toFloat(); val h = height.toFloat()
        val r = minOf(w, h) * 0.06f
        title.textSize = r * 0.75f; text.textSize = r * 0.55f
        // Edge pills, as the pad draws them.
        val pw = w * 0.18f; val ph = 8f
        c.drawRoundRect((w - pw) / 2, 10f, (w + pw) / 2, 10f + ph, ph, ph, pill)
        c.drawRoundRect((w - pw) / 2, h - 10f - ph, (w + pw) / 2, h - 10f, ph, ph, pill)

        // Top: pull down for the panel.
        arrow(c, w / 2, h * 0.06f, h * 0.24f, r)
        c.drawText("Pull down from the top edge", w / 2, h * 0.33f, title)
        c.drawText("opens the SidePad panel: controller, shield, looks, edit layout", w / 2, h * 0.33f + r * 0.9f, text)

        // Bottom: pull up to show or hide.
        arrow(c, w / 2, h * 0.94f, h * 0.76f, r)
        c.drawText("Pull up from the bottom edge", w / 2, h * 0.64f, title)
        c.drawText("shows or hides the pad", w / 2, h * 0.64f + r * 0.9f, text)

        c.drawText("Tap anywhere to close", w / 2, h * 0.50f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) onDismiss()
        return true
    }
}
