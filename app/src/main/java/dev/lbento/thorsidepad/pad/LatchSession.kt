package dev.lbento.thorsidepad.pad

/**
 * Latch state shared by the pad's windows in islands mode: whether HOLD is armed (the next
 * button tapped latches) and which button codes are latched down right now.
 */
class LatchSession {
    var holdArmed = false
    var turboArmed = false
    val latched = HashSet<Int>()
    /** The latched codes that are pulsing rather than simply held. */
    val turboLatched = HashSet<Int>()
    /** Called when the armed state changes so every HOLD and TURBO element can redraw. */
    var onArmChanged: (() -> Unit)? = null

    /** Only one of the two can be armed; arming either clears the other. */
    fun arm(on: Boolean) { if (holdArmed != on || (on && turboArmed)) { holdArmed = on; if (on) turboArmed = false; onArmChanged?.invoke() } }
    fun armTurbo(on: Boolean) { if (turboArmed != on || (on && holdArmed)) { turboArmed = on; if (on) holdArmed = false; onArmChanged?.invoke() } }
    fun disarm() { if (holdArmed || turboArmed) { holdArmed = false; turboArmed = false; onArmChanged?.invoke() } }
}
