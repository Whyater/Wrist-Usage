package io.github.whyater.wristusage.phone

import android.app.Application
import android.content.Context
import io.github.whyater.wristusage.phone.data.PrefsPhoneStore
import io.github.whyater.wristusage.phone.sync.PlayWatchLink
import io.github.whyater.wristusage.phone.sync.UsageSync
import io.github.whyater.wristusage.phone.work.FetchScheduler

/** Process-wide objects. Screens, workers and the watch listener all share one [UsageSync]. */
class AppGraph(context: Context) {
    val sync: UsageSync = UsageSync(store = PrefsPhoneStore(context), link = PlayWatchLink(context))
    val scheduler: FetchScheduler = FetchScheduler(context)
}

class PhoneApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph
    get() = (applicationContext as PhoneApp).graph
