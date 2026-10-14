package io.github.whyater.wristusage.phone.data

import android.content.Context
import androidx.core.content.edit
import io.github.whyater.wristusage.data.Snapshot
import io.github.whyater.wristusage.data.SnapshotException
import io.github.whyater.wristusage.data.SnapshotParser
import java.time.Instant

/** The last good download: raw JSON exactly as served, when it was fetched, and the parsed form for the screen. */
data class Cached(val raw: String, val fetchedAt: Instant, val snapshot: Snapshot) {
    companion object {
        /** Parses [raw]. A collector clock far ahead of [fetchedAt] is replaced by [fetchedAt]. */
        @Throws(SnapshotException::class)
        fun parse(raw: String, fetchedAt: Instant): Cached =
            Cached(raw, fetchedAt, SnapshotParser.parse(raw).withSaneClock(fetchedAt))
    }
}

/** What the phone remembers between launches. Small enough to fake in tests. */
interface PhoneStore {
    fun url(): String?
    fun setUrl(url: String?)
    fun cache(): Cached?
    fun saveCache(raw: String, fetchedAt: Instant)
    fun lastPushAt(): Instant?
    fun setLastPushAt(at: Instant?)
    fun clearCache()
}

class PrefsPhoneStore(context: Context) : PhoneStore {
    private val prefs = context.applicationContext.getSharedPreferences("wristusage_phone", Context.MODE_PRIVATE)

    override fun url(): String? = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    override fun setUrl(url: String?) {
        prefs.edit { if (url == null) remove(KEY_URL) else putString(KEY_URL, url) }
    }

    override fun cache(): Cached? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        val at = prefs.getLong(KEY_FETCHED_AT, 0L).takeIf { it > 0 } ?: return null
        return try {
            Cached.parse(raw, Instant.ofEpochMilli(at))
        } catch (_: SnapshotException) {
            null
        }
    }

    override fun saveCache(raw: String, fetchedAt: Instant) {
        prefs.edit {
            putString(KEY_JSON, raw)
            putLong(KEY_FETCHED_AT, fetchedAt.toEpochMilli())
        }
    }

    override fun lastPushAt(): Instant? =
        prefs.getLong(KEY_LAST_PUSH, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    override fun setLastPushAt(at: Instant?) {
        prefs.edit { if (at == null) remove(KEY_LAST_PUSH) else putLong(KEY_LAST_PUSH, at.toEpochMilli()) }
    }

    override fun clearCache() {
        prefs.edit {
            remove(KEY_JSON)
            remove(KEY_FETCHED_AT)
            remove(KEY_LAST_PUSH)
        }
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_JSON = "json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_LAST_PUSH = "last_push_at"
    }
}
