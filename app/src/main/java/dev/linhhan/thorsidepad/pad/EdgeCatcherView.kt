package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * A small transparent strip parked on the top or bottom edge of the pad's screen while the pad
 * is hidden (or in islands mode). A pull that starts on it fires; anything else is ignored, so
 * the app underneath keeps almost all of its edge. Top = open the panel, bottom = show the pad.
 */
class EdgeCatcherView(ctx: Context, private val pullDown: Boolean, private val onPull: () -> Unit) : View(ctx) {
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private var y0 = 0f
    private var t0 = 0L
    private var armed = false

    override fun onDraw(c: Canvas) {
        val w = width * 0.5f; val h = 6f
        val y = if (pullDown) 6f else height - 6f - h
        c.drawRoundRect((width - w) / 2, y, (width + w) / 2, y + h, h, h, hint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { y0 = e.y; t0 = e.eventTime; armed = true }
            // Fire on finger-up so the panel never appears under a finger that is still moving.
            MotionEvent.ACTION_UP -> {
                val travel = if (pullDown) e.y - y0 else y0 - e.y
                if (armed && travel > PULL_PX) onPull()
                armed = false
            }
            MotionEvent.ACTION_CANCEL -> armed = false
        }
        return true
    }

    companion object {
        const val PULL_PX = 140f
        const val HEIGHT_PX = 28
    }
}
