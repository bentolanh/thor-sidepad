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
 * for it; the service then performs the real action. A tap on "Skip" ends the guide.
 *
 * Steps: 1 pull down (panel), 2 pull up (show the pad), 3 pull up (hide it), 4 end screen. The pad is hidden at the start.
 */
class GuideView(
    ctx: Context,
    private val step: Int,
    private val onGesture: (EdgeGesture) -> Unit,
    private val onSkip: () -> Unit,
    private val pull: PullListener? = null,     // step 1: the panel follows the finger like the shade
) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7DFF.toInt() }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val skipBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0101418.toInt() }
    private val path = Path()

    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var edge: EdgeGesture? = null
    private var dragY = 0f

    private val wantsDown get() = step == 1
    private val wantsUp get() = step == 2 || step == 3
    private val isEnd get() = step == 4

    private fun arrow(c: Canvas, cx: Float, fromY: Float, toY: Float, r: Float, dotOffset: Float) {
        stroke.strokeWidth = r * 0.16f
        c.drawLine(cx, fromY, cx, toY, stroke)
        val dir = if (toY > fromY) 1f else -1f
        path.reset()
        path.moveTo(cx - r * 0.55f, toY - dir * r * 0.55f); path.lineTo(cx, toY); path.lineTo(cx + r * 0.55f, toY - dir * r * 0.55f)
        c.drawPath(path, stroke)
        c.drawCircle(cx, fromY + dotOffset, r * 0.32f, fill)
    }

    private fun labelY(): Float = height * 0.50f
    private fun skipRect(): FloatArray { val w = width.toFloat(); val y = labelY(); return floatArrayOf(w * 0.5f - 130f, y + 20f, w * 0.5f + 130f, y + 90f) }

    override fun onDraw(c: Canvas) {
        // Light while the pad is up (step 3) so the frosted pad stays visible; dark while it is hidden
        // (steps 1, 2 and the end screen) so the app behind does not distract.
        c.drawColor(if (step == 3) 0x50101418 else 0xE0101418.toInt())
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.06f
        title.textSize = r * 0.75f; text.textSize = r * 0.55f
        val pw = w * 0.18f; val ph = 8f
        c.drawRoundRect((w - pw) / 2, 10f, (w + pw) / 2, 10f + ph, ph, ph, pill)
        c.drawRoundRect((w - pw) / 2, h - 10f - ph, (w + pw) / 2, h - 10f, ph, ph, pill)

        if (isEnd) {
            c.drawText("You're all set", w / 2, h * 0.34f, title)
            c.drawText("Pull down from the top edge: the SidePad panel", w / 2, h * 0.34f + r * 1.3f, text)
            c.drawText("Pull up from the bottom edge: show or hide the pad", w / 2, h * 0.34f + r * 2.2f, text)
            val s = skipRect()
            c.drawRoundRect(s[0], s[1], s[2], s[3], 16f, 16f, skipBox)
            c.drawText("Done", w / 2, (s[1] + s[3]) / 2 + text.textSize * 0.35f, text)
            return
        }
        // With the pad showing under a light scrim, give the words a dark band so the buttons do not
        // compete with them; the arrow zone below stays clear.
        if (step == 3) c.drawRoundRect(w * 0.06f, h * 0.43f, w * 0.94f, h * 0.745f, 24f, 24f, band)
        c.drawText("Step $step of 3", w / 2, labelY(), text)
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
                c.drawText(if (step == 2) "Try it now: it shows the pad" else "Once more: it hides the pad", w / 2, h * 0.66f + r * 0.9f, text)
            }
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
                if (edge == EdgeGesture.PULL_DOWN && wantsDown) pull?.onPullStart()
            }
            MotionEvent.ACTION_MOVE -> if (edge != null) {
                dragY = e.y - y0; invalidate()
                if (edge == EdgeGesture.PULL_DOWN && wantsDown) pull?.onPullMove(dragY)
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - x0; val dy = e.y - y0
                val need = min(width, height) * ShieldPadView.SWIPE_LEN
                val fast = e.eventTime - t0 < ShieldPadView.SWIPE_MAX_MS
                val g = edge
                dragY = 0f; edge = null; invalidate()
                val tap = hypot(dx, dy) < 30f
                val s = skipRect()
                if (isEnd) { if (tap) onSkip(); return true }
                when {
                    g == EdgeGesture.PULL_DOWN && wantsDown && pull != null -> pull.onPullEnd(dy > need && abs(dx) < dy)
                    g == EdgeGesture.PULL_DOWN && wantsDown && dy > need && abs(dx) < dy && fast -> onGesture(g)
                    g == EdgeGesture.PULL_UP && wantsUp && -dy > need && abs(dx) < -dy && fast -> onGesture(g)
                    tap && e.x in s[0]..s[2] && e.y in s[1]..s[3] -> onSkip()
                }
            }
            MotionEvent.ACTION_CANCEL -> { if (edge == EdgeGesture.PULL_DOWN && wantsDown) pull?.onPullEnd(false); dragY = 0f; edge = null; invalidate() }
        }
        return true
    }
}
