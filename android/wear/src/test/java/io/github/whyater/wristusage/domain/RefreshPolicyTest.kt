package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.data.FetchError
import io.github.whyater.wristusage.data.UsageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RefreshPolicyTest {
    private val now = Instant.parse("2026-10-13T04:30:00Z")
    private fun ago(seconds: Long) = now.minusSeconds(seconds)

    private val error = FetchError("HTTP 500", "Server answered HTTP 500")

    /** A configured state; [fetched] and [attempted] are seconds ago. */
    private fun state(
        fetched: Long? = null,
        attempted: Long? = null,
        failed: Boolean = false,
        refreshing: Boolean = false,
        configured: Boolean = true,
    ) = UsageState(
        configured = configured,
        snapshot = null,
        fetchedAt = fetched?.let(::ago),
        error = if (failed) error else null,
        refreshing = refreshing,
        attemptedAt = attempted?.let(::ago),
    )

    private fun shouldRefresh(s: UsageState, forced: Boolean = false) = RefreshPolicy.shouldRefresh(s, now, forced)

    // ---- never ----------------------------------------------------------------------------------

    @Test fun neverWithoutAUrl() {
        assertFalse(shouldRefresh(state(configured = false)))
        assertFalse(shouldRefresh(state(configured = false), forced = true))
    }

    @Test fun neverWhileAFetchIsRunning() {
        assertFalse(shouldRefresh(state(fetched = 900, attempted = 900, refreshing = true)))
        assertFalse(shouldRefresh(state(fetched = 900, attempted = 900, refreshing = true), forced = true))
    }

    // ---- normal view ----------------------------------------------------------------------------

    @Test fun refreshesWhenThereIsNoDataYet() {
        assertTrue(shouldRefresh(state()))
    }

    @Test fun freshDataIsLeftAlone() {
        assertFalse(shouldRefresh(state(fetched = 30, attempted = 30)))
        assertFalse(shouldRefresh(state(fetched = 60, attempted = 60))) // exactly the limit is not "older than"
    }

    @Test fun oldDataAfterASuccessRefreshesOnceTheMinuteIsUp() {
        assertTrue(shouldRefresh(state(fetched = 61, attempted = 61)))
        assertTrue(shouldRefresh(state(fetched = 600, attempted = 600)))
    }

    @Test fun anAttemptInTheLastMinuteBlocksAnotherEvenIfDataIsOld() {
        // e.g. a fetch that just finished and stored the same old success time
        assertFalse(shouldRefresh(state(fetched = 600, attempted = 20)))
    }

    // ---- backoff after failure -------------------------------------------------------------------

    @Test fun failureBacksOffForFiveMinutes() {
        assertFalse(shouldRefresh(state(fetched = 900, attempted = 10, failed = true)))
        assertFalse(shouldRefresh(state(fetched = 900, attempted = 120, failed = true)))
        assertFalse(shouldRefresh(state(fetched = 900, attempted = 300, failed = true)))
        assertTrue(shouldRefresh(state(fetched = 900, attempted = 301, failed = true)))
    }

    @Test fun failureWithNoGoodDataAlsoBacksOff() {
        assertFalse(shouldRefresh(state(attempted = 30, failed = true)))
        assertTrue(shouldRefresh(state(attempted = 400, failed = true)))
    }

    @Test fun failureWithNoRecordedAttemptStillRefreshes() {
        // Cache written by an older build that did not store attempt times.
        assertTrue(shouldRefresh(state(fetched = 900, failed = true)))
    }

    @Test fun theLoopCaseIsStopped() {
        // Server keeps answering 500: the worker finished a failed attempt 5 s ago and asked for a tile redraw.
        assertFalse(shouldRefresh(state(fetched = 3600, attempted = 5, failed = true)))
    }

    // ---- explicit tap ----------------------------------------------------------------------------

    @Test fun tapIgnoresAgeAndBackoff() {
        assertTrue(shouldRefresh(state(fetched = 5, attempted = 5), forced = true))
        assertTrue(shouldRefresh(state(fetched = 900, attempted = 5, failed = true), forced = true))
        assertTrue(shouldRefresh(state(), forced = true))
    }

    // ---- odd clocks ------------------------------------------------------------------------------

    @Test fun timesInTheFutureCountAsOld() {
        val s = state().copy(fetchedAt = now.plus(Duration.ofHours(1)), attemptedAt = now.plus(Duration.ofHours(1)))
        assertTrue(shouldRefresh(s))
    }
}
