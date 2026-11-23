package com.mantis.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mantis.scheduler.ProactiveScheduler

/**
 * Receives [Intent.ACTION_BOOT_COMPLETED] and re-enqueues all enabled scheduled tasks.
 *
 * WorkManager periodic tasks survive reboots only if the app has been opened at least
 * once since the last boot. This receiver guarantees tasks are always active regardless
 * of whether the user manually relaunched MANTIS after a restart.
 *
 * Requires: RECEIVE_BOOT_COMPLETED permission (declared in AndroidManifest.xml).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — rescheduling all enabled tasks")
        ProactiveScheduler.rescheduleAll(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
