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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import dev.lbento.thorsidepad.MainActivity
import dev.lbento.thorsidepad.Prefs
import dev.lbento.thorsidepad.R
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.IInjector
import dev.lbento.thorsidepad.inject.Injector
import dev.lbento.thorsidepad.inject.Key
import org.json.JSONArray
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
    // shell side asks twice a second and the pad steps aside for the keyboard.
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
                        // Ask the window manager about the keyboard's own window, not the input-method
                        // service. Asking that service makes it turn round and ask the current keyboard
                        // app to dump itself, which wakes a sleeping app roughly once a second for as
                        // long as the pad is up and costs about half a core; the window manager answers
                        // out of its own state in a tenth of the time and leaves the keyboard alone.
                        //
                        // The window is also the better answer. The old question, whether the service
                        // considers input shown, says yes for a text field on the top screen where no
                        // keyboard ever appears, which is why it had to be paired with a guess about
                        // which screen the field was on. A window that is ready for display on the pad's
                        // own screen is a keyboard the pad is actually covering, and nothing else is.
                        val out = svc.shell(
                            "dumpsys window InputMethod 2>/dev/null | grep -oE 'mDisplayId=[0-9]+|isReadyForDisplay\\(\\)=[a-z]+'")
                        val padDisplay = overlay?.displayId ?: -1
                        var display = -1
                        var active = false
                        // The fields come out in document order, so each window's display is followed by
                        // its own readiness; a display with no window after it simply never matches.
                        for (line in out.lineSequence()) {
                            val t = line.trim()
                            when {
                                t.startsWith("mDisplayId=") ->
                                    display = t.removePrefix("mDisplayId=").toIntOrNull() ?: -1
                                t == "isReadyForDisplay()=true" -> if (display == padDisplay) active = true
                            }
                        }
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
    /** Input devices SidePad created, so their comings and goings are not mistaken for news. */
    private val ourInputIds = HashSet<Int>()

    /**
     * Watches for controllers appearing and disappearing, and ignores its own.
     *
     * Reopening the target destroys the virtual pad and makes a new one. Android reports that as
     * a device arriving, which used to bring us straight back here to reopen again — a loop that
     * ran about every 0.7 seconds, and which Android announced each time round as a controller
     * connecting and disconnecting. The device being added is checked by name, and its id
     * remembered so the matching removal can be ignored too; by then there is nothing left to
     * look the name up from.
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            if (isOurInputDevice(id)) { ourInputIds.add(id); return }
            scheduleReopen("added $id")
        }
        override fun onInputDeviceRemoved(id: Int) {
            if (ourInputIds.remove(id)) return
            scheduleReopen("removed $id")
        }
        override fun onInputDeviceChanged(id: Int) {
            if (id in ourInputIds || isOurInputDevice(id)) return
            scheduleReopen("changed $id")
        }
    }

    private fun isOurInputDevice(id: Int): Boolean = try {
        isOurDevice(android.view.InputDevice.getDevice(id)?.name ?: "")
    } catch (_: Exception) { false }

    private fun scheduleReopen(why: String) {
        if (!visible || reopenScheduled) return
        reopenScheduled = true
        main.postDelayed({ reopenScheduled = false; reopenTarget(why) }, 700)
    }

    /** Re-resolves and reopens the target and points the existing engine at it. No window is touched. */
    private fun reopenTarget(why: String) {
        if (!visible) return
        val svc = Injector.current() ?: return
        if (prefs.padTo != Prefs.TO_DEVICE) return   // nothing local to reopen
        try {
            val err = openTarget(svc)
            if (err.isNotEmpty()) { Log.w(TAG, "reopen ($why): $err"); toast(err); return }
            val caps = Caps.fromJson(svc.targetCaps())
            val eng = engine
            if (eng == null) { show(); return }
            eng.rebind(LocalSink(svc), caps)
            overlay?.invalidatePad()
            Log.i(TAG, "reopened target ($why): ${builtInLabel(prefs.physicalName) ?: prefs.physicalName}")
        } catch (e: Exception) { Log.w(TAG, "reopen failed", e) }
    }

    // ---- pairing: the Thor's own "hold the button until it blinks" -------------------------------
    private val visibleTick = object : Runnable {
        override fun run() {
            val ov = overlay ?: return
            ov.updatePanel(panelState(ov))
            if (secondsVisible() > 0) main.postDelayed(this, 1000)
        }
    }

    private fun secondsVisible(): Int {
        // Findability belongs to the gamepad itself rather than to the adapter: there is no
        // system-wide discoverable mode to ask for, only what this pad chooses to say.
        return (btSink as? BleSink)?.secondsFindable() ?: 0
    }

    /** What the link badge and the log want to hear whenever the connection changes. */
    private fun linkNote(): (String) -> Unit = { msg ->
        main.post { refreshLinkBadge() }
        if (msg.isNotEmpty()) Log.i(TAG, "link: $msg")
    }

    /**
     * The pad a computer talks to, made now if it does not exist yet.
     *
     * Showing the pad creates one, but the panel can ask to be findable before that has happened,
     * and until this existed that case fell through to making the whole Thor discoverable instead.
     */
    private fun ensureBleSink(): BleSink? {
        (btSink as? BleSink)?.let { return it }
        if (btSink != null) return null
        return try {
            BleSink(this, bleIdentity(), linkNote(), prefs.rumble, prefs.plainButtons).also { s ->
                btSink = s
                s.open { err -> if (err.isNotEmpty()) main.post { toast(err) } }
            }
        } catch (e: Exception) { Log.w(TAG, "could not present a pad", e); null }
    }

    /** Machines the Thor is paired with, computers first since those are what this is for. */
    private fun bluetoothName(): String = try {
        getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name ?: "this device"
    } catch (e: Exception) { "this device" }

    /**
     * Machines offered as a destination: the ones paired from inside SidePad, plus whichever is
     * currently chosen so an existing setup is never dropped. The device's own pairing list is full
     * of headphones and other controllers, and none of those are somewhere to send presses.
     */
    private fun pairedHosts(): List<HostChoice> = try {
        val ours = prefs.pairedHostList.split(',').filter { it.isNotBlank() }.toSet() +
            setOfNotNull(prefs.btHost.ifEmpty { null })
        val a = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        (a?.bondedDevices ?: emptySet())
            .filter { it.address in ours }
            .map { HostChoice(it.address, it.name ?: it.address) }
    } catch (e: Exception) { emptyList() }

    /** Paired computers SidePad does not know about, offered on the Pair page to take on. */
    private fun adoptableHosts(): List<HostChoice> = try {
        val known = pairedHosts().map { it.address }.toSet()
        val a = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        (a?.bondedDevices ?: emptySet())
            .filter { it.address !in known &&
                it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER }
            .map { HostChoice(it.address, it.name ?: it.address) }
    } catch (e: Exception) { emptyList() }

    /**
     * While the Thor is visible, anything that finishes pairing did so because of what the user just
     * did here, so it is remembered as a destination. Nothing else adds to that list.
     */
    private var bondWatch: android.content.BroadcastReceiver? = null

    /**
     * Opens a window during which a machine that pairs is taken to be one the user meant to add.
     *
     * Only bonds formed here are offered as destinations, because the handheld's pairing list is
     * mostly headphones and other controllers and none of those are somewhere to send presses.
     * The window is what makes that judgement, not the listening — the listening never stops now,
     * so that a bond disappearing is noticed whenever it happens.
     */
    private fun watchForNewPairing(secs: Int) {
        adoptUntil = SystemClock.elapsedRealtime() + secs * 1000L + 5_000
    }

    /**
     * Keeps the panel honest about what is paired.
     *
     * The panel is drawn when the app changes something, and pairing is not something the app
     * changes — it happens in Android's settings or in a dialog, underneath. So a machine unpaired
     * elsewhere went on being offered as a destination, and one just paired did not appear until
     * the destination was switched away and back. Both were the panel showing a list nobody had
     * told it to look at again.
     */
    private fun watchBonds() {
        if (bondWatch != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                    android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                when (i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    android.bluetooth.BluetoothDevice.BOND_BONDED -> {
                        if (SystemClock.elapsedRealtime() < adoptUntil) {
                            prefs.rememberPairedHost(d.address)
                            Log.i(TAG, "paired from SidePad: ${d.address}")
                        }
                    }
                    android.bluetooth.BluetoothDevice.BOND_NONE -> {
                        prefs.forgetPairedHost(d.address)
                        Log.i(TAG, "no longer paired: ${d.address}")
                    }
                    else -> return
                }
                overlay?.let { ov -> main.post { ov.updatePanel(panelState(ov)) } }
            }
        }
        try {
            registerReceiver(r, android.content.IntentFilter(
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondWatch = r
        } catch (e: Exception) { Log.w(TAG, "bond watch", e) }
    }

    @Volatile private var adoptUntil = 0L

    private fun stopWatchingForPairing() {
        bondWatch?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        bondWatch = null
    }

    private fun panelState(ov: PadOverlay) = PanelState(ov.isShowing, prefs.shield, prefs.opacity, prefs.backdrop, ov.blurSupported,
        targets, prefs.physicalName, prefs.padSeparate,
        shizukuReady = Injector.state() == Injector.ShizukuState.READY, activeProfile = prefs.activePreset,
        remote = prefs.anyToMachine, hosts = pairedHosts(),
        physicalRemote = prefs.physicalTo == Prefs.TO_MACHINE, padRemote = prefs.padTo == Prefs.TO_MACHINE,
        hostAddress = prefs.btHost, hostConnected = btSink?.connected == true,
        padName = bluetoothName(), visibleFor = secondsVisible(), adoptable = adoptableHosts(),
        transport = prefs.btTransport, identity = prefs.btIdentity,
        edges = !ov.singleScreen)

    /** Applies a shield/islands switch that was chosen while the panel was open. */
    private fun applyDirty() {
        if (padDirty) { padDirty = false; if (visible) rebuildPad() }
    }
    private var targets: List<TargetChoice> = emptyList()   // controllers seen at the last probe

    /**
     * Lists the gamepad nodes and re-resolves the chosen controller by name, since a node number
     * changes when the Thor switches controller style or a Bluetooth pad reconnects.
     */
    /** Devices SidePad itself created. They are real to the kernel and meaningless as targets. */
    private fun isOurDevice(name: String) = name.contains("SidePad")

    private fun refreshTargets(svc: IInjector) {
        try {
            val devs = JSONObject(svc.probe()).getJSONArray("devices")
            val found = ArrayList<TargetChoice>()
            for (i in 0 until devs.length()) {
                val d = devs.getJSONObject(i)
                val name = d.getString("name")
                // Our own virtual pad is in this list because from the kernel's side it is a
                // controller like any other. It is not somewhere to send presses, though —
                // writing into it would be writing into ourselves — and it was being offered as
                // if it were, which reads as a controller that does nothing.
                if (d.optBoolean("gamepad", false) && !isOurDevice(name)) found.add(TargetChoice(name, d.getString("path")))
            }
            targets = found
            // The Thor's own pad keeps its identity across style changes even though its name changes.
            val byName = found.firstOrNull { it.name == prefs.physicalName }
                ?: if (isBuiltInName(prefs.physicalName)) found.firstOrNull { isBuiltInName(it.name) } else null
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
        watchBonds()
        prefs.guideSnapshot?.let { snap ->
            // A guide was interrupted (service killed, reboot): undo its temporary look now. The
            // guide turns the shield on for its own sake, so that goes back with the rest.
            val p = snap.split("|")
            if (p.size == 3) {
                prefs.shield = p[0].toBooleanStrictOrNull() ?: prefs.shield
                prefs.backdrop = p[1]
                prefs.opacity = p[2].toFloatOrNull() ?: prefs.opacity
            }
            prefs.guideSnapshot = null
            Log.i(TAG, "guide snapshot restored after interruption: $snap")
        }
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
            ACTION_STOP -> { userStopped = true; disarmKeepAlive(); hide(); stopSelf() }
            ACTION_PANEL -> showPanel(keepPage = false)
            ACTION_FOCUS_TOP -> returnFocusToTopScreen()
            ACTION_PROBE_BT -> dev.lbento.thorsidepad.inject.BtHidProbe.start(this)
            ACTION_PROBE_BT_SEND -> dev.lbento.thorsidepad.inject.BtHidProbe.press()
            ACTION_PROBE -> Injector.current()?.let { svc ->
                Thread { try { Log.i(TAG, "pointer probe:\n" + svc.probePointer(12_000)) }
                         catch (e: Exception) { Log.w(TAG, "probe failed", e) } }.start()
            }
            ACTION_GUIDE -> showGuide()
            // Debug only; see BleSink.tryDirectedConnect.
            ACTION_TRY_CONNECT -> (btSink as? BleSink)?.tryDirectedConnect(
                intent.getStringExtra("address").orEmpty(),
                intent.getStringExtra("auto") != "false")
            ACTION_TRY_RESET -> (btSink as? BleSink)?.endExperiment()
            ACTION_FORGET -> intent.getStringExtra("address")?.let { a ->
                if ((btSink as? BleSink)?.forgetMachine(a) == true) prefs.forgetPairedHost(a)
                overlay?.let { ov -> main.post { ov.updatePanel(panelState(ov)) } }
            }
            // START and anything unrecognised: come back as the pad was left. The watchdog uses
            // START, so a pad that was on screen when the app was taken is on screen again.
            else -> {
                if (prefs.padShown) show() else ensureCatcher()
                if (!prefs.guideShown) showGuide()
            }
        }
        // Every way in arms the watchdog: however the service came up, it is meant to stay up.
        armKeepAlive()
        return START_STICKY
    }

    // ---- staying up -----------------------------------------------------------------------------
    // The pad is a convenience that is meant to be there, so it is not left to the memory manager's
    // judgement. The shell process cannot be killed; it watches for our heartbeat and puts us back.
    // Only the Stop button disarms it, so quitting still means quitting.
    @Volatile private var armed = false
    /** Set only by the Stop button, so an unexpected death leaves the shell side alive to revive us. */
    @Volatile private var userStopped = false

    // Arming happens here rather than at start-up because the shell side is bound asynchronously and
    // is usually not there yet when the service starts. The beat arms it the moment it appears, and
    // re-arms if the connection is ever replaced.
    private val beat = object : Runnable {
        override fun run() {
            try {
                Injector.current()?.let { svc ->
                    if (!armed) { svc.keepAlive(packageName, GRACE_MS); armed = true }
                    svc.heartbeat()
                }
            } catch (e: Exception) { armed = false; Log.w(TAG, "keep-alive beat failed", e) }
            main.postDelayed(this, BEAT_MS)
        }
    }

    private fun armKeepAlive() { main.removeCallbacks(beat); main.post(beat) }

    private fun disarmKeepAlive() {
        main.removeCallbacks(beat); armed = false
        try { Injector.current()?.keepAlive(packageName, 0) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener) } catch (_: Exception) {}
        hide()
        stopImeWatch(); stopNowWatch(); stopWatchingForPairing()
        overlay?.tearDown()
        try { levelThread.quitSafely() } catch (_: Exception) {}
        running = false
        visible = false
        refreshBubble()
        // Only a deliberate Stop lets the shell process go. Dying any other way has to leave it
        // running, because it is the thing that puts the pad back.
        // The service is going; nothing is left to hold the gamepad up.
        btSink?.close(); btSink = null
        if (userStopped) try { Injector.disconnect(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toggle() { if (overlay?.isShowing == true) hide() else show() }

    private fun withInjector(block: (IInjector) -> Unit) {
        Injector.connect(this) { svc ->
            if (svc == null) {
                toast("Shizuku is not running or not authorised. Open SidePad to fix.")
            } else block(svc)
        }
    }

    /** Opens the configured target (a controller node or a virtual pad). Returns an error or "". */
    private fun openTarget(svc: IInjector): String {
        refreshTargets(svc)
        return if (prefs.padSeparate) {
            val abs = Catalog.virtualAbs
            svc.openVirtual("Thor SidePad", Catalog.virtualKeys,
                abs.map { it.first }.toIntArray(), abs.map { it.second }.toIntArray(), abs.map { it.third }.toIntArray())
        } else {
            if (prefs.physicalPath.isBlank()) "No controller found. Pull down and pick one once a controller is connected."
            else svc.openPhysical(prefs.physicalPath)
        }
    }

    /**
     * Puts the pad up.
     *
     * [keepPanel] is for the settings that have to rebuild the pad underneath an open panel.
     * Changing where presses go swaps the sink, which means a new engine, which means building
     * the pad again — and closing the panel to do that made a player reopen it after every such
     * change, for a reason that is entirely ours.
     */
    private fun show(keepPanel: Boolean = false) {
        // Sending to a machine asks nothing of Shizuku, so the pad opens without waiting for it. The
        // device sliders and the media units still need it and simply have nothing to show without.
        if (prefs.padTo == Prefs.TO_DEVICE) withInjector { svc -> showWith(svc, keepPanel) }
        else showWith(Injector.current(), keepPanel)
    }

    /**
     * The Bluetooth pad, built once and reused. Opening is asynchronous, so the pad goes up now
     * and the first presses simply do not land until the host answers; the state callback says so.
     */
    private fun openMachineSink(): PadTransport? {
        // A pad that went quiet waiting for somebody is being reached for now.
        (btSink as? BleSink)?.wake()
        btSink?.let { return it }
        val note = linkNote()
        return try {
            if (prefs.btTransport != Prefs.TRANSPORT_CLASSIC) {
                // A Low Energy pad does not dial a host; it makes itself findable and the host
                // comes to it. So there is no address to open with.
                BleSink(this, bleIdentity(), note, prefs.rumble, prefs.plainButtons).also { s ->
                    btSink = s
                    s.open { err -> if (err.isNotEmpty()) main.post { toast(err) } }
                }
            } else {
                BluetoothSink(this, note).also { s ->
                    btSink = s
                    s.open(prefs.btHost) { err -> if (err.isNotEmpty()) main.post { toast(err) } }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "could not present a pad", e); null }
    }

    private fun showWith(svc: IInjector?, keepPanel: Boolean = false) {
        run {
            try {
                val sink: PadSink
                val caps: Caps
                // The Bluetooth pad goes up if anything at all is headed for a machine — the
                // built-in controller, the on-screen buttons, or both. Which of them it carries
                // is decided separately below.
                val bt = if (prefs.anyToMachine) openMachineSink() else null
                if (prefs.padTo == Prefs.TO_MACHINE) {
                    if (bt == null) { toast("Could not present a pad"); return@run }
                    sink = bt; caps = BluetoothSink.CAPS
                } else {
                    if (svc == null) { toast("Shizuku is not ready"); return@run }
                    val err = openTarget(svc)
                    if (err.isNotEmpty()) { toast(err); return@run }
                    sink = LocalSink(svc); caps = Caps.fromJson(svc.targetCaps())
                }
                // The built-in controller is only taken over when it is the thing being sent
                // away. Left alone it keeps working as this handheld's own controller, which is
                // the whole point of being able to point the two at different places.
                if (prefs.physicalTo == Prefs.TO_MACHINE && bt != null) {
                    if (svc != null) startForwarding(bt, svc)
                    else Injector.connect(this) { late -> startForwarding(bt, late) }
                } else if (prefs.physicalTo != Prefs.TO_MACHINE) {
                    try { Injector.current()?.forwardStop() } catch (_: Exception) {}
                }
                engine?.shutdown()
                // Only the local destination can be reopened; a Bluetooth one reconnects on its own terms.
                val eng = PadEngine(sink, caps) {
                    if (prefs.padTo == Prefs.TO_DEVICE) main.post { scheduleReopen("write failed") }
                }
                engine = eng
                val ov = overlayOrCreate()
                if (!keepPanel) ov.removePanel()
                if (svc != null) { readLevels(svc); syncNowWatch() }
                if (keepPanel && ov.isPanelShowing) {
                    // The panel covers the screen, so the pad beneath it cannot be seen or
                    // touched: building it now would gain nothing and cost the one thing that
                    // matters. Windows stack in the order they are added, so new pad windows land
                    // above the panel, and the only way to put the panel back on top is to add it
                    // again — which blinks, because a window really is destroyed and recreated.
                    //
                    // So the pad waits. padDirty is the existing path for "rebuild when the panel
                    // closes", the engine above is already the new one, and the rebuild happens
                    // when there is something to look at.
                    padDirty = true
                } else {
                    ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.backdrop,
                        onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) }, levels = levels, onSlider = { c, l, f -> onSlider(c, l, f) },
                        pointer = if (Injector.current() != null) pointerSink else null)
                    // The shield catches the edge pulls itself; islands mode still needs the strips.
                    if (prefs.shield) ov.removeCatchers() else ensureCatcher()
                }
                visible = true
                prefs.padShown = true
                refreshBubble()
                main.postDelayed({ refreshLinkBadge() }, 1500)
                updateNotification()
                startImeWatch()
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                toast("Could not show pad: ${e.message}")
            }
        }
    }

    // ---- the trackpad's end of the wire ----

    @Volatile private var pointerOpen = false

    /**
     * Drives this device's own pointer for [TrackpadView].
     *
     * Opened on first touch rather than with the pad. Most layouts carry no trackpad, and a uinput
     * mouse that existed regardless would put a second pointer device in front of everything that
     * enumerates input — including our own controller probe, which is looking for exactly that.
     */
    private val pointerSink = object : PointerSink {
        private fun ready(): IInjector? {
            val svc = Injector.current() ?: return null
            if (!pointerOpen) {
                val why = try { svc.openPointer() } catch (e: Exception) {
                    Log.w(TAG, "the helper is too old for a pointer; restart SidePad", e); return null
                }
                if (!why.isNullOrEmpty()) { Log.w(TAG, "pointer refused: $why"); return null }
                pointerOpen = true
            }
            return svc
        }
        private inline fun send(what: String, body: (IInjector) -> Unit) {
            val svc = ready() ?: return
            try { body(svc) } catch (e: Exception) {
                // A dead helper leaves the flag lying; clear it so the next touch opens again.
                pointerOpen = false
                Log.w(TAG, "pointer $what failed", e)
            }
        }
        override fun move(dx: Int, dy: Int) = send("move") { it.pointerMove(dx, dy) }
        override fun button(code: Int, down: Boolean) = send("button") { it.pointerButton(code, down) }
        override fun wheel(clicks: Int) = send("wheel") { it.pointerWheel(clicks) }
    }

    /** Takes the virtual mouse away again, so it is not left in front of the system for nothing. */
    private fun closePointer() {
        if (!pointerOpen) return
        pointerOpen = false
        try { Injector.current()?.closePointer() } catch (e: Exception) { Log.w(TAG, "closing pointer", e) }
    }

    private var btSink: PadTransport? = null

    /**
     * Lets a Low Energy pad go, but only when it should: the destination is no longer a machine,
     * or the radio underneath has been changed. Hiding the pad is not a reason.
     */
    /**
     * Takes the Low Energy pad down, but only when it is genuinely finished with.
     *
     * Not when the presses are simply going somewhere else for a while. Playing on the handheld
     * and then pointing the pad back at the machine used to cost a pairing: switching destination
     * closed the server, and a rebuilt server is one a bonded host keeps the old layout for and
     * will not look at again. The same reasoning that leaves it standing when the pad is hidden
     * applies here — measured on 2026-09-21, hiding and showing keeps a working controller,
     * while anything that rebuilds the server does not.
     *
     * So it lives as long as the service does, and comes down only when the player stops the pad
     * or moves off this radio entirely.
     */
    private fun releaseBleIfIdle() {
        val stillWanted = prefs.btTransport == Prefs.TRANSPORT_LE && !userStopped
        if (stillWanted) return
        btSink?.close(); btSink = null
    }
    private var forwarder: ControllerForwarder? = null

    private fun bleIdentity(): BleSink.Identity =
        try { BleSink.Identity.valueOf(prefs.btIdentity) } catch (_: Exception) { BleSink.Identity.OWN }

    /**
     * Shows a badge on the pad only while the link to the machine is down. A connected pad says
     * nothing, which is right; a dropped one has to say something, because from the player's side
     * it looks identical to a working one until they wonder why nothing is happening.
     */
    /**
     * Keeps the handheld's processor running while a machine is actually holding the pad.
     *
     * With the screen off the Thor suspends, and a suspended handheld reads nothing from its own
     * controller: the link to the machine stays up, the pad looks connected, and not one press
     * arrives. Which is the state someone playing on another screen will be in most of the time,
     * since there is no reason to leave the handheld's displays lit.
     *
     * Held only while a machine is connected and the pad is up, so putting it down still lets the
     * Thor sleep properly.
     */
    private var awake: android.os.PowerManager.WakeLock? = null

    private fun holdAwake(on: Boolean) {
        try {
            if (on) {
                if (awake?.isHeld == true) return
                val pm = getSystemService(android.os.PowerManager::class.java) ?: return
                val w = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SidePad:controller")
                w.setReferenceCounted(false)
                w.acquire()
                awake = w
                Log.i(TAG, "keeping this device awake while the machine has the pad")
            } else {
                awake?.let { if (it.isHeld) { it.release(); Log.i(TAG, "letting this device sleep again") } }
                awake = null
            }
        } catch (e: Exception) { Log.w(TAG, "wake lock", e) }
    }

    /**
     * Applies the wake lock the keep-awake switch asks for.
     *
     * Split out of [refreshLinkBadge], which did this and raised the "not connected" badge in one
     * call. Flicking the switch therefore announced the state of the link, which is not what the
     * switch is about and startled anyone who touched it while playing.
     */
    private fun applyAwakeHold() {
        // Not a setting. A grabbed controller is invisible to Android, so while a machine has the
        // pad the screen times out as though nobody were there and the game is interrupted at the
        // thirty-minute mark. Nobody wants that, so nobody is asked.
        val want = prefs.physicalTo == Prefs.TO_MACHINE && visible && btSink?.connected == true
        // The lock keeps the processor alive and costs nothing. Holding the *screen* on is no
        // longer done here: pinning it for the whole session was a blunt answer to a narrow
        // problem, which is that a grabbed controller is invisible to Android. Reporting the
        // presses as user activity solves it where it happens — see ControllerForwarder — and
        // lets the handheld sleep normally the moment somebody stops playing.
        holdAwake(want)
    }

    private fun refreshLinkBadge() {
        applyAwakeHold()
        val ov = overlay ?: return
        // A link coming up or going down is the answer the pairing screen is waiting for, and
        // only the countdown was refreshing the panel — so a pairing that connected after the two
        // minutes had run out left the screen still saying nothing had happened.
        if (ov.isPanelShowing) ov.updatePanel(panelState(ov))
        val bt = btSink
        val remote = prefs.anyToMachine
        // Never while the panel is open. The badge is a nudge for somebody mid-game who has just
        // found the pad has stopped answering; in the panel it is an interruption, and on the
        // pairing screen it is nonsense — of course nothing is connected, that is what the screen
        // is for. Changing what the pad appears as rebuilds it, which is how it turned up there.
        if (!remote || !visible || bt == null || bt.connected || ov.isPanelShowing) {
            ov.showLinkBadge(null) {}
            return
        }
        val name = pairedHosts().firstOrNull { it.address == prefs.btHost }?.label ?: "the machine"
        ov.showLinkBadge("Not connected to $name \u2014 tap to try again") {
            bt.reconnect()
            main.postDelayed({ refreshLinkBadge() }, 2500)
        }
    }

    /**
     * Sends the Thor's own sticks and buttons to the machine as well as the on-screen ones. This is
     * the half that makes the handheld a controller rather than a touch panel, and it is the one
     * part of the remote destination that does need Shizuku, because only the shell may read a
     * controller's events. Without it the on-screen pad still works.
     *
     * The controller is taken exclusively, so a press drives the other machine and not this one.
     */
    @Volatile private var forwardingWanted = false

    private fun startForwarding(bt: PadTransport, svc: IInjector?, attempt: Int = 1) {
        if (svc == null) { Log.i(TAG, "no Shizuku: only the on-screen pad will reach the machine"); return }
        // Both the direct path and the late connection can arrive; only one may hold the controller.
        synchronized(this) { if (forwardingWanted) return; forwardingWanted = true }
        Thread {
            var held = false
            try {
                refreshTargets(svc)
                val calib = PadCalibration.parse(prefs.calibration)
                val path = prefs.physicalPath.ifEmpty { targets.firstOrNull()?.path.orEmpty() }
                if (path.isEmpty() && calib.isEmpty) { Log.w(TAG, "no controller to forward"); return@Thread }
                val f = ControllerForwarder(bt, calibration = calib, stillPlaying = {
                    // Only while the player has asked for it, and only for presses that Android
                    // will never see: these are being forwarded to another machine off a grabbed
                    // device, so without this the screen times out as though nobody were here.
                    try { svc.pokeUserActivity() } catch (e: Exception) { Log.w(TAG, "poke", e) }
                }, passThrough = { code, down ->
                    when {
                        code !in passThrough -> Unit
                        // Home is not handed back as a key. Injected from our own device it
                        // carries no screen with it, so Android sends every display home at once
                        // where the Thor's own handler sends only this one. It cannot be pressed
                        // on the real node either, the way the on-screen Home button manages it:
                        // we hold that node, so the press would be read straight back by our own
                        // reader and go round again.
                        //
                        // Nor can the home activity simply be started on a named display. Beacon
                        // is a single instance, so "am start --display 4" cannot place a copy
                        // there and hands the intent to the one already running instead —
                        // "delivered to currently running top-most instance", and both screens go
                        // home. A key event aimed at a display goes through that display's window
                        // manager, which is the same route the Back button here already takes.
                        code == Key.HOME -> if (down) shellAsync(
                            "input -d ${overlay?.displayId ?: 0} keyevent 3")
                        else -> try { svc.key(code, down) }
                            catch (e: Exception) { Log.w(TAG, "pass through", e) }
                    }
                })
                // A calibration names every device it needs; without one there is a single node
                // and the old call still does. The helper may also predate the multi-node call
                // entirely, in which case it throws and the single-node path is still correct
                // for the device the player picked.
                val caps: String? = if (!calib.isEmpty) {
                    val multi = try { svc.forwardStartMulti(calib.nodePaths.toTypedArray(), true, f) }
                                catch (e: Exception) { Log.w(TAG, "the helper is too old for multi-device reading; restart SidePad", e); null }
                    if (multi != null && !multi.startsWith("error")) { f.configureMulti(multi, calib); multi }
                    else multi
                } else {
                    svc.forwardStart(path, true, f)?.also { if (!it.startsWith("error")) f.configure(it) }
                }
                if (caps.isNullOrEmpty() || caps.startsWith("error")) {
                    Log.w(TAG, "forward refused: ${caps ?: "the helper is too old; restart SidePad"}")
                    return@Thread
                }
                // A refused grab is not a detail. The handheld keeps acting on every press as
                // well as the machine, which reads as the pad being broken, so say it plainly
                // rather than leaving it in a log nobody opens.
                if (!JSONObject(caps).optBoolean("grabbed", true)) main.post {
                    toast("Could not take the controller from this device \u2014 presses will reach both. Hide the pad and show it again.")
                }
                openPassThrough(svc, bt, caps)
                forwarder = f
                held = true
                Log.i(TAG, if (calib.isEmpty) "forwarding the controller from $path"
                           else "forwarding ${calib.nodePaths.size} calibrated device(s): ${calib.describe()}")
            } catch (e: Exception) { Log.w(TAG, "forward failed", e) }
            finally {
                if (!held) {
                    // Leaving the flag set would block every later attempt, so a failure has to
                    // put it back. Reinstalling the app is the common way to get here: the
                    // process is killed, Shizuku's binding with it, and the first try after it
                    // comes back is too early. Until this retried, the controller stayed with
                    // Android and every press went to whatever was on the top screen.
                    synchronized(this) { forwardingWanted = false }
                    if (attempt < 4 && visible) main.postDelayed({
                        if (visible && forwarder == null) {
                            Log.i(TAG, "controller not held yet; trying again (attempt ${attempt + 1})")
                            Injector.connect(this) { late -> startForwarding(bt, late, attempt + 1) }
                        }
                    }, 1500L * attempt)
                }
            }
        }.start()
    }

    /**
     * Opens somewhere to put the buttons the pad cannot carry.
     *
     * Grabbing the controller takes every button on it, including the ones no gamepad report has
     * a place for: Home, Back, the volume pair, recents. Those were simply destroyed — the
     * machine never saw them and neither did the handheld, so the Thor's volume keys stopped
     * working whenever the pad was running. This is a small keyboard-shaped device to hand them
     * back through. Buttons in the gamepad ranges are deliberately left out: those belong to the
     * machine, and echoing them here would drive Android at the same time.
     */
    @Volatile private var passThrough: Set<Int> = emptySet()

    /** Just for the log, so it says Home rather than 102. */
    private fun name(code: Int) = when (code) {
        Key.HOME -> "Home"
        Key.BACK -> "Back"
        114 -> "Volume down"
        115 -> "Volume up"
        580 -> "Recents"
        else -> "code $code"
    }

    private fun openPassThrough(svc: IInjector, bt: PadTransport, caps: String) {
        try {
            // Two shapes arrive here. One device gives {"keys":[..]}; several give
            // {"nodes":[{"keys":[..]},..]}, and every one of those devices has been grabbed, so
            // every one of their spare buttons needs somewhere to go. Reading only the first
            // shape would leave the others' Home and volume keys destroyed, which is the exact
            // fault this function exists to prevent.
            val root = JSONObject(caps)
            val declared = root.optJSONArray("keys") ?: JSONArray().also { merged ->
                val nodes = root.optJSONArray("nodes") ?: return
                val seen = HashSet<Int>()
                for (i in 0 until nodes.length()) {
                    val k = nodes.getJSONObject(i).optJSONArray("keys") ?: continue
                    for (j in 0 until k.length()) if (seen.add(k.getInt(j))) merged.put(k.getInt(j))
                }
            }
            val spare = ArrayList<Int>()
            var home = false
            for (i in 0 until declared.length()) {
                val code = declared.getInt(i)
                if (bt.handles(code)) continue
                if (code in 0x130..0x13F || code in 0x220..0x223) continue
                // Home is handled rather than handed back; it needs a screen naming. It still
                // belongs in the set the forwarder watches, just not on the device we inject to.
                if (code == Key.HOME) { home = true; continue }
                spare.add(code)
            }
            passThrough = if (home) spare.toSet() + Key.HOME else spare.toSet()
            if (spare.isEmpty()) return
            val err = svc.openVirtual("SidePad keys", spare.toIntArray(),
                IntArray(0), IntArray(0), IntArray(0))
            if (err.isNotEmpty()) { Log.w(TAG, "pass-through device: $err"); return }
            Log.i(TAG, "handing these back to Android: " +
                passThrough.sorted().joinToString { name(it) })
        } catch (e: Exception) { Log.w(TAG, "pass-through device", e) }
    }

    private fun stopForwarding() {
        holdAwake(false)
        passThrough = emptySet()
        synchronized(this) { if (!forwardingWanted) return; forwardingWanted = false }
        forwarder = null
        val svc = Injector.current() ?: return
        Thread { try { svc.forwardStop() } catch (_: Exception) {} }.start()
    }

    private fun hide() {
        prefs.padShown = false
        overlay?.showLinkBadge(null) {}
        stopForwarding()
        // A Low Energy gamepad is left standing. Tearing the server down and building it again
        // is not free the way it is over Classic: a host keeps the service layout of a peripheral
        // it has bonded with and will not look a second time, so a pad that is hidden and shown
        // comes back bonded, connected, and invisible to the host's input stack until it is made
        // to forget and pair afresh. Real controllers never rearrange themselves, which is why
        // real controllers do not have this. So it lives as long as the service does, and only
        // dropping the link or changing transport takes it down.
        if (btSink is BleSink) {
            releaseBleIfIdle()
        } else {
            btSink?.close(); btSink = null
        }
        stopImeWatch(); stopNowWatch(); imeSuspended = false
        overlay?.removeAll()
        engine?.shutdown(); engine = null
        closePointer()
        try { Injector.current()?.closeTarget() } catch (_: Exception) {}
        visible = false
        updateNotification()
        ensureCatcher()
        syncBubble()
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

    /**
     * Starts the invisible hand-off activity on the top screen through the shell user (a plain
     * start would be a blocked background launch). NO_USER_ACTION matters: without it a player
     * like YouTube treats the launch as the user leaving and drops into picture-in-picture.
     */
    private fun returnFocusToTopScreen() {
        val svc = Injector.current() ?: return
        Thread {
            try { svc.shell("am start -f 0x10050000 --display 0 -n $packageName/.FocusHandoffActivity") }
            catch (e: Exception) { Log.w(TAG, "focus handoff failed", e) }
        }.start()
    }

    /** Keeps the edge strips on the pad's screen whenever the shield is not covering it. */
    private fun ensureCatcher() {
        try { val ov = overlayOrCreate(); ov.showCatchers(onPullDown = { showPanel() }, onPullUp = { toggle() }, tracker = ov.pullTracker) } catch (e: Exception) { Log.w(TAG, "catcher failed", e) }
        syncBubble()
    }

    /**
     * Puts the floating button up, or takes it away, to match the setting.
     *
     * Called wherever the catchers are, because the two answer the same need by different means:
     * on a handheld whose pad has a screen to itself an edge swipe is free, and on one with a
     * single screen every edge belongs to Android and a button is the only way in.
     */
    /**
     * The shield, from the floating button rather than the panel.
     *
     * The panel defers this until it closes, because switching while it is open would rebuild the
     * pad underneath it for nothing. There is no panel here, so it takes effect at once, which is
     * the whole point of putting it on the button.
     */
    private fun toggleShield() {
        prefs.shield = !prefs.shield
        if (visible) rebuildPad()
        refreshBubble()
    }

    /** Keeps the floating button showing what is true, whatever changed it. */
    private fun refreshBubble() {
        overlay?.updateBubble(visible, prefs.physicalTo == Prefs.TO_MACHINE, prefs.shield)
    }

    private fun syncBubble() {
        val ov = overlay ?: return
        // Offered by default on a machine with one screen, where there are no edge gestures left
        // and this is the only thing on the pad's own surface that reaches the panel. Once only:
        // after that the choice is the player's, including having thrown it away.
        if (!prefs.bubbleDefaulted) {
            prefs.bubbleDefaulted = true
            if (ov.singleScreen) prefs.bubble = true
        }
        // The shield owns every touch on its screen, and on a handheld with only one screen the
        // edges belong to Android, so the button is the only way back to the panel. Dismissing it
        // while the shield is up is already refused — but nothing stopped the shield going up
        // after it had been dismissed, or the pad starting that way, and then there is no way in
        // at all. Locked a device out on 2026-09-21. So the shield brings it back.
        val trapped = ov.singleScreen && prefs.shield && visible
        if (!prefs.bubble && !trapped) { ov.removeBubble(); return }
        ov.showBubble(prefs.bubbleX, prefs.bubbleY,
            onMoved = { x, y -> prefs.bubbleX = x; prefs.bubbleY = y },
            onLongPress = { showPanel() },
            onTap = { toggle() },
            onShield = { toggleShield() },
            // With the shield up this button is the only way out of a screen that is entirely
            // ours, so it cannot be thrown away. Anywhere else Android is still reachable and
            // there is no reason to refuse.
            canDismiss = { !(visible && prefs.shield) },
            onDismiss = {
                // Thrown away while the pad is up in islands mode, the pad goes with it: the
                // button is the pad's handle, and leaving the pad behind with no handle would be
                // the same trap the shield case avoids.
                val takePadToo = visible && !prefs.shield
                prefs.bubble = false
                ov.removeBubble()
                if (takePadToo) hide()
                Log.i(TAG, "bubble thrown away" + if (takePadToo) "; pad went with it" else "")
            })
        refreshBubble()
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
                toast("Android blocked the launch and Shizuku is not running. Tap the SidePad notification to open the app.")
            }
        }, 1200)
    }

    /**
     * Home on the pad's own screen.
     *
     * Every other per-screen button here names the display it means: Back does on both screens,
     * and so does Home on the top one. This was the exception — it pressed the Thor's physical
     * Home key, which does not ask for a screen at all, it asks Mjolnir. Mjolnir's answer to one
     * press is to send BOTH screens home, so the button did something nobody asked for and there
     * was no setting on our side that could change it.
     *
     * Naming the display is what the other three already do. A secondary display is allowed to
     * refuse a home intent, though, and this cannot be tried without a Thor in hand, so a refusal
     * falls back to the old key press: still wrong, but no more wrong than before.
     */
    private fun homeOnPad() {
        val display = overlay?.displayId ?: 0
        val svc = Injector.current() ?: return
        Thread {
            val out = try {
                svc.shell("am start --display $display -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1")
            } catch (e: Exception) { Log.w(TAG, "home on the pad's screen failed", e); "" }
            if (out.contains("Error", true) || out.contains("Exception", true)) {
                Log.i(TAG, "display $display would not take a home intent; falling back to the Thor's own key")
                try { svc.pressKeyOn(prefs.physicalPath, Key.HOME, 60) } catch (_: Exception) {}
            }
        }.start()
    }

    /** Buttons that act on the pad or the device. Home/Back go through the shell user or the Thor's own key. */
    private fun onAction(code: Int) {
        val ov = overlay
        when (code) {
            dev.lbento.thorsidepad.inject.Action.SHIELD -> { prefs.shield = !prefs.shield; rebuildPad() }
            dev.lbento.thorsidepad.inject.Action.HOME_TOP -> shellAsync("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
            dev.lbento.thorsidepad.inject.Action.HOME_2ND -> homeOnPad()
            dev.lbento.thorsidepad.inject.Action.BACK_TOP -> shellAsync("input -d 0 keyevent 4")
            dev.lbento.thorsidepad.inject.Action.BACK_2ND -> shellAsync("input -d ${ov?.displayId ?: 0} keyevent 4")
            dev.lbento.thorsidepad.inject.Action.MEDIA_PREV -> mediaKey("previous")
            dev.lbento.thorsidepad.inject.Action.MEDIA_PLAY -> mediaKey("play-pause")
            dev.lbento.thorsidepad.inject.Action.MEDIA_NEXT -> mediaKey("next")
            dev.lbento.thorsidepad.inject.Action.VIDEO_BACK -> mediaSkipMs(-10_000)
            dev.lbento.thorsidepad.inject.Action.VIDEO_FWD -> mediaSkipMs(10_000)
            dev.lbento.thorsidepad.inject.Action.VIDEO_APP -> openPlayingApp()
        }
    }

    private fun mediaKey(action: String) {
        val svc = Injector.current()
        if (svc == null) { toast("Shizuku not ready"); return }
        Thread { try { svc.mediaKey(action) } catch (e: Exception) { Log.w(TAG, "media key failed", e) } }.start()
        resolveMediaTarget()    // whoever answered that key is what the track should move
    }

    private fun shellAsync(cmd: String) {
        val svc = Injector.current()
        if (svc == null) { toast("Shizuku not ready"); return }
        Thread { try { svc.shell(cmd) } catch (e: Exception) { Log.w(TAG, "shell failed", e) } }.start()
    }

    /** Current device levels for the sliders, read once when the pad shows. */
    private val levels = HashMap<Int, Float>()
    private fun readLevels(svc: IInjector) {
        try {
            val d = overlay?.displayId ?: 0
            svc.getBrightness(0).let { if (it >= 0f) levels[dev.lbento.thorsidepad.inject.Slider.BRIGHT_TOP] = it }
            svc.getBrightness(d).let { if (it >= 0f) levels[dev.lbento.thorsidepad.inject.Slider.BRIGHT_2ND] = it }
            levels[dev.lbento.thorsidepad.inject.Slider.BRIGHT_TOP]?.let { levels[dev.lbento.thorsidepad.inject.Slider.BRIGHT_BOTH] = it }
            svc.getVolume().let { if (it >= 0f) levels[dev.lbento.thorsidepad.inject.Slider.VOLUME] = it }
            svc.getVolume2nd().let { if (it >= 0f) levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_2ND] = it }
            levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] = levels[mediaVolumeTarget] ?: 0.5f
            resolveMediaTarget()
        } catch (e: Exception) { Log.w(TAG, "levels", e) }
    }

    /**
     * A slider moved. Levels go to the injector on one worker, and only the newest value per
     * slider is sent: a drag produces far more move events than the system can apply, and
     * queueing them all made the screen lag behind the thumb. Brightness uses the live
     * (temporary) path while dragging, which skips the system's ramp animation, and is
     * committed when the finger lifts ([final]).
     */
    private val levelThread by lazy { HandlerThread("sidepad-levels").apply { start() } }
    private val levelHandler by lazy { Handler(levelThread.looper) }
    private val pendingLevel = HashMap<Int, Pair<Float, Boolean>>()   // code -> (level, final)
    private val levelJobQueued = HashSet<Int>()
    private val lastApplied = HashMap<Int, Long>()

    private fun onSlider(code: Int, level: Float, final: Boolean) {
        if (code == dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA) {
            // Check which screen is playing once per drag, not on every move.
            if (!mediaDragging) { mediaDragging = true; resolveMediaTarget() }
            if (final) mediaDragging = false
        }
        val delay: Long
        synchronized(pendingLevel) {
            val had = pendingLevel[code]
            pendingLevel[code] = level to (final || had?.second == true)
            if (code in levelJobQueued) return      // a job is queued; it will send this newer value
            levelJobQueued.add(code)
            // The display service takes a while per change; feeding it faster than this only builds a backlog.
            val since = SystemClock.uptimeMillis() - (lastApplied[code] ?: 0L)
            delay = if (final) 0L else (LEVEL_INTERVAL_MS - since).coerceIn(0L, LEVEL_INTERVAL_MS)
        }
        levelHandler.postDelayed({
            val job = synchronized(pendingLevel) { levelJobQueued.remove(code); pendingLevel.remove(code) } ?: return@postDelayed
            synchronized(pendingLevel) { lastApplied[code] = SystemClock.uptimeMillis() }
            applyLevel(code, job.first, job.second)
        }, delay)
    }

    /**
     * Which volume the media unit's track moves. The Thor scales the audio of apps on the second
     * screen separately from the main stream, so a track that always moved the main stream would
     * turn down the wrong app whenever the player sits on the pad's own screen.
     */
    @Volatile private var mediaVolumeTarget = dev.lbento.thorsidepad.inject.Slider.VOLUME
    @Volatile private var mediaDragging = false
    @Volatile private var nowDuration = 0L
    @Volatile private var nowWatching = false
    private var nowThread: Thread? = null

    /**
     * Keeps the media and video units showing what is actually playing. It runs only while such a
     * unit is on the pad, and reads the session directly rather than through the shell.
     */
    private fun startNowWatch() {
        if (nowWatching) return
        nowWatching = true
        val t = Thread({
            while (nowWatching) {
                Injector.current()?.let { svc ->
                    try {
                        val parts = svc.mediaInfo().split('\u0001')
                        val pkg = parts.getOrNull(0) ?: ""
                        val playing = parts.getOrNull(1)?.toIntOrNull() == 3
                        val pos = parts.getOrNull(2)?.toLongOrNull() ?: -1L
                        val dur = parts.getOrNull(3)?.toLongOrNull() ?: -1L
                        val label = parts.getOrNull(4) ?: pkg
                        val title = parts.getOrNull(5).orEmpty()
                        val sub = parts.getOrNull(6).orEmpty()
                        nowDuration = dur
                        main.post {
                            overlay?.updateVideo(playing, pos, dur, if (pkg.isEmpty()) "" else label, pkg, title, sub)
                        }
                    } catch (e: Exception) { Log.w(TAG, "now playing", e) }
                }
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }, "sidepad-now")
        t.isDaemon = true; nowThread = t; t.start()
    }

    private fun stopNowWatch() { nowWatching = false; nowThread?.interrupt(); nowThread = null }

    /** Only worth watching the session while something on the pad shows it. */
    private fun syncNowWatch() {
        val wants = PadLayout.fromJson(prefs.layoutJson).buttons.any {
            dev.lbento.thorsidepad.inject.isMediaUnit(it.code) || dev.lbento.thorsidepad.inject.isVideoUnit(it.code)
        }
        if (wants) startNowWatch() else stopNowWatch()
    }

    /**
     * Brings the playing app forward on the screen its window is already on, so a player on the
     * top screen comes back there rather than being moved.
     */
    private fun openPlayingApp() {
        val pkg = overlay?.video?.pkg.orEmpty()
        if (pkg.isEmpty()) return
        shellAsync(
            "d=\$(dumpsys window displays 2>/dev/null | awk -v p=\"$pkg\" " +
            "'/Display: mDisplayId=/{d=\$0; sub(/.*mDisplayId=/,\"\",d); sub(/ .*/,\"\",d)} index(\$0,p){print d; exit}' 2>/dev/null); " +
            "c=\$(cmd package resolve-activity --brief $pkg | tail -1); " +
            "[ -n \"\$c\" ] && am start --display \${d:-0} -n \"\$c\""
        )
    }

    private fun mediaSkipMs(deltaMs: Int) {
        val svc = Injector.current()
        if (svc == null) { toast("Shizuku not ready"); return }
        Thread { try { svc.mediaSkip(deltaMs) } catch (e: Exception) { Log.w(TAG, "skip failed", e) } }.start()
    }

    /** Asks the system which app holds the media keys and which screen its window is on. */
    private fun resolveMediaTarget() {
        val svc = Injector.current() ?: return
        Thread {
            try {
                val out = svc.shell(
                    "p=\$(dumpsys media_session 2>/dev/null | grep -m1 'Media button session is' | sed -E 's|.*is ([^/]+)/.*|\\1|'); " +
                    "[ -n \"\$p\" ] && dumpsys window displays 2>/dev/null | awk -v p=\"\$p\" " +
                    "'/Display: mDisplayId=/{d=\$0; sub(/.*mDisplayId=/,\"\",d); sub(/ .*/,\"\",d)} index(\$0,p){print d; exit}' 2>/dev/null"
                )
                val display = out.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
                val target = if (display != null && display == overlay?.displayId)
                    dev.lbento.thorsidepad.inject.Slider.VOLUME_2ND else dev.lbento.thorsidepad.inject.Slider.VOLUME
                mediaVolumeTarget = target
                main.post {
                    // Never while the finger is down: resolving takes a shell round trip, so an
                    // answer can arrive mid-drag and would snatch the thumb back.
                    if (!mediaDragging) levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] = levels[target] ?: 0.5f
                    overlay?.invalidatePad()
                }
            } catch (e: Exception) { Log.w(TAG, "media target", e) }
        }.start()
    }

    private fun applyLevel(code: Int, level: Float, final: Boolean) {
        val svc = Injector.current() ?: return
        val second = overlay?.displayId ?: 0
        // The media unit's track stands for whichever volume the playing app actually uses.
        val code = if (code == dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA) mediaVolumeTarget else code
        // The media track stands in for one of the two real volumes, so what it just set is now
        // that volume's level. Without this the cached one stays at whatever it was when the pad
        // came up, and the next time the target is resolved the thumb springs back to it.
        if (code == dev.lbento.thorsidepad.inject.Slider.VOLUME || code == dev.lbento.thorsidepad.inject.Slider.VOLUME_2ND) main.post { levels[code] = level }
        // setTemporaryBrightness exists on the Thor but never reaches its display controller and
        // stalls the real change for seconds, so every update is a plain setBrightness.
        fun bright(display: Int) = svc.setBrightness(display, level)
        try {
            when (code) {
                dev.lbento.thorsidepad.inject.Slider.VIDEO_SEEK ->
                    if (nowDuration > 0L) svc.mediaSeek((level * nowDuration).toLong())
                dev.lbento.thorsidepad.inject.Slider.VOLUME -> svc.setVolume(level)
                dev.lbento.thorsidepad.inject.Slider.VOLUME_2ND -> svc.setVolume2nd(level)
                dev.lbento.thorsidepad.inject.Slider.BRIGHT_TOP -> bright(0)
                dev.lbento.thorsidepad.inject.Slider.BRIGHT_2ND -> bright(second)
                dev.lbento.thorsidepad.inject.Slider.BRIGHT_BOTH -> { bright(0); bright(second) }
            }
        } catch (e: Exception) { Log.w(TAG, "slider failed", e) }
    }

    /** Re-lays the pad windows with the current looks, keeping the injector target open. */
    private fun rebuildPad() {
        val eng = engine; val ov = overlay
        if (!visible || eng == null || ov == null) { if (visible) show(); return }
        try {
            Injector.current()?.let { readLevels(it) }
            ov.showPlay(PadLayout.fromJson(prefs.layoutJson), prefs.opacity, eng, prefs.shield, prefs.backdrop,
                onGesture = { g -> onGesture(g) }, onAction = { code -> onAction(code) }, levels = levels, onSlider = { c, l, f -> onSlider(c, l, f) },
                pointer = if (Injector.current() != null) pointerSink else null)
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
        // Steps 1 to 3 teach the two edge pulls. On one screen those do not exist, so there is
        // nothing to rehearse and the guide goes straight to the closing card, which names the
        // bubble instead. Teaching a gesture that will not answer is worse than teaching none.
        val first = if (PadOverlay.isSingleScreen(this)) 4 else 1
        guideStep = first
        if (visible) hide() else ensureCatcher()
        main.postDelayed({ if (guideStep == first) showGuideStep(first) }, 600)
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
        main.removeCallbacks(visibleTick)
        if (guideStep == 2) main.postDelayed({ if (guideStep == 2) showGuideStep(2) }, 1000)
    }

    private fun showPanel(keepPage: Boolean = false) = openPanel(dragged = false, keepPage = keepPage)

    private fun openPanel(dragged: Boolean, keepPage: Boolean = false) {
        if (!keepPage) ControlPanel.page = ControlPanel.Page.MAIN
        val ov = try { overlayOrCreate() } catch (e: Exception) { toast("No second screen: ${e.message}"); return }
        Injector.current()?.let { refreshTargets(it) }
        ov.showPanel(panelState(ov), dragged = dragged, shieldOn = prefs.shield, backdrop = prefs.backdrop, actions = object : PanelActions {
            override fun setTarget(choice: TargetChoice?) {
                prefs.padTo = Prefs.TO_DEVICE
                if (choice == null) prefs.padSeparate = true
                else { prefs.padSeparate = false; prefs.physicalName = choice.name; prefs.physicalPath = choice.path }
                if (visible) reopenTarget("target chosen")   // reopens the injector; the windows stay
                ov.updatePanel(panelState(ov))
            }
            override fun pickProfile() {
                ov.showProfilePicker(prefs.activePreset) { p ->
                    prefs.activePreset = p.name
                    prefs.layoutJson = p.layout.toJson()
                    ov.removePanel(); padDirty = false; show(); panelClosed()
                }
            }
            override fun editLayout() { ov.removePanel(); padDirty = false; edit() }
            override fun setShield(on: Boolean) { prefs.shield = on; padDirty = true; refreshBubble(); ov.updatePanel(panelState(ov)); ov.updatePanelLook(prefs.shield, prefs.backdrop) }
            override fun setOpacity(value: Float) { prefs.opacity = value; ov.updateLooks(prefs.opacity, prefs.backdrop) }
            override fun setBackdrop(value: String) { prefs.backdrop = value; ov.updateLooks(prefs.opacity, prefs.backdrop); ov.updatePanel(panelState(ov)); ov.updatePanelLook(prefs.shield, prefs.backdrop) }
            override fun setDestination(address: String) {
                // Only the built-in controller. Where the on-screen buttons go is its own
                // question now, and answering both from here is what made the old single setting
                // unable to express the useful combination.
                prefs.physicalTo = if (address.isEmpty()) Prefs.TO_DEVICE else Prefs.TO_MACHINE
                refreshBubble()
                // Going back to this device changes where presses go, not which machine is known:
                // forgetting it here would drop it out of the list and it would have to be paired
                // again for no reason.
                if (address.isNotEmpty()) { prefs.btHost = address; prefs.rememberPairedHost(address) }
                ControlPanel.page = ControlPanel.Page.MAIN
                // Rebuilt underneath the panel rather than instead of it. This does change which
                // sink the engine writes to, so the pad genuinely has to be built again — but
                // that is our business, not a reason to make someone reopen the panel.
                if (visible) { hide(); show(keepPanel = true) }
                ov.updatePanel(panelState(ov))
            }

            /**
             * Changes the radio presses are carried over.
             *
             * The link has to come all the way down. To a machine these are two different devices
             * — different address kind, different bond — so one cannot be handed over to the
             * other, and the machine has to pair again with whichever is chosen. Saying that
             * plainly on the page is kinder than letting someone discover it.
             */
            override fun setPadTo(machine: Boolean) {
                val want = if (machine) Prefs.TO_MACHINE else Prefs.TO_DEVICE
                if (prefs.padTo == want) return
                prefs.padTo = want
                ControlPanel.page = ControlPanel.Page.MAIN
                // A different sink for the on-screen buttons means a different engine, so the pad
                // is built again — under the open panel, like every other destination change.
                if (visible) { hide(); show(keepPanel = true) }
                ov.updatePanel(panelState(ov))
            }

            override fun setTransport(value: String) {
                if (prefs.btTransport == value && prefs.anyToMachine) return
                prefs.btTransport = value
                // Choosing a radio is choosing to send somewhere, so this no longer depends on a
                // destination having been picked first on another page.
                prefs.physicalTo = Prefs.TO_MACHINE; prefs.padTo = Prefs.TO_MACHINE
                btSink?.close(); btSink = null
                if (visible) { hide(); show() }
                ov.updatePanel(panelState(ov))
                toast(if (value == Prefs.TRANSPORT_LE)
                          "Now a Low Energy gamepad \u2014 pair with it again from the machine"
                      else "Back to Classic Bluetooth")
            }

            /** What a machine is told the pad is. Also a different device, so also a re-pair. */
            override fun setIdentity(value: String) {
                if (prefs.btIdentity == value) return
                prefs.btIdentity = value
                // Changing what we claim to be can change which radio that claim has to arrive
                // on, so the transport moves with it rather than being left behind on whatever
                // the previous identity needed.
                prefs.btTransport = Prefs.transportFor(value)
                btSink?.close(); btSink = null
                if (visible) { hide(); show(keepPanel = true) }
                ov.updatePanel(panelState(ov))
                toast("Pair with it again from the machine")
            }

            /** The page itself is the pairing screen now; nothing to do but show it. */
            override fun pairMachine() {}

            /**
             * Points the pad at a machine over Low Energy without one being chosen, because over
             * Low Energy there is nothing to choose: the machine finds the pad. Asking for a host
             * first, the way Classic does, made this unreachable for anyone who had not already
             * paired one — and pairing one needs this set.
             */
            override fun pairNewMachine(forPad: Boolean) {
                // Only the set of controls this was asked for. Sending both was what made the
                // on-screen buttons follow the built-in controller to a machine uninvited, which
                // is the one combination the split exists to keep apart.
                if (forPad) prefs.padTo = Prefs.TO_MACHINE else prefs.physicalTo = Prefs.TO_MACHINE
                // The radio follows what we are appearing as, rather than being fixed here. Both
                // identities are Low Energy today, so nothing changes yet — but a PlayStation or
                // Nintendo profile would be Classic, and that belongs to the profile rather than
                // to this button.
                prefs.btTransport = Prefs.transportFor(prefs.btIdentity)
                btSink?.close(); btSink = null
                // Rebuilt under the open panel, not instead of it — the same reason setDestination
                // does: this is reached from a button on the panel and closing it would be a
                // reply to the wrong question.
                if (visible) { hide(); show(keepPanel = true) }
                ControlPanel.page = ControlPanel.Page.PAIRING
                ov.updatePanel(panelState(ov))
            }

            override fun makeVisible() {
                // Findability belongs to the pad, never to the handheld. Pressing this before the
                // pad had been shown used to ask Android to make the whole Thor discoverable,
                // which offered a phone over Classic where a gamepad was wanted: the computer
                // listed "Thor" with nothing to pair with.
                val ble = ensureBleSink()
                if (ble == null) { toast("The pad is busy talking to a machine."); return }
                ble.makeFindable(120)
                // Whatever bonds inside this window is the machine the user just walked over to.
                // Nothing else records a pairing, so without opening it here the list of machines
                // stays empty however many times they pair.
                watchForNewPairing(120)
                // The countdown is only honest if something redraws it.
                main.removeCallbacks(visibleTick); main.post(visibleTick)
            }

            override fun adoptMachine(address: String) {
                prefs.rememberPairedHost(address)
                ControlPanel.page = ControlPanel.Page.DESTINATION
                ov.updatePanel(panelState(ov))
            }

            override fun stopService() {
                ov.removePanel(); padDirty = false
                userStopped = true; disarmKeepAlive(); hide(); stopSelf()
            }
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "SidePad", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pad)
            .setContentTitle("SidePad")
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
        private const val LEVEL_INTERVAL_MS = 60L
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
        const val ACTION_TRY_CONNECT = "dev.lbento.thorsidepad.TRY_CONNECT"
        const val ACTION_TRY_RESET = "dev.lbento.thorsidepad.TRY_RESET"
        const val ACTION_FORGET = "dev.lbento.thorsidepad.FORGET"
        const val ACTION_START = "dev.lbento.thorsidepad.START"
        /**
         * How often the service says it is still there, and how long silence is allowed. A beat is a
         * single cheap call into a process that is already running, so it can be frequent; the grace
         * is four missed beats, which puts the pad back within about half a minute of losing it.
         */
        private const val BEAT_MS = 5_000L
        private const val GRACE_MS = 20_000
        /** Development only: see IInjector.probePointer. */
        const val ACTION_PROBE = "dev.lbento.thorsidepad.PROBE"
        /** Development only: see BtHidProbe. */
        const val ACTION_PROBE_BT = "dev.lbento.thorsidepad.PROBE_BT"
        const val ACTION_PROBE_BT_SEND = "dev.lbento.thorsidepad.PROBE_BT_SEND"

        @Volatile var running = false
        @Volatile var visible = false

        fun send(ctx: Context, action: String, address: String? = null, auto: String? = null) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            if (address != null) i.putExtra("address", address)
            if (auto != null) i.putExtra("auto", auto)
            try {
                // Android only lets a background app start a foreground service in narrow cases,
                // and a broadcast from Tasker is not one of them. Once the service is up, a plain
                // start is always allowed, so only the first one has to go the restricted way.
                if (running) ctx.startService(i) else ctx.startForegroundService(i)
            } catch (e: Exception) {
                Log.w(TAG, "could not deliver $action", e)
            }
        }
    }
}
