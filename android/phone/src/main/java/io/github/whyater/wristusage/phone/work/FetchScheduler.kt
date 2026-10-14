package io.github.whyater.wristusage.phone.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Owns the two WorkManager jobs: the 15 minute loop and the one-off fetch the watch can ask for. */
class FetchScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Safe to call on every launch: an existing job is kept as it is. */
    fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<FetchWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_NAME)
    }

    /** Fetch and push right now, even if the app is in the background. */
    fun fetchNow() {
        val request = OneTimeWorkRequestBuilder<FetchWorker>()
            .setConstraints(network)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val PERIOD_MINUTES = 15L
        const val PERIODIC_NAME = "wristusage_periodic_fetch"
        const val NOW_NAME = "wristusage_fetch_now"
    }
}
