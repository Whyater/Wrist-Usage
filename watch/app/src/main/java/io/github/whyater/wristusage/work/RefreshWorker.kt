package io.github.whyater.wristusage.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.whyater.wristusage.data.SnapshotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads the snapshot, then refreshes the tile and the complication so they show it. */
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = SnapshotRepository(applicationContext)
        withContext(Dispatchers.IO) { repo.refresh() }
        // A failed fetch is recorded in the cache, and the surfaces show it. The next period retries.
        Refresher.requestSurfaceUpdates(applicationContext)
        return Result.success()
    }
}
