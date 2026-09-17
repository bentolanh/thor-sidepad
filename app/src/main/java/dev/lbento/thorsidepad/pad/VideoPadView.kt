package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.Slider
import kotlin.math.abs

/**
 * The video unit in its own overlay window (islands mode): a timeline on top, the full transport
 * beneath it, and a volume row at the bottom. The two tracks answer a drag and ignore a tap, so a
 * stray touch never jumps the video or the volume.
 */
class VideoPadView(
    ctx: Context,
    private val now: NowPlaying,
    private var volume: Float,
    private val onAction: (Int) -> Unit,
    private val onSlider: (Int, Float, Boolean) -> Unit,
) : View(ctx) {
    private val painter = ButtonPainter()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var row = -1            // 0 app band, 1 timeline, 2 transport, 3 volume
    private var zone = -1
    private var downX = 0f
    private var dragging = false
    private var scrubFrac = 0f

    override fun onDraw(c: Canvas) {
        val scrubbing = dragging && row == 1
        val f = if (scrubbing) scrubFrac else now.fraction
        val shown = if (scrubbing && now.duration > 0L) (now.duration * f).toLong() else now.position
        painter.drawVideo(c, 0f, 0f, width.toFloat(), height.toFloat(), now.playing, f, volume, zone,
            NowPlaying.time(shown), NowPlaying.time(now.duration), false, now.app, row == 0)
    }

    private fun frac(x: Float, l: Float, r: Float) =
        ((x - width * l) / (width * (r - l))).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; dragging = false
                row = when {
                    e.y < h * ButtonPainter.VIDEO_ROW_APP -> 0
                    e.y < h * ButtonPainter.VIDEO_ROW_TIME -> 1
                    e.y < h * ButtonPainter.VIDEO_ROW_CTRL -> 2
                    else -> 3
                }
                if (row == 2) zone = ((e.x / width.coerceAtLeast(1)) * 5f).toInt().coerceIn(0, 4)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (row == 1 || row == 3) {
                if (!dragging && abs(e.x - downX) > slop) dragging = true
                if (dragging) {
                    if (row == 1) scrubFrac = frac(e.x, ButtonPainter.VIDEO_TRACK_L, ButtonPainter.VIDEO_TRACK_R)
                    else {
                        volume = frac(e.x, ButtonPainter.VIDEO_VOL_L, ButtonPainter.VIDEO_VOL_R)
                        onSlider(Slider.VOLUME_MEDIA, volume, false)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    row == 0 && now.app.isNotEmpty() -> onAction(Action.VIDEO_APP)
                    row == 2 && zone >= 0 -> onAction(when (zone) {
                        0 -> Action.MEDIA_PREV
                        1 -> Action.VIDEO_BACK
                        2 -> Action.MEDIA_PLAY
                        3 -> Action.VIDEO_FWD
                        else -> Action.MEDIA_NEXT
                    })
                    // Seek once, on release: seeking on every move would stutter the player.
                    row == 1 && dragging -> onSlider(Slider.VIDEO_SEEK, scrubFrac, true)
                    row == 3 && dragging -> onSlider(Slider.VOLUME_MEDIA, volume, true)
                }
                row = -1; zone = -1; dragging = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { row = -1; zone = -1; dragging = false; invalidate() }
        }
        return true
    }
}
