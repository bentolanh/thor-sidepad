package dev.lbento.thorsidepad.pad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.lbento.thorsidepad.MainActivity
import dev.lbento.thorsidepad.Prefs
import dev.lbento.thorsidepad.R
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.IInjector
import dev.lbento.thorsidepad.inject.Injector
import dev.lbento.thorsidepad.inject.Key
import org.json.JSONObject

/**
 * Foreground service that keeps the pad alive: holds the injector connection, the overlay
 * windows, and the edge strips that keep the pull gestures available.
 */
class OverlayService : Service() {

    private lateinit var prefs: Prefs
    private var overlay: PadOverlay? = null
    private var engine: PadEngine? = null
    private var gpioPath: String? = null
    private var reopenScheduled = false
    private var padDirty = false     // a shield/islands switch made from the panel; applied when the panel closes

    // ---- keyboard: our windows draw above the on-screen keyboard on the Thor's second screen, and
    // non-focusable overlays are never told the keyboard is up. So while the pad is showing, the
    // shell side asks the input-method service twice a second and the pad steps aside for the keyboard.
    @Volatile private var imeWatching = false
    private var imeThread: Thread? = null
    private var imeSuspended = false

    private fun startImeWatch() {
        if (imeWatching) return
        imeWatching = true
        val t = Thread({
            var lastShown = false
            while (imeWatching) {
                val svc = Injector.current()
                if (svc != null) {
                    try {
                        val out = svc.shell("dumpsys input_method 2>/dev/null | grep -m2 -o -E 'mCurTokenDisplayId=[0-9]+|mInputShown=[a-z]+'")
                        val shown = out.contains("mInputShown=true")
                        val onPadDisplay = Regex("mCurTokenDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()?.let { it == (overlay?.displayId ?: -1) } ?: true
                        val active = shown && onPadDisplay
                        if (active != lastShown) { lastShown = active; main.post { onImeVisible(active) } }
                    } catch (e: Exception) { Log.w(TAG, "ime poll failed", e) }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "sidepad-ime")
        t.isDaemon = true; imeThread = t; t.start()
    }

    private fun stopImeWatch() { imeWatching = false; imeThread?.interrupt(); imeThread = null }

    /** Keyboard up on the pad's screen: take the pad windows down; keyboard gone: put them back. */
    private fun onImeVisible(shown: Boolean) {
        val ov = overlay ?: return
        if (shown && visible && !imeSuspended) {
            imeSuspended = true
            ov.removePanel(); ov.removeAll(); ov.removeCatchers()
            Log.i(TAG, "keyboard up: pad set aside")
        } else if (!shown && imeSuspended) {
            imeSuspended = false
            Log.i(TAG, "keyboard gone: pad back")
            if (visible) rebuildPad() else ensureCatcher()
        }
    }

    /**
     * The Thor recreates its controller node when its style changes, and a Bluetooth pad comes
     * and goes; an open descriptor to the old node then writes into nothing. Reopen the target
     * whenever Android reports an input device change while the pad is up.
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = scheduleReopen("added $id")
        override fun onInputDeviceRemoved(id: Int) = scheduleReopen("removed $id")
        override fun onInputDeviceChanged(id: Int) = scheduleReopen("changed $id")
    }

    private fun scheduleReopen(why: String) {
        if (!visible || reopenScheduled) return
        reopenScheduled = true
        main.postDelayed({ reopenScheduled = false; reopenTarget(why) }, 700)
    }

    /** Re-resolves and reopens the target and points the existing engine at it. No window is touched. */
    private fun reopenTarget(why: String) {
        if (!visible) return
        val svc = Injector.current() ?: return
        try {
            val err = openTarget(svc)
            if (err.isNotEmpty()) { Log.w(TAG, "reopen ($why): $err"); toast(err); return }
            val caps = Caps.fromJson(svc.targetCaps())
            val eng = engine
            if (eng == null) { show(); return }
            eng.rebind(svc, caps)
            overlay?.invalidatePad()
            Log.i(TAG, "reopened target ($why): ${thorLabel(prefs.physicalName) ?: prefs.physicalName}")
        } catch (e: Exception) { Log.w(TAG, "reopen failed", e) }
    }

    private fun panelState(ov: PadOverlay) = PanelState(ov.isShowing, prefs.shield, prefs.opacity, prefs.backdrop, ov.blurSupported,
        targets, prefs.physicalName, prefs.targetMode == Prefs.MODE_VIRTUAL,
        shizukuReady = Injector.state() == Injector.ShizukuState.READY)

    /** Applies a shield/islands switch that was chosen while the panel was open. */
    private fun applyDirty() {
        if (padDirty) { padDirty = false; if (visible) rebuildPad() }
    }
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
            // The Thor's own pad keeps its identity across style changes even though its name changes.
            val byName = found.firstOrNull { it.name == prefs.physicalName }
                ?: if (isThorName(prefs.physicalName)) found.firstOrNull { isThorName(it.name) } else null
            val chosen = byName ?: found.firstOrNull { it.path == prefs.physicalPath } ?: found.firstOrNull()
            if (chosen != null) {
                if (prefs.physicalName != chosen.name) prefs.physicalName = chosen.name
                if (prefs.physicalPath != chosen.path) prefs.physicalPath = chosen.path
            }
        } catch (e: Exception) { Log.w(TAG, "probe failed", e) }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.guideSnapshot?.let { snap ->
            // A guide was interrupted (service killed, reboot): undo its temporary look now.
            val p = snap.split("|")
            if (p.size == 3) { prefs.backdrop = p[1]; prefs.opacity = p[2].toFloatOrNull() ?: prefs.opacity }
            prefs.guideSnapshot = null
            Log.i(TAG, "guide snapshot restored after interruption: $snap")
        }
        prefs.shield = false   // SidePad always starts with the shield off; turn it on per session
        startForeground(NOTIF_ID, buildNotification())
        running = true
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, main)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_HIDE -> hide()
            ACTION_TOGGLE -> toggle()
            ACTION_EDIT -> edit()
            ACTION_STOP -> { hide(); stopSelf() }
            ACTION_PANEL -> showPanel(keepPage = false)
            ACTION_FOCUS_TOP -> returnFocusToTopScreen()
            ACTION_GUIDE -> showGuide()
            else -> { ensureCatcher(); if (!prefs.guideShown) showGuide() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener) } catch (_: Exception) {}
        hide()
        stopImeWatch()
        overlay?.tearDown()
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
                val eng = PadEngine(svc, Caps.fromJson(svc.targetCaps())) { main.post { scheduleReopen("write failed") } }
                engine = eng
                val ov = overlayOrCreate()
                ov.removePanel()
                ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.backdrop,
                    onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) })
                // The shield catches the edge pulls itself; islands mode still needs the strips.
                if (prefs.shield) ov.removeCatchers() else ensureCatcher()
                visible = true
                updateNotification()
                startImeWatch()
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                toast("Could not show pad: ${e.message}")
            }
        }
    }

    private fun hide() {
        stopImeWatch(); imeSuspended = false
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
            ov.pullTracker = object : PullListener {
                override fun onPullStart() { if (guideStep == 0) openPanel(dragged = true) }
                override fun onPullMove(dy: Float) { if (guideStep == 0) ov.dragPanel(dy) }
                override fun onPullEnd(commit: Boolean) { if (guideStep == 0) ov.endPanelDrag(commit) }
            }
            ov.onFocusReturn = { returnFocusToTopScreen() }
        }

    /** Starts the invisible hand-off activity on the top screen through the shell user (a plain start would be a blocked background launch). */
    private fun returnFocusToTopScreen() {
        val svc = Injector.current() ?: return
        Thread { try { svc.shell("am start --display 0 -n $packageName/.FocusHandoffActivity") } catch (e: Exception) { Log.w(TAG, "focus handoff failed", e) } }.start()
    }

    /** Keeps the edge strips on the pad's screen whenever the shield is not covering it. */
    private fun ensureCatcher() {
        try { val ov = overlayOrCreate(); ov.showCatchers(onPullDown = { showPanel() }, onPullUp = { toggle() }, tracker = ov.pullTracker) } catch (e: Exception) { Log.w(TAG, "catcher failed", e) }
    }

    /**
     * Opens the setup screen on the pad's display. Android 13 usually refuses activity launches
     * from a background service, but not always, so try directly first; if the activity has not
     * resumed shortly after, fall back to the shell user, and if that is not available either,
     * point at the notification, which can always open the app.
     */
    private fun openApp(displayId: Int) {
        val before = MainActivity.lastResumedAt
        try {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(intent, opts.toBundle())
        } catch (e: Exception) { Log.w(TAG, "direct open failed", e) }
        main.postDelayed({
            if (MainActivity.lastResumedAt > before) return@postDelayed
            val svc = Injector.current()
            if (svc != null) {
                Thread { try { svc.shell("am start --display $displayId -n $packageName/.MainActivity") } catch (e: Exception) { Log.w(TAG, "open app failed", e) } }.start()
            } else {
                toast("Android blocked the launch and Shizuku is not running. Tap the Thor SidePad notification to open the app.")
            }
        }, 1200)
    }

    /** Buttons that act on the pad itself. */
    private fun onAction(code: Int) {
        when (code) {
            dev.lbento.thorsidepad.inject.Action.SHIELD -> { prefs.shield = !prefs.shield; rebuildPad() }
        }
    }

    /** Re-lays the pad windows with the current looks, keeping the injector target open. */
    private fun rebuildPad() {
        val eng = engine; val ov = overlay
        if (!visible || eng == null || ov == null) { if (visible) show(); return }
        try {
            ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.backdrop,
                onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) })
            if (prefs.shield) ov.removeCatchers() else ensureCatcher()
        } catch (e: Exception) { Log.e(TAG, "rebuild failed", e); show() }
    }

    /** Opens the panel. A fresh open starts on the main page; a re-render after a setting change keeps its page. */
    private var guideStep = 0   // 0 = not running; 1 pull down, 2 pull up (show), 3 pull up (hide), 4 end screen
    private var saved: Triple<Boolean, String, Float>? = null   // shield, backdrop, opacity before the guide
    private var savedVisible = false

    /**
     * Starts the interactive guide: SidePad running, shield on (with a frosted backdrop and fully
     * opaque buttons, the most legible look) but the pad hidden, so step 2 has something to show.
     * Everything is put back when the guide ends.
     */
    private fun showGuide() {
        if (saved == null) {
            saved = Triple(prefs.shield, prefs.backdrop, prefs.opacity); savedVisible = visible
            prefs.guideSnapshot = "${prefs.shield}|${prefs.backdrop}|${prefs.opacity}"
        }
        Log.i(TAG, "guide start: saved=$saved visible=$savedVisible")
        prefs.shield = true; prefs.backdrop = Prefs.BACKDROP_FROSTED; prefs.opacity = 1f
        guideStep = 1
        if (visible) hide() else ensureCatcher()
        main.postDelayed({ if (guideStep == 1) showGuideStep(1) }, 600)
    }

    /** Puts the pad's look and visibility back to what they were before the guide. */
    private fun restoreAfterGuide() {
        val s = saved ?: return
        saved = null
        Log.i(TAG, "guide end: restoring $s visible=$savedVisible")
        prefs.shield = s.first; prefs.backdrop = s.second; prefs.opacity = s.third
        prefs.guideSnapshot = null
        if (savedVisible && !visible) show() else if (!savedVisible && visible) hide() else if (visible) rebuildPad()
    }

    private fun showGuideStep(step: Int) {
        val ov = try { overlayOrCreate() } catch (e: Exception) { guideStep = 0; return }
        guideStep = step
        ov.removePanel()
        // Step 1 pulls the real panel down over the guide, finger-tracked like the shade.
        val pull = if (step == 1) object : PullListener {
            override fun onPullStart() { openPanel(dragged = true) }
            override fun onPullMove(dy: Float) { ov.dragPanel(dy) }
            override fun onPullEnd(commit: Boolean) {
                if (commit) { ov.removeGuide(); guideStep = 2; ov.endPanelDrag(true) } else ov.endPanelDrag(false)
            }
        } else null
        ov.showGuide(step, onGesture = { g -> onGuideGesture(g) }, onSkip = { finishGuide() }, pull = pull)
    }

    private fun finishGuide() {
        guideStep = 0
        prefs.guideShown = true
        overlay?.removeGuide()
        restoreAfterGuide()
    }

    /** Each step performs the real action at once; the next step follows after a short pause. */
    private fun onGuideGesture(g: EdgeGesture) {
        val ov = overlay ?: return
        when {
            guideStep == 1 && g == EdgeGesture.PULL_DOWN -> { ov.removeGuide(); guideStep = 2; showPanel() }   // step 2 resumes when the panel closes
            guideStep == 2 && g == EdgeGesture.PULL_UP -> { ov.removeGuide(); show(); main.postDelayed({ if (guideStep == 2) showGuideStep(3) }, 1000) }
            guideStep == 3 && g == EdgeGesture.PULL_UP -> { ov.removeGuide(); hide(); main.postDelayed({ if (guideStep == 3) showGuideStep(4) }, 1000) }
        }
    }

    /** Called whenever the panel goes away, so a guide waiting on it can continue. */
    private fun panelClosed() {
        if (guideStep == 2) main.postDelayed({ if (guideStep == 2) showGuideStep(2) }, 1000)
    }

    private fun showPanel(keepPage: Boolean = false) = openPanel(dragged = false, keepPage = keepPage)

    private fun openPanel(dragged: Boolean, keepPage: Boolean = false) {
        if (!keepPage) ControlPanel.page = ControlPanel.Page.MAIN
        val ov = try { overlayOrCreate() } catch (e: Exception) { toast("No second screen: ${e.message}"); return }
        Injector.current()?.let { refreshTargets(it) }
        ov.showPanel(panelState(ov), dragged = dragged, shieldOn = prefs.shield, backdrop = prefs.backdrop, actions = object : PanelActions {
            override fun setTarget(choice: TargetChoice?) {
                if (choice == null) prefs.targetMode = Prefs.MODE_VIRTUAL
                else { prefs.targetMode = Prefs.MODE_PHYSICAL; prefs.physicalName = choice.name; prefs.physicalPath = choice.path }
                if (visible) reopenTarget("target chosen")   // reopens the injector; the windows stay
                ov.updatePanel(panelState(ov))
            }
            override fun togglePad() { ov.removePanel(); padDirty = false; toggle(); panelClosed() }
            override fun editLayout() { ov.removePanel(); padDirty = false; edit() }
            override fun setShield(on: Boolean) { prefs.shield = on; padDirty = true; ov.updatePanel(panelState(ov)); ov.updatePanelLook(prefs.shield, prefs.backdrop) }
            override fun setOpacity(value: Float) { prefs.opacity = value; ov.updateLooks(prefs.opacity, prefs.backdrop) }
            override fun setBackdrop(value: String) { prefs.backdrop = value; ov.updateLooks(prefs.opacity, prefs.backdrop); ov.updatePanel(panelState(ov)); ov.updatePanelLook(prefs.shield, prefs.backdrop) }
            override fun stopService() { ov.removePanel(); padDirty = false; hide(); stopSelf() }
            override fun openApp() { ov.removePanel(); applyDirty(); openApp(ov.displayId) }
            override fun startShizuku() {
                ov.removePanel(); applyDirty()
                val launch = packageManager.getLaunchIntentForPackage(MainActivity.SHIZUKU_PACKAGE)
                if (launch == null) { toast("Shizuku is not installed. Open the app to set it up."); return }
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(ov.displayId).toBundle())
                } catch (e: Exception) { Log.w(TAG, "open Shizuku failed", e); toast("Could not open Shizuku.") }
            }
            override fun close() { ov.removePanel(); applyDirty(); panelClosed() }
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

    /** Pull-down opens our panel, pull-up shows or hides the pad. */
    private fun onGesture(g: EdgeGesture) {
        when (g) {
            EdgeGesture.PULL_DOWN -> showPanel()
            EdgeGesture.PULL_UP -> toggle()
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

        const val ACTION_SHOW = "dev.lbento.thorsidepad.SHOW"
        const val ACTION_HIDE = "dev.lbento.thorsidepad.HIDE"
        const val ACTION_TOGGLE = "dev.lbento.thorsidepad.TOGGLE"
        const val ACTION_EDIT = "dev.lbento.thorsidepad.EDIT"
        const val ACTION_STOP = "dev.lbento.thorsidepad.STOP"
        const val ACTION_PANEL = "dev.lbento.thorsidepad.PANEL"
        const val ACTION_FOCUS_TOP = "dev.lbento.thorsidepad.FOCUS_TOP"
        const val ACTION_GUIDE = "dev.lbento.thorsidepad.GUIDE"
        const val ACTION_START = "dev.lbento.thorsidepad.START"

        @Volatile var running = false
        @Volatile var visible = false

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action))
        }
    }
}
