package io.github.whyater.wristusage

import android.app.Application
import io.github.whyater.wristusage.sync.WearSync
import io.github.whyater.wristusage.work.Refresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UsageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Refresher.schedulePeriodic(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            WearSync.catchUp(this@UsageApp)
            Refresher.schedulePeriodic(this@UsageApp)
        }
    }
}
