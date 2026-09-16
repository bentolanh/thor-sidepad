package dev.lbento.thorsidepad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lbento.thorsidepad.pad.OverlayService

/** Brings the service (and its pull-down catcher) back after a reboot, if the user wants that. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).startAtBoot) OverlayService.send(context, OverlayService.ACTION_START)
    }
}
