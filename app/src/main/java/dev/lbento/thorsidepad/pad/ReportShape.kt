package dev.lbento.thorsidepad.pad

import dev.lbento.thorsidepad.inject.Abs

/**
 * The shape of what a host is told, and of what it is then sent.
 *
 * These two things are one decision. A descriptor is a promise about the bytes that follow, and a
 * host that recognises the name on the box will hold us to the shape that name implies rather than
 * to the one we describe — which is how a pad can be recognised perfectly and still arrive with
 * both sticks pinned to a corner. So a shape owns its descriptor and its report together, and
 * nothing may mix one with the other.
 *
 * Values arriving here are always in the pad's own terms: sticks −127 to 127, triggers 0 to 127,
 * hat as two −1/0/1 axes. Converting those to whatever a shape actually puts on the wire is the
 * shape's business, which keeps the layout engine, the profiles and the controller forwarder from
 * ever needing to know which one is in use.
 */
interface ReportShape {
    val descriptor: ByteArray
    /** Bytes a player taps rather than sweeps; a frame carrying a change to one is never dropped. */
    val discreteBytes: IntArray
    fun setKey(code: Int, down: Boolean)
    fun setAbs(code: Int, value: Int)
    fun snapshot(): ByteArray

    /**
     * Whether a button of this code has anywhere to go in this report.
     *
     * Taking the controller takes all of it, so a button with no slot here is not merely unsent,
     * it is destroyed: the handheld no longer sees it either. Knowing which those are is what
     * lets them be handed back to Android instead.
     */
    fun handles(code: Int): Boolean
    /**
     * What a host has asked the pad to do, read out of an output report it wrote. Returns how
     * hard to buzz, nought to two hundred and fifty-five, or null when the shape has no such
     * report or the bytes say nothing.
     */
    fun rumbleFrom(bytes: ByteArray): Int? = null

    /**
     * A second report, for anything the pad's own report has no room for. Null when the shape has
     * only one, which is the ordinary case; the Xbox shape uses it for the button in the middle,
     * because that is where the hardware it imitates puts it.
     */
    fun systemSnapshot(): ByteArray? = null
}

/**
 * What this pad has always sent: sixteen buttons first, then four signed stick bytes, two trigger
 * bytes and a hat. Nine bytes, and the shape both the Classic transport and the pad's own identity
 * use. Nothing here changes.
 */
class StandardShape : ReportShape {
    private val report = ByteArray(9)
    private var hatX = 0
    private var hatY = 0

    init { report[8] = 8 }   // the hat's rest is eight; zero would be north

    override val descriptor: ByteArray get() = BluetoothSink.reportDescriptor()
    override val discreteBytes = intArrayOf(0, 1, 8)

    override fun handles(code: Int): Boolean = BluetoothSink.buttonBit(code) != null

    override fun setKey(code: Int, down: Boolean) {
        val bit = BluetoothSink.buttonBit(code) ?: return
        val i = bit / 8
        val mask = 1 shl (bit % 8)
        synchronized(report) {
            report[i] = if (down) (report[i].toInt() or mask).toByte()
                        else (report[i].toInt() and mask.inv()).toByte()
        }
    }

    override fun setAbs(code: Int, value: Int) {
        synchronized(report) {
            when (code) {
                Abs.HAT0X -> { hatX = value; writeHat() }
                Abs.HAT0Y -> { hatY = value; writeHat() }
                else -> BluetoothSink.axisByte(code)?.let {
                    report[it] = value.coerceIn(-127, 127).toByte()
                }
            }
        }
    }

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

    override fun snapshot(): ByteArray = synchronized(report) { report.copyOf() }
}

/**
 * What a pad claiming to be Xbox-compatible has to send.
 *
 * Read on 2026-09-19 off an 8BitDo pad that claims the same vendor and product numbers and that a
 * Mac accepts, rather than guessed at: its descriptor was fetched from the host and decoded, and
 * every field below matches it. The differences from [StandardShape] are all of them — the order,
 * the widths, the ranges, and the hat's idea of where centre is — which is why claiming the name
 * without this made a pad that was recognised and useless.
 *
 * Fifteen bytes, after the report number the stack puts in front:
 *
 *     0‥1   left stick X     sixteen bits, unsigned, centre 0x8000
 *     2‥3   left stick Y
 *     4‥5   right stick X    declared Rx
 *     6‥7   right stick Y    declared Ry
 *     8‥9   left trigger     ten bits in a sixteen-bit field, 0‥1023
 *    10‥11  right trigger
 *    12     hat              four bits, 1 is north through 8 north-west, 0 is centred
 *    13‥14  ten buttons      then six bits of nothing
 */
class XboxShape(
    private val rumble: Boolean = true,
    /** Which of the two button orders below to send. See [BUTTONS] and [BUTTONS_PLAIN]. */
    private val plainButtons: Boolean = false,
    /**
     * Whether to offer the Consumer collections the real controller carries. See [WITH_CONSUMER].
     * False — the default — is what Apple hosts need and what every host is known to cope with.
     */
    private val consumer: Boolean = false,
    /**
     * Send the real Xbox Series controller's report map, verbatim. See [SERIES].
     *
     * It already carries its own force-feedback and Consumer declarations, so [rumble] and
     * [consumer] do not apply: the point is to be exactly what that controller is, and editing
     * it would defeat that.
     */
    private val series: Boolean = false,
) : ReportShape {
    private val report = ByteArray(16)
    private var hatX = 0
    private var hatY = 0

    init {
        // Sticks rest at the middle of an unsigned range, not at zero.
        putShort(0, CENTRE); putShort(2, CENTRE); putShort(4, CENTRE); putShort(6, CENTRE)
    }

    override val descriptor = when {
        series -> SERIES
        consumer -> WITH_CONSUMER
        rumble -> DESCRIPTOR
        else -> NO_RUMBLE
    }
    override val discreteBytes = intArrayOf(12, 13, 14, 15)

    override fun handles(code: Int): Boolean =
        (if (plainButtons) BUTTONS_PLAIN else BUTTONS).containsKey(code)

    override fun setKey(code: Int, down: Boolean) {
        // View and Guide do not live among the buttons. The controller this imitates sends
        // them on the Consumer page — View as AC Back, the last bit of this report, and Guide as
        // AC Home in a report of its own. A host that knows this identity looks for them there
        // and nowhere else.
        // View used to be written to byte fifteen, as AC Back on the Consumer page — which is
        // what the model 1708 does and therefore what copying its descriptor gave us. macOS does
        // not translate it. Measured 2026-09-21 with PadScope: this pad produced a flawless
        // standard mapping, every index in place and matching two 8BitDo controllers exactly,
        // with one hole — buttons[8], View, where nothing arrived. So View is an ordinary button
        // now, at bit ten, immediately below Menu at eleven, where the Xbox layout puts it.
        //
        // Guide went the same way and further: it had a Consumer collection of its own, report
        // two, and it has never once worked — not in a gamepad tester, not in Steam, not in a
        // game. It is no longer sent at all, so it reaches Android as an ordinary Home press.
        // See the descriptor below for why that collection had to go regardless.
        // Guide has a home only in the faithful descriptor. Without it the press is not sent,
        // so it falls through to Android as an ordinary Home instead of being swallowed.
        if (consumer && !plainButtons && code == dev.lbento.thorsidepad.inject.Btn.MODE) {
            synchronized(system) { system[0] = if (down) 1 else 0 }
            return
        }
        val bit = (if (plainButtons) BUTTONS_PLAIN else BUTTONS)[code] ?: return
        val i = 13 + bit / 8
        val mask = 1 shl (bit % 8)
        synchronized(report) {
            report[i] = if (down) (report[i].toInt() or mask).toByte()
                        else (report[i].toInt() and mask.inv()).toByte()
        }
    }

    override fun setAbs(code: Int, value: Int) {
        synchronized(report) {
            when (code) {
                Abs.HAT0X -> { hatX = value; writeHat() }
                Abs.HAT0Y -> { hatY = value; writeHat() }
                Abs.X -> putShort(0, stick(value))
                Abs.Y -> putShort(2, stick(value))
                Abs.Z -> putShort(4, stick(value))
                Abs.RZ -> putShort(6, stick(value))
                Abs.BRAKE -> putShort(8, trigger(value))
                Abs.GAS -> putShort(10, trigger(value))
            }
        }
    }

    /** −127‥127 into 0‥65535, so the middle of the stick is the middle of the range. */
    private fun stick(v: Int): Int {
        val c = v.coerceIn(-127, 127)
        return ((c + 127) * 65535 / 254).coerceIn(0, 65535)
    }

    /** 0‥127 into the ten bits a trigger is given here. */
    private fun trigger(v: Int): Int = (v.coerceIn(0, 127) * 1023 / 127).coerceIn(0, 1023)

    private fun putShort(at: Int, v: Int) {
        report[at] = (v and 0xFF).toByte()
        report[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** One is north and it runs clockwise; centred is zero, which is the opposite of ours. */
    private fun writeHat() {
        val dir = when {
            hatY < 0 && hatX == 0 -> 1
            hatY < 0 && hatX > 0 -> 2
            hatY == 0 && hatX > 0 -> 3
            hatY > 0 && hatX > 0 -> 4
            hatY > 0 && hatX == 0 -> 5
            hatY > 0 && hatX < 0 -> 6
            hatY == 0 && hatX < 0 -> 7
            hatY < 0 && hatX < 0 -> 8
            else -> 0
        }
        report[12] = ((report[12].toInt() and 0xF0) or dir).toByte()
    }

    override fun snapshot(): ByteArray = synchronized(report) { report.copyOf() }

    /** Report two exists only in the faithful descriptor; null is what stops one being offered. */
    private val system = ByteArray(1)
    override fun systemSnapshot(): ByteArray? = if (consumer) synchronized(system) { system.copyOf() } else null

    /**
     * Reads a rumble instruction the way the pad this imitates describes one.
     *
     * Its output report carries a nibble saying which motors to run, then four magnitudes from
     * nought to a hundred — the two triggers first, then the two handles — and a duration. This
     * handheld has one motor where that pad has four, so the loudest of the four is what it gets;
     * a game asking for a gentle trigger tick and a hard left rumble at once means a hard rumble,
     * which is nearer the intent than averaging them into something limp.
     */
    override fun rumbleFrom(bytes: ByteArray): Int? {
        // Nothing asked for one: without the force-feedback collection a host has no report to
        // write here, so anything arriving is not a rumble instruction and must not be read as one.
        if (!rumble) return null
        if (bytes.size < 5) return null
        val enabled = bytes[0].toInt() and 0x0F
        if (enabled == 0) return 0
        var worst = 0
        for (i in 1..4) {
            val m = bytes[i].toInt() and 0xFF
            if (m > worst) worst = m
        }
        return (worst.coerceIn(0, 100) * 255 / 100)
    }

    private companion object {
        const val CENTRE = 0x8000

        /**
         * Why there is no Consumer page in this descriptor, when the controller it imitates has
         * two.
         *
         * The real pad declares a gamepad and, nested inside it, a second application collection
         * on the Consumer page — report two, AC Home — plus an AC Back field inside report one.
         * Copying the descriptor faithfully brought both across.
         *
         * Neither ever worked. View arrived nowhere at all (macOS does not translate AC Back
         * into a gamepad button, measured 2026-09-21), and Guide has never registered in a
         * gamepad tester, in Steam, or in any game tried.
         *
         * And they are suspected of worse. On 2026-09-21 at 00:05 WindowServer and
         * gamecontrollerd were caught in a livelock — one XPC connection opened and cancelled
         * about 1,800 times a second, started by a game launch, surviving that game's exit,
         * ending only when the controller was unplugged. WindowServer is the process that owns
         * input-permission validation, and a device presenting itself as both a gamepad and a
         * consumer-control device is exactly the shape that validation has to arbitrate. That
         * fits the evidence better than anything else considered, and it is the only theory so
         * far that explains why the peer was WindowServer specifically.
         *
         * It is a theory. What is certain is that these two collections cost us nothing, because
         * neither button they carry has ever worked. So they are gone, and whether the livelock
         * goes with them is a measurement waiting to be taken: attach the pad, launch a game,
         * and read driverflood.log.
         */
        /**
         * Where the force-feedback collection sits inside the descriptor, and what is left
         * without it.
         *
         * The real controller ends its report map with a Physical Interface Device collection —
         * report three, enable-actuators, four magnitudes, duration, delay, loop count — and that
         * collection is the whole of how a host learns there is a motor to drive, so leaving it
         * out is how a host that behaves badly when offered motors is told there are none.
         *
         * It was cut out first for a different reason, and that reason turned out to be wrong.
         * macOS runs _GCHapticServerManager's run loop for as long as this pad is connected —
         * 1.02 seconds of processor a minute on an untouched pad, against nothing at all for an
         * 8BitDo on the same machine — and taking this collection away did not change it: 1.07
         * with the map trimmed to 226 bytes, the host confirmed by ioreg to be holding the
         * trimmed one, and the same loop still on top of the sample. Whatever wakes that loop is
         * not this. Measured 2026-09-20.
         *
         * Everything before it is untouched and the application collection still has to be
         * closed, which is the last byte.
         */
        const val PID_AT = 176
        const val PID_END = 267

        /**
         * Ten buttons, in the order the name implies. The Thor's M1 and M2 have nowhere to go:
         * an Xbox pad has no such buttons, and inventing an eleventh would be describing a shape
         * no host is expecting. They are simply not sent in this mode.
         */
        /**
         * Where each button sits in the real controller's fifteen, which is the order every host
         * expects from this identity: A, B, C, X, Y, Z, the shoulders, the trigger clicks, View,
         * Menu, Guide, then the two stick clicks.
         *
         * Microsoft leaves C and Z unused, so the Thor's own extra pair lands in slots a host has
         * no name for but will still report. View and Guide are absent here on purpose: the real
         * controller sends those on the Consumer page instead, View as AC Back at the end of this
         * report and Guide as AC Home in a report of its own.
         */
        val BUTTONS = mapOf(
            dev.lbento.thorsidepad.inject.Btn.A to 0,
            dev.lbento.thorsidepad.inject.Btn.B to 1,
            dev.lbento.thorsidepad.inject.Btn.C to 2,
            dev.lbento.thorsidepad.inject.Btn.X to 3,
            dev.lbento.thorsidepad.inject.Btn.Y to 4,
            dev.lbento.thorsidepad.inject.Btn.Z to 5,
            dev.lbento.thorsidepad.inject.Btn.TL to 6,
            dev.lbento.thorsidepad.inject.Btn.TR to 7,
            dev.lbento.thorsidepad.inject.Btn.SELECT to 10,
            dev.lbento.thorsidepad.inject.Btn.START to 11,
            dev.lbento.thorsidepad.inject.Btn.THUMBL to 13,
            dev.lbento.thorsidepad.inject.Btn.THUMBR to 14,
        )

        /**
         * The same buttons, numbered straight through, for a host that does not know us.
         *
         * Nothing in a report map says which button is A. It declares fifteen buttons and the
         * host decides what they mean — from the vendor and product numbers if it recognises
         * them, and otherwise by counting: button one is A, two is B, three is X, four is Y,
         * then the shoulders, then Back and Start. The real controller's order leaves buttons
         * three and six empty, so a host that counts reads everything after B one or two places
         * late. Measured in Eastward on 2026-09-20: R1 arrived as Start, Y arrived as a
         * shoulder, X arrived as nothing, with Steam Input on and off alike.
         *
         * So this is the counted order, for those. It is wrong for anything that does recognise
         * the identity, which is why it is not the default and why the choice is the player's.
         * M1 and M2 have nowhere to go here — a counted host has no name for a ninth button that
         * every layout would read differently — so they are left out rather than sent somewhere
         * arbitrary.
         */
        val BUTTONS_PLAIN = mapOf(
            dev.lbento.thorsidepad.inject.Btn.A to 0,
            dev.lbento.thorsidepad.inject.Btn.B to 1,
            dev.lbento.thorsidepad.inject.Btn.X to 2,
            dev.lbento.thorsidepad.inject.Btn.Y to 3,
            dev.lbento.thorsidepad.inject.Btn.TL to 4,
            dev.lbento.thorsidepad.inject.Btn.TR to 5,
            dev.lbento.thorsidepad.inject.Btn.SELECT to 6,
            dev.lbento.thorsidepad.inject.Btn.START to 7,
            dev.lbento.thorsidepad.inject.Btn.MODE to 8,
            dev.lbento.thorsidepad.inject.Btn.THUMBL to 9,
            dev.lbento.thorsidepad.inject.Btn.THUMBR to 10,
        )

        /** Transcribed from a pad that works. The gamepad's own report; the extras are omitted. */
        /**
         * The faithful descriptor, Consumer collections and all, for hosts that may want them.
         *
         * This is what the real model 1708 sends: the gamepad, a Consumer AC Back inside report
         * one for View, and a nested Consumer application collection for Guide in report two.
         *
         * macOS wants none of it. Measured 2026-09-21: AC Back is never translated into a
         * gamepad button, so View simply did not exist there, and Guide has never registered in
         * any tester, in Steam, or in a game. The Consumer collections are also the leading
         * suspect for the WindowServer livelock. So Apple hosts get [DESCRIPTOR], which carries
         * no Consumer page at all.
         *
         * Every other platform is UNMEASURED. Windows, Linux and Android may well handle these
         * usages properly and hand back a working Guide button, which is the only reason this is
         * kept rather than deleted. Nobody has checked. Do not assume it is better anywhere —
         * find out, the way macOS was found out, by reading what actually arrives.
         */
        val WITH_CONSUMER: ByteArray = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x05.toByte(), 0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x01.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
            0x09.toByte(), 0x30.toByte(), 0x09.toByte(), 0x31.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(),
            0x02.toByte(), 0x75.toByte(), 0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(), 0x09.toByte(), 0x32.toByte(),
            0x09.toByte(), 0x35.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(), 0x02.toByte(), 0x75.toByte(),
            0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC5.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(),
            0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(),
            0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC4.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(),
            0xFF.toByte(), 0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x75.toByte(), 0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x39.toByte(), 0x15.toByte(), 0x01.toByte(),
            0x25.toByte(), 0x08.toByte(), 0x35.toByte(), 0x00.toByte(), 0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), 0x66.toByte(), 0x14.toByte(), 0x00.toByte(), 0x75.toByte(), 0x04.toByte(),
            0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x42.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x35.toByte(), 0x00.toByte(), 0x45.toByte(), 0x00.toByte(), 0x65.toByte(), 0x00.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x0F.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x0F.toByte(), 0x81.toByte(), 0x02.toByte(),
            0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x0C.toByte(),
            0x0A.toByte(), 0x24.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x81.toByte(),
            0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x07.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(),
            0x0C.toByte(), 0x09.toByte(), 0x01.toByte(), 0x85.toByte(), 0x02.toByte(), 0xA1.toByte(), 0x01.toByte(), 0x05.toByte(), 0x0C.toByte(), 0x0A.toByte(), 0x23.toByte(), 0x02.toByte(),
            0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x07.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0xC0.toByte(), 0x05.toByte(), 0x0F.toByte(), 0x09.toByte(),
            0x21.toByte(), 0x85.toByte(), 0x03.toByte(), 0xA1.toByte(), 0x02.toByte(), 0x09.toByte(), 0x97.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x75.toByte(),
            0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(),
            0x01.toByte(), 0x91.toByte(), 0x03.toByte(), 0x09.toByte(), 0x70.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x64.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(),
            0x04.toByte(), 0x91.toByte(), 0x02.toByte(), 0x09.toByte(), 0x50.toByte(), 0x66.toByte(), 0x01.toByte(), 0x10.toByte(), 0x55.toByte(), 0x0E.toByte(), 0x15.toByte(), 0x00.toByte(),
            0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x09.toByte(), 0xA7.toByte(), 0x15.toByte(),
            0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x65.toByte(), 0x00.toByte(),
            0x55.toByte(), 0x00.toByte(), 0x09.toByte(), 0x7C.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(),
            0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0xC0.toByte(), 0xC0.toByte()
        )

        /**
         * The Xbox Series X|S controller's own report map, read off the real thing.
         *
         * Dumped from `045e:0b13` over Bluetooth Low Energy on 2026-09-21 and used verbatim.
         * Its report one is byte-for-byte the layout this pad already sends — the same 16-bit
         * sticks, the same 10-bit triggers, the same hat, the same Buttons 1..15 at byte
         * thirteen — and its report three is an 8-byte force-feedback report like ours. The only
         * difference is byte fifteen, which carries that controller's Record button on the
         * Consumer page where this pad has padding.
         *
         * Why claim it: Steam holds a profile for 0b13 and none for the 1708 this pad has been
         * claiming. Measured in Eastward, a real Series controller maps correctly under Steam
         * Input while this pad is read positionally. Being the thing Steam already knows is the
         * cheapest way to fix that, and costs no new information — every byte here came off the
         * user's own controller.
         *
         * What it will not fix: native play. The real Series controller is routed to
         * IOHIDEventDummyService exactly as the 1708 is, and does not work in Eastward without
         * Steam Input either. Microsoft's numbers keep a pad off Apple's GameController path
         * whichever Microsoft pad is claimed.
         */
        val SERIES: ByteArray = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x05.toByte(), 0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x01.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
            0x09.toByte(), 0x30.toByte(), 0x09.toByte(), 0x31.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(),
            0x02.toByte(), 0x75.toByte(), 0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(), 0x09.toByte(), 0x32.toByte(),
            0x09.toByte(), 0x35.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(), 0x02.toByte(), 0x75.toByte(),
            0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC5.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(),
            0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(),
            0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC4.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(),
            0xFF.toByte(), 0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x75.toByte(), 0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x39.toByte(), 0x15.toByte(), 0x01.toByte(),
            0x25.toByte(), 0x08.toByte(), 0x35.toByte(), 0x00.toByte(), 0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), 0x66.toByte(), 0x14.toByte(), 0x00.toByte(), 0x75.toByte(), 0x04.toByte(),
            0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x42.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x35.toByte(), 0x00.toByte(), 0x45.toByte(), 0x00.toByte(), 0x65.toByte(), 0x00.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x0F.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x0F.toByte(), 0x81.toByte(), 0x02.toByte(),
            0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x0C.toByte(),
            0x0A.toByte(), 0xB2.toByte(), 0x00.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x81.toByte(),
            0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x07.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(),
            0x0F.toByte(), 0x09.toByte(), 0x21.toByte(), 0x85.toByte(), 0x03.toByte(), 0xA1.toByte(), 0x02.toByte(), 0x09.toByte(), 0x97.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(),
            0x01.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(),
            0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x03.toByte(), 0x09.toByte(), 0x70.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x64.toByte(), 0x75.toByte(),
            0x08.toByte(), 0x95.toByte(), 0x04.toByte(), 0x91.toByte(), 0x02.toByte(), 0x09.toByte(), 0x50.toByte(), 0x66.toByte(), 0x01.toByte(), 0x10.toByte(), 0x55.toByte(), 0x0E.toByte(),
            0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x09.toByte(),
            0xA7.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(),
            0x65.toByte(), 0x00.toByte(), 0x55.toByte(), 0x00.toByte(), 0x09.toByte(), 0x7C.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(),
            0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0xC0.toByte(), 0xC0.toByte()
        )

        private val NO_RUMBLE: ByteArray by lazy {
            DESCRIPTOR.copyOfRange(0, PID_AT) + DESCRIPTOR.copyOfRange(PID_END, DESCRIPTOR.size)
        }

        val DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x05.toByte(), 0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x01.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
            0x09.toByte(), 0x30.toByte(), 0x09.toByte(), 0x31.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(),
            0x02.toByte(), 0x75.toByte(), 0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(), 0x09.toByte(), 0x32.toByte(),
            0x09.toByte(), 0x35.toByte(), 0x15.toByte(), 0x00.toByte(), 0x27.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x95.toByte(), 0x02.toByte(), 0x75.toByte(),
            0x10.toByte(), 0x81.toByte(), 0x02.toByte(), 0xC0.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC5.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(),
            0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(),
            0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x02.toByte(), 0x09.toByte(), 0xC4.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(),
            0xFF.toByte(), 0x03.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x0A.toByte(), 0x81.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x75.toByte(), 0x06.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x39.toByte(), 0x15.toByte(), 0x01.toByte(),
            0x25.toByte(), 0x08.toByte(), 0x35.toByte(), 0x00.toByte(), 0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), 0x66.toByte(), 0x14.toByte(), 0x00.toByte(), 0x75.toByte(), 0x04.toByte(),
            0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x42.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
            0x35.toByte(), 0x00.toByte(), 0x45.toByte(), 0x00.toByte(), 0x65.toByte(), 0x00.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x0F.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x0F.toByte(), 0x81.toByte(), 0x02.toByte(),
            0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x0F.toByte(), 0x09.toByte(), 0x21.toByte(),
            0x85.toByte(), 0x03.toByte(), 0xA1.toByte(), 0x02.toByte(), 0x09.toByte(), 0x97.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x04.toByte(),
            0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(), 0x75.toByte(), 0x04.toByte(), 0x95.toByte(), 0x01.toByte(),
            0x91.toByte(), 0x03.toByte(), 0x09.toByte(), 0x70.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x64.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x04.toByte(),
            0x91.toByte(), 0x02.toByte(), 0x09.toByte(), 0x50.toByte(), 0x66.toByte(), 0x01.toByte(), 0x10.toByte(), 0x55.toByte(), 0x0E.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(),
            0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x09.toByte(), 0xA7.toByte(), 0x15.toByte(), 0x00.toByte(),
            0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x91.toByte(), 0x02.toByte(), 0x65.toByte(), 0x00.toByte(), 0x55.toByte(),
            0x00.toByte(), 0x09.toByte(), 0x7C.toByte(), 0x15.toByte(), 0x00.toByte(), 0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(),
            0x91.toByte(), 0x02.toByte(), 0xC0.toByte(), 0xC0.toByte()
        
        )
    }
}
