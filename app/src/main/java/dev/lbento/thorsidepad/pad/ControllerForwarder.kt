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
 * Axis values have to be rescaled. A real stick reports whatever range its driver declares, and
 * the gamepad we advertise has a range of its own — sixteen bits under the Xbox identity, a signed
 * byte under the standard one. Both ends are read rather than assumed: the source from the device,
 * the destination from the transport. Assuming the destination was a byte was what made a stick
 * with 65,535 positions arrive with 255 of them.
 */
class ControllerForwarder(
    private val sink: PadTransport,
    /** Where a button the pad has no room for goes instead. See [onEvent]. */
    private val passThrough: (Int, Boolean) -> Unit = { _, _ -> },
    /**
     * What the player taught us about this handheld, or [PadCalibration.EMPTY] to take every
     * device at its word. Empty is right on most hardware, the Thor included; it is wrong where
     * a control sits on an unexpected code or an unexpected device.
     */
    private val calibration: PadCalibration = PadCalibration.EMPTY,
    /**
     * Called now and then while real presses are arriving, so Android can be told somebody is
     * still playing. Not called per event: once a minute is enough to hold off a screen timeout
     * measured in tens of minutes, and it keeps a binder call off the event path.
     */
    private val stillPlaying: () -> Unit = {},
) : IPadEvents.Stub() {

    private var lastReported = 0L

    /**
     * Axis scaling, keyed by the device it came from as well as the code.
     *
     * Keying by code alone was fine while one device was read. It is not once several are: two
     * handhelds' nodes can both report ABS_X over completely different ranges, and one would
     * silently scale the other's stick.
     */
    private var scalers: Map<Long, (Int) -> Int> = emptyMap()

    private fun scalerKey(node: Int, code: Int): Long =
        (node.toLong() shl 32) or (code.toLong() and 0xffffffffL)

    /** Builds the per-axis scaling from one device's declared ranges. */
    fun configure(capsJson: String) = configureNode(-1, capsJson, emptyMap())

    /**
     * The multi-device form: `{"nodes":[{"path","index","abs":{..}},..]}` as forwardStartMulti
     * returns it. Each node's axes are scaled by its own ranges, and named by what the
     * calibration says they are rather than by the code they arrive under.
     */
    fun configureMulti(nodesJson: String, calib: PadCalibration) {
        val out = HashMap<Long, (Int) -> Int>()
        try {
            val nodes = JSONObject(nodesJson).optJSONArray("nodes") ?: return
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                val idx = n.optInt("index", i)
                val abs = n.optJSONObject("abs") ?: continue
                out.putAll(buildScalers(idx, abs) { code -> calib.axis(idx, code)?.canonical ?: code })
            }
        } catch (e: Exception) { Log.w(TAG, "caps", e) }
        scalers = out
        Log.i(TAG, "forwarding ${out.size} axes across devices")
    }

    private fun configureNode(node: Int, capsJson: String, extra: Map<Long, (Int) -> Int>) {
        val out = HashMap<Long, (Int) -> Int>(extra)
        try {
            val abs = JSONObject(capsJson).optJSONObject("abs") ?: JSONObject()
            out.putAll(buildScalers(node, abs) { it })
        } catch (e: Exception) { Log.w(TAG, "caps", e) }
        scalers = out
        Log.i(TAG, "forwarding ${out.size} axes")
    }

    /**
     * One device's axes, scaled into the shape the pad reports.
     *
     * [canonicalOf] says what an incoming code will have become by the time the scaler is looked
     * up, because the target range depends on what the axis IS, not what the device calls it.
     */
    private fun buildScalers(node: Int, abs: JSONObject, canonicalOf: (Int) -> Int): Map<Long, (Int) -> Int> {
        val out = HashMap<Long, (Int) -> Int>()
        abs.keys().forEach { k ->
            val code = k.toIntOrNull() ?: return@forEach
            val r = abs.getJSONArray(k)
            val lo = r.getInt(0); val hi = r.getInt(1)
            val target = sink.caps.abs[canonicalOf(code)] ?: return@forEach
            val tl = target[0]; val th = target[1]
            out[scalerKey(node, code)] = if (hi <= lo) { _ -> tl } else { v ->
                val clamped = v.coerceIn(lo, hi)
                tl + ((clamped - lo).toLong() * (th - tl) / (hi - lo)).toInt()
            }
        }
        return out
    }

    private var dropped = 0

    override fun onEvent(type: Int, code: Int, value: Int) = onEventAt(-1, type, code, value)

    /**
     * [node] says which of the opened devices this came from, or -1 for the single-device path.
     *
     * Only the calibration cares. Without one, the same event means the same thing wherever it
     * arrives from, which is the behaviour this had before several devices could be read at once.
     */
    override fun onEventAt(node: Int, type: Int, code: Int, value: Int) {
        // Sticks report constantly, including while resting, so only a button counts as somebody
        // being there. Throttled to once a minute; the timeout it is holding off is thirty.
        if (type == Ev.KEY && value != 0) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastReported > 60_000L) { lastReported = now; stillPlaying() }
        }
        var code = code
        var flip = false
        val rawCode = code
        if (!calibration.isEmpty && node >= 0) {
            when (type) {
                Ev.KEY -> {
                    val mapped = calibration.key(node, code)
                    // A calibrated handheld sends plenty this pad was never taught about — a
                    // volume key, a lid switch. Those go back to Android exactly as an unmapped
                    // button always has, rather than being reported as whatever code they carry.
                    if (mapped == null) { passThrough(code, value != 0); return }
                    code = mapped
                }
                Ev.ABS -> {
                    val a = calibration.axis(node, code) ?: return
                    code = a.canonical
                    flip = a.flip
                }
            }
        }
        when (type) {
            // Taking the controller takes all of it, so a button with no slot in the report is
            // not merely unsent — it is destroyed, and the handheld stops seeing it too. That is
            // how the Thor's volume keys went dead whenever the pad was running, and Home and
            // Back with them. Anything the pad cannot carry goes back to Android instead.
            Ev.KEY -> if (sink.handles(code)) sink.setKey(code, value != 0)
                      else passThrough(code, value != 0)
            Ev.ABS -> {
                // No axis is turned over here. An earlier version flipped the two vertical ones,
                // on a reading that turned out to be of the wrong byte; the Thor already reports
                // up as negative, which is what a host wants, and flipping it sent up as down.
                // Measured on 2026-09-19 by holding both sticks at the top and reading the bytes
                // as they arrived on the other machine: with the flip in place they read +127.
                // Scaled by the range the device that sent it declared, then turned over if
                // the player's calibration said this stick reads backwards. Reversing the raw
                // value instead would be wrong wherever a stick's range is not symmetric.
                val scaled = scalers[scalerKey(node, rawCode)]?.invoke(value) ?: value
                sink.setAbs(code, if (flip) -scaled else scaled)
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
