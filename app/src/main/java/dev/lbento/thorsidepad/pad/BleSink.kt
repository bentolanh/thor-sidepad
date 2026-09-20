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
        // The version is part of the identity and not decoration. A program carrying its own list
        // of controllers keys it on the whole thing, so two bytes of firmware number are enough to
        // miss by: Eastward logged `no joystick mapping found` against
        // `030000005e040000e002000000010000`, which is this pad ending in a version of 1.0, while
        // every pad here that it does accept reports 9.0.3 and ends in `0309`. Same vendor, same
        // product, different answer.
        XBOX(0x045E, 0x02E0, 0x0903, "Xbox-compatible"),
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
        if (identity == Identity.XBOX) XboxShape() else StandardShape()

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
    private var changedChar: BluetoothGattCharacteristic? = null
    private var goodbye: java.util.concurrent.CountDownLatch? = null

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
        restartAdvertising()
        ticker.schedule({
            if (secondsFindable() == 0) {
                Log.i(TAG, "no longer findable; quiet again")
                restartAdvertising()
            }
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
        // How a peripheral tells a host that already knows it to look again.
        //
        // A bonded host does not re-read the attribute table on every connection; it keeps the
        // one it found the first time. That is a sensible saving until the table changes, and
        // ours changes whenever the app is reinstalled or the pad is made to appear as something
        // else. On 2026-09-20 a reinstall left a Mac asking for a handle that no longer existed:
        // nothing answered, its ATT transaction timed out thirty seconds later, it hung up, and
        // it came straight back with the same stale table. Every death left another dead gamepad
        // in its device list. The only cure was forgetting the pad and pairing it again.
        //
        // Indicating on this characteristic is how that is meant to be avoided, so it goes first,
        // where the handle range it reports begins.
        val gatt = BluetoothGattService(uuid(GATT_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val changed = BluetoothGattCharacteristic(uuid(SERVICE_CHANGED),
            BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
        changed.addDescriptor(BluetoothGattDescriptor(uuid(CCCD),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        gatt.addCharacteristic(changed)
        changedChar = changed
        srv.addService(gatt)
        awaitService()

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
            Log.i(TAG, "a machine already has us; staying quiet")
            return true
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Loud while findable, anonymous otherwise. The name and the gamepad service are what put
        // an entry in a stranger's list; a host that already knows us finds us by address.
        val open = secondsFindable() > 0
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(open)
            .also { if (open) it.addServiceUuid(ParcelUuid(uuid(HID_SERVICE))) }
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) {
                Log.i(TAG, (if (open) "findable as " else "quietly reachable as ") +
                    "${identity.label} (${hex(identity.vendor)}:${hex(identity.product)})")
            }
            override fun onStartFailure(code: Int) { Log.w(TAG, "advertising refused, code $code") }
        }
        advertiser = cb
        wantAdvertising = true
        le.startAdvertising(settings, data, cb)
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

    private fun resumeAdvertising() {
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
                // separate question and only it can answer: it says so by subscribing. Assuming
                // otherwise was tried and was worse than useless — the pad reported itself
                // connected and sent presses to a host that had not asked for any, so a link that
                // was plainly broken looked like a working one.
                if (isBonded(device) && subscriptions.getBoolean(device.address, false)) {
                    subscribed = true
                    Log.i(TAG, "${device.address} is bonded and had subscribed; sending reports again")
                    onState("")
                } else {
                    // Either never met, or bonded over Classic only and never subscribed here.
                    // Asking for a Low Energy bond covers both; one that already exists is
                    // refused harmlessly.
                    createBondWith(device)
                }
                startHeartbeat()
                announceLayoutIfChanged(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                goodbye?.countDown()
                Log.i(TAG, "${device.address} went away (status $status)")
                if (host?.address == device.address) {
                    host = null; connected = false; subscribed = false
                    stopHeartbeat()
                    if (status == REMOTE_HUNG_UP) {
                        // Somebody pressed Disconnect. A controller does not climb back into a
                        // machine that has just put it down — it waits to be picked up, and the
                        // pad's own "tap to try again" is how that is done here. Calling out
                        // again would simply undo the request: a Mac reattaches to a bonded pad
                        // the moment it sees one advertise, which it did inside a second.
                        Log.i(TAG, "it let go on purpose; waiting to be asked rather than calling out")
                    } else {
                        // A drop rather than a decision: a pocket, a door, a flat battery. Go back
                        // on air, but not instantly — a machine needs a moment to put the old
                        // device away, and 358ms was not enough for one measured on 2026-09-20.
                        ticker.schedule({ resumeAdvertising() }, QUIET_AFTER_MS, TimeUnit.MILLISECONDS)
                    }
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
                device?.let { subscriptions.edit().putBoolean(it.address, subscribed).apply() }
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

    override fun sync(): Int = send()

    override fun key(code: Int, down: Boolean): Int { setKey(code, down); return send() }
    override fun abs(code: Int, value: Int): Int { setAbs(code, value); return send() }

    // ---- sending, which is the Classic sink's arrangement because it was learned the hard way ---

    private fun send(): Int {
        if (!subscribed || host == null) return -1
        synchronized(lock) {
            queue.addLast(shape.snapshot())
            if (!draining) { draining = true; pool.execute(::drain) }
        }
        return 1
    }

    private fun drain() {
        while (true) {
            var next: ByteArray? = null
            synchronized(lock) {
                collapse()
                next = queue.removeFirstOrNull()
                if (next == null) draining = false
            }
            deliver(next ?: return)
        }
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
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (e: Exception) { Log.w(TAG, "notify failed", e); return }
            if (taken) return
            tries++
            try { Thread.sleep(2) } catch (_: InterruptedException) { return }
        }
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
    private fun announceLayoutIfChanged(device: BluetoothDevice) {
        // What the host has to re-read if it changed. The identity and the report descriptor are
        // the obvious parts, but the server itself counts: reinstalling the app builds a new one,
        // and a host holding the old session will sit on a link that works while publishing no
        // gamepad at all. Measured on 2026-09-20 — no churn, no timeouts, simply no controller.
        // The install time is what says "this is not the server you met".
        val built = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        } catch (e: Exception) { 0L }
        var fingerprint = identity.name.hashCode() * 31 + shape.descriptor.contentHashCode()
        fingerprint = fingerprint * 31 + built.hashCode()
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

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = ticker.scheduleAtFixedRate({ if (subscribed) send() }, BEAT_MS, BEAT_MS,
            TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() { heartbeat?.cancel(false); heartbeat = null }

    private companion object {
        const val TAG = "SidePadBle"
        /** Every handle there is: read all of it again. */
        val EVERYTHING_CHANGED = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val INDICATE_ON = byteArrayOf(0x02, 0x00)
        /** How long the pad stays off the air after a machine lets go of it. */
        /** The machine chose to end it, rather than the link failing. */
        const val REMOTE_HUNG_UP = 0x13
        const val QUIET_AFTER_MS = 2500L
        const val BEAT_MS = 10L
        const val RETRIES = 8

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
