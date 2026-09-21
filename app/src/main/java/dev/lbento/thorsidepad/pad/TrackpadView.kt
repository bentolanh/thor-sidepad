package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** What a trackpad drives: this device's own pointer, through the injector. */
interface PointerSink {
    fun move(dx: Int, dy: Int)
    fun button(code: Int, down: Boolean)
    fun wheel(clicks: Int)
}

/**
 * A trackpad: drag to move the pointer, tap to click, two fingers to scroll.
 *
 * Relative, not absolute — the finger says how far to move rather than where to go, the way a
 * laptop's trackpad does. That matters because a surface the size of a few buttons cannot address
 * a whole screen with any precision, and because a pointer that jumped to wherever a thumb landed
 * would be unusable next to a thumbstick.
 *
 * Movement is accelerated the way pointers have been since the eighties: a slow drag moves
 * one-for-one for placing the cursor exactly, and a fast one covers ground. Without it the choice
 * is between a pad that cannot cross the screen and one that cannot hit a menu item.
 */
class TrackpadView(
    ctx: Context,
    private val onMove: (Int, Int) -> Unit,
    private val onButton: (Int, Boolean) -> Unit,
    private val onWheel: (Int) -> Unit,
    private val enabled: Boolean = true,
) : View(ctx) {

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val tapMs = ViewConfiguration.getTapTimeout().toLong() + 120L

    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var travelled = 0f
    private var fingers = 0
    private var scrolling = false
    private var scrollRemainder = 0f
    private var lit = false

    /** A finger's worth of scroll before one wheel click is sent. */
    private val wheelStepPx = 28f * ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val r = 18f
        face.color = if (lit) 0x33FFFFFF else 0x1FFFFFFF
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, face)
        edge.color = if (enabled) 0x66FFFFFF else 0x33FFFFFF
        edge.strokeWidth = 2f
        c.drawRoundRect(1f, 1f, width - 1f, height - 1f, r, r, edge)
        mark.color = if (enabled) 0x99FFFFFF.toInt() else 0x4DFFFFFF
        mark.textSize = minOf(width, height) * 0.16f
        c.drawText("TRACKPAD", width / 2f, height / 2f + mark.textSize * 0.36f, mark)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downAt = e.eventTime
                travelled = 0f
                fingers = 1
                scrolling = false
                scrollRemainder = 0f
                lit = true; invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = e.pointerCount
                // A second finger turns the gesture into a scroll from where it is now, so the
                // pointer does not lurch as the grip changes.
                if (fingers >= 2) { scrolling = true; lastY = e.getY(0); scrollRemainder = 0f }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val dy = e.getY(0) - lastY
                    lastY = e.getY(0)
                    scrollRemainder += dy
                    // Down the pad scrolls the page down, which is the direction a wheel turns.
                    while (abs(scrollRemainder) >= wheelStepPx) {
                        onWheel(if (scrollRemainder > 0) 1 else -1)
                        scrollRemainder -= sign(scrollRemainder) * wheelStepPx
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    travelled += hypot(dx, dy)
                    val g = gain(hypot(dx, dy))
                    onMove((dx * g).toInt(), (dy * g).toInt())
                }
            }

            MotionEvent.ACTION_POINTER_UP -> fingers = e.pointerCount - 1

            MotionEvent.ACTION_UP -> {
                lit = false; invalidate()
                // A tap is a short touch that went nowhere. Two fingers down makes it a right
                // click, which is the only way to reach one without a second button.
                val quick = e.eventTime - downAt <= tapMs
                if (quick && travelled <= slop && !scrolling) click(Mouse.LEFT)
                else if (quick && travelled <= slop && fingers >= 2) click(Mouse.RIGHT)
                fingers = 0; scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> { lit = false; invalidate(); fingers = 0; scrolling = false }
        }
        return true
    }

    private fun click(button: Int) {
        onButton(button, true)
        postDelayed({ onButton(button, false) }, 40)
    }

    /**
     * How far the pointer moves per pixel of finger.
     *
     * Flat below a crawl so the cursor can be placed, rising to roughly three times for a quick
     * flick so the far side of the screen is reachable without lifting off repeatedly.
     */
    private fun gain(step: Float): Float {
        val d = context.resources.displayMetrics.density
        val fast = (step / (6f * d)).coerceIn(0f, 1f)
        return 1f + fast * 2f
    }

    private object Mouse {
        const val LEFT = 0x110
        const val RIGHT = 0x111
    }

    companion object {
        /** Half-extents in button radii, so islands and shield agree on the same rectangle. */
        const val HALF_W = 2.2f
        const val HALF_H = 1.6f
    }
}
