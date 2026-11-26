package com.octelys.plugins.tempo.settings

import com.octelys.plugins.tempo.core.IssueKeys
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class SubmissionMode { AUTOMATIC, MANUAL }

/** How the plugin authenticates against the Tempo API. */
enum class TempoAuthMethod { OAUTH, API_TOKEN }

/** IDE-wide settings, shared by every project. Secrets live in [TempoCredentials]. */
@Service(Service.Level.APP)
@State(name = "TempoSettings", storages = [Storage("tempo.xml", roamingType = RoamingType.DISABLED)])
class TempoSettings : SimplePersistentStateComponent<TempoSettings.State>(State()) {

    class State : BaseState() {
        /** For example `https://your-domain.atlassian.net`. */
        var jiraSiteUrl by string("")
        var jiraEmail by string("")
        var tempoApiUrl by string(DEFAULT_TEMPO_API_URL)

        /** Existing installs keep using their API token until they switch. */
        var tempoAuthMethod by enum(TempoAuthMethod.API_TOKEN)

        /** Client ID of the OAuth 2.0 application registered in Tempo; its secret is in [TempoCredentials]. */
        var tempoOAuthClientId by string("")

        /** When the stored OAuth access token expires, in epoch milliseconds; 0 when unknown or logged out. */
        var tempoOAuthExpiresAt by property(0L)

        var submissionMode by enum(SubmissionMode.AUTOMATIC)
        var autoSubmitIntervalMinutes by property(5)
        var idleThresholdMinutes by property(5)
        var roundingMinutes by property(5)

        /** Worklog description. Supports `{issue}` and `{summary}`. */
        var descriptionTemplate by string(DEFAULT_DESCRIPTION_TEMPLATE)

        /** Regular expression that finds the issue key in a branch name; blank uses the built-in detection. */
        var branchIssuePattern by string("")

        /** Cached Jira account id of [jiraEmail]; cleared whenever the Jira connection changes. */
        var jiraAccountId by string()
    }

    val jiraSiteUrl: String get() = state.jiraSiteUrl.orEmpty().trim().trimEnd('/')
    val jiraEmail: String get() = state.jiraEmail.orEmpty().trim()
    val tempoApiUrl: String get() = state.tempoApiUrl.orEmpty().trim().trimEnd('/').ifEmpty { DEFAULT_TEMPO_API_URL }
    val tempoAuthMethod: TempoAuthMethod get() = state.tempoAuthMethod
    val tempoOAuthClientId: String get() = state.tempoOAuthClientId.orEmpty().trim()
    val submissionMode: SubmissionMode get() = state.submissionMode
    val autoSubmitIntervalMinutes: Int get() = state.autoSubmitIntervalMinutes.coerceIn(5, 24 * 60)
    val idleThresholdMillis: Long get() = state.idleThresholdMinutes.coerceIn(1, 120) * 60_000L
    val roundingMinutes: Int get() = state.roundingMinutes.coerceIn(1, 60)
    val descriptionTemplate: String get() = state.descriptionTemplate.orEmpty().ifBlank { DEFAULT_DESCRIPTION_TEMPLATE }

    /** The compiled [State.branchIssuePattern], or `null` for the built-in detection (also when it is invalid). */
    val branchIssuePattern: Regex? get() = runCatching { IssueKeys.compilePattern(state.branchIssuePattern) }.getOrNull()

    companion object {
        const val DEFAULT_TEMPO_API_URL = "https://api.tempo.io/4"
        const val DEFAULT_DESCRIPTION_TEMPLATE = "{summary}"

        fun getInstance(): TempoSettings = service()
    }
}
