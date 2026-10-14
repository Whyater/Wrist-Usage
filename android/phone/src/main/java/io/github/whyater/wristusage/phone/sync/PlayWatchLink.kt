package io.github.whyater.wristusage.phone.sync

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.whyater.wristusage.sync.SyncContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** [WatchLink] backed by Google Play services. */
class PlayWatchLink(context: Context) : WatchLink {
    private val appContext = context.applicationContext

    override suspend fun putSnapshot(json: String, fetchedAtMillis: Long) {
        val request = PutDataMapRequest.create(SyncContract.PATH_SNAPSHOT).apply {
            dataMap.putString(SyncContract.KEY_JSON, json)
            dataMap.putLong(SyncContract.KEY_FETCHED_AT, fetchedAtMillis)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request).await()
    }

    override suspend fun putConfig(url: String) {
        val request = PutDataMapRequest.create(SyncContract.PATH_CONFIG).apply {
            dataMap.putString(SyncContract.KEY_SNAPSHOT_URL, url)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request).await()
    }

    override suspend fun watchStatus(): WatchStatus = try {
        val info = Wearable.getCapabilityClient(appContext)
            .getCapability(SyncContract.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL)
            .await()
        val node = info.nodes.sortedByDescending { it.isNearby }.firstOrNull()
        if (node == null) WatchStatus.NoWatch else WatchStatus.Found(node.displayName)
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        WatchStatus.Unavailable
    } catch (_: Exception) {
        // Anything else from the Wearable client is treated the same: the status row must never crash the app.
        WatchStatus.Unavailable
    }
}
