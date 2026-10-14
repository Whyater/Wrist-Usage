package io.github.whyater.wristusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotUrlTest {
    private fun bust(url: String) = SnapshotUrl.withCacheBuster(url, 1760329432)

    @Test fun addsAQueryWhenThereIsNone() {
        assertEquals("https://x.test/usage.json?t=1760329432", bust("https://x.test/usage.json"))
    }

    @Test fun joinsAnExistingQueryWithAmpersand() {
        assertEquals("https://x.test/u.json?a=1&t=1760329432", bust("https://x.test/u.json?a=1"))
    }

    @Test fun goesBeforeTheFragment() {
        assertEquals("https://x.test/u.json?t=1760329432#raw", bust("https://x.test/u.json#raw"))
        assertEquals("https://x.test/u.json?a=1&t=1760329432#raw", bust("https://x.test/u.json?a=1#raw"))
    }

    @Test fun doesNotDoubleUpTheSeparator() {
        assertEquals("https://x.test/u.json?t=1760329432", bust("https://x.test/u.json?"))
        assertEquals("https://x.test/u.json?a=1&t=1760329432", bust("https://x.test/u.json?a=1&"))
    }

    @Test fun aQuestionMarkInsideTheFragmentIsNotAQuery() {
        assertEquals("https://x.test/u.json?t=1760329432#a?b", bust("https://x.test/u.json#a?b"))
    }
}
