package dev.lbento.thorsidepad.pad

/**
 * Latch state shared by the pad's windows in islands mode: whether HOLD is armed (the next
 * button tapped latches) and which button codes are latched down right now.
 */
class LatchSession {
    var holdArmed = false
    val latched = HashSet<Int>()
    /** Called when the armed state changes so every HOLD element can redraw. */
    var onArmChanged: (() -> Unit)? = null

    fun arm(on: Boolean) { if (holdArmed != on) { holdArmed = on; onArmChanged?.invoke() } }
}
