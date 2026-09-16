package dev.linhhan.thorsidepad

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sidepad", Context.MODE_PRIVATE)

    /** "physical" writes into the Thor's own controller node; "virtual" creates a separate uinput pad. */
    var targetMode: String
        get() = sp.getString("targetMode", MODE_PHYSICAL) ?: MODE_PHYSICAL
        set(v) = sp.edit().putString("targetMode", v).apply()

    var physicalPath: String
        get() = sp.getString("physicalPath", "") ?: ""
        set(v) = sp.edit().putString("physicalPath", v).apply()

    /** -1 = first non-default display. */
    var displayId: Int
        get() = sp.getInt("displayId", -1)
        set(v) = sp.edit().putInt("displayId", v).apply()

    var opacity: Float
        get() = sp.getFloat("opacity", 0.75f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    var chordEnabled: Boolean
        get() = sp.getBoolean("chordEnabled", true)
        set(v) = sp.edit().putBoolean("chordEnabled", v).apply()

    /** Shield: one full-screen window, nothing behind it is touchable. Off = one window per button. */
    var shield: Boolean
        get() = sp.getBoolean("shield", true)
        set(v) = sp.edit().putBoolean("shield", v).apply()

    /** Edge swipes on the shield: pull up = Home, left edge = Back. (Pull down always opens our panel.) */
    var gestures: Boolean
        get() = sp.getBoolean("gestures", true)
        set(v) = sp.edit().putBoolean("gestures", v).apply()

    /** Pull-up goes Home on the top (main) screen; off = press the Thor's Home key, which follows its own focus rules. */
    var pullUpTop: Boolean
        get() = sp.getBoolean("pullUpTop", true)
        set(v) = sp.edit().putBoolean("pullUpTop", v).apply()

    var startAtBoot: Boolean
        get() = sp.getBoolean("startAtBoot", true)
        set(v) = sp.edit().putBoolean("startAtBoot", v).apply()

    var layoutJson: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    companion object {
        const val MODE_PHYSICAL = "physical"
        const val MODE_VIRTUAL = "virtual"
    }
}
