package io.github.whyater.wristusage.phone.data

import java.net.URI
import java.net.URISyntaxException

/** Checks what the user typed or pasted in the URL field before anything is downloaded. */
sealed interface UrlInput {
    /** [url] is trimmed and has a lowercase scheme. */
    data class Valid(val url: String) : UrlInput
    data class Invalid(val reason: String) : UrlInput
}

object SnapshotUrlInput {
    const val EMPTY = "Paste the snapshot URL first"
    const val NOT_WEB = "That doesn't look like a web address. It should start with https://"
    const val NOT_HTTPS = "That address isn't allowed. Use an https:// link."

    /** Plain http only reaches a test server on this machine or the emulator host. Debug builds only. */
    private val LOCAL_TEST_HOSTS = setOf("10.0.2.2", "localhost")

    fun normalise(raw: String): UrlInput {
        val text = raw.trim()
        if (text.isEmpty()) return UrlInput.Invalid(EMPTY)
        if (text.any { it.isWhitespace() }) return UrlInput.Invalid(NOT_WEB)

        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return UrlInput.Invalid(NOT_WEB)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return UrlInput.Invalid(NOT_WEB)
        if (uri.host.isNullOrEmpty()) return UrlInput.Invalid(NOT_WEB)
        if (scheme == "http" && uri.host.lowercase() !in LOCAL_TEST_HOSTS) return UrlInput.Invalid(NOT_HTTPS)

        // Only the scheme is rewritten; the rest of the URL (case-sensitive path, query) stays as typed.
        return UrlInput.Valid(scheme + text.substring(uri.scheme.length))
    }
}
