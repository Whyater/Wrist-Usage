package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.TestData
import io.github.whyater.wristusage.data.Snapshot
import io.github.whyater.wristusage.data.SnapshotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TilePlannerTest {

    private fun snap(vararg p: Triple<String, String, List<Double>>): Snapshot =
        SnapshotParser.parse(TestData.json(p.toList()))

    private fun ids(plan: TilePlan, section: Int) = plan.sections[section].limits.map { it.id }

    @Test
    fun docSampleShowsEverythingOnBothScreenSizes() {
        for (dp in listOf(192f, 227f)) {
            val plan = TilePlanner.plan(TestData.sample(), dp)
            assertEquals("screen $dp", 0, plan.hiddenCount)
            assertEquals(listOf("session", "weekly", "weekly_fable"), ids(plan, 0))
            assertEquals(listOf("weekly"), ids(plan, 1))
        }
    }

    @Test
    fun keepsTheHighestPercentsAndShowsHowManyAreHidden() {
        val s = snap(
            Triple("claude", "Claude", listOf(10.0, 95.0, 30.0, 60.0, 5.0)),
            Triple("codex", "Codex", listOf(20.0, 80.0)),
        )
        val plan = TilePlanner.prioritize(s.providers, maxRows = 4)

        // Top 4 by percent: 95, 80, 60, 30. Displayed in snapshot order.
        assertEquals(listOf("l1", "l2", "l3"), ids(plan, 0))
        assertEquals(listOf("l1"), ids(plan, 1))
        assertEquals(3, plan.hiddenCount)
    }

    @Test
    fun everyProviderKeepsItsBestLimitEvenWhenAnotherProviderIsHigher() {
        val s = snap(
            Triple("claude", "Claude", listOf(90.0, 92.0, 95.0)),
            Triple("codex", "Codex", listOf(3.0)),
        )
        val plan = TilePlanner.prioritize(s.providers, maxRows = 2)

        assertEquals(listOf("l2"), ids(plan, 0))
        assertEquals(listOf("l0"), ids(plan, 1))
        assertEquals(2, plan.hiddenCount)
    }

    @Test
    fun ties_keepSnapshotOrder() {
        val s = snap(Triple("claude", "Claude", listOf(50.0, 50.0, 50.0)))
        val plan = TilePlanner.prioritize(s.providers, maxRows = 2)
        assertEquals(listOf("l0", "l1"), ids(plan, 0))
        assertEquals(1, plan.hiddenCount)
    }

    @Test
    fun plannerDropsRowsWhenTheScreenIsTooSmallForThem() {
        val s = snap(
            Triple("claude", "Claude", listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0)),
            Triple("codex", "Codex", listOf(70.0, 80.0)),
        )
        val plan = TilePlanner.plan(s, 192f)
        val shown = plan.sections.sumOf { it.limits.size }
        assertTrue("shown=$shown", shown in 3..6)
        assertEquals(8 - shown, plan.hiddenCount)
        // The highest percent always survives.
        assertTrue(plan.sections[1].limits.any { it.percent == 80.0 })
    }

    @Test
    fun largerScreenFitsAtLeastAsManyRows() {
        val s = snap(
            Triple("claude", "Claude", listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)),
            Triple("codex", "Codex", listOf(7.0, 8.0, 9.0)),
        )
        val small = TilePlanner.plan(s, 192f).sections.sumOf { it.limits.size }
        val large = TilePlanner.plan(s, 227f).sections.sumOf { it.limits.size }
        assertTrue("small=$small large=$large", large >= small)
    }

    @Test
    fun nonOkProvidersShowAMessageInsteadOfRows() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"claude","name":"Claude","plan":"Max","status":"auth_expired","message":"Sign in again","limits":[]},
          {"id":"codex","name":"Codex","plan":null,"status":"ok","limits":[
            {"id":"w","label":"Weekly","percent":8,"resets_at":null}]}]}"""
        val plan = TilePlanner.plan(SnapshotParser.parse(json), 192f)
        assertTrue(plan.sections[0].showsMessage)
        assertTrue(plan.sections[0].limits.isEmpty())
        assertEquals(listOf("w"), ids(plan, 1))

        val kinds = TilePlanner.blocks(plan).map { it::class.simpleName }
        assertEquals(listOf("Header", "Message", "Header", "Row", "Footer"), kinds)
    }

    // ---- Round screen geometry ------------------------------------------------------------

    @Test
    fun everyBlockCornerStaysInsideTheCircle() {
        val samples = listOf(
            TestData.sample(),
            snap(
                Triple("claude", "Claude", listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0)),
                Triple("codex", "Codex", listOf(70.0, 80.0)),
            ),
        )
        for (dp in listOf(192f, 210f, 227f)) {
            for (s in samples) {
                val blocks = TilePlanner.blocks(TilePlanner.plan(s, dp))
                val placed = TilePlanner.place(blocks, dp)
                val radius = dp / 2f
                for (p in placed) {
                    val half = p.widthDp / 2f
                    val cornerY = maxOf(abs(p.topDp), abs(p.topDp + p.block.height))
                    val corner = hypot(half, cornerY)
                    assertTrue(
                        "dp=$dp ${p.block::class.simpleName} corner at $corner exceeds radius $radius",
                        corner <= radius + 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun contentFitsTheHeightBudget() {
        for (dp in listOf(192f, 227f)) {
            val blocks = TilePlanner.blocks(TilePlanner.plan(TestData.sample(), dp))
            assertTrue(TilePlanner.contentHeight(blocks) <= dp * TileMetrics.HEIGHT_BUDGET)
        }
    }

    @Test
    fun blocksAtTheTopAndBottomEdgesAreNarrowerThanTheCap() {
        val s = snap(
            Triple("claude", "Claude", listOf(10.0, 20.0, 30.0)),
            Triple("codex", "Codex", listOf(40.0, 50.0)),
        )
        val placed = TilePlanner.place(TilePlanner.blocks(TilePlanner.plan(s, 227f)), 227f)
        val cap = 227f * TileMetrics.MAX_WIDTH_SHARE
        assertTrue(placed.first().widthDp < cap)
        assertTrue(placed.last().widthDp < cap)
    }

    @Test
    fun rowsOfOneProviderShareTheNarrowestWidthSoEdgesLineUp() {
        val s = snap(
            Triple("claude", "Claude", listOf(10.0, 20.0, 30.0)),
            Triple("codex", "Codex", listOf(40.0, 50.0)),
        )
        val placed = TilePlanner.place(TilePlanner.blocks(TilePlanner.plan(s, 227f)), 227f)
        val groups = placed.filter { it.block is TileBlock.Row }
            .groupBy { (it.block as TileBlock.Row).provider.id }
        for ((id, rows) in groups) {
            assertEquals("provider $id", 1, rows.map { it.widthDp }.distinct().size)
        }
        // The top row of the first block is the tightest, so the whole block is narrower than the cap.
        assertTrue(groups.getValue("claude").first().widthDp < 227f * TileMetrics.MAX_WIDTH_SHARE)
    }
}
