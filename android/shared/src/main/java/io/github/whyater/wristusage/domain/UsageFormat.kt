package io.github.whyater.wristusage.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

enum class Severity { Normal, Warning, Critical }

/** Pure formatting and classification helpers shared by the tile, the app and the complication. */
object UsageFormat {
    const val WARNING_PERCENT = 75.0
    const val CRITICAL_PERCENT = 90.0

    /** A snapshot older than this is shown as stale. */
    val STALE_AFTER: Duration = Duration.ofMinutes(30)

    fun severity(percent: Double): Severity = when {
        percent >= CRITICAL_PERCENT -> Severity.Critical
        percent >= WARNING_PERCENT -> Severity.Warning
        else -> Severity.Normal
    }

    /** "14%". Fractions are rounded to the nearest whole percent. */
    fun percentText(percent: Double): String = "${percent.roundToInt().coerceIn(0, 100)}%"

    /**
     * Short countdown to [resetsAt]: "42m", "2h 15m", "3d 4h" or "now".
     * Under a minute left reads "<1m". Null (unknown reset) returns an empty string.
     * A reset that already passed reads "now" on fresh data, but "Reset" when the data is [dataStale]:
     * the window did reset, and the number shown is just old.
     */
    fun countdown(resetsAt: Instant?, now: Instant, dataStale: Boolean = false): String {
        if (resetsAt == null) return ""
        val seconds = Duration.between(now, resetsAt).seconds
        if (seconds <= 0) return if (dataStale) "Reset" else "now"
        if (seconds < 60) return "<1m"
        val totalMinutes = seconds / 60
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** Sentence form of [countdown]: "Resets in 42m", "Resets now", or "Reset". Empty if the reset time is unknown. */
    fun resetsIn(resetsAt: Instant?, now: Instant, dataStale: Boolean = false): String {
        if (resetsAt == null) return ""
        return when (val text = countdown(resetsAt, now, dataStale)) {
            "now" -> "Resets now"
            "Reset" -> "Reset"
            else -> "Resets in $text"
        }
    }

    /** Age only: "just now", "5m ago", "2h ago", "3d ago". Clock skew (future times) reads "just now". */
    fun ageText(since: Instant, now: Instant): String {
        val minutes = Duration.between(since, now).toMinutes()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }

    /** "Updated 5m ago" / "Updated just now". */
    fun updatedAgo(since: Instant, now: Instant): String = "Updated ${ageText(since, now)}"

    fun isStale(generatedAt: Instant, now: Instant): Boolean =
        Duration.between(generatedAt, now) > STALE_AFTER
}
