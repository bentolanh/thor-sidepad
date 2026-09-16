package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * An interactive guide over the pad's screen. Each step asks for one real gesture and waits
 * for it; the service then performs the real action. A tap in the middle skips the guide.
 *
 * Steps: 1 pull down (opens the panel), 2 pull up (hides the pad), 3 pull up (shows it again).
 */
class GuideView(ctx: Context, private val step: Int, private val onGesture: (EdgeGesture) -> Unit, private val onSkip: () -> Unit) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7DFF.toInt() }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val path = Path()

    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var edge: EdgeGesture? = null
    private var dragY = 0f   // live finger offset so the dot follows the finger

    private val wantsDown get() = step == 1

    private fun arrow(c: Canvas, cx: Float, fromY: Float, toY: Float, r: Float, dotOffset: Float) {
        stroke.strokeWidth = r * 0.16f
        c.drawLine(cx, fromY, cx, toY, stroke)
        val dir = if (toY > fromY) 1f else -1f
        path.reset()
        path.moveTo(cx - r * 0.55f, toY - dir * r * 0.55f)
        path.lineTo(cx, toY)
        path.lineTo(cx + r * 0.55f, toY - dir * r * 0.55f)
        c.drawPath(path, stroke)
        c.drawCircle(cx, fromY + dotOffset, r * 0.32f, fill)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xE0101418.toInt())
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.06f
        title.textSize = r * 0.75f; text.textSize = r * 0.55f
        val pw = w * 0.18f; val ph = 8f
        c.drawRoundRect((w - pw) / 2, 10f, (w + pw) / 2, 10f + ph, ph, ph, pill)
        c.drawRoundRect((w - pw) / 2, h - 10f - ph, (w + pw) / 2, h - 10f, ph, ph, pill)

        c.drawText("Step $step of 3", w / 2, h * 0.47f, text)
        c.drawText("Tap here to skip the guide", w / 2, h * 0.53f, text)

        if (wantsDown) {
            arrow(c, w / 2, h * 0.06f, h * 0.24f, r, dragY.coerceIn(0f, h * 0.18f))
            c.drawText("Pull down from the top edge", w / 2, h * 0.33f, title)
            c.drawText("Try it now: it opens the SidePad panel", w / 2, h * 0.33f + r * 0.9f, text)
        } else {
            arrow(c, w / 2, h * 0.94f, h * 0.76f, r, dragY.coerceIn(-h * 0.18f, 0f))
            c.drawText("Pull up from the bottom edge", w / 2, h * 0.64f, title)
            c.drawText(if (step == 2) "Try it now: it hides the pad" else "Once more: it brings the pad back", w / 2, h * 0.64f + r * 0.9f, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val zone = min(width, height) * ShieldPadView.EDGE_ZONE
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; t0 = e.eventTime; dragY = 0f
                edge = when {
                    e.y < zone -> EdgeGesture.PULL_DOWN
                    e.y > height - zone -> EdgeGesture.PULL_UP
                    else -> null
                }
            }
            MotionEvent.ACTION_MOVE -> if (edge != null) { dragY = e.y - y0; invalidate() }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - x0; val dy = e.y - y0
                val need = min(width, height) * ShieldPadView.SWIPE_LEN
                val fast = e.eventTime - t0 < ShieldPadView.SWIPE_MAX_MS
                val g = edge
                dragY = 0f; edge = null; invalidate()
                if (g == EdgeGesture.PULL_DOWN && wantsDown && dy > need && abs(dx) < dy && fast) onGesture(g)
                else if (g == EdgeGesture.PULL_UP && !wantsDown && -dy > need && abs(dx) < -dy && fast) onGesture(g)
                else if (g == null && hypot(dx, dy) < 30f && abs(e.y - height / 2f) < height * 0.12f) onSkip()
            }
            MotionEvent.ACTION_CANCEL -> { dragY = 0f; edge = null; invalidate() }
        }
        return true
    }
}
