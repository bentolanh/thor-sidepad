package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.linhhan.thorsidepad.inject.Catalog

/** A single round button living in its own overlay window. Press on finger down, release on up. */
class PadButtonView(
    ctx: Context,
    val code: Int,
    private val enabled: Boolean,
    private val onPress: (Int) -> Unit,
    private val onRelease: (Int) -> Unit,
) : View(ctx) {

    private var down = false
    private val label = Catalog.byCode(code).label
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        ring.strokeWidth = r * 0.08f
        fill.color = when {
            !enabled -> 0x66444444
            down -> 0xDD2E7DFF.toInt()
            else -> 0xAA202020.toInt()
        }
        if (!enabled) ring.pathEffect = DashPathEffect(floatArrayOf(r * 0.2f, r * 0.15f), 0f)
        c.drawCircle(r, r, r * 0.92f, fill)
        c.drawCircle(r, r, r * 0.92f, ring)
        text.textSize = if (label.length > 2) r * 0.5f else r * 0.8f
        val y = r - (text.descent() + text.ascent()) / 2
        c.drawText(label, r, y, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!down) { down = true; invalidate(); if (enabled) onPress(code) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (down) { down = false; invalidate(); if (enabled) onRelease(code) }
        }
        return true
    }
}
