package com.octelys.plugins.tempo.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.octelys.plugins.tempo.core.TempoOAuth
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64

class AtlassianApiException(val statusCode: Int, message: String) : Exception(message)

private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        // The IDE installs its own default ProxySelector, so this honours the IDE proxy settings.
        .proxy(ProxySelector.getDefault())
        .connectTimeout(Duration.ofSeconds(15))
        .build()
}

private fun send(request: HttpRequest.Builder, service: String): JsonObject {
    val response = httpClient.send(
        request.timeout(Duration.ofSeconds(30)).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    val body = response.body().orEmpty()
    if (response.statusCode() !in 200..299) {
        throw AtlassianApiException(response.statusCode(), "$service returned HTTP ${response.statusCode()}: ${errorMessageOf(body)}")
    }
    return if (body.isBlank()) JsonObject() else JsonParser.parseString(body).asJsonObject
}

/** Pulls the human-readable part out of Jira/Tempo error payloads. */
private fun errorMessageOf(body: String): String = runCatching {
    val json = JsonParser.parseString(body).asJsonObject
    val messages = buildList {
        json.getAsJsonArray("errors")?.forEach { error ->
            if (error.isJsonObject) error.asJsonObject.get("message")?.asString?.let(::add) else add(error.asString)
        }
        json.getAsJsonArray("errorMessages")?.forEach { add(it.asString) }
        json.getAsJsonObject("errors")?.entrySet()?.forEach { (field, message) -> add("$field: ${message.asString}") }
        json.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
        // OAuth 2.0 errors: {"error": "invalid_grant", "error_description": "..."}
        json.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.let { error ->
            add(json.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString?.let { "$error: $it" } ?: error)
        }
    }
    messages.joinToString("; ").ifEmpty { body.take(300) }
}.getOrElse { body.take(300) }

data class JiraIssue(val id: Long, val key: String, val summary: String)

data class JiraUser(val accountId: String, val displayName: String)

/** Jira Cloud REST API v3, authenticated with an Atlassian account email and API token. */
class JiraClient(siteUrl: String, email: String, apiToken: String) {
    private val baseUrl = siteUrl.trimEnd('/')
    private val authorization = "Basic " + Base64.getEncoder()
        .encodeToString("$email:$apiToken".toByteArray(StandardCharsets.UTF_8))

    fun myAccountId(): String = myself().accountId

    fun myself(): JiraUser {
        val json = get("/rest/api/3/myself")
        return JiraUser(
            accountId = json.get("accountId").asString,
            displayName = json.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
        )
    }

    fun issue(key: String): JiraIssue {
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
        val json = get("/rest/api/3/issue/$encodedKey?fields=summary")
        return JiraIssue(
            id = json.get("id").asString.toLong(),
            key = json.get("key").asString,
            summary = json.getAsJsonObject("fields")?.get("summary")?.asString.orEmpty(),
        )
    }

    private fun get(path: String): JsonObject =
        send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", authorization).GET(), "Jira")
}

data class TempoWorklog(
    val authorAccountId: String,
    val issueId: Long,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val timeSpentSeconds: Long,
    val description: String,
)

/** A worklog read back from Tempo. */
data class TempoLoggedWorklog(
    val tempoWorklogId: Long,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val timeSpentSeconds: Long,
    val issueId: Long? = null,
    val description: String = "",
)

/** Tokens returned by Tempo's OAuth token endpoint. */
data class TempoOAuthTokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

/**
 * Tempo's OAuth 2.0 token endpoints. Tempo documents a [clientSecret] on every call; it is sent
 * only when set, so that a public client can try without one.
 */
class TempoOAuthClient(private val clientId: String, clientSecret: String?) {
    private val clientSecret = clientSecret?.takeIf { it.isNotBlank() }

    fun exchangeCode(code: String, redirectUri: String = TempoOAuth.REDIRECT_URI): TempoOAuthTokens = tokens(
        "grant_type" to "authorization_code",
        "redirect_uri" to redirectUri,
        "code" to code,
    )

    /** Tempo rotates refresh tokens: the returned pair replaces the old one, which stops working. */
    fun refresh(refreshToken: String, redirectUri: String = TempoOAuth.REDIRECT_URI): TempoOAuthTokens = tokens(
        "grant_type" to "refresh_token",
        "redirect_uri" to redirectUri,
        "refresh_token" to refreshToken,
    )

    fun revoke(token: String, tokenTypeHint: String) {
        post(
            TempoOAuth.REVOKE_URL,
            "token_type_hint" to tokenTypeHint,
            "token" to token,
        )
    }

    private fun tokens(vararg form: Pair<String, String>): TempoOAuthTokens {
        val json = post(TempoOAuth.TOKEN_URL, *form)
        val expiresIn = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        return TempoOAuthTokens(
            accessToken = json.get("access_token").asString,
            refreshToken = json.get("refresh_token").asString,
            expiresAtMillis = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
        )
    }

    private fun post(url: String, vararg form: Pair<String, String>): JsonObject {
        val client = listOfNotNull("client_id" to clientId, clientSecret?.let { "client_secret" to it })
        val body = TempoOAuth.formEncode(*(form.toList() + client).toTypedArray())
        return send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
            "Tempo login",
        )
    }
}

/** Tempo Cloud REST API v4, authenticated with a bearer token: an API token or an OAuth access token. */
class TempoClient(apiUrl: String, private val apiToken: String) {
    private val baseUrl = apiUrl.trimEnd('/')

    /** Makes the smallest authenticated read, to check the URL and token. Throws when either is wrong. */
    fun ping() {
        val today = LocalDate.now().toString()
        send(request("/worklogs?from=$today&to=$today&limit=1").GET(), "Tempo")
    }

    /** Returns the new worklog's `tempoWorklogId`. */
    fun create(worklog: TempoWorklog): Long =
        send(request("/worklogs").POST(body(worklog)), "Tempo").get("tempoWorklogId").asLong

    fun update(tempoWorklogId: Long, worklog: TempoWorklog): Long =
        send(request("/worklogs/$tempoWorklogId").PUT(body(worklog)), "Tempo").get("tempoWorklogId").asLong

    /** Every worklog of [accountId] from [from] to [to], both included, following Tempo's pages. */
    fun worklogsOf(accountId: String, from: LocalDate, to: LocalDate): List<TempoLoggedWorklog> {
        val account = URLEncoder.encode(accountId, StandardCharsets.UTF_8)
        var url: String? = "$baseUrl/worklogs/user/$account?from=$from&to=$to&limit=$PAGE_SIZE"
        val worklogs = mutableListOf<TempoLoggedWorklog>()
        while (url != null) {
            val page = send(requestTo(url).GET(), "Tempo")
            page.getAsJsonArray("results")?.forEach { element ->
                val json = element.asJsonObject
                worklogs += TempoLoggedWorklog(
                    tempoWorklogId = json.get("tempoWorklogId").asLong,
                    startDate = LocalDate.parse(json.get("startDate").asString),
                    startTime = json.get("startTime")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT,
                    timeSpentSeconds = json.get("timeSpentSeconds").asLong,
                    issueId = json.get("issue")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("id")?.takeIf { it.isJsonPrimitive }?.asLong,
                    description = json.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
            }
            url = page.getAsJsonObject("metadata")?.get("next")?.takeIf { it.isJsonPrimitive }?.asString
        }
        return worklogs
    }

    private fun request(path: String): HttpRequest.Builder = requestTo(baseUrl + path)

    private fun requestTo(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $apiToken")
            .header("Content-Type", "application/json")

    private fun body(worklog: TempoWorklog): HttpRequest.BodyPublisher {
        val json = JsonObject().apply {
            addProperty("authorAccountId", worklog.authorAccountId)
            addProperty("issueId", worklog.issueId)
            addProperty("startDate", worklog.startDate.toString())
            addProperty("startTime", worklog.startTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
            addProperty("timeSpentSeconds", worklog.timeSpentSeconds)
            addProperty("description", worklog.description)
        }
        return HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PAGE_SIZE = 1000
    }
}
