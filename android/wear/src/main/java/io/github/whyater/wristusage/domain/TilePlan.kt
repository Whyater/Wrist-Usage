package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.data.Provider
import io.github.whyater.wristusage.data.ProviderStatus
import io.github.whyater.wristusage.data.Snapshot
import io.github.whyater.wristusage.data.UsageLimit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Fixed sizes of the tile building blocks, in dp. The planner and the layout both read these. */
object TileMetrics {
    const val HEADER_HEIGHT = 13f
    const val HEADER_GAP = 2f
    const val ROW_HEIGHT = 22f
    const val ROW_GAP = 3f
    const val SECTION_GAP = 7f
    const val MESSAGE_HEIGHT = 28f
    const val MORE_HEIGHT = 12f
    const val FOOTER_GAP = 4f
    const val FOOTER_HEIGHT = 18f

    /** Share of the screen diameter the whole content column may use vertically. */
    const val HEIGHT_BUDGET = 0.84f

    /** Widest a block may get, as a share of the screen diameter. */
    const val MAX_WIDTH_SHARE = 0.84f

    /** Clear space kept between a block corner and the bezel. */
    const val EDGE_MARGIN = 5f
    const val MIN_WIDTH = 64f
}

/** What one provider contributes to the tile. */
data class TileSection(
    val provider: Provider,
    /** Limits that fit on the tile, in the order they appear in the snapshot. */
    val limits: List<UsageLimit>,
) {
    /** True when the section shows a message line instead of bars. */
    val showsMessage: Boolean get() = provider.status != ProviderStatus.Ok || provider.limits.isEmpty()
}

data class TilePlan(
    val sections: List<TileSection>,
    /** Number of limits that did not fit. Shown as "+N more". */
    val hiddenCount: Int,
)

sealed interface TileBlock {
    val height: Float
    val gapBefore: Float

    data class Header(val provider: Provider, override val gapBefore: Float) : TileBlock {
        override val height get() = TileMetrics.HEADER_HEIGHT
    }

    data class Row(val limit: UsageLimit, val provider: Provider, override val gapBefore: Float) : TileBlock {
        override val height get() = TileMetrics.ROW_HEIGHT
    }

    data class Message(val provider: Provider, override val gapBefore: Float) : TileBlock {
        override val height get() = TileMetrics.MESSAGE_HEIGHT
    }

    data class More(val count: Int, override val gapBefore: Float) : TileBlock {
        override val height get() = TileMetrics.MORE_HEIGHT
    }

    data class Footer(override val gapBefore: Float) : TileBlock {
        override val height get() = TileMetrics.FOOTER_HEIGHT
    }
}

/** A block with its final width on a particular round screen. */
data class PlacedBlock(val block: TileBlock, val widthDp: Float, val topDp: Float)

object TilePlanner {

    /**
     * Chooses which limits to show. Every provider that reports limits keeps at least its
     * highest-percent one, then the remaining space goes to the highest percents overall.
     * The result keeps the snapshot order.
     */
    fun plan(snapshot: Snapshot, screenDp: Float): TilePlan {
        val providers = snapshot.providers
        val totalLimits = providers.filter { it.status == ProviderStatus.Ok }.sumOf { it.limits.size }
        val capacity = rowCapacity(providers, screenDp, reserveMore = false)
        val rowBudget = if (totalLimits > capacity) rowCapacity(providers, screenDp, reserveMore = true) else capacity
        return prioritize(providers, rowBudget)
    }

    /** Picks at most [maxRows] limits (but never fewer than one per provider with limits). */
    fun prioritize(providers: List<Provider>, maxRows: Int): TilePlan {
        // (provider index, limit index) pairs
        val candidates = providers.flatMapIndexed { pi, p ->
            if (p.status != ProviderStatus.Ok) emptyList() else p.limits.indices.map { pi to it }
        }
        val chosen = LinkedHashSet<Pair<Int, Int>>()

        // 1. best limit of each provider
        providers.forEachIndexed { pi, p ->
            if (p.status == ProviderStatus.Ok && p.limits.isNotEmpty()) {
                val best = p.limits.indices.maxBy { p.limits[it].percent } // first max wins on ties
                chosen += pi to best
            }
        }
        // 2. fill by percent, highest first; ties keep snapshot order
        val rest = candidates
            .filter { it !in chosen }
            .sortedByDescending { (pi, li) -> providers[pi].limits[li].percent }
        for (c in rest) {
            if (chosen.size >= maxRows) break
            chosen += c
        }

        val sections = providers.mapIndexed { pi, p ->
            val visible = if (p.status == ProviderStatus.Ok) {
                p.limits.filterIndexed { li, _ -> (pi to li) in chosen }
            } else {
                emptyList()
            }
            TileSection(p, visible)
        }
        return TilePlan(sections, hiddenCount = candidates.size - chosen.size)
    }

    /** How many limit rows fit in the height budget, never below one per provider that has limits. */
    fun rowCapacity(providers: List<Provider>, screenDp: Float, reserveMore: Boolean): Int {
        val budget = screenDp * TileMetrics.HEIGHT_BUDGET
        var fixed = TileMetrics.FOOTER_GAP + TileMetrics.FOOTER_HEIGHT
        if (reserveMore) fixed += TileMetrics.ROW_GAP + TileMetrics.MORE_HEIGHT
        providers.forEachIndexed { i, p ->
            if (i > 0) fixed += TileMetrics.SECTION_GAP
            fixed += TileMetrics.HEADER_HEIGHT
            if (p.status != ProviderStatus.Ok || p.limits.isEmpty()) {
                fixed += TileMetrics.HEADER_GAP + TileMetrics.MESSAGE_HEIGHT
            } else {
                fixed += TileMetrics.HEADER_GAP
            }
        }
        val perRow = TileMetrics.ROW_HEIGHT + TileMetrics.ROW_GAP
        // The first row of each provider has no ROW_GAP before it, so add one gap back per provider.
        val withRows = providers.count { it.status == ProviderStatus.Ok && it.limits.isNotEmpty() }
        val remaining = budget - fixed + withRows * TileMetrics.ROW_GAP
        val fits = (remaining / perRow).toInt()
        return max(fits, withRows)
    }

    /** Flattens a plan into ordered blocks with their gaps. */
    fun blocks(plan: TilePlan, withFooter: Boolean = true): List<TileBlock> = buildList {
        plan.sections.forEachIndexed { i, section ->
            add(TileBlock.Header(section.provider, gapBefore = if (i == 0) 0f else TileMetrics.SECTION_GAP))
            if (section.showsMessage) {
                add(TileBlock.Message(section.provider, gapBefore = TileMetrics.HEADER_GAP))
            } else {
                section.limits.forEachIndexed { li, limit ->
                    add(
                        TileBlock.Row(
                            limit,
                            section.provider,
                            gapBefore = if (li == 0) TileMetrics.HEADER_GAP else TileMetrics.ROW_GAP,
                        ),
                    )
                }
            }
        }
        if (plan.hiddenCount > 0) add(TileBlock.More(plan.hiddenCount, gapBefore = TileMetrics.ROW_GAP))
        if (withFooter) add(TileBlock.Footer(gapBefore = TileMetrics.FOOTER_GAP))
    }

    fun contentHeight(blocks: List<TileBlock>): Float = blocks.sumOf { (it.gapBefore + it.height).toDouble() }.toFloat()

    /**
     * Gives each block the widest width that keeps its corners inside the round screen,
     * assuming the column is centred vertically. Rows (and the message line) of one provider
     * share the narrowest width in their group, so their left and right edges line up.
     */
    fun place(blocks: List<TileBlock>, screenDp: Float): List<PlacedBlock> {
        val radius = screenDp / 2f
        val total = contentHeight(blocks)
        var top = -total / 2f
        val placed = blocks.map { block ->
            top += block.gapBefore
            val bottom = top + block.height
            val farthest = max(abs(top), abs(bottom))
            val chord = if (farthest >= radius) 0f else 2f * sqrt(radius * radius - farthest * farthest)
            val width = (chord - 2f * TileMetrics.EDGE_MARGIN)
                .coerceAtMost(screenDp * TileMetrics.MAX_WIDTH_SHARE)
                .coerceAtLeast(TileMetrics.MIN_WIDTH)
            PlacedBlock(block, width, top).also { top = bottom }
        }

        // Group = the rows or message that follow one header.
        val result = placed.toMutableList()
        var start = 0
        while (start < result.size) {
            var end = start
            while (end < result.size && isBody(result[end].block)) end++
            if (end > start) {
                val shared = (start until end).minOf { result[it].widthDp }
                for (i in start until end) result[i] = result[i].copy(widthDp = shared)
                start = end
            } else {
                start++
            }
        }
        return result
    }

    private fun isBody(block: TileBlock) = block is TileBlock.Row || block is TileBlock.Message
}
