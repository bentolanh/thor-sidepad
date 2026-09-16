package dev.lbento.thorsidepad.pad

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
        // Every built-in carries a SHLD button; delete it in the editor if you do not want it.
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

    fun find(ctx: Context, name: String): Preset? = all(ctx).firstOrNull { it.name == name }
    fun isBuiltin(name: String) = builtins.any { it.name == name }

    /** Writes a custom preset, replacing one with the same name. */
    fun upsert(ctx: Context, preset: Preset) {
        val list = customs(ctx)
        val i = list.indexOfFirst { it.name == preset.name }
        if (i >= 0) list[i] = preset else list.add(preset)
        saveCustoms(ctx, list)
    }

    fun delete(ctx: Context, name: String) = saveCustoms(ctx, customs(ctx).filter { it.name != name })

    /**
     * Everything the user made, as one JSON document: their presets, the pad's current layout
     * and which preset it came from. Meant to be written to a file of the user's choosing so
     * it survives a reinstall or moves to another device.
     */
    fun exportJson(ctx: Context): String {
        val prefs = dev.lbento.thorsidepad.Prefs(ctx)
        val arr = JSONArray()
        customs(ctx).forEach { p -> arr.put(JSONObject().put("name", p.name).put("layout", JSONObject(p.layout.toJson()))) }
        return JSONObject()
            .put("app", "Thor SidePad")
            .put("format", 1)
            .put("activePreset", prefs.activePreset)
            .put("layout", JSONObject(PadLayout.fromJson(prefs.layoutJson).toJson()))
            .put("presets", arr)
            .toString(2)
    }

    /**
     * Reads a document written by [exportJson]. Presets in the file replace ones of the same
     * name and are added otherwise; the file's current layout becomes the pad's layout.
     * Returns how many presets came in, or throws if the document is not ours.
     */
    fun importJson(ctx: Context, json: String): Int {
        val o = JSONObject(json)
        if (o.optString("app") != "Thor SidePad") throw IllegalArgumentException("not a Thor SidePad preset file")
        val list = customs(ctx)
        val arr = o.optJSONArray("presets") ?: JSONArray()
        var n = 0
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.getString("name")
            if (isBuiltin(name)) continue
            val preset = Preset(name, "Your preset", PadLayout.fromJson(p.getJSONObject("layout").toString()), false)
            val at = list.indexOfFirst { it.name == name }
            if (at >= 0) list[at] = preset else list.add(preset)
            n++
        }
        saveCustoms(ctx, list)
        val prefs = dev.lbento.thorsidepad.Prefs(ctx)
        o.optJSONObject("layout")?.let { prefs.layoutJson = PadLayout.fromJson(it.toString()).toJson() }
        o.optString("activePreset").takeIf { it.isNotEmpty() && (isBuiltin(it) || list.any { p -> p.name == it }) }?.let { prefs.activePreset = it }
        return n
    }

    fun nextName(ctx: Context): String {
        val names = customs(ctx).map { it.name }.toSet()
        var n = 1
        while ("My preset $n" in names) n++
        return "My preset $n"
    }
}
