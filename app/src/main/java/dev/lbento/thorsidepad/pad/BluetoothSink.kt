package dev.lbento.thorsidepad.pad

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dev.lbento.thorsidepad.inject.Abs
import dev.lbento.thorsidepad.inject.Btn
import java.util.concurrent.Executors

/**
 * A destination that is another machine: the pad presents itself as a Bluetooth gamepad and the
 * presses land wherever that machine is.
 *
 * Nothing here is privileged. Unlike the local destination, which needs Shizuku to reach the
 * kernel, being a Bluetooth peripheral is an ordinary app capability.
 *
 * The shape advertised is a plain gamepad: sixteen buttons, two sticks, two triggers and a hat.
 * [CAPS] describes the same shape in the terms the engine already speaks, so layouts, profiles and
 * everything else carry over untouched.
 */
class BluetoothSink(private val ctx: Context, private val onState: (String) -> Unit = {}) : PadSink {

    private val pool = Executors.newSingleThreadExecutor()
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile var connected = false; private set

    /** The live report: buttons, four stick axes, two triggers, and the hat in the low nibble. */
    private val report = ByteArray(9)
    private var hatX = 0
    private var hatY = 0

    fun open(address: String, done: (String) -> Unit) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { done("Bluetooth is off"); return }
        // Without a chosen host, take a paired computer rather than the first thing in the list,
        // which on a handheld is far more likely to be headphones or another controller.
        val target = try {
            val bonded = adapter.bondedDevices ?: emptySet()
            bonded.firstOrNull { address.isNotEmpty() && it.address == address }
                ?: bonded.firstOrNull {
                    it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
                }
        } catch (e: SecurityException) { null }
        if (target == null) { done("No paired machine. Pair one first."); return }
        Log.i(TAG, "host is ${target.address}")
        host = target

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Thor SidePad", "Virtual gamepad", "lbento",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, DESCRIPTOR)
                try {
                    // registerApp reports false even when it works; the callback is the truth.
                    h.registerApp(sdp, null, null, pool, object : BluetoothHidDevice.Callback() {
                        override fun onAppStatusChanged(plugged: BluetoothDevice?, registered: Boolean) {
                            Log.i(TAG, "registered=$registered")
                            if (registered) {
                                // A paired host does not reconnect on its own, so ask.
                                try { h.connect(target) } catch (e: SecurityException) { Log.w(TAG, "connect", e) }
                                done("")
                            } else done("Could not present a gamepad")
                        }
                        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                            connected = state == BluetoothProfile.STATE_CONNECTED
                            if (connected && device != null) host = device
                            Log.i(TAG, "connected=$connected")
                            onState(if (connected) "" else "Not connected")
                        }
                    })
                } catch (e: SecurityException) { done("Bluetooth permission refused") }
            }
            override fun onServiceDisconnected(profile: Int) { if (profile == BluetoothProfile.HID_DEVICE) hid = null }
        }
        if (!adapter.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)) done("No gamepad support")
    }

    /** Ask the host again, for when the link has dropped and the player wants it back. */
    fun reconnect() {
        val h = hid ?: return
        val d = host ?: return
        try { h.connect(d) } catch (e: Exception) { Log.w(TAG, "reconnect", e) }
    }

    fun close() {
        val h = hid ?: return
        try { host?.let { h.disconnect(it) }; h.unregisterApp() } catch (_: Exception) {}
        hid = null; connected = false
    }

    // ---- PadSink -----------------------------------------------------------------------------

    /** Updates the report without sending. Used when a burst of events ends in a sync. */
    fun setKey(code: Int, down: Boolean) {
        val bit = BUTTONS[code] ?: return            // nothing to say for this one, not a failure
        val i = bit / 8
        val mask = 1 shl (bit % 8)
        report[i] = if (down) (report[i].toInt() or mask).toByte()
                    else (report[i].toInt() and mask.inv()).toByte()
    }

    fun setAbs(code: Int, value: Int) {
        when (code) {
            Abs.HAT0X -> { hatX = value; writeHat() }
            Abs.HAT0Y -> { hatY = value; writeHat() }
            else -> AXES[code]?.let { report[it] = value.coerceIn(-127, 127).toByte() }
        }
    }

    /** Sends whatever the report currently says. */
    fun sync(): Int = send()

    override fun key(code: Int, down: Boolean): Int { setKey(code, down); return send() }

    override fun abs(code: Int, value: Int): Int { setAbs(code, value); return send() }

    /** Eight compass points, or the null value when the pad is centred. */
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

    private fun send(): Int {
        val h = hid ?: return -1
        val d = host ?: return -1
        return try { if (h.sendReport(d, 1, report)) 1 else -1 }
        catch (e: Exception) { Log.w(TAG, "send failed", e); -1 }
    }

    companion object {
        private const val TAG = "SidePadBtSink"

        /** evdev button to HID button number, zero-based within the report's sixteen bits. */
        private val BUTTONS = mapOf(
            Btn.A to 0, Btn.B to 1, Btn.C to 2, Btn.X to 3, Btn.Y to 4, Btn.Z to 5,
            Btn.TL to 6, Btn.TR to 7, Btn.TL2 to 8, Btn.TR2 to 9,
            Btn.SELECT to 10, Btn.START to 11, Btn.MODE to 12,
            Btn.THUMBL to 13, Btn.THUMBR to 14,
        )

        /** evdev axis to its byte in the report. */
        private val AXES = mapOf(
            Abs.X to 2, Abs.Y to 3, Abs.Z to 4, Abs.RZ to 5,
            Abs.BRAKE to 6, Abs.GAS to 7,
        )

        /**
         * What this destination can express, in the engine's own terms. Deliberately the same shape
         * as the local virtual pad: the D-pad is left out of the buttons so it plans onto the hat,
         * and the triggers get their own axes so they do not fight the right stick.
         */
        val CAPS = Caps(
            keys = BUTTONS.keys,
            abs = mapOf(
                Abs.X to intArrayOf(-127, 127), Abs.Y to intArrayOf(-127, 127),
                Abs.Z to intArrayOf(-127, 127), Abs.RZ to intArrayOf(-127, 127),
                Abs.BRAKE to intArrayOf(0, 127), Abs.GAS to intArrayOf(0, 127),
                Abs.HAT0X to intArrayOf(-1, 1), Abs.HAT0Y to intArrayOf(-1, 1),
            ),
            virtual = true,
        )

        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            // sixteen buttons
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
            // two sticks
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
            // two triggers
            0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x25, 0x7F,
            0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
            // hat, then four bits of padding to close the byte
            0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46.toByte(), 0x3B, 0x01,
            0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            0xC0.toByte(),
        )
    }
}
