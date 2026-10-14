package io.github.whyater.wristusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SnapshotRepositoryDecisionTest {
    @Test fun nonBlankRuntimeUrlWins() {
        assertEquals("https://phone.example/usage.json", SnapshotRepository.effectiveSnapshotUrl(" https://phone.example/usage.json ", "https://build.example/usage.json"))
    }

    @Test fun blankRuntimeUrlFallsBackToBuildUrl() {
        assertEquals("https://build.example/usage.json", SnapshotRepository.effectiveSnapshotUrl("  ", "https://build.example/usage.json"))
        assertEquals("", SnapshotRepository.effectiveSnapshotUrl("", ""))
    }

    @Test fun notSetUpRequiresNoUrlAndNoSnapshot() {
        val empty = UsageState(configured = false, snapshot = null, fetchedAt = null, error = null)
        assertTrue(SnapshotRepository.isNotSetUp(empty))
        assertFalse(SnapshotRepository.isNotSetUp(empty.copy(configured = true)))
    }

    private val made = Instant.parse("2026-10-13T04:00:00Z")
    private val current = Snapshot(made, emptyList())
    private val fetched = made.plusSeconds(60)

    @Test fun anythingReplacesAnEmptyCache() {
        assertTrue(SnapshotRepository.acceptIncomingSnapshot(null, null, current, fetched))
    }

    @Test fun olderSnapshotIsRejected() {
        val older = current.copy(generatedAt = made.minusSeconds(1))
        assertFalse(SnapshotRepository.acceptIncomingSnapshot(current, fetched, older, fetched.plusSeconds(600)))
    }

    @Test fun sameSnapshotOnlyReplacesWhenDownloadedLater() {
        assertTrue(SnapshotRepository.acceptIncomingSnapshot(current, fetched, current, fetched.plusSeconds(1)))
        assertFalse(SnapshotRepository.acceptIncomingSnapshot(current, fetched, current, fetched))
        // A late Data Layer item carrying an earlier download must not move the cache backwards.
        assertFalse(SnapshotRepository.acceptIncomingSnapshot(current, fetched, current, fetched.minusSeconds(30)))
    }

    @Test fun collectorClockRunningAheadDoesNotBlockLaterSnapshots() {
        // Stamped a day ahead, downloaded at 04:01. Its real age is the download time.
        val ahead = current.copy(generatedAt = made.plusSeconds(86_400))
        val next = current.copy(generatedAt = made.plusSeconds(300))
        assertTrue(SnapshotRepository.acceptIncomingSnapshot(ahead, fetched, next, made.plusSeconds(360)))
    }
}
