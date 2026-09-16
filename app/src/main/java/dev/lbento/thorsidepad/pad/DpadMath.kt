package dev.lbento.thorsidepad.pad

import dev.lbento.thorsidepad.inject.Btn
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Direction bits of the one-element D-pad and how a thumb position maps to them. */
object DpadMath {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 4
    const val RIGHT = 8

    /** Which directions a touch at (dx, dy), in units of the pad radius, holds down. Eight sectors, dead centre. */
    fun maskAt(dx: Float, dy: Float): Int {
        if (hypot(dx, dy) < 0.22f) return 0
        val sector = ((Math.toDegrees(atan2(dy, dx).toDouble()) / 45.0).roundToInt() + 8) % 8
        return when (sector) {
            0 -> RIGHT
            1 -> DOWN or RIGHT
            2 -> DOWN
            3 -> DOWN or LEFT
            4 -> LEFT
            5 -> UP or LEFT
            6 -> UP
            else -> UP or RIGHT
        }
    }

    fun code(bit: Int): Int = when (bit) {
        UP -> Btn.DPAD_UP
        DOWN -> Btn.DPAD_DOWN
        LEFT -> Btn.DPAD_LEFT
        else -> Btn.DPAD_RIGHT
    }

    /** Presses the directions newly set in [new] and releases the ones no longer in it. */
    fun apply(engine: PadEngine, old: Int, new: Int) {
        for (bit in intArrayOf(UP, DOWN, LEFT, RIGHT)) {
            val was = old and bit != 0; val now = new and bit != 0
            if (now && !was) engine.press(code(bit)) else if (was && !now) engine.release(code(bit))
        }
    }
}
