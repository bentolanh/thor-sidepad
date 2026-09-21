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
        // Left of centre so the forwarding arrow has room without widening the button.
        drawShield(c, width * 0.42f, (splitY + height) / 2f - 1f * d, width * 0.155f)
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

    /**
     * A small shield, filled while it is up and hollow while it is not.
     *
     * It was a track-and-knob switch, which looked like a setting rather than the thing it
     * controls — and beside a controller glyph, a second abstract widget is one shape too many to
     * decode at a glance. A shield says what it is.
     *
     * An arrowhead sits to its right while presses are being forwarded. The glyph above is
     * already tinted for that, but colour alone is easy to miss on a screen held at arm's length
     * mid-game, and a shape is not.
     */
    private fun drawShield(c: Canvas, cx: Float, cy: Float, half: Float) {
        // Straight shoulders, then sides that draw in to a point. A shallower curve gives a
        // rounded bottom, which reads as a bucket rather than a shield — it did.
        val top = cy - half * 1.00f
        val shoulder = cy + half * 0.10f
        val bottom = cy + half * 1.45f
        blob.reset()
        blob.moveTo(cx - half, top)
        blob.lineTo(cx + half, top)
        blob.lineTo(cx + half, shoulder)
        blob.cubicTo(cx + half, cy + half * 0.80f, cx + half * 0.52f, cy + half * 1.16f, cx, bottom)
        blob.cubicTo(cx - half * 0.52f, cy + half * 1.16f, cx - half, cy + half * 0.80f, cx - half, shoulder)
        blob.close()

        if (shieldOn) {
            fill.color = 0xFF2E7DFF.toInt()
            c.drawPath(blob, fill)
            // A tick, so "on" reads even where the blue is washed out by what is behind it.
            line.color = Color.WHITE
            line.strokeWidth = 1.6f * d
            c.drawLine(cx - half * 0.40f, cy + half * 0.10f, cx - half * 0.06f, cy + half * 0.48f, line)
            c.drawLine(cx - half * 0.06f, cy + half * 0.48f, cx + half * 0.46f, cy - half * 0.34f, line)
        } else {
            line.color = 0x99FFFFFF.toInt()
            line.strokeWidth = 1.6f * d
            c.drawPath(blob, line)
        }

        if (!remote) return
        val a = half * 0.85f
        val x0 = cx + half + 4f * d
        blob.reset()
        blob.moveTo(x0, cy - a)
        blob.lineTo(x0 + a * 1.15f, cy)
        blob.lineTo(x0, cy + a)
        blob.close()
        fill.color = 0xFF9CC4F0.toInt()
        c.drawPath(blob, fill)
    }
}
