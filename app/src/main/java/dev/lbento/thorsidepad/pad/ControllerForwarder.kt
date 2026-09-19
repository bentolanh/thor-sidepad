package dev.lbento.thorsidepad.pad

import android.util.Log
import dev.lbento.thorsidepad.inject.Ev
import dev.lbento.thorsidepad.inject.IPadEvents
import org.json.JSONObject

/**
 * Sends the Thor's own controller to wherever the pad is pointed.
 *
 * The on-screen pad was only ever half the point: the handheld has real sticks and buttons, and
 * when it is acting as a controller for another machine those are what a player will reach for.
 * This reads them from the kernel through the injector and feeds the same [BluetoothSink] the
 * virtual buttons use, so both work at once.
 *
 * Axis values have to be rescaled. A real stick reports whatever range its driver declares, often
 * tens of thousands wide, while the gamepad we advertise is a single signed byte. The ranges are
 * read from the device itself rather than assumed.
 */
class ControllerForwarder(private val sink: BluetoothSink) : IPadEvents.Stub() {

    private var scalers: Map<Int, (Int) -> Int> = emptyMap()

    /** Builds the per-axis scaling from the device's own declared ranges. */
    fun configure(capsJson: String) {
        val out = HashMap<Int, (Int) -> Int>()
        try {
            val abs = JSONObject(capsJson).optJSONObject("abs") ?: JSONObject()
            abs.keys().forEach { k ->
                val code = k.toIntOrNull() ?: return@forEach
                val r = abs.getJSONArray(k)
                val lo = r.getInt(0); val hi = r.getInt(1)
                val target = BluetoothSink.CAPS.abs[code] ?: return@forEach
                val tl = target[0]; val th = target[1]
                out[code] = if (hi <= lo) { _ -> tl } else { v ->
                    val clamped = v.coerceIn(lo, hi)
                    tl + ((clamped - lo).toLong() * (th - tl) / (hi - lo)).toInt()
                }
            }
        } catch (e: Exception) { Log.w(TAG, "caps", e) }
        scalers = out
        Log.i(TAG, "forwarding ${out.size} axes")
    }

    private var dropped = 0

    override fun onEvent(type: Int, code: Int, value: Int) {
        when (type) {
            Ev.KEY -> sink.setKey(code, value != 0)
            Ev.ABS -> {
                // No axis is turned over here. An earlier version flipped the two vertical ones,
                // on a reading that turned out to be of the wrong byte; the Thor already reports
                // up as negative, which is what a host wants, and flipping it sent up as down.
                // Measured on 2026-09-19 by holding both sticks at the top and reading the bytes
                // as they arrived on the other machine: with the flip in place they read +127.
                sink.setAbs(code, scalers[code]?.invoke(value) ?: value)
            }
            // A controller ends every batch with a sync, which is the moment to send one report
            // rather than one per axis: a stick moving would otherwise be two reports per frame.
            Ev.SYN -> {
                // SYN_DROPPED. The kernel says its buffer for us overflowed and it threw events
                // away to catch up. Anything lost there was lost before we ever saw it.
                if (code == SYN_DROPPED) Log.w(TAG, "kernel dropped events, ${++dropped} so far")
                else sink.sync()
            }
        }
    }

    private companion object {
        const val TAG = "SidePadForward"
        const val SYN_DROPPED = 3
    }
}
