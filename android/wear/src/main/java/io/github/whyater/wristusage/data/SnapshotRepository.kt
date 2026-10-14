package io.github.whyater.wristusage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.whyater.wristusage.BuildConfig
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

    /** The phone-provided URL takes precedence when it is non-blank. */
    val effectiveUrl: String
        get() = effectiveSnapshotUrl(prefs.getString(KEY_SNAPSHOT_URL, null), url)

    val isConfigured: Boolean get() = effectiveUrl.isNotBlank()

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

    /** Saves the URL sent by the phone. An empty value clears the runtime override. */
    fun storeSnapshotUrl(url: String): Boolean {
        val normalized = url.trim()
        if (prefs.getString(KEY_SNAPSHOT_URL, null) == normalized) return false
        prefs.edit { putString(KEY_SNAPSHOT_URL, normalized) }
        return true
    }

    /** Validates and stores a snapshot the phone downloaded at [fetchedAt]. Returns true when the cache changed. */
    fun ingest(json: String, fetchedAt: Long): Boolean {
        if (fetchedAt <= 0) return false
        val parsed = runCatching { SnapshotParser.parse(json) }.getOrNull() ?: return false
        return store(json, parsed, Instant.ofEpochMilli(fetchedAt))
    }

    /**
     * Replaces the cache unless it already holds newer data. The phone and the watch can both deliver
     * snapshots, and Data Layer items can arrive late, so the order they land in proves nothing.
     */
    private fun store(raw: String, parsed: Snapshot, fetchedAt: Instant): Boolean = synchronized(LOCK) {
        val currentFetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val current = prefs.getString(KEY_RAW, null)?.let { runCatching { SnapshotParser.parse(it) }.getOrNull() }
        if (!acceptIncomingSnapshot(current, currentFetchedAt, parsed, fetchedAt)) return false
        prefs.edit {
            putString(KEY_RAW, raw)
            putLong(KEY_FETCHED_AT, fetchedAt.toEpochMilli())
            remove(KEY_ERROR_SHORT)
            remove(KEY_ERROR_DETAIL)
        }
        return true
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

    /**
     * Downloads, validates and caches the snapshot. Blocking: call from a background thread.
     * The download runs outside [LOCK], so phone data arriving meanwhile is not held up behind it.
     */
    fun refresh(now: Instant = Instant.now()): UsageState {
        if (!isConfigured) return load(now)
        prefs.edit { putLong(KEY_ATTEMPTED_AT, now.toEpochMilli()) }
        try {
            val raw = SnapshotFetcher.fetch(effectiveUrl, now)
            // Validate before replacing the good copy. Older than the cache is still a working download.
            if (!store(raw, SnapshotParser.parse(raw), now)) {
                prefs.edit { remove(KEY_ERROR_SHORT); remove(KEY_ERROR_DETAIL) }
            }
        } catch (t: Exception) {
            val error = FetchError.from(t)
            prefs.edit {
                putString(KEY_ERROR_SHORT, error.short)
                putString(KEY_ERROR_DETAIL, error.detail)
            }
        }
        return load(now)
    }

    companion object {
        const val PREFS = "usage_cache"
        const val KEY_RAW = "raw_json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_SNAPSHOT_URL = "snapshot_url"
        const val KEY_ERROR_SHORT = "error_short"
        const val KEY_ERROR_DETAIL = "error_detail"
        const val KEY_REFRESHING_UNTIL = "refreshing_until"
        const val KEY_ATTEMPTED_AT = "attempted_at"
        const val KEY_HANDLED_TAP = "handled_tap"
        const val REFRESHING_TTL_MS = 60_000L
        val LOCK = Any()

        /** Resolves the URL at the time it is needed, rather than when work was scheduled. */
        fun effectiveSnapshotUrl(runtimeUrl: String?, buildUrl: String): String =
            runtimeUrl?.trim().takeIf { !it.isNullOrBlank() } ?: buildUrl.trim()

        /** True when setup text should be shown instead of an empty-data message. */
        fun isNotSetUp(state: UsageState): Boolean = !state.configured && state.snapshot == null

        /**
         * Orders snapshots by when the Mac made them, then by when they were downloaded. A collector clock
         * that ran ahead is corrected first, or its snapshot would block every normal one after it.
         */
        fun acceptIncomingSnapshot(
            current: Snapshot?,
            currentFetchedAt: Instant?,
            incoming: Snapshot,
            incomingFetchedAt: Instant,
        ): Boolean {
            if (current == null || currentFetchedAt == null) return true
            val currentMade = current.withSaneClock(currentFetchedAt).generatedAt
            val incomingMade = incoming.withSaneClock(incomingFetchedAt).generatedAt
            return incomingMade.isAfter(currentMade) ||
                (incomingMade == currentMade && incomingFetchedAt.isAfter(currentFetchedAt))
        }
    }
}
