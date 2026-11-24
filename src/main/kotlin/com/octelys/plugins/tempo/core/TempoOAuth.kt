package com.octelys.plugins.tempo.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Tempo's OAuth 2.0 authorization-code flow, as documented at https://apidocs.tempo.io/
 * (Authentication › Using the REST API as an application developer).
 */
object TempoOAuth {
    const val AUTHORIZE_URL = "https://api.tempo.io/oauth/authorize/redirect"
    const val TOKEN_URL = "https://api.tempo.io/oauth/token/"
    const val REVOKE_URL = "https://api.tempo.io/oauth/revoke_token/"

    /** Local port the login flow listens on; it is part of the redirect URL registered in Tempo. */
    const val REDIRECT_PORT = 47821
    const val REDIRECT_PATH = "/tempo/oauth/callback"
    const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT$REDIRECT_PATH"

    /** Refresh this long before the access token expires, so a submission never races the expiry. */
    const val REFRESH_MARGIN_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * The URL that asks the user to grant access. [state] is not documented by Tempo but is
     * standard OAuth; [CallbackResult.matches] tolerates it being dropped on the way back.
     */
    fun authorizeUrl(clientId: String, jiraSiteUrl: String, state: String, redirectUri: String = REDIRECT_URI): String =
        AUTHORIZE_URL + "?" + formEncode(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "jira_url" to jiraSiteUrl.trim().trimEnd('/'),
            "state" to state,
        )

    /** Encodes pairs as `application/x-www-form-urlencoded`, which the token endpoints also expect. */
    fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

    /** Parses a query string such as `code=abc&state=xyz`; later duplicates win. */
    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val name = part.substringBefore('=')
            val value = if ('=' in part) part.substringAfter('=') else ""
            decode(name) to decode(value)
        }
    }

    /** What the browser brought back to the redirect URL. */
    data class CallbackResult(val code: String?, val state: String?, val error: String?) {
        /** A state that came back must be ours; a missing one is accepted because Tempo does not document it. */
        fun matches(expectedState: String) = state == null || state == expectedState
    }

    fun parseCallback(query: String?): CallbackResult {
        val params = parseQuery(query)
        val error = params["error"]?.let { error ->
            params["error_description"]?.takeIf { it.isNotBlank() }?.let { "$error: $it" } ?: error
        }
        return CallbackResult(params["code"]?.takeIf { it.isNotBlank() }, params["state"], error)
    }

    /** Whether an access token expiring at [expiresAtMillis] should be refreshed now. Unknown expiry (0) means yes. */
    fun needsRefresh(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis <= 0 || nowMillis >= expiresAtMillis - REFRESH_MARGIN_MILLIS

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
}
