package io.github.whyater.wristusage.phone.sync

import io.github.whyater.wristusage.data.FetchError
import io.github.whyater.wristusage.data.HttpStatusException
import io.github.whyater.wristusage.data.SnapshotException
import io.github.whyater.wristusage.data.SnapshotFetcher
import io.github.whyater.wristusage.phone.data.Cached
import io.github.whyater.wristusage.phone.data.PhoneStore
import io.github.whyater.wristusage.phone.data.SnapshotUrlInput
import io.github.whyater.wristusage.phone.data.UrlInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.MalformedURLException
import java.net.UnknownServiceException
import java.time.Instant
import javax.net.ssl.SSLException

/** What the screens show. Shared by the UI, the periodic worker and the watch refresh requests. */
data class SyncState(
    val url: String?,
    val cached: Cached?,
    /** When a snapshot was last put on the Data Layer successfully. */
    val lastPushAt: Instant?,
    /** The most recent failed fetch, cleared by the next success. The cache is kept either way. */
    val lastError: FetchError?,
    val refreshing: Boolean,
)

sealed interface RefreshResult {
    data object NoUrl : RefreshResult

    /** [pushed] is false when the fetch worked but the Data Layer could not be reached. */
    data class Success(val cached: Cached, val pushed: Boolean) : RefreshResult

    data class Failure(val error: FetchError) : RefreshResult
}

sealed interface SaveResult {
    data class Saved(val cached: Cached, val pushed: Boolean) : SaveResult

    /** Nothing was saved. [message] is ready to show under the URL field. */
    data class Rejected(val message: String) : SaveResult
}

/**
 * The fetch-and-push logic, free of Android UI so it can be tested with a fake [WatchLink] and [PhoneStore].
 * One instance per process: the mutex keeps a screen refresh and a background job from overlapping.
 */
class UsageSync(
    private val store: PhoneStore,
    private val link: WatchLink,
    private val download: (url: String, now: Instant) -> String = SnapshotFetcher::fetch,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        SyncState(store.url(), store.cache(), store.lastPushAt(), lastError = null, refreshing = false),
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Fetches the saved URL, keeps the result as the new cache and pushes it to the watch. */
    suspend fun refresh(): RefreshResult = mutex.withLock {
        val url = store.url() ?: return RefreshResult.NoUrl
        _state.update { it.copy(refreshing = true) }
        try {
            val now = clock()
            val cached = fetchAndParse(url, now)
            store.saveCache(cached.raw, now)
            _state.update { it.copy(cached = cached, lastError = null) }
            RefreshResult.Success(cached, pushSnapshot(cached))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cache stays exactly as it was: only the error is recorded.
            val error = FetchError.from(e)
            _state.update { it.copy(lastError = error) }
            RefreshResult.Failure(error)
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /**
     * Downloads and parses [input] first. Only a working URL is saved; then the watch is told about
     * it and gets the first snapshot.
     */
    suspend fun saveUrl(input: String): SaveResult {
        val url = when (val checked = SnapshotUrlInput.normalise(input)) {
            is UrlInput.Invalid -> return SaveResult.Rejected(checked.reason)
            is UrlInput.Valid -> checked.url
        }
        return mutex.withLock {
            val now = clock()
            val cached = try {
                fetchAndParse(url, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SaveResult.Rejected(SetupMessages.forFailure(e))
            }
            store.setUrl(url)
            store.saveCache(cached.raw, now)
            _state.update { it.copy(url = url, cached = cached, lastError = null) }
            safely { link.putConfig(url) }
            SaveResult.Saved(cached, pushSnapshot(cached))
        }
    }

    /** Forgets the URL and the cache, and tells the watch the URL is gone. */
    suspend fun clearUrl() {
        mutex.withLock {
            store.setUrl(null)
            store.clearCache()
            _state.update { SyncState(url = null, cached = null, lastPushAt = null, lastError = null, refreshing = false) }
            safely { link.putConfig("") }
        }
    }

    /**
     * Called when the app starts so a watch app installed later still learns the URL. With no URL it
     * sends "", which also delivers a Clear that could not reach the watch at the time.
     */
    suspend fun pushConfig() {
        val url = store.url() ?: ""
        safely { link.putConfig(url) }
    }

    /** A Data Layer that never answers (no Wear OS app, no Play services) reads as unavailable. */
    suspend fun watchStatus(): WatchStatus =
        withTimeoutOrNull(LINK_TIMEOUT_MS) { link.watchStatus() } ?: WatchStatus.Unavailable

    private suspend fun fetchAndParse(url: String, now: Instant): Cached {
        val raw = withContext(io) { download(url, now) }
        return Cached.parse(raw, now)
    }

    private suspend fun pushSnapshot(cached: Cached): Boolean {
        val ok = safely { link.putSnapshot(cached.raw, cached.fetchedAt.toEpochMilli()) }
        if (ok) {
            val at = clock()
            store.setLastPushAt(at)
            _state.update { it.copy(lastPushAt = at) }
        }
        return ok
    }

    /** The Data Layer may be missing or busy. That must never fail a fetch or crash the app. */
    private suspend fun safely(block: suspend () -> Unit): Boolean = try {
        withTimeoutOrNull(LINK_TIMEOUT_MS) { block() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/** Without this, a missing Wear OS app can make Save wait many seconds for a Data Layer that will never answer. */
private const val LINK_TIMEOUT_MS = 4_000L

/** Messages for the setup screen. Plain words for what went wrong while checking a URL. */
object SetupMessages {
    const val NOT_SNAPSHOT = "Not a Wrist Usage snapshot"
    const val UNREACHABLE = "Couldn't reach it"
    const val NOT_ALLOWED = "That address isn't allowed. Use an https:// link."
    const val TLS = "Couldn't make a secure connection"

    fun forFailure(t: Throwable): String = when (t) {
        is SnapshotException -> NOT_SNAPSHOT
        is HttpStatusException -> "The server answered HTTP ${t.code}"
        is MalformedURLException,
        is UnknownServiceException -> NOT_ALLOWED
        is SSLException -> TLS
        is IOException -> UNREACHABLE
        else -> UNREACHABLE
    }
}
