package dev.linhhan.thorsidepad

import android.app.Activity
import android.os.Bundle
import dev.linhhan.thorsidepad.pad.OverlayService

/**
 * Invisible, exported entry point so other things can drive the pad: adb, Tasker, launcher
 * shortcuts, or a vendor panel. It forwards its action to the service and finishes at once.
 *
 *   am start -n dev.linhhan.thorsidepad/.TriggerActivity -a dev.linhhan.thorsidepad.SHOW
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action != null && action.startsWith("dev.linhhan.thorsidepad.")) OverlayService.send(this, action)
        finish()
        overridePendingTransition(0, 0)
    }
}
