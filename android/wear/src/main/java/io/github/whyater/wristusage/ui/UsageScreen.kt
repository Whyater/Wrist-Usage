package io.github.whyater.wristusage.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import io.github.whyater.wristusage.data.Provider
import io.github.whyater.wristusage.data.ProviderStatus
import io.github.whyater.wristusage.data.UsageLimit
import io.github.whyater.wristusage.data.UsageState
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.domain.Palette
import io.github.whyater.wristusage.domain.UsageFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun UsageScreen(
    state: UsageState,
    now: Instant,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val snapshot = state.snapshot

    // The list content changes completely between "not set up", "loading" and "data".
    // Start each of those at the top instead of keeping the old scroll anchor.
    val mode = when {
        SnapshotRepository.isNotSetUp(state) -> 0
        snapshot == null -> 1
        else -> 2
    }
    LaunchedEffect(mode) { listState.scrollToItem(0) }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        // A little extra room above the first item so it rests below the clock at full size.
        val layoutDirection = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = contentPadding.calculateLeftPadding(layoutDirection),
            end = contentPadding.calculateRightPadding(layoutDirection),
            top = contentPadding.calculateTopPadding() + 10.dp,
            bottom = contentPadding.calculateBottomPadding() + 6.dp,
        )
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                SnapshotRepository.isNotSetUp(state) -> item {
                    Notice(
                        title = "Not set up",
                        body = "Open Wrist Usage on your phone to set up",
                        modifier = Modifier.scrollTransformed(this, spec),
                    )
                }

                snapshot == null -> item {
                    Notice(
                        title = if (state.error != null) state.error.short else "Loading",
                        body = state.error?.detail ?: "Fetching your usage.",
                        modifier = Modifier.scrollTransformed(this, spec),
                        tone = if (state.error != null) Palette.WARNING else Palette.TEXT_PRIMARY,
                    )
                }

                else -> {
                    // Keys come from positions, not ids: the list can never hit a duplicate-key crash.
                    val dataStale = UsageFormat.isStale(snapshot.generatedAt, now)
                    snapshot.providers.forEachIndexed { pi, provider ->
                        item(key = "h-$pi") {
                            ProviderHeader(provider, Modifier.scrollTransformed(this, spec))
                        }
                        if (provider.status != ProviderStatus.Ok || provider.limits.isEmpty()) {
                            item(key = "m-$pi") {
                                ProviderMessage(provider, Modifier.scrollTransformed(this, spec))
                            }
                        } else {
                            provider.limits.forEachIndexed { li, limit ->
                                item(key = "l-$pi-$li") {
                                    LimitCard(limit, provider, now, dataStale, Modifier.scrollTransformed(this, spec))
                                }
                            }
                        }
                    }
                }
            }

            if (state.configured) {
                item(key = "status") {
                    val stale = snapshot != null && UsageFormat.isStale(snapshot.generatedAt, now)
                    val statusText = when {
                        snapshot == null -> null
                        state.error != null -> "${state.error.short}. ${UsageFormat.updatedAgo(snapshot.generatedAt, now)}"
                        else -> UsageFormat.updatedAgo(snapshot.generatedAt, now)
                    }
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(if (stale || state.error != null) Palette.WARNING else Palette.TEXT_TERTIARY),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp).scrollTransformed(this, spec),
                        )
                    }
                }
                item(key = "refresh") {
                    FilledTonalButton(
                        onClick = onRefresh,
                        enabled = !refreshing,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text(
                            text = if (refreshing) "Refreshing..." else "Refresh",
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Gives a custom item the same scale, fade and clip as the library cards while it scrolls past the
 * screen edge, so headers and cards shrink together instead of overlapping.
 */
private fun Modifier.scrollTransformed(scope: TransformingLazyColumnItemScope, spec: TransformationSpec): Modifier =
    transformedHeight(scope, spec).graphicsLayer {
        val progress = with(scope) { scrollProgress }
        with(spec) {
            applyContainerTransformation(progress)
            applyContentTransformation(progress)
        }
    }

@Composable
private fun ProviderHeader(provider: Provider, modifier: Modifier) {
    val accent = Color(Palette.accent(provider))
    Row(
        modifier = modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = provider.name.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        provider.plan?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Color(Palette.TEXT_TERTIARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderMessage(provider: Provider, modifier: Modifier) {
    val (fallback, color) = when (provider.status) {
        ProviderStatus.AuthExpired -> "Sign in again on the Mac" to Palette.WARNING
        ProviderStatus.Error -> "Unavailable right now" to Palette.CRITICAL
        ProviderStatus.Ok -> "No limits reported" to Palette.TEXT_TERTIARY
    }
    Text(
        text = provider.message ?: fallback,
        style = MaterialTheme.typography.bodySmall,
        color = Color(color),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

@Composable
private fun LimitCard(limit: UsageLimit, provider: Provider, now: Instant, dataStale: Boolean, modifier: Modifier) {
    val severity = UsageFormat.severity(limit.percent)
    val barColor = Color(Palette.bar(severity, Palette.accent(provider)))
    val resetLine = UsageFormat.resetsIn(limit.resetsAt, now, dataStale)
    val exactReset = limit.resetsAt?.let { exactResetText(it) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(Palette.SURFACE), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = limit.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color(Palette.TEXT_SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = UsageFormat.percentText(limit.percent),
                style = MaterialTheme.typography.numeralExtraSmall,
                color = Color(Palette.percentText(severity)),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(limit.percent, barColor)
        if (resetLine.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = resetLine,
                style = MaterialTheme.typography.bodySmall,
                color = Color(Palette.TEXT_SECONDARY),
                maxLines = 1,
            )
            exactReset?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = Color(Palette.TEXT_TERTIARY),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(percent: Double, color: Color) {
    val share = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(Color(Palette.TRACK), RoundedCornerShape(50)),
    ) {
        if (percent >= 0.5) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(share.coerceAtLeast(0.03f))
                    .background(color, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun Notice(title: String, body: String, modifier: Modifier, tone: Int = Palette.TEXT_PRIMARY) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color(tone),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Color(Palette.TEXT_SECONDARY),
            textAlign = TextAlign.Center,
        )
    }
}

/** "Tue 6:59 AM" in the watch's local time zone and 12/24 hour setting. */
@Composable
private fun exactResetText(resetsAt: Instant): String {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "EEE Hm" else "EEE jm"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(resetsAt)
}
