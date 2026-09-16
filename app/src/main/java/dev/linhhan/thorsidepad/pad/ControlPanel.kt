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

/** One controller the pad can write into. */
data class TargetChoice(val name: String, val path: String)

data class PanelState(
    val padVisible: Boolean, val shield: Boolean, val gestures: Boolean, val opacity: Float,
    val backdrop: String, val blurSupported: Boolean,
    val targets: List<TargetChoice>, val targetName: String, val virtual: Boolean, val chord: Boolean,
)

/** What the panel can do; each returns nothing and the service decides what happens. */
interface PanelActions {
    fun togglePad()
    fun editLayout()
    fun setShield(on: Boolean)
    fun setGestures(on: Boolean)
    fun setOpacity(value: Float)
    fun setBackdrop(value: String)
    fun setTarget(choice: TargetChoice?)   // null = virtual pad (player 2)
    fun setChord(on: Boolean)
    fun openThorControlCenter()
    fun stopService()
    fun close()
}

/**
 * Our own control panel, pulled down from the top edge of the pad's screen. A short main page
 * holds the everyday actions; four sections hold the settings. A translucent scrim fills the
 * display and tapping it closes the panel. Everything here works without window focus.
 */
object ControlPanel {
    enum class Page { MAIN, CONTROLLER, LOOKS, GESTURES, MORE }

    /** Remembered so a re-render after a setting change stays on the same page. */
    var page: Page = Page.MAIN

    fun build(themed: Context, state: PanelState, actions: PanelActions, maxHeightPx: Int): FrameLayout {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x88000000.toInt())
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) actions.close(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0202124.toInt())
            setPadding(28, 20, 28, 24)
            isClickable = true
        }
        val openedAt = android.os.SystemClock.uptimeMillis()

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
        /** A main-page row: title, a one-line summary of the section's current state, and a chevron. */
        fun sectionRow(title: String, summary: String, onClick: () -> Unit) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2A2E33.toInt())
            setPadding(24, 18, 24, 18)
            isClickable = true
            setOnClickListener { onClick() }
            val text = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(title, 16f))
            text.addView(label(summary, 12f, 0xFFB0B8C0.toInt()))
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 22f, 0xFFB0B8C0.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 }

        fun header(title: String, back: Boolean) {
            val bar = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
            if (back) bar.addView(btn("‹ Back") { page = Page.MAIN; render(card, ::header, ::label, ::row, ::btn, ::sw, ::sectionRow, ::rowParams, state, actions) })
            bar.addView(label(title, 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = if (back) 16 else 0 })
            bar.addView(btn("Close") { actions.close() })
            card.addView(bar)
        }

        render(card, ::header, ::label, ::row, ::btn, ::sw, ::sectionRow, ::rowParams, state, actions)

        val scroll = ScrollView(themed).apply { addView(card); isClickable = true }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx, Gravity.TOP))
        return root
    }

    private fun backdropName(b: String) = when (b) { "dim" -> "Dim"; "dark" -> "Dark"; "frosted" -> "Frosted"; else -> "Clear" }

    private fun render(
        card: LinearLayout,
        header: (String, Boolean) -> Unit,
        label: (String, Float, Int) -> TextView,
        row: (Array<Button>) -> LinearLayout,
        btn: (String, Boolean, () -> Unit) -> Button,
        sw: (String, Boolean, (Boolean) -> Unit) -> Switch,
        sectionRow: (String, String, () -> Unit) -> LinearLayout,
        rowParams: () -> LinearLayout.LayoutParams,
        state: PanelState, actions: PanelActions,
    ) {
        card.removeAllViews()
        fun go(p: Page) { page = p; render(card, header, label, row, btn, sw, sectionRow, rowParams, state, actions) }
        val white = Color.WHITE; val grey = 0xFFB0B8C0.toInt()
        when (page) {
            Page.MAIN -> {
                header("Thor SidePad", false)
                card.addView(row(arrayOf(
                    btn(if (state.padVisible) "Hide pad" else "Show pad", true) { actions.togglePad() },
                    btn("Edit layout", true) { actions.editLayout() },
                )))
                val target = if (state.virtual) "Virtual pad (2nd player)" else state.targetName.ifEmpty { "no controller found" }
                card.addView(sectionRow("Controller", "Presses go to $target") { go(Page.CONTROLLER) }, rowParams())
                card.addView(sectionRow("Looks", "Shield ${if (state.shield) "on" else "off"} · ${backdropName(state.backdrop)} backdrop · buttons ${(state.opacity * 100).toInt()}%") { go(Page.LOOKS) }, rowParams())
                card.addView(sectionRow("Gestures", "Left-edge Back ${if (state.gestures) "on" else "off"} · Select+Start chord ${if (state.chord) "on" else "off"}") { go(Page.GESTURES) }, rowParams())
                card.addView(sectionRow("More", "Thor Control Center · stop SidePad") { go(Page.MORE) }, rowParams())
                card.addView(label("Tap outside to close. Pull down from the top edge for this panel, pull up from the bottom edge to show or hide the pad.", 12f, grey).apply { setPadding(0, 14, 0, 0) })
            }
            Page.CONTROLLER -> {
                header("Controller", true)
                card.addView(label("Presses go to", 14f, grey))
                for (t in state.targets) {
                    val active = !state.virtual && t.name == state.targetName
                    card.addView(btn((if (active) "●  " else "") + t.name, !active) { actions.setTarget(t) })
                }
                card.addView(btn((if (state.virtual) "●  " else "") + "Virtual pad (shows up as a 2nd player)", !state.virtual) { actions.setTarget(null) })
                if (state.targets.isEmpty()) card.addView(label("No controller found. Is Shizuku running?", 12f, grey))
                card.addView(label("A Bluetooth controller appears here by name once it is connected. Writing into a real controller keeps games seeing one pad; the virtual pad is a separate device.", 12f, grey).apply { setPadding(0, 14, 0, 0) })
            }
            Page.LOOKS -> {
                header("Looks", true)
                card.addView(sw("Shield: block touches to the app behind the pad", state.shield) { actions.setShield(it) })
                card.addView(label("Behind the pad (shield mode)", 14f, grey))
                val choices = listOf("clear" to "Clear", "dim" to "Dim", "dark" to "Dark", "frosted" to (if (state.blurSupported) "Frosted" else "Frosted*"))
                card.addView(row(choices.map { (key, name) -> btn(name, key != state.backdrop) { actions.setBackdrop(key) } }.toTypedArray()))
                if (!state.blurSupported) card.addView(label("* This device cannot blur behind windows; Frosted falls back to a heavy dim.", 11f, grey))
                card.addView(label("Button opacity", 14f, grey))
                card.addView(SeekBar(card.context).apply {
                    max = 100; progress = (state.opacity * 100).toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) { actions.setOpacity(sb.progress / 100f) }
                    })
                })
            }
            Page.GESTURES -> {
                header("Gestures", true)
                card.addView(label("Always on: pull down from the top edge opens this panel; pull up from the bottom edge shows or hides the pad.", 13f, white))
                card.addView(sw("Swipe in from the left edge = Back", state.gestures) { actions.setGestures(it) })
                card.addView(sw("Hold Select + Start on the controller to show / hide the pad", state.chord) { actions.setChord(it) })
            }
            Page.MORE -> {
                header("More", true)
                card.addView(btn("Open the Thor Control Center", true) { actions.openThorControlCenter() })
                card.addView(btn("Stop SidePad", true) { actions.stopService() })
                card.addView(label("Stopping removes the pad and the edge strips. Start it again from the app, the Quick Settings tile, or the notification.", 12f, grey).apply { setPadding(0, 14, 0, 0) })
            }
        }
    }
}
