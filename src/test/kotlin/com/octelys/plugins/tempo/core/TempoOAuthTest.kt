package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoOAuthTest {

    @Test
    fun `authorizeUrl encodes every parameter`() {
        val url = TempoOAuth.authorizeUrl("client 1", "https://acme.atlassian.net/", "s&t")

        assertEquals(
            "https://api.tempo.io/oauth/authorize/redirect" +
                "?client_id=client+1" +
                "&redirect_uri=http%3A%2F%2Flocalhost%3A47821%2Ftempo%2Foauth%2Fcallback" +
                "&jira_url=https%3A%2F%2Facme.atlassian.net" +
                "&state=s%26t",
            url,
        )
    }

    @Test
    fun `parseCallback reads the code and state`() {
        val result = TempoOAuth.parseCallback("code=abc%2F123&state=xyz")

        assertEquals("abc/123", result.code)
        assertEquals("xyz", result.state)
        assertNull(result.error)
    }

    @Test
    fun `parseCallback reports an error with its description`() {
        val result = TempoOAuth.parseCallback("error=access_denied&error_description=User+denied")

        assertNull(result.code)
        assertEquals("access_denied: User denied", result.error)
    }

    @Test
    fun `parseCallback handles an empty query`() {
        val result = TempoOAuth.parseCallback(null)

        assertNull(result.code)
        assertNull(result.state)
        assertNull(result.error)
    }

    @Test
    fun `matches accepts our state or no state, and rejects another one`() {
        assertTrue(TempoOAuth.CallbackResult("c", "ours", null).matches("ours"))
        assertTrue(TempoOAuth.CallbackResult("c", null, null).matches("ours"))
        assertFalse(TempoOAuth.CallbackResult("c", "theirs", null).matches("ours"))
    }

    @Test
    fun `needsRefresh is true within a day of expiry or when expiry is unknown`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 10 * day

        assertFalse(TempoOAuth.needsRefresh(expiresAtMillis = now + 2 * day, nowMillis = now))
        assertTrue(TempoOAuth.needsRefresh(expiresAtMillis = now + day - 1, nowMillis = now))
        assertTrue(TempoOAuth.needsRefresh(expiresAtMillis = now - 1, nowMillis = now))
        assertTrue(TempoOAuth.needsRefresh(expiresAtMillis = 0, nowMillis = now))
    }
}
