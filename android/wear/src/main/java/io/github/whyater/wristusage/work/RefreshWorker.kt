package io.github.whyater.wristusage.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.sync.WearSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Gets a new snapshot, then refreshes the tile and the complication so they show it.
 * The phone is asked first because a Bluetooth message costs the watch far less battery than its own
 * Wi-Fi or LTE download. If the phone's data does not arrive in time, the watch downloads it itself.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // The periodic job and a tap can both be running. Taking turns keeps one owner of the
        // "Refreshing" flag, and the second one usually finds fresh data and stops early.
        running.withLock {
            val repo = SnapshotRepository(applicationContext)
            // The phone may have pushed while this job was waiting to run.
            WearSync.catchUp(applicationContext)
            val before = repo.load().fetchedAt
            val force = inputData.getBoolean(KEY_FORCE, false)
            if (force || shouldFetch(before, Instant.now())) {
                repo.setRefreshing(true)
                try {
                    if (!(Refresher.askPhone(applicationContext) && awaitNewData(repo, before))) {
                        withContext(Dispatchers.IO) { repo.refresh() }
                    }
                } finally {
                    repo.setRefreshing(false)
                }
            }
        }
        Refresher.requestSurfaceUpdates(applicationContext)
        return Result.success()
    }

    /** Waits for the phone's push to land in the cache. The listener service writes it, not this job. */
    private suspend fun awaitNewData(repo: SnapshotRepository, before: Instant?): Boolean {
        val deadline = System.currentTimeMillis() + PHONE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            if (repo.load().fetchedAt != before) return true
        }
        return false
    }

    companion object {
        private val running = Mutex()
        const val KEY_FORCE = "force"
        const val FRESHNESS_WINDOW_MS = 20 * 60 * 1000L
        private const val PHONE_WAIT_MS = 20_000L
        private const val POLL_MS = 1_000L

        /**
         * The periodic job skips data under twenty minutes old. While the phone is nearby it pushes every
         * fifteen minutes, so the watch rarely needs to fetch anything itself.
         */
        fun shouldFetch(fetchedAt: Instant?, now: Instant): Boolean {
            if (fetchedAt == null) return true
            val age = Duration.between(fetchedAt, now)
            // A time in the future means the clock moved back, so the age is unknown.
            return age.isNegative || age.toMillis() >= FRESHNESS_WINDOW_MS
        }
    }
}
