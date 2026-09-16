package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.linhhan.thorsidepad.inject.Catalog
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen layout editor: tap to select, drag to move. Toolbar actions live in [PadOverlay]. */
class EditPadView(ctx: Context, val layout: PadLayout) : View(ctx) {

    var selected: Int = -1
        set(v) { field = v; invalidate(); onSelectionChanged?.invoke(v) }
    var onSelectionChanged: ((Int) -> Unit)? = null

    private var dragIndex = -1
    private var dragDx = 0f
    private var dragDy = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA202020.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val selRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFC107.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }

    private fun short() = min(width, height).toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(0x55000000)
        val step = width / 10f
        var x = step; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), grid); x += step }
        var y = step; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, grid); y += step }
        layout.buttons.forEachIndexed { i, b ->
            val r = b.size * short() / 2f
            val cx = b.cx * width; val cy = b.cy * height
            ring.strokeWidth = r * 0.08f; selRing.strokeWidth = r * 0.14f
            c.drawCircle(cx, cy, r * 0.92f, fill)
            c.drawCircle(cx, cy, r * 0.92f, if (i == selected) selRing else ring)
            val label = Catalog.byCode(b.code).label
            text.textSize = if (label.length > 2) r * 0.5f else r * 0.8f
            c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun hit(x: Float, y: Float): Int {
        // Topmost (last drawn) wins.
        for (i in layout.buttons.indices.reversed()) {
            val b = layout.buttons[i]
            val r = b.size * short() / 2f
            if (hypot(x - b.cx * width, y - b.cy * height) <= r) return i
        }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = hit(e.x, e.y)
                selected = i
                dragIndex = i
                if (i >= 0) {
                    val b = layout.buttons[i]
                    dragDx = b.cx * width - e.x; dragDy = b.cy * height - e.y
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragIndex >= 0) {
                val b = layout.buttons[dragIndex]
                b.cx = ((e.x + dragDx) / width).coerceIn(0.02f, 0.98f)
                b.cy = ((e.y + dragDy) / height).coerceIn(0.02f, 0.98f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragIndex = -1
        }
        return true
    }
}
