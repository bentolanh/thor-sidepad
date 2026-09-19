package dev.lbento.thorsidepad.pad

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
import android.view.View

/** One controller the pad can write into. `name` is the kernel name; `label` is what the user sees. */
data class TargetChoice(val name: String, val path: String) {
    val label: String get() = thorLabel(name) ?: name
}

/** A machine the Thor is paired with, that presses can be sent to instead of into this device. */
data class HostChoice(val address: String, val label: String)

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
    val shizukuReady: Boolean = true,
    val activeProfile: String = "",
    /** Where presses go: this device, or one of [hosts]. */
    val remote: Boolean = false,
    val hosts: List<HostChoice> = emptyList(),
    val hostAddress: String = "",
    val hostConnected: Boolean = false,
    /** Pairing: the name a computer will see, and how long the Thor is still visible for. */
    val padName: String = "",
    val visibleFor: Int = 0,
    /** Computers already paired with this Thor that SidePad does not yet know about. */
    val adoptable: List<HostChoice> = emptyList(),
)

/** What the panel can do; each returns nothing and the service decides what happens. */
interface PanelActions {
    fun pickProfile()
    fun editLayout()
    fun setShield(on: Boolean)
    fun setOpacity(value: Float)
    fun setBackdrop(value: String)
    fun setTarget(choice: TargetChoice?)   // null = virtual pad (player 2)
    /** Empty address = this device; otherwise the paired machine to send to. */
    fun setDestination(address: String)
    fun pairMachine()
    fun makeVisible()
    fun adoptMachine(address: String)
    fun stopService()
    fun openApp()
    fun startShizuku()
    fun close()
}

/**
 * Our own control panel, pulled down from the top edge of the pad's screen. A short main page
 * holds the everyday actions; four sections hold the settings. A translucent scrim fills the
 * display and tapping it closes the panel. Everything here works without window focus.
 */
object ControlPanel {
    enum class Page { MAIN, CONTROLLER, DESTINATION, PAIRING }

    /** Remembered so a re-render after a setting change stays on the same page. */
    var page: Page = Page.MAIN

    /** The built panel window: the scrim and the sheet (animated separately) plus an in-place re-render. */
    class Handle(val root: FrameLayout, val scrim: View, val sheet: View, val update: (PanelState) -> Unit)

    fun build(themed: Context, initial: PanelState, actions: PanelActions, maxHeightPx: Int): Handle {
        var state = initial
        val root = FrameLayout(themed)
        val scrim = View(themed).apply {
            setBackgroundColor(0x88000000.toInt())
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) actions.close(); true }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        /**
         * A settings row rather than a button: the caption on the left, what it is set to on the
         * right, and a chevron. Two of these sit together in one panel so they read as a pair of
         * choices, not as more action buttons.
         */
        fun pickerRow(caption: String, value: String, onClick: () -> Unit) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 12, 18, 12)
            setOnClickListener { onClick() }
            addView(TextView(themed).apply { text = caption; setTextColor(grey); textSize = 13f })
            addView(TextView(themed).apply {
                text = value; setTextColor(Color.WHITE); textSize = 16f
                gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 20 })
            addView(TextView(themed).apply { text = "›"; setTextColor(0xFF8AB4F8.toInt()); textSize = 20f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 14 })
        }
        fun hairline() = View(themed).apply { setBackgroundColor(0x22FFFFFF) }
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
            // Every page that is not the main one needs a way back; the scrim only closes the panel.
            if (page != Page.MAIN) bar.addView(btn("‹ Back") { page = Page.MAIN; render() })
            bar.addView(label(when (page) {
                Page.MAIN -> "Thor SidePad"
                Page.DESTINATION -> "Send to"
                Page.PAIRING -> "Pair a new machine"
                else -> "Appears as"
            }, 20f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = if (page != Page.MAIN) 16 else 0 })
            if (page == Page.MAIN) {
                bar.addView(btn("Open app") { actions.openApp() })
                bar.addView(btn("Stop SidePad") { actions.stopService() })
            }
            card.addView(bar)

            if (page == Page.MAIN) {
                if (!state.shizukuReady) {
                    val notice = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x33FF6B5A); setPadding(16, 10, 16, 10) }
                    notice.addView(TextView(themed).apply {
                        text = "Shizuku is not running, so the pad cannot press anything. Open Shizuku and tap Start there, then come back."
                        setTextColor(0xFFFFB4A9.toInt()); textSize = 14f
                    })
                    notice.addView(btn("Start Shizuku") { actions.startShizuku() })
                    card.addView(notice)
                }
                // Quick settings: everything on one page, as before.
                val target = if (state.virtual) "Virtual pad (2nd player)" else (thorLabel(state.targetName) ?: state.targetName).ifEmpty { "no controller found" }
                val choicesGroup = LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF2A2D31.toInt())
                }
                // Two questions, not one: where the presses go, and what the receiver thinks they
                // come from. They were the same thing only while there was one possible destination.
                val hostLabel = state.hosts.firstOrNull { it.address == state.hostAddress }?.label ?: "a machine"
                val whereTo = if (!state.remote) "This device"
                              else hostLabel + (if (state.hostConnected) "" else " — not connected")
                choicesGroup.addView(pickerRow("Send to", whereTo) { page = Page.DESTINATION; render() })
                choicesGroup.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                choicesGroup.addView(pickerRow("Appears as", if (state.remote) "A gamepad" else target) {
                    // Remote has only one answer for now; the row stays so the question is visible.
                    if (!state.remote) { page = Page.CONTROLLER; render() }
                })
                choicesGroup.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                choicesGroup.addView(pickerRow("Profile", state.activeProfile) { actions.pickProfile() })
                card.addView(choicesGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 14; bottomMargin = 14 })
                // The one action on this page, so it is coloured rather than a third grey band.
                card.addView(btn("Edit layout") { actions.editLayout() }.apply {
                    setBackgroundColor(0xFF31507E.toInt()); setTextColor(Color.WHITE)
                })
                card.addView(sw("Shield: block touches to the app behind the pad", state.shield) { actions.setShield(it) })
                card.addView(label("Behind the pad (shield mode)", 14f, grey))
                val choices = listOf("clear" to "Clear", "dim" to "Dim", "dark" to "Dark", "frosted" to (if (state.blurSupported) "Frosted" else "Frosted*"))
                card.addView(row(*choices.map { (key, name) -> btn(name, key != state.backdrop) { actions.setBackdrop(key) } }.toTypedArray()))
                if (!state.blurSupported) card.addView(label("* This device cannot blur behind windows; Frosted falls back to a heavy dim.", 11f, grey))
                card.addView(label("Button transparency", 14f, grey))
                // Kept well away from both screen edges so the thumb never sits in the system back-gesture zone.
                card.addView(SeekBar(themed).apply {
                    // Slider = transparency: further right, more see-through (stored as opacity underneath).
                    max = 90; progress = (100 - state.opacity * 100).toInt().coerceIn(0, 90)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) { actions.setOpacity((100 - sb.progress) / 100f) }
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 140; marginEnd = 140
                })
                card.addView(label("Tap outside to close. Pull down from the top edge for this panel, pull up from the bottom edge to show or hide the pad.", 12f, grey).apply { setPadding(0, 10, 0, 0) })
            } else if (page == Page.PAIRING) {
                // A controller normally has a button you hold until a light blinks. This is that
                // light: it says the Thor is listening, what to look for, and how long is left.
                if (state.visibleFor > 0) {
                    card.addView(label("Visible now as \u201C${state.padName}\u201D", 22f))
                    card.addView(label("${state.visibleFor} seconds left", 16f, grey))
                    card.addView(label(
                        "On the computer, open Bluetooth and choose \u201C${state.padName}\u201D. " +
                        "Once it has paired it stays paired; you only do this once per machine.",
                        13f, grey).apply { setPadding(0, 14, 0, 0) })
                } else {
                    card.addView(label("The Thor is not visible to other machines", 18f))
                    card.addView(label(
                        "Making it visible is the same as holding the pairing button on an ordinary " +
                        "controller. It lasts two minutes, then stops on its own.",
                        13f, grey).apply { setPadding(0, 6, 0, 12) })
                    card.addView(btn("Make the Thor visible") { actions.makeVisible() }.apply {
                        setBackgroundColor(0xFF31507E.toInt()); setTextColor(Color.WHITE)
                    })
                }
                // Something paired with the Thor outside SidePad is not offered as a destination by
                // itself, since the pairing list is mostly headphones and controllers. It can be
                // taken on deliberately here, which is a choice rather than a guess.
                if (state.adoptable.isNotEmpty()) {
                    card.addView(label("Already paired with this Thor", 14f, grey)
                        .apply { setPadding(0, 18, 0, 4) })
                    for (h in state.adoptable) card.addView(btn("Use ${h.label}") { actions.adoptMachine(h.address) })
                }
            } else if (page == Page.DESTINATION) {
                card.addView(label("Send presses to", 14f, grey))
                card.addView(btn((if (!state.remote) "\u25CF  " else "") + "This device", state.remote) { actions.setDestination("") })
                for (h in state.hosts) {
                    val active = state.remote && h.address == state.hostAddress
                    card.addView(btn((if (active) "\u25CF  " else "") + h.label, !active) { actions.setDestination(h.address) })
                }
                card.addView(btn("Pair a new machine\u2026") { page = Page.PAIRING; render() })
                card.addView(label(
                    if (state.hosts.isEmpty())
                        "Nothing paired yet. Pair a computer and the Thor becomes a controller for it, over Bluetooth."
                    else "Sending to a machine needs no Shizuku: the Thor presents itself as an ordinary Bluetooth gamepad.",
                    12f, grey).apply { setPadding(0, 14, 0, 0) })
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
        return Handle(root, scrim, scroll) { s -> state = s; render() }
    }
}
