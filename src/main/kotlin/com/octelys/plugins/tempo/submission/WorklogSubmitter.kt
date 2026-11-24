package com.octelys.plugins.tempo.submission

import com.octelys.plugins.tempo.api.AtlassianApiException
import com.octelys.plugins.tempo.api.JiraClient
import com.octelys.plugins.tempo.api.JiraIssue
import com.octelys.plugins.tempo.api.TempoClient
import com.octelys.plugins.tempo.api.TempoWorklog
import com.octelys.plugins.tempo.auth.ConfigurationMissingException
import com.octelys.plugins.tempo.auth.TempoAuth
import com.octelys.plugins.tempo.core.Durations
import com.octelys.plugins.tempo.core.PlannedWorklog
import com.octelys.plugins.tempo.core.WorklogPlanner
import com.octelys.plugins.tempo.ledger.WorklogLedger
import com.octelys.plugins.tempo.settings.TempoCredentials
import com.octelys.plugins.tempo.settings.TempoSettings
import com.octelys.plugins.tempo.tracking.ActivityMonitor
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.octelys.plugins.tempo.tracking.TempoListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Sends pending tracked time to Tempo, one worklog per issue and day. */
@Service(Service.Level.APP)
class WorklogSubmitter {
    private val running = AtomicBoolean(false)
    private val issueCache = ConcurrentHashMap<String, JiraIssue>()

    /** Whether the last automatic submission failed; avoids repeating the same error every interval. */
    @Volatile
    private var lastAutomaticFailed = false

    /**
     * Submits in the background. [interactive] submissions report every outcome; automatic ones
     * only report the first failure of a streak.
     */
    fun submit(project: Project?, interactive: Boolean) {
        if (!running.compareAndSet(false, true)) {
            if (interactive) TempoNotifications.info(project, "A Tempo submission is already in progress.")
            return
        }

        ActivityMonitor.openProjects().forEach { ProjectActivityTracker.getInstance(it).checkpoint() }

        object : Task.Backgroundable(project, "Submitting worklogs to Tempo", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val outcome = submitPending(indicator)
                    report(project, interactive, outcome)
                } catch (e: ConfigurationMissingException) {
                    if (interactive || !lastAutomaticFailed) TempoNotifications.configurationMissing(project, e.message!!)
                    if (!interactive) lastAutomaticFailed = true
                } catch (e: Exception) {
                    thisLogger().warn("Tempo submission failed", e)
                    if (interactive || !lastAutomaticFailed) TempoNotifications.error(project, "Tempo submission failed", e.message ?: e.toString())
                    if (!interactive) lastAutomaticFailed = true
                } finally {
                    running.set(false)
                    TempoListener.publish()
                }
            }
        }.queue()
    }

    private class Outcome(val submitted: List<PlannedWorklog>, val failures: List<String>, val nothingToSubmit: Boolean)

    private fun submitPending(indicator: ProgressIndicator): Outcome {
        val settings = TempoSettings.getInstance()
        val ledger = WorklogLedger.getInstance()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        ledger.pruneSubmitted(keepFrom = today.minusDays(SUBMITTED_RETENTION_DAYS))
        val plan = WorklogPlanner.plan(ledger.pending(), ledger.submitted(), settings.roundingMinutes, today, zone)
        ledger.removePending(plan.discarded)
        if (plan.worklogs.isEmpty()) return Outcome(emptyList(), emptyList(), nothingToSubmit = true)

        val jira = jiraClient(settings)
        val tempo = tempoClient(settings)
        val accountId = accountId(settings, jira)

        val submitted = mutableListOf<PlannedWorklog>()
        val failures = mutableListOf<String>()
        plan.worklogs.forEachIndexed { index, planned ->
            indicator.fraction = index.toDouble() / plan.worklogs.size
            indicator.text = "Logging ${Durations.format(planned.roundedSeconds)} on ${planned.issueKey}"
            try {
                val issue = issueCache.getOrPut(planned.issueKey) { jira.issue(planned.issueKey) }
                val worklog = TempoWorklog(
                    authorAccountId = accountId,
                    issueId = issue.id,
                    startDate = planned.date,
                    startTime = planned.startTime,
                    timeSpentSeconds = planned.roundedSeconds,
                    description = describe(settings.descriptionTemplate, issue),
                )
                val (logged, tempoWorklogId) = upsert(tempo, ledger, planned, worklog)
                ledger.markSubmitted(logged, tempoWorklogId)
                submitted += logged
            } catch (e: Exception) {
                thisLogger().warn("Tempo submission failed for ${planned.issueKey} on ${planned.date}", e)
                failures += "${planned.issueKey} (${planned.date}): ${e.message ?: e}"
            }
        }
        return Outcome(submitted, failures, nothingToSubmit = false)
    }

    /**
     * Updates the day's existing worklog, or creates one. Returns what was actually logged with
     * its Tempo id.
     */
    private fun upsert(
        tempo: TempoClient,
        ledger: WorklogLedger,
        planned: PlannedWorklog,
        worklog: TempoWorklog,
    ): Pair<PlannedWorklog, Long> {
        val existingId = planned.existingWorklogId ?: return planned to tempo.create(worklog)
        return try {
            planned to tempo.update(existingId, worklog)
        } catch (e: AtlassianApiException) {
            if (e.statusCode != 404) throw e
            // Deleted in Tempo meanwhile: respect that and log only the time still pending.
            ledger.forgetSubmitted(planned.issueKey, planned.date)
            val pendingSeconds = planned.sessions.sumOf { it.durationMillis } / 1000
            val rounded = WorklogPlanner.round(pendingSeconds, TempoSettings.getInstance().roundingMinutes * 60L, isPastDay = true)
                .coerceAtLeast(60)
            val recreated = planned.copy(totalRawSeconds = pendingSeconds, roundedSeconds = rounded, existingWorklogId = null)
            recreated to tempo.create(worklog.copy(timeSpentSeconds = rounded))
        }
    }

    private fun report(project: Project?, interactive: Boolean, outcome: Outcome) {
        if (outcome.failures.isNotEmpty()) {
            if (interactive || !lastAutomaticFailed) {
                TempoNotifications.error(project, "Some worklogs were not sent to Tempo", outcome.failures.joinToString("<br/>"))
            }
            if (!interactive) lastAutomaticFailed = true
            return
        }

        lastAutomaticFailed = false
        if (!interactive) return
        if (outcome.nothingToSubmit) {
            TempoNotifications.info(project, "Nothing to submit yet: tracked time still rounds to zero.")
        } else {
            val lines = outcome.submitted.joinToString("<br/>") { "${it.issueKey} · ${it.date}: ${Durations.format(it.roundedSeconds)}" }
            TempoNotifications.info(project, "Worklogs sent to Tempo:<br/>$lines")
        }
    }

    private fun jiraClient(settings: TempoSettings): JiraClient {
        val token = TempoCredentials.jiraApiToken
        if (settings.jiraSiteUrl.isEmpty() || settings.jiraEmail.isEmpty() || token == null) {
            throw ConfigurationMissingException("Set your Jira site URL, email and API token to submit worklogs.")
        }
        return JiraClient(settings.jiraSiteUrl, settings.jiraEmail, token)
    }

    private fun tempoClient(settings: TempoSettings): TempoClient =
        TempoClient(settings.tempoApiUrl, TempoAuth.bearerToken(settings))

    private fun accountId(settings: TempoSettings, jira: JiraClient): String =
        settings.state.jiraAccountId?.takeIf { it.isNotBlank() }
            ?: jira.myAccountId().also { settings.state.jiraAccountId = it }

    /** Forgets cached Jira lookups, for example after the connection settings changed. */
    fun resetCaches() {
        issueCache.clear()
        TempoSettings.getInstance().state.jiraAccountId = null
    }

    companion object {
        /** Worklogs older than this are never updated again; new time on them would be a new day anyway. */
        private const val SUBMITTED_RETENTION_DAYS = 7L

        fun getInstance(): WorklogSubmitter = service()

        fun describe(template: String, issue: JiraIssue): String =
            template.replace("{issue}", issue.key).replace("{summary}", issue.summary).trim().ifEmpty { issue.key }
    }
}
