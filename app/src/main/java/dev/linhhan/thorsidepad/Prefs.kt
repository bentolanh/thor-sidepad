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

    var layoutJson: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    companion object {
        const val MODE_PHYSICAL = "physical"
        const val MODE_VIRTUAL = "virtual"
    }
}
