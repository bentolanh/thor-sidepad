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
    private var targets: List<TargetChoice> = emptyList()   // controllers seen at the last probe

    /**
     * Lists the gamepad nodes and re-resolves the chosen controller by name, since a node number
     * changes when the Thor switches controller style or a Bluetooth pad reconnects.
     */
    private fun refreshTargets(svc: IInjector) {
        try {
            val devs = JSONObject(svc.probe()).getJSONArray("devices")
            val found = ArrayList<TargetChoice>()
            for (i in 0 until devs.length()) {
                val d = devs.getJSONObject(i)
                if (d.optBoolean("gamepad", false)) found.add(TargetChoice(d.getString("name"), d.getString("path")))
            }
            targets = found
            val byName = found.firstOrNull { it.name == prefs.physicalName }
            val chosen = byName ?: found.firstOrNull { it.path == prefs.physicalPath } ?: found.firstOrNull()
            if (chosen != null) {
                if (prefs.physicalName.isEmpty() || byName == null) prefs.physicalName = chosen.name
                if (prefs.physicalPath != chosen.path) { prefs.physicalPath = chosen.path; watching = false; try { svc.stopWatch() } catch (_: Exception) {} }
            }
        } catch (e: Exception) { Log.w(TAG, "probe failed", e) }
    }
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
            ACTION_PANEL -> showPanel(keepPage = false)
            else -> { ensureCatcher(); ensureChord() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hide()
        overlay?.tearDown()
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

    /** Opens the configured target (a controller node or a virtual pad). Returns an error or "". */
    private fun openTarget(svc: IInjector): String {
        refreshTargets(svc)
        return if (prefs.targetMode == Prefs.MODE_VIRTUAL) {
            val abs = Catalog.virtualAbs
            svc.openVirtual("Thor SidePad", Catalog.virtualKeys,
                abs.map { it.first }.toIntArray(), abs.map { it.second }.toIntArray(), abs.map { it.third }.toIntArray())
        } else {
            if (prefs.physicalPath.isBlank()) "No controller found. Pull down and pick one once a controller is connected."
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
                val ov = overlayOrCreate()
                ov.removePanel()
                ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.gestures, prefs.backdrop,
                    onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) })
                // The shield catches the edge pulls itself; islands mode still needs the strips.
                if (prefs.shield) ov.removeCatchers() else ensureCatcher()
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
        ensureCatcher()
    }

    private fun overlayOrCreate(): PadOverlay =
        overlay ?: PadOverlay(this, PadOverlay.resolveDisplayId(this, prefs.displayId)).also { ov ->
            overlay = ov
            ov.onFocusReturn = { returnFocusToTopScreen() }
        }

    /** Starts the invisible hand-off activity on the top screen through the shell user (a plain start would be a blocked background launch). */
    private fun returnFocusToTopScreen() {
        val svc = Injector.current() ?: return
        Thread { try { svc.shell("am start --display 0 -n $packageName/.FocusHandoffActivity") } catch (e: Exception) { Log.w(TAG, "focus handoff failed", e) } }.start()
    }

    /** Keeps the edge strips on the pad's screen whenever the shield is not covering it. */
    private fun ensureCatcher() {
        try { overlayOrCreate().showCatchers(onPullDown = { showPanel() }, onPullUp = { toggle() }) } catch (e: Exception) { Log.w(TAG, "catcher failed", e) }
    }

    /** Buttons that act on the pad itself. */
    private fun onAction(code: Int) {
        when (code) {
            dev.linhhan.thorsidepad.inject.Action.SHIELD -> { prefs.shield = !prefs.shield; rebuildPad() }
        }
    }

    /** Re-lays the pad windows with the current looks, keeping the injector target open. */
    private fun rebuildPad() {
        val eng = engine; val ov = overlay
        if (!visible || eng == null || ov == null) { if (visible) show(); return }
        try {
            ov.removePanel()
            ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.gestures, prefs.backdrop,
                onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) })
            if (prefs.shield) ov.removeCatchers() else ensureCatcher()
        } catch (e: Exception) { Log.e(TAG, "rebuild failed", e); show() }
    }

    /** Opens the panel. A fresh open starts on the main page; a re-render after a setting change keeps its page. */
    private fun showPanel(keepPage: Boolean = false) {
        if (!keepPage) ControlPanel.page = ControlPanel.Page.MAIN
        val ov = try { overlayOrCreate() } catch (e: Exception) { toast("No second screen: ${e.message}"); return }
        Injector.current()?.let { refreshTargets(it) }
        val state = PanelState(ov.isShowing, prefs.shield, prefs.gestures, prefs.opacity, prefs.backdrop, ov.blurSupported,
            targets, prefs.physicalName, prefs.targetMode == Prefs.MODE_VIRTUAL, prefs.chordEnabled)
        ov.showPanel(state, object : PanelActions {
            override fun setTarget(choice: TargetChoice?) {
                if (choice == null) prefs.targetMode = Prefs.MODE_VIRTUAL
                else { prefs.targetMode = Prefs.MODE_PHYSICAL; prefs.physicalName = choice.name; prefs.physicalPath = choice.path }
                if (visible) { hide(); show() }   // the target itself changes, so the injector must reopen
                showPanel(keepPage = true)
            }
            override fun setChord(on: Boolean) {
                prefs.chordEnabled = on
                if (on) ensureChord() else { watching = false; try { Injector.current()?.stopWatch() } catch (_: Exception) {} }
            }
            override fun togglePad() { ov.removePanel(); toggle() }
            override fun editLayout() { ov.removePanel(); edit() }
            override fun setShield(on: Boolean) { prefs.shield = on; rebuildPad(); showPanel(keepPage = true) }
            override fun setGestures(on: Boolean) { prefs.gestures = on; rebuildPad() }
            override fun setOpacity(value: Float) { prefs.opacity = value; rebuildPad() }
            override fun setBackdrop(value: String) { prefs.backdrop = value; rebuildPad(); showPanel(keepPage = true) }
            override fun openThorControlCenter() {
                ov.removePanel()
                Injector.connect(this@OverlayService) { svc ->
                    if (svc == null) { toast("Shizuku not ready"); return@connect }
                    val err = gpioPath(svc)?.let { svc.pressKeyOn(it, Key.F24, 60) } ?: "gpio-keys device not found"
                    if (err.isNotEmpty()) toast(err)
                }
            }
            override fun stopService() { ov.removePanel(); hide(); stopSelf() }
            override fun close() { ov.removePanel() }
        })
    }

    private fun edit() {
        withInjector {
            try {
                val ov = overlayOrCreate()
                ov.removePanel()
                ov.showEdit(PadLayout.fromJson(prefs.layoutJson), prefs.activePreset,
                    onSaved = { l, name -> prefs.layoutJson = l.toJson(); prefs.activePreset = name; show() },
                    onCancel = { show() })
            } catch (e: Exception) {
                Log.e(TAG, "edit failed", e)
                toast("Could not open editor: ${e.message}")
            }
        }
    }

    /**
     * Pull-down opens our panel, pull-up shows or hides the pad. Left-edge presses the Thor's
     * own Back key through the controller node, which the Thor routes to the last-touched screen.
     */
    private fun onGesture(g: EdgeGesture) {
        Log.i(TAG, "gesture $g")
        when (g) {
            EdgeGesture.PULL_DOWN -> showPanel()
            EdgeGesture.PULL_UP -> toggle()
            EdgeGesture.LEFT_EDGE -> {
                val svc = Injector.current() ?: return
                try {
                    val err = svc.pressKeyOn(prefs.physicalPath, Key.BACK, 60)
                    if (err.isNotEmpty()) toast(err)
                } catch (e: Exception) { Log.w(TAG, "gesture failed", e) }
            }
        }
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
            .setContentText(if (visible) "Pad is on the second screen. Pull up from the bottom edge to hide it." else "Pad hidden. Pull up from the bottom edge to show it, pull down for the panel.")
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
        const val ACTION_PANEL = "dev.linhhan.thorsidepad.PANEL"
        const val ACTION_START = "dev.linhhan.thorsidepad.START"

        @Volatile var running = false
        @Volatile var visible = false

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action))
        }
    }
}
