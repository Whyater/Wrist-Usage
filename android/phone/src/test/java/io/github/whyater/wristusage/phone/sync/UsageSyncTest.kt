package io.github.whyater.wristusage.phone.sync

import io.github.whyater.wristusage.data.FetchError
import io.github.whyater.wristusage.data.HttpStatusException
import io.github.whyater.wristusage.phone.data.Cached
import io.github.whyater.wristusage.phone.data.PhoneStore
import io.github.whyater.wristusage.sync.SyncContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant

private class FakeStore : PhoneStore {
    var savedUrl: String? = null
    var raw: String? = null
    var fetchedAt: Instant? = null
    var lastPush: Instant? = null

    override fun url() = savedUrl
    override fun setUrl(url: String?) { savedUrl = url }
    override fun cache(): Cached? {
        val r = raw ?: return null
        return Cached.parse(r, fetchedAt!!)
    }
    override fun saveCache(raw: String, fetchedAt: Instant) { this.raw = raw; this.fetchedAt = fetchedAt }
    override fun lastPushAt() = lastPush
    override fun setLastPushAt(at: Instant?) { lastPush = at }
    override fun clearCache() { raw = null; fetchedAt = null; lastPush = null }
}

private class FakeLink : WatchLink {
    data class Snapshot(val json: String, val fetchedAtMillis: Long)
    val snapshots = mutableListOf<Snapshot>()
    val configs = mutableListOf<String>()
    var failPuts = false
    var status: WatchStatus = WatchStatus.NoWatch

    override suspend fun putSnapshot(json: String, fetchedAtMillis: Long) {
        if (failPuts) throw IllegalStateException("Data Layer down")
        snapshots += Snapshot(json, fetchedAtMillis)
    }

    override suspend fun putConfig(url: String) {
        if (failPuts) throw IllegalStateException("Data Layer down")
        configs += url
    }

    override suspend fun watchStatus() = status
}

class UsageSyncTest {
    private val t0 = Instant.parse("2026-10-13T12:00:00Z")
    private val url = "https://example.com/usage.json"

    private fun json(percent: Int, generatedAt: String = "2026-10-13T11:58:00Z") = """
        {"schema":1,"generated_at":"$generatedAt","providers":[
          {"id":"claude","name":"Claude","plan":"Max","status":"ok","message":null,
           "limits":[{"id":"session","label":"5-hour","percent":$percent,"resets_at":"2026-10-13T15:00:00Z"}]}]}
    """.trimIndent()

    private class Rig(
        val store: FakeStore = FakeStore(),
        val link: FakeLink = FakeLink(),
        var body: () -> String,
        var now: Instant,
    ) {
        val sync = UsageSync(
            store = store,
            link = link,
            download = { _, _ -> body() },
            clock = { now },
            io = Dispatchers.Unconfined,
        )
    }

    private fun rig(body: () -> String) = Rig(body = body, now = t0)

    @Test
    fun refreshPushesRawJsonAndFetchTimeToTheWatch() = runBlocking {
        val r = rig { json(14) }
        r.store.savedUrl = url

        val result = r.sync.refresh()

        assertTrue(result is RefreshResult.Success && result.pushed)
        assertEquals(1, r.link.snapshots.size)
        assertEquals(json(14), r.link.snapshots[0].json)
        assertEquals(t0.toEpochMilli(), r.link.snapshots[0].fetchedAtMillis)
        assertEquals(t0, r.store.lastPush)
        assertEquals(t0, r.sync.state.value.lastPushAt)
    }

    @Test
    fun refreshWithoutUrlDoesNothing() = runBlocking {
        val r = rig { error("should not download") }

        assertEquals(RefreshResult.NoUrl, r.sync.refresh())
        assertTrue(r.link.snapshots.isEmpty())
    }

    @Test
    fun failedFetchKeepsTheCachedSnapshotAndRecordsTheError() = runBlocking {
        var fail = false
        val r = rig { if (fail) throw UnknownHostException("example.com") else json(41) }
        r.store.savedUrl = url
        r.sync.refresh()
        val before = r.sync.state.value.cached
        assertEquals(41.0, before!!.snapshot.providers[0].limits[0].percent, 0.0)

        fail = true
        r.now = t0.plusSeconds(900)
        val result = r.sync.refresh()

        assertTrue(result is RefreshResult.Failure)
        val after = r.sync.state.value
        assertEquals(before, after.cached)
        assertEquals(FetchError.OFFLINE, after.lastError?.short)
        assertEquals(json(41), r.store.raw)
        assertEquals(t0, r.store.fetchedAt)
        assertEquals("nothing new is pushed", 1, r.link.snapshots.size)
        assertFalse(after.refreshing)
    }

    @Test
    fun badDataKeepsTheCacheToo() = runBlocking {
        var body = json(41)
        val r = rig { body }
        r.store.savedUrl = url
        r.sync.refresh()

        body = "<html>login page</html>"
        val result = r.sync.refresh()

        assertTrue(result is RefreshResult.Failure)
        assertEquals(json(41), r.store.raw)
        assertEquals("Bad data", r.sync.state.value.lastError?.short)
        assertEquals(1, r.link.snapshots.size)
    }

    @Test
    fun httpErrorKeepsTheCache() = runBlocking {
        var fail = false
        val r = rig { if (fail) throw HttpStatusException(503) else json(10) }
        r.store.savedUrl = url
        r.sync.refresh()
        fail = true
        r.sync.refresh()

        assertEquals("HTTP 503", r.sync.state.value.lastError?.short)
        assertEquals(json(10), r.store.raw)
    }

    @Test
    fun nextSuccessClearsTheError() = runBlocking {
        var fail = true
        val r = rig { if (fail) throw IOException("boom") else json(5) }
        r.store.savedUrl = url
        r.sync.refresh()
        assertTrue(r.sync.state.value.lastError != null)

        fail = false
        r.sync.refresh()
        assertNull(r.sync.state.value.lastError)
    }

    @Test
    fun watchUnreachableDoesNotFailTheFetch() = runBlocking {
        val r = rig { json(22) }
        r.store.savedUrl = url
        r.link.failPuts = true

        val result = r.sync.refresh()

        assertTrue(result is RefreshResult.Success && !result.pushed)
        assertEquals(json(22), r.store.raw)
        assertNull(r.sync.state.value.lastPushAt)
        assertNull(r.sync.state.value.lastError)
    }

    @Test
    fun saveWritesConfigAndFirstSnapshot() = runBlocking {
        val r = rig { json(14) }

        val result = r.sync.saveUrl("  $url \n")

        assertTrue(result is SaveResult.Saved)
        assertEquals(url, r.store.savedUrl)
        assertEquals(listOf(url), r.link.configs)
        assertEquals(1, r.link.snapshots.size)
        assertEquals(url, r.sync.state.value.url)
    }

    @Test
    fun saveRejectsAnythingThatIsNotASnapshotAndSavesNothing() = runBlocking {
        val r = rig { """{"hello":"world"}""" }

        val result = r.sync.saveUrl(url)

        assertEquals(SaveResult.Rejected(SetupMessages.NOT_SNAPSHOT), result)
        assertNull(r.store.savedUrl)
        assertNull(r.store.raw)
        assertTrue(r.link.configs.isEmpty())
        assertTrue(r.link.snapshots.isEmpty())
    }

    @Test
    fun saveSaysWhenTheServerCannotBeReached() = runBlocking {
        val r = rig { throw UnknownHostException("nope") }

        assertEquals(SaveResult.Rejected(SetupMessages.UNREACHABLE), r.sync.saveUrl(url))
        assertNull(r.store.savedUrl)
    }

    @Test
    fun saveRejectsAnInvalidAddressWithoutDownloading() = runBlocking {
        val r = rig { error("should not download") }

        val result = r.sync.saveUrl("ftp://example.com/usage.json")

        assertTrue(result is SaveResult.Rejected)
        assertNull(r.store.savedUrl)
    }

    @Test
    fun aRejectedSaveKeepsTheOldUrlAndData() = runBlocking {
        var body = json(33)
        val r = rig { body }
        r.sync.saveUrl(url)

        body = "nope"
        r.sync.saveUrl("https://example.com/other.json")

        assertEquals(url, r.store.savedUrl)
        assertEquals(json(33), r.store.raw)
        assertEquals(listOf(url), r.link.configs)
    }

    @Test
    fun saveStillWorksWhenTheWatchLinkIsDown() = runBlocking {
        val r = rig { json(14) }
        r.link.failPuts = true

        val result = r.sync.saveUrl(url)

        assertTrue(result is SaveResult.Saved && !result.pushed)
        assertEquals(url, r.store.savedUrl)
    }

    @Test
    fun clearForgetsEverythingAndTellsTheWatch() = runBlocking {
        val r = rig { json(14) }
        r.sync.saveUrl(url)

        r.sync.clearUrl()

        assertNull(r.store.savedUrl)
        assertNull(r.store.raw)
        assertEquals(listOf(url, ""), r.link.configs)
        assertNull(r.sync.state.value.url)
        assertNull(r.sync.state.value.cached)
        assertEquals(RefreshResult.NoUrl, r.sync.refresh())
    }

    @Test
    fun startupPushesTheSavedUrlOrAClear() = runBlocking {
        val r = rig { json(1) }
        r.sync.pushConfig()
        assertEquals("no URL: repeat the Clear in case the watch missed it", listOf(""), r.link.configs)
        r.link.configs.clear()

        r.store.savedUrl = url
        r.sync.pushConfig()
        assertEquals(listOf(url), r.link.configs)
        assertEquals(SyncContract.PATH_CONFIG, "/wristusage/config")
    }

    @Test
    fun startupConfigPushSurvivesAnUnavailableDataLayer() = runBlocking {
        val r = rig { json(1) }
        r.store.savedUrl = url
        r.link.failPuts = true

        r.sync.pushConfig() // must return normally instead of throwing
        assertEquals(url, r.store.savedUrl)
    }

    @Test
    fun stateStartsFromWhatIsStored() {
        val store = FakeStore().apply {
            savedUrl = "https://example.com/usage.json"
            raw = json(7)
            fetchedAt = t0
            lastPush = t0
        }
        val sync = UsageSync(store, FakeLink(), { _, _ -> error("no") }, { t0 })

        val state = sync.state.value
        assertEquals(url, state.url)
        assertEquals(7.0, state.cached!!.snapshot.providers[0].limits[0].percent, 0.0)
        assertEquals(t0, state.lastPushAt)
    }
}

class SetupMessagesTest {
    @Test
    fun mapsFailuresToPlainMessages() {
        assertEquals("Not a Wrist Usage snapshot", SetupMessages.forFailure(io.github.whyater.wristusage.data.SnapshotException("Missing schema")))
        assertEquals("Couldn't reach it", SetupMessages.forFailure(UnknownHostException("x")))
        assertEquals("Couldn't reach it", SetupMessages.forFailure(java.net.SocketTimeoutException()))
        assertEquals("The server answered HTTP 404", SetupMessages.forFailure(HttpStatusException(404)))
        assertEquals(SetupMessages.NOT_ALLOWED, SetupMessages.forFailure(java.net.UnknownServiceException("Cleartext not permitted")))
        assertEquals(SetupMessages.TLS, SetupMessages.forFailure(javax.net.ssl.SSLHandshakeException("bad cert")))
    }
}
