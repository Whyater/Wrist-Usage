package io.github.whyater.wristusage.phone.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ResetTimeFormatTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    // 2026-10-13 10:00 in Los Angeles (UTC-7).
    private val now = Instant.parse("2026-10-13T17:00:00Z")

    private fun format(at: String, use24: Boolean = false) =
        ResetTimeFormat.format(Instant.parse(at), now, zone, use24, Locale.US)

    @Test
    fun sameLocalDayReadsToday() {
        assertEquals("Today, 3:09 PM", format("2026-10-13T22:09:00Z"))
    }

    @Test
    fun nextLocalDayReadsTomorrow() {
        assertEquals("Tomorrow, 9:00 AM", format("2026-10-14T16:00:00Z"))
    }

    @Test
    fun laterDatesShowWeekdayAndDate() {
        assertEquals("Sat 17 Oct, 12:50 PM", format("2026-10-17T19:50:00Z"))
    }

    @Test
    fun localDayCanDifferFromUtcDay() {
        // 02:00 UTC on the 14th is 7 PM on the 13th in Los Angeles, so still "Today".
        assertEquals("Today, 7:00 PM", format("2026-10-14T02:00:00Z"))
    }

    @Test
    fun twentyFourHourClock() {
        assertEquals("Today, 15:09", format("2026-10-13T22:09:00Z", use24 = true))
    }
}
