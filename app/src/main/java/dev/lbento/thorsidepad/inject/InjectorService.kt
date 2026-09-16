package dev.lbento.thorsidepad.inject

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in the Shizuku user-service process (shell UID). Owns the open evdev/uinput fd and does
 * every write to it. The app process talks to this over Binder through [IInjector].
 */
class InjectorService() : IInjector.Stub() {

    private var context: Context? = null

    @Suppress("unused")
    constructor(context: Context) : this() { this.context = context }

    // ---- device levels. The shell user holds CONTROL_DISPLAY_BRIGHTNESS, and DisplayManager's
    // per-display setBrightness/getBrightness are hidden but callable from this process.
    private fun displayManager(): Any? = context?.getSystemService(Context.DISPLAY_SERVICE)

    override fun getBrightness(displayId: Int): Float = try {
        val dm = displayManager() ?: return -1f
        val m = dm.javaClass.getMethod("getBrightness", Int::class.javaPrimitiveType)
        (m.invoke(dm, displayId) as Float)
    } catch (e: Throwable) { Log.w(TAG, "getBrightness", e); -1f }

    override fun setBrightness(displayId: Int, level: Float) {
        try {
            val dm = displayManager() ?: return
            val m = dm.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            m.invoke(dm, displayId, level.coerceIn(0f, 1f))
        } catch (e: Throwable) { Log.w(TAG, "setBrightness", e) }
    }

    private fun audio(): android.media.AudioManager? = context?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    override fun getVolume(): Float = try {
        val am = audio() ?: return -1f
        val s = android.media.AudioManager.STREAM_MUSIC
        val min = am.getStreamMinVolume(s); val max = am.getStreamMaxVolume(s)
        (am.getStreamVolume(s) - min).toFloat() / (max - min).coerceAtLeast(1)
    } catch (e: Throwable) { Log.w(TAG, "getVolume", e); -1f }

    /**
     * The Thor's second-screen volume. AYN's own slider writes only the system setting
     * secondary_screen_volume_level (0..15); a running AYN process maps it to the
     * persist.sys.audio.value gain that AudioFlinger applies to apps on the second screen.
     */
    override fun getVolume2nd(): Float = try {
        val out = sh("settings get system secondary_screen_volume_level")
        out.trim().toIntOrNull()?.let { it.coerceIn(0, 15) / 15f } ?: -1f
    } catch (e: Throwable) { Log.w(TAG, "getVolume2nd", e); -1f }

    override fun setVolume2nd(level: Float) {
        try {
            val idx = (level.coerceIn(0f, 1f) * 15f + 0.5f).toInt()
            sh("settings put system secondary_screen_volume_level $idx")
        } catch (e: Throwable) { Log.w(TAG, "setVolume2nd", e) }
    }

    private fun sh(cmd: String): String =
        ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()

    override fun setVolume(level: Float) {
        try {
            val am = audio() ?: return
            val s = android.media.AudioManager.STREAM_MUSIC
            val min = am.getStreamMinVolume(s); val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (min + level.coerceIn(0f, 1f) * (max - min) + 0.5f).toInt(), 0)
        } catch (e: Throwable) { Log.w(TAG, "setVolume", e) }
    }

    private var fd = -1
    private var virtual = false
    private var keys: Set<Int> = emptySet()
    private var abs: Map<Int, IntArray> = emptyMap()   // code -> [min, max]
    private val lock = Any()


    override fun destroy() {
        closeTarget()
        exitProcess(0)
    }

    override fun probe(): String {
        val out = JSONObject()
        out.put("uid", Process.myUid())
        val ufd = Native.openDevice("/dev/uinput", true)
        if (ufd >= 0) { Native.closeDevice(ufd); out.put("uinput", "ok") }
        else out.put("uinput", Native.strerror(ufd))

        val devices = JSONArray()
        val nodes = File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: 0 } ?: emptyList()
        for (node in nodes) {
            val dfd = Native.openDevice(node.absolutePath, false)
            if (dfd < 0) {
                devices.put(JSONObject().put("path", node.absolutePath).put("name", "?").put("error", Native.strerror(dfd)))
                continue
            }
            try {
                val k = Native.deviceCodes(dfd, Ev.KEY) ?: IntArray(0)
                val a = Native.deviceCodes(dfd, Ev.ABS) ?: IntArray(0)
                val gamepad = k.any { it in Btn.A..Btn.THUMBR }
                devices.put(JSONObject()
                    .put("path", node.absolutePath)
                    .put("name", Native.deviceName(dfd) ?: node.name)
                    .put("gamepad", gamepad)
                    .put("keys", JSONArray(k.toList()))
                    .put("abs", JSONArray(a.toList())))
            } finally { Native.closeDevice(dfd) }
        }
        out.put("devices", devices)
        return out.toString()
    }

    override fun openPhysical(path: String): String = synchronized(lock) {
        closeTargetLocked()
        val f = Native.openDevice(path, true)
        if (f < 0) return "open $path: ${Native.strerror(f)}"
        fd = f; virtual = false
        keys = (Native.deviceCodes(f, Ev.KEY) ?: IntArray(0)).toSet()
        abs = (Native.deviceCodes(f, Ev.ABS) ?: IntArray(0)).associateWith { code ->
            Native.absInfo(f, code)?.let { intArrayOf(it[0], it[1]) } ?: intArrayOf(0, 0)
        }
        Log.i(TAG, "opened physical $path keys=${keys.size} abs=${abs.keys}")
        ""
    }

    override fun openVirtual(name: String, k: IntArray, absCodes: IntArray, absMin: IntArray, absMax: IntArray): String = synchronized(lock) {
        closeTargetLocked()
        val f = Native.createUinput(name, VENDOR, PRODUCT, k, absCodes, absMin, absMax)
        if (f < 0) return "uinput: ${Native.strerror(f)}"
        fd = f; virtual = true
        keys = k.toSet()
        abs = absCodes.indices.associate { absCodes[it] to intArrayOf(absMin[it], absMax[it]) }
        Log.i(TAG, "created virtual pad '$name'")
        ""
    }

    override fun closeTarget() { synchronized(lock) { closeTargetLocked() } }

    private fun closeTargetLocked() {
        if (fd < 0) return
        // Release anything still held so the game never sees a stuck button.
        for (k in keys) if (k in pressed) Native.writeEvent(fd, Ev.KEY, k, 0)
        Native.writeEvent(fd, Ev.SYN, Ev.SYN_REPORT, 0)
        pressed.clear()
        if (virtual) Native.destroyUinput(fd) else Native.closeDevice(fd)
        fd = -1
        keys = emptySet(); abs = emptyMap()
    }

    override fun isOpen(): Boolean = fd >= 0

    override fun targetCaps(): String = synchronized(lock) {
        val a = JSONObject()
        abs.forEach { (code, range) -> a.put(code.toString(), JSONArray(range.toList())) }
        JSONObject().put("keys", JSONArray(keys.toList())).put("abs", a).put("virtual", virtual).toString()
    }

    private val pressed = HashSet<Int>()

    override fun key(code: Int, down: Boolean): Int {
        synchronized(lock) {
            if (fd < 0) return -1
            val r = Native.writeEvent(fd, Ev.KEY, code, if (down) 1 else 0)
            Native.writeEvent(fd, Ev.SYN, Ev.SYN_REPORT, 0)
            if (down) pressed.add(code) else pressed.remove(code)
            return r
        }
    }

    override fun abs(code: Int, value: Int): Int {
        synchronized(lock) {
            if (fd < 0) return -1
            val r = Native.writeEvent(fd, Ev.ABS, code, value)
            Native.writeEvent(fd, Ev.SYN, Ev.SYN_REPORT, 0)
            return r
        }
    }

    override fun pressKeyOn(path: String, code: Int, holdMs: Int): String {
        val f = Native.openDevice(path, true)
        if (f < 0) return "open $path: ${Native.strerror(f)}"
        Thread({
            try {
                Native.writeEvent(f, Ev.KEY, code, 1); Native.writeEvent(f, Ev.SYN, Ev.SYN_REPORT, 0)
                Thread.sleep(holdMs.toLong().coerceIn(20, 2000))
                Native.writeEvent(f, Ev.KEY, code, 0); Native.writeEvent(f, Ev.SYN, Ev.SYN_REPORT, 0)
            } catch (_: InterruptedException) {
            } finally { Native.closeDevice(f) }
        }, "sidepad-key").start()
        return ""
    }

    override fun shell(cmd: String): String = try {
        // The user-service process may have no PATH; be explicit about where sh and the tools live.
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true)
        pb.environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        Log.i(TAG, "shell[$cmd] -> ${out.trim().take(200)}")
        out
    } catch (e: Exception) { Log.w(TAG, "shell failed", e); "error: ${e.message}" }

    companion object {
        private const val TAG = "SidePadInjector"
        // Arbitrary, stable identity so per-device key layouts (if ever added) stick to this pad.
        const val VENDOR = 0x5350   // "SP"
        const val PRODUCT = 0x0001
    }
}
