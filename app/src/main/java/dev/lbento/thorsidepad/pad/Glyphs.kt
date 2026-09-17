package dev.lbento.thorsidepad.pad

import android.graphics.Color
import dev.lbento.thorsidepad.inject.Btn
import dev.lbento.thorsidepad.inject.Catalog

/**
 * How a preset labels its buttons. The presses never change (BUTTON_A is BUTTON_A); only
 * the glyph drawn on the circle does, so a layout can read like the pad of the console
 * being emulated. Xbox and PlayStation use each maker's colours; Nintendo stays white.
 */
object Glyphs {
    const val XBOX = "xbox"
    const val PLAYSTATION = "ps"
    const val NINTENDO = "nintendo"

    val styles = listOf(XBOX to "Xbox", PLAYSTATION to "PlayStation", NINTENDO to "Nintendo")
    fun name(style: String) = styles.firstOrNull { it.first == style }?.second ?: "Xbox"

    fun label(code: Int, style: String): String = when (style) {
        PLAYSTATION -> when (code) {
            Btn.A -> "✕"; Btn.B -> "○"; Btn.X -> "□"; Btn.Y -> "△"
            Btn.TL -> "L1"; Btn.TR -> "R1"; Btn.TL2 -> "L2"; Btn.TR2 -> "R2"
            Btn.SELECT -> "SELECT"; Btn.START -> "START"; Btn.MODE -> "PS"
            else -> Catalog.byCode(code).label
        }
        NINTENDO -> when (code) {
            // Nintendo's letters sit the other way round: B south, A east, Y west, X north.
            Btn.A -> "B"; Btn.B -> "A"; Btn.X -> "Y"; Btn.Y -> "X"
            Btn.TL -> "L"; Btn.TR -> "R"; Btn.TL2 -> "ZL"; Btn.TR2 -> "ZR"
            Btn.SELECT -> "−"; Btn.START -> "+"; Btn.MODE -> "HOME"
            else -> Catalog.byCode(code).label
        }
        else -> when (code) {
            Btn.TL -> "LB"; Btn.TR -> "RB"; Btn.TL2 -> "LT"; Btn.TR2 -> "RT"
            Btn.SELECT -> "VIEW"; Btn.START -> "MENU"
            else -> Catalog.byCode(code).label
        }
    }

    /** 0 = draw the label, 1 = the View panes, 2 = the Menu bars. Xbox prints icons, not words. */
    fun icon(code: Int, style: String): Int = when {
        style == XBOX && code == Btn.SELECT -> 1          // the View panes
        style == XBOX && code == Btn.START -> 2           // the Menu bars
        style == PLAYSTATION && code == Btn.SELECT -> 3   // the Select oval
        style == PLAYSTATION && code == Btn.START -> 4    // the Start triangle
        else -> 0
    }

    fun color(code: Int, style: String): Int = when (style) {
        PLAYSTATION -> when (code) {
            Btn.A -> 0xFF8FB3F0.toInt(); Btn.B -> 0xFFF08A82.toInt(); Btn.X -> 0xFFF0A8D0.toInt(); Btn.Y -> 0xFF9CDCAE.toInt()
            else -> Color.WHITE
        }
        NINTENDO -> Color.WHITE
        else -> when (code) {
            Btn.A -> 0xFF8BD46A.toInt(); Btn.B -> 0xFFF06A66.toInt(); Btn.X -> 0xFF6FB0F0.toInt(); Btn.Y -> 0xFFF5D35C.toInt()
            else -> Color.WHITE
        }
    }
}
