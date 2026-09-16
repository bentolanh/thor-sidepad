package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.isActionCode
import dev.lbento.thorsidepad.inject.isSliderCode
import dev.lbento.thorsidepad.inject.isStickCode
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen layout editor: tap to select, drag to move, pinch to resize. Toolbar actions live in [PadOverlay]. */
class EditPadView(ctx: Context, val layout: PadLayout) : View(ctx) {

    var selected: Int = -1
        set(v) { field = v; invalidate(); onSelectionChanged?.invoke(v) }
    var onSelectionChanged: ((Int) -> Unit)? = null

    private var dragIndex = -1
    private var dragDx = 0f
    private var dragDy = 0f

    private val painter = ButtonPainter()
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private var scaling = false
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = selected >= 0; return scaling }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val b = layout.buttons.getOrNull(selected) ?: return false
            b.size = (b.size * d.scaleFactor).coerceIn(0.06f, 0.6f)
            invalidate(); return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false }
    })

    private fun short() = min(width, height).toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(0x55000000)
        val step = width / 10f
        var x = step; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), grid); x += step }
        var y = step; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, grid); y += step }
        layout.buttons.forEachIndexed { i, b ->
            val label = Catalog.byCode(b.code).label
            if (isStickCode(b.code)) painter.drawStick(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, true, 0f, 0f, i == selected)
            else if (b.code == Action.SHIELD) painter.drawShieldToggle(c, b.cx * width, b.cy * height, b.size * short() / 2f, true, i == selected)
            else if (isActionCode(b.code)) painter.drawSysButton(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, Catalog.screenTag(b.code), false, i == selected)
            else if (isSliderCode(b.code)) painter.drawSlider(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, Catalog.screenTag(b.code), 0.6f, false, i == selected)
            else painter.draw(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, true, false, i == selected)
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
        scaler.onTouchEvent(e)
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
            MotionEvent.ACTION_POINTER_DOWN -> dragIndex = -1   // second finger: pinch, not drag
            MotionEvent.ACTION_MOVE -> if (dragIndex >= 0 && !scaling && e.pointerCount == 1) {
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
