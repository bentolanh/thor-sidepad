package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/** A one-element D-pad in its own overlay window (islands mode). */
class DpadView(ctx: Context, private val enabled: Boolean, private val engine: PadEngine) : View(ctx) {
    private val painter = ButtonPainter()
    private var mask = 0

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        painter.drawDpad(c, r, r, r, enabled, mask)
    }

    private fun steer(x: Float, y: Float) {
        val r = width / 2f * 0.95f
        val m = DpadMath.maskAt((x - width / 2f) / r, (y - height / 2f) / r)
        if (m != mask) { if (enabled) DpadMath.apply(engine, mask, m); mask = m; invalidate() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> steer(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (enabled) DpadMath.apply(engine, mask, 0); mask = 0; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        if (enabled) DpadMath.apply(engine, mask, 0); mask = 0
        super.onDetachedFromWindow()
    }
}
