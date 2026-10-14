package io.github.whyater.wristusage.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RefreshWorkerTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test fun skipsWhenCacheYoungerThanTwentyMinutes() {
        assertFalse(RefreshWorker.shouldFetch(now.minusSeconds(19 * 60), now))
    }

    @Test fun fetchesWhenCacheIsTwentyMinutesOldOrMissing() {
        assertTrue(RefreshWorker.shouldFetch(now.minusSeconds(20 * 60), now))
        assertTrue(RefreshWorker.shouldFetch(null, now))
    }

    @Test fun fetchesWhenCacheTimeIsInTheFuture() {
        assertTrue(RefreshWorker.shouldFetch(now.plusSeconds(60), now))
    }
}
