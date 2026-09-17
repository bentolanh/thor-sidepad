package dev.lbento.thorsidepad.inject

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

/** One D-pad element: a cross pressed in any of eight directions, delivered as the four DPAD buttons. */
object Dpad {
    const val PAD = -19
}

/** Pseudo-codes for pad buttons that act on the pad or the device instead of the game. */
object Action {
    const val SHIELD = -3       // toggle shield / islands mode
    const val HOME_TOP = -13    // Home on the main screen
    const val HOME_2ND = -14    // Home on the pad's screen (the Thor's own Home key)
    const val BACK_TOP = -15    // Back on the main screen
    const val BACK_2ND = -16    // Back on the pad's screen
    const val HOLD = -20        // arms a latch: the next button tapped stays down until tapped again
    const val TURBO = -21       // arms a repeat: the next button tapped pulses until tapped again
    const val MEDIA_PREV = -22  // whatever is playing: previous track
    const val MEDIA_PLAY = -23  // play or pause
    const val MEDIA_NEXT = -24  // next track
    const val MEDIA = -25       // the media unit itself: one element carrying the three above
    const val VIDEO = -27       // the video unit: a timeline with jump back and forward
    const val VIDEO_BACK = -28  // jump back ten seconds
    const val VIDEO_FWD = -29   // jump forward ten seconds
}

/** The verbs the media unit sends: keys handed to whatever is playing, whichever app that is. */
fun isMediaCode(code: Int) = code == Action.MEDIA_PREV || code == Action.MEDIA_PLAY || code == Action.MEDIA_NEXT

/** The media unit is one wide element, not three buttons; a tap picks a verb by where it lands. */
fun isMediaUnit(code: Int) = code == Action.MEDIA

/** The video unit: a timeline you drag to seek, with jump back and forward either side of play. */
fun isVideoUnit(code: Int) = code == Action.VIDEO

/** Which verb a tap at [f], measured 0..1 across the unit, means. */
fun mediaCodeAt(f: Float) = when {
    f < 1f / 3f -> Action.MEDIA_PREV
    f < 2f / 3f -> Action.MEDIA_PLAY
    else -> Action.MEDIA_NEXT
}

/** Pseudo-codes for sliders that set a device level, 0..1. */
object Slider {
    const val BRIGHT_TOP = -10
    const val BRIGHT_2ND = -11
    const val VOLUME = -12
    const val VOLUME_2ND = -17   // the Thor's second-screen volume (AYN's secondary_screen_volume_level setting)
    const val BRIGHT_BOTH = -18  // one slider that moves both screens' brightness together
    /**
     * The media unit's own track. Not a placeable element: it stands for "the volume of whatever
     * is playing", which the service resolves to this screen's volume or the main screen's,
     * depending on which screen the app holding the media keys is on.
     */
    const val VOLUME_MEDIA = -26

    /** The video unit's timeline, as a fraction of the whole; the service turns it into a position. */
    const val VIDEO_SEEK = -30
}

fun isStickCode(code: Int) = code == Stick.LEFT || code == Stick.RIGHT
fun isActionCode(code: Int) = code == Action.SHIELD || code == Action.HOME_TOP || code == Action.HOME_2ND || code == Action.BACK_TOP || code == Action.BACK_2ND || isMediaUnit(code) || isVideoUnit(code)
fun isDpadCode(code: Int) = code == Dpad.PAD
fun isSliderCode(code: Int) = code == Slider.BRIGHT_TOP || code == Slider.BRIGHT_2ND || code == Slider.VOLUME || code == Slider.VOLUME_2ND || code == Slider.BRIGHT_BOTH

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
    val isDpadElement get() = isDpadCode(code)
    val isStickClick get() = code == Btn.THUMBL || code == Btn.THUMBR   // L3 / R3: pressing a stick in
    val isAction get() = isActionCode(code)
    val isSlider get() = isSliderCode(code)
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
        PadCode(Dpad.PAD, "✚", "D-pad, DPAD_UP / DOWN / LEFT / RIGHT"),
        PadCode(Btn.C, "M1", "BUTTON_C"),
        PadCode(Btn.Z, "M2", "BUTTON_Z"),
        PadCode(Stick.LEFT, "LS", "left stick, AXIS_X / AXIS_Y"),
        PadCode(Stick.RIGHT, "RS", "right stick, AXIS_Z / AXIS_RZ"),
        PadCode(Action.HOLD, "HOLD", "the next button you tap stays down until you tap it again"),
        PadCode(Action.TURBO, "TURBO", "the next button you tap repeats until you tap it again"),
        PadCode(Action.MEDIA, "MEDIA", "one unit: previous, play or pause, next"),
        PadCode(Action.VIDEO, "VIDEO", "a timeline you drag, with jump back and forward ten seconds"),
        PadCode(Action.SHIELD, "SHLD", "toggles the shield on / off"),
        PadCode(Action.HOME_TOP, "HOME", "Home on the main screen"),
        PadCode(Action.HOME_2ND, "HOME", "Home on this screen"),
        PadCode(Action.BACK_TOP, "BACK", "Back on the main screen"),
        PadCode(Action.BACK_2ND, "BACK", "Back on this screen"),
        PadCode(Slider.BRIGHT_TOP, "☀", "brightness slider, main screen"),
        PadCode(Slider.BRIGHT_2ND, "☀", "brightness slider, this screen"),
        PadCode(Slider.BRIGHT_BOTH, "☀", "brightness slider, both screens together"),
        PadCode(Slider.VOLUME, "♪", "volume slider, main screen"),
        PadCode(Slider.VOLUME_2ND, "♪", "volume slider, this screen"),
    )

    /**
     * The Add picker's pages, grouped by what pressing the thing affects: Controller goes to the
     * game, Macro changes how the pad's own buttons behave, System acts on the device and its
     * screens. The shield sits in System because it governs how this screen behaves, next to that
     * screen's brightness and its Home and Back.
     */
    val groups: List<Pair<String, List<PadCode>>> = listOf(
        // The four single-direction buttons stay out of the picker; the one-element D-pad replaces them.
        "Controller" to all.filter { (it.code > 0 && !it.isStickClick && !it.isDpad) || it.isDpadElement } +
            all.filter { it.isStick } + all.filter { it.isStickClick },
        "Macro" to all.filter { it.code == Action.HOLD || it.code == Action.TURBO },
        // Everything the device itself answers: its screens, what is playing, its levels, and the
        // shield. Media sits here rather than in a page of its own until there is more of it.
        "System" to all.filter { it.isAction && !isMediaUnit(it.code) && !isVideoUnit(it.code) && it.code != Action.SHIELD } +
            all.filter { isMediaUnit(it.code) || isVideoUnit(it.code) } +
            all.filter { it.isSlider } + all.filter { it.code == Action.SHIELD },
    )

    /** Small tag drawn under a Home/Back/slider element: which screen it acts on. */
    fun screenTag(code: Int): String? = when (code) {
        Action.HOME_TOP, Action.BACK_TOP, Slider.BRIGHT_TOP, Slider.VOLUME -> "top"
        Action.HOME_2ND, Action.BACK_2ND, Slider.BRIGHT_2ND, Slider.VOLUME_2ND -> "2nd"
        Slider.BRIGHT_BOTH -> "both"
        else -> null
    }

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
