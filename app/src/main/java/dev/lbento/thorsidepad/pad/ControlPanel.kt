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
    val label: String get() = builtInLabel(name) ?: name
}

/** A machine the Thor is paired with, that presses can be sent to instead of into this device. */
data class HostChoice(val address: String, val label: String)

/**
 * The Thor's built-in controller reports one of two names depending on the Control Center's
 * "Handle Style". Both mean the same physical pad, so both are shown as "Built-in controller".
 */
fun builtInLabel(name: String): String? = when (name) {
    "Xbox Wireless Controller" -> "Built-in controller (Xbox style)"
    "Odin Controller" -> "Built-in controller (Standard style)"
    else -> null
}
fun isBuiltInName(name: String) = builtInLabel(name) != null

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
    val keepAwake: Boolean = false,
    /** Computers already paired with this Thor that SidePad does not yet know about. */
    val adoptable: List<HostChoice> = emptyList(),
    /** Which radio carries presses to a machine, and what that machine is told the pad is. */
    val transport: String = "classic",
    val identity: String = "OWN",
    /** False on a single-screen device, where the top and bottom edges belong to Android. */
    val edges: Boolean = true,
)

/** What the panel can do; each returns nothing and the service decides what happens. */
interface PanelActions {
    fun pickProfile()
    fun editLayout()
    fun setShield(on: Boolean)
    fun setKeepAwake(on: Boolean)
    fun setOpacity(value: Float)
    fun setBackdrop(value: String)
    fun setTarget(choice: TargetChoice?)   // null = virtual pad (player 2)
    /** Empty address = this device; otherwise the paired machine to send to. */
    fun setDestination(address: String)
    /** Sends to a machine over Low Energy, which needs no machine chosen first: one comes to us. */
    fun useLowEnergy()
    fun setTransport(value: String)
    fun setIdentity(value: String)
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
    enum class Page { MAIN, CONTROLLER, DESTINATION, PAIRING, APPEARANCE }

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

        /**
         * The identities a machine can be told this pad is.
         *
         * One list, because there were two copies on two pages and they drifted the moment a
         * third identity was added: the pairing page offered it, the appearance page did not,
         * and the appearance page is where the choice is actually reached from.
         */
        fun identityButtons(into: LinearLayout) {
            // Two choices, not four. Two Xbox modes meant offering one that was measured as
            // worse, and the fourth shared this one's vendor and product while sending a
            // different report.
            val xbox = state.identity == "XBOX_SERIES"
            val dot = "\u25CF  "
            into.addView(btn((if (!xbox) dot else "") + "SidePad", xbox) { actions.setIdentity("OWN") })
            into.addView(btn((if (xbox) dot else "") + "An Xbox-compatible pad", !xbox) { actions.setIdentity("XBOX_SERIES") })
            into.addView(label(
                "As an Xbox pad it behaves as a real Xbox controller does, which is what nearly " +
                "every third-party controller claims to be: games that work with one work with " +
                "this, and games that want Steam Input want it either way. As SidePad it is " +
                "honest about what it is, and a machine has to be taught which button is which " +
                "\u2014 some, macOS among them, will not treat it as a game controller at all. " +
                "Either way this is a different device to the machine, so it needs pairing again.",
                12f, grey).apply { setPadding(0, 10, 0, 0) })
        }
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
                Page.MAIN -> "SidePad"
                Page.DESTINATION -> "Send button presses to"
                Page.APPEARANCE -> "Appears as"
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
                // The page is two subjects, and used to run them together in one stack: where the
                // presses end up, and the pad drawn on this screen. Every row then had to carry
                // its own context in its title, and two of them could not. "Appears as" was the
                // worst of it — two unrelated settings sharing a row, telling the pad which
                // controller to write into when presses stay here and which identity to claim
                // when they do not, chosen by a mode set one row above.
                //
                // So: two headed groups, and a row appears only where it means something.
                // Which mode the pad is in decides half of what this page means, and the only
                // sign of it was one word inside one row. So the sending group is coloured: a
                // cool blue while presses are leaving for another machine, plain grey while they
                // stay here. The heading takes the same colour, which is enough to read across
                // the room without adding a word.
                val away = 0xFF2E4C6D.toInt()
                val hereTint = 0xFF2A2D31.toInt()
                fun heading(t: String, color: Int = grey) = label(t, 13f, color).apply { setPadding(0, 4, 0, 6) }
                fun group(tint: Int = hereTint) = LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(tint)
                }
                val groupLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 16 }
                val line = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)

                // ---- where the presses end up ----
                val target = if (state.virtual) "Virtual pad (2nd player)" else (builtInLabel(state.targetName) ?: state.targetName).ifEmpty { "no controller found" }
                val hostLabel = state.hosts.firstOrNull { it.address == state.hostAddress }?.label ?: "a machine"
                val whereTo = if (!state.remote) "This device"
                              else hostLabel + (if (state.hostConnected) "" else " — not connected")
                val sending = group(if (state.remote) away else hereTint)
                sending.addView(pickerRow("Send button presses to", whereTo) { page = Page.DESTINATION; render() })
                sending.addView(hairline(), line)
                if (state.remote) {
                    // Only a machine on the other end has an opinion about what we look like.
                    val remoteAs = if (state.identity == "XBOX_SERIES") "An Xbox-compatible pad" else "SidePad"
                    sending.addView(pickerRow("Appears as", remoteAs) { page = Page.APPEARANCE; render() })
                } else {
                    sending.addView(pickerRow("Controller", target) { page = Page.CONTROLLER; render() })
                }
                card.addView(heading(
                    if (state.remote) "Where presses go \u2014 another machine" else "Where presses go \u2014 this device",
                    if (state.remote) 0xFF9CC4F0.toInt() else grey))
                card.addView(sending, groupLp)
                if (state.remote) {
                    // Holding the machine awake does nothing at all when the presses never leave
                    // it, so the switch is not offered there. The behaviour was already limited
                    // this way; only the switch was not.
                    card.addView(sw("Keep the controller awake when the screen sleeps", state.keepAwake) { actions.setKeepAwake(it) })
                    card.addView(label(
                        "Off, this device sleeps as usual and stops reading its own controller, so presses " +
                        "stop reaching the machine even though the pad still looks connected. On, it " +
                        "keeps working with the screens dark and uses more battery.",
                        11f, grey).apply { setPadding(0, 0, 0, 16) })
                }

                // ---- the pad drawn on this screen ----
                val padGroup = group()
                padGroup.addView(pickerRow("Pad layout", state.activeProfile) { actions.pickProfile() })
                card.addView(heading("The pad on this screen"))
                card.addView(padGroup, groupLp)
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
            } else if (page == Page.PAIRING) {
                // One screen, one story, whichever radio is underneath. Pick the mode, press the
                // button, go to the machine and connect. What the two radios do differently to
                // become findable is ours to worry about and nobody else's: an earlier version
                // made the mode live on another page and told the user why Low Energy was
                // different, which is a plumbing detail dressed up as a choice.
                card.addView(label("Appears as", 14f, grey))
                identityButtons(card)
                card.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = 18; bottomMargin = 14 })
                if (state.visibleFor > 0) {
                    card.addView(label("Visible now as \u201C${state.padName}\u201D", 22f))
                    card.addView(label("${state.visibleFor} seconds left", 16f, grey))
                    card.addView(label(
                        "On the computer, open Bluetooth and connect to \u201C${state.padName}\u201D.",
                        13f, grey).apply { setPadding(0, 14, 0, 0) })
                    // While the window is open there used to be nothing to press. A pairing that
                    // failed — and the first attempt often did — left the player watching a
                    // countdown for up to two minutes with no way to try again.
                    card.addView(btn("Start the two minutes again") { actions.makeVisible() }
                        .apply { setPadding(0, 12, 0, 0) })
                } else {
                    card.addView(btn("Make this device visible") { actions.makeVisible() }.apply {
                        setBackgroundColor(0xFF31507E.toInt()); setTextColor(Color.WHITE)
                    })
                    card.addView(label(
                        "Two minutes, then it stops on its own. Then connect to it from the computer.",
                        13f, grey).apply { setPadding(0, 8, 0, 0) })
                }
                if (state.adoptable.isNotEmpty()) {
                    card.addView(label("Already paired with this device", 14f, grey)
                        .apply { setPadding(0, 18, 0, 4) })
                    for (h in state.adoptable) card.addView(btn("Use ${h.label}") { actions.adoptMachine(h.address) })
                }
                card.addView(label(
                    "Changing what it appears as makes this a different device to the computer, so " +
                    "it has to be paired again \u2014 and if it will not connect, forget it on the " +
                    "computer and on this device both, since clearing one side leaves keys behind.",
                    12f, grey).apply { setPadding(0, 18, 0, 0) })
            } else if (page == Page.APPEARANCE) {
                run {
                    card.addView(label("Known to the machine as", 14f, grey))
                    identityButtons(card)
                    card.addView(btn("Pair a machine\u2026") { page = Page.PAIRING; render() }.apply {
                        setBackgroundColor(0xFF31507E.toInt()); setTextColor(Color.WHITE)
                    })
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
                        "Nothing paired yet. Pair a computer and this device becomes a controller for it, over Bluetooth."
                    else "Sending to a machine needs no Shizuku: this device presents itself as an ordinary Bluetooth gamepad.",
                    12f, grey).apply { setPadding(0, 14, 0, 0) })
                // Low Energy has no machine to pick. Over Classic the Thor dials a computer it has
                // paired with, so the list above is the question; over Low Energy the computer
                // comes to the pad, and whoever bonds is the answer. Which is why this cannot live
                // behind a chosen machine the way it first did — there is nothing to choose until
                // after the pairing, and the pairing needs this set first.
                card.addView(label("Or wait to be found", 14f, grey).apply { setPadding(0, 22, 0, 4) })
                card.addView(btn((if (state.remote) "\u25CF  " else "") + "A machine", !state.remote) {
                    actions.useLowEnergy()
                })
                card.addView(label(
                    "No machine to pick: the computer finds the pad and connects to it, and whichever " +
                    "one does is the answer.",
                    12f, grey).apply { setPadding(0, 6, 0, 0) })
                if (state.remote) card.addView(btn("What the machine sees\u2026") { page = Page.APPEARANCE; render() })
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

        // The card is only as tall as what is in it, and the panel is now the whole screen, so a
        // short page left the rest of the scroller transparent with the scrim showing through —
        // and the scrim closes the panel when touched. A strip of "outside" in the middle of the
        // panel, right where a thumb rests. Filling the viewport makes the card reach the bottom
        // however little it holds.
        val scroll = PanelScrollView(themed).apply { isFillViewport = true; addView(card) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx, Gravity.TOP))
        return Handle(root, scrim, scroll) { s -> state = s; render() }
    }
}
