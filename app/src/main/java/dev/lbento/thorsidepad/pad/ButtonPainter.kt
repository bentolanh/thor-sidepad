package dev.lbento.thorsidepad.pad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

/** Draws one round pad button. Shared by the per-button windows, the shield, and the editor. */
class ButtonPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val dash = DashPathEffect(floatArrayOf(12f, 9f), 0f)

    private val shieldPath = Path()

    /** The shield toggle: a shield-shaped badge, filled blue with a tick when on, outlined when off. */
    fun drawShieldToggle(c: Canvas, cx: Float, cy: Float, r: Float, on: Boolean, selected: Boolean = false) {
        // Soft round base so it reads as a button.
        fill.color = if (on) 0xDD2E7DFF.toInt() else 0xAA202020.toInt()
        ring.pathEffect = null
        ring.strokeWidth = r * (if (selected) 0.14f else 0.08f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawCircle(cx, cy, r * 0.92f, fill)
        c.drawCircle(cx, cy, r * 0.92f, ring)
        // Shield glyph.
        val w = r * 0.55f; val top = cy - r * 0.5f; val bottom = cy + r * 0.55f
        shieldPath.reset()
        shieldPath.moveTo(cx - w, top)
        shieldPath.lineTo(cx + w, top)
        shieldPath.lineTo(cx + w, cy)
        shieldPath.quadTo(cx + w, bottom - r * 0.15f, cx, bottom)
        shieldPath.quadTo(cx - w, bottom - r * 0.15f, cx - w, cy)
        shieldPath.close()
        fill.color = if (on) Color.WHITE else 0x00000000
        ring.strokeWidth = r * 0.07f; ring.color = Color.WHITE
        if (on) c.drawPath(shieldPath, fill)
        c.drawPath(shieldPath, ring)
        if (on) {
            // Tick inside the shield.
            ring.color = 0xFF2E7DFF.toInt(); ring.strokeWidth = r * 0.1f
            c.drawLine(cx - w * 0.5f, cy, cx - w * 0.1f, cy + r * 0.22f, ring)
            c.drawLine(cx - w * 0.1f, cy + r * 0.22f, cx + w * 0.55f, cy - r * 0.25f, ring)
        }
    }

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D6DC.toInt(); textAlign = Paint.Align.CENTER }

    /** A Home/Back style button: round, label, and a small tag saying which screen it acts on. */
    fun drawSysButton(c: Canvas, cx: Float, cy: Float, r: Float, label: String, tag: String?, down: Boolean, selected: Boolean = false) {
        draw(c, cx, cy, r, "", true, down, selected)
        text.textSize = r * 0.42f
        c.drawText(label, cx, cy - (if (tag != null) r * 0.02f else 0f) - (text.descent() + text.ascent()) / 2 - (if (tag != null) r * 0.12f else 0f), text)
        if (tag != null) { small.textSize = r * 0.28f; c.drawText(tag, cx, cy + r * 0.42f, small) }
    }

    /**
     * A vertical slider: a track of height 2r (r = half the element size), a filled part up to
     * [level] (0..1), a knob, the symbol at the bottom and the screen tag under it.
     */
    fun drawSlider(c: Canvas, cx: Float, cy: Float, r: Float, symbol: String, tag: String?, level: Float, active: Boolean, selected: Boolean = false) {
        val top = cy - r * 0.9f; val bottom = cy + r * 0.55f
        val w = r * 0.34f
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, fill)
        val lv = level.coerceIn(0f, 1f)
        val ky = bottom - (bottom - top) * lv
        fill.color = if (active) 0xDD2E7DFF.toInt() else 0xAA6A8FBF.toInt()
        c.drawRoundRect(cx - w / 2, ky, cx + w / 2, bottom, w / 2, w / 2, fill)
        ring.pathEffect = null; ring.strokeWidth = r * (if (selected) 0.1f else 0.06f); ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, ring)
        fill.color = Color.WHITE
        c.drawCircle(cx, ky, w * 0.62f, fill)
        text.textSize = r * 0.42f
        c.drawText(symbol, cx, bottom + r * 0.42f, text)
        if (tag != null) { small.textSize = r * 0.26f; c.drawText(tag, cx, bottom + r * 0.7f, small) }
    }

    /** A stick: outer ring, knob offset by (kx, ky) in -1..1 of the ring radius. */
    fun drawStick(c: Canvas, cx: Float, cy: Float, r: Float, label: String, enabled: Boolean, kx: Float, ky: Float, selected: Boolean = false) {
        ring.strokeWidth = r * (if (selected) 0.10f else 0.05f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        ring.pathEffect = if (enabled) null else dash
        fill.color = if (enabled) 0x66202020 else 0x44444444
        c.drawCircle(cx, cy, r * 0.95f, fill)
        c.drawCircle(cx, cy, r * 0.95f, ring)
        val kr = r * 0.42f
        fill.color = if (kx != 0f || ky != 0f) 0xDD2E7DFF.toInt() else 0xCC303030.toInt()
        ring.pathEffect = null; ring.strokeWidth = r * 0.05f; ring.color = Color.WHITE
        val kcx = cx + kx * (r - kr) * 0.95f; val kcy = cy + ky * (r - kr) * 0.95f
        c.drawCircle(kcx, kcy, kr, fill)
        c.drawCircle(kcx, kcy, kr, ring)
        text.textSize = kr * 0.7f
        c.drawText(label, kcx, kcy - (text.descent() + text.ascent()) / 2, text)
    }

    /** A D-pad cross; [mask] holds the DpadMath direction bits currently pressed. */
    fun drawDpad(c: Canvas, cx: Float, cy: Float, r: Float, enabled: Boolean, mask: Int, selected: Boolean = false) {
        val rr = r * 0.95f; val w = r * 0.62f; val h = w / 2f
        ring.strokeWidth = r * (if (selected) 0.10f else 0.05f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        ring.pathEffect = if (enabled) null else dash
        val cross = android.graphics.Path().apply {
            moveTo(cx - h, cy - rr); lineTo(cx + h, cy - rr); lineTo(cx + h, cy - h); lineTo(cx + rr, cy - h)
            lineTo(cx + rr, cy + h); lineTo(cx + h, cy + h); lineTo(cx + h, cy + rr); lineTo(cx - h, cy + rr)
            lineTo(cx - h, cy + h); lineTo(cx - rr, cy + h); lineTo(cx - rr, cy - h); lineTo(cx - h, cy - h); close()
        }
        fill.color = if (enabled) 0xAA202020.toInt() else 0x44444444
        c.drawPath(cross, fill)
        fill.color = 0xDD2E7DFF.toInt()
        val g = r * 0.06f
        if (mask and DpadMath.UP != 0) c.drawRect(cx - h + g, cy - rr + g, cx + h - g, cy - h, fill)
        if (mask and DpadMath.DOWN != 0) c.drawRect(cx - h + g, cy + h, cx + h - g, cy + rr - g, fill)
        if (mask and DpadMath.LEFT != 0) c.drawRect(cx - rr + g, cy - h + g, cx - h, cy + h - g, fill)
        if (mask and DpadMath.RIGHT != 0) c.drawRect(cx + h, cy - h + g, cx + rr - g, cy + h - g, fill)
        c.drawPath(cross, ring)
        // Arrow heads near the four ends.
        fill.color = Color.WHITE
        val a = r * 0.16f; val d = rr - r * 0.22f
        fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            c.drawPath(android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, fill)
        }
        tri(cx, cy - d - a * 0.6f, cx - a, cy - d + a * 0.6f, cx + a, cy - d + a * 0.6f)
        tri(cx, cy + d + a * 0.6f, cx - a, cy + d - a * 0.6f, cx + a, cy + d - a * 0.6f)
        tri(cx - d - a * 0.6f, cy, cx - d + a * 0.6f, cy - a, cx - d + a * 0.6f, cy + a)
        tri(cx + d + a * 0.6f, cy, cx + d - a * 0.6f, cy - a, cx + d - a * 0.6f, cy + a)
    }

    /** The Xbox View button: two overlapping panes, drawn rather than spelled out. */
    private fun drawViewIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, bg: Int) {
        val w = r * 0.50f; val h = r * 0.38f; val off = r * 0.13f; val rad = r * 0.07f
        ring.pathEffect = null; ring.strokeWidth = r * 0.085f; ring.color = color
        c.drawRoundRect(cx - w / 2 - off, cy - h / 2 - off, cx + w / 2 - off, cy + h / 2 - off, rad, rad, ring)
        fill.color = bg     // the front pane occludes the back one
        c.drawRoundRect(cx - w / 2 + off, cy - h / 2 + off, cx + w / 2 + off, cy + h / 2 + off, rad, rad, fill)
        c.drawRoundRect(cx - w / 2 + off, cy - h / 2 + off, cx + w / 2 + off, cy + h / 2 + off, rad, rad, ring)
    }

    /** The Xbox Menu button: three stacked bars. */
    private fun drawMenuIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        val w = r * 0.62f; val t = r * 0.11f; val gap = r * 0.20f
        for (i in -1..1) {
            val y = cy + i * gap
            c.drawRoundRect(cx - w / 2, y - t / 2, cx + w / 2, y + t / 2, t / 2, t / 2, fill)
        }
    }

    /**
     * PlayStation Select and Start are drawn as the shapes of the buttons themselves on a
     * DualShock, not as the words printed beside them: a small rounded rectangle and a
     * right-pointing wedge.
     */
    private fun drawSelectIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        ring.pathEffect = null; ring.strokeWidth = r * 0.10f; ring.color = color
        val w = r * 0.58f; val h = r * 0.30f; val rad = r * 0.06f
        c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, rad, rad, ring)
    }

    /** PlayStation Start: a right-pointing triangle. */
    private fun drawStartIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        val w = r * 0.46f; val h = r * 0.52f
        c.drawPath(android.graphics.Path().apply {
            moveTo(cx - w / 2, cy - h / 2); lineTo(cx + w / 2, cy); lineTo(cx - w / 2, cy + h / 2); close()
        }, fill)
    }

    /**
     * [mark]: 0 none, 1 a hollow dot (sticky, not held), 2 a filled dot (latched down).
     * [icon]: 0 draw [label] as text, 1 the View panes, 2 the Menu bars, 3 the Select oval,
     * 4 the Start triangle.
     */
    private fun triR(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawPath(android.graphics.Path().apply { moveTo(x, y - s); lineTo(x + s * 0.95f, y); lineTo(x, y + s); close() }, fill)
    }
    private fun triL(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawPath(android.graphics.Path().apply { moveTo(x, y - s); lineTo(x - s * 0.95f, y); lineTo(x, y + s); close() }, fill)
    }

    /**
     * The media unit: one capsule split into previous, play or pause, and next. [pressed] is the
     * third under the finger, 0..2, or -1 for none.
     */
    fun drawMedia(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, pressed: Int = -1, selected: Boolean = false) {
        val h = bottom - top; val w = right - left; val rad = h * 0.30f
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(left, top, right, bottom, rad, rad, fill)
        if (pressed in 0..2) {
            val zw = w / 3f
            fill.color = 0x772E7DFF
            c.drawRoundRect(left + pressed * zw, top, left + (pressed + 1) * zw, bottom, rad, rad, fill)
        }
        ring.pathEffect = null
        ring.strokeWidth = h * (if (selected) 0.10f else 0.055f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(left, top, right, bottom, rad, rad, ring)
        ring.strokeWidth = h * 0.025f; ring.color = 0x55FFFFFF
        c.drawLine(left + w / 3f, top + h * 0.22f, left + w / 3f, bottom - h * 0.22f, ring)
        c.drawLine(left + 2 * w / 3f, top + h * 0.22f, left + 2 * w / 3f, bottom - h * 0.22f, ring)

        fill.color = Color.WHITE
        val cy = (top + bottom) / 2f
        val s = h * 0.30f
        var cx = left + w / 6f                      // previous
        c.drawRect(cx - s * 1.5f, cy - s, cx - s * 1.28f, cy + s, fill)
        triL(c, cx - s * 0.25f, cy, s); triL(c, cx + s * 0.85f, cy, s)
        cx = left + w / 2f                          // play or pause
        triR(c, cx - s * 1.25f, cy, s)
        c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.5f, cy + s, fill)
        c.drawRect(cx + s * 0.75f, cy - s, cx + s * 1.0f, cy + s, fill)
        cx = left + 5 * w / 6f                      // next
        triR(c, cx - s * 1.6f, cy, s); triR(c, cx - s * 0.5f, cy, s)
        c.drawRect(cx + s * 1.05f, cy - s, cx + s * 1.27f, cy + s, fill)
    }

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, label: String, enabled: Boolean, down: Boolean, selected: Boolean = false, labelColor: Int = Color.WHITE, mark: Int = 0, icon: Int = 0, turbo: Boolean = false) {
        ring.strokeWidth = r * (if (selected) 0.14f else 0.08f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        ring.pathEffect = if (enabled) null else dash
        fill.color = when {
            !enabled -> 0x66444444
            down -> 0xDD2E7DFF.toInt()
            else -> 0xAA202020.toInt()
        }
        c.drawCircle(cx, cy, r * 0.92f, fill)
        c.drawCircle(cx, cy, r * 0.92f, ring)
        val bg = fill.color
        if (icon != 0) {
            val ink = if (down) Color.WHITE else labelColor
            when (icon) {
                1 -> drawViewIcon(c, cx, cy, r, ink, bg)
                2 -> drawMenuIcon(c, cx, cy, r, ink)
                3 -> drawSelectIcon(c, cx, cy, r, ink)
                else -> drawStartIcon(c, cx, cy, r, ink)
            }
        } else {
            text.textSize = if (label.length > 4) r * 0.42f else if (label.length > 2) r * 0.5f else r * 0.8f
            text.color = if (down) Color.WHITE else labelColor
            c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
            text.color = Color.WHITE
        }
        if (mark != 0) {
            // A small dot at the top right of the ring: hollow = sticky, filled = held down.
            val mx = cx + r * 0.62f; val my = cy - r * 0.62f; val mr = r * 0.16f
            fill.color = if (mark == 2) 0xFFFFC107.toInt() else 0xCC202020.toInt()
            c.drawCircle(mx, my, mr, fill)
            ring.pathEffect = null; ring.strokeWidth = r * 0.05f; ring.color = if (mark == 2) 0xFFFFC107.toInt() else Color.WHITE
            c.drawCircle(mx, my, mr, ring)
        }
        if (turbo) {
            // Two chevrons at the top left, the other corner from the hold dot, so a button can show both.
            fill.color = 0xFF8AB4F8.toInt()
            val mx = cx - r * 0.72f; val my = cy - r * 0.60f; val t = r * 0.15f
            for (k in 0..1) {
                val x = mx + k * t
                c.drawPath(android.graphics.Path().apply {
                    moveTo(x - t * 0.5f, my - t); lineTo(x + t * 0.5f, my); lineTo(x - t * 0.5f, my + t); close()
                }, fill)
            }
        }
    }

    companion object {
        /** The media unit's half width and half height, as multiples of the element radius: a slim bar. */
        const val MEDIA_HALF_W = 1.5f
        const val MEDIA_HALF_H = 0.36f
    }
}
