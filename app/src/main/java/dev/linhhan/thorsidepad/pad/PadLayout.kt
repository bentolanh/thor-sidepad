package dev.linhhan.thorsidepad.pad

import dev.linhhan.thorsidepad.inject.Btn
import org.json.JSONArray
import org.json.JSONObject

/**
 * One on-screen button. Position and size are fractions of the display so a layout survives
 * rotation and other screens: cx/cy of width/height, size (diameter) of the shorter side.
 */
data class PadButton(var code: Int, var cx: Float, var cy: Float, var size: Float)

class PadLayout(val buttons: MutableList<PadButton>) {

    fun toJson(): String {
        val arr = JSONArray()
        buttons.forEach { b ->
            arr.put(JSONObject().put("code", b.code).put("cx", b.cx.toDouble()).put("cy", b.cy.toDouble()).put("size", b.size.toDouble()))
        }
        return JSONObject().put("version", 1).put("buttons", arr).toString()
    }

    fun copy(): PadLayout = PadLayout(buttons.map { it.copy() }.toMutableList())

    companion object {
        fun fromJson(json: String?): PadLayout {
            if (json.isNullOrBlank()) return default()
            return try {
                val arr = JSONObject(json).getJSONArray("buttons")
                val list = ArrayList<PadButton>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(PadButton(o.getInt("code"), o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(), o.getDouble("size").toFloat()))
                }
                PadLayout(list)
            } catch (e: Exception) { default() }
        }

        /**
         * One-handed default: the face diamond sits on the left half, just below where the left
         * thumb rests on the stick, so the thumb drops down to it. Shoulders in the top corners,
         * the two extra buttons (M1/M2) on the right where a second hand could reach them.
         */
        fun default(): PadLayout = PadLayout(mutableListOf(
            PadButton(Btn.Y, 0.30f, 0.30f, 0.17f),
            PadButton(Btn.X, 0.17f, 0.44f, 0.17f),
            PadButton(Btn.B, 0.43f, 0.44f, 0.17f),
            PadButton(Btn.A, 0.30f, 0.58f, 0.17f),
            PadButton(Btn.TL, 0.14f, 0.10f, 0.14f),
            PadButton(Btn.TR, 0.86f, 0.10f, 0.14f),
            PadButton(Btn.C, 0.70f, 0.40f, 0.15f),
            PadButton(Btn.Z, 0.86f, 0.40f, 0.15f),
            PadButton(Btn.SELECT, 0.62f, 0.82f, 0.12f),
            PadButton(Btn.START, 0.82f, 0.82f, 0.12f),
        ))
    }
}
