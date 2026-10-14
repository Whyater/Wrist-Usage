package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.TestData
import io.github.whyater.wristusage.data.SnapshotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ComplicationContentTest {
    private val fresh = Instant.parse("2026-10-13T04:25:00Z")

    @Test fun showsTheHighestLimitWithTheProviderName() {
        val c = ComplicationContent.from(TestData.sample(), fresh)
        assertEquals(41.0, c.percent!!, 0.0)
        assertEquals("Claude", c.title)
        assertEquals("Claude Weekly 41% used", c.description)
    }

    @Test fun staleDataKeepsTheValueButSaysSo() {
        val c = ComplicationContent.from(TestData.sample(), Instant.parse("2026-10-13T05:23:52Z")) // 1h old
        assertEquals(41.0, c.percent!!, 0.0)
        assertEquals("Stale", c.title)
        assertEquals("Claude Weekly 41% used, updated 1h ago", c.description)
    }

    @Test fun exactlyThirtyMinutesIsNotStaleYet() {
        val c = ComplicationContent.from(TestData.sample(), Instant.parse("2026-10-13T04:53:52Z"))
        assertEquals("Claude", c.title)
    }

    @Test fun providersThatAreNotOkAreIgnored() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:20:00Z","providers":[
          {"id":"a","name":"A","status":"error","limits":[{"id":"x","label":"X","percent":99}]},
          {"id":"b","name":"B","status":"ok","limits":[{"id":"y","label":"Y","percent":12}]}]}"""
        val c = ComplicationContent.from(SnapshotParser.parse(json), fresh)
        assertEquals(12.0, c.percent!!, 0.0)
        assertEquals("B", c.title)
    }

    @Test fun noUsableLimitsReadsAsNoData() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:20:00Z","providers":[
          {"id":"a","name":"A","status":"auth_expired","message":"Sign in","limits":[]}]}"""
        val c = ComplicationContent.from(SnapshotParser.parse(json), fresh)
        assertNull(c.percent)
        assertEquals("No usage data", c.description)
        assertNull(ComplicationContent.from(null, fresh).percent)
    }
}
