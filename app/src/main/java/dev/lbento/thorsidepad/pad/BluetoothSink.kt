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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
class BluetoothSink(private val ctx: Context, private val onState: (String) -> Unit = {}) : PadTransport {

    private val pool = Executors.newSingleThreadExecutor()
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile override var connected = false; private set

    /** Classic sends the standard nine-byte report, whose sticks are a signed byte. */
    override val caps: Caps = CAPS

    /** The live report: buttons, four stick axes, two triggers, and the hat in the low nibble. */
    private val report = ByteArray(9)
    private var hatX = 0
    private var hatY = 0

    /** Frames waiting for the radio, newest last. Guarded by [lock], drained by [pool]. */
    private val queue = ArrayDeque<ByteArray>()
    private val lock = Any()
    private var draining = false

    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: ScheduledFuture<*>? = null

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

    fun open(address: String, report: (String) -> Unit) {
        // The status callback fires again when the gamepad is taken down, saying it is no longer
        // registered. That is not a failure to open, and reporting it as one made switching to a
        // machine announce that it could not connect at the very moment it had.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val done: (String) -> Unit = { msg -> if (answered.compareAndSet(false, true)) report(msg) }
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
                            if (connected) startHeartbeat() else stopHeartbeat()
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
    override fun reconnect() {
        val h = hid ?: return
        val d = host ?: return
        try { h.connect(d) } catch (e: Exception) { Log.w(TAG, "reconnect", e) }
    }

    override fun close() {
        val h = hid ?: return
        stopHeartbeat()
        try { host?.let { h.disconnect(it) }; h.unregisterApp() } catch (_: Exception) {}
        hid = null; connected = false
    }

    // ---- PadSink -----------------------------------------------------------------------------

    override fun handles(code: Int): Boolean = buttonBit(code) != null

    /** Updates the report without sending. Used when a burst of events ends in a sync. */
    override fun setKey(code: Int, down: Boolean) {
        val bit = BUTTONS[code] ?: return            // nothing to say for this one, not a failure
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
                else -> AXES[code]?.let { report[it] = value.coerceIn(-127, 127).toByte() }
            }
        }
    }

    /** Sends whatever the report currently says. */
    override fun sync(): Int = send()

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

    /**
     * Sends the pad's state on a steady beat, whether or not anything changed.
     *
     * Every real controller does this: it reports at a fixed rate and keeps reporting while it is
     * awake. This one used to speak only when something moved, which meant a still pad sent
     * nothing at all — measured on 2026-09-19, twelve seconds of silence produced not one report.
     * Everything below the receiver was fine with that, and it is provably fine: a press after
     * twenty seconds of quiet reached the other machine's input layer as quickly as one after
     * fifty milliseconds. What is not fine with it is whatever reads the pad further up, where a
     * first movement after a pause arrived late while a controller that never stops talking did
     * not. So now this one never stops talking either.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = ticker.scheduleAtFixedRate({
            if (connected) send()
        }, BEAT_MS, BEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() { heartbeat?.cancel(false); heartbeat = null }

    /**
     * Queues the current state and makes sure someone is sending.
     *
     * The radio will not always take a report the moment it is offered, and a controller offers
     * far more of them than a Bluetooth link can carry: the Thor's sticks alone report faster than
     * the interrupt channel drains. The old code handed the report straight to the radio and threw
     * it away if it was refused, which is why a button press sometimes did nothing. A press made
     * while a stick was moving survived, because the next stick frame carried the button along
     * with it; a press made with the sticks at rest produced one report and nothing to repeat it.
     */
    private fun send(): Int {
        if (hid == null || host == null) return -1
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

    /**
     * Throws away frames the radio no longer needs to see.
     *
     * A report is a whole picture of the pad rather than a change to it, so an older frame is
     * worth nothing once a newer one exists — as long as the buttons in it never got their turn.
     * Axis frames are dropped freely, which is what keeps a moving stick from filling the queue,
     * while a frame whose buttons differ from the one after it is always sent: that is the press.
     */
    private fun collapse() {
        while (queue.size > 1) {
            val a = queue[0]; val b = queue[1]
            // Bytes 0 and 1 are the buttons and byte 8 carries the hat. Both are things a player
            // taps, so both have to survive; everything between them is stick and trigger travel.
            if (a[0] != b[0] || a[1] != b[1] || a[8] != b[8]) return
            queue.removeFirst()
        }
    }

    /** Offers one frame until the radio takes it. */
    private fun deliver(frame: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        var tries = 0
        while (tries < RETRIES) {
            val taken = try { h.sendReport(d, 1, frame) }
                        catch (e: Exception) { Log.w(TAG, "send failed", e); return }
            if (taken) return
            tries++
            try { Thread.sleep(2) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "radio refused a report ${RETRIES} times, dropping it")
    }

    companion object {
        // Shared with the Low Energy sink next door. A host must be shown one gamepad, not two
        // that disagree, so both transports read the arrangement from here.
        fun buttonBit(code: Int): Int? = BUTTONS[code]
        fun axisByte(code: Int): Int? = AXES[code]
        fun reportDescriptor(): ByteArray = DESCRIPTOR

        private const val TAG = "SidePadBtSink"
        /** About sixteen milliseconds of trying, which outlasts any ordinary link hiccup. */
        private const val RETRIES = 8
        /** A hundred reports a second, which is an ordinary rate for a Bluetooth gamepad. */
        private const val BEAT_MS = 10L

        /**
         * evdev button to HID button number, zero-based within the report's sixteen bits.
         *
         * The order is not ours to choose. A host with no mapping for a pad falls back to the
         * arrangement every ordinary controller uses: A, B, X, Y, the two shoulders, Select, Start,
         * then the two stick clicks. The Thor's extra M1 and M2 sit after all of that. Slotting
         * them into the middle, which is where they fall if you simply walk the evdev codes in
         * order, shifts everything after them by two, so Start lands on the number a game reads as
         * something else entirely and appears not to work.
         */
        private val BUTTONS = mapOf(
            Btn.A to 0, Btn.B to 1, Btn.X to 2, Btn.Y to 3,
            Btn.TL to 4, Btn.TR to 5,
            Btn.SELECT to 6, Btn.START to 7,
            Btn.THUMBL to 8, Btn.THUMBR to 9,
            // Ten is Guide, and this is not a matter of taste. Read out of Steam's own log on
            // 2026-09-19, the layout it guesses for a pad it does not recognise ends
            // "...leftstick:b8, rightstick:b9, guide:b10", which is the Xbox button order with
            // the triggers taken off to their axes. Everything above already agreed with it; ten
            // was the one place we disagreed, and we had the trigger click there, so pulling L2
            // opened the Steam overlay and the Guide button did nothing.
            Btn.MODE to 10,
            // The triggers' own travel is on axes two and five, so these are only the click at
            // the bottom. Nothing standard claims eleven or twelve, so they sit here.
            Btn.TL2 to 11, Btn.TR2 to 12,
            Btn.C to 13, Btn.Z to 14,      // the Thor's M1 and M2, after everything standard
        )

        /**
         * evdev axis to its byte in the report. The byte order is unchanged; what moved is which
         * HID usage each byte is declared as. A controller calls its right stick Z and Rz on this
         * side and Rx and Ry on the other, and its triggers the reverse.
         */
        private val AXES = mapOf(
            Abs.X to 2, Abs.Y to 3,        // left stick, X and Y
            Abs.Z to 4, Abs.RZ to 5,       // right stick, declared as Rx and Ry
            Abs.BRAKE to 6, Abs.GAS to 7,  // triggers, declared as Z and Rz
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
            // Two sticks on X/Y and Rx/Ry, two triggers on Z/Rz. The names are not free choice:
            // this is the arrangement every host assumes for an unknown pad. Declared the other way
            // round, a resting trigger reads as a stick held hard over, which is how a game ends up
            // with input flying about while nothing is being touched.
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
            0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x25, 0x7F,
            0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
            // hat, then four bits of padding to close the byte
            0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46.toByte(), 0x3B, 0x01,
            0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            0xC0.toByte(),
        )
    }
}
