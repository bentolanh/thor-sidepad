package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The floating button: a controller that says where presses are going, over a switch for the
 * shield.
 *
 * It was a plain circle that did one thing. On a single-screen handheld it is the only thing
 * always on screen, which makes it the only place a state can be read without opening anything —
 * and two states are worth reading mid-game. Whether the handheld's own sticks are driving
 * another device, because that decides what a press will do. And whether the shield is up,
 * because that decides whether a touch reaches the game underneath.
 *
 * So the controller is the indicator and the pad toggle, and the switch beneath it is the shield.
 * Neither needs a label: a switch looks like a switch, and the controller is tinted the same blue
 * the panel uses for "gone to another device".
 */
class BubbleView(ctx: Context) : View(ctx) {

    /** True while the pad is on screen; the controller is filled rather than outlined. */
    var padShown = false
        set(v) { field = v; invalidate() }

    /** True while the handheld's own sticks are being sent to another device. */
    var remote = false
        set(v) { field = v; invalidate() }

    var shieldOn = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private val d get() = resources.displayMetrics.density

    /** Where the controller ends and the switch begins. */
    private val splitY get() = height * 0.64f

    /** 0 for the controller, 1 for the shield switch. */
    fun regionAt(y: Float): Int = if (y < splitY) 0 else 1

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = 14f * d

        // The body, tinted by where presses are going.
        fill.color = if (remote) 0xE62E4C6D.toInt() else 0xE6303337.toInt()
        c.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fill)
        line.color = 0x55FFFFFF
        line.strokeWidth = 1.5f * d
        c.drawRoundRect(0.75f * d, 0.75f * d, w - 0.75f * d, height - 0.75f * d, r, r, line)

        drawController(c, w / 2f, splitY / 2f, w * 0.30f)
        drawSwitch(c, w / 2f, (splitY + height) / 2f, w * 0.34f)
    }

    /**
     * A gamepad in outline: a rounded body with two grips and two thumbsticks. Filled while the
     * pad is showing, hollow while it is not, so the button says what a tap will do.
     */
    private fun drawController(c: Canvas, cx: Float, cy: Float, r: Float) {
        val on = Color.WHITE
        fill.color = if (padShown) on else 0x00000000
        line.color = on
        line.strokeWidth = 1.8f * d

        path.reset()
        val bw = r * 1.55f; val bh = r * 0.95f
        path.addRoundRect(cx - bw, cy - bh * 0.72f, cx + bw, cy + bh, r * 0.55f, r * 0.55f, Path.Direction.CW)
        if (padShown) c.drawPath(path, fill)
        c.drawPath(path, line)

        // Two sticks, drawn in the opposite ink so they read either way round.
        fill.color = if (padShown) 0xFF303337.toInt() else on
        c.drawCircle(cx - bw * 0.46f, cy + bh * 0.05f, r * 0.24f, fill)
        c.drawCircle(cx + bw * 0.46f, cy + bh * 0.05f, r * 0.24f, fill)
    }

    /** An ordinary switch: track plus knob, over to the right and lit when the shield is up. */
    private fun drawSwitch(c: Canvas, cx: Float, cy: Float, halfW: Float) {
        val h = halfW * 0.86f
        val left = cx - halfW; val right = cx + halfW
        fill.color = if (shieldOn) 0xFF2E7DFF.toInt() else 0x40FFFFFF
        c.drawRoundRect(left, cy - h / 2f, right, cy + h / 2f, h / 2f, h / 2f, fill)
        val knob = h * 0.40f
        val kx = if (shieldOn) right - knob - 2f * d else left + knob + 2f * d
        fill.color = Color.WHITE
        c.drawCircle(kx, cy, knob, fill)
    }
}
