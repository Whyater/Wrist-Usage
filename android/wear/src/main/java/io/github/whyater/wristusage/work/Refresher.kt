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
import androidx.work.workDataOf
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.github.whyater.wristusage.complication.UsageComplicationService
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.sync.SyncContract
import io.github.whyater.wristusage.tile.UsageTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Schedules background refreshes and pushes new data to the tile and the complication. */
object Refresher {
    private const val PERIODIC_NAME = "usage-periodic"
    private const val NOW_NAME = "usage-now"
    private const val PHONE_TIMEOUT_MS = 5_000L

    /** Safe to call repeatedly: an existing periodic job is updated in place, keeping its schedule. */
    fun schedulePeriodic(context: Context) {
        val appContext = context.applicationContext
        if (!SnapshotRepository(appContext).isConfigured) {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** One refresh as soon as the network allows. Extra calls while one is queued are ignored. */
    fun refreshNow(context: Context) {
        val appContext = context.applicationContext
        if (!SnapshotRepository(appContext).isConfigured) return
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint())
            .setInputData(workDataOf(RefreshWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Queues a refresh for a tap or a view of stale data. Returns true when it can start right away,
     * so the caller may show "Refreshing". Offline, the job waits for the network and this returns false,
     * because claiming "Refreshing" would only hide how old the data is.
     */
    fun requestRefresh(context: Context): Boolean {
        refreshNow(context)
        if (!Connectivity.isOnline(context)) return false
        SnapshotRepository(context).setRefreshing(true)
        return true
    }

    /**
     * Asks a nearby phone running Wrist Usage to fetch and push a new snapshot. Returns true when the
     * message was delivered, which says nothing yet about whether the phone's fetch will work.
     */
    suspend fun askPhone(context: Context): Boolean = try {
        withTimeoutOrNull(PHONE_TIMEOUT_MS) {
            val phone = Wearable.getCapabilityClient(context)
                .getCapability(SyncContract.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .firstOrNull() ?: return@withTimeoutOrNull false
            Wearable.getMessageClient(context)
                .sendMessage(phone.id, SyncContract.PATH_REFRESH, ByteArray(0))
                .await()
            true
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No Play services, no paired phone, or the phone app is missing: fall back to a direct download.
        false
    }

    /** Uses the application context: a BroadcastReceiver's own context is not allowed to bind to the tile host. */
    fun requestSurfaceUpdates(context: Context) {
        val appContext = context.applicationContext
        TileService.getUpdater(appContext).requestUpdate(UsageTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(appContext, ComponentName(appContext, UsageComplicationService::class.java))
            .requestUpdateAll()
    }

    private fun networkConstraint() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
