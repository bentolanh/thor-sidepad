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

    private var setBrightnessM: java.lang.reflect.Method? = null
    private var setTempBrightnessM: java.lang.reflect.Method? = null
    private var tempMissing = false

    override fun setBrightness(displayId: Int, level: Float) {
        try {
            val dm = displayManager() ?: return
            val m = setBrightnessM ?: dm.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).also { setBrightnessM = it }
            m.invoke(dm, displayId, level.coerceIn(0f, 1f))
        } catch (e: Throwable) { Log.w(TAG, "setBrightness", e) }
    }

    override fun setBrightnessLive(displayId: Int, level: Float) {
        if (tempMissing) { setBrightness(displayId, level); return }
        try {
            val dm = displayManager() ?: return
            val m = setTempBrightnessM ?: dm.javaClass.getMethod("setTemporaryBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).also { setTempBrightnessM = it }
            m.invoke(dm, displayId, level.coerceIn(0f, 1f))
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setTemporaryBrightness missing on this firmware; using setBrightness")
            tempMissing = true; setBrightness(displayId, level)
        } catch (e: Throwable) { Log.w(TAG, "setBrightnessLive", e) }
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

    /**
     * Hands a media key to whatever holds the media session, so it works whatever app is playing.
     * AudioManager.dispatchMediaKeyEvent is not usable here: this process is started by Shizuku and
     * never runs the media framework's initialisers, so MediaSessionManager cannot be constructed.
     * The media_session service command does the same job from the shell.
     */
    override fun mediaKey(action: String) {
        try {
            val verb = when (action) { "next", "previous", "play", "pause", "stop", "play-pause" -> action; else -> return }
            sh("cmd media_session dispatch $verb")
        } catch (e: Throwable) { Log.w(TAG, "mediaKey", e) }
    }

    /**
     * TEMPORARY probe. This process is started by Shizuku and never runs the media framework's
     * start-up, so MediaSessionManager could not be constructed. Try invoking that start-up by
     * hand, then reach the sessions with the shell's own MEDIA_CONTENT_CONTROL.
     */
    // ---- the playing session. The shell already holds MEDIA_CONTENT_CONTROL, so no notification
    // listener is needed; this process simply never ran the media framework's start-up, which
    // leaves MediaServiceManager null. Supplying that by hand is enough to reach the sessions.
    private var mediaReady = false

    private fun ensureMediaFramework() {
        if (mediaReady) return
        try {
            val mgrCls = Class.forName("android.media.MediaServiceManager")
            val ctor = mgrCls.getDeclaredConstructor(); ctor.isAccessible = true
            val mgr = ctor.newInstance()
            for (cls in listOf("android.media.MediaFrameworkPlatformInitializer", "android.media.MediaFrameworkInitializer")) {
                try {
                    val m = Class.forName(cls).getDeclaredMethod("setMediaServiceManager", mgrCls)
                    m.isAccessible = true; m.invoke(null, mgr)
                } catch (_: Throwable) {}
            }
            mediaReady = true
        } catch (t: Throwable) { Log.w(TAG, "media framework init", t) }
    }

    /**
     * The session a media key would reach, so the timeline follows the same player the transport
     * buttons do.
     */
    private fun session(): android.media.session.MediaController? {
        ensureMediaFramework()
        val msm = context?.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            // The system names the session that receives media keys; priority order in
            // getActiveSessions does not reliably match it, which put the timeline on a
            // different player from the transport buttons.
            val token = msm.getMediaKeyEventSession()
            if (token != null) android.media.session.MediaController(context!!, token)
            else msm.getActiveSessions(null).firstOrNull()
        } catch (t: Throwable) { Log.w(TAG, "sessions", t); null }
    }

    /** A playing session reports where it was at a moment; carry that forward to now. */
    private fun livePosition(st: android.media.session.PlaybackState?): Long {
        if (st == null) return -1L
        if (st.state != android.media.session.PlaybackState.STATE_PLAYING) return st.position
        val dt = android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return st.position + (dt * st.playbackSpeed).toLong()
    }

    override fun mediaInfo(): String = try {
        val c = session()
        if (c == null) "" else {
            val st = c.playbackState
            val dur = c.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
            val label = try {
                val pm = context!!.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            } catch (_: Throwable) { c.packageName }
            val md = c.metadata
            fun meta(vararg keys: String): String {
                for (k in keys) md?.getString(k)?.takeIf { it.isNotBlank() }?.let { return it }
                return ""
            }
            val title = meta(android.media.MediaMetadata.METADATA_KEY_TITLE,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val sub = meta(android.media.MediaMetadata.METADATA_KEY_ARTIST,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            // A title can hold any character, so the fields are split on one that text never carries.
            listOf(c.packageName, "${st?.state ?: 0}", "${livePosition(st)}", "$dur", label, title, sub)
                .joinToString("\u0001")
        }
    } catch (t: Throwable) { Log.w(TAG, "mediaInfo", t); "" }

    override fun mediaSeek(posMs: Long) {
        try { session()?.transportControls?.seekTo(posMs.coerceAtLeast(0L)) }
        catch (t: Throwable) { Log.w(TAG, "mediaSeek", t) }
    }

    /** Jump by [deltaMs] from where the session is now, clamped to the end if a length is known. */
    override fun mediaSkip(deltaMs: Int) {
        try {
            val c = session() ?: return
            val dur = c.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
            var target = livePosition(c.playbackState) + deltaMs
            if (target < 0L) target = 0L
            if (dur > 0L && target > dur) target = dur
            c.transportControls.seekTo(target)
        } catch (t: Throwable) { Log.w(TAG, "mediaSkip", t) }
    }

    override fun appIcon(pkg: String): ByteArray? = try {
        val d = context!!.packageManager.getApplicationIcon(pkg)
        val side = 96
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, side, side)
        d.draw(c)
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (t: Throwable) { Log.w(TAG, "appIcon $pkg", t); null }

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
        // No relative axes: a pad that declared them would be taken for a mouse.
        val f = Native.createUinput(name, VENDOR, PRODUCT, k, absCodes, absMin, absMax, IntArray(0))
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

    override fun probePointer(holdMs: Int): String {
        val report = StringBuilder()
        val f = Native.createUinput(
            "Thor SidePad Mouse", VENDOR, PRODUCT + 1,
            intArrayOf(Mouse.LEFT, Mouse.RIGHT, Mouse.MIDDLE),
            IntArray(0), IntArray(0), IntArray(0),
            intArrayOf(Rel.X, Rel.Y, Rel.WHEEL),
        )
        if (f < 0) return "create failed: ${Native.strerror(f)}"
        report.append("created, fd=").append(f).append('\n')
        try {
            Thread.sleep(800)     // let the system pick the new device up
            report.append(shell("dumpsys input | grep -A8 'SidePad Mouse' | head -20").trim()).append('\n')
            // A slow sweep rather than one jump, so a cursor has time to appear and settle.
            repeat(40) {
                Native.writeEvent(f, Ev.REL, Rel.X, 14)
                Native.writeEvent(f, Ev.REL, Rel.Y, 7)
                Native.writeEvent(f, Ev.SYN, Ev.SYN_REPORT, 0)
                Thread.sleep(25)
            }
            report.append("swept\n")
            Thread.sleep(holdMs.toLong().coerceIn(0L, 30_000L))
        } catch (e: Exception) {
            report.append("error: ").append(e).append('\n')
        } finally {
            Native.destroyUinput(f)
            report.append("destroyed\n")
        }
        return report.toString()
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
