package com.octelys.plugins.tempo.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class SubmissionMode { AUTOMATIC, MANUAL }

/** IDE-wide settings, shared by every project. Secrets live in [TempoCredentials]. */
@Service(Service.Level.APP)
@State(name = "TempoSettings", storages = [Storage("tempo.xml", roamingType = RoamingType.DISABLED)])
class TempoSettings : SimplePersistentStateComponent<TempoSettings.State>(State()) {

    class State : BaseState() {
        /** For example `https://your-domain.atlassian.net`. */
        var jiraSiteUrl by string("")
        var jiraEmail by string("")
        var tempoApiUrl by string(DEFAULT_TEMPO_API_URL)

        var submissionMode by enum(SubmissionMode.MANUAL)
        var autoSubmitIntervalMinutes by property(30)
        var idleThresholdMinutes by property(5)
        var roundingMinutes by property(1)

        /** Worklog description. Supports `{issue}` and `{summary}`. */
        var descriptionTemplate by string(DEFAULT_DESCRIPTION_TEMPLATE)

        /** Cached Jira account id of [jiraEmail]; cleared whenever the Jira connection changes. */
        var jiraAccountId by string()
    }

    val jiraSiteUrl: String get() = state.jiraSiteUrl.orEmpty().trim().trimEnd('/')
    val jiraEmail: String get() = state.jiraEmail.orEmpty().trim()
    val tempoApiUrl: String get() = state.tempoApiUrl.orEmpty().trim().trimEnd('/').ifEmpty { DEFAULT_TEMPO_API_URL }
    val submissionMode: SubmissionMode get() = state.submissionMode
    val autoSubmitIntervalMinutes: Int get() = state.autoSubmitIntervalMinutes.coerceIn(5, 24 * 60)
    val idleThresholdMillis: Long get() = state.idleThresholdMinutes.coerceIn(1, 120) * 60_000L
    val roundingMinutes: Int get() = state.roundingMinutes.coerceIn(1, 60)
    val descriptionTemplate: String get() = state.descriptionTemplate.orEmpty().ifBlank { DEFAULT_DESCRIPTION_TEMPLATE }

    companion object {
        const val DEFAULT_TEMPO_API_URL = "https://api.tempo.io/4"
        const val DEFAULT_DESCRIPTION_TEMPLATE = "{summary}"

        fun getInstance(): TempoSettings = service()
    }
}
