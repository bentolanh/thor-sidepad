package dev.lbento.thorsidepad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lbento.thorsidepad.pad.OverlayService

/**
 * The entry point for driving the pad from outside the app: adb, Tasker, a vendor panel.
 *
 *   am broadcast -a dev.lbento.thorsidepad.SHOW -n dev.lbento.thorsidepad/.TriggerReceiver
 *
 * A receiver rather than an activity on purpose. Starting any activity on the top screen paused
 * whatever was playing there with userLeaving set, and a video app reads that as the user walking
 * away and drops itself into picture-in-picture. A broadcast never enters the task stack, so the
 * app on the top screen does not notice the pad being told what to do.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action.startsWith("dev.lbento.thorsidepad.")) OverlayService.send(context, action)
    }
}
