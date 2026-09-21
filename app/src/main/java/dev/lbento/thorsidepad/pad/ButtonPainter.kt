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
        val top = cy - r * SLIDER_TOP; val bottom = cy + r * SLIDER_BOTTOM
        val w = r * SLIDER_W
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, fill)
        val lv = level.coerceIn(0f, 1f)
        // The knob travels between insets so it never hangs off either end of the track.
        val (kTop, kBottom) = sliderTravel(cy, r)
        val ky = kBottom - (kBottom - kTop) * lv
        fill.color = if (active) 0xDD2E7DFF.toInt() else 0xAA6A8FBF.toInt()
        c.drawRoundRect(cx - w / 2, ky, cx + w / 2, bottom, w / 2, w / 2, fill)
        ring.pathEffect = null; ring.strokeWidth = r * (if (selected) 0.1f else 0.06f); ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, bottom, w / 2, w / 2, ring)
        fill.color = Color.WHITE
        c.drawCircle(cx, ky, w * SLIDER_KNOB, fill)
        text.textSize = r * 0.42f
        c.drawText(symbol, cx, bottom + r * 0.42f, text)
        if (tag != null) { small.textSize = r * 0.26f; c.drawText(tag, cx, bottom + r * 0.7f, small) }
    }

    /**
     * The trackpad: a plain rounded field, lighter while a finger is on it.
     *
     * Deliberately unlike every other control here. Round means "press me"; this one is a surface
     * to be dragged across, and drawing it as a button was enough on its own to make it unusable —
     * the editor showed a circle, so there was no way to see or size the area a finger would have.
     */
    fun drawTrackpad(c: Canvas, left: Float, top: Float, right: Float, bottom: Float,
                     active: Boolean, selected: Boolean = false, enabled: Boolean = true) {
        val r = (bottom - top) * 0.12f
        fill.color = if (!enabled) 0x14FFFFFF else if (active) 0x40FFFFFF else 0x1FFFFFFF
        c.drawRoundRect(left, top, right, bottom, r, r, fill)
        ring.pathEffect = if (enabled) null else DashPathEffect(floatArrayOf(10f, 8f), 0f)
        ring.strokeWidth = (bottom - top) * (if (selected) 0.05f else 0.03f)
        ring.color = if (selected) 0xFFFFC107.toInt() else if (enabled) 0x99FFFFFF.toInt() else 0x4DFFFFFF
        c.drawRoundRect(left, top, right, bottom, r, r, ring)
        text.textSize = (bottom - top) * 0.17f
        text.color = if (enabled) 0x99FFFFFF.toInt() else 0x4DFFFFFF
        c.drawText(if (enabled) "TRACKPAD" else "SHIELD IS ON",
            (left + right) / 2f, (top + bottom) / 2f + text.textSize * 0.36f, text)
        ring.pathEffect = null
        text.color = Color.WHITE
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

    /**
     * The video unit: a timeline across the top that you drag to seek, and jump back, play or
     * pause, and jump forward beneath it. [pressed] is the third under the finger, or -1.
     */
    /** Trims text with an ellipsis until it fits the room given. */
    private fun fit(t: String, p: Paint, room: Float): String {
        if (p.measureText(t) <= room) return t
        var cut = t
        while (cut.isNotEmpty() && p.measureText("$cut…") > room) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** A round arrow with the seconds inside, the mark players show when you double-tap to skip. */
    private fun drawSkipIcon(c: Canvas, cx: Float, cy: Float, rad: Float, forward: Boolean, label: String) {
        ring.pathEffect = null; ring.strokeWidth = rad * 0.20f; ring.color = Color.WHITE
        val box = android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        // Most of a circle, with the gap where the arrowhead sits.
        if (forward) c.drawArc(box, -50f, 285f, false, ring) else c.drawArc(box, -130f, -285f, false, ring)
        fill.color = Color.WHITE
        val dir = if (forward) 1f else -1f
        val hx = cx + dir * rad * 0.64f
        val hy = cy - rad * 0.76f
        val s = rad * 0.42f
        c.drawPath(android.graphics.Path().apply {
            moveTo(hx + dir * s * 0.95f, hy + s * 0.15f)
            moveTo(hx - dir * s * 0.15f, hy - s * 0.75f)
            lineTo(hx + dir * s * 0.95f, hy + s * 0.20f)
            lineTo(hx - dir * s * 0.85f, hy + s * 0.45f)
            close()
        }, fill)
        text.textSize = rad * 0.86f
        text.color = Color.WHITE
        c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    /** The little speaker that marks the volume row, so it is not read as another timeline. */
    private fun drawSpeaker(c: Canvas, cx: Float, cy: Float, s: Float) {
        fill.color = Color.WHITE
        c.drawRect(cx - s * 0.80f, cy - s * 0.30f, cx - s * 0.30f, cy + s * 0.30f, fill)
        c.drawPath(android.graphics.Path().apply {
            moveTo(cx - s * 0.30f, cy - s * 0.30f); lineTo(cx + s * 0.28f, cy - s * 0.85f)
            lineTo(cx + s * 0.28f, cy + s * 0.85f); lineTo(cx - s * 0.30f, cy + s * 0.30f); close()
        }, fill)
        ring.pathEffect = null; ring.strokeWidth = s * 0.17f; ring.color = Color.WHITE
        c.drawArc(android.graphics.RectF(cx + s * 0.10f, cy - s * 0.62f, cx + s * 0.95f, cy + s * 0.62f), -55f, 110f, false, ring)
    }

    /**
     * The video unit: a timeline across the top that you drag to seek, the full transport beneath
     * it, and a volume row at the bottom marked with a speaker. [pressed] is the control under the
     * finger, 0..4, or -1.
     */
    fun drawVideo(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, playing: Boolean,
                  posFrac: Float, volume: Float = 0.5f, pressed: Int = -1, elapsed: String = "",
                  total: String = "", selected: Boolean = false, app: String = "", appDown: Boolean = false,
                  icon: android.graphics.drawable.Drawable? = null, title: String = "") {
        val h = bottom - top; val w = right - left; val rad = h * 0.10f
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(left, top, right, bottom, rad, rad, fill)
        ring.pathEffect = null
        ring.strokeWidth = h * (if (selected) 0.040f else 0.022f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(left, top, right, bottom, rad, rad, ring)

        val yApp = top + h * VIDEO_ROW_APP
        val yTime = top + h * VIDEO_ROW_TIME
        val yCtrl = top + h * VIDEO_ROW_CTRL

        // The band naming what is playing; tapping it brings that app forward.
        if (appDown) {
            fill.color = 0x772E7DFF
            c.drawRect(left + w * 0.012f, top + h * 0.012f, right - w * 0.012f, yApp, fill)
        }
        val bandCy = top + h * VIDEO_ROW_APP * 0.5f
        var textL = left + w * 0.030f
        if (icon != null) {
            val side = h * VIDEO_ROW_APP * 0.66f
            icon.setBounds((textL).toInt(), (bandCy - side / 2).toInt(), (textL + side).toInt(), (bandCy + side / 2).toInt())
            icon.draw(c)
            textL += side + w * 0.016f
        }
        small.textSize = h * 0.088f
        small.textAlign = Paint.Align.RIGHT
        var textR = right - w * 0.030f
        if (app.isNotEmpty()) {
            small.color = 0xFF8AB4F8.toInt()
            c.drawText("open  ›", textR, bandCy + small.textSize * 0.36f, small)
            textR -= small.measureText("open  ›") + w * 0.020f
        }
        // What is playing, falling back to the app's own name when it publishes no title.
        small.textAlign = Paint.Align.LEFT
        small.color = if (app.isEmpty()) 0xFF8A9199.toInt() else Color.WHITE
        val shown = when {
            app.isEmpty() -> "Nothing playing"
            title.isNotEmpty() -> title
            else -> app
        }
        val room = (textR - textL).coerceAtLeast(1f)
        c.drawText(fit(shown, small, room), textL, bandCy + small.textSize * 0.36f, small)
        small.textAlign = Paint.Align.CENTER

        ring.strokeWidth = h * 0.012f; ring.color = 0x33FFFFFF
        c.drawLine(left + w * 0.02f, yApp, right - w * 0.02f, yApp, ring)
        c.drawLine(left + w * 0.02f, yTime, right - w * 0.02f, yTime, ring)
        c.drawLine(left + w * 0.02f, yCtrl, right - w * 0.02f, yCtrl, ring)

        // timeline
        val ty = (yApp + yTime) / 2f
        val padX = w * 0.028f
        small.textSize = h * 0.085f
        small.color = 0xFFC2CAD2.toInt()
        small.textAlign = Paint.Align.LEFT
        c.drawText(elapsed, left + padX, ty + small.textSize * 0.36f, small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText(total, right - padX, ty + small.textSize * 0.36f, small)
        small.textAlign = Paint.Align.CENTER
        val (trackL, trackR) = videoTrackSpan(w, h, total)
        val tl = left + w * trackL; val tr = left + w * trackR
        val th = h * 0.036f
        fill.color = 0x88101010.toInt()
        c.drawRoundRect(tl, ty - th / 2, tr, ty + th / 2, th / 2, th / 2, fill)
        val pf = posFrac.coerceIn(0f, 1f)
        fill.color = 0xDD2E7DFF.toInt()
        c.drawRoundRect(tl, ty - th / 2, tl + (tr - tl) * pf, ty + th / 2, th / 2, th / 2, fill)
        fill.color = Color.WHITE
        c.drawCircle(tl + (tr - tl) * pf, ty, h * 0.052f, fill)

        // transport: previous, back ten, play or pause, forward ten, next
        val zw = w / 5f
        if (pressed in 0..4) {
            fill.color = 0x772E7DFF
            c.drawRect(left + pressed * zw, yTime + h * 0.01f, left + (pressed + 1) * zw, yCtrl - h * 0.01f, fill)
        }
        val cy = (yTime + yCtrl) / 2f
        val s = h * 0.088f
        fill.color = Color.WHITE
        var cx = left + zw * 0.5f                                   // previous
        c.drawRect(cx - s * 1.25f, cy - s, cx - s * 1.05f, cy + s, fill)
        triL(c, cx - s * 0.05f, cy, s); triL(c, cx + s * 1.05f, cy, s)
        drawSkipIcon(c, left + zw * 1.5f, cy, s * 1.30f, false, "10")
        cx = left + zw * 2.5f                                       // play or pause
        if (playing) {
            c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.25f, cy + s, fill)
            c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.75f, cy + s, fill)
        } else triR(c, cx - s * 0.5f, cy, s)
        drawSkipIcon(c, left + zw * 3.5f, cy, s * 1.30f, true, "10")
        cx = left + zw * 4.5f                                       // next
        fill.color = Color.WHITE
        triR(c, cx - s * 1.25f, cy, s); triR(c, cx - s * 0.15f, cy, s)
        c.drawRect(cx + s * 1.05f, cy - s, cx + s * 1.25f, cy + s, fill)

        // volume, marked with a speaker so it is never taken for the timeline
        val vy = (yCtrl + bottom) / 2f
        drawSpeaker(c, left + w * 0.072f, vy, h * 0.078f)
        val vl = left + w * VIDEO_VOL_L; val vr = left + w * VIDEO_VOL_R
        val vh = h * 0.036f
        fill.color = 0x88101010.toInt()
        c.drawRoundRect(vl, vy - vh / 2, vr, vy + vh / 2, vh / 2, vh / 2, fill)
        val vf = volume.coerceIn(0f, 1f)
        fill.color = 0xDD6A8FBF.toInt()
        c.drawRoundRect(vl, vy - vh / 2, vl + (vr - vl) * vf, vy + vh / 2, vh / 2, vh / 2, fill)
        fill.color = Color.WHITE
        c.drawCircle(vl + (vr - vl) * vf, vy, h * 0.050f, fill)
        small.color = 0xFFD0D6DC.toInt()
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
    fun drawMedia(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, pressed: Int = -1,
                  selected: Boolean = false, level: Float = 0.6f, playing: Boolean = false) {
        val h = bottom - top; val w = right - left; val rad = h * 0.30f
        val split = left + w * MEDIA_BUTTONS_FRAC
        val zw = (split - left) / 3f
        fill.color = 0xAA202020.toInt()
        c.drawRoundRect(left, top, right, bottom, rad, rad, fill)
        if (pressed in 0..2) {
            fill.color = 0x772E7DFF
            c.drawRoundRect(left + pressed * zw, top, left + (pressed + 1) * zw, bottom, rad, rad, fill)
        }
        ring.pathEffect = null
        ring.strokeWidth = h * (if (selected) 0.10f else 0.055f)
        ring.color = if (selected) 0xFFFFC107.toInt() else Color.WHITE
        c.drawRoundRect(left, top, right, bottom, rad, rad, ring)
        ring.strokeWidth = h * 0.025f; ring.color = 0x55FFFFFF
        c.drawLine(left + zw, top + h * 0.22f, left + zw, bottom - h * 0.22f, ring)
        c.drawLine(left + 2 * zw, top + h * 0.22f, left + 2 * zw, bottom - h * 0.22f, ring)
        c.drawLine(split, top + h * 0.16f, split, bottom - h * 0.16f, ring)

        fill.color = Color.WHITE
        val cy = (top + bottom) / 2f
        val s = h * 0.30f
        var cx = left + zw * 0.5f                   // previous
        c.drawRect(cx - s * 1.5f, cy - s, cx - s * 1.28f, cy + s, fill)
        triL(c, cx - s * 0.25f, cy, s); triL(c, cx + s * 0.85f, cy, s)
        cx = left + zw * 1.5f                       // whichever of play or pause it would do now
        if (playing) {
            c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.25f, cy + s, fill)
            c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.75f, cy + s, fill)
        } else triR(c, cx - s * 0.5f, cy, s)
        cx = left + zw * 2.5f                       // next
        triR(c, cx - s * 1.6f, cy, s); triR(c, cx - s * 0.5f, cy, s)
        c.drawRect(cx + s * 1.05f, cy - s, cx + s * 1.27f, cy + s, fill)

        // The volume track: the media stream, which is the volume of whatever is playing.
        val tl = split + w * 0.035f; val tr = right - w * 0.030f
        val th = h * 0.20f
        fill.color = 0x88101010.toInt()
        c.drawRoundRect(tl, cy - th / 2, tr, cy + th / 2, th / 2, th / 2, fill)
        val lv = level.coerceIn(0f, 1f)
        fill.color = 0xDD2E7DFF.toInt()
        c.drawRoundRect(tl, cy - th / 2, tl + (tr - tl) * lv, cy + th / 2, th / 2, th / 2, fill)
        fill.color = Color.WHITE
        c.drawCircle(tl + (tr - tl) * lv, cy, h * 0.21f, fill)
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
        /** A slider's track, as multiples of the element radius, and its knob within that track. */
        const val SLIDER_TOP = 0.9f
        const val SLIDER_BOTTOM = 0.55f
        const val SLIDER_W = 0.34f
        const val SLIDER_KNOB = 0.62f

        /** Where the knob's centre may sit: the track inset by the knob's own radius. */
        fun sliderTravel(cy: Float, r: Float): Pair<Float, Float> {
            val kr = r * SLIDER_W * SLIDER_KNOB
            return Pair(cy - r * SLIDER_TOP + kr, cy + r * SLIDER_BOTTOM - kr)
        }

        /** The level a touch at [y] means, matching where the knob is drawn. */
        fun levelAt(cy: Float, r: Float, y: Float): Float {
            val (t, b) = sliderTravel(cy, r)
            return ((b - y) / (b - t)).coerceIn(0f, 1f)
        }

        /** The video unit's half width and half height, as multiples of the element radius. */
        const val VIDEO_HALF_W = 2.2f
        const val VIDEO_HALF_H = 1.28f
        /** The unit's four rows, as fractions of its height: the app, timeline, buttons, volume. */
        const val VIDEO_ROW_APP = 0.19f
        const val VIDEO_ROW_TIME = 0.44f
        const val VIDEO_ROW_CTRL = 0.76f
        /** The volume track's ends, as fractions of the unit's width. */
        const val VIDEO_VOL_L = 0.150f
        const val VIDEO_VOL_R = 0.940f

        private val clock = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * Where the timeline runs inside a video unit of this size, as fractions of its width.
         * The clock is measured rather than guessed at, so neither the track nor its knob ever
         * runs under the time on either side. The same room is kept on both ends, and it is the
         * total running time that is measured, never the elapsed one: the elapsed time is never
         * the wider of the two, so the track cannot shift about while a scrub is in progress.
         */
        fun videoTrackSpan(w: Float, h: Float, total: String): Pair<Float, Float> {
            clock.textSize = h * 0.085f
            val room = clock.measureText(total.ifEmpty { "0:00" })
            val inset = (w * 0.028f + room + w * 0.022f + h * 0.052f) / w
            val l = inset.coerceIn(0.10f, 0.40f)
            return l to 1f - l
        }

        /** The trackpad's half-extents in button radii; islands, shield and editor share them. */
        const val PAD_HALF_W = 2.2f
        const val PAD_HALF_H = 1.6f
        const val MEDIA_HALF_W = 2.3f
        /** How much of the unit's width the three transport zones take; the rest is the volume track. */
        const val MEDIA_BUTTONS_FRAC = 0.58f
        const val MEDIA_HALF_H = 0.36f
    }
}
