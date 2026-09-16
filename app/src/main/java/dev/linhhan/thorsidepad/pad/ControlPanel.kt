package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** What the panel shows. */
data class PanelState(val padVisible: Boolean, val shield: Boolean, val gestures: Boolean, val opacity: Float, val pullUpTop: Boolean)

/** What the panel can do; each returns nothing and the service decides what happens. */
interface PanelActions {
    fun togglePad()
    fun editLayout()
    fun setShield(on: Boolean)
    fun setGestures(on: Boolean)
    fun setPullUpTop(on: Boolean)
    fun setOpacity(value: Float)
    fun openThorControlCenter()
    fun stopService()
    fun close()
}

/**
 * Our own control panel, pulled down from the top edge of the pad's screen. A translucent scrim
 * fills the display; tapping it closes the panel. Everything here works without window focus.
 */
object ControlPanel {
    fun build(themed: Context, state: PanelState, actions: PanelActions): FrameLayout {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x88000000.toInt())
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) actions.close(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0202124.toInt())
            setPadding(28, 20, 28, 24)
            isClickable = true   // swallow touches so they do not reach the scrim
        }
        fun label(t: String, size: Float = 16f) = TextView(themed).apply { text = t; setTextColor(Color.WHITE); textSize = size }
        fun row(vararg buttons: Button) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { b -> addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun btn(t: String, onClick: () -> Unit) = Button(themed).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
        val openedAt = android.os.SystemClock.uptimeMillis()
        fun sw(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(themed).apply {
            text = t; isChecked = checked; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { v, on ->
                // A switch flipped within the first moments is a stray touch from the opening gesture, not a choice.
                if (android.os.SystemClock.uptimeMillis() - openedAt < 500) { v.isChecked = !on; return@setOnCheckedChangeListener }
                onChange(on)
            }
        }

        card.addView(label("Thor SidePad", 20f))
        card.addView(row(
            btn(if (state.padVisible) "Hide pad" else "Show pad") { actions.togglePad() },
            btn("Edit layout") { actions.editLayout() },
        ))
        card.addView(sw("Shield: block touches to the app behind the pad", state.shield) { actions.setShield(it) })
        card.addView(sw("Edge swipes: pull up = Home, from left = Back", state.gestures) { actions.setGestures(it) })
        card.addView(sw("Pull up goes Home on the top screen (off: presses the Thor's Home key)", state.pullUpTop) { actions.setPullUpTop(it) })
        card.addView(label("Opacity", 14f))
        card.addView(SeekBar(themed).apply {
            max = 100; progress = (state.opacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { actions.setOpacity(sb.progress / 100f) }
            })
        })
        card.addView(row(
            btn("Thor Control Center") { actions.openThorControlCenter() },
            btn("Stop SidePad") { actions.stopService() },
        ))
        card.addView(label("Tap outside to close. Pull down from the top edge to open this panel any time.", 12f))

        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        return root
    }
}
