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
import dev.lbento.thorsidepad.inject.isMediaUnit
import dev.lbento.thorsidepad.inject.isVideoUnit
import dev.lbento.thorsidepad.inject.isSliderCode
import dev.lbento.thorsidepad.inject.isDpadCode
import dev.lbento.thorsidepad.inject.isStickCode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen layout editor: tap to select, drag to move, pinch to resize. Toolbar actions live in [PadOverlay]. */
class EditPadView(ctx: Context, val layout: PadLayout) : View(ctx) {

    var selected: Int = -1
        set(v) { field = v; invalidate(); onSelectionChanged?.invoke(v) }
    var onSelectionChanged: ((Int) -> Unit)? = null
    /** Fired on every touch in the pad area, so the helper text can step aside while editing. */
    var onTouched: (() -> Unit)? = null

    private var dragIndex = -1
    private var dragDx = 0f
    private var dragDy = 0f

    private val painter = ButtonPainter()
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private var scaling = false
    private var downOnEmpty = false     // first finger landed on empty space
    private var pinched = false         // a two-finger resize happened in this gesture
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        // Resize the selected element no matter where the pinch is, so a small element does not
        // have to be pinched exactly. onScaleBegin keeps the current selection.
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = selected >= 0; if (scaling) pinched = true; return scaling }
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
            val label = Glyphs.label(b.code, layout.style)
            if (isVideoUnit(b.code)) {
                val r = b.size * short() / 2f; val vx = b.cx * width; val vy = b.cy * height
                painter.drawVideo(c, vx - r * ButtonPainter.VIDEO_HALF_W, vy - r * ButtonPainter.VIDEO_HALF_H,
                    vx + r * ButtonPainter.VIDEO_HALF_W, vy + r * ButtonPainter.VIDEO_HALF_H,
                    false, 0.35f, -1, "0:00", "--:--", i == selected)
            }
            else if (isMediaUnit(b.code)) {
                val r = b.size * short() / 2f; val mx = b.cx * width; val my = b.cy * height
                painter.drawMedia(c, mx - r * ButtonPainter.MEDIA_HALF_W, my - r * ButtonPainter.MEDIA_HALF_H,
                    mx + r * ButtonPainter.MEDIA_HALF_W, my + r * ButtonPainter.MEDIA_HALF_H, -1, i == selected)
            }
            else if (isDpadCode(b.code)) painter.drawDpad(c, b.cx * width, b.cy * height, b.size * short() / 2f, true, 0, i == selected)
            else if (isStickCode(b.code)) painter.drawStick(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, true, 0f, 0f, i == selected)
            else if (b.code == Action.SHIELD) painter.drawShieldToggle(c, b.cx * width, b.cy * height, b.size * short() / 2f, true, i == selected)
            else if (isActionCode(b.code)) painter.drawSysButton(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, Catalog.screenTag(b.code), false, i == selected)
            else if (isSliderCode(b.code)) painter.drawSlider(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, Catalog.screenTag(b.code), 0.6f, false, i == selected)
            else painter.draw(c, b.cx * width, b.cy * height, b.size * short() / 2f, label, true, false, i == selected, Glyphs.color(b.code, layout.style), if (b.sticky) 1 else 0, Glyphs.icon(b.code, layout.style), b.turbo)
        }
    }

    private fun hit(x: Float, y: Float): Int {
        // Topmost (last drawn) wins.
        for (i in layout.buttons.indices.reversed()) {
            val b = layout.buttons[i]
            val r = b.size * short() / 2f
            if (isVideoUnit(b.code)) {
                if (abs(x - b.cx * width) <= r * ButtonPainter.VIDEO_HALF_W && abs(y - b.cy * height) <= r * ButtonPainter.VIDEO_HALF_H) return i
            } else if (isMediaUnit(b.code)) {
                if (abs(x - b.cx * width) <= r * ButtonPainter.MEDIA_HALF_W && abs(y - b.cy * height) <= r * ButtonPainter.MEDIA_HALF_H) return i
            } else if (hypot(x - b.cx * width, y - b.cy * height) <= r) return i
        }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        onTouched?.invoke()
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinched = false
                val i = hit(e.x, e.y)
                if (i >= 0) {
                    selected = i; dragIndex = i; downOnEmpty = false
                    val b = layout.buttons[i]
                    dragDx = b.cx * width - e.x; dragDy = b.cy * height - e.y
                } else {
                    // Empty space: keep the current selection so a pinch here can resize it. A plain
                    // tap that turns out not to be a pinch clears the selection on release.
                    dragIndex = -1; downOnEmpty = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragIndex = -1   // second finger: pinch, not drag
            MotionEvent.ACTION_MOVE -> if (dragIndex >= 0 && !scaling && e.pointerCount == 1) {
                val b = layout.buttons[dragIndex]
                b.cx = ((e.x + dragDx) / width).coerceIn(0.02f, 0.98f)
                b.cy = ((e.y + dragDy) / height).coerceIn(0.02f, 0.98f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (downOnEmpty && !pinched) selected = -1   // a tap on empty space deselects
                dragIndex = -1; downOnEmpty = false
            }
        }
        return true
    }
}
