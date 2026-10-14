package io.github.whyater.wristusage.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whyater.wristusage.data.Provider
import io.github.whyater.wristusage.data.ProviderStatus
import io.github.whyater.wristusage.data.UsageLimit
import io.github.whyater.wristusage.domain.UsageFormat
import io.github.whyater.wristusage.phone.data.Cached
import io.github.whyater.wristusage.phone.domain.ResetTimeFormat
import io.github.whyater.wristusage.phone.sync.SyncState
import io.github.whyater.wristusage.phone.sync.WatchLabel
import io.github.whyater.wristusage.phone.sync.WatchTone
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    state: SyncState,
    watch: WatchLabel,
    now: Instant,
    onRefresh: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    Scaffold(
        containerColor = Hues.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = insets,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { Header(onOpenSetup) }

                val cached = state.cached
                if (cached == null) {
                    item(key = "empty") { EmptyState(state, onRefresh) }
                } else {
                    for (provider in cached.snapshot.providers) {
                        item(key = "provider-${provider.id}") { ProviderHeader(provider) }
                        if (provider.status != ProviderStatus.Ok) {
                            item(key = "status-${provider.id}") { ProviderStatusCard(provider) }
                        } else if (provider.limits.isEmpty()) {
                            item(key = "nolimits-${provider.id}") { NoLimitsCard() }
                        } else {
                            val stale = UsageFormat.isStale(cached.snapshot.generatedAt, now)
                            for (limit in provider.limits) {
                                item(key = "limit-${provider.id}-${limit.id}") {
                                    LimitCard(provider, limit, now, stale)
                                }
                            }
                        }
                    }
                    item(key = "updated") { UpdatedLine(cached, state, now) }
                }
                item(key = "watch") { WatchRow(watch) }
                item(key = "end") { Spacer(Modifier.height(12.dp)) }
            }
            // Cards scroll under the transparent status bar; fade them out so the clock stays readable.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Brush.verticalGradient(listOf(Hues.Background, Hues.Background.copy(alpha = 0f)))),
            )
        }
    }
}

@Composable
private fun Header(onOpenSetup: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Wrist Usage",
            style = MaterialTheme.typography.titleLarge,
            color = Hues.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSetup) {
            Text("Change URL", color = Hues.TextSecondary)
        }
    }
}

@Composable
private fun ProviderHeader(provider: Provider) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Hues.accent(provider.id)),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            provider.name,
            style = MaterialTheme.typography.titleMedium,
            color = Hues.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        provider.plan?.let { plan ->
            Spacer(Modifier.size(10.dp))
            Text(
                plan,
                style = MaterialTheme.typography.bodyMedium,
                color = Hues.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Hues.Surface)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
private fun LimitCard(provider: Provider, limit: UsageLimit, now: Instant, dataStale: Boolean) {
    val severity = UsageFormat.severity(limit.percent)
    val barColor = Hues.bar(severity, Hues.accent(provider.id))
    val resetsIn = UsageFormat.resetsIn(limit.resetsAt, now, dataStale)
    val exact = limit.resetsAt?.let {
        ResetTimeFormat.format(
            resetsAt = it,
            now = now,
            zone = ZoneId.systemDefault(),
            use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
        )
    }
    Card(Modifier.semantics(mergeDescendants = true) {}) {
        Text(limit.label, style = MaterialTheme.typography.labelLarge, color = Hues.TextSecondary)
        Text(
            UsageFormat.percentText(limit.percent),
            style = PhoneType.Percent,
            color = Hues.percentText(severity),
        )
        Spacer(Modifier.height(10.dp))
        UsageBar((limit.percent / 100.0).toFloat(), barColor)
        if (resetsIn.isNotEmpty() || exact != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(resetsIn, style = MaterialTheme.typography.bodyMedium, color = Hues.TextSecondary)
                if (exact != null) {
                    Text(exact, style = MaterialTheme.typography.bodyMedium, color = Hues.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Hues.Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

@Composable
private fun ProviderStatusCard(provider: Provider) {
    val expired = provider.status == ProviderStatus.AuthExpired
    val title = if (expired) "Sign-in expired" else "Couldn't read usage"
    val fallback = if (expired) "Sign in again on the Mac." else "The collector reported an error."
    Card {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (expired) Hues.Warning else Hues.Critical,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            provider.message ?: fallback,
            style = MaterialTheme.typography.bodyMedium,
            color = Hues.TextSecondary,
        )
    }
}

@Composable
private fun NoLimitsCard() {
    Card {
        Text("No limits reported", style = MaterialTheme.typography.bodyMedium, color = Hues.TextSecondary)
    }
}

@Composable
private fun UpdatedLine(cached: Cached, state: SyncState, now: Instant) {
    val generatedAt = cached.snapshot.generatedAt
    val stale = UsageFormat.isStale(generatedAt, now)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            UsageFormat.updatedAgo(generatedAt, now),
            style = MaterialTheme.typography.bodyMedium,
            color = if (stale) Hues.Warning else Hues.TextTertiary,
        )
        if (stale) {
            Text(
                "The Mac hasn't published new numbers for a while. Is the collector running?",
                style = MaterialTheme.typography.bodySmall,
                color = Hues.TextTertiary,
            )
        }
        state.lastError?.let { error ->
            Text(
                "Couldn't refresh: ${error.detail}. Showing the last good numbers.",
                style = MaterialTheme.typography.bodySmall,
                color = Hues.Critical,
            )
        }
    }
}

@Composable
private fun EmptyState(state: SyncState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val error = state.lastError
        when {
            state.refreshing -> CircularProgressIndicator(color = Hues.TextSecondary, strokeWidth = 2.dp)
            error != null -> {
                Text(error.detail, style = MaterialTheme.typography.bodyMedium, color = Hues.Critical)
                Button(onClick = onRetry) { Text("Try again") }
            }
            else -> Text("Pull down to load your usage", style = MaterialTheme.typography.bodyMedium, color = Hues.TextSecondary)
        }
    }
}

@Composable
private fun WatchRow(label: WatchLabel) {
    val dot = when (label.tone) {
        WatchTone.Good -> Hues.Good
        WatchTone.Warning -> Hues.Warning
        WatchTone.Neutral -> Hues.TextTertiary
    }
    Card(Modifier.semantics(mergeDescendants = true) {}) {
        Text("WATCH", style = PhoneType.Overline, color = Hues.TextTertiary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.size(10.dp))
            Text(label.text, style = MaterialTheme.typography.bodyMedium, color = Hues.TextPrimary)
        }
    }
}
