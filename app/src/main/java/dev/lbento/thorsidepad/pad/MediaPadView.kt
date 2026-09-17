package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Slider
import dev.lbento.thorsidepad.inject.mediaCodeAt

/**
 * The media unit in its own overlay window (islands mode): three transport zones and a volume
 * track in one element. The track moves the media stream, which is the volume of whatever is
 * playing on the device, so it follows the same audio the transport keys reach.
 */
class MediaPadView(
    ctx: Context,
    level: Float,
    private val onAction: (Int) -> Unit,
    private val onSlider: (Int, Float, Boolean) -> Unit,
) : View(ctx) {
    private val painter = ButtonPainter()
    private var level = level
    private var zone = -1
    private var sliding = false

    override fun onDraw(c: Canvas) {
        painter.drawMedia(c, 0f, 0f, width.toFloat(), height.toFloat(), zone, false, level)
    }

    private fun frac(x: Float) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun setLevel(x: Float, final: Boolean) {
        val f = frac(x)
        level = ((f - ButtonPainter.MEDIA_BUTTONS_FRAC) / (1f - ButtonPainter.MEDIA_BUTTONS_FRAC)).coerceIn(0f, 1f)
        onSlider(Slider.VOLUME, level, final)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (frac(e.x) >= ButtonPainter.MEDIA_BUTTONS_FRAC) { sliding = true; setLevel(e.x, false) }
                else { zone = (frac(e.x) / ButtonPainter.MEDIA_BUTTONS_FRAC * 3f).toInt().coerceIn(0, 2); invalidate() }
            MotionEvent.ACTION_MOVE -> if (sliding) setLevel(e.x, false)
            MotionEvent.ACTION_UP -> {
                if (sliding) { setLevel(e.x, true); sliding = false }
                else if (zone >= 0) onAction(mediaCodeAt((zone + 0.5f) / 3f))
                zone = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (sliding) { onSlider(Slider.VOLUME, level, true); sliding = false }
                zone = -1; invalidate()
            }
        }
        return true
    }
}
