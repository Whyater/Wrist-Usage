package io.github.whyater.wristusage.work

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.whyater.wristusage.BuildConfig
import io.github.whyater.wristusage.complication.UsageComplicationService
import io.github.whyater.wristusage.tile.UsageTileService
import java.util.concurrent.TimeUnit

/** Schedules background refreshes and pushes new data to the tile and the complication. */
object Refresher {
    private const val PERIODIC_NAME = "usage-periodic"
    private const val NOW_NAME = "usage-now"

    /** Nothing to download without a snapshot URL, so nothing is scheduled. */
    private val configured: Boolean get() = BuildConfig.SNAPSHOT_URL.isNotBlank()

    /** Safe to call repeatedly: an existing periodic job is updated in place, keeping its schedule. */
    fun schedulePeriodic(context: Context) {
        if (!configured) return
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** One refresh as soon as the network allows. Extra calls while one is queued are ignored. */
    fun refreshNow(context: Context) {
        if (!configured) return
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun requestSurfaceUpdates(context: Context) {
        TileService.getUpdater(context).requestUpdate(UsageTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, UsageComplicationService::class.java))
            .requestUpdateAll()
    }

    private fun networkConstraint() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
