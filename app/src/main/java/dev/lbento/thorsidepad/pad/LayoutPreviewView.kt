package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.isActionCode
import dev.lbento.thorsidepad.inject.isSliderCode
import dev.lbento.thorsidepad.inject.isDpadCode
import dev.lbento.thorsidepad.inject.isStickCode
import kotlin.math.min

/** A miniature of a layout, drawn at the pad screen's aspect ratio. */
class LayoutPreviewView(ctx: Context, private val layout: PadLayout, private val aspect: Float) : View(ctx) {
    private val painter = ButtonPainter()
    private val bg = Paint().apply { color = 0xFF101418.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 2f }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width / aspect).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f, bg)
        c.drawRoundRect(1f, 1f, width - 1f, height - 1f, 12f, 12f, frame)
        val short = min(width, height).toFloat()
        for (b in layout.buttons) {
            val r = b.size * short / 2f
            val label = Glyphs.label(b.code, layout.style)
            if (isDpadCode(b.code)) painter.drawDpad(c, b.cx * width, b.cy * height, r, true, 0)
            else if (isStickCode(b.code)) painter.drawStick(c, b.cx * width, b.cy * height, r, label, true, 0f, 0f)
            else if (b.code == Action.SHIELD) painter.drawShieldToggle(c, b.cx * width, b.cy * height, r, true)
            else if (isActionCode(b.code)) painter.drawSysButton(c, b.cx * width, b.cy * height, r, label, Catalog.screenTag(b.code), false)
            else if (isSliderCode(b.code)) painter.drawSlider(c, b.cx * width, b.cy * height, r, label, Catalog.screenTag(b.code), 0.6f, false)
            else painter.draw(c, b.cx * width, b.cy * height, r, label, true, false, labelColor = Glyphs.color(b.code, layout.style), mark = if (b.sticky) 1 else 0)
        }
    }
}
