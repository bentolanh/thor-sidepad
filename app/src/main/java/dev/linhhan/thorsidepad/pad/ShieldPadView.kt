package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.isActionCode
import dev.linhhan.thorsidepad.inject.isStickCode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class EdgeGesture { PULL_DOWN, PULL_UP }

/**
 * Shield mode: one full-screen window that owns every touch on the display. Buttons are drawn
 * on it, fingers can slide from one button to the next, and touches between buttons go
 * nowhere, so the app underneath cannot be poked by accident. Pull-down opens the panel and
 * pull-up hides the pad.
 */
class ShieldPadView(
    ctx: Context,
    private val layout: PadLayout,
    private val engine: PadEngine,
    private val onGesture: (EdgeGesture) -> Unit,
    private val onAction: (Int) -> Unit,
    private val shieldOn: Boolean = true,
    opacity: Float = 1f,
    backdropColor: Int = 0,
) : View(ctx) {
    var opacity: Float = opacity
        set(v) { field = v; invalidate() }
    var backdropColor: Int = backdropColor
        set(v) { field = v; invalidate() }

    private val painter = ButtonPainter()
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val tracked = HashSet<Int>()             // pointers that may press buttons (not edge swipes)
    private val pressedBy = HashMap<Int, Int>()      // pointerId -> button index currently held
    private val pressCount = HashMap<Int, Int>()     // button index -> pointers holding it
    private val stickBy = HashMap<Int, Int>()        // pointerId -> stick index it is steering
    private val knob = HashMap<Int, FloatArray>()    // stick index -> current knob offset (-1..1)

    private class Swipe(val edge: EdgeGesture, val x0: Float, val y0: Float, val t0: Long)
    private val swipes = HashMap<Int, Swipe>()

    private fun short() = min(width, height).toFloat()

    private fun hit(x: Float, y: Float): Int {
        for (i in layout.buttons.indices.reversed()) {
            val b = layout.buttons[i]
            if (hypot(x - b.cx * width, y - b.cy * height) <= b.size * short() / 2f) return i
        }
        return -1
    }

    override fun onDraw(c: Canvas) {
        if (backdropColor != 0) c.drawColor(backdropColor)
        // Opacity applies to the buttons and pills only, never to the backdrop.
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        layout.buttons.forEachIndexed { i, b ->
            val r = b.size * short() / 2f
            val label = Catalog.byCode(b.code).label
            if (isStickCode(b.code)) {
                val k = knob[i]
                painter.drawStick(c, b.cx * width, b.cy * height, r, label, engine.enabled(b.code), k?.get(0) ?: 0f, k?.get(1) ?: 0f)
            } else if (isActionCode(b.code)) {
                painter.drawShieldToggle(c, b.cx * width, b.cy * height, r, shieldOn)
            } else {
                painter.draw(c, b.cx * width, b.cy * height, r, label, engine.enabled(b.code), (pressCount[i] ?: 0) > 0)
            }
        }
        // Small pills marking the gesture edges: top (panel) and bottom (hide).
        val w = width * 0.18f; val h = 8f
        c.drawRoundRect((width - w) / 2, 10f, (width + w) / 2, 10f + h, h, h, hint)
        c.drawRoundRect((width - w) / 2, height - 10f - h, (width + w) / 2, height - 10f, h, h, hint)
        c.restoreToCount(layer)
    }

    private fun steer(i: Int, x: Float, y: Float) {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f * 0.95f
        var dx = (x - b.cx * width) / r; var dy = (y - b.cy * height) / r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        knob[i] = floatArrayOf(dx, dy)
        engine.stick(b.code, dx, dy)
    }

    private fun releaseStick(pid: Int) {
        val i = stickBy.remove(pid) ?: return
        knob.remove(i)
        engine.stick(layout.buttons[i].code, 0f, 0f)
    }

    private fun press(i: Int, pid: Int) {
        pressedBy[pid] = i
        val n = (pressCount[i] ?: 0) + 1
        pressCount[i] = n
        val code = layout.buttons[i].code
        if (n == 1 && !isActionCode(code)) engine.press(code)
    }

    /** Action buttons fire on release, only if the finger is still on them. */
    private fun release(pid: Int, fireAction: Boolean = false) {
        val i = pressedBy.remove(pid) ?: return
        val n = (pressCount[i] ?: 1) - 1
        pressCount[i] = n
        val code = layout.buttons[i].code
        if (n <= 0) {
            pressCount.remove(i)
            if (isActionCode(code)) { if (fireAction) onAction(code) } else engine.release(code)
        }
    }

    private fun edgeAt(x: Float, y: Float): EdgeGesture? {
        val zone = short() * EDGE_ZONE
        return when {
            y < zone -> EdgeGesture.PULL_DOWN            // opens our panel
            y > height - zone -> EdgeGesture.PULL_UP     // hides the pad
            else -> null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                val x = e.getX(idx); val y = e.getY(idx)
                val i = hit(x, y)
                val edge = if (i < 0) edgeAt(x, y) else null
                if (edge != null) swipes[pid] = Swipe(edge, x, y, e.eventTime)
                else if (i >= 0 && isStickCode(layout.buttons[i].code)) { stickBy[pid] = i; steer(i, x, y) }
                else { tracked.add(pid); if (i >= 0) press(i, pid) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until e.pointerCount) {
                    val pid = e.getPointerId(idx)
                    stickBy[pid]?.let { si -> steer(si, e.getX(idx), e.getY(idx)); invalidate() }
                }
                // A finger keeps being tracked while it crosses gaps, so it can slide A -> B.
                for (idx in 0 until e.pointerCount) {
                    val pid = e.getPointerId(idx)
                    if (pid !in tracked) continue
                    val cur = pressedBy[pid] ?: -1
                    val i = hit(e.getX(idx), e.getY(idx))
                    if (i != cur) { release(pid); if (i >= 0) press(i, pid); invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                release(pid, fireAction = pressedBy[pid]?.let { it == hit(e.getX(idx), e.getY(idx)) } == true)
                releaseStick(pid)
                tracked.remove(pid)
                swipes.remove(pid)?.let { s ->
                    val dx = e.getX(idx) - s.x0; val dy = e.getY(idx) - s.y0
                    val need = short() * SWIPE_LEN
                    val fast = e.eventTime - s.t0 < SWIPE_MAX_MS
                    val ok = when (s.edge) {
                        EdgeGesture.PULL_DOWN -> dy > need && abs(dx) < dy
                        EdgeGesture.PULL_UP -> -dy > need && abs(dx) < -dy
                    }
                    if (ok && fast) onGesture(s.edge)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedBy.keys.toList().forEach { release(it) }
                stickBy.keys.toList().forEach { releaseStick(it) }
                tracked.clear()
                swipes.clear()
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        pressedBy.keys.toList().forEach { release(it) }
        stickBy.keys.toList().forEach { releaseStick(it) }
        super.onDetachedFromWindow()
    }

    companion object {
        const val EDGE_ZONE = 0.07f     // fraction of the short side that counts as an edge
        const val SWIPE_LEN = 0.15f     // minimum travel, fraction of the short side
        const val SWIPE_MAX_MS = 700L
    }
}
