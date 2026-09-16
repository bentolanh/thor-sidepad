package dev.lbento.thorsidepad

import android.app.Activity
import android.os.Bundle
import dev.lbento.thorsidepad.pad.OverlayService

/**
 * Invisible, exported entry point so other things can drive the pad: adb, Tasker, launcher
 * shortcuts, or a vendor panel. It forwards its action to the service and finishes at once.
 *
 *   am start -n dev.lbento.thorsidepad/.TriggerActivity -a dev.lbento.thorsidepad.SHOW
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action != null && action.startsWith("dev.lbento.thorsidepad.")) OverlayService.send(this, action)
        finish()
        overridePendingTransition(0, 0)
    }
}
