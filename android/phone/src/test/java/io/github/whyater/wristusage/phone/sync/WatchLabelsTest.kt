package io.github.whyater.wristusage.phone.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WatchLabelsTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    @Test
    fun syncedShowsWatchNameAndAge() {
        val label = WatchLabels.of(WatchStatus.Found("Galaxy Watch6"), now.minusSeconds(150), now)
        assertEquals("Synced to Galaxy Watch6 2m ago", label.text)
        assertEquals(WatchTone.Good, label.tone)
    }

    @Test
    fun syncedJustNow() {
        val label = WatchLabels.of(WatchStatus.Found("Galaxy Watch6"), now.minusSeconds(10), now)
        assertEquals("Synced to Galaxy Watch6 just now", label.text)
    }

    @Test
    fun foundButNeverPushed() {
        val label = WatchLabels.of(WatchStatus.Found("Galaxy Watch6"), null, now)
        assertEquals("Galaxy Watch6 found, not synced yet", label.text)
        assertEquals(WatchTone.Neutral, label.tone)
    }

    @Test
    fun noWatch() {
        val label = WatchLabels.of(WatchStatus.NoWatch, now, now)
        assertEquals("No watch with Wrist Usage found", label.text)
        assertEquals(WatchTone.Neutral, label.tone)
    }

    @Test
    fun wearServicesUnavailable() {
        val label = WatchLabels.of(WatchStatus.Unavailable, null, now)
        assertEquals("Wear OS services unavailable", label.text)
        assertEquals(WatchTone.Warning, label.tone)
    }

    @Test
    fun beforeFirstCheck() {
        assertEquals("Looking for your watch", WatchLabels.of(null, null, now).text)
    }
}
