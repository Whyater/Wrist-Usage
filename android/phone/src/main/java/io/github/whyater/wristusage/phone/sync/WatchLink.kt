package io.github.whyater.wristusage.phone.sync

/** Result of looking for a watch that has Wrist Usage installed. */
sealed interface WatchStatus {
    /** The Wearable API could not be used at all, for example on a phone without the Wear OS app. */
    data object Unavailable : WatchStatus

    data object NoWatch : WatchStatus

    data class Found(val name: String) : WatchStatus
}

/** Everything the app needs from the Wearable Data Layer. Faked in tests. */
interface WatchLink {
    /** Puts the raw snapshot JSON as an urgent DataItem. Throws when the Data Layer is not reachable. */
    suspend fun putSnapshot(json: String, fetchedAtMillis: Long)

    /** Tells the watch which URL to use. An empty string means "not set". */
    suspend fun putConfig(url: String)

    /** Never throws (other than cancellation): failures map to [WatchStatus.Unavailable]. */
    suspend fun watchStatus(): WatchStatus
}
