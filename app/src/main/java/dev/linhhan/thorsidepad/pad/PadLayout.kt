package dev.linhhan.thorsidepad.pad

import dev.linhhan.thorsidepad.inject.Btn
import dev.linhhan.thorsidepad.inject.Action
import dev.linhhan.thorsidepad.inject.Stick
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

        fun default(): PadLayout = leftHand()

        /**
         * Left hand on the Thor (left stick, d-pad, L1/L2 physical). Everything the right hand
         * would do sits on the LEFT half of the bottom screen, under the same left thumb.
         */
        fun leftHand(): PadLayout = PadLayout(mutableListOf(
            PadButton(Btn.TR, 0.10f, 0.10f, 0.12f),
            PadButton(Btn.TR2, 0.26f, 0.10f, 0.12f),
            PadButton(Btn.START, 0.42f, 0.10f, 0.11f),
            PadButton(Btn.Y, 0.22f, 0.28f, 0.16f),
            PadButton(Btn.X, 0.10f, 0.42f, 0.16f),
            PadButton(Btn.B, 0.34f, 0.42f, 0.16f),
            PadButton(Btn.A, 0.22f, 0.56f, 0.16f),
            PadButton(Stick.RIGHT, 0.22f, 0.80f, 0.28f),
            PadButton(Btn.C, 0.45f, 0.62f, 0.11f),
            PadButton(Btn.Z, 0.45f, 0.82f, 0.11f),
            PadButton(Action.SHIELD, 0.46f, 0.40f, 0.10f),
        ))

        /** Mirror image: right hand on the Thor, the left-side controls on the RIGHT half of the screen. */
        fun rightHand(): PadLayout = PadLayout(mutableListOf(
            PadButton(Btn.TL, 0.90f, 0.10f, 0.12f),
            PadButton(Btn.TL2, 0.74f, 0.10f, 0.12f),
            PadButton(Btn.SELECT, 0.58f, 0.10f, 0.11f),
            PadButton(Btn.DPAD_UP, 0.78f, 0.28f, 0.15f),
            PadButton(Btn.DPAD_LEFT, 0.66f, 0.42f, 0.15f),
            PadButton(Btn.DPAD_RIGHT, 0.90f, 0.42f, 0.15f),
            PadButton(Btn.DPAD_DOWN, 0.78f, 0.56f, 0.15f),
            PadButton(Stick.LEFT, 0.78f, 0.80f, 0.28f),
            PadButton(Btn.C, 0.55f, 0.62f, 0.11f),
            PadButton(Btn.Z, 0.55f, 0.82f, 0.11f),
            PadButton(Action.SHIELD, 0.54f, 0.40f, 0.10f),
        ))

        /** The original set: a face diamond on the left, shoulders in the corners, extras and Start/Select on the right. */
        fun faceButtons(): PadLayout = PadLayout(mutableListOf(
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
            PadButton(Action.SHIELD, 0.50f, 0.12f, 0.10f),
        ))
    }
}
