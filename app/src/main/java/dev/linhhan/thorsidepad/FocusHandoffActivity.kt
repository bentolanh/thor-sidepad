package dev.linhhan.thorsidepad

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible activity started on the top screen for a moment. Android moves the focused display
 * to wherever the newest focused window is, so after the pad's one focusable dialog (the preset
 * name box) closes, this puts focus back on the top screen, where the game is.
 */
class FocusHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask(); overridePendingTransition(0, 0) }, 400)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { finishAndRemoveTask(); overridePendingTransition(0, 0) }
    }
}
