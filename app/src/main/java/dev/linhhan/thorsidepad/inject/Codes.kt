package dev.linhhan.thorsidepad.inject

/** Linux input event constants (linux/input-event-codes.h) and the gamepad catalogue the pad can emit. */
object Ev {
    const val SYN = 0x00
    const val KEY = 0x01
    const val ABS = 0x03
    const val SYN_REPORT = 0
}

object Abs {
    const val X = 0x00
    const val Y = 0x01
    const val Z = 0x02
    const val RX = 0x03
    const val RY = 0x04
    const val RZ = 0x05
    const val GAS = 0x09
    const val BRAKE = 0x0a
    const val HAT0X = 0x10
    const val HAT0Y = 0x11
}

/** Keys outside the gamepad range: the AYN key that opens the Thor Control Center. */
object Key {
    const val HOME = 0x66        // KEY_HOME on the Thor controller node: the Home button
    const val BACK = 0x9e        // KEY_BACK on the Thor controller node: the Back button
    const val F24 = 0xc2         // KEY_F24 on gpio-keys: the AYN key that opens the Control Center
}

/** Pseudo-codes for the two analogue sticks (negative so they never collide with a Linux key). */
object Stick {
    const val LEFT = -1
    const val RIGHT = -2
}

/** Pseudo-codes for pad buttons that act on the pad itself instead of the game. */
object Action {
    const val SHIELD = -3   // toggle shield / islands mode
}

fun isStickCode(code: Int) = code == Stick.LEFT || code == Stick.RIGHT
fun isActionCode(code: Int) = code == Action.SHIELD

object Btn {
    const val A = 0x130          // BTN_SOUTH
    const val B = 0x131          // BTN_EAST
    const val C = 0x132          // BTN_C: the Thor's key layouts map it to BUTTON_C; used as M1
    const val X = 0x133          // BTN_NORTH
    const val Y = 0x134          // BTN_WEST
    const val Z = 0x135          // BTN_Z: mapped to BUTTON_Z on the Thor; used as M2
    const val TL = 0x136
    const val TR = 0x137
    const val TL2 = 0x138
    const val TR2 = 0x139
    const val SELECT = 0x13a
    const val START = 0x13b
    const val MODE = 0x13c
    const val THUMBL = 0x13d
    const val THUMBR = 0x13e
    const val DPAD_UP = 0x220
    const val DPAD_DOWN = 0x221
    const val DPAD_LEFT = 0x222
    const val DPAD_RIGHT = 0x223
}

/**
 * One button the pad can show. `code` is the Linux key code; `androidName` is what a gamepad
 * tester on the top screen will display for it (from the framework's Generic.kl).
 */
data class PadCode(val code: Int, val label: String, val androidName: String) {
    val isDpad get() = code in Btn.DPAD_UP..Btn.DPAD_RIGHT
    val isTrigger get() = code == Btn.TL2 || code == Btn.TR2
    val isExtra get() = code == Btn.C || code == Btn.Z
    val isStick get() = isStickCode(code)
    val isAction get() = isActionCode(code)
}

object Catalog {
    val all: List<PadCode> = listOf(
        PadCode(Btn.A, "A", "BUTTON_A"),
        PadCode(Btn.B, "B", "BUTTON_B"),
        PadCode(Btn.X, "X", "BUTTON_X"),
        PadCode(Btn.Y, "Y", "BUTTON_Y"),
        PadCode(Btn.TL, "L1", "BUTTON_L1"),
        PadCode(Btn.TR, "R1", "BUTTON_R1"),
        PadCode(Btn.TL2, "L2", "BUTTON_L2"),
        PadCode(Btn.TR2, "R2", "BUTTON_R2"),
        PadCode(Btn.THUMBL, "L3", "BUTTON_THUMBL"),
        PadCode(Btn.THUMBR, "R3", "BUTTON_THUMBR"),
        PadCode(Btn.SELECT, "SEL", "BUTTON_SELECT"),
        PadCode(Btn.START, "START", "BUTTON_START"),
        PadCode(Btn.MODE, "HOME", "BUTTON_MODE"),
        PadCode(Btn.DPAD_UP, "▲", "DPAD_UP"),
        PadCode(Btn.DPAD_DOWN, "▼", "DPAD_DOWN"),
        PadCode(Btn.DPAD_LEFT, "◀", "DPAD_LEFT"),
        PadCode(Btn.DPAD_RIGHT, "▶", "DPAD_RIGHT"),
        // Extra buttons the Thor has no physical key for. Its controller node still advertises
        // BTN_C/BTN_Z and AYN's key layouts map them, so they work in both delivery modes.
        // (BTN_TRIGGER_HAPPY1.. would be BUTTON_1.. on stock Android, but the Thor's kernel
        // stamps every uinput pad with AYN's vendor/product ids, whose layout lacks them.)
        PadCode(Btn.C, "M1", "BUTTON_C"),
        PadCode(Btn.Z, "M2", "BUTTON_Z"),
        PadCode(Stick.LEFT, "LS", "left stick, AXIS_X / AXIS_Y"),
        PadCode(Stick.RIGHT, "RS", "right stick, AXIS_Z / AXIS_RZ"),
        PadCode(Action.SHIELD, "SHLD", "toggles the shield on / off"),
    )

    fun byCode(code: Int): PadCode = all.firstOrNull { it.code == code } ?: PadCode(code, "0x%x".format(code), "KEY_$code")

    /** Every key the virtual pad declares: the whole button catalogue. */
    val virtualKeys: IntArray = all.filter { it.code >= 0 }.map { it.code }.toIntArray()

    /** Axes the virtual pad declares, laid out like the Thor's own controller: X/Y left stick, Z/RZ right stick, GAS/BRAKE triggers. */
    val virtualAbs = listOf(
        Triple(Abs.X, -32767, 32767), Triple(Abs.Y, -32767, 32767),
        Triple(Abs.Z, -32767, 32767), Triple(Abs.RZ, -32767, 32767),
        Triple(Abs.GAS, 0, 32767), Triple(Abs.BRAKE, 0, 32767),
        Triple(Abs.HAT0X, -1, 1), Triple(Abs.HAT0Y, -1, 1),
    )
}
