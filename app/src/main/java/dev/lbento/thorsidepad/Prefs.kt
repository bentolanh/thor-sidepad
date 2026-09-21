package dev.lbento.thorsidepad

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sidepad", Context.MODE_PRIVATE)

    /** "physical" writes into the Thor's own controller node; "virtual" creates a separate uinput pad. */
    var targetMode: String
        get() = sp.getString("targetMode", MODE_PHYSICAL) ?: MODE_PHYSICAL
        set(v) = sp.edit().putString("targetMode", v).apply()

    /** Last resolved node of the chosen controller; re-resolved from [physicalName] whenever the pad shows. */
    /**
     * Machines paired from inside SidePad. The device's own list is full of headphones and other
     * controllers, and none of those are somewhere to send presses, so only what was paired here is
     * offered.
     */
    var pairedHostList: String
        get() = sp.getString("pairedHosts", "") ?: ""
        set(v) = sp.edit().putString("pairedHosts", v).apply()

    fun rememberPairedHost(address: String) {
        val all = pairedHostList.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (all.add(address)) pairedHostList = all.joinToString(",")
    }

    /** A machine unpaired anywhere is no longer somewhere to send presses, wherever that happened. */
    fun forgetPairedHost(address: String) {
        val all = pairedHostList.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (all.remove(address)) pairedHostList = all.joinToString(",")
    }

    /**
     * Which radio carries presses to a machine, when the destination is one.
     *
     * Low Energy is the only one offered now. Classic works and the code for it is kept, but it
     * has nothing to offer that this does not: over Classic the vendor and product numbers a host
     * reads belong to the handheld's Bluetooth chip and cannot be changed, and those numbers are
     * the whole of how a host decides what a pad is. Over Low Energy they are ours, which is what
     * makes one mapping serve every handheld this runs on rather than one per radio, and what got
     * a native game to accept the pad at all.
     *
     * Kept as a setting rather than deleted because one thing is genuinely untested: a Windows
     * machine reached through CrossOver was working over Classic and has never been tried over
     * Low Energy. If that turns out to need Classic, this is how it comes back.
     */
    var btTransport: String
        get() = sp.getString("btTransport", TRANSPORT_LE) ?: TRANSPORT_LE
        set(v) = sp.edit().putString("btTransport", v).apply()

    /** What a host is told the pad is, when it is carried over Low Energy. See BleSink.Identity. */
    var btIdentity: String
        get() = sp.getString("btIdentity", "OWN") ?: "OWN"
        set(v) = sp.edit().putString("btIdentity", v).apply()

    /** The paired machine presses are sent to, when the destination is another machine. */
    var btHost: String
        get() = sp.getString("btHost", "") ?: ""
        set(v) = sp.edit().putString("btHost", v).apply()

    var physicalPath: String
        get() = sp.getString("physicalPath", "") ?: ""
        set(v) = sp.edit().putString("physicalPath", v).apply()

    /** The chosen controller by name, because event node numbers change when a device re-enumerates. */
    var physicalName: String
        get() = sp.getString("physicalName", "") ?: ""
        set(v) = sp.edit().putString("physicalName", v).apply()

    /**
     * A floating button on the pad's own screen that shows and hides it.
     *
     * The Thor does not need one: the pad has a screen to itself, and an edge swipe there means
     * nothing else. A handheld with a single screen has no such spare edge — every gesture
     * belongs to Android — so without this there is no way to reach the pad at all.
     */
    var bubble: Boolean
        get() = sp.getBoolean("bubble", false)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", 0)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", 0)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    /** -1 = first non-default display. */
    var displayId: Int
        get() = sp.getInt("displayId", -1)
        set(v) = sp.edit().putInt("displayId", v).apply()

    var opacity: Float
        get() = sp.getFloat("opacity", 0.75f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    /** Shield: one full-screen window, nothing behind it is touchable. Off = one window per button. Off at every start. */
    /**
     * Whether the handheld stays awake while a machine is holding the pad.
     *
     * Off, the Thor sleeps as it normally would and stops reading its own controller, so presses
     * stop arriving while the link still looks fine. On, it plays with both screens dark and
     * spends the battery to do it. Neither is obviously right, so it is asked rather than chosen.
     */
    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", false)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    /**
     * Whether the pad tells a host it has motors in it.
     *
     * Saying so is a force-feedback collection at the end of the report map, and leaving it out
     * is how a host that behaves badly when offered motors can be told there are none.
     *
     * It was added expecting it to answer something else, and it did not. macOS runs a haptics
     * run loop for as long as this pad is connected — 1.02 seconds of processor a minute with
     * the pad untouched, against nothing at all for an 8BitDo on the same machine — and taking
     * the collection out changed that figure not at all: 1.07 with the map trimmed to 226 bytes
     * and the host confirmed to be holding the trimmed one. Whatever starts that loop, it is not
     * this. Measured 2026-09-20.
     *
     * Changing this changes the shape of what a host was promised, so a host that already knows
     * the pad has to be told to look again before it takes effect.
     */
    var rumble: Boolean
        get() = sp.getBoolean("rumble", true)
        set(v) = sp.edit().putBoolean("rumble", v).apply()

    /**
     * Which button order the Xbox identity sends.
     *
     * Off — the default — is the real controller's own order, which leaves buttons three and six
     * empty and is what any host that recognises the identity expects. On is the counted order,
     * for a game that numbers buttons straight through instead: without it such a game reads
     * everything after B one or two places late, which in Eastward on 2026-09-20 put R1 on
     * Start, Y on a shoulder and X on nothing at all, with Steam Input on and off alike.
     *
     * There is no way to satisfy both, and nothing in what the pad sends says which a game will
     * do, so it is the player's to pick. Changing it needs the machine to reconnect.
     */
    var plainButtons: Boolean
        get() = sp.getBoolean("plainButtons", false)
        set(v) = sp.edit().putBoolean("plainButtons", v).apply()

    /**
     * What the player taught us about this handheld's own controls, as JSON. See PadCalibration.
     *
     * Empty means take every device at its word, which is right on the Thor and on most
     * hardware. It is wrong where a control sits on an unexpected code, or on a different input
     * device than the rest — the Odin 2 Mini puts Start and Select on a node of their own, and
     * a pad that reads one device loses them without any sign that it has.
     */
    var calibration: String
        get() = sp.getString("calibration", "") ?: ""
        set(v) = sp.edit().putString("calibration", v).apply()

    var shield: Boolean
        get() = sp.getBoolean("shield", false)
        set(v) = sp.edit().putBoolean("shield", v).apply()

    /** Name of the preset the pad's layout came from. Save in the editor writes back into it. */
    var activePreset: String
        get() = sp.getString("activePreset", "Left hand") ?: "Left hand"
        set(v) = sp.edit().putString("activePreset", v).apply()

    /** What the shield paints behind the buttons: "clear", "dim", "dark" or "frosted" (blur, where supported). */
    var backdrop: String
        get() = sp.getString("backdrop", BACKDROP_CLEAR) ?: BACKDROP_CLEAR
        set(v) = sp.edit().putString("backdrop", v).apply()

    /** The look saved while the guide runs ("shield|backdrop|opacity"), so an interrupted guide can still be undone. */
    var guideSnapshot: String?
        get() = sp.getString("guideSnapshot", null)
        set(v) = sp.edit().putString("guideSnapshot", v).apply()

    /** Whether the pad was on screen, so a service revived by the watchdog comes back as it was. */
    var padShown: Boolean
        get() = sp.getBoolean("padShown", false)
        set(v) = sp.edit().putBoolean("padShown", v).apply()

    var guideShown: Boolean
        get() = sp.getBoolean("guideShown", false)
        set(v) = sp.edit().putBoolean("guideShown", v).apply()

    var startAtBoot: Boolean
        get() = sp.getBoolean("startAtBoot", true)
        set(v) = sp.edit().putBoolean("startAtBoot", v).apply()

    var layoutJson: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    companion object {
        const val BACKDROP_CLEAR = "clear"
        const val BACKDROP_DIM = "dim"
        const val BACKDROP_DARK = "dark"
        const val BACKDROP_FROSTED = "frosted"
        const val MODE_PHYSICAL = "physical"
        const val MODE_VIRTUAL = "virtual"
        /** Presses go to another machine over Bluetooth rather than into this device. */
        const val MODE_BT = "bt"
        const val TRANSPORT_CLASSIC = "classic"
        const val TRANSPORT_LE = "le"
    }
}
