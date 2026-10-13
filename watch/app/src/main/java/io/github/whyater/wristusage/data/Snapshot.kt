package io.github.whyater.wristusage.data

import java.time.Duration
import java.time.Instant

/** One usage snapshot as published by the Mac collector (see docs/snapshot-format.md). */
data class Snapshot(
    val generatedAt: Instant,
    val providers: List<Provider>,
) {
    /** The limit with the highest used percent across providers that report status ok, or null if there are none. */
    fun highestLimit(): Pair<Provider, UsageLimit>? =
        providers
            .filter { it.status == ProviderStatus.Ok }
            .flatMap { p -> p.limits.map { p to it } }
            .maxByOrNull { it.second.percent }

    /**
     * A collector clock that runs ahead of the watch would make the data look fresh for as long as the
     * gap lasts. Past [MAX_CLOCK_SKEW] ahead of the download time, trust the download time instead.
     */
    fun withSaneClock(fetchedAt: Instant): Snapshot =
        if (generatedAt.isAfter(fetchedAt.plus(MAX_CLOCK_SKEW))) copy(generatedAt = fetchedAt) else this

    companion object {
        val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(2)
    }
}

enum class ProviderStatus { Ok, AuthExpired, Error }

data class Provider(
    val id: String,
    val name: String,
    val plan: String?,
    val status: ProviderStatus,
    val message: String?,
    val limits: List<UsageLimit>,
)

data class UsageLimit(
    val id: String,
    val label: String,
    /** Used share, 0 to 100 (may be fractional). */
    val percent: Double,
    val resetsAt: Instant?,
)
