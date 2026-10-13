package io.github.whyater.wristusage.work

import android.content.Context
import android.net.ConnectivityManager

object Connectivity {
    /** True when the watch has an active network. Cheap enough to call on every tile request. */
    fun isOnline(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
}
