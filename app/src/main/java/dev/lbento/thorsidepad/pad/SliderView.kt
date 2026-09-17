package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog

/** A vertical level slider in its own overlay window (islands mode). */
class SliderView(ctx: Context, val code: Int, private var level: Float, private val onSlider: (Int, Float, Boolean) -> Unit) : View(ctx) {
    private val painter = ButtonPainter()
    private var active = false

    // The window is half an element wide and 1.2 elements tall: r is the element's half size,
    // the track sits in the top 2r and the symbol and screen tag hang below it.
    override fun onDraw(c: Canvas) {
        val r = width.toFloat()
        painter.drawSlider(c, width / 2f, r, r, Catalog.byCode(code).label, Catalog.screenTag(code), level, active)
    }

    private fun slide(y: Float) {
        val r = width.toFloat()
        level = ButtonPainter.levelAt(r, r, y)
        onSlider(code, level, false); invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { active = true; slide(e.y) }
            MotionEvent.ACTION_MOVE -> slide(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = false; onSlider(code, level, true); invalidate() }
        }
        return true
    }
}
