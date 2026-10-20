package com.soundtube.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SapisidHashGeneratorTest {

    @Test
    fun generate_withKnownTestVector_returnsExactSha1Hash() {
        val sapisid = "test_sapisid_123"
        val timestamp = 1700000000L
        val origin = "https://www.youtube.com"

        val expected = "SAPISIDHASH 1700000000_95ea8d4021ed9169f869d7ff0ff0f6f25eea1c4a"
        val actual = SapisidHashGenerator.generate(
            sapisid = sapisid,
            origin = origin,
            timestampSeconds = timestamp
        )

        assertEquals(expected, actual)
    }

    @Test
    fun generate_withSecondKnownVector_returnsExpectedHash() {
        val sapisid = "another_secret"
        val timestamp = 1690000000L
        val origin = "https://www.youtube.com"

        val expected = "SAPISIDHASH 1690000000_325d586362bf321ac733123c1349f1727a88909c"
        val actual = SapisidHashGenerator.generate(
            sapisid = sapisid,
            origin = origin,
            timestampSeconds = timestamp
        )

        assertEquals(expected, actual)
    }

    @Test
    fun generateFromCookieHeader_extractsSapisidAndGeneratesHash() {
        val cookieHeader = "VISITOR_INFO1_LIVE=xyz; SAPISID=another_secret; LOGIN_INFO=abc; SID=123"
        val timestamp = 1690000000L

        val result = SapisidHashGenerator.generateFromCookieHeader(
            cookieHeader = cookieHeader,
            timestampSeconds = timestamp
        )

        assertNotNull(result)
        assertEquals("SAPISIDHASH 1690000000_325d586362bf321ac733123c1349f1727a88909c", result)
    }

    @Test
    fun generateFromCookieHeader_supportsSecure3PAPISID() {
        val cookieHeader = "PREF=f1; __Secure-3PAPISID=another_secret; HSID=def"
        val timestamp = 1690000000L

        val result = SapisidHashGenerator.generateFromCookieHeader(
            cookieHeader = cookieHeader,
            timestampSeconds = timestamp
        )

        assertNotNull(result)
        assertTrue(result!!.contains("SAPISIDHASH 1690000000_325d586362bf321ac733123c1349f1727a88909c"))
    }

    @Test
    fun generateFromCookieHeader_returnsNullWhenSapisidMissing() {
        val cookieHeader = "VISITOR_INFO1_LIVE=xyz; LOGIN_INFO=abc; SID=123"
        val result = SapisidHashGenerator.generateFromCookieHeader(cookieHeader)
        assertNull(result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_withBlankSapisid_throwsException() {
        SapisidHashGenerator.generate("")
    }

    @Test
    fun generate_defaultTimestamp_isRecent() {
        val sapisid = "valid_token"
        val result = SapisidHashGenerator.generate(sapisid)
        assertTrue(result.startsWith("SAPISIDHASH "))
        val parts = result.removePrefix("SAPISIDHASH ").split("_")
        assertEquals(2, parts.size)
        val ts = parts[0].toLong()
        assertTrue(ts > 1700000000L)
        assertEquals(40, parts[1].length)
    }
}
