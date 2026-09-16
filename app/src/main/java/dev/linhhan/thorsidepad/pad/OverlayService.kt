package dev.linhhan.thorsidepad.pad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.linhhan.thorsidepad.MainActivity
import dev.linhhan.thorsidepad.Prefs
import dev.linhhan.thorsidepad.R
import dev.linhhan.thorsidepad.inject.Btn
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.IInjector
import dev.linhhan.thorsidepad.inject.IInjectorListener
import dev.linhhan.thorsidepad.inject.Injector
import dev.linhhan.thorsidepad.inject.Key
import org.json.JSONObject

/**
 * Foreground service that keeps the pad alive: holds the injector connection, the overlay
 * windows, and the physical-button chord watcher that toggles the pad.
 */
class OverlayService : Service() {

    private lateinit var prefs: Prefs
    private var overlay: PadOverlay? = null
    private var engine: PadEngine? = null
    private var watching = false
    private var gpioPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val chordListener = object : IInjectorListener.Stub() {
        override fun onChord() { main.post { toggle() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForeground(NOTIF_ID, buildNotification())
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_HIDE -> hide()
            ACTION_TOGGLE -> toggle()
            ACTION_EDIT -> edit()
            ACTION_STOP -> { hide(); stopSelf() }
            else -> ensureChord()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hide()
        try { Injector.current()?.stopWatch() } catch (_: Exception) {}
        running = false
        visible = false
        super.onDestroy()
    }

    private fun toggle() { if (overlay?.isShowing == true) hide() else show() }

    private fun withInjector(block: (IInjector) -> Unit) {
        Injector.connect(this) { svc ->
            if (svc == null) {
                toast("Shizuku is not running or not authorised. Open Thor SidePad to fix.")
            } else block(svc)
        }
    }

    /** Opens the configured target (Thor controller node or a virtual pad). Returns an error or "". */
    private fun openTarget(svc: IInjector): String {
        return if (prefs.targetMode == Prefs.MODE_VIRTUAL) {
            val abs = Catalog.virtualAbs
            svc.openVirtual("Thor SidePad", Catalog.virtualKeys,
                abs.map { it.first }.toIntArray(), abs.map { it.second }.toIntArray(), abs.map { it.third }.toIntArray())
        } else {
            if (prefs.physicalPath.isBlank()) "No controller selected. Open Thor SidePad and pick the Thor controller."
            else svc.openPhysical(prefs.physicalPath)
        }
    }

    private fun show() {
        withInjector { svc ->
            try {
                val err = openTarget(svc)
                if (err.isNotEmpty()) { toast(err); return@withInjector }
                engine?.shutdown()
                val eng = PadEngine(svc, Caps.fromJson(svc.targetCaps()))
                engine = eng
                val ov = overlay ?: PadOverlay(this, PadOverlay.resolveDisplayId(this, prefs.displayId)).also { overlay = it }
                ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.gestures) { g -> onGesture(svc, g) }
                visible = true
                updateNotification()
                ensureChord()
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                toast("Could not show pad: ${e.message}")
            }
        }
    }

    private fun hide() {
        overlay?.removeAll()
        engine?.shutdown(); engine = null
        try { Injector.current()?.closeTarget() } catch (_: Exception) {}
        visible = false
        updateNotification()
    }

    private fun edit() {
        withInjector {
            try {
                val ov = overlay ?: PadOverlay(this, PadOverlay.resolveDisplayId(this, prefs.displayId)).also { overlay = it }
                ov.showEdit(PadLayout.fromJson(prefs.layoutJson),
                    onSave = { l -> prefs.layoutJson = l.toJson(); show() },
                    onCancel = { show() })
            } catch (e: Exception) {
                Log.e(TAG, "edit failed", e)
                toast("Could not open editor: ${e.message}")
            }
        }
    }

    /**
     * Edge swipes press the Thor's own buttons: the AYN key (Control Center), Home, Back. Those
     * go through the same nodes the physical keys use, so the Thor routes them exactly as usual.
     */
    private fun onGesture(svc: IInjector, g: EdgeGesture) {
        try {
            val err = when (g) {
                EdgeGesture.PULL_DOWN -> gpioPath(svc)?.let { svc.pressKeyOn(it, Key.F24, 60) } ?: "gpio-keys device not found"
                EdgeGesture.PULL_UP -> svc.pressKeyOn(prefs.physicalPath, Key.HOME, 60)
                EdgeGesture.LEFT_EDGE -> svc.pressKeyOn(prefs.physicalPath, Key.BACK, 60)
            }
            if (err.isNotEmpty()) toast(err)
        } catch (e: Exception) { Log.w(TAG, "gesture failed", e) }
    }

    /** The node named "gpio-keys", where the AYN key lives; found once per service life. */
    private fun gpioPath(svc: IInjector): String? {
        gpioPath?.let { return it }
        val devs = JSONObject(svc.probe()).getJSONArray("devices")
        for (i in 0 until devs.length()) {
            val d = devs.getJSONObject(i)
            if (d.optString("name") == "gpio-keys") { gpioPath = d.getString("path"); return gpioPath }
        }
        return null
    }

    /** Select + Start held together toggles the pad, read straight from the controller node. */
    private fun ensureChord() {
        if (!prefs.chordEnabled || prefs.physicalPath.isBlank() || watching) return
        Injector.connect(this) { svc ->
            if (svc == null) return@connect
            val err = svc.watchChord(prefs.physicalPath, intArrayOf(Btn.SELECT, Btn.START), CHORD_HOLD_MS, chordListener)
            if (err.isEmpty()) watching = true else Log.w(TAG, "chord watch: $err")
        }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun pending(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Thor SidePad", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pad)
            .setContentTitle("Thor SidePad")
            .setContentText(if (visible) "Pad is on the second screen" else "Pad hidden. Hold Select+Start to show it.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (visible) "Hide" else "Show", pending(ACTION_TOGGLE)).build())
            .addAction(Notification.Action.Builder(null, "Edit", pending(ACTION_EDIT)).build())
            .addAction(Notification.Action.Builder(null, "Stop", pending(ACTION_STOP)).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "SidePadService"
        private const val CHANNEL = "sidepad"
        private const val NOTIF_ID = 1
        private const val CHORD_HOLD_MS = 600

        const val ACTION_SHOW = "dev.linhhan.thorsidepad.SHOW"
        const val ACTION_HIDE = "dev.linhhan.thorsidepad.HIDE"
        const val ACTION_TOGGLE = "dev.linhhan.thorsidepad.TOGGLE"
        const val ACTION_EDIT = "dev.linhhan.thorsidepad.EDIT"
        const val ACTION_STOP = "dev.linhhan.thorsidepad.STOP"

        @Volatile var running = false
        @Volatile var visible = false

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action))
        }
    }
}
