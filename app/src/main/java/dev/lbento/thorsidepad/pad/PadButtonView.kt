package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.isActionCode

/** A single round button living in its own overlay window (islands mode). */
class PadButtonView(
    ctx: Context,
    val code: Int,
    private val enabled: Boolean,
    private val onPress: (Int) -> Unit,
    private val onRelease: (Int) -> Unit,
) : View(ctx) {

    private var down = false
    private val label = Catalog.byCode(code).label
    private val painter = ButtonPainter()

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        if (isActionCode(code)) painter.drawShieldToggle(c, r, r, r, false) else painter.draw(c, r, r, r, label, enabled, down)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!down) { down = true; invalidate(); if (enabled) onPress(code) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (down) { down = false; invalidate(); if (enabled) onRelease(code) }
        }
        return true
    }
}
