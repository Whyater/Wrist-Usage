package io.github.whyater.wristusage.data

import io.github.whyater.wristusage.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class SnapshotParserTest {

    @Test
    fun parsesTheSampleFromTheFormatDoc() {
        val s = SnapshotParser.parse(TestData.DOC_SAMPLE)

        assertEquals(Instant.parse("2026-10-13T04:23:52Z"), s.generatedAt)
        assertEquals(listOf("claude", "codex"), s.providers.map { it.id })

        val claude = s.providers[0]
        assertEquals("Claude", claude.name)
        assertEquals("Max 20x", claude.plan)
        assertEquals(ProviderStatus.Ok, claude.status)
        assertNull(claude.message)
        assertEquals(listOf("5-hour", "Weekly", "Fable weekly"), claude.limits.map { it.label })
        assertEquals(listOf(14.0, 41.0, 0.0), claude.limits.map { it.percent })
        assertEquals(Instant.parse("2026-10-13T04:39:59Z"), claude.limits[0].resetsAt)

        val codex = s.providers[1]
        assertEquals("Pro Lite", codex.plan)
        assertEquals(1, codex.limits.size)
    }

    @Test
    fun highestLimitSpansAllProviders() {
        val (provider, limit) = TestData.sample().highestLimit()!!
        assertEquals("claude", provider.id)
        assertEquals("weekly", limit.id)
        assertEquals(41.0, limit.percent, 0.0)
    }

    @Test
    fun ignoresUnknownFieldsAtEveryLevel() {
        val json = """
        {"schema":1,"generated_at":"2026-10-13T04:00:00Z","future":{"a":1},
         "providers":[{"id":"claude","name":"Claude","plan":null,"status":"ok","message":null,
           "extra":[1,2,3],
           "limits":[{"id":"s","label":"5-hour","percent":10.5,"resets_at":null,"burn_rate":3}]}]}
        """
        val s = SnapshotParser.parse(json)
        assertEquals(10.5, s.providers[0].limits[0].percent, 0.0)
        assertNull(s.providers[0].plan)
    }

    @Test
    fun rejectsOtherSchemaVersionsWithAClearMessage() {
        val e = failure("""{"schema":2,"generated_at":"2026-10-13T04:00:00Z","providers":[]}""")
        assertTrue(e.message!!, e.message!!.contains("schema 2"))
    }

    @Test
    fun schemaMustBeAnIntegralOne() {
        fun json(schema: String) = """{"schema":$schema,"generated_at":"2026-10-13T04:00:00Z","providers":[]}"""
        SnapshotParser.parse(json("1"))
        SnapshotParser.parse(json("1.0"))
        assertTrue(failure(json("1.9")).message!!.contains("schema 1.9"))
        failure(json("\"1\""))
        failure(json("true"))
        failure(json("0"))
        assertEquals("Missing schema", failure(json("null")).message)
    }

    @Test
    fun duplicateProviderIdsKeepTheFirst() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"claude","name":"First","status":"ok","limits":[{"id":"a","label":"A","percent":1}]},
          {"id":"codex","name":"Codex","status":"ok","limits":[{"id":"a","label":"A","percent":2}]},
          {"id":"claude","name":"Second","status":"ok","limits":[{"id":"b","label":"B","percent":3}]}]}"""
        val providers = SnapshotParser.parse(json).providers
        assertEquals(listOf("claude", "codex"), providers.map { it.id })
        assertEquals("First", providers[0].name)
        assertEquals(listOf("a"), providers[0].limits.map { it.id })
    }

    @Test
    fun duplicateLimitIdsInsideAProviderKeepTheFirst() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"c","name":"C","status":"ok","limits":[
            {"id":"weekly","label":"First","percent":10},
            {"id":"session","label":"S","percent":20},
            {"id":"weekly","label":"Second","percent":30}]}]}"""
        val limits = SnapshotParser.parse(json).providers.single().limits
        assertEquals(listOf("First", "S"), limits.map { it.label })
        assertEquals(listOf(10.0, 20.0), limits.map { it.percent })
    }

    @Test
    fun sameLimitIdInDifferentProvidersIsFine() {
        val limits = TestData.sample().providers.flatMap { p -> p.limits.map { it.id } }
        assertEquals(listOf("session", "weekly", "weekly_fable", "weekly"), limits)
    }

    @Test
    fun highestLimitIgnoresProvidersThatAreNotOk() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"a","name":"A","status":"error","limits":[{"id":"x","label":"X","percent":99}]},
          {"id":"b","name":"B","status":"ok","limits":[{"id":"y","label":"Y","percent":12}]}]}"""
        val (provider, limit) = SnapshotParser.parse(json).highestLimit()!!
        assertEquals("b", provider.id)
        assertEquals(12.0, limit.percent, 0.0)
    }

    @Test
    fun futureGeneratedAtFallsBackToDownloadTimeOnlyBeyondTheSkewAllowance() {
        val fetched = Instant.parse("2026-10-13T04:00:00Z")
        val base = TestData.sample()
        val slightlyAhead = base.copy(generatedAt = fetched.plusSeconds(100))
        assertEquals(slightlyAhead, slightlyAhead.withSaneClock(fetched))
        val farAhead = base.copy(generatedAt = fetched.plusSeconds(3600))
        assertEquals(fetched, farAhead.withSaneClock(fetched).generatedAt)
        assertEquals(base, base.withSaneClock(fetched.plusSeconds(86_400)))
    }

    @Test
    fun rejectsMissingSchema() {
        val e = failure("""{"generated_at":"2026-10-13T04:00:00Z","providers":[]}""")
        assertEquals("Missing schema", e.message)
    }

    @Test
    fun rejectsInvalidJson() {
        assertEquals("Not valid JSON", failure("<html>404</html>").message)
    }

    @Test
    fun rejectsMissingOrBadTimestamp() {
        assertTrue(failure("""{"schema":1,"providers":[]}""").message!!.contains("generated_at"))
        assertTrue(failure("""{"schema":1,"generated_at":"yesterday","providers":[]}""").message!!.contains("generated_at"))
    }

    @Test
    fun authExpiredProviderWithEmptyLimits() {
        val json = """
        {"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"claude","name":"Claude","plan":"Max 20x","status":"auth_expired","message":"Sign in again on the Mac","limits":[]}]}
        """
        val p = SnapshotParser.parse(json).providers.single()
        assertEquals(ProviderStatus.AuthExpired, p.status)
        assertEquals("Sign in again on the Mac", p.message)
        assertTrue(p.limits.isEmpty())
    }

    @Test
    fun unknownStatusIsTreatedAsError() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"x","name":"X","status":"on_fire","limits":[]}]}"""
        assertEquals(ProviderStatus.Error, SnapshotParser.parse(json).providers.single().status)
    }

    @Test
    fun nullAndMissingResetsAtBecomeNull() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"c","name":"C","status":"ok","limits":[
            {"id":"a","label":"A","percent":5,"resets_at":null},
            {"id":"b","label":"B","percent":6},
            {"id":"d","label":"D","percent":7,"resets_at":"not a date"}]}]}"""
        val limits = SnapshotParser.parse(json).providers.single().limits
        assertEquals(3, limits.size)
        assertTrue(limits.all { it.resetsAt == null })
    }

    @Test
    fun percentIsClampedAndLimitsWithoutPercentAreDropped() {
        val json = """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[
          {"id":"c","name":"C","status":"ok","limits":[
            {"id":"a","label":"A","percent":140},
            {"id":"b","label":"B","percent":-3},
            {"id":"d","label":"D","percent":null},
            {"id":"e","label":"E"}]}]}"""
        val limits = SnapshotParser.parse(json).providers.single().limits
        assertEquals(listOf(100.0, 0.0), limits.map { it.percent })
    }

    @Test
    fun acceptsOffsetTimestamps() {
        val json = """{"schema":1,"generated_at":"2026-10-13T06:00:00+02:00","providers":[]}"""
        assertEquals(Instant.parse("2026-10-13T04:00:00Z"), SnapshotParser.parse(json).generatedAt)
    }

    private fun failure(raw: String): SnapshotException {
        try {
            SnapshotParser.parse(raw)
        } catch (e: SnapshotException) {
            return e
        }
        fail("expected SnapshotException")
        throw AssertionError()
    }
}
