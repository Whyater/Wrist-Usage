package io.github.whyater.wristusage.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException

class FetchErrorTest {
    @Test
    fun networkFailuresReadAsOffline() {
        assertEquals("Offline", FetchError.from(UnknownHostException("gist.githubusercontent.com")).short)
        assertEquals("Offline", FetchError.from(SocketTimeoutException()).short)
    }

    @Test
    fun httpStatusIsShownInShortForm() {
        assertEquals("HTTP 404", FetchError.from(HttpStatusException(404)).short)
    }

    @Test
    fun badDataKeepsTheParserMessageForTheApp() {
        val e = FetchError.from(SnapshotException("Unsupported format (schema 2)"))
        assertEquals("Bad data", e.short)
        assertEquals("Unsupported format (schema 2)", e.detail)
    }

    @Test
    fun tlsProblemsAreNamed() {
        assertEquals("TLS error", FetchError.from(SSLHandshakeException("bad cert")).short)
    }

    @Test
    fun badOrBlockedUrlsAreConfigErrorsNotOffline() {
        assertEquals("Config error", FetchError.from(MalformedURLException("no protocol: x")).short)
        assertEquals(
            "Config error",
            FetchError.from(UnknownServiceException("CLEARTEXT communication not permitted")).short,
        )
    }

    @Test
    fun otherIoErrorsStillReadAsOffline() {
        assertEquals("Offline", FetchError.from(java.io.IOException("reset")).short)
    }
}
