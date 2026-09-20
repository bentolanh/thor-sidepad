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
) : ReportShape {
    private val report = ByteArray(16)
    /** Report two: one bit, the button in the middle, exactly as the real controller sends it. */
    private val system = ByteArray(1)
    private var hatX = 0
    private var hatY = 0

    init {
        // Sticks rest at the middle of an unsigned range, not at zero.
        putShort(0, CENTRE); putShort(2, CENTRE); putShort(4, CENTRE); putShort(6, CENTRE)
    }

    override val descriptor = if (rumble) DESCRIPTOR else NO_RUMBLE
    override val discreteBytes = intArrayOf(12, 13, 14, 15)

    override fun handles(code: Int): Boolean =
        (if (plainButtons) BUTTONS_PLAIN else BUTTONS).containsKey(code)

    override fun setKey(code: Int, down: Boolean) {
        // View and Guide do not live among the buttons. The controller this imitates sends
        // them on the Consumer page — View as AC Back, the last bit of this report, and Guide as
        // AC Home in a report of its own. A host that knows this identity looks for them there
        // and nowhere else.
        // Only where the host knows the identity. A host that counts buttons expects Back and
        // Guide among them, at seven and nine, and never looks at the Consumer page at all —
        // so in that order they are ordinary buttons and fall through to the table below.
        if (!plainButtons && code == dev.lbento.thorsidepad.inject.Btn.SELECT) {
            synchronized(report) {
                report[15] = if (down) 1 else 0
            }
            return
        }
        if (!plainButtons && code == dev.lbento.thorsidepad.inject.Btn.MODE) {
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

    override fun systemSnapshot(): ByteArray = synchronized(system) { system.copyOf() }

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
        const val PID_AT = 225
        const val PID_END = 316

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
    }
}
