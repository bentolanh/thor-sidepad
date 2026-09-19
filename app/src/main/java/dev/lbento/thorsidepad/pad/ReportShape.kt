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
class XboxShape : ReportShape {
    private val report = ByteArray(15)
    private var hatX = 0
    private var hatY = 0

    init {
        // Sticks rest at the middle of an unsigned range, not at zero.
        putShort(0, CENTRE); putShort(2, CENTRE); putShort(4, CENTRE); putShort(6, CENTRE)
    }

    override val descriptor = DESCRIPTOR
    override val discreteBytes = intArrayOf(12, 13, 14)

    override fun setKey(code: Int, down: Boolean) {
        val bit = BUTTONS[code] ?: return          // no room for the Thor's extra pair here
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

    private companion object {
        const val CENTRE = 0x8000

        /**
         * Ten buttons, in the order the name implies. The Thor's M1 and M2 have nowhere to go:
         * an Xbox pad has no such buttons, and inventing an eleventh would be describing a shape
         * no host is expecting. They are simply not sent in this mode.
         */
        val BUTTONS = mapOf(
            dev.lbento.thorsidepad.inject.Btn.A to 0,
            dev.lbento.thorsidepad.inject.Btn.B to 1,
            dev.lbento.thorsidepad.inject.Btn.X to 2,
            dev.lbento.thorsidepad.inject.Btn.Y to 3,
            dev.lbento.thorsidepad.inject.Btn.TL to 4,
            dev.lbento.thorsidepad.inject.Btn.TR to 5,
            dev.lbento.thorsidepad.inject.Btn.SELECT to 6,
            dev.lbento.thorsidepad.inject.Btn.START to 7,
            dev.lbento.thorsidepad.inject.Btn.THUMBL to 8,
            dev.lbento.thorsidepad.inject.Btn.THUMBR to 9,
        )

        /** Transcribed from a pad that works. The gamepad's own report; the extras are omitted. */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            // Both sticks, four sixteen-bit unsigned axes.
            //
            // The pad these bytes were copied from wraps each stick in a Usage(Pointer) physical
            // collection, and that wrapper is left out here deliberately. It makes a host read the
            // device as a gamepad *and* a pointing device, and macOS answers that by publishing a
            // second, inert copy of the pad alongside the real one — an `IOHIDEventDummyService`
            // sharing its LocationID. A game that enumerates raw HID then has two pads to choose
            // between and no way to tell that one of them never speaks. The axes, their usages,
            // their widths and their order in the report are all unchanged; only the wrapping is
            // gone, because nothing needs it.
            0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x00, 0x27, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
            0x95.toByte(), 0x04, 0x75, 0x10, 0x81.toByte(), 0x02,
            // left trigger: ten bits of travel, six of padding
            0x05, 0x01, 0x09, 0x32, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x03,
            0x95.toByte(), 0x01, 0x75, 0x0A, 0x81.toByte(), 0x02,
            0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            // right trigger
            0x05, 0x01, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x03,
            0x95.toByte(), 0x01, 0x75, 0x0A, 0x81.toByte(), 0x02,
            0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            // hat, one to eight, with a null state for centred
            0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08,
            0x35, 0x00, 0x46.toByte(), 0x3B, 0x01, 0x66, 0x14, 0x00,
            0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
            0x75, 0x04, 0x95.toByte(), 0x01, 0x15, 0x00, 0x25, 0x00,
            0x35, 0x00, 0x45, 0x00, 0x65, 0x00, 0x81.toByte(), 0x03,
            // ten buttons, then six bits of nothing
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0A, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x0A, 0x81.toByte(), 0x02,
            0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            0xC0.toByte(),
        )
    }
}
