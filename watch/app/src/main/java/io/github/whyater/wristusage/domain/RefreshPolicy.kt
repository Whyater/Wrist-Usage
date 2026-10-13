package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.data.UsageState
import java.time.Duration
import java.time.Instant

/** Decides when viewing the tile or the app should start a download. Pure, so it is easy to test. */
object RefreshPolicy {
    /** Data newer than this is never re-downloaded just because someone looked at it. */
    val MIN_DATA_AGE: Duration = Duration.ofSeconds(60)

    /** Gap between attempts after a successful one. */
    val RETRY_AFTER_SUCCESS: Duration = Duration.ofSeconds(60)

    /** Gap between attempts after a failed one. Stops a dead server or bad JSON from causing a fetch loop. */
    val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(5)

    /**
     * True when a view should queue a refresh. [forced] is an explicit tap on refresh:
     * it skips the age and backoff rules but never starts a second fetch while one is running.
     */
    fun shouldRefresh(state: UsageState, now: Instant, forced: Boolean = false): Boolean {
        if (!state.configured) return false
        if (state.refreshing) return false
        if (forced) return true

        if (!isOlderThan(state.fetchedAt, MIN_DATA_AGE, now)) return false
        val wait = if (state.error != null) RETRY_AFTER_FAILURE else RETRY_AFTER_SUCCESS
        return isOlderThan(state.attemptedAt, wait, now)
    }

    /** Unknown times count as old. A time in the future (clock moved back) counts as old too. */
    private fun isOlderThan(time: Instant?, limit: Duration, now: Instant): Boolean {
        if (time == null) return true
        val age = Duration.between(time, now)
        return age.isNegative || age > limit
    }
}
