package com.octelys.plugins.tempo.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    }
    messages.joinToString("; ").ifEmpty { body.take(300) }
}.getOrElse { body.take(300) }

data class JiraIssue(val id: Long, val key: String, val summary: String)

/** Jira Cloud REST API v3, authenticated with an Atlassian account email and API token. */
class JiraClient(siteUrl: String, email: String, apiToken: String) {
    private val baseUrl = siteUrl.trimEnd('/')
    private val authorization = "Basic " + Base64.getEncoder()
        .encodeToString("$email:$apiToken".toByteArray(StandardCharsets.UTF_8))

    fun myAccountId(): String =
        get("/rest/api/3/myself").get("accountId").asString

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

/** Tempo Cloud REST API v4, authenticated with a Tempo API token. */
class TempoClient(apiUrl: String, private val apiToken: String) {
    private val baseUrl = apiUrl.trimEnd('/')

    /** Returns the new worklog's `tempoWorklogId`. */
    fun create(worklog: TempoWorklog): Long =
        send(request("/worklogs").POST(body(worklog)), "Tempo").get("tempoWorklogId").asLong

    fun update(tempoWorklogId: Long, worklog: TempoWorklog): Long =
        send(request("/worklogs/$tempoWorklogId").PUT(body(worklog)), "Tempo").get("tempoWorklogId").asLong

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
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
}
