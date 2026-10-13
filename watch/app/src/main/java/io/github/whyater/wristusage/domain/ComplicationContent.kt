package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.data.Snapshot
import java.time.Instant

/** What the complication shows. [percent] is null when there is nothing to show. */
data class ComplicationContent(val percent: Double?, val title: String, val description: String) {
    companion object {
        const val STALE_TITLE = "Stale"

        /**
         * The highest used percent among providers with status ok, like the tile.
         * Stale data still shows its value, but the title and description say it is old.
         */
        fun from(snapshot: Snapshot?, now: Instant): ComplicationContent {
            val top = snapshot?.highestLimit()
                ?: return ComplicationContent(null, "Usage", "No usage data")
            val (provider, limit) = top
            val used = "${provider.name} ${limit.label} ${UsageFormat.percentText(limit.percent)} used"
            val stale = UsageFormat.isStale(snapshot.generatedAt, now)
            return ComplicationContent(
                percent = limit.percent,
                title = if (stale) STALE_TITLE else provider.name,
                description = if (stale) "$used, ${UsageFormat.updatedAgo(snapshot.generatedAt, now).lowercase()}" else used,
            )
        }
    }
}
