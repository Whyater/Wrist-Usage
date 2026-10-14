package io.github.whyater.wristusage.phone.work

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.whyater.wristusage.phone.graph
import io.github.whyater.wristusage.sync.SyncContract

/** The watch sends [SyncContract.PATH_REFRESH] when its own data is stale. We answer by fetching and pushing. */
class WatchMessageService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == SyncContract.PATH_REFRESH) {
            applicationContext.graph.scheduler.fetchNow()
        }
    }
}
