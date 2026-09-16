package dev.linhhan.thorsidepad.pad

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.linhhan.thorsidepad.inject.Abs
import dev.linhhan.thorsidepad.inject.Btn
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.Ev
import dev.linhhan.thorsidepad.inject.IInjector
import org.json.JSONObject

/** What the open target can emit, parsed from IInjector.targetCaps(). */
class Caps(val keys: Set<Int>, val abs: Map<Int, IntArray>, val virtual: Boolean) {
    companion object {
        fun fromJson(json: String): Caps {
            val o = JSONObject(json)
            val ka = o.getJSONArray("keys")
            val keys = HashSet<Int>(); for (i in 0 until ka.length()) keys.add(ka.getInt(i))
            val ao = o.getJSONObject("abs")
            val abs = HashMap<Int, IntArray>()
            ao.keys().forEach { k -> val r = ao.getJSONArray(k); abs[k.toInt()] = intArrayOf(r.getInt(0), r.getInt(1)) }
            return Caps(keys, abs, o.optBoolean("virtual", false))
        }
    }
}

/** One evdev event to send on press (value `down`) and on release (value `up`). */
data class Emit(val type: Int, val code: Int, val down: Int, val up: Int)

/**
 * Turns a catalogue code into the concrete events the current target understands. Returns an
 * empty list when the target cannot express that button (the pad then shows it disabled).
 */
fun planFor(code: Int, caps: Caps): List<Emit> {
    val out = ArrayList<Emit>()
    val pc = Catalog.byCode(code)
    if (code in caps.keys) out += Emit(Ev.KEY, code, 1, 0)

    if (pc.isDpad && out.isEmpty() && caps.abs.containsKey(Abs.HAT0X) && caps.abs.containsKey(Abs.HAT0Y)) {
        out += when (code) {
            Btn.DPAD_UP -> Emit(Ev.ABS, Abs.HAT0Y, -1, 0)
            Btn.DPAD_DOWN -> Emit(Ev.ABS, Abs.HAT0Y, 1, 0)
            Btn.DPAD_LEFT -> Emit(Ev.ABS, Abs.HAT0X, -1, 0)
            else -> Emit(Ev.ABS, Abs.HAT0X, 1, 0)
        }
    }

    if (pc.isTrigger) {
        // Real pads report a trigger as a key AND an axis; send whichever the target has.
        val candidates = if (code == Btn.TL2) listOf(Abs.BRAKE, Abs.Z) else listOf(Abs.GAS, Abs.RZ)
        val axis = candidates.firstOrNull { caps.abs.containsKey(it) }
        if (axis != null) {
            val r = caps.abs.getValue(axis)
            out += Emit(Ev.ABS, axis, r[1], r[0])
        }
    }
    return out
}

/** Sends press/release plans to the injector off the UI thread, in order. */
class PadEngine(private val injector: IInjector, val caps: Caps) {
    private val thread = HandlerThread("sidepad-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val plans = HashMap<Int, List<Emit>>()

    fun plan(code: Int): List<Emit> = plans.getOrPut(code) { planFor(code, caps) }

    fun press(code: Int) = send(plan(code), true)
    fun release(code: Int) = send(plan(code), false)

    private fun send(plan: List<Emit>, down: Boolean) {
        if (plan.isEmpty()) return
        handler.post {
            try {
                for (e in plan) {
                    val v = if (down) e.down else e.up
                    if (e.type == Ev.KEY) injector.key(e.code, v != 0) else injector.abs(e.code, v)
                }
            } catch (ex: Exception) { Log.w("SidePadEngine", "inject failed", ex) }
        }
    }

    fun shutdown() { thread.quitSafely() }
}
