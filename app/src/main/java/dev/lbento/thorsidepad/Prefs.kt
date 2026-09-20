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

    /** -1 = first non-default display. */
    var displayId: Int
        get() = sp.getInt("displayId", -1)
        set(v) = sp.edit().putInt("displayId", v).apply()

    var opacity: Float
        get() = sp.getFloat("opacity", 0.75f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    /** Shield: one full-screen window, nothing behind it is touchable. Off = one window per button. Off at every start. */
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
