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
    enum class Identity(val vendor: Int, val product: Int, val label: String) {
        OWN(0x1209, 0x5350, "SidePad"),
        XBOX(0x045E, 0x02E0, "Xbox-compatible"),
    }

    @Volatile override var connected = false; private set
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var subscribed = false
    private var server: BluetoothGattServer? = null
    private var advertiser: AdvertiseCallback? = null
    private var reportChar: BluetoothGattCharacteristic? = null

    /** Buttons, four stick axes, two triggers, the hat in the low nibble. Same shape as Classic. */
    private val report = ByteArray(9)
    private var hatX = 0
    private var hatY = 0

    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var draining = false
    private val pool = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: ScheduledFuture<*>? = null

    // ---- opening and closing -------------------------------------------------------------------

    /**
     * The hat's resting value is eight, not zero.
     *
     * Zero is north. A report that has never carried a hat event therefore says the D-pad is held
     * up, and stays saying it until the player touches the D-pad once. Seen on 2026-09-19 in the
     * first bytes a host received over Low Energy; the Classic sink hid the same fault because the
     * forwarder sends the controller's resting axes the moment it attaches, and the on-screen pad
     * alone never did.
     */
    init { report[8] = 8 }

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
        try { advertiser?.let { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        startAdvertising(adapter)
    }

    override fun close() {
        stopHeartbeat()
        try { ctx.unregisterReceiver(bondWatcher) } catch (_: Exception) {}
        try {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            advertiser?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        } catch (_: Exception) {}
        try { host?.let { server?.cancelConnection(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null; advertiser = null; reportChar = null
        host = null; connected = false; subscribed = false
    }

    // ---- the services --------------------------------------------------------------------------

    private fun addServices(srv: BluetoothGattServer) {
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
        hid.addCharacteristic(BluetoothGattCharacteristic(uuid(PROTOCOL_MODE),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE))
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
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(uuid(HID_SERVICE)))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) {
                Log.i(TAG, "advertising as ${identity.label} (${hex(identity.vendor)}:${hex(identity.product)})")
            }
            override fun onStartFailure(code: Int) { Log.w(TAG, "advertising refused, code $code") }
        }
        advertiser = cb
        le.startAdvertising(settings, data, cb)
        return true
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
            Log.i(TAG, "asking ${device.address} to pair")
            if (!device.createBond()) Log.w(TAG, "the pairing request was refused outright")
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
                host = device; connected = true
                Log.i(TAG, "${device.address} connected")
                // A bond only means this host has been here before. Whether it is listening is a
                // separate question and only it can answer: it says so by subscribing. Assuming
                // otherwise was tried and was worse than useless — the pad reported itself
                // connected and sent presses to a host that had not asked for any, so a link that
                // was plainly broken looked like a working one.
                if (isBonded(device)) Log.i(TAG, "${device.address} has bonded before")
                else createBondWith(device)
                startHeartbeat()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "${device.address} went away")
                if (host?.address == device.address) {
                    host = null; connected = false; subscribed = false
                    stopHeartbeat()
                    onState("Not connected")
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int,
                offset: Int, ch: BluetoothGattCharacteristic?) {
            val value = when (ch?.uuid) {
                uuid(PNP_ID) -> pnpId()
                uuid(HID_INFO) -> HID_INFORMATION
                uuid(REPORT_MAP) -> DESCRIPTOR
                uuid(REPORT) -> synchronized(lock) { report.copyOf() }
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
                uuid(REPORT_REF) -> byteArrayOf(REPORT_ID, INPUT_REPORT)
                uuid(CCCD) -> if (subscribed) NOTIFY_ON else NOTIFY_OFF
                else -> ByteArray(0)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int,
                d: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray?) {
            if (d?.uuid == uuid(CCCD) && d.characteristic?.uuid == uuid(REPORT)) {
                subscribed = value != null && value.isNotEmpty() && value[0].toInt() and 0x01 != 0
                Log.i(TAG, if (subscribed) "the host is taking reports" else "the host stopped taking reports")
                if (subscribed) onState("")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int,
                ch: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.i(TAG, "the host asked for $mtu bytes a packet")
        }
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
        0x00, 0x01,                                 // version 1.0
    )

    // ---- the pad's side ------------------------------------------------------------------------

    override fun setKey(code: Int, down: Boolean) {
        val bit = BluetoothSink.buttonBit(code) ?: return
        val i = bit / 8
        val mask = 1 shl (bit % 8)
        synchronized(lock) {
            report[i] = if (down) (report[i].toInt() or mask).toByte()
                        else (report[i].toInt() and mask.inv()).toByte()
        }
    }

    override fun setAbs(code: Int, value: Int) {
        synchronized(lock) {
            when (code) {
                Abs.HAT0X -> { hatX = value; writeHat() }
                Abs.HAT0Y -> { hatY = value; writeHat() }
                else -> BluetoothSink.axisByte(code)?.let {
                    report[it] = value.coerceIn(-127, 127).toByte()
                }
            }
        }
    }

    override fun sync(): Int = send()

    override fun key(code: Int, down: Boolean): Int { setKey(code, down); return send() }
    override fun abs(code: Int, value: Int): Int { setAbs(code, value); return send() }

    private fun writeHat() {
        val dir = when {
            hatY < 0 && hatX == 0 -> 0
            hatY < 0 && hatX > 0 -> 1
            hatY == 0 && hatX > 0 -> 2
            hatY > 0 && hatX > 0 -> 3
            hatY > 0 && hatX == 0 -> 4
            hatY > 0 && hatX < 0 -> 5
            hatY == 0 && hatX < 0 -> 6
            hatY < 0 && hatX < 0 -> 7
            else -> 8
        }
        report[8] = ((report[8].toInt() and 0xF0) or dir).toByte()
    }

    // ---- sending, which is the Classic sink's arrangement because it was learned the hard way ---

    private fun send(): Int {
        if (!subscribed || host == null) return -1
        synchronized(lock) {
            queue.addLast(report.copyOf())
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
            if (a[0] != b[0] || a[1] != b[1] || a[8] != b[8]) return
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
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = ticker.scheduleAtFixedRate({ if (subscribed) send() }, BEAT_MS, BEAT_MS,
            TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() { heartbeat?.cancel(false); heartbeat = null }

    private companion object {
        const val TAG = "SidePadBle"
        const val BEAT_MS = 10L
        const val RETRIES = 8

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
        const val INPUT_REPORT = 1.toByte()
        const val REPORT_PROTOCOL = 1.toByte()
        val NOTIFY_ON = byteArrayOf(0x01, 0x00)
        val NOTIFY_OFF = byteArrayOf(0x00, 0x00)

        /** Version 1.11, no country, normally connectable and remote-wake capable. */
        val HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x02)

        fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        fun hex(v: Int) = String.format("%04x", v)

        /** The same gamepad the Classic sink describes; a host must see one shape, not two. */
        val DESCRIPTOR = BluetoothSink.reportDescriptor()
    }
}
