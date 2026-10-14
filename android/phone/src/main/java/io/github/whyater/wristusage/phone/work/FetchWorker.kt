package io.github.whyater.wristusage.phone.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.whyater.wristusage.phone.R
import io.github.whyater.wristusage.phone.graph
import io.github.whyater.wristusage.phone.sync.RefreshResult

/** Fetches the saved URL and pushes the result to the watch. Used for both the 15 minute loop and watch requests. */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.graph.sync.refresh()) {
        RefreshResult.NoUrl -> Result.success()
        is RefreshResult.Success -> Result.success()
        // The loop tries again in 15 minutes anyway, so only a couple of quick retries.
        is RefreshResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /** Only used on Android 11 and older, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.refresh_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.refresh_notification_title))
            .setSmallIcon(R.drawable.ic_stat_refresh)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CHANNEL_ID = "refresh"
        const val NOTIFICATION_ID = 1
    }
}
