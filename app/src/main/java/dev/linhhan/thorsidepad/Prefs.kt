package dev.linhhan.thorsidepad

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sidepad", Context.MODE_PRIVATE)

    /** "physical" writes into the Thor's own controller node; "virtual" creates a separate uinput pad. */
    var targetMode: String
        get() = sp.getString("targetMode", MODE_PHYSICAL) ?: MODE_PHYSICAL
        set(v) = sp.edit().putString("targetMode", v).apply()

    /** Last resolved node of the chosen controller; re-resolved from [physicalName] whenever the pad shows. */
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

    /** Shield: one full-screen window, nothing behind it is touchable. Off = one window per button. */
    var shield: Boolean
        get() = sp.getBoolean("shield", true)
        set(v) = sp.edit().putBoolean("shield", v).apply()

    /** Name of the preset the pad's layout came from. Save in the editor writes back into it. */
    var activePreset: String
        get() = sp.getString("activePreset", "Left hand") ?: "Left hand"
        set(v) = sp.edit().putString("activePreset", v).apply()

    /** What the shield paints behind the buttons: "clear", "dim", "dark" or "frosted" (blur, where supported). */
    var backdrop: String
        get() = sp.getString("backdrop", BACKDROP_CLEAR) ?: BACKDROP_CLEAR
        set(v) = sp.edit().putString("backdrop", v).apply()

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
    }
}
