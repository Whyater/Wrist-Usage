package io.github.whyater.wristusage.phone.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The exact local reset time under a limit: "Today, 3:09 PM", "Tomorrow, 9:00 AM" or "Fri 17 Oct, 3:50 PM". */
object ResetTimeFormat {
    fun format(
        resetsAt: Instant,
        now: Instant,
        zone: ZoneId,
        use24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
            .format(resetsAt.atZone(zone))
        val resetDay = resetsAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (resetDay) {
            today -> "Today, $time"
            today.plusDays(1) -> "Tomorrow, $time"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(resetDay) + ", $time"
        }
    }
}
