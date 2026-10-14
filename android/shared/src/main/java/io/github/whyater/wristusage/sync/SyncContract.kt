package io.github.whyater.wristusage.sync

object SyncContract {
    /** DataItem the phone writes after each successful fetch. */
    const val PATH_SNAPSHOT = "/wristusage/snapshot"
    const val KEY_JSON = "json"              // String: raw snapshot JSON exactly as downloaded
    const val KEY_FETCHED_AT = "fetched_at"  // Long: epoch millis when the phone downloaded it
    /** DataItem the phone writes when the user saves or clears the snapshot URL. */
    const val PATH_CONFIG = "/wristusage/config"
    const val KEY_SNAPSHOT_URL = "snapshot_url" // String, "" means not set
    /** Message the watch sends to ask the phone to fetch right now. No payload. */
    const val PATH_REFRESH = "/wristusage/refresh"
    /** Capabilities declared in res/values/wear.xml of each app. */
    const val CAPABILITY_WATCH = "wristusage_watch"
    const val CAPABILITY_PHONE = "wristusage_phone"
}
