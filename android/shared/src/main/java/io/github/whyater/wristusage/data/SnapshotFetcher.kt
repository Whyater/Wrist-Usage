package io.github.whyater.wristusage.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.time.Instant

/** Plain HTTP download of the snapshot JSON. No caching or parsing: callers validate the result. */
object SnapshotFetcher {
    const val TIMEOUT_MS = 10_000
    /** Real snapshots are about 1 KB. This keeps them well under the Data Layer's 100 KB item limit. */
    const val MAX_BYTES = 64 * 1024

    /**
     * Downloads [baseUrl] (with a cache buster derived from [now]) and returns the raw body.
     * Blocking: call from a background thread. Throws [MalformedURLException] for non-http(s) URLs,
     * [HttpStatusException] for non-200 answers and [SnapshotException] when the body is too large.
     */
    @Throws(Exception::class)
    fun fetch(baseUrl: String, now: Instant = Instant.now()): String {
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
}
