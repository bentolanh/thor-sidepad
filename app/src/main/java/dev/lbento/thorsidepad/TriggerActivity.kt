package dev.lbento.thorsidepad

import android.app.Activity
import android.os.Bundle
import dev.lbento.thorsidepad.pad.OverlayService

/**
 * Invisible, exported entry point for launcher shortcuts, which can start an activity and nothing
 * else. Everything that can send a broadcast should use [TriggerReceiver] instead: starting an
 * activity pauses the app on the top screen with userLeaving set, and a video app takes that as
 * the user walking away and drops into picture-in-picture.
 *
 *   am start -f 0x10040000 -n dev.lbento.thorsidepad/.TriggerActivity -a dev.lbento.thorsidepad.SHOW
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
