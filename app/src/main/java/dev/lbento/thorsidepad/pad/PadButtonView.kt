package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.isActionCode

/** A single round button living in its own overlay window (islands mode). */
class PadButtonView(
    ctx: Context,
    val code: Int,
    private val enabled: Boolean,
    private val onPress: (Int) -> Unit,
    private val onRelease: (Int) -> Unit,
    style: String = Glyphs.XBOX,
    private val sticky: Boolean = false,
    private val session: LatchSession? = null,
    private val turbo: Boolean = false,
    private val onTurboStart: (Int) -> Unit = {},
    private val onTurboStop: (Int) -> Unit = {},
) : View(ctx) {

    /** A turbo button pulses instead of simply going down, but is started and stopped the same way. */
    private fun fire() { if (turbo) onTurboStart(code) else onPress(code) }
    private fun unfire() { if (turbo) onTurboStop(code) else onRelease(code) }

    private var down = false
    private var toggled = false     // this touch latched or released the button; its lift must not send a release
    private val latched get() = session?.latched?.contains(code) == true
    private val label = Glyphs.label(code, style)
    private val labelColor = Glyphs.color(code, style)
    private val icon = Glyphs.icon(code, style)
    private val painter = ButtonPainter()

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        when {
            code == Action.SHIELD -> painter.drawShieldToggle(c, r, r, r, false)
            code == Action.HOLD -> painter.drawSysButton(c, r, r, r, label, null, down || session?.holdArmed == true)
            isActionCode(code) -> painter.drawSysButton(c, r, r, r, label, Catalog.screenTag(code), down)
            else -> painter.draw(c, r, r, r, label, enabled, down || latched, labelColor = labelColor, mark = if (latched) 2 else if (sticky) 1 else 0, icon = icon, turbo = turbo)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!down) {
                down = true; invalidate()
                if (!enabled) return true
                val s = session
                when {
                    code == Action.HOLD -> { s?.arm(!s.holdArmed); toggled = true }
                    isActionCode(code) -> {}
                    s != null && code in s.latched -> { s.latched.remove(code); toggled = true; unfire() }
                    s != null && (sticky || s.holdArmed) -> { s.arm(false); s.latched.add(code); toggled = true; fire() }
                    else -> fire()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (down) {
                down = false; invalidate()
                if (toggled) toggled = false else if (enabled) unfire()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        // A held button must not stay down once its window is gone.
        session?.let { if (code in it.latched) { it.latched.remove(code); unfire() } }
        super.onDetachedFromWindow()
    }
}
