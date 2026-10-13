package io.github.whyater.wristusage.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.em
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontSetting
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Spannable
import androidx.wear.protolayout.LayoutElementBuilders.SpanText
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import io.github.whyater.wristusage.data.Provider
import io.github.whyater.wristusage.data.ProviderStatus
import io.github.whyater.wristusage.data.Snapshot
import io.github.whyater.wristusage.data.UsageLimit
import io.github.whyater.wristusage.data.UsageState
import io.github.whyater.wristusage.domain.Palette
import io.github.whyater.wristusage.domain.PlacedBlock
import io.github.whyater.wristusage.domain.TileBlock
import io.github.whyater.wristusage.domain.TileMetrics
import io.github.whyater.wristusage.domain.TilePlanner
import io.github.whyater.wristusage.domain.UsageFormat
import java.time.Instant
import kotlin.math.min

/** Resource id of the refresh glyph, registered by [UsageTileService.onTileResourcesRequest]. */
const val REFRESH_ICON_ID = "refresh"

/** Resource id of the small "resets in" glyph shown before each countdown. */
const val RESET_ICON_ID = "reset"

/** Where the clickables on the tile go. */
data class TileTargets(val packageName: String, val activityClassName: String)

/**
 * Builds the tile UI. Everything here is a plain function of (state, now, screen size),
 * so it is easy to tweak and to unit test. Sizes live in [TileMetrics].
 */
object UsageTileLayout {
    private const val ID_OPEN = "open"
    private const val ID_REFRESH_PREFIX = "refresh:"

    /**
     * Each render gets its own refresh id. The host reports the last clicked id on every later request,
     * so a fixed id could not tell a new tap from an old one.
     */
    private fun refreshId(now: Instant) = "$ID_REFRESH_PREFIX${now.toEpochMilli()}"

    /** True when [clickableId] (from the tile request state) came from tapping a refresh target. */
    fun isRefreshTap(clickableId: String?): Boolean = clickableId?.startsWith(ID_REFRESH_PREFIX) == true

    fun render(
        state: UsageState,
        now: Instant,
        screenDp: Float,
        targets: TileTargets,
    ): LayoutElement {
        val snapshot = state.snapshot
        val refreshId = refreshId(now)
        val body: LayoutElement
        var rootAction: ActionBuilders.Action = launchAction(targets)
        var rootId = ID_OPEN

        when {
            !state.configured -> body = messageScreen(
                title = "Not set up",
                lines = listOf("Run the Mac setup,", "then rebuild this app."),
                screenDp = screenDp,
            )
            snapshot == null && state.error == null -> body = messageScreen(
                title = "Loading",
                lines = listOf(if (state.refreshing) "Fetching usage" else "Waiting for data"),
                screenDp = screenDp,
            )
            snapshot == null -> {
                body = messageScreen(
                    title = state.error?.short ?: "Offline",
                    lines = listOf("No usage data yet.", "Tap to retry."),
                    screenDp = screenDp,
                    titleColor = Palette.WARNING,
                )
                rootAction = loadAction()
                rootId = refreshId
            }
            else -> body = dataScreen(snapshot, state, now, screenDp, refreshId)
        }

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(Palette.BACKGROUND)).build())
                    .setClickable(Clickable.Builder().setId(rootId).setOnClick(rootAction).build())
                    .build(),
            )
            .addContent(body)
            .build()
    }

    // ---- Screens -----------------------------------------------------------------------------

    private fun dataScreen(
        snapshot: Snapshot,
        state: UsageState,
        now: Instant,
        screenDp: Float,
        refreshId: String,
    ): LayoutElement {
        val plan = TilePlanner.plan(snapshot, screenDp)
        val placed = TilePlanner.place(TilePlanner.blocks(plan), screenDp)

        val column = Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        for (p in placed) {
            if (p.block.gapBefore > 0f) column.addContent(vSpace(p.block.gapBefore))
            column.addContent(blockElement(p, snapshot, state, now, refreshId))
        }
        return column.build()
    }

    private fun blockElement(
        p: PlacedBlock,
        snapshot: Snapshot,
        state: UsageState,
        now: Instant,
        refreshId: String,
    ): LayoutElement =
        when (val block = p.block) {
            is TileBlock.Header -> header(block.provider, p.widthDp)
            is TileBlock.Row -> limitRow(
                block.limit, block.provider, p.widthDp, now,
                dataStale = UsageFormat.isStale(snapshot.generatedAt, now),
            )
            is TileBlock.Message -> providerMessage(block.provider, p.widthDp)
            is TileBlock.More -> centeredLine("+${block.count} more", p.widthDp, TileMetrics.MORE_HEIGHT)
            is TileBlock.Footer -> footer(footerLabel(snapshot, state, now), p.widthDp, refreshId)
        }

    private fun messageScreen(
        title: String,
        lines: List<String>,
        screenDp: Float,
        titleColor: Int = Palette.TEXT_PRIMARY,
    ): LayoutElement {
        val width = screenDp * 0.66f
        val column = Column.Builder()
            .setWidth(dp(width))
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                text(title, 15f, titleColor, bold = true)
                    .let { centered(it, width) },
            )
            .addContent(vSpace(4f))
        lines.forEach { column.addContent(centered(text(it, 11f, Palette.TEXT_SECONDARY, lineHeight = 14f), width)) }
        return column.build()
    }

    // ---- Blocks ------------------------------------------------------------------------------

    private fun header(provider: Provider, width: Float): LayoutElement {
        val accent = Palette.accent(provider)
        val spannable = Spannable.Builder()
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setLineHeight(sp(12f))
            .addSpan(
                SpanText.Builder()
                    .setText(provider.name.uppercase())
                    .setFontStyle(font(10f, accent, weight = 700, letterSpacingEm = 0.08f))
                    .build(),
            )
        provider.plan?.let {
            spannable.addSpan(
                SpanText.Builder()
                    .setText("  $it")
                    .setFontStyle(font(10f, Palette.TEXT_TERTIARY, weight = 500))
                    .build(),
            )
        }
        return fixedBox(width, TileMetrics.HEADER_HEIGHT, spannable.build())
    }

    private fun limitRow(
        limit: UsageLimit,
        provider: Provider,
        width: Float,
        now: Instant,
        dataStale: Boolean,
    ): LayoutElement {
        val severity = UsageFormat.severity(limit.percent)
        val barColor = Palette.bar(severity, Palette.accent(provider))
        val countdown = UsageFormat.countdown(limit.resetsAt, now, dataStale)

        // The label gives way first (it ellipsizes) so the countdown and percent are never cut off.
        val label = Text.Builder()
            .setText(limit.label)
            .setFontStyle(font(11f, Palette.TEXT_SECONDARY, weight = 500))
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setLineHeight(sp(15f))
            .build()

        val textLine = Row.Builder()
            .setWidth(expand())
            .setHeight(dp(15f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(label)
                    .build(),
            )
        if (countdown.isNotEmpty()) {
            // The small "time until reset" glyph says what the number counts down to.
            textLine
                .addContent(hSpace(4f))
                .addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(RESET_ICON_ID)
                        .setWidth(dp(10f))
                        .setHeight(dp(10f))
                        .build(),
                )
                .addContent(hSpace(2f))
                .addContent(
                    text(countdown, 11f, Palette.TEXT_TERTIARY, lineHeight = 15f, tabular = true, maxLines = 1),
                )
        }
        textLine
            .addContent(hSpace(8f))
            .addContent(
                text(
                    UsageFormat.percentText(limit.percent), 14f, Palette.percentText(severity),
                    bold = true, lineHeight = 15f, tabular = true, maxLines = 1,
                ),
            )

        return Column.Builder()
            .setWidth(dp(width))
            .setHeight(dp(TileMetrics.ROW_HEIGHT))
            .addContent(textLine.build())
            .addContent(vSpace(3f))
            .addContent(bar(limit.percent, barColor))
            .build()
    }

    private fun bar(percent: Double, color: Int): LayoutElement {
        val track = Row.Builder()
            .setWidth(expand())
            .setHeight(dp(BAR_HEIGHT))
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(Palette.TRACK))
                            .setCorner(Corner.Builder().setRadius(dp(BAR_HEIGHT / 2f)).build())
                            .build(),
                    )
                    .build(),
            )
        if (percent >= 0.5) {
            // Tiny values still get a visible sliver.
            val filled = percent.toFloat().coerceIn(3f, 100f)
            track.addContent(barSegment(filled, color))
            if (filled < 100f) track.addContent(barSegment(100f - filled, null))
        }
        return track.build()
    }

    private fun barSegment(share: Float, color: Int?): LayoutElement {
        val box = Box.Builder().setWidth(weight(share)).setHeight(expand())
        if (color != null) {
            box.setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(color))
                            .setCorner(Corner.Builder().setRadius(dp(BAR_HEIGHT / 2f)).build())
                            .build(),
                    )
                    .build(),
            )
        }
        return box.build()
    }

    private fun providerMessage(provider: Provider, width: Float): LayoutElement {
        val (fallback, color) = when (provider.status) {
            ProviderStatus.AuthExpired -> "Sign in again on the Mac" to Palette.WARNING
            ProviderStatus.Error -> "Unavailable right now" to Palette.CRITICAL
            ProviderStatus.Ok -> "No limits reported" to Palette.TEXT_TERTIARY
        }
        val line = Text.Builder()
            .setText(provider.message ?: fallback)
            .setFontStyle(font(11f, color, weight = 500))
            .setMaxLines(2)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setLineHeight(sp(13f))
            .build()
        return fixedBox(width, TileMetrics.MESSAGE_HEIGHT, line)
    }

    /** Footer text and color. Refreshing on stale or failed data keeps the age visible, in the warning color. */
    internal fun footerLabel(snapshot: Snapshot, state: UsageState, now: Instant): FooterLabel {
        val age = UsageFormat.ageText(snapshot.generatedAt, now)
        val needsAttention = state.error != null || UsageFormat.isStale(snapshot.generatedAt, now)
        return when {
            state.refreshing && needsAttention -> FooterLabel("Refreshing · $age", Palette.WARNING)
            state.refreshing -> FooterLabel("Refreshing...", Palette.TEXT_SECONDARY)
            state.error != null -> FooterLabel("${state.error.short} · $age", Palette.WARNING)
            needsAttention -> FooterLabel("Updated $age", Palette.WARNING)
            else -> FooterLabel("Updated $age", Palette.TEXT_TERTIARY)
        }
    }

    internal data class FooterLabel(val text: String, val color: Int)

    private fun footer(footerLabel: FooterLabel, width: Float, refreshId: String): LayoutElement {
        val (label, color) = footerLabel

        val content = Row.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(REFRESH_ICON_ID)
                    .setWidth(dp(10f))
                    .setHeight(dp(10f))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build(),
                    )
                    .build(),
            )
            .addContent(hSpace(4f))
            .addContent(
                Text.Builder()
                    .setText(label)
                    .setFontStyle(font(10f, color, weight = 500, tabular = true))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                    .setLineHeight(sp(12f))
                    .build(),
            )
            .build()

        // Pill sized to its text, never wider than the round screen allows.
        val pillWidth = min(width, FOOTER_PILL_WIDTH)
        return Box.Builder()
            .setWidth(dp(pillWidth))
            .setHeight(dp(TileMetrics.FOOTER_HEIGHT))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(Palette.SURFACE))
                            .setCorner(Corner.Builder().setRadius(dp(TileMetrics.FOOTER_HEIGHT / 2f)).build())
                            .build(),
                    )
                    .setPadding(Padding.Builder().setStart(dp(6f)).setEnd(dp(6f)).build())
                    .setClickable(Clickable.Builder().setId(refreshId).setOnClick(loadAction()).build())
                    .build(),
            )
            .addContent(content)
            .build()
    }

    private fun centeredLine(value: String, width: Float, height: Float): LayoutElement =
        fixedBox(width, height, text(value, 10f, Palette.TEXT_TERTIARY, lineHeight = 12f))

    // ---- Primitives --------------------------------------------------------------------------

    private fun fixedBox(width: Float, height: Float, child: LayoutElement): LayoutElement =
        Box.Builder()
            .setWidth(dp(width))
            .setHeight(dp(height))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(child)
            .build()

    private fun centered(child: LayoutElement, width: Float): LayoutElement =
        Box.Builder()
            .setWidth(dp(width))
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(child)
            .build()

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        lineHeight: Float? = null,
        tabular: Boolean = false,
        maxLines: Int = 2,
    ): LayoutElement {
        val builder = Text.Builder()
            .setText(value)
            .setFontStyle(font(sizeSp, color, weight = if (bold) 700 else 400, tabular = tabular))
            .setMaxLines(maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        lineHeight?.let { builder.setLineHeight(sp(it)) }
        return builder.build()
    }

    private fun font(
        sizeSp: Float,
        color: Int,
        weight: Int = 400,
        letterSpacingEm: Float? = null,
        tabular: Boolean = false,
    ): FontStyle {
        val builder = FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setColor(argb(color))
            .setWeight(weightConstant(weight))
        letterSpacingEm?.let { builder.setLetterSpacing(em(it)) }
        if (tabular) builder.setSettings(FontSetting.tabularNum())
        return builder.build()
    }

    /** Only normal and bold are stable in the tile renderer; the medium constant is experimental. */
    private fun weightConstant(weight: Int): Int = when {
        weight >= 600 -> LayoutElementBuilders.FONT_WEIGHT_BOLD
        else -> LayoutElementBuilders.FONT_WEIGHT_NORMAL
    }

    private fun vSpace(heightDp: Float): LayoutElement =
        Spacer.Builder().setWidth(dp(1f)).setHeight(dp(heightDp)).build()

    private fun hSpace(widthDp: Float): LayoutElement =
        Spacer.Builder().setWidth(dp(widthDp)).setHeight(dp(1f)).build()

    private fun launchAction(targets: TileTargets): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(targets.packageName)
                    .setClassName(targets.activityClassName)
                    .build(),
            )
            .build()

    private fun loadAction(): ActionBuilders.Action = ActionBuilders.LoadAction.Builder().build()

    private const val BAR_HEIGHT = 4f
    private const val FOOTER_PILL_WIDTH = 124f
}
