package io.github.whyater.wristusage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.whyater.wristusage.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant

/** What the UI surfaces need: the last good snapshot, when it was downloaded, and the last failure if any. */
data class UsageState(
    val configured: Boolean,
    val snapshot: Snapshot?,
    /** When the cached snapshot was downloaded. */
    val fetchedAt: Instant?,
    /** Set when the most recent fetch failed. [snapshot] still holds the last good data. */
    val error: FetchError?,
    val refreshing: Boolean = false,
    /** When the last download was tried, whether it worked or not. Drives the retry backoff. */
    val attemptedAt: Instant? = null,
)

/**
 * Downloads the snapshot and keeps the last good copy in SharedPreferences,
 * so every surface can render instantly and keep showing data while offline.
 */
class SnapshotRepository(
    context: Context,
    private val url: String = BuildConfig.SNAPSHOT_URL,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = url.isNotBlank()

    /** Reads the cache only. Never touches the network. */
    fun load(now: Instant = Instant.now()): UsageState {
        val raw = prefs.getString(KEY_RAW, null)
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val snapshot = raw?.let { runCatching { SnapshotParser.parse(it) }.getOrNull() }
            ?.let { if (fetchedAt != null) it.withSaneClock(fetchedAt) else it }
        val errorShort = prefs.getString(KEY_ERROR_SHORT, null)
        val error = errorShort?.let { FetchError(it, prefs.getString(KEY_ERROR_DETAIL, null) ?: it) }
        return UsageState(
            configured = isConfigured,
            snapshot = snapshot,
            fetchedAt = if (snapshot != null) fetchedAt else null,
            error = error,
            refreshing = prefs.getLong(KEY_REFRESHING_UNTIL, 0L) > now.toEpochMilli(),
            attemptedAt = prefs.getLong(KEY_ATTEMPTED_AT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
    }

    /** Emits the current state, then a new one each time the cache changes (for example when the worker writes). */
    fun changes(): Flow<UsageState> = callbackFlow {
        // SharedPreferences only keeps a weak reference, so the listener lives in this scope until the flow closes.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { load() }.flowOn(Dispatchers.IO)

    /**
     * True the first time a refresh tap [id] is seen. The tile keeps reporting the last clicked id on later
     * requests, so without this every redraw would look like a new tap.
     */
    fun consumeTap(id: String?): Boolean {
        if (id == null || prefs.getString(KEY_HANDLED_TAP, null) == id) return false
        prefs.edit { putString(KEY_HANDLED_TAP, id) }
        return true
    }

    /** Marks a refresh as running so the tile can show "Refreshing". Expires on its own if the worker dies. */
    fun setRefreshing(active: Boolean, now: Instant = Instant.now()) {
        val until = if (active) now.toEpochMilli() + REFRESHING_TTL_MS else 0L
        prefs.edit { putLong(KEY_REFRESHING_UNTIL, until) }
    }

    /** Downloads, validates and caches the snapshot. Blocking: call from a background thread. */
    fun refresh(now: Instant = Instant.now()): UsageState {
        if (!isConfigured) return load(now)
        synchronized(LOCK) {
            prefs.edit { putLong(KEY_ATTEMPTED_AT, now.toEpochMilli()) }
            try {
                val raw = download(url, now)
                SnapshotParser.parse(raw) // validate before replacing the good copy
                prefs.edit {
                    putString(KEY_RAW, raw)
                    putLong(KEY_FETCHED_AT, now.toEpochMilli())
                    remove(KEY_ERROR_SHORT)
                    remove(KEY_ERROR_DETAIL)
                    putLong(KEY_REFRESHING_UNTIL, 0L)
                }
            } catch (t: Exception) {
                val error = FetchError.from(t)
                prefs.edit {
                    putString(KEY_ERROR_SHORT, error.short)
                    putString(KEY_ERROR_DETAIL, error.detail)
                    putLong(KEY_REFRESHING_UNTIL, 0L)
                }
            }
        }
        return load(now)
    }

    private fun download(baseUrl: String, now: Instant): String {
        val url = URL(SnapshotUrl.withCacheBuster(baseUrl, now.epochSecond))
        val conn = url.openConnection() as? HttpURLConnection
            ?: throw MalformedURLException("Snapshot URL must start with http or https")
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code)
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw SnapshotException("Response too large")
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val PREFS = "usage_cache"
        const val KEY_RAW = "raw_json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_ERROR_SHORT = "error_short"
        const val KEY_ERROR_DETAIL = "error_detail"
        const val KEY_REFRESHING_UNTIL = "refreshing_until"
        const val KEY_ATTEMPTED_AT = "attempted_at"
        const val KEY_HANDLED_TAP = "handled_tap"
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 256 * 1024
        const val REFRESHING_TTL_MS = 30_000L
        val LOCK = Any()
    }
}
