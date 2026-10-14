package io.github.whyater.wristusage.tile

import io.github.whyater.wristusage.TestData
import io.github.whyater.wristusage.data.FetchError
import io.github.whyater.wristusage.data.SnapshotParser
import io.github.whyater.wristusage.data.UsageState
import io.github.whyater.wristusage.domain.Palette
import io.github.whyater.wristusage.domain.TilePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Builds every tile state, serialises it, and checks the text a person would read on the watch. */
class UsageTileLayoutTest {
    private val now = Instant.parse("2026-10-13T04:25:00Z")
    private val targets = TileTargets("io.github.whyater.wristusage", "io.github.whyater.wristusage.ui.MainActivity")

    private fun element(state: UsageState, dp: Float = 192f) = UsageTileLayout.render(state, now, dp, targets)

    /** Every string in the layout, in tree order. */
    private fun texts(state: UsageState, dp: Float = 192f): List<String> =
        Regex("""value: "([^"]*)"""")
            .findAll(element(state, dp).toLayoutElementProto().toString())
            .map { unescape(it.groupValues[1]) }
            .toList()

    /** Proto text format writes non-ASCII bytes as octal escapes (the middle dot becomes a pair of them). */
    private fun unescape(s: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 4 <= s.length && s.substring(i + 1, i + 4).all { it in '0'..'7' }) {
                bytes.write(s.substring(i + 1, i + 4).toInt(8))
                i += 4
            } else {
                bytes.write(s[i].toString().toByteArray())
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private val fetched = Instant.parse("2026-10-13T04:24:00Z")
    private val ok = UsageState(true, TestData.sample(), fetched, null)
    private val offline = FetchError("Offline", "No connection")
    private val staleSnapshot = SnapshotParser.parse(TestData.DOC_SAMPLE.replace("04:23:52", "02:00:00")) // 2h25m old

    // ---- full screens ----------------------------------------------------------------------------

    @Test fun notSetUp() {
        val t = texts(UsageState(false, null, null, null))
        assertEquals(listOf("Not set up", "Open Wrist Usage", "on your phone to set up"), t)
    }

    @Test fun loading() {
        assertEquals(listOf("Loading", "Waiting for data"), texts(UsageState(true, null, null, null)))
        assertEquals(
            listOf("Loading", "Fetching usage"),
            texts(UsageState(true, null, null, null, refreshing = true)),
        )
    }

    @Test fun errorWithoutData() {
        val t = texts(UsageState(true, null, null, offline))
        assertEquals(listOf("Offline", "No usage data yet.", "Tap to retry."), t)
    }

    @Test fun okShowsRowsPercentsAndAFreshFooter() {
        val t = texts(ok)
        assertTrue(t.toString(), t.containsAll(listOf("CLAUDE", "CODEX", "5-hour", "Weekly", "14%", "41%", "8%")))
        assertEquals("Updated 1m ago", t.last())
    }

    @Test fun bothScreenSizesBuild() {
        assertEquals("Updated 1m ago", texts(ok, 192f).last())
        assertEquals("Updated 1m ago", texts(ok, 227f).last())
    }

    @Test fun authExpiredProviderShowsItsMessage() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:20:00Z","providers":[
          {"id":"claude","name":"Claude","plan":"Max 20x","status":"auth_expired","message":"Sign in again on the Mac","limits":[]},
          {"id":"codex","name":"Codex","plan":null,"status":"ok","limits":[{"id":"w","label":"Weekly","percent":96,"resets_at":null}]}]}"""
        val t = texts(ok.copy(snapshot = SnapshotParser.parse(json)))
        assertTrue(t.toString(), "Sign in again on the Mac" in t)
        assertTrue(t.toString(), "96%" in t)
    }

    // ---- "+N more" ---------------------------------------------------------------------------------

    @Test fun moreLineCountsTheHiddenLimits() {
        val snapshot = SnapshotParser.parse(
            TestData.json(
                listOf(
                    Triple("claude", "Claude", List(8) { it * 12.0 }),
                    Triple("codex", "Codex", List(4) { it * 30.0 }),
                ),
            ),
        )
        // 192 dp fits 3 of the 12 limits once the "+N more" line is reserved, so 9 are hidden.
        assertEquals(9, TilePlanner.plan(snapshot, 192f).hiddenCount)
        val t = texts(ok.copy(snapshot = snapshot))
        assertEquals(1, t.count { it == "+9 more" })
    }

    @Test fun noMoreLineWhenEverythingFits() {
        assertFalse(texts(ok).any { it.endsWith("more") })
    }

    // ---- footer ------------------------------------------------------------------------------------

    private fun footer(state: UsageState) = UsageTileLayout.footerLabel(state.snapshot!!, state, now)

    @Test fun refreshingOnFreshDataSaysRefreshing() {
        val f = footer(ok.copy(refreshing = true))
        assertEquals("Refreshing...", f.text)
        assertEquals(Palette.TEXT_SECONDARY, f.color)
    }

    @Test fun refreshingOnStaleDataKeepsTheAge() {
        val s = ok.copy(snapshot = staleSnapshot, refreshing = true)
        val f = footer(s)
        assertEquals("Refreshing · 2h ago", f.text)
        assertEquals(Palette.WARNING, f.color)
        assertEquals("Refreshing · 2h ago", texts(s).last())
    }

    @Test fun refreshingAfterAFailureKeepsTheAge() {
        val f = footer(ok.copy(refreshing = true, error = offline))
        assertEquals("Refreshing · 1m ago", f.text)
        assertEquals(Palette.WARNING, f.color)
    }

    @Test fun errorFooterNamesTheErrorAndTheAge() {
        val f = footer(ok.copy(error = offline))
        assertEquals("Offline · 1m ago", f.text)
        assertEquals(Palette.WARNING, f.color)
    }

    @Test fun staleFooterIsWarningColored() {
        val f = footer(ok.copy(snapshot = staleSnapshot))
        assertEquals("Updated 2h ago", f.text)
        assertEquals(Palette.WARNING, f.color)
    }

    @Test fun freshFooterIsQuiet() {
        assertEquals(Palette.TEXT_TERTIARY, footer(ok).color)
    }

    // ---- countdowns on old data --------------------------------------------------------------------

    @Test fun passedResetOnStaleDataReadsReset() {
        // The sample's 5-hour window reset at 04:39, which is before "now" once the clock is far ahead.
        val later = Instant.parse("2026-10-13T06:00:00Z")
        val t = UsageTileLayout.render(ok.copy(refreshing = false), later, 192f, targets)
            .toLayoutElementProto().toString()
        assertTrue(t.contains("value: \"Reset\""))
        assertFalse(t.contains("value: \"now\""))
    }

    // ---- taps --------------------------------------------------------------------------------------

    @Test fun refreshTapsAreRecognisedAndEachRenderHasItsOwnId() {
        val a = element(ok).toLayoutElementProto().toString()
        val ids = Regex("""id: "(refresh:\d+)"""").findAll(a).map { it.groupValues[1] }.toSet()
        assertEquals(1, ids.size)
        assertTrue(UsageTileLayout.isRefreshTap(ids.single()))
        assertFalse(UsageTileLayout.isRefreshTap("open"))
        assertFalse(UsageTileLayout.isRefreshTap(""))
        assertFalse(UsageTileLayout.isRefreshTap(null))

        val b = UsageTileLayout.render(ok, now.plusSeconds(5), 192f, targets).toLayoutElementProto().toString()
        assertFalse(b.contains(ids.single()))
    }
}
