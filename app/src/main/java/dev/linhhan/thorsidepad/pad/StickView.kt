package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.linhhan.thorsidepad.inject.Catalog
import kotlin.math.hypot

/** A virtual analogue stick in its own overlay window (islands mode). */
class StickView(ctx: Context, val code: Int, private val enabled: Boolean, private val engine: PadEngine) : View(ctx) {
    private val painter = ButtonPainter()
    private val label = Catalog.byCode(code).label
    private var kx = 0f
    private var ky = 0f

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        painter.drawStick(c, r, r, r, label, enabled, kx, ky)
    }

    private fun steer(x: Float, y: Float) {
        val r = width / 2f * 0.95f
        var dx = (x - width / 2f) / r; var dy = (y - height / 2f) / r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        kx = dx; ky = dy
        if (enabled) engine.stick(code, dx, dy)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> steer(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { kx = 0f; ky = 0f; if (enabled) engine.stick(code, 0f, 0f); invalidate() }
        }
        return true
    }
}
