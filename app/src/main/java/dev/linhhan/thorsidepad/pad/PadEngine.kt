package dev.linhhan.thorsidepad.pad

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.linhhan.thorsidepad.inject.Abs
import dev.linhhan.thorsidepad.inject.Btn
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.Ev
import dev.linhhan.thorsidepad.inject.IInjector
import dev.linhhan.thorsidepad.inject.Stick
import dev.linhhan.thorsidepad.inject.isActionCode
import dev.linhhan.thorsidepad.inject.isStickCode
import org.json.JSONObject
import kotlin.math.roundToInt

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

/** The two axes a virtual stick drives, with their ranges on the current target. */
data class StickPlan(val ax: Int, val ay: Int, val xr: IntArray, val yr: IntArray)

fun stickPlanFor(code: Int, caps: Caps): StickPlan? {
    val pairs = when (code) {
        Stick.LEFT -> listOf(Abs.X to Abs.Y)
        Stick.RIGHT -> listOf(Abs.Z to Abs.RZ, Abs.RX to Abs.RY)   // Xbox-style first, then generic
        else -> return null
    }
    val (ax, ay) = pairs.firstOrNull { caps.abs.containsKey(it.first) && caps.abs.containsKey(it.second) } ?: return null
    return StickPlan(ax, ay, caps.abs.getValue(ax), caps.abs.getValue(ay))
}

/** Sends press/release plans to the injector off the UI thread, in order. */
class PadEngine(@Volatile private var injector: IInjector, caps: Caps, private val onTargetLost: () -> Unit = {}) {
    private val thread = HandlerThread("sidepad-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val plans = HashMap<Int, List<Emit>>()
    @Volatile private var lost = false
    @Volatile var caps: Caps = caps
        private set

    /** Points the engine at a (re)opened target without touching the views that hold it. */
    fun rebind(newInjector: IInjector, newCaps: Caps) {
        injector = newInjector; caps = newCaps
        synchronized(plans) { plans.clear() }
        synchronized(sticks) { sticks.clear() }
        lost = false
    }

    /** A write into a recreated node fails with -ENODEV; tell the service once so it reopens. */
    private fun check(r: Int) {
        if (r < 0 && !lost) { lost = true; Log.w("SidePadEngine", "target lost (${r})"); onTargetLost() }
    }

    fun plan(code: Int): List<Emit> = synchronized(plans) { plans.getOrPut(code) { planFor(code, caps) } }
    private val sticks = HashMap<Int, StickPlan?>()
    fun stickPlan(code: Int): StickPlan? = synchronized(sticks) { sticks.getOrPut(code) { stickPlanFor(code, caps) } }

    /** Whether the current target can express this element at all. */
    fun enabled(code: Int): Boolean = when {
        isActionCode(code) -> true
        isStickCode(code) -> stickPlan(code) != null
        else -> plan(code).isNotEmpty()
    }

    /** Moves a virtual stick; nx/ny are -1..1, y positive = down like a real pad. */
    fun stick(code: Int, nx: Float, ny: Float) {
        val sp = stickPlan(code) ?: return
        fun map(n: Float, r: IntArray): Int {
            val mid = (r[0] + r[1]) / 2f
            val half = (r[1] - r[0]) / 2f
            return (mid + n.coerceIn(-1f, 1f) * half).roundToInt()
        }
        val x = map(nx, sp.xr); val y = map(ny, sp.yr)
        handler.post {
            try { check(injector.abs(sp.ax, x)); check(injector.abs(sp.ay, y)) } catch (ex: Exception) { Log.w("SidePadEngine", "stick failed", ex) }
        }
    }

    fun press(code: Int) = send(plan(code), true)
    fun release(code: Int) = send(plan(code), false)

    private fun send(plan: List<Emit>, down: Boolean) {
        if (plan.isEmpty()) return
        handler.post {
            try {
                for (e in plan) {
                    val v = if (down) e.down else e.up
                    check(if (e.type == Ev.KEY) injector.key(e.code, v != 0) else injector.abs(e.code, v))
                }
            } catch (ex: Exception) { Log.w("SidePadEngine", "inject failed", ex) }
        }
    }

    fun shutdown() { thread.quitSafely() }
}
