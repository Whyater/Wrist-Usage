package io.github.whyater.wristusage.data

import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/** A failed fetch. [short] fits on the tile footer, [detail] goes in the app. */
data class FetchError(val short: String, val detail: String) {
    companion object {
        const val OFFLINE = "Offline"
        const val CONFIG_ERROR = "Config error"

        fun from(t: Throwable): FetchError = when (t) {
            is HttpStatusException -> FetchError("HTTP ${t.code}", "Server answered HTTP ${t.code}")
            is SnapshotException -> FetchError("Bad data", t.message ?: "Could not read the snapshot")
            // A bad or blocked URL is a build setting problem, not a network problem. These are IOExceptions, so they go first.
            is MalformedURLException,
            is UnknownServiceException -> FetchError(CONFIG_ERROR, "The snapshot URL is invalid or not allowed (HTTPS only)")
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketTimeoutException -> FetchError(OFFLINE, "No connection to the snapshot server")
            is SSLException -> FetchError("TLS error", "Secure connection failed")
            is IOException -> FetchError(OFFLINE, t.message ?: "Network error")
            else -> FetchError("Error", t.message ?: t.javaClass.simpleName)
        }
    }
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")
