package io.github.whyater.wristusage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UsageFormatTest {
    private val now = Instant.parse("2026-10-13T04:00:00Z")
    private fun inFuture(d: Duration) = now.plus(d)

    // ---- countdown -------------------------------------------------------------------------

    @Test fun minutesOnlyUnderAnHour() {
        assertEquals("42m", UsageFormat.countdown(inFuture(Duration.ofMinutes(42)), now))
        assertEquals("59m", UsageFormat.countdown(inFuture(Duration.ofMinutes(59).plusSeconds(59)), now))
        assertEquals("1m", UsageFormat.countdown(inFuture(Duration.ofSeconds(60)), now))
    }

    @Test fun hoursAndMinutesUnderADay() {
        assertEquals("2h 15m", UsageFormat.countdown(inFuture(Duration.ofMinutes(135)), now))
        assertEquals("1h", UsageFormat.countdown(inFuture(Duration.ofMinutes(60)), now))
        assertEquals("23h 59m", UsageFormat.countdown(inFuture(Duration.ofHours(24).minusMinutes(1)), now))
    }

    @Test fun daysAndHoursFromADayUp() {
        assertEquals("3d 4h", UsageFormat.countdown(inFuture(Duration.ofHours(76).plusMinutes(30)), now))
        assertEquals("1d", UsageFormat.countdown(inFuture(Duration.ofHours(24)), now))
        assertEquals("1d 1h", UsageFormat.countdown(inFuture(Duration.ofHours(25)), now))
    }

    @Test fun nowWhenTheResetHasPassed() {
        assertEquals("now", UsageFormat.countdown(now, now))
        assertEquals("now", UsageFormat.countdown(now.minusSeconds(5), now))
    }

    @Test fun underAMinuteIsNotRoundedToZero() {
        assertEquals("<1m", UsageFormat.countdown(inFuture(Duration.ofSeconds(30)), now))
    }

    @Test fun unknownResetIsEmpty() {
        assertEquals("", UsageFormat.countdown(null, now))
        assertEquals("", UsageFormat.resetsIn(null, now))
    }

    @Test fun resetsInSentence() {
        assertEquals("Resets in 42m", UsageFormat.resetsIn(inFuture(Duration.ofMinutes(42)), now))
        assertEquals("Resets in 3d 4h", UsageFormat.resetsIn(inFuture(Duration.ofHours(76)), now))
        assertEquals("Resets now", UsageFormat.resetsIn(now.minusSeconds(1), now))
    }

    @Test fun passedResetOnStaleDataReadsReset() {
        assertEquals("Reset", UsageFormat.countdown(now.minusSeconds(5), now, dataStale = true))
        assertEquals("Reset", UsageFormat.countdown(now, now, dataStale = true))
        assertEquals("Reset", UsageFormat.resetsIn(now.minusSeconds(5), now, dataStale = true))
    }

    @Test fun staleDataWithAFutureResetStillCountsDown() {
        assertEquals("42m", UsageFormat.countdown(inFuture(Duration.ofMinutes(42)), now, dataStale = true))
        assertEquals("Resets in 42m", UsageFormat.resetsIn(inFuture(Duration.ofMinutes(42)), now, dataStale = true))
    }

    @Test fun passedResetOnFreshDataStillReadsNow() {
        assertEquals("now", UsageFormat.countdown(now.minusSeconds(5), now, dataStale = false))
        assertEquals("Resets now", UsageFormat.resetsIn(now.minusSeconds(5), now, dataStale = false))
    }

    // ---- updated ago -----------------------------------------------------------------------

    @Test fun updatedAgoBuckets() {
        assertEquals("Updated just now", UsageFormat.updatedAgo(now.minusSeconds(40), now))
        assertEquals("Updated 5m ago", UsageFormat.updatedAgo(now.minus(Duration.ofMinutes(5)), now))
        assertEquals("Updated 59m ago", UsageFormat.updatedAgo(now.minus(Duration.ofMinutes(59)), now))
        assertEquals("Updated 2h ago", UsageFormat.updatedAgo(now.minus(Duration.ofMinutes(150)), now))
        assertEquals("Updated 3d ago", UsageFormat.updatedAgo(now.minus(Duration.ofHours(80)), now))
    }

    @Test fun futureTimestampsFromClockSkewReadAsJustNow() {
        assertEquals("Updated just now", UsageFormat.updatedAgo(now.plusSeconds(90), now))
    }

    // ---- severity --------------------------------------------------------------------------

    @Test fun severityThresholds() {
        assertEquals(Severity.Normal, UsageFormat.severity(0.0))
        assertEquals(Severity.Normal, UsageFormat.severity(74.9))
        assertEquals(Severity.Warning, UsageFormat.severity(75.0))
        assertEquals(Severity.Warning, UsageFormat.severity(89.9))
        assertEquals(Severity.Critical, UsageFormat.severity(90.0))
        assertEquals(Severity.Critical, UsageFormat.severity(100.0))
    }

    // ---- staleness -------------------------------------------------------------------------

    @Test fun staleAfterThirtyMinutes() {
        assertFalse(UsageFormat.isStale(now.minus(Duration.ofMinutes(29)), now))
        assertFalse(UsageFormat.isStale(now.minus(Duration.ofMinutes(30)), now))
        assertTrue(UsageFormat.isStale(now.minus(Duration.ofMinutes(30)).minusSeconds(1), now))
        assertTrue(UsageFormat.isStale(now.minus(Duration.ofHours(5)), now))
    }

    @Test fun futureGeneratedAtIsNeverStale() {
        assertFalse(UsageFormat.isStale(now.plus(Duration.ofMinutes(10)), now))
        assertFalse(UsageFormat.isStale(now.plus(Duration.ofDays(30)), now))
    }

    // ---- percent text ----------------------------------------------------------------------

    @Test fun percentTextRoundsToWholeNumbers() {
        assertEquals("14%", UsageFormat.percentText(14.0))
        assertEquals("15%", UsageFormat.percentText(14.5))
        assertEquals("0%", UsageFormat.percentText(0.2))
        assertEquals("100%", UsageFormat.percentText(100.0))
    }
}
