package dev.linhhan.thorsidepad.pad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Preset(val name: String, val description: String, val layout: PadLayout, val builtin: Boolean)

/** Built-in presets plus the user's own, stored as JSON in the app prefs. */
object PresetStore {
    private const val KEY = "presets"

    val builtins: List<Preset> = listOf(
        Preset("Left hand", "You hold the left side. A/B/X/Y, right stick, R1/R2, Start, M1/M2 sit on the left half.", PadLayout.leftHand(), true),
        Preset("Right hand", "You hold the right side. D-pad, left stick, L1/L2, Select, M1/M2 sit on the right half.", PadLayout.rightHand(), true),
        Preset("Face buttons", "A/B/X/Y on the left, shoulders in the corners, M1/M2 and Select/Start on the right.", PadLayout.faceButtons(), true),
    )

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("sidepad", Context.MODE_PRIVATE)

    fun customs(ctx: Context): MutableList<Preset> {
        val out = ArrayList<Preset>()
        try {
            val arr = JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Preset(o.getString("name"), "Your preset", PadLayout.fromJson(o.getString("layout")), false))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveCustoms(ctx: Context, list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("name", p.name).put("layout", p.layout.toJson())) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun all(ctx: Context): List<Preset> = builtins + customs(ctx)

    fun nextName(ctx: Context): String {
        val names = customs(ctx).map { it.name }.toSet()
        var n = 1
        while ("My preset $n" in names) n++
        return "My preset $n"
    }
}
