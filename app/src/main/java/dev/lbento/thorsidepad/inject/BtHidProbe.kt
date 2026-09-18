package dev.lbento.thorsidepad.inject

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Feasibility probe for the handheld acting as a controller for another machine.
 *
 * Nothing here is privileged: presenting as a Bluetooth gamepad is an ordinary app capability, no
 * Shizuku, no developer mode, no root. The probe registers a gamepad and reports what the stack and
 * any host make of it; it sends nothing until something connects.
 */
object BtHidProbe {
    private const val TAG = "SidePadBtHid"

    /** A plain gamepad: sixteen buttons and four axes, the shape a host is most likely to accept. */
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x01,    //   Report ID (1)
        0x05, 0x09,             //   Usage Page (Button)
        0x19, 0x01,             //   Usage Minimum (Button 1)
        0x29, 0x10,             //   Usage Maximum (Button 16)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x10,    //   Report Count (16)
        0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x30,             //   Usage (X)
        0x09, 0x31,             //   Usage (Y)
        0x09, 0x32,             //   Usage (Z)
        0x09, 0x35,             //   Usage (Rz)
        0x15, 0x81.toByte(),    //   Logical Minimum (-127)
        0x25, 0x7F,             //   Logical Maximum (127)
        0x75, 0x08,             //   Report Size (8)
        0x95.toByte(), 0x04,    //   Report Count (4)
        0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
        0xC0.toByte(),          // End Collection
    )

    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: android.bluetooth.BluetoothDevice? = null
    @Volatile private var adapter: android.bluetooth.BluetoothAdapter? = null
    private val pool = Executors.newSingleThreadExecutor()

    fun start(ctx: Context) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { Log.w(TAG, "no bluetooth adapter"); return }
        this.adapter = adapter
        Log.i(TAG, "adapter on=${adapter.isEnabled} name=${runCatching { adapter.name }.getOrNull()}")

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                Log.i(TAG, "HID_DEVICE proxy obtained")
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Thor SidePad", "Virtual gamepad", "lbento",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, DESCRIPTOR)
                val ok = try {
                    h.registerApp(sdp, null, null, pool, object : BluetoothHidDevice.Callback() {
                        override fun onAppStatusChanged(plugged: BluetoothDevice?, registered: Boolean) {
                            Log.i(TAG, "registered=$registered plugged=${plugged?.address}")
                            if (plugged != null) host = plugged
                        }
                        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                            Log.i(TAG, "connection ${device?.address} state=$state" +
                                if (state == BluetoothProfile.STATE_CONNECTED) " CONNECTED" else "")
                            if (state == BluetoothProfile.STATE_CONNECTED && device != null) host = device
                        }
                        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, size: Int) {
                            Log.i(TAG, "host asked for report type=$type id=$id")
                        }
                    })
                } catch (e: SecurityException) { Log.w(TAG, "registerApp denied", e); false }
                Log.i(TAG, "registerApp returned $ok")
            }
            override fun onServiceDisconnected(profile: Int) { if (profile == BluetoothProfile.HID_DEVICE) hid = null }
        }
        val got = adapter.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
        Log.i(TAG, "getProfileProxy(HID_DEVICE) = $got")
    }

    /** Presses each of the first six buttons in turn, then sweeps a stick, so a host can be watched. */
    fun press() {
        val h = hid
        if (h == null) { Log.w(TAG, "not registered yet"); return }
        Thread {
            // A host that has paired before does not necessarily reconnect on its own, so the link is
            // opened from this side when there is none.
            var dev = try { h.connectedDevices.firstOrNull() } catch (e: SecurityException) { null }
            if (dev == null) {
                val target = host ?: try { adapter?.bondedDevices?.firstOrNull() } catch (e: SecurityException) { null }
                if (target == null) { Log.w(TAG, "no paired host to connect to"); return@Thread }
                Log.i(TAG, "connecting to ${target.address}")
                try { Log.i(TAG, "connect() = ${h.connect(target)}") } catch (e: Exception) { Log.w(TAG, "connect", e) }
                repeat(10) {
                    Thread.sleep(700)
                    dev = try { h.connectedDevices.firstOrNull() } catch (e: SecurityException) { null }
                    if (dev != null) return@repeat
                }
            }
            val target = dev
            if (target == null) { Log.w(TAG, "host never connected"); return@Thread }
            Log.i(TAG, "sending to ${target.address}")
            fun send(buttons: Int, x: Int, y: Int) {
                val data = byteArrayOf(
                    (buttons and 0xFF).toByte(), ((buttons shr 8) and 0xFF).toByte(),
                    x.toByte(), y.toByte(), 0, 0)
                val ok = try { h.sendReport(target, 1, data) } catch (e: Exception) { Log.w(TAG, "send", e); false }
                Log.i(TAG, "report buttons=0x${buttons.toString(16)} x=$x y=$y -> $ok")
            }
            repeat(6) { i -> send(1 shl i, 0, 0); Thread.sleep(220); send(0, 0, 0); Thread.sleep(220) }
            send(0, 100, -100); Thread.sleep(400); send(0, 0, 0)
        }.start()
    }
}
