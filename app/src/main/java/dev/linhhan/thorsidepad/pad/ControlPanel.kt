package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** One controller the pad can write into. `name` is the kernel name; `label` is what the user sees. */
data class TargetChoice(val name: String, val path: String) {
    val label: String get() = thorLabel(name) ?: name
}

/**
 * The Thor's built-in controller reports one of two names depending on the Control Center's
 * "Handle Style". Both mean the same physical pad, so both are shown as "Thor controller".
 */
fun thorLabel(name: String): String? = when (name) {
    "Xbox Wireless Controller" -> "Thor controller (Xbox style)"
    "Odin Controller" -> "Thor controller (Standard style)"
    else -> null
}
fun isThorName(name: String) = thorLabel(name) != null

data class PanelState(
    val padVisible: Boolean, val shield: Boolean, val opacity: Float,
    val backdrop: String, val blurSupported: Boolean,
    val targets: List<TargetChoice>, val targetName: String, val virtual: Boolean,
)

/** What the panel can do; each returns nothing and the service decides what happens. */
interface PanelActions {
    fun togglePad()
    fun editLayout()
    fun setShield(on: Boolean)
    fun setOpacity(value: Float)
    fun setBackdrop(value: String)
    fun setTarget(choice: TargetChoice?)   // null = virtual pad (player 2)
    fun stopService()
    fun close()
}

/**
 * Our own control panel, pulled down from the top edge of the pad's screen. A short main page
 * holds the everyday actions; four sections hold the settings. A translucent scrim fills the
 * display and tapping it closes the panel. Everything here works without window focus.
 */
object ControlPanel {
    enum class Page { MAIN, CONTROLLER }

    /** Remembered so a re-render after a setting change stays on the same page. */
    var page: Page = Page.MAIN

    /** The built panel window and a way to re-render its contents in place with a new state. */
    class Handle(val root: FrameLayout, val update: (PanelState) -> Unit)

    fun build(themed: Context, initial: PanelState, actions: PanelActions, maxHeightPx: Int): Handle {
        var state = initial
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x88000000.toInt())
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) actions.close(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0202124.toInt())
            setPadding(28, 20, 28, 24)
            // Swallow touches in the gaps without being "clickable": a clickable parent pushes its
            // pressed state onto non-clickable children, which made the opacity thumb light up on any tap.
            setOnTouchListener { _, _ -> true }
        }
        val openedAt = android.os.SystemClock.uptimeMillis()
        val grey = 0xFFB0B8C0.toInt()

        fun label(t: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(themed).apply { text = t; setTextColor(color); textSize = size }
        fun row(vararg buttons: Button) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { b -> addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun btn(t: String, enabled: Boolean = true, onClick: () -> Unit) = Button(themed).apply { text = t; isAllCaps = false; isEnabled = enabled; setOnClickListener { onClick() } }
        fun sw(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(themed).apply {
            text = t; isChecked = checked; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { v, on ->
                // A switch flipped within the first moments is a stray touch from the opening gesture, not a choice.
                if (android.os.SystemClock.uptimeMillis() - openedAt < 500) { v.isChecked = !on; return@setOnCheckedChangeListener }
                onChange(on)
            }
        }

        fun render() {
            card.removeAllViews()
            val bar = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
            if (page == Page.CONTROLLER) bar.addView(btn("‹ Back") { page = Page.MAIN; render() })
            bar.addView(label(if (page == Page.MAIN) "Thor SidePad" else "Controller", 20f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = if (page == Page.CONTROLLER) 16 else 0 })
            if (page == Page.MAIN) bar.addView(btn("Stop SidePad") { actions.stopService() })
            card.addView(bar)

            if (page == Page.MAIN) {
                // Quick settings: everything on one page, as before.
                val target = if (state.virtual) "Virtual pad (2nd player)" else (thorLabel(state.targetName) ?: state.targetName).ifEmpty { "no controller found" }
                card.addView(btn("Controller: $target  ›") { page = Page.CONTROLLER; render() })
                card.addView(row(
                    btn(if (state.padVisible) "Hide pad" else "Show pad") { actions.togglePad() },
                    btn("Edit layout") { actions.editLayout() },
                ))
                card.addView(sw("Shield: block touches to the app behind the pad", state.shield) { actions.setShield(it) })
                card.addView(label("Behind the pad (shield mode)", 14f, grey))
                val choices = listOf("clear" to "Clear", "dim" to "Dim", "dark" to "Dark", "frosted" to (if (state.blurSupported) "Frosted" else "Frosted*"))
                card.addView(row(*choices.map { (key, name) -> btn(name, key != state.backdrop) { actions.setBackdrop(key) } }.toTypedArray()))
                if (!state.blurSupported) card.addView(label("* This device cannot blur behind windows; Frosted falls back to a heavy dim.", 11f, grey))
                card.addView(label("Button opacity", 14f, grey))
                card.addView(SeekBar(themed).apply {
                    max = 100; progress = (state.opacity * 100).toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) { actions.setOpacity(sb.progress / 100f) }
                    })
                })
                card.addView(label("Tap outside to close. Pull down from the top edge for this panel, pull up from the bottom edge to show or hide the pad.", 12f, grey).apply { setPadding(0, 10, 0, 0) })
            } else {
                card.addView(label("Presses go to", 14f, grey))
                for (t in state.targets) {
                    val active = !state.virtual && t.name == state.targetName
                    card.addView(btn((if (active) "●  " else "") + t.label, !active) { actions.setTarget(t) })
                }
                card.addView(btn((if (state.virtual) "●  " else "") + "Virtual pad (shows up as a 2nd player)", !state.virtual) { actions.setTarget(null) })
                if (state.targets.isEmpty()) card.addView(label("No controller found. Is Shizuku running?", 12f, grey))
                card.addView(label("A Bluetooth controller appears here by name once it is connected. Writing into a real controller keeps games seeing one pad; the virtual pad is a separate device.", 12f, grey).apply { setPadding(0, 14, 0, 0) })
            }
        }
        render()

        val scroll = ScrollView(themed).apply { addView(card) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx, Gravity.TOP))
        return Handle(root) { s -> state = s; render() }
    }
}
