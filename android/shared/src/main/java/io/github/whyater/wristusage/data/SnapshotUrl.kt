package io.github.whyater.wristusage.data

/** Small URL helpers for the snapshot download. */
object SnapshotUrl {
    /** Adds `t=<seconds>` so caches see a new URL. Goes before any #fragment and joins an existing query with `&`. */
    fun withCacheBuster(url: String, epochSecond: Long): String {
        val hash = url.indexOf('#')
        val base = if (hash >= 0) url.substring(0, hash) else url
        val fragment = if (hash >= 0) url.substring(hash) else ""
        val separator = when {
            '?' !in base -> "?"
            base.endsWith('?') || base.endsWith('&') -> ""
            else -> "&"
        }
        return "$base${separator}t=$epochSecond$fragment"
    }
}
