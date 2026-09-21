package dev.lbento.thorsidepad.pad

import dev.lbento.thorsidepad.inject.Abs
import dev.lbento.thorsidepad.inject.Btn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which physical control on this handheld is which, and which input device it lives on.
 *
 * Two assumptions were built in from the start and both are wrong on some hardware. The first is
 * that a handheld's controls all sit on one input device: the Odin 2 Mini delivers Start and
 * Select from a different node than its sticks and face buttons, and a reader that opens one
 * device loses them silently — indistinguishable, from outside, from the buttons not existing at
 * all. The second is that the Linux code for a control means the same thing everywhere; it
 * mostly does, and when it does not there is nothing to notice it by.
 *
 * So the player can teach it instead. A calibration is a plain list of controls, each naming the
 * device it came from and the event it produced, and anything not calibrated falls back to the
 * standard code, which is right on most hardware including the Thor.
 *
 * Stored as JSON because it has to survive a reinstall and be legible when something is wrong:
 *
 * ```json
 * {"v":1,
 *  "keys":[{"c":314,"path":"/dev/input/event5","code":314}],
 *  "axes":[{"c":0,"path":"/dev/input/event3","code":0,"flip":false}]}
 * ```
 *
 * `c` is what the pad should report — our canonical code, the same constants in [Btn] and [Abs].
 * `path` and `code` are what the hardware actually produced.
 */
class PadCalibration private constructor(
    val keys: List<KeyEntry>,
    val axes: List<AxisEntry>,
) {
    data class KeyEntry(val canonical: Int, val path: String, val code: Int)
    data class AxisEntry(val canonical: Int, val path: String, val code: Int, val flip: Boolean)

    /** Every device this calibration needs open, in a stable order. */
    val nodePaths: List<String> =
        (keys.map { it.path } + axes.map { it.path }).distinct().sorted()

    private val keyLookup: Map<Long, Int> =
        keys.associate { pack(nodePaths.indexOf(it.path), it.code) to it.canonical }
    private val axisLookup: Map<Long, AxisEntry> =
        axes.associateBy { pack(nodePaths.indexOf(it.path), it.code) }

    val isEmpty get() = keys.isEmpty() && axes.isEmpty()

    /** What this key press should be reported as, or null when this device says nothing about it. */
    fun key(node: Int, code: Int): Int? = keyLookup[pack(node, code)]

    /** The same for an axis, with whether its direction needs reversing. */
    fun axis(node: Int, code: Int): AxisEntry? = axisLookup[pack(node, code)]

    fun toJson(): String {
        val k = JSONArray()
        for (e in keys) k.put(JSONObject().put("c", e.canonical).put("path", e.path).put("code", e.code))
        val a = JSONArray()
        for (e in axes) a.put(JSONObject().put("c", e.canonical).put("path", e.path)
            .put("code", e.code).put("flip", e.flip))
        return JSONObject().put("v", 1).put("keys", k).put("axes", a).toString()
    }

    /** A readable summary for the panel, so a player can see what was learned without a log. */
    fun describe(): String {
        if (isEmpty) return "Nothing calibrated; using the standard codes."
        val devices = nodePaths.size
        return "${keys.size} button(s) and ${axes.size} axis/axes across " +
            (if (devices == 1) "one device" else "$devices devices")
    }

    companion object {
        private fun pack(node: Int, code: Int): Long = (node.toLong() shl 32) or (code.toLong() and 0xffffffffL)

        val EMPTY = PadCalibration(emptyList(), emptyList())

        fun parse(json: String?): PadCalibration {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val o = JSONObject(json)
                val keys = mutableListOf<KeyEntry>()
                val ka = o.optJSONArray("keys") ?: JSONArray()
                for (i in 0 until ka.length()) {
                    val e = ka.getJSONObject(i)
                    keys.add(KeyEntry(e.getInt("c"), e.getString("path"), e.getInt("code")))
                }
                val axes = mutableListOf<AxisEntry>()
                val aa = o.optJSONArray("axes") ?: JSONArray()
                for (i in 0 until aa.length()) {
                    val e = aa.getJSONObject(i)
                    axes.add(AxisEntry(e.getInt("c"), e.getString("path"), e.getInt("code"), e.optBoolean("flip", false)))
                }
                PadCalibration(keys, axes)
            } catch (_: Exception) {
                // A calibration that will not parse is worse than none: it would silently drop
                // controls. Falling back to the standard codes at least leaves a working pad.
                EMPTY
            }
        }

        fun of(keys: List<KeyEntry>, axes: List<AxisEntry>) = PadCalibration(keys, axes)

        /**
         * The controls a player is asked for, in order, with the code each should report.
         *
         * Face buttons first: they are what makes a pad usable at all, so an abandoned run still
         * leaves something better than nothing. Sticks are asked for as directions rather than
         * axes because "push right" is a thing a person can do and "ABS_X positive" is not.
         */
        val STEPS: List<Step> = listOf(
            Step("A", Btn.A), Step("B", Btn.B), Step("X", Btn.X), Step("Y", Btn.Y),
            Step("L1", Btn.TL), Step("R1", Btn.TR),
            Step("L2", Btn.TL2), Step("R2", Btn.TR2),
            Step("Select / View", Btn.SELECT), Step("Start / Menu", Btn.START),
            Step("Home / Guide", Btn.MODE),
            Step("L3 (press the left stick in)", Btn.THUMBL),
            Step("R3 (press the right stick in)", Btn.THUMBR),
            Step("D-pad up", Btn.DPAD_UP), Step("D-pad down", Btn.DPAD_DOWN),
            Step("D-pad left", Btn.DPAD_LEFT), Step("D-pad right", Btn.DPAD_RIGHT),
            Step("M1", Btn.C), Step("M2", Btn.Z),
            Step("Left stick: push RIGHT", Abs.X, axis = true),
            Step("Left stick: push DOWN", Abs.Y, axis = true),
            Step("Right stick: push RIGHT", Abs.RX, axis = true),
            Step("Right stick: push DOWN", Abs.RY, axis = true),
        )

        data class Step(val label: String, val canonical: Int, val axis: Boolean = false)
    }
}
