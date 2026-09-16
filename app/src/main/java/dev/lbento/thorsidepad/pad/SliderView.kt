package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog

/** A vertical level slider in its own overlay window (islands mode). */
class SliderView(ctx: Context, val code: Int, private var level: Float, private val onSlider: (Int, Float) -> Unit) : View(ctx) {
    private val painter = ButtonPainter()
    private var active = false

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        painter.drawSlider(c, width / 2f, r, r, Catalog.byCode(code).label, Catalog.screenTag(code), level, active)
    }

    private fun slide(y: Float) {
        val r = height / 2f; val cy = height / 2f
        val top = cy - r * 0.9f; val bottom = cy + r * 0.55f
        level = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        onSlider(code, level); invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { active = true; slide(e.y) }
            MotionEvent.ACTION_MOVE -> slide(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = false; invalidate() }
        }
        return true
    }
}
