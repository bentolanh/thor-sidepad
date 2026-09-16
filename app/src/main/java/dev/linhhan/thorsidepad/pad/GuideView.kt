package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * An interactive guide over the pad's screen. Each step asks for one real gesture and waits
 * for it; the service then performs the real action. After a success the step shows a short
 * "Good" beat before moving on. A tap on "Skip" ends the guide.
 *
 * Steps: 1 pull down (panel), 2 pull up (hide), 3 pull up (show),
 *        4 tap the shield button (off), 5 tap it again (on).
 */
class GuideView(
    ctx: Context,
    private val step: Int,
    private val spot: FloatArray?,            // shield button: cx, cy, r in px (steps 4-5)
    private val onGesture: (EdgeGesture) -> Unit,
    private val onSpotTap: () -> Unit,
    private val onSkip: () -> Unit,
) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7DFF.toInt() }
    private val good = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DDC84.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val skipBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val path = Path()
    private val painter = ButtonPainter()

    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var edge: EdgeGesture? = null
    private var dragY = 0f
    private var doneText: String? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }   // needed for the CLEAR hole

    /** Freeze the step and show a green tick with [msg]; input is ignored until the service moves on. */
    fun showDone(msg: String) { doneText = msg; invalidate() }

    private val wantsDown get() = step == 1
    private val wantsUp get() = step == 2 || step == 3
    private val wantsSpot get() = (step == 4 || step == 5) && spot != null

    private fun arrow(c: Canvas, cx: Float, fromY: Float, toY: Float, r: Float, dotOffset: Float) {
        stroke.strokeWidth = r * 0.16f
        c.drawLine(cx, fromY, cx, toY, stroke)
        val dir = if (toY > fromY) 1f else -1f
        path.reset()
        path.moveTo(cx - r * 0.55f, toY - dir * r * 0.55f); path.lineTo(cx, toY); path.lineTo(cx + r * 0.55f, toY - dir * r * 0.55f)
        c.drawPath(path, stroke)
        c.drawCircle(cx, fromY + dotOffset, r * 0.32f, fill)
    }

    private fun tick(c: Canvas, cx: Float, cy: Float, r: Float) {
        good.strokeWidth = r * 0.18f
        c.drawCircle(cx, cy, r, good)
        c.drawLine(cx - r * 0.45f, cy, cx - r * 0.1f, cy + r * 0.38f, good)
        c.drawLine(cx - r * 0.1f, cy + r * 0.38f, cx + r * 0.5f, cy - r * 0.35f, good)
    }

    /** The step label and Skip box sit mid-screen, or low on the screen when the shield spot needs the middle. */
    private fun labelY(): Float = if (wantsSpot) height * 0.88f else height * 0.50f
    private fun skipRect(): FloatArray { val w = width.toFloat(); val y = labelY(); return floatArrayOf(w * 0.5f - 130f, y + 20f, w * 0.5f + 130f, y + 90f) }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xE0101418.toInt())
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.06f
        title.textSize = r * 0.75f; text.textSize = r * 0.55f
        val pw = w * 0.18f; val ph = 8f
        c.drawRoundRect((w - pw) / 2, 10f, (w + pw) / 2, 10f + ph, ph, ph, pill)
        c.drawRoundRect((w - pw) / 2, h - 10f - ph, (w + pw) / 2, h - 10f, ph, ph, pill)

        doneText?.let { msg ->
            tick(c, w / 2, h * 0.40f, r * 1.2f)
            c.drawText(msg, w / 2, h * 0.40f + r * 2.4f, title)
            return
        }

        c.drawText("Step $step of 5", w / 2, labelY(), text)
        val s = skipRect()
        c.drawRoundRect(s[0], s[1], s[2], s[3], 16f, 16f, skipBox)
        c.drawText("Skip the guide", w / 2, (s[1] + s[3]) / 2 + text.textSize * 0.35f, text)

        when {
            wantsDown -> {
                arrow(c, w / 2, h * 0.06f, h * 0.24f, r, dragY.coerceIn(0f, h * 0.18f))
                c.drawText("Pull down from the top edge", w / 2, h * 0.33f, title)
                c.drawText("Try it now: it opens the SidePad panel", w / 2, h * 0.33f + r * 0.9f, text)
            }
            wantsUp -> {
                arrow(c, w / 2, h * 0.94f, h * 0.76f, r, dragY.coerceIn(-h * 0.18f, 0f))
                c.drawText("Pull up from the bottom edge", w / 2, h * 0.66f, title)
                c.drawText(if (step == 2) "Try it now: it hides the pad" else "Once more: it brings the pad back", w / 2, h * 0.66f + r * 0.9f, text)
            }
            wantsSpot -> {
                val sp = spot!!
                c.drawCircle(sp[0], sp[1], sp[2] * 1.15f, hole)          // show the real button through the scrim
                // Draw the toggle ourselves too, so it is obvious even at a low button opacity.
                painter.drawShieldToggle(c, sp[0], sp[1], sp[2], on = step == 4)
                stroke.strokeWidth = r * 0.12f
                c.drawCircle(sp[0], sp[1], sp[2] * 1.45f, stroke)
                val ty = if (sp[1] < h / 2) h * 0.66f else h * 0.20f
                if (step == 4) {
                    c.drawText("This is the shield button", w / 2, ty, title)
                    c.drawText("Shield on: nothing behind the pad can be touched by accident.", w / 2, ty + r * 0.9f, text)
                    c.drawText("Tap it to turn the shield off.", w / 2, ty + r * 1.7f, text)
                } else {
                    c.drawText("Shield off", w / 2, ty, title)
                    c.drawText("Now only the buttons are covered; taps between them reach the app behind.", w / 2, ty + r * 0.9f, text)
                    c.drawText("Tap it again to turn the shield back on.", w / 2, ty + r * 1.7f, text)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doneText != null) return true
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
                val tap = hypot(dx, dy) < 30f
                val s = skipRect()
                when {
                    g == EdgeGesture.PULL_DOWN && wantsDown && dy > need && abs(dx) < dy && fast -> onGesture(g)
                    g == EdgeGesture.PULL_UP && wantsUp && -dy > need && abs(dx) < -dy && fast -> onGesture(g)
                    tap && wantsSpot && hypot(e.x - spot!![0], e.y - spot[1]) <= spot[2] * 1.3f -> onSpotTap()
                    tap && e.x in s[0]..s[2] && e.y in s[1]..s[3] -> onSkip()
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragY = 0f; edge = null; invalidate() }
        }
        return true
    }
}
