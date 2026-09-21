package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The floating button: a controller, with a small switch under it for the shield.
 *
 * It was a plain circle that did one thing. On a single-screen handheld it is the only thing
 * always on screen, which makes it the only place a state can be read without opening something —
 * and two are worth reading mid-game. Whether the handheld's own sticks are driving another
 * device, because that decides what a press will do. And whether the shield is up, because that
 * decides whether a touch reaches the game underneath.
 *
 * The controller is the button itself rather than an icon inside a box, and it is a silhouette
 * with grips, a d-pad and buttons rather than a rectangle with two dots — which read as a face.
 * The switch beneath it is small on purpose: it is a state to glance at, not a control to aim
 * for. Its touch area is the whole lower third regardless, so it is easy to hit and quiet to look
 * at.
 */
class BubbleView(ctx: Context) : View(ctx) {

    /** True while the pad is on screen; the controller brightens. */
    var padShown = false
        set(v) { field = v; invalidate() }

    /** True while the handheld's own sticks are being sent to another device. */
    var remote = false
        set(v) { field = v; invalidate() }

    var shieldOn = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val body = Path()
    private val blob = Path()
    private val rect = RectF()

    private val d get() = resources.displayMetrics.density

    /** Where the controller ends and the switch begins. */
    private val splitY get() = height * 0.70f

    /** 0 for the controller, 1 for the shield switch. */
    fun regionAt(y: Float): Int = if (y < splitY) 0 else 1

    override fun onDraw(c: Canvas) {
        drawController(c, width / 2f, splitY * 0.5f, width * 0.46f)
        drawSwitch(c, width / 2f, (splitY + height) / 2f, width * 0.22f)
    }

    /**
     * A gamepad silhouette: a body with two grips, a d-pad on the left and two buttons on the
     * right. The grips are unioned into the body so the outline is one continuous edge rather
     * than three shapes with seams through them.
     */
    private fun drawController(c: Canvas, cx: Float, cy: Float, half: Float) {
        val bw = half; val bh = half * 0.62f
        body.reset()
        rect.set(cx - bw, cy - bh, cx + bw, cy + bh * 0.55f)
        body.addRoundRect(rect, bh * 0.55f, bh * 0.55f, Path.Direction.CW)
        // The grips, as two rounded lobes hanging off the lower corners.
        for (sx in intArrayOf(-1, 1)) {
            blob.reset()
            blob.addCircle(cx + sx * bw * 0.66f, cy + bh * 0.42f, bh * 0.58f, Path.Direction.CW)
            body.op(blob, Path.Op.UNION)
        }

        val ink = if (remote) 0xFF9CC4F0.toInt() else Color.WHITE
        fill.color = if (remote) 0xE6223549.toInt() else 0xE6262A2E.toInt()
        c.drawPath(body, fill)
        line.color = ink
        line.strokeWidth = 1.7f * d
        c.drawPath(body, line)

        // A d-pad on the left and four buttons on the right: enough asymmetry that it reads as a
        // controller rather than a face, which two dots did.
        val dx = cx - bw * 0.45f; val dy = cy - bh * 0.18f
        val arm = bh * 0.34f; val thick = bh * 0.20f
        fill.color = ink
        c.drawRect(dx - arm, dy - thick / 2f, dx + arm, dy + thick / 2f, fill)
        c.drawRect(dx - thick / 2f, dy - arm, dx + thick / 2f, dy + arm, fill)

        // The four in a diamond, in their usual colours while the pad is up. Colour is the
        // quickest way to say "this is live" without another shape to learn; with the pad down
        // they go to the outline's ink and the whole glyph dims.
        val bxc = cx + bw * 0.45f
        val spread = bh * 0.30f
        val dot = bh * 0.155f
        val live = padShown
        val faces = arrayOf(
            Triple(0f, spread, 0xFF6DBF5B.toInt()),    // A, below
            Triple(spread, 0f, 0xFFD9534F.toInt()),    // B, right
            Triple(-spread, 0f, 0xFF5B8FD9.toInt()),   // X, left
            Triple(0f, -spread, 0xFFE0C04A.toInt()),   // Y, above
        )
        for ((ox, oy, colour) in faces) {
            fill.color = if (live) colour else ink
            c.drawCircle(bxc + ox, dy + oy, dot, fill)
        }

        // Dimmed while the pad is down, so the button says what a tap will do without a second
        // shape to learn.
        if (!padShown) {
            fill.color = 0x66000000
            c.drawPath(body, fill)
        }
    }

    /** A small switch: a short track with a knob, lit and over to the right when the shield is up. */
    private fun drawSwitch(c: Canvas, cx: Float, cy: Float, half: Float) {
        val h = 5f * d
        fill.color = if (shieldOn) 0xFF2E7DFF.toInt() else 0x59FFFFFF
        c.drawRoundRect(cx - half, cy - h / 2f, cx + half, cy + h / 2f, h / 2f, h / 2f, fill)
        val knob = h * 0.95f
        val kx = if (shieldOn) cx + half - knob else cx - half + knob
        fill.color = if (shieldOn) Color.WHITE else 0xCCFFFFFF.toInt()
        c.drawCircle(kx, cy, knob, fill)
    }
}
