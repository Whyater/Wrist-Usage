package io.github.whyater.wristusage.phone.sync

import io.github.whyater.wristusage.domain.UsageFormat
import java.time.Instant

enum class WatchTone { Good, Neutral, Warning }

data class WatchLabel(val text: String, val tone: WatchTone)

/** Text for the small "Watch" row on the usage screen. [status] is null while the first check runs. */
object WatchLabels {
    fun of(status: WatchStatus?, lastPushAt: Instant?, now: Instant): WatchLabel = when (status) {
        null -> WatchLabel("Looking for your watch", WatchTone.Neutral)
        WatchStatus.Unavailable -> WatchLabel("Wear OS services unavailable", WatchTone.Warning)
        WatchStatus.NoWatch -> WatchLabel("No watch with Wrist Usage found", WatchTone.Neutral)
        is WatchStatus.Found ->
            if (lastPushAt == null) {
                WatchLabel("${status.name} found, not synced yet", WatchTone.Neutral)
            } else {
                WatchLabel("Synced to ${status.name} ${UsageFormat.ageText(lastPushAt, now)}", WatchTone.Good)
            }
    }
}
