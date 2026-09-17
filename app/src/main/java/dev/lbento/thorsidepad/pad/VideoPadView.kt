package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.Slider

/**
 * The video unit in its own overlay window (islands mode): a timeline you drag to seek, with jump
 * back, play or pause, and jump forward beneath it.
 */
class VideoPadView(
    ctx: Context,
    private val state: NowPlaying,
    private val onAction: (Int) -> Unit,
    private val onSlider: (Int, Float, Boolean) -> Unit,
) : View(ctx) {
    private val painter = ButtonPainter()
    private var zone = -1
    private var scrubbing = false
    private var scrubFrac = 0f

    override fun onDraw(c: Canvas) {
        val f = if (scrubbing) scrubFrac else state.fraction
        val shown = if (scrubbing && state.duration > 0L) (state.duration * f).toLong() else state.position
        painter.drawVideo(c, 0f, 0f, width.toFloat(), height.toFloat(), state.playing, f, zone,
            NowPlaying.time(shown), NowPlaying.time(state.duration))
    }

    private fun fracAt(x: Float): Float {
        val l = width * ButtonPainter.VIDEO_TRACK_L
        val r = width * ButtonPainter.VIDEO_TRACK_R
        return ((x - l) / (r - l)).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val onTimeline = e.y < height * ButtonPainter.VIDEO_SPLIT
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (onTimeline) { scrubbing = true; scrubFrac = fracAt(e.x); invalidate() }
                else { zone = ((e.x / width.coerceAtLeast(1)) * 3f).toInt().coerceIn(0, 2); invalidate() }
            MotionEvent.ACTION_MOVE -> if (scrubbing) { scrubFrac = fracAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                if (scrubbing) {
                    // Seek once, on release: seeking on every move would stutter the player.
                    onSlider(Slider.VIDEO_SEEK, fracAt(e.x), true)
                    scrubbing = false
                } else if (zone >= 0) {
                    onAction(when (zone) { 0 -> Action.VIDEO_BACK; 1 -> Action.MEDIA_PLAY; else -> Action.VIDEO_FWD })
                }
                zone = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { scrubbing = false; zone = -1; invalidate() }
        }
        return true
    }
}
