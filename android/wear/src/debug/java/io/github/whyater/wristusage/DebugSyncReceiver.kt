package io.github.whyater.wristusage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.work.Refresher

/**
 * Debug builds only. Stands in for the phone on an emulator with no paired phone:
 * `adb shell am broadcast -a io.github.whyater.wristusage.DEBUG_INGEST -p io.github.whyater.wristusage
 * --es url <url> --es json <snapshot json>`. Both extras are optional.
 */
class DebugSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = SnapshotRepository(context.applicationContext)
        var changed = false
        intent.getStringExtra("url")?.let { changed = repo.storeSnapshotUrl(it) || changed }
        intent.getStringExtra("json")?.let { changed = repo.ingest(it, System.currentTimeMillis()) || changed }
        if (changed) {
            Refresher.schedulePeriodic(context)
            Refresher.requestSurfaceUpdates(context)
        }
    }
}
