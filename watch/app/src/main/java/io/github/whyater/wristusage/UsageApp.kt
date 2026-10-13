package io.github.whyater.wristusage

import android.app.Application
import io.github.whyater.wristusage.work.Refresher

class UsageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Does nothing when no snapshot URL was set at build time.
        Refresher.schedulePeriodic(this)
    }
}
