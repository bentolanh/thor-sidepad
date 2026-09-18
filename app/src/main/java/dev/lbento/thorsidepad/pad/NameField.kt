package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The preset name as it is typed, with a caret that blinks and can be moved.
 *
 * A normal text field cannot do this here. Android stops a field's cursor blinking the moment its
 * window loses focus, and the naming window deliberately never takes focus at all, so the caret is
 * drawn and blinked by hand. Which also means the caret is the only thing that knows where the next
 * letter lands, so it is kept solid for a moment after every key: a caret that happens to be in its
 * dark half just as you press something looks like nothing happened.
 */
class NameField(ctx: Context, initial: String) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 46f }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8AB4F8.toInt() }
    private val text = StringBuilder(initial.take(MAX))
    private var caret = text.length
    private var lit = true

    private val blink = object : Runnable {
        override fun run() { lit = !lit; invalidate(); postDelayed(this, BLINK_MS) }
    }

    val value: String get() = text.toString()

    /** Any change puts the caret back on and restarts the blink, so it is visible where it matters. */
    private fun changed() {
        lit = true
        removeCallbacks(blink); postDelayed(blink, BLINK_MS)
        invalidate()
    }

    /** Ignores anything past the cap rather than truncating silently mid-word. */
    fun insert(s: String) {
        if (text.length + s.length > MAX) return
        text.insert(caret, s); caret += s.length; changed()
    }

    val atLimit: Boolean get() = text.length >= MAX
    fun backspace() { if (caret > 0) { text.deleteCharAt(caret - 1); caret--; changed() } }
    fun left() { if (caret > 0) { caret--; changed() } }
    fun right() { if (caret < text.length) { caret++; changed() } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); changed() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(blink) }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthSpec),
            (paint.textSize * 1.9f + paddingTop + paddingBottom).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = paddingLeft.toFloat()
        val room = (width - paddingLeft - paddingRight).toFloat()
        val whole = paint.measureText(text, 0, text.length)
        val upToCaret = paint.measureText(text, 0, caret)
        // A long name scrolls rather than running off the end, and it scrolls by the caret so the
        // place you are typing is always the place you can see.
        val shift = if (whole <= room) 0f
                    else (upToCaret - room + paint.textSize).coerceIn(0f, whole - room)
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, 0, text.length, left - shift, baseline, paint)
        if (lit) {
            val x = left + upToCaret - shift
            c.drawRect(x, baseline + paint.ascent(), x + 3f, baseline + paint.descent() * 0.6f, caretPaint)
        }
    }

    companion object {
        /** Long names are fine; unbounded ones break the chip, the list and the exported file. */
        const val MAX = 32
        private const val BLINK_MS = 480L
    }
}
