package io.github.whyater.wristusage.sync

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.work.Refresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Reads what the phone app writes to the Data Layer: the snapshot URL and the latest snapshot. */
object WearSync {
    private const val CATCH_UP_TIMEOUT_MS = 5_000L

    /**
     * Reads items the phone wrote while this app was not listening, for example before it was installed.
     * Any failure just means no phone data, and the direct download path still works.
     */
    suspend fun catchUp(context: Context): Boolean {
        val appContext = context.applicationContext
        val repo = SnapshotRepository(appContext)
        var changed = false
        for (path in listOf(SyncContract.PATH_CONFIG, SyncContract.PATH_SNAPSHOT)) {
            val items = try {
                withTimeoutOrNull(CATCH_UP_TIMEOUT_MS) {
                    Wearable.getDataClient(appContext).getDataItems("wear://*$path".toUri()).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                for (item in items) {
                    changed = apply(repo, path, DataMapItem.fromDataItem(item).dataMap) || changed
                }
            } finally {
                items.release()
            }
        }
        if (changed) onChanged(appContext)
        return changed
    }

    /** Handles one Data Layer callback. */
    fun ingestEvents(context: Context, events: DataEventBuffer) {
        val repo = SnapshotRepository(context.applicationContext)
        var changed = false
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            changed = apply(repo, path, DataMapItem.fromDataItem(event.dataItem).dataMap) || changed
        }
        if (changed) onChanged(context.applicationContext)
    }

    /** Stores one item. Returns true when the cache or the URL changed. */
    private fun apply(repo: SnapshotRepository, path: String, map: DataMap): Boolean = when (path) {
        SyncContract.PATH_CONFIG -> repo.storeSnapshotUrl(map.getString(SyncContract.KEY_SNAPSHOT_URL) ?: "")
        SyncContract.PATH_SNAPSHOT -> {
            val json = map.getString(SyncContract.KEY_JSON)
            json != null && repo.ingest(json, map.getLong(SyncContract.KEY_FETCHED_AT))
        }
        else -> false
    }

    private fun onChanged(context: Context) {
        Refresher.schedulePeriodic(context)
        Refresher.requestSurfaceUpdates(context)
    }
}

/** Woken by Google Play services when the phone writes a new item, even if the app is not running. */
class WearableSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is released by the base class after this returns, so read it here, synchronously.
        WearSync.ingestEvents(this, events)
    }
}
