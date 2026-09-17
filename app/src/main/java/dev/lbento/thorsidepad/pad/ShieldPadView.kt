package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.isActionCode
import dev.lbento.thorsidepad.inject.isMediaUnit
import dev.lbento.thorsidepad.inject.isVideoUnit
import dev.lbento.thorsidepad.inject.mediaCodeAt
import dev.lbento.thorsidepad.inject.isSliderCode
import dev.lbento.thorsidepad.inject.isDpadCode
import dev.lbento.thorsidepad.inject.isStickCode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class EdgeGesture { PULL_DOWN, PULL_UP }

/** Finger-tracked pull from the top edge, so the panel can follow it like the notification shade. */
interface PullListener {
    fun onPullStart()
    fun onPullMove(dy: Float)
    fun onPullEnd(commit: Boolean)
}

/**
 * Shield mode: one full-screen window that owns every touch on the display. Buttons are drawn
 * on it, fingers can slide from one button to the next, and touches between buttons go
 * nowhere, so the app underneath cannot be poked by accident. Pull-down opens the panel and
 * pull-up hides the pad.
 */
class ShieldPadView(
    ctx: Context,
    private val layout: PadLayout,
    private val engine: PadEngine,
    private val onGesture: (EdgeGesture) -> Unit,
    private val onAction: (Int) -> Unit,
    private val onPullDown: PullListener? = null,
    private val shieldOn: Boolean = true,
    opacity: Float = 1f,
    backdropColor: Int = 0,
    private val levels: MutableMap<Int, Float> = HashMap(),     // slider code -> current 0..1
    private val onSlider: (Int, Float, Boolean) -> Unit = { _, _, _ -> },   // code, level, final (finger lifted)
    private val video: NowPlaying = NowPlaying(),
) : View(ctx) {
    private val sliderBy = HashMap<Int, Int>()       // pointerId -> slider index it is dragging
    var opacity: Float = opacity
        set(v) { field = v; invalidate() }
    var backdropColor: Int = backdropColor
        set(v) { field = v; invalidate() }

    private val painter = ButtonPainter()
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val tracked = HashSet<Int>()             // pointers that may press buttons (not edge swipes)
    private val pressedBy = HashMap<Int, Int>()      // pointerId -> button index currently held
    private val pressCount = HashMap<Int, Int>()     // button index -> pointers holding it
    private val stickBy = HashMap<Int, Int>()        // pointerId -> stick index it is steering
    private val knob = HashMap<Int, FloatArray>()    // stick index -> current knob offset (-1..1)
    private val latched = HashSet<Int>()             // button indexes held down by a latch
    private val latchTouch = HashSet<Int>()          // pointers whose touch-down toggled a latch
    private var holdArmed = false                     // HOLD tapped: the next button latches
    private var turboArmed = false                    // TURBO tapped: the next button latches and pulses
    private val turboLatched = HashSet<Int>()         // latched indexes that are pulsing
    private val mediaZone = HashMap<Int, Int>()       // media unit index -> the third under the finger
    private val mediaDrag = HashMap<Int, Int>()       // pointerId -> media unit whose track it is dragging
    private val videoZone = HashMap<Int, Int>()       // video unit index -> the third under the finger
    private val videoDrag = HashMap<Int, Int>()       // pointerId -> video unit whose timeline it is dragging
    private val videoScrub = HashMap<Int, Float>()    // video unit index -> the position being dragged to
    private val videoRow = HashMap<Int, Int>()        // pointerId -> 0 timeline, 2 volume
    private val videoDownX = HashMap<Int, Float>()    // pointerId -> where the drag began
    private val videoMoved = HashSet<Int>()           // pointers that have moved far enough to count
    private val dpadBy = HashMap<Int, Int>()         // pointerId -> D-pad index it is steering
    private val dpadMask = HashMap<Int, Int>()       // D-pad index -> directions held

    private class Swipe(val edge: EdgeGesture, val x0: Float, val y0: Float, val t0: Long)
    private val swipes = HashMap<Int, Swipe>()

    private fun short() = min(width, height).toFloat()

    /**
     * With the shield up the screen behind is not meant to be touched, so claim the left and right
     * edges from the system's back gesture. A back swipe there then lands on the shield and does
     * nothing, instead of reaching the app behind or falling through to the top screen. Android
     * caps how much of an edge an app may claim, so this covers the strip, not the whole side.
     */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strip = (BACK_EDGE_DP * resources.displayMetrics.density).toInt()
            systemGestureExclusionRects = listOf(Rect(0, 0, strip, h), Rect(w - strip, 0, w, h))
        }
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in layout.buttons.indices.reversed()) {
            val b = layout.buttons[i]
            val r = b.size * short() / 2f
            if (isSliderCode(b.code)) {
                // A slider is a narrow bar; only the bar and a little around it count.
                val cx = b.cx * width; val cy = b.cy * height
                if (abs(x - cx) <= r * 0.45f && y >= cy - r && y <= cy + r * 0.7f) return i
            } else if (isMediaUnit(b.code)) {
                // The media unit is a wide capsule, so its hit area is that rectangle.
                val cx = b.cx * width; val cy = b.cy * height
                if (abs(x - cx) <= r * ButtonPainter.MEDIA_HALF_W && abs(y - cy) <= r * ButtonPainter.MEDIA_HALF_H) return i
            } else if (isVideoUnit(b.code)) {
                val cx = b.cx * width; val cy = b.cy * height
                if (abs(x - cx) <= r * ButtonPainter.VIDEO_HALF_W && abs(y - cy) <= r * ButtonPainter.VIDEO_HALF_H) return i
            } else if (hypot(x - b.cx * width, y - b.cy * height) <= r) return i
        }
        return -1
    }

    override fun onDraw(c: Canvas) {
        if (backdropColor != 0) c.drawColor(backdropColor)
        // Opacity applies to the buttons and pills only, never to the backdrop.
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        layout.buttons.forEachIndexed { i, b ->
            val r = b.size * short() / 2f
            val label = Glyphs.label(b.code, layout.style)
            if (isDpadCode(b.code)) {
                painter.drawDpad(c, b.cx * width, b.cy * height, r, engine.enabled(b.code), dpadMask[i] ?: 0)
            } else if (isStickCode(b.code)) {
                val k = knob[i]
                painter.drawStick(c, b.cx * width, b.cy * height, r, label, engine.enabled(b.code), k?.get(0) ?: 0f, k?.get(1) ?: 0f)
            } else if (b.code == Action.SHIELD) {
                painter.drawShieldToggle(c, b.cx * width, b.cy * height, r, shieldOn)
            } else if (b.code == Action.HOLD) {
                painter.drawSysButton(c, b.cx * width, b.cy * height, r, label, null, holdArmed || (pressCount[i] ?: 0) > 0)
            } else if (b.code == Action.TURBO) {
                painter.drawSysButton(c, b.cx * width, b.cy * height, r, label, null, turboArmed || (pressCount[i] ?: 0) > 0)
            } else if (isVideoUnit(b.code)) {
                val vx = b.cx * width; val vy = b.cy * height
                val scrub = videoScrub[i]
                val f = scrub ?: video.fraction
                val shown = if (scrub != null && video.duration > 0L) (video.duration * f).toLong() else video.position
                painter.drawVideo(c, vx - r * ButtonPainter.VIDEO_HALF_W, vy - r * ButtonPainter.VIDEO_HALF_H,
                    vx + r * ButtonPainter.VIDEO_HALF_W, vy + r * ButtonPainter.VIDEO_HALF_H,
                    video.playing, f, levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] ?: 0.5f,
                    videoZone[i] ?: -1, NowPlaying.time(shown), NowPlaying.time(video.duration), false,
                    video.app, videoZone[i] == 9, AppIcons.of(context, video.pkg) { invalidate() }, video.title)
            } else if (isMediaUnit(b.code)) {
                val mx = b.cx * width; val my = b.cy * height
                painter.drawMedia(c, mx - r * ButtonPainter.MEDIA_HALF_W, my - r * ButtonPainter.MEDIA_HALF_H,
                    mx + r * ButtonPainter.MEDIA_HALF_W, my + r * ButtonPainter.MEDIA_HALF_H, mediaZone[i] ?: -1,
                    false, levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] ?: 0.5f, video.playing)
            } else if (isActionCode(b.code)) {
                painter.drawSysButton(c, b.cx * width, b.cy * height, r, label, Catalog.screenTag(b.code), (pressCount[i] ?: 0) > 0)
            } else if (isSliderCode(b.code)) {
                painter.drawSlider(c, b.cx * width, b.cy * height, r, label, Catalog.screenTag(b.code), levels[b.code] ?: 0.5f, sliderBy.containsValue(i))
            } else {
                painter.draw(c, b.cx * width, b.cy * height, r, label, engine.enabled(b.code), (pressCount[i] ?: 0) > 0 || i in latched, labelColor = Glyphs.color(b.code, layout.style),
                    mark = if (i in latched) 2 else if (b.sticky) 1 else 0, icon = Glyphs.icon(b.code, layout.style), turbo = b.turbo)
            }
        }
        // Small pills marking the gesture edges: top (panel) and bottom (hide).
        val w = width * 0.18f; val h = 8f
        c.drawRoundRect((width - w) / 2, 10f, (width + w) / 2, 10f + h, h, h, hint)
        c.drawRoundRect((width - w) / 2, height - 10f - h, (width + w) / 2, height - 10f, h, h, hint)
        c.restoreToCount(layer)
    }

    private fun steer(i: Int, x: Float, y: Float) {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f * 0.95f
        var dx = (x - b.cx * width) / r; var dy = (y - b.cy * height) / r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        knob[i] = floatArrayOf(dx, dy)
        engine.stick(b.code, dx, dy)
    }

    private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    /** The video unit's volume row moves the same audio the media unit's track does. */
    private fun videoVolume(i: Int, x: Float, final: Boolean) {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f
        val left = b.cx * width - r * ButtonPainter.VIDEO_HALF_W
        val w = 2f * r * ButtonPainter.VIDEO_HALF_W
        val vl = left + w * ButtonPainter.VIDEO_VOL_L
        val vr = left + w * ButtonPainter.VIDEO_VOL_R
        val lv = ((x - vl) / (vr - vl)).coerceIn(0f, 1f)
        levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] = lv
        onSlider(dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA, lv, final)
    }

    /** Where along a video unit's timeline a touch sits, 0..1. */
    private fun videoFrac(i: Int, x: Float): Float {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f
        val w = 2f * r * ButtonPainter.VIDEO_HALF_W
        val left = b.cx * width - r * ButtonPainter.VIDEO_HALF_W
        val h = 2f * r * ButtonPainter.VIDEO_HALF_H
        val (fl, fr) = ButtonPainter.videoTrackSpan(w, h, NowPlaying.time(video.duration))
        val tl = left + w * fl
        val tr = left + w * fr
        return ((x - tl) / (tr - tl)).coerceIn(0f, 1f)
    }

    /** Where along the media unit a touch sits, 0..1 across its width. */
    private fun mediaFrac(i: Int, x: Float): Float {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f
        val left = b.cx * width - r * ButtonPainter.MEDIA_HALF_W
        return ((x - left) / (2f * r * ButtonPainter.MEDIA_HALF_W)).coerceIn(0f, 1f)
    }

    private fun mediaSlide(i: Int, x: Float, final: Boolean) {
        val f = mediaFrac(i, x)
        val lv = ((f - ButtonPainter.MEDIA_BUTTONS_FRAC) / (1f - ButtonPainter.MEDIA_BUTTONS_FRAC)).coerceIn(0f, 1f)
        levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] = lv
        onSlider(dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA, lv, final)
    }

    private fun slide(i: Int, y: Float) {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f
        val lv = ButtonPainter.levelAt(b.cy * height, r, y)
        levels[b.code] = lv
        onSlider(b.code, lv, false)
    }

    /** The finger left a slider: commit its last level. */
    private fun endSlide(pid: Int) {
        val i = sliderBy.remove(pid) ?: return
        val code = layout.buttons[i].code
        onSlider(code, levels[code] ?: return, true)
    }

    private fun steerDpad(i: Int, x: Float, y: Float) {
        val b = layout.buttons[i]
        val r = b.size * short() / 2f * 0.95f
        val m = DpadMath.maskAt((x - b.cx * width) / r, (y - b.cy * height) / r)
        val old = dpadMask[i] ?: 0
        if (m != old) { DpadMath.apply(engine, old, m); dpadMask[i] = m }
    }

    private fun releaseDpad(pid: Int) {
        val i = dpadBy.remove(pid) ?: return
        DpadMath.apply(engine, dpadMask.remove(i) ?: 0, 0)
    }

    private fun releaseStick(pid: Int) {
        val i = stickBy.remove(pid) ?: return
        knob.remove(i)
        engine.stick(layout.buttons[i].code, 0f, 0f)
    }

    /**
     * [initial] is the finger's touch-down; a finger sliding onto a button later is not.
     * Latching happens only on touch-down: a latched button tapped again releases, a sticky
     * button or any button tapped while HOLD is armed latches down.
     */
    /** A turbo button pulses instead of simply going down, but is started and stopped the same way. */
    private fun fire(b: PadButton) { if (b.turbo) engine.startTurbo(b.code, b.turboMs) else engine.press(b.code) }
    private fun unfire(b: PadButton) { if (b.turbo) engine.stopTurbo(b.code) else engine.release(b.code) }

    private fun press(i: Int, pid: Int, initial: Boolean = false) {
        pressedBy[pid] = i
        val n = (pressCount[i] ?: 0) + 1
        pressCount[i] = n
        val b = layout.buttons[i]
        val code = b.code
        if (code == Action.HOLD) { if (initial) { holdArmed = !holdArmed; if (holdArmed) turboArmed = false }; return }
        if (code == Action.TURBO) { if (initial) { turboArmed = !turboArmed; if (turboArmed) holdArmed = false }; return }
        if (isActionCode(code)) return
        if (n != 1) return
        if (i in latched) {
            if (initial) {
                latched.remove(i); latchTouch.add(pid)
                if (turboLatched.remove(i)) engine.stopTurbo(code) else unfire(b)
            }
            return                                   // sliding over a held button leaves it held
        }
        if (initial && (b.sticky || holdArmed || turboArmed)) {
            // TURBO makes the latch pulse; so does the button's own turbo setting.
            val asTurbo = b.turbo || turboArmed
            holdArmed = false; turboArmed = false
            latched.add(i); if (asTurbo) turboLatched.add(i); latchTouch.add(pid)
            if (asTurbo) engine.startTurbo(code, b.turboMs) else engine.press(code)
            return
        }
        fire(b)
    }

    /** Action buttons fire on release, only if the finger is still on them. */
    private fun release(pid: Int, fireAction: Boolean = false) {
        val i = pressedBy.remove(pid) ?: return
        val n = (pressCount[i] ?: 1) - 1
        pressCount[i] = n
        val code = layout.buttons[i].code
        val toggled = latchTouch.remove(pid)
        if (n <= 0) {
            pressCount.remove(i)
            if (code == Action.HOLD) return
            if (isActionCode(code)) {
                // A unit reports which third was tapped, not the element's own code.
                val z = mediaZone.remove(i)
                val vz = videoZone.remove(i)
                val fired = when {
                    isMediaUnit(code) && z != null -> mediaCodeAt((z + 0.5f) / 3f)
                    isVideoUnit(code) && vz != null -> when (vz) {
                        9 -> Action.VIDEO_APP
                        0 -> Action.MEDIA_PREV
                        1 -> Action.VIDEO_BACK
                        2 -> Action.MEDIA_PLAY
                        3 -> Action.VIDEO_FWD
                        else -> Action.MEDIA_NEXT
                    }
                    else -> code
                }
                if (fireAction) onAction(fired)
            }
            else if (!toggled && i !in latched) unfire(layout.buttons[i])
        }
    }

    private fun edgeAt(x: Float, y: Float): EdgeGesture? {
        val zone = short() * EDGE_ZONE
        return when {
            y < zone -> EdgeGesture.PULL_DOWN            // opens our panel
            y > height - zone -> EdgeGesture.PULL_UP     // hides the pad
            else -> null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                val x = e.getX(idx); val y = e.getY(idx)
                val i = hit(x, y)
                val edge = if (i < 0) edgeAt(x, y) else null
                if (edge != null) {
                    swipes[pid] = Swipe(edge, x, y, e.eventTime)
                    if (edge == EdgeGesture.PULL_DOWN && onPullDown != null && swipes.size == 1) onPullDown.onPullStart()
                }
                else if (i >= 0 && isStickCode(layout.buttons[i].code)) { stickBy[pid] = i; steer(i, x, y) }
                else if (i >= 0 && isDpadCode(layout.buttons[i].code)) { dpadBy[pid] = i; steerDpad(i, x, y) }
                else if (i >= 0 && isSliderCode(layout.buttons[i].code)) { sliderBy[pid] = i; slide(i, y) }
                else {
                    tracked.add(pid)
                    if (i >= 0) {
                        val b = layout.buttons[i]
                        if (isVideoUnit(b.code)) {
                            val r = b.size * short() / 2f
                            val top = b.cy * height - r * ButtonPainter.VIDEO_HALF_H
                            val h = 2f * r * ButtonPainter.VIDEO_HALF_H
                            val ry = (y - top) / h
                            val left = b.cx * width - r * ButtonPainter.VIDEO_HALF_W
                            val w = 2f * r * ButtonPainter.VIDEO_HALF_W
                            when {
                                // 9 marks the app band, which is a tap rather than a transport zone.
                                ry < ButtonPainter.VIDEO_ROW_APP -> {
                                    videoZone[i] = 9; press(i, pid, initial = true)
                                }
                                ry < ButtonPainter.VIDEO_ROW_TIME -> {
                                    tracked.remove(pid); videoDrag[pid] = i; videoRow[pid] = 0; videoDownX[pid] = x
                                }
                                ry < ButtonPainter.VIDEO_ROW_CTRL -> {
                                    videoZone[i] = (((x - left) / w) * 5f).toInt().coerceIn(0, 4)
                                    press(i, pid, initial = true)
                                }
                                else -> {
                                    tracked.remove(pid); videoDrag[pid] = i; videoRow[pid] = 2; videoDownX[pid] = x
                                }
                            }
                            invalidate()
                            return true
                        }
                        val f = if (isMediaUnit(b.code)) mediaFrac(i, x) else 0f
                        if (isMediaUnit(b.code) && f >= ButtonPainter.MEDIA_BUTTONS_FRAC) {
                            // The track half of the media unit drags like a slider.
                            tracked.remove(pid); mediaDrag[pid] = i; mediaSlide(i, x, false)
                        } else {
                            if (isMediaUnit(b.code)) {
                                mediaZone[i] = (f / ButtonPainter.MEDIA_BUTTONS_FRAC * 3f).toInt().coerceIn(0, 2)
                            }
                            press(i, pid, initial = true)
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until e.pointerCount) {
                    val pid = e.getPointerId(idx)
                    stickBy[pid]?.let { si -> steer(si, e.getX(idx), e.getY(idx)); invalidate() }
                    dpadBy[pid]?.let { di -> steerDpad(di, e.getX(idx), e.getY(idx)); invalidate() }
                    sliderBy[pid]?.let { si -> slide(si, e.getY(idx)); invalidate() }
                    mediaDrag[pid]?.let { mi -> mediaSlide(mi, e.getX(idx), false); invalidate() }
                    videoDrag[pid]?.let { vi ->
                        // Both tracks answer a drag and ignore a tap, so a stray touch changes nothing.
                        val x0 = videoDownX[pid] ?: e.getX(idx)
                        if (pid !in videoMoved && abs(e.getX(idx) - x0) > touchSlop) videoMoved.add(pid)
                        if (pid in videoMoved) {
                            if (videoRow[pid] == 0) videoScrub[vi] = videoFrac(vi, e.getX(idx))
                            else videoVolume(vi, e.getX(idx), false)
                            invalidate()
                        }
                    }
                    swipes[pid]?.let { s -> if (s.edge == EdgeGesture.PULL_DOWN) onPullDown?.onPullMove(e.getY(idx) - s.y0) }
                }
                // A finger keeps being tracked while it crosses gaps, so it can slide A -> B.
                for (idx in 0 until e.pointerCount) {
                    val pid = e.getPointerId(idx)
                    if (pid !in tracked) continue
                    val cur = pressedBy[pid] ?: -1
                    val i = hit(e.getX(idx), e.getY(idx))
                    if (i != cur) { release(pid); if (i >= 0) press(i, pid); invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                release(pid, fireAction = pressedBy[pid]?.let { it == hit(e.getX(idx), e.getY(idx)) } == true)
                releaseStick(pid)
                releaseDpad(pid)
                mediaDrag.remove(pid)?.let { mi -> mediaSlide(mi, e.getX(idx), true) }
                videoDrag.remove(pid)?.let { vi ->
                    if (pid in videoMoved) {
                        // Seek once, on release: seeking on every move would stutter the player.
                        if (videoRow[pid] == 0) onSlider(dev.lbento.thorsidepad.inject.Slider.VIDEO_SEEK, videoFrac(vi, e.getX(idx)), true)
                        else videoVolume(vi, e.getX(idx), true)
                    }
                    videoScrub.remove(vi); videoRow.remove(pid); videoDownX.remove(pid); videoMoved.remove(pid)
                }
                endSlide(pid)
                tracked.remove(pid)
                swipes.remove(pid)?.let { s ->
                    val dx = e.getX(idx) - s.x0; val dy = e.getY(idx) - s.y0
                    val need = short() * SWIPE_LEN
                    val fast = e.eventTime - s.t0 < SWIPE_MAX_MS
                    val ok = when (s.edge) {
                        EdgeGesture.PULL_DOWN -> dy > need && abs(dx) < dy
                        EdgeGesture.PULL_UP -> -dy > need && abs(dx) < -dy
                    }
                    if (s.edge == EdgeGesture.PULL_DOWN && onPullDown != null) onPullDown.onPullEnd(ok)   // the shade needs no speed
                    else if (ok && fast) onGesture(s.edge)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipes.values.any { it.edge == EdgeGesture.PULL_DOWN }) onPullDown?.onPullEnd(false)
                pressedBy.keys.toList().forEach { release(it) }
                stickBy.keys.toList().forEach { releaseStick(it) }
                dpadBy.keys.toList().forEach { releaseDpad(it) }
                sliderBy.keys.toList().forEach { endSlide(it) }
                mediaDrag.clear(); videoDrag.clear(); videoScrub.clear()
                videoRow.clear(); videoDownX.clear(); videoMoved.clear()
                tracked.clear()
                swipes.clear()
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        pressedBy.keys.toList().forEach { release(it) }
        latched.toList().forEach { i -> if (i !in turboLatched) unfire(layout.buttons[i]) }
        latched.clear(); turboLatched.clear()
        engine.stopAllTurbo()
        stickBy.keys.toList().forEach { releaseStick(it) }
        dpadBy.keys.toList().forEach { releaseDpad(it) }
        super.onDetachedFromWindow()
    }

    companion object {
        const val EDGE_ZONE = 0.07f     // fraction of the short side that counts as an edge
        const val SWIPE_LEN = 0.15f     // minimum travel, fraction of the short side
        const val SWIPE_MAX_MS = 700L
        const val BACK_EDGE_DP = 32     // side strip taken from the system back gesture while the shield is up
    }
}
