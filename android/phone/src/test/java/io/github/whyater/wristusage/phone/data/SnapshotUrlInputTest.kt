package io.github.whyater.wristusage.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotUrlInputTest {
    private fun valid(raw: String) = (SnapshotUrlInput.normalise(raw) as UrlInput.Valid).url

    private fun assertInvalid(raw: String) {
        assertTrue("'$raw' should be rejected", SnapshotUrlInput.normalise(raw) is UrlInput.Invalid)
    }

    @Test
    fun trimsSurroundingWhitespaceAndNewlines() {
        assertEquals("https://example.com/usage.json", valid("  https://example.com/usage.json \n"))
    }

    @Test
    fun acceptsHttpAndHttps() {
        assertEquals("http://10.0.2.2:8766/usage.json", valid("http://10.0.2.2:8766/usage.json"))
        assertEquals("https://gist.githubusercontent.com/a/b/raw/usage.json", valid("https://gist.githubusercontent.com/a/b/raw/usage.json"))
    }

    @Test
    fun rejectsPlainHttpExceptForLocalTestServers() {
        assertEquals(UrlInput.Invalid(SnapshotUrlInput.NOT_HTTPS), SnapshotUrlInput.normalise("http://gist.githubusercontent.com/a/raw/usage.json"))
        assertEquals("http://localhost:8765/usage.json", valid("http://localhost:8765/usage.json"))
    }

    @Test
    fun lowercasesOnlyTheScheme() {
        assertEquals("https://Example.com/Path/Usage.JSON?Key=Ab", valid("HTTPS://Example.com/Path/Usage.JSON?Key=Ab"))
    }

    @Test
    fun keepsQueryAndFragmentAsTyped() {
        assertEquals("https://x.io/u.json?a=1&b=2#top", valid("https://x.io/u.json?a=1&b=2#top"))
    }

    @Test
    fun rejectsEmptyAndBlank() {
        assertEquals(UrlInput.Invalid(SnapshotUrlInput.EMPTY), SnapshotUrlInput.normalise(""))
        assertEquals(UrlInput.Invalid(SnapshotUrlInput.EMPTY), SnapshotUrlInput.normalise("   \n"))
    }

    @Test
    fun rejectsOtherSchemesAndMissingScheme() {
        assertInvalid("ftp://example.com/usage.json")
        assertInvalid("file:///sdcard/usage.json")
        assertInvalid("javascript:alert(1)")
        assertInvalid("example.com/usage.json")
        assertInvalid("//example.com/usage.json")
    }

    @Test
    fun rejectsMissingHostAndInnerWhitespace() {
        assertInvalid("https://")
        assertInvalid("https:///usage.json")
        assertInvalid("https://exa mple.com/usage.json")
        assertInvalid("https://example.com/a b.json")
    }
}
