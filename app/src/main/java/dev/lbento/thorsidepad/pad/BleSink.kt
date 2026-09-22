package dev.lbento.thorsidepad.pad

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import dev.lbento.thorsidepad.inject.Abs
import dev.lbento.thorsidepad.inject.Btn
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The pad as a Bluetooth Low Energy gamepad.
 *
 * The Classic sink next door is the one that works today and is not going anywhere: Windows and
 * Android both want it, and it is what every host already pairs with. This exists for one reason
 * that has nothing to do with speed — measured, the two carry a gamepad about equally well. Over
 * Classic the vendor and product numbers a host reads belong to the handheld's Bluetooth chip, are
 * published by Android before this app exists, and cannot be changed without root. Over Low Energy
 * they live in a characteristic written here. That is the difference: every handheld running this
 * app presents the same identity, so one mapping serves all of them instead of one per radio.
 *
 * **Pairing is the hard part and the reason an earlier attempt at this failed.** A host will find
 * an advertisement, connect, read whatever is offered without encryption, and leave again, and
 * nothing about the report descriptor or the identity changes that. What it wants is a bond. A
 * GATT server cannot wait to be asked: [createBondWith] reaches out as soon as a host connects,
 * and the HID characteristics refuse to be read without encryption so that a host which ignores
 * the invitation gets nowhere rather than getting a half-working device.
 */
class BleSink(
    private val ctx: Context,
    private val identity: Identity = Identity.OWN,
    private val onState: (String) -> Unit = {},
    /** Whether to tell a host there are motors here. See Prefs.rumble for what it costs. */
    private val rumble: Boolean = true,
    /** Send buttons counted straight through, for a host that does not know the identity. */
    private val plainButtons: Boolean = false,
) : PadTransport {

    /**
     * What a host is told this is.
     *
     * Not cosmetic: a host looks these numbers up and decides from them alone whether it has a
     * layout, so the same presses land differently depending on what is claimed here. [OWN] is an
     * identifier from the pool meant for open projects and is borrowed from nobody, at the price
     * of a host having no built-in layout for it. The alternatives exist because an Xbox report
     * has no room for a trackpad or media keys, so what this should claim depends on what the pad
     * is currently offering, and that is a decision for the panel rather than for this file.
     */
    enum class Identity(val vendor: Int, val product: Int, val version: Int, val label: String) {
        OWN(0x1209, 0x5350, 0x0100, "SidePad"),


        /**
         * The Xbox Series X|S controller, numbers and report map both, taken off a real one.
         *
         * The pad has been claiming `045e:02e0`, which is the model 1708 — the Xbox One S
         * generation. Steam holds a profile for the Series controller and none for that, and it
         * shows: measured in Eastward on 2026-09-21, a genuine Series pad maps correctly under
         * Steam Input while this one is read positionally, its bits taken as button numbers.
         *
         * Version 0x0520 is not decoration. A program keying its controller database on the SDL
         * GUID uses vendor, product and version together, and being wrong by two bytes has
         * already cost this project a mapping once.
         *
         * This is not expected to fix native play. A real Series controller is routed to
         * IOHIDEventDummyService exactly as the 1708 is, and needs Steam Input in Eastward too.
         * Apple's GameController path is for controllers that are not Microsoft's at all.
         */
        XBOX_SERIES(0x045E, 0x0B13, 0x0520, "Xbox-compatible"),
    }

    @Volatile override var connected = false; private set
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var subscribed = false
    private var server: BluetoothGattServer? = null
    private var advertiser: AdvertiseCallback? = null
    @Volatile private var wantAdvertising = false
    /** While this is in the future the pad is findable by anyone; otherwise only by its own host. */
    @Volatile private var findableUntil = 0L
    private var reportChar: BluetoothGattCharacteristic? = null
    /** The second report, when the shape has one: the button in the middle. */
    private var systemChar: BluetoothGattCharacteristic? = null
    private var lastSystem: ByteArray? = null
    /** Where a host writes to ask the pad to buzz. */
    private var rumbleChar: BluetoothGattCharacteristic? = null

    /**
     * What this pad tells a host it is, and therefore what it sends.
     *
     * A host that recognises the name holds us to the shape that name implies, not to the one we
     * describe, so the descriptor and the bytes have to be chosen together. Claiming to be
     * Xbox-compatible while sending our own nine-byte layout produced a pad a Mac accepted and
     * then read as two sticks jammed into a corner.
     */
    private val shape: ReportShape =
        // Every identity but OWN sends the Xbox report; they differ in the numbers they carry
        // and, for the Series, in sending that controller's own map rather than the 1708's.
        if (identity == Identity.XBOX_SERIES) XboxShape(rumble, plainButtons) else StandardShape()

    /** Whatever this identity's own report holds; see ReportShape.caps. */
    override val caps: Caps get() = shape.caps

    /**
     * Which bonded hosts have asked to be sent reports, by address.
     *
     * A bonded host asks once and expects the answer kept: the specification puts that on the
     * peripheral, not the host. Measured on 2026-09-19 after a cold restart, the Mac reconnected
     * on its bond, remembered it had subscribed, and never wrote the request again — while this
     * side had reset to unsubscribed and sat waiting for it. Guessing instead of remembering was
     * tried earlier the same evening and was worse: it sent presses to a host that had not asked.
     * So it is stored when a host writes it and restored when that host comes back.
     */
    private val subscriptions = ctx.getSharedPreferences("sidepad_ble", Context.MODE_PRIVATE)

    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var draining = false
    private val pool = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: ScheduledFuture<*>? = null
    /** How many more times to repeat the current report before falling silent. */
    private val repeatsLeft = java.util.concurrent.atomic.AtomicInteger(0)
    private val dropped = java.util.concurrent.atomic.AtomicInteger(0)
    private var changedChar: BluetoothGattCharacteristic? = null
    private var goodbye: java.util.concurrent.CountDownLatch? = null
    private val sendLock = Any()
    @Volatile private var inFlight = false
    /** The last frame handed to the radio, and the one-shot that says it again if nothing follows. */
    @Volatile private var lastFrame: ByteArray? = null
    private var settle: ScheduledFuture<*>? = null
    private var sleeper: ScheduledFuture<*>? = null
    /** Set only by the directed-connection experiment; keeps the pad off the air while it runs. */
    @Volatile private var experimenting = false

    // ---- opening and closing -------------------------------------------------------------------

    /**
     * Seconds of open findability left, for the panel to count down. Zero means the pad is not
     * announcing itself to anything but the machine it already belongs to.
     */
    fun secondsFindable(): Int {
        val left = findableUntil - android.os.SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /**
     * Announces the pad to anything listening, for a while.
     *
     * The rest of the time it keeps quiet, which is the whole point. A controller is findable
     * because someone held its pairing button, not because it is switched on, and a pad that
     * shouts its name at every device in the room for as long as it is running is both rude and
     * how a machine's list fills up with entries nobody can clear. Once bonded it still has to
     * advertise — a Low Energy peripheral cannot dial its host, it can only be findable and wait —
     * but it does that without giving its name, so scanners see something anonymous rather than a
     * gamepad they will never be offered.
     */
    fun makeFindable(seconds: Int) {
        findableUntil = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        Log.i(TAG, "findable by anything for $seconds seconds")
        // Asking to be found means asking to be found by something else. Every controller lets go
        // of whatever has it when its pairing button is held, and for good reason: a pad that
        // stays attached to one machine cannot be picked up by the next, and the person holding it
        // has already said which they want by pressing the button. Letting go also puts the
        // advertisement back up, since this will not call out while a machine has it.
        host?.let { d ->
            Log.i(TAG, "letting go of ${d.address} so something else can take the pad")
            try { server?.cancelConnection(d) } catch (_: Exception) {}
            host = null; connected = false; subscribed = false
            stopHeartbeat()
            onState("Not connected")
        }
        // Only if there is nothing on the air. Restarting an advertisement that is already
        // running changes the address for no reason, and the settings no longer differ.
        if (advertiser == null) restartAdvertising() else Log.i(TAG, "already on the air; keeping this address")
        ticker.schedule({
            // Nothing to restart when the window closes any more. The advertisement outside a
            // findable window is now identical to the one inside it, so switching back would
            // only cost another address. What the window still decides is whether the pad lets
            // go of a machine that has it, which happens above, not here.
            if (secondsFindable() == 0) Log.i(TAG, "no longer findable; still reachable")
        }, seconds.toLong() + 1, TimeUnit.SECONDS)
    }

    /**
     * Puts the advertisement back up, but only when there is nobody on the other end.
     *
     * Advertising while a machine is already attached is not merely wasteful, it is how the pad
     * arrives twice. Seen on 2026-09-19: the findability timer fired a second after a host
     * connected, the advertisement went back up, the same Mac connected again, and from then on
     * there were two of us in its device list. Two pads from one radio is also enough to wedge
     * `gamecontrollerd`, after which every application that enumerates controllers hangs at launch
     * — which is a far worse thing to inflict than a missing advertisement.
     */
    /**
     * Forgets every machine bonded to the pad, because this server is not the one they met.
     *
     * Called once as the server comes up. Quietly does nothing when there is nothing to forget,
     * which is the ordinary case for a pad that has never been paired.
     */
    private fun dropStalePairings() {
        val remembered = dev.lbento.thorsidepad.Prefs(ctx).pairedHostList
            .split(',').filter { it.isNotBlank() }
        if (remembered.isEmpty()) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (addr in remembered) {
            val d = try { adapter.getRemoteDevice(addr) } catch (_: Exception) { continue }
            if (!isBonded(d)) continue
            // Ask it to go away first: a machine holding an open link to a server that no longer
            // knows it will sit there claiming to be connected until something says otherwise.
            try { server?.cancelConnection(d) } catch (_: Exception) {}
            if (forgetMachine(addr)) Log.i(TAG, "let go of $addr: this is not the server it paired with")
        }
    }

    private fun restartAdvertising() {
        if (!wantAdvertising) return
        stopAdvertising()
        if (host != null) { Log.i(TAG, "a machine already has us; staying quiet"); return }
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.let { startAdvertising(it) }
    }

    /**
     * Stands the gamepad up and starts advertising. [report] is called once, with an empty string
     * when the pad is up and a sentence to show the user when it is not.
     */
    fun open(report: (String) -> Unit) {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val done: (String) -> Unit = { m -> if (answered.compareAndSet(false, true)) report(m) }
        val mgr = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) { done("Bluetooth is off"); return }
        try {
            val srv = mgr.openGattServer(ctx, callback)
            if (srv == null) { done("Could not present a gamepad"); return }
            server = srv
            addServices(srv)
            watchBonding()
            // Anything this server inherited from a previous life is already dead.
            //
            // A host keeps the attribute layout of a peripheral it has bonded with and does not
            // look a second time. This server is a new one — the app was stopped, reinstalled or
            // killed — so a machine that was paired with the old one holds the connection open
            // and sees nothing. Measured repeatedly on 2026-09-21: connected on the Mac, bonded
            // on the handheld, no controller anywhere, every time the app restarted.
            //
            // Hiding and showing the pad is the case that does survive, because the server is
            // deliberately left standing then. This is the case that cannot.
            //
            // So let go of it here rather than leaving a pairing that looks fine and does
            // nothing. The machine's own record still has to be forgotten by hand; this at least
            // means the two sides agree, and the fresh pairing that follows always works.
            dropStalePairings()
            if (!startAdvertising(adapter)) { done("Could not advertise"); return }
            done("")
        } catch (e: SecurityException) {
            done("Bluetooth permission refused")
        } catch (e: Exception) {
            Log.w(TAG, "open", e); done("Could not present a gamepad")
        }
    }

    /**
     * Starts advertising again.
     *
     * There is nothing to dial: a Low Energy peripheral cannot reach out to a host, it can only
     * make itself findable and wait. So this puts the advertisement back up, which is the whole of
     * what this side can do about a dropped link.
     */
    override fun reconnect() {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        stopAdvertising()
        startAdvertising(adapter)
    }

    override fun close() {
        wantAdvertising = false
        findableUntil = 0L
        stopHeartbeat()
        try { ctx.unregisterReceiver(bondWatcher) } catch (_: Exception) {}
        try {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            advertiser?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        } catch (_: Exception) {}
        // Say goodbye properly and wait for it to land. Cancelling and closing on the next line
        // tore the server down before the disconnection had gone out, so a machine saw the link
        // vanish rather than end — and a host that loses a device that way keeps the dead one in
        // its list instead of removing it.
        try {
            host?.let { d ->
                val done = java.util.concurrent.CountDownLatch(1)
                goodbye = done
                server?.cancelConnection(d)
                done.await(300, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {}
        goodbye = null
        try { server?.close() } catch (_: Exception) {}
        try { ctx.getSystemService(android.os.Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        server = null; advertiser = null; reportChar = null; systemChar = null; rumbleChar = null
        host = null; connected = false; subscribed = false
    }

    // ---- the services --------------------------------------------------------------------------

    private fun addServices(srv: BluetoothGattServer) {
        // Generic Access is not ours to publish. Claiming the gamepad appearance there would
        // have earned the fast connection parameters macOS reserves for real pads — its log
        // checks appearanceValue and gives an 8BitDo answering 0x03C4 a fifteen-millisecond
        // interval where we answer 0x0 and wait longer. But the stack owns that service and
        // never answers the callback for it: measured on 2026-09-20, every addService after it
        // ran two seconds late waiting on a reply that does not come, the server took eight
        // seconds to stand up, and a machine that connected in the meantime found no gamepad at
        // all. The appearance stays out of reach.

        // Generic Attribute is not ours to publish either, and nor was Generic Access before
        // it. Both are reserved to the Bluetooth stack, which never answers the callback for
        // them: measured on 2026-09-20, each cost the full two-second wait and delayed every
        // service behind it, so the gamepad itself did not exist until eight seconds in and a
        // machine connecting before that found nothing to subscribe to. Service Changed would
        // have been the polite way to tell a bonded host its cached copy of us had moved — every
        // host we met logged "not listening for service changes", which is what a service that
        // was never really registered looks like from the outside.

        // Device information, holding the identity. Readable without encryption on purpose: a host
        // that cannot see who we are before bonding has no reason to want to bond.
        val dis = BluetoothGattService(uuid(DEVICE_INFO), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        dis.addCharacteristic(BluetoothGattCharacteristic(uuid(PNP_ID),
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        srv.addService(dis)
        awaitService()

        // Battery. Not decoration: a host that expects the profile to be complete may walk away
        // without it, and it costs one characteristic.
        val bat = BluetoothGattService(uuid(BATTERY), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(uuid(BATTERY_LEVEL),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)
        level.addDescriptor(BluetoothGattDescriptor(uuid(CCCD),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        bat.addCharacteristic(level)
        srv.addService(bat)
        awaitService()

        // The gamepad itself. Everything here wants encryption, which is what makes a host bond
        // rather than helping itself to a device that then never works.
        val hid = BluetoothGattService(uuid(HID_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hid.addCharacteristic(BluetoothGattCharacteristic(uuid(HID_INFO),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        hid.addCharacteristic(BluetoothGattCharacteristic(uuid(REPORT_MAP),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        val rep = BluetoothGattCharacteristic(uuid(REPORT),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        rep.addDescriptor(BluetoothGattDescriptor(uuid(CCCD),
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        // Says which report this is and that it travels device to host. Without it a host has a
        // descriptor full of reports and no way to tell which characteristic carries which.
        rep.addDescriptor(BluetoothGattDescriptor(uuid(REPORT_REF),
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED))
        hid.addCharacteristic(rep)
        reportChar = rep
        // A shape with a second report needs a second characteristic to carry it: one report per
        // characteristic is how this profile works, and the reference descriptor on each is what
        // tells a host which is which.
        if (shape.systemSnapshot() != null) {
            val sys = BluetoothGattCharacteristic(uuid(REPORT),
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
            sys.addDescriptor(BluetoothGattDescriptor(uuid(CCCD),
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
            sys.addDescriptor(BluetoothGattDescriptor(uuid(REPORT_REF),
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED))
            hid.addCharacteristic(sys)
            systemChar = sys
        }
        hid.addCharacteristic(BluetoothGattCharacteristic(uuid(PROTOCOL_MODE),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE))
        // A host that means to rumble the pad needs somewhere to write it. The shape describes the
        // report; this is the characteristic that carries it, and its reference descriptor says
        // report three and that it travels host to device rather than the other way.
        if (shape.rumbleFrom(ByteArray(8)) != null) {
            val out = BluetoothGattCharacteristic(uuid(REPORT),
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
            out.addDescriptor(BluetoothGattDescriptor(uuid(REPORT_REF),
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED))
            hid.addCharacteristic(out)
            rumbleChar = out
        }
        hid.addCharacteristic(BluetoothGattCharacteristic(uuid(CONTROL_POINT),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        srv.addService(hid)
        awaitService()
    }

    /** Services must be added one at a time; the next may not start until the last has answered. */
    private val serviceAdded = java.util.concurrent.ArrayBlockingQueue<Int>(4)
    private fun awaitService() {
        try { serviceAdded.poll(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    private fun startAdvertising(adapter: BluetoothAdapter): Boolean {
        val le = adapter.bluetoothLeAdvertiser ?: return false
        // Never while a machine already has us, unless it is a pairing window and we are meant to
        // be findable by something else. restartAdvertising checked this; open() did not, and a
        // host that reconnected while the server was still coming up left the pad calling out
        // through the whole session. A machine sees that and reattaches the instant the link
        // drops — so pressing Disconnect on a Mac undid itself inside a second, every time.
        if (host != null && secondsFindable() == 0) {
            // Wanting to advertise and being able to are different things: the intent has to be
            // recorded even when this turn is declined, or resumeAdvertising will decide the pad
            // never wanted to be on air and it goes silent for good once the machine lets go.
            wantAdvertising = true
            Log.i(TAG, "a machine already has us; staying quiet")
            return true
        }
        // Off the air unless somebody is looking.
        //
        // The pad used to advertise around the clock so a machine could come back to it. It
        // cannot: a host keeps the attribute layout of a peripheral it has bonded with, this
        // server is rebuilt whenever the app is, and the pairing is thrown away on
        // disconnection anyway. So the quiet advertisement led to nothing and cost an address
        // every time it restarted — and Android hands out a fresh random one on every start.
        //
        // That is where a machine's list fills up with rows nobody can clear. Measured
        // 2026-09-21: seven addresses in half an hour, seven rows in the Mac's menu, and one
        // pairing actually stored. Advertising only while findable makes it one per pairing.
        if (secondsFindable() == 0) {
            wantAdvertising = true
            Log.i(TAG, "not findable; off the air until asked")
            return true
        }
        // One setting, always, because changing it costs an identity.
        //
        // These used to be loud while findable and quiet otherwise. Since the advertisement
        // carries the same name and the same gamepad service either way, that switch changed
        // nothing a host can see — but it meant tearing the advertisement down and putting it
        // back up, and Android hands out a fresh random address every time advertising starts.
        // Measured on 2026-09-21: six different addresses in half an hour, where a rotation
        // alone would give two. Each one arrives at a host as another device, which is where the
        // unnamed rows in a Mac's list come from.
        //
        // Balanced sits between the two it replaces: found promptly enough to pair by hand, and
        // not a packet every hundred milliseconds around the clock.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Always named, and always carrying the gamepad service.
        //
        // Both halves were once left out when the pad was not in a findable window, on the
        // reasoning that a machine which already knows us finds us by address. That reasoning has
        // been wrong twice.
        //
        // Dropping the service uuid made the pad invisible: macOS reconnects by scanning for
        // 0x1812 and nothing else, and across nineteen quiet minutes on 2026-09-20 it scanned
        // every twenty seconds and never saw a gamepad, so pressing Connect fell through to
        // Classic and handed back a phone.
        //
        // Dropping the name made it anonymous instead, which is its own mess. Android hands out
        // a fresh random address every quarter of an hour, so once the pad became visible again
        // every rotation arrived at the host as a brand new nameless device. The Mac filled up
        // with unnamed "Bluetooth Device" rows nobody can clear — one per rotation, all of them
        // this pad.
        //
        // A name costs two bytes plus its length in an advertisement with thirty-one to spend,
        // and flags and one 16-bit uuid take seven of them. It fits. Where it does not, the
        // host is told at least that a gamepad is here, which is the half that cannot be spared.
        fun advertData(withName: Boolean) = AdvertiseData.Builder()
            .setIncludeDeviceName(withName)
            .addServiceUuid(ParcelUuid(uuid(HID_SERVICE)))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) {
                Log.i(TAG, "findable as ${identity.label} " +
                    "(${hex(identity.vendor)}:${hex(identity.product)})")
            }
            override fun onStartFailure(code: Int) {
                // A long device name is the one thing here that can overflow the packet. Rather
                // than go silent, drop the name and keep the gamepad service, which is what a
                // host actually needs to find us.
                if (code == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    Log.w(TAG, "advertisement too large with the name in it; going without")
                    try { le.startAdvertising(settings, advertData(false), this) }
                    catch (e: Exception) { Log.w(TAG, "nameless retry refused", e) }
                } else Log.w(TAG, "advertising refused, code $code")
            }
        }
        advertiser = cb
        wantAdvertising = true
        le.startAdvertising(settings, advertData(true), cb)
        armSleep()
        return true
    }

    private fun stopAdvertising() {
        val cb = advertiser ?: return
        try {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            Log.i(TAG, "stopped calling out")
        } catch (e: Exception) { Log.w(TAG, "stopAdvertising", e) }
        advertiser = null
    }

    /**
     * Stops calling out after a long enough spell with nobody listening.
     *
     * A controller left on a shelf goes to sleep; this one used to advertise until its battery
     * gave out. Sleeping is also what finally makes a machine's Disconnect mean something — a Mac
     * reattaches to any bonded pad it sees advertising, so while the pad never stops, the request
     * is undone within seconds. Trying instead to guess from the disconnection status which
     * departures were deliberate does not work, and left the pad unreachable.
     */
    private fun armSleep() {
        sleeper?.cancel(false)
        sleeper = ticker.schedule({
            if (host == null && advertiser != null) {
                Log.i(TAG, "nobody has picked the pad up; going quiet until it is wanted")
                stopAdvertising()
                onState("Not connected")
            }
        }, SLEEP_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Asks the stack for one specific machine, with nothing on the air for anyone else to find.
     *
     * The question this answers: can a peripheral choose which of several paired machines gets it?
     * Advertising cannot — every bonded machine resolves the same identity and the quickest one
     * wins. BluetoothGattServer.connect names a peer instead, but it is overwhelmingly used from
     * the central side and vendor stacks differ on whether a peripheral may use it at all.
     *
     * Not wired to anything a user can press. Driven over adb while the answer is unknown.
     */
    fun tryDirectedConnect(address: String, auto: Boolean = true) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val target = try { adapter.getRemoteDevice(address) } catch (e: Exception) {
            Log.w(TAG, "no such machine: $address", e); return
        }
        experimenting = true
        sleeper?.cancel(false)
        host?.let { d ->
            Log.i(TAG, "experiment: letting go of ${d.address}")
            try { server?.cancelConnection(d) } catch (_: Exception) {}
        }
        stopAdvertising()
        Log.i(TAG, "experiment: off the air, asking the stack for $address")
        try {
            val ok = server?.connect(target, auto)
            Log.i(TAG, "experiment: connect($address, auto=$auto) returned $ok; waiting to see who arrives")
        } catch (e: Exception) { Log.w(TAG, "experiment: connect refused", e) }
    }

    /**
     * Drops a machine's keys, so the pad stops belonging to it.
     *
     * The point of being able to do this from here rather than from Android's settings: a pad
     * bonded to one machine only cannot be taken by another, which is the one reliable way to
     * decide where it goes. Choosing between several bonded machines is not possible — they all
     * resolve the same identity and the quickest awake one wins.
     *
     * Only this side forgets. Whether the machine notices and offers to pair again, or sits there
     * failing with keys that no longer match, is the machine's business and differs between them.
     */
    fun forgetMachine(address: String): Boolean {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) {
            Log.w(TAG, "no such machine: $address", e); return false
        }
        if (host?.address == address) {
            Log.i(TAG, "letting go of $address before forgetting it")
            try { server?.cancelConnection(device) } catch (_: Exception) {}
            host = null; connected = false; subscribed = false
            stopHeartbeat()
        }
        subscriptions.edit().remove(address).remove("sc:$address").remove("fp:$address").apply()
        // removeBond is not in the public API, so it is asked for by name.
        return try {
            val m = BluetoothDevice::class.java.getMethod("removeBond")
            val ok = m.invoke(device) as? Boolean ?: false
            Log.i(TAG, "forgetting $address: $ok")
            onState("Not connected")
            // Clearing host above means the disconnection callback skips its own block, and the
            // line that puts the advertisement back up lives there — so it has to happen here or
            // the pad goes silent the moment it is unpaired, with no way back but a hide and show.
            ticker.schedule({ resumeAdvertising() }, QUIET_AFTER_MS, TimeUnit.MILLISECONDS)
            ok
        } catch (e: Exception) { Log.w(TAG, "could not forget $address", e); false }
    }

    /** Ends the experiment and puts the pad back on the air. */
    fun endExperiment() {
        experimenting = false
        Log.i(TAG, "experiment: over, calling out again")
        reconnect()
    }

    /** Back on air after sleeping, because somebody reached for the pad again. */
    fun wake() {
        if (host != null) return
        resumeAdvertising()
    }

    private fun resumeAdvertising() {
        if (experimenting) { Log.i(TAG, "staying off the air: a directed connection is being tried"); return }
        if (!wantAdvertising || advertiser != null || host != null) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        startAdvertising(adapter)
    }

    // ---- bonding -------------------------------------------------------------------------------

    /**
     * Asks a host to pair the moment it connects.
     *
     * A peripheral is usually expected to wait: the host reads something encrypted, is told it may
     * not, and starts pairing itself. Measured against a Mac on 2026-09-19, that host never took
     * the hint — it connected, read what was in the clear, and hung up forty seconds later without
     * ever trying. Asking first costs nothing when the host would have asked anyway.
     */
    /**
     * Asks the machine to talk to us more often than it offered.
     *
     * A Low Energy link only carries anything at a rendezvous the two radios agreed on, and the
     * machine picks the spacing. macOS offered thirty milliseconds, measured 2026-09-22 —
     * "interval 24, LSTO 72" — which is about thirty-three chances a second to report a stick,
     * against the four to fifteen milliseconds a real controller runs at. The queue fills at that
     * rate and reports are thrown away rather than sent late, which is what stick lag is.
     *
     * Nothing here had ever asked for anything, so thirty milliseconds was simply accepted. A
     * peripheral cannot set the spacing — only the machine can — but it can ask, and both ways of
     * asking are tried:
     *
     * The direct one names the numbers but is behind BLUETOOTH_PRIVILEGED, which is signature
     * level, so it is expected to fail here and is attempted first only because it costs nothing.
     * The second opens a client connection over the link that already exists and asks for high
     * priority, which Android turns into the same request with its own numbers.
     *
     * Whether the machine agrees is the machine's business. Both outcomes are logged, because the
     * interval it settles on is the number this was built to move.
     */
    private fun askForAFasterLink(device: BluetoothDevice) {
        try {
            val m = BluetoothDevice::class.java.getMethod("requestLeConnectionUpdate",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            // Units of 1.25 ms: 6 = 7.5 ms, the shortest the specification allows, through 12 = 15 ms.
            // No skipped rendezvous, and a five-second timeout instead of the 720 ms macOS chose,
            // which is the specification's minimum and drops the link after 24 missed events.
            val ok = m.invoke(device, 6, 12, 0, 500, 0, 0) as? Boolean ?: false
            Log.i(TAG, "asked ${device.address} for 7.5-15ms directly: $ok")
            if (ok) return
        } catch (e: Exception) {
            Log.i(TAG, "cannot name the interval (${e.javaClass.simpleName}); asking by priority instead")
        }
        try {
            val g = device.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, state: Int) {
                    if (state != BluetoothProfile.STATE_CONNECTED) return
                    val ok = try {
                        gatt.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Exception) { false }
                    Log.i(TAG, "asked ${device.address} for a high-priority link: $ok")
                }
            }, BluetoothDevice.TRANSPORT_LE)
            // Held only long enough for the request to go out. Closing it does not take the link
            // down: the machine opened that, and this was a second client riding on it.
            ticker.schedule({ try { g?.close() } catch (_: Exception) {} }, 4, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "could not ask for a faster link", e)
        }
    }

    private fun createBondWith(device: BluetoothDevice) {
        try {
            Log.i(TAG, "asking ${device.address} to pair over Low Energy")
            // Which radio the bond is made over is not a detail. Asked without saying, Android
            // picks for itself, and for a machine it already knows over Classic it picks Classic
            // — measured on 2026-09-19, the bond came out BR/EDR every time while the pad was
            // talking Low Energy. A Classic bond carries no Low Energy identity key, so the
            // machine cannot tell it is the same pad next time: a Low Energy address is a
            // disposable one, and recognising it across sessions is exactly what that key is for.
            // Hence five entries called Thor in the machine's list, none of which it could keep.
            // The method that says which radio is not in the public API, so it is asked for by
            // name and the plain one is the fallback.
            val asked = try {
                val m = BluetoothDevice::class.java.getMethod("createBond", Int::class.javaPrimitiveType)
                m.invoke(device, BluetoothDevice.TRANSPORT_LE) as? Boolean ?: false
            } catch (e: Exception) {
                Log.i(TAG, "could not name the radio to pair over (${e.javaClass.simpleName}); asking plainly")
                device.createBond()
            }
            if (!asked) Log.i(TAG, "no new bond was made (one may already exist)")
        } catch (e: SecurityException) { Log.w(TAG, "no permission to pair", e) }
    }

    private fun isBonded(d: BluetoothDevice): Boolean =
        try { d.bondState == BluetoothDevice.BOND_BONDED } catch (_: SecurityException) { false }

    private val bondWatcher = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "bonded to ${d.address}")
                    if (host?.address == d.address) subscribed = true
                    onState("")
                }
                BluetoothDevice.BOND_NONE -> Log.i(TAG, "pairing with ${d.address} did not complete")
            }
        }
    }

    private fun watchBonding() {
        try {
            ctx.registerReceiver(bondWatcher, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        } catch (e: Exception) { Log.w(TAG, "bond watch", e) }
    }

    // ---- serving the host ----------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            serviceAdded.offer(status)
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "service ${service?.uuid} refused: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // One machine at a time. A pad is a thing you hold; there is no sense in which it
                // is being used by two computers at once, and serving a second connection puts a
                // second copy of this pad in somebody's device list.
                // One machine at a time, and now that the advertisement stays up through a
                // pairing window, that includes a second link from the machine which already has
                // us. Two links from one radio is how the pad arrives twice in a device list.
                val already = host
                if (already != null) {
                    Log.w(TAG, "${device.address} also wants the pad; ${already.address} has it")
                    try { server?.cancelConnection(device) } catch (_: Exception) {}
                    return
                }
                host = device; connected = true
                sleeper?.cancel(false)
                Log.i(TAG, "${device.address} connected")
                // Outside a pairing window a controller stops calling out the moment a machine
                // takes it, and so should this: the machine that has us cannot use it, every
                // other device in the room is shown a gamepad it will never be offered, and each
                // session leaves another entry in their lists that nobody asked for.
                //
                // Inside a pairing window it must not stop. Android hands out a fresh random
                // address every time advertising starts, so stopping here and starting again
                // when a failed pairing drops the link moves the pad: the host retries against an
                // address that no longer answers, and another "Thor" nobody can clear is left in
                // its list. A real controller holds one address for the whole window, and this is
                // as near to that as an app is allowed to get.
                if (secondsFindable() == 0) stopAdvertising()
                // A bond only means this host has been here before. Whether it is listening is a
                // separate question and only it can answer: it says so by subscribing.
                //
                // Nor is it something to remember from last time. It was, and the remembered
                // answer goes stale the moment the machine rebuilds its session — after which
                // the pad fires reports at a hundred a second into nothing. Measured on
                // 2026-09-20: macOS answered every one with "received an indication on handle
                // 0x0087 but no session is subscribed", dropped them all, and hung up about
                // sixteen seconds later, again and again. Nothing here noticed, because from
                // this side a link that is up and a link that is listening look identical.
                //
                // So: wait to be asked — unless nothing has moved since this host last asked.
                //
                // The specification puts remembering on the peripheral, not the host, and macOS
                // follows it: a bonded machine reconnects and expects reports to resume without
                // subscribing again. This pad did not remember, so the machine waited for
                // reports and the pad waited to be asked, and every reconnection since has
                // needed the pairing thrown away and rebuilt. That is the cost of the caution
                // above, and it has been paid on every test for two days.
                //
                // The caution is still right where the attribute table has changed, because then
                // the machine's cached handles are wrong and reports go nowhere. But that is
                // exactly what the fingerprint below already detects — same identity, same
                // descriptor, same build. When it matches, the machine's session is as valid as
                // it was when it subscribed, and resuming is what the specification asks for.
                // When it does not, nothing is assumed and the old behaviour stands.
                val known = subscriptions.getBoolean(device.address, false)
                val sameLayout = subscriptions.getInt("fp:${device.address}", 0) == layoutFingerprint()
                if (known && sameLayout) {
                    subscribed = true
                    Log.i(TAG, "${device.address} subscribed last time and nothing has moved; resuming")
                    onState("")
                }
                if (!isBonded(device)) {
                    // Never met, or bonded over Classic only. Asking for a Low Energy bond
                    // covers both; one that already exists is refused harmlessly.
                    createBondWith(device)
                }
                askForAFasterLink(device)
                startHeartbeat()
                announceLayoutIfChanged(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                goodbye?.countDown()
                Log.i(TAG, "${device.address} went away (status $status)")
                if (host?.address == device.address) {
                    host = null; connected = false; subscribed = false
                    stopHeartbeat()
                    // Always go back on air, whatever the reason. Telling a deliberate
                    // Disconnect from a link that simply failed was tried on 2026-09-20 by
                    // reading the status, and it cannot be done: Android reports both as plain
                    // success. Staying quiet on that guess left the pad unreachable for twenty
                    // minutes after an ordinary drop, which is far worse than a Disconnect that
                    // does not stick. What makes a Disconnect mean something is sleeping below,
                    // not guessing here.
                    //
                    // Not instantly, though: a machine needs a moment to put the old device away,
                    // and the 358ms it used to take was not enough.
                    // Let go of the machine as it lets go of us.
                    //
                    // A pairing that cannot be reconnected is worse than none: it looks like a
                    // working pairing and behaves like a broken pad. macOS files a handheld as a
                    // Mobile Phone, because the Class of Device comes from the Classic radio, so
                    // every reconnection takes the phone route — Classic, SDP, no HID, stop. The
                    // pairing survives and the controller does not. That is not fixable from
                    // here: it was traced to the handheld having a Classic radio at all, which a
                    // real controller does not (2026-09-21).
                    //
                    // So pairing again is how this pad reconnects, everywhere, deliberately.
                    // A host where reconnection would have worked loses a little convenience;
                    // everyone gets one rule instead of behaviour that depends on which machine
                    // is at the other end and which switch was found.
                    val addr = device.address
                    ticker.schedule({
                        if (host == null && forgetMachine(addr)) {
                            Log.i(TAG, "let go of $addr; it will have to be paired again")
                            onState("Pair again to use the pad")
                        }
                    }, QUIET_AFTER_MS, TimeUnit.MILLISECONDS)
                    ticker.schedule({ resumeAdvertising() }, QUIET_AFTER_MS, TimeUnit.MILLISECONDS)
                    onState("Not connected")
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int,
                offset: Int, ch: BluetoothGattCharacteristic?) {
            val value = when (ch?.uuid) {
                uuid(PNP_ID) -> pnpId()
                uuid(HID_INFO) -> HID_INFORMATION
                uuid(REPORT_MAP) -> shape.descriptor
                uuid(REPORT) -> if (ch === systemChar) (shape.systemSnapshot() ?: ByteArray(1))
                                else shape.snapshot()
                uuid(PROTOCOL_MODE) -> byteArrayOf(REPORT_PROTOCOL)
                uuid(APPEARANCE) -> GAMEPAD_APPEARANCE
                uuid(BATTERY_LEVEL) -> byteArrayOf(100)
                else -> ByteArray(0)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int,
                offset: Int, d: BluetoothGattDescriptor?) {
            val value = when (d?.uuid) {
                // Report one, and it travels from us to the host. The one in the report map.
                uuid(REPORT_REF) -> when {
                    d.characteristic === systemChar -> byteArrayOf(SYSTEM_REPORT_ID, INPUT_REPORT)
                    d.characteristic === rumbleChar -> byteArrayOf(RUMBLE_REPORT_ID, OUTPUT_REPORT)
                    else -> byteArrayOf(REPORT_ID, INPUT_REPORT)
                }
                uuid(CCCD) -> when {
                    d.characteristic === changedChar ->
                        if (device != null && subscriptions.getBoolean("sc:${device.address}", false))
                            INDICATE_ON else NOTIFY_OFF
                    subscribed -> NOTIFY_ON
                    else -> NOTIFY_OFF
                }
                else -> ByteArray(0)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int,
                d: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray?) {
            if (d?.uuid == uuid(CCCD) && d.characteristic === changedChar) {
                // The spec asks a server to remember this one across a bonded reconnection, and
                // Android's remembers nothing, so it is written down here.
                val on = value != null && value.isNotEmpty() && value[0].toInt() and 0x03 != 0
                device?.let { subscriptions.edit().putBoolean("sc:${it.address}", on).apply() }
                // Worth saying out loud: without this subscription the pad cannot tell this host
                // its services moved, and the next install poisons that host's cache again.
                Log.i(TAG, if (on) "${device?.address} will listen for service changes"
                           else "${device?.address} is not listening for service changes")
            }
            if (d?.uuid == uuid(CCCD) && d.characteristic?.uuid == uuid(REPORT)) {
                subscribed = value != null && value.isNotEmpty() && value[0].toInt() and 0x01 != 0
                Log.i(TAG, if (subscribed) "the host is taking reports" else "the host stopped taking reports")
                // Record which layout it subscribed to, so the next connection can tell whether
                // resuming is safe. Without this a first-time subscriber has nothing to compare
                // against and would be made to subscribe again on every visit.
                device?.let {
                    subscriptions.edit()
                        .putBoolean(it.address, subscribed)
                        .putInt("fp:${it.address}", layoutFingerprint())
                        .apply()
                }
                if (subscribed) onState("")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int,
                ch: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray?) {
            if (ch === rumbleChar && value != null) shape.rumbleFrom(value)?.let { buzz(it) }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            synchronized(sendLock) { inFlight = false; (sendLock as Object).notifyAll() }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.i(TAG, "the host asked for $mtu bytes a packet")
        }
    }

    /**
     * Buzzes the handheld as hard as a host asked for.
     *
     * A rumble instruction says how hard but not really for how long — a game sends them
     * continuously while something is shaking and stops sending when it stops. So each one runs
     * for a little longer than the gap between them and is replaced by the next, which keeps a
     * long rumble smooth and lets a forgotten one die on its own rather than buzzing forever.
     */
    private fun buzz(amplitude: Int) {
        try {
            val v = ctx.getSystemService(android.os.Vibrator::class.java) ?: return
            if (amplitude <= 0) { v.cancel(); return }
            v.vibrate(android.os.VibrationEffect.createOneShot(
                RUMBLE_MS, amplitude.coerceIn(1, 255)))
        } catch (e: Exception) { Log.w(TAG, "rumble", e) }
    }

    /** Answers a read, handing back only the part from [offset] on, as a long read expects. */
    private fun respond(device: BluetoothDevice?, requestId: Int, offset: Int, value: ByteArray) {
        val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice) }
        catch (e: Exception) { Log.w(TAG, "respond", e) }
    }

    private fun pnpId(): ByteArray = byteArrayOf(
        0x02,                                       // the numbers are USB-IF's kind
        (identity.vendor and 0xFF).toByte(), ((identity.vendor shr 8) and 0xFF).toByte(),
        (identity.product and 0xFF).toByte(), ((identity.product shr 8) and 0xFF).toByte(),
        (identity.version and 0xFF).toByte(), ((identity.version shr 8) and 0xFF).toByte(),
    )

    // ---- the pad's side ------------------------------------------------------------------------

    override fun setKey(code: Int, down: Boolean) {
        shape.setKey(code, down)
        sendSystemIfChanged()
    }

    /**
     * Sends the second report when it changes.
     *
     * It is not worth the queue the pad's own report gets: one bit, pressed rarely, and nothing
     * else is competing for the radio when it moves.
     */
    private fun sendSystemIfChanged() {
        val c = systemChar ?: return
        val now = shape.systemSnapshot() ?: return
        if (lastSystem != null && now.contentEquals(lastSystem)) return
        lastSystem = now
        if (!subscribed) return
        val d = host ?: return
        try {
            @Suppress("DEPRECATION")
            c.value = now
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(d, c, false)
        } catch (e: Exception) { Log.w(TAG, "system report", e) }
    }

    override fun handles(code: Int): Boolean = shape.handles(code)

    override fun setAbs(code: Int, value: Int) = shape.setAbs(code, value)

    override fun sync(): Int {
        // Something moved, so say it now and again a few times in case a packet goes missing.
        repeatsLeft.set(REPEATS)
        return send()
    }

    override fun key(code: Int, down: Boolean): Int { setKey(code, down); return send() }
    override fun abs(code: Int, value: Int): Int { setAbs(code, value); return send() }

    // ---- sending, which is the Classic sink's arrangement because it was learned the hard way ---

    private fun send(): Int {
        if (!subscribed || host == null) return -1
        settle?.cancel(false)
        synchronized(lock) {
            queue.addLast(shape.snapshot())
            if (!draining) { draining = true; pool.execute(::drain) }
        }
        return 1
    }

    private fun drain() {
        while (true) {
            // Wait for the radio before offering it anything else. This is the whole of the fix:
            // the queue collapses while we wait, so what finally goes out is the newest state
            // rather than a backlog. Pushing regardless is what filled the radio's twenty-packet
            // queue, after which the stack stopped queueing reports and started discarding them —
            // "cannot send, already congested ... failed to write data to L2CAP" — which is a
            // stick that moves in steps, a press that never arrives, and a button that never
            // comes up because the frame releasing it was the one thrown away.
            if (!awaitIdle()) {
                // Far longer than any report should take. Assume the acknowledgement itself was
                // lost rather than going quiet forever.
                synchronized(sendLock) { inFlight = false }
            }
            var next: ByteArray? = null
            synchronized(lock) {
                collapse()
                next = queue.removeFirstOrNull()
                if (next == null) draining = false
            }
            val frame = next
            if (frame == null) { scheduleSettle(); return }
            deliver(frame)
        }
    }

    /**
     * Says the last thing again, once, shortly after everything goes quiet.
     *
     * A notification is not acknowledged by the machine, only by our own radio, so a packet lost
     * over the air leaves the host holding whatever it last heard. In the middle of a movement
     * that corrects itself on the next report; at the end of one there is no next report, and a
     * button stays down or a stick stays pushed. One repeat after the quiet costs nothing — the
     * link is idle by then — and it is the only thing standing between a dropped final frame and
     * a controller that appears to have jammed.
     */
    private fun scheduleSettle() {
        settle?.cancel(false)
        val frame = lastFrame ?: return
        settle = try {
            ticker.schedule({
                if (subscribed && host != null) {
                    synchronized(lock) {
                        if (queue.isEmpty() && !draining) { draining = true; queue.addLast(frame); pool.execute(::drain) }
                    }
                }
            }, SETTLE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
    }

    /** Drops frames the radio no longer needs. A press is never what gets dropped. */
    private fun collapse() {
        while (queue.size > 1) {
            val a = queue[0]; val b = queue[1]
            // Which bytes those are depends on the shape: the buttons and hat sit at the front of
            // one layout and the back of the other.
            for (i in shape.discreteBytes) if (a[i] != b[i]) return
            queue.removeFirst()
        }
    }

    private fun deliver(frame: ByteArray) {
        val d = host ?: return
        val c = reportChar ?: return
        val srv = server ?: return
        var tries = 0
        while (tries < RETRIES) {
            val taken = try {
                @Suppress("DEPRECATION")
                c.value = frame
                inFlight = true
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (e: Exception) { inFlight = false; Log.w(TAG, "notify failed", e); return }
            if (taken) { lastFrame = frame; return }
            // The stack refused it outright, which is its own kind of back-pressure. Give the
            // radio longer than two milliseconds to breathe: retrying that hard is how a refusal
            // turns into a burst the moment it stops refusing.
            inFlight = false
            tries++
            try { Thread.sleep(REFUSED_BACKOFF_MS) } catch (_: InterruptedException) { return }
        }
        // Every retry refused. The report is gone, and until now that happened in silence — which
        // is why the missed presses this was built to survive were never diagnosed, only covered
        // over by resending the whole state a hundred times a second. Count them instead, so the
        // question "is anything actually being dropped" has an answer.
        val n = dropped.incrementAndGet()
        if (n == 1 || n % 25 == 0) Log.w(TAG, "report dropped, $n so far (the radio would not take it)")
    }

    /**
     * Waits for the radio to admit it has sent the last report before offering another.
     *
     * The pad produces a hundred reports a second; a link to a machine carries perhaps sixty.
     * Android accepts every one of them regardless and queues the surplus inside its own stack,
     * where it is invisible from here — notifyCharacteristicChanged answers true, our queue drains
     * happily, and the packets go out later and later. Measured on 2026-09-20: a stick kept moving
     * for about a second after it was released, because that second of reports was still waiting
     * in a buffer we could not see.
     *
     * None of this showed while a bug meant nothing was subscribed and the reports went nowhere.
     * The moment they were really delivered, the link became the limit.
     */
    /**
     * Waits until the radio has nothing of ours outstanding. True if it said so, false if it
     * never did.
     *
     * This used to clear the flag itself on timeout and return as though the report had gone,
     * which was a lie with consequences: the next report went out on top of one still queued,
     * and the one after that, until the radio's queue was full and the stack began discarding
     * them. Timing out means the radio is busy, not that it is free, and the caller is told so.
     */
    private fun awaitIdle(): Boolean {
        val until = System.nanoTime() + STALL_MS * 1_000_000
        synchronized(sendLock) {
            while (inFlight) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                try { (sendLock as Object).wait(left) } catch (_: InterruptedException) { return false }
            }
        }
        return true
    }

    /** The same steady beat the Classic sink keeps, and for the same reason: silence reads badly. */
    /**
     * Tells a returning host to read our services again, if they are not what it last saw.
     *
     * Only when they have actually changed: rediscovery costs a second or so at every connection
     * and there is no sense spending it when nothing moved. What counts as changed is the shape
     * of the attribute table — the identity we claim and the report descriptor that goes with it
     * — which is exactly what a reinstall or a change of "appears as" disturbs.
     */
    /**
     * What a host would have to re-read if it changed.
     *
     * The identity and the report descriptor are the obvious parts, but the server itself
     * counts: reinstalling the app builds a new one, and a host holding the old session will sit
     * on a link that works while publishing no gamepad at all. Measured on 2026-09-20 — no
     * churn, no timeouts, simply no controller. The install time is what says "this is not the
     * server you met".
     */
    private fun layoutFingerprint(): Int {
        val built = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        } catch (e: Exception) { 0L }
        var fp = identity.name.hashCode() * 31 + shape.descriptor.contentHashCode()
        return fp * 31 + built.hashCode()
    }

    private fun announceLayoutIfChanged(device: BluetoothDevice) {
        val fingerprint = layoutFingerprint()
        val key = "fp:${device.address}"
        if (subscriptions.getInt(key, 0) == fingerprint) return
        ticker.schedule({
            val ch = changedChar ?: return@schedule
            if (host?.address != device.address) return@schedule
            if (!subscriptions.getBoolean("sc:${device.address}", false)) {
                // It never subscribed, so it cannot be told. Hanging up to force a rebuild was
                // tried on 2026-09-20 and is worse: the machine goes on believing it is
                // connected, so it never comes back and the link is simply gone. Say so and
                // leave it alone; clearing this needs a disconnect from the machine's own side.
                subscriptions.edit().putInt(key, fingerprint).apply()
                Log.i(TAG, "${device.address} is not listening for service changes; " +
                    "it may be holding a session from before this build")
                return@schedule
            }
            try {
                @Suppress("DEPRECATION")
                ch.value = EVERYTHING_CHANGED
                @Suppress("DEPRECATION")
                server?.notifyCharacteristicChanged(device, ch, true)
                Log.i(TAG, "told ${device.address} our services changed; it should look again")
                subscriptions.edit().putInt(key, fingerprint).apply()
            } catch (e: Exception) { Log.w(TAG, "service changed", e) }
        }, 400, TimeUnit.MILLISECONDS)
    }

    /**
     * Repeats a report for a moment after something changes, then goes quiet.
     *
     * This used to send a hundred a second for as long as a machine was attached, whether or not
     * anything had moved — which is the one way the pad behaved unlike every real controller. A
     * controller you put down stops talking. Ours did not, and with Steam Input in the chain each
     * report drives an update of the virtual gamepad it publishes, which is exactly the device
     * Apple says the system is meant to be ignoring to avoid "looping game controller input back
     * into the OS". On 2026-09-20 that loop reached about 2,500 driver connections a second and
     * hung every game that asked the daemon for controllers.
     *
     * The repeats are still wanted: they are what stopped presses going missing over a link that
     * drops the odd packet. So a change is sent at once and then repeated a few times, and after
     * that the pad is silent until something else happens.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = ticker.scheduleAtFixedRate({
            if (subscribed && repeatsLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) send()
        }, BEAT_MS, BEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() { heartbeat?.cancel(false); heartbeat = null }

    private companion object {
        const val TAG = "SidePadBle"
        /** Every handle there is: read all of it again. */
        val EVERYTHING_CHANGED = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val INDICATE_ON = byteArrayOf(0x02, 0x00)
        /** How long the pad stays off the air after a machine lets go of it. */
        const val QUIET_AFTER_MS = 2500L
        /** How long the pad keeps offering itself to nobody before going quiet. */
        const val SLEEP_AFTER_MS = 10 * 60 * 1000L
        /** Longest we wait for the radio to report a frame gone before giving up on it. */
        /** How long to let the radio finish one report before assuming its answer is lost. */
        const val STALL_MS = 400L
        /** Pause after the stack refuses a report outright. */
        const val REFUSED_BACKOFF_MS = 6L
        /** Quiet after which the last state is said once more, in case its frame went missing. */
        const val SETTLE_MS = 90L
        /**
         * How many times to repeat a report after something changes.
         *
         * Nought means once and no more, which is what a real controller does. Held there
         * deliberately: the repeats were added alongside a retry to cure presses going missing,
         * the symptom went away, and nobody ever learned which of the two did it or why the
         * presses were lost at all. With the radio now paced properly and the drops counted,
         * this is the way to find out — if presses start going missing again, the repeats were
         * carrying real weight and the reason is worth finding; if they do not, this was
         * covering for an overrun we were causing ourselves.
         */
        const val REPEATS = 0
        const val BEAT_MS = 10L
        const val RETRIES = 8

        const val GENERIC_ACCESS = "1800"
        const val APPEARANCE = "2a01"
        /** Gamepad, as the assigned-numbers list has it. */
        val GAMEPAD_APPEARANCE = byteArrayOf(0xC4.toByte(), 0x03)
        const val GATT_SERVICE = "1801"
        const val SERVICE_CHANGED = "2a05"
        const val DEVICE_INFO = "180a"
        const val PNP_ID = "2a50"
        const val BATTERY = "180f"
        const val BATTERY_LEVEL = "2a19"
        const val HID_SERVICE = "1812"
        const val HID_INFO = "2a4a"
        const val REPORT_MAP = "2a4b"
        const val CONTROL_POINT = "2a4c"
        const val REPORT = "2a4d"
        const val PROTOCOL_MODE = "2a4e"
        const val REPORT_REF = "2908"
        const val CCCD = "2902"

        const val REPORT_ID = 1.toByte()
        const val SYSTEM_REPORT_ID = 2.toByte()
        const val RUMBLE_REPORT_ID = 3.toByte()
        const val OUTPUT_REPORT = 2.toByte()
        /** Longer than a host's usual gap between instructions, so a held rumble does not stutter. */
        const val RUMBLE_MS = 120L
        const val INPUT_REPORT = 1.toByte()
        const val REPORT_PROTOCOL = 1.toByte()
        val NOTIFY_ON = byteArrayOf(0x01, 0x00)
        val NOTIFY_OFF = byteArrayOf(0x00, 0x00)

        /** Version 1.11, no country, normally connectable and remote-wake capable. */
        val HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x02)

        fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        fun hex(v: Int) = String.format("%04x", v)

    }
}
