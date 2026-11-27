package com.octelys.plugins.tempo.submission

import com.octelys.plugins.tempo.api.AtlassianApiException
import com.octelys.plugins.tempo.api.JiraClient
import com.octelys.plugins.tempo.api.JiraIssue
import com.octelys.plugins.tempo.api.TempoClient
import com.octelys.plugins.tempo.api.TempoWorklog
import com.octelys.plugins.tempo.auth.ConfigurationMissingException
import com.octelys.plugins.tempo.auth.TempoAuth
import com.octelys.plugins.tempo.core.BusySpan
import com.octelys.plugins.tempo.core.Durations
import com.octelys.plugins.tempo.core.PlannedWorklog
import com.octelys.plugins.tempo.core.WorklogPlacement
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
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A worklog in Tempo on one day; [issueKey] is `null` when the issue cannot be looked up. */
data class DayWorklog(
    val startTime: LocalTime,
    val issueKey: String?,
    val seconds: Long,
    val description: String,
    val byPlugin: Boolean,
)

/**
 * A plan in Tempo on one day. [item] is the planned issue key, or a description of what else is
 * planned; [approvalStatus] is `null` when the plan needs no approval.
 */
data class DayPlan(
    val startTime: LocalTime?,
    val item: String?,
    val seconds: Long,
    val description: String,
    val approvalStatus: String?,
)

/** Sends pending tracked time to Tempo, one worklog per issue and day. */
@Service(Service.Level.APP)
class WorklogSubmitter {
    private val running = AtomicBoolean(false)
    private val issueCache = ConcurrentHashMap<String, JiraIssue>()
    private val issueKeysById = ConcurrentHashMap<Long, String>()

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
        val pending = ledger.pending()
        val previouslySubmitted = ledger.submitted()

        // Busy time can only shrink a plan, so when nothing is due there is no need to ask Tempo.
        val draft = WorklogPlanner.plan(pending, previouslySubmitted, settings.roundingMinutes, today, zone)
        if (draft.worklogs.isEmpty()) {
            ledger.removePending(draft.discarded)
            return Outcome(emptyList(), emptyList(), nothingToSubmit = true)
        }

        val jira = jiraClient(settings)
        val tempo = tempoClient(settings)
        val accountId = accountId(settings, jira)

        // Every block in Tempo by id, kept current as worklogs are sent so later ones avoid them too.
        val blocks = loggedBlocks(tempo, accountId, draft.worklogs.map { it.date }, zone)
        val own = previouslySubmitted.mapTo(HashSet()) { it.tempoWorklogId }
        val busy = blocks.filterKeys { it !in own }.values.toList()
        val plan = WorklogPlanner.plan(pending, previouslySubmitted, settings.roundingMinutes, today, zone, busy)
        ledger.removePending(plan.discarded)
        if (plan.worklogs.isEmpty()) return Outcome(emptyList(), emptyList(), nothingToSubmit = true)

        val submitted = mutableListOf<PlannedWorklog>()
        val failures = mutableListOf<String>()
        plan.worklogs.forEachIndexed { index, unplaced ->
            indicator.fraction = index.toDouble() / plan.worklogs.size
            indicator.text = "Logging ${Durations.format(unplaced.roundedSeconds)} on ${unplaced.issueKey}"
            try {
                val others = blocks.filterKeys { it != unplaced.existingWorklogId }.values
                val start = WorklogPlacement.place(
                    unplaced.date, unplaced.startTime, unplaced.roundedSeconds, others, zone,
                    latestEndMillis = System.currentTimeMillis(),
                )
                if (start == null && unplaced.date == today) {
                    // A gap before now may still free up, or the day's later gaps will be past by the next submission.
                    thisLogger().info("No free ${unplaced.roundedSeconds}s before now in Tempo for ${unplaced.issueKey}; kept pending")
                    return@forEachIndexed
                }
                if (start == null) {
                    thisLogger().warn("No free ${unplaced.roundedSeconds}s in Tempo on ${unplaced.date} for ${unplaced.issueKey}; kept pending")
                    failures += "${unplaced.issueKey} (${unplaced.date}): no free ${Durations.format(unplaced.roundedSeconds)} left " +
                        "in Tempo that day without overlapping another worklog. The time stays pending."
                    return@forEachIndexed
                }
                if (start != unplaced.startTime) {
                    thisLogger().info("Moved the ${unplaced.issueKey} worklog on ${unplaced.date} from ${unplaced.startTime} to $start to avoid an overlap")
                }
                val planned = unplaced.copy(startTime = start)

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
                planned.existingWorklogId?.let(blocks::remove)
                blocks[tempoWorklogId] = blockOf(logged.date, logged.startTime, logged.roundedSeconds, zone)
                submitted += logged
            } catch (e: Exception) {
                thisLogger().warn("Tempo submission failed for ${unplaced.issueKey} on ${unplaced.date}", e)
                failures += "${unplaced.issueKey} (${unplaced.date}): ${e.message ?: e}"
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
            val pendingSeconds = planned.pendingSeconds
            val rounded = WorklogPlanner.round(pendingSeconds, TempoSettings.getInstance().roundingMinutes * 60L, isPastDay = true)
                .coerceAtLeast(60)
            val recreated = planned.copy(totalRawSeconds = pendingSeconds, roundedSeconds = rounded, existingWorklogId = null)
            recreated to tempo.create(worklog.copy(timeSpentSeconds = rounded))
        }
    }

    /**
     * Every block already logged in Tempo around [dates], this plugin's included, by Tempo id.
     * Without permission to read worklogs, nothing could be placed safely, so this fails.
     */
    private fun loggedBlocks(
        tempo: TempoClient,
        accountId: String,
        dates: List<LocalDate>,
        zone: ZoneId,
    ): MutableMap<Long, BusySpan> {
        val logged = try {
            // One more day each side, for worklogs running past midnight.
            tempo.worklogsOf(accountId, dates.min().minusDays(1), dates.max().plusDays(1))
        } catch (e: AtlassianApiException) {
            if (e.statusCode != 401 && e.statusCode != 403) throw e
            thisLogger().warn("Cannot read Tempo worklogs, so worklogs cannot be placed without overlaps", e)
            throw ConfigurationMissingException(
                "Allow this connection to read your Tempo worklogs, so new worklogs never overlap existing ones. " +
                    "Your time stays pending until then.",
            )
        }
        return logged.associateTo(HashMap()) { it.tempoWorklogId to blockOf(it.startDate, it.startTime, it.timeSpentSeconds, zone) }
    }

    private fun blockOf(date: LocalDate, start: LocalTime, seconds: Long, zone: ZoneId): BusySpan {
        val from = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        return BusySpan(from, from + seconds * 1000)
    }

    /**
     * Everything logged in Tempo on [date] by the configured account, sent by this plugin or not.
     * Blocks on the network; call it off the EDT.
     */
    fun loggedOn(date: LocalDate): List<DayWorklog> {
        val settings = TempoSettings.getInstance()
        val jira = jiraClient(settings)
        val tempo = tempoClient(settings)
        val accountId = accountId(settings, jira)
        val own = WorklogLedger.getInstance().submitted().associate { it.tempoWorklogId to it.issueKey }

        return tempo.worklogsOf(accountId, date, date)
            .filter { it.startDate == date }
            .map { worklog ->
                val ownKey = own[worklog.tempoWorklogId]
                DayWorklog(
                    startTime = worklog.startTime,
                    issueKey = ownKey ?: worklog.issueId?.let { issueKeyOf(jira, it) },
                    seconds = worklog.timeSpentSeconds,
                    description = worklog.description,
                    byPlugin = ownKey != null,
                )
            }
            .sortedBy { it.startTime }
    }

    /**
     * Every plan of the configured account with time on [date], approved or not.
     * Blocks on the network; call it off the EDT.
     */
    fun plannedOn(date: LocalDate): List<DayPlan> {
        val settings = TempoSettings.getInstance()
        val jira = jiraClient(settings)
        val tempo = tempoClient(settings)
        val accountId = accountId(settings, jira)

        return tempo.plansOf(accountId, date, date)
            .mapNotNull { plan ->
                val seconds = plan.plannedSecondsByDay[date] ?: 0
                if (seconds <= 0) return@mapNotNull null
                DayPlan(
                    startTime = plan.startTime,
                    item = when {
                        plan.itemId == null -> null
                        plan.itemType == "ISSUE" -> plan.itemId.toLongOrNull()?.let { issueKeyOf(jira, it) }
                        else -> "${plan.itemType.orEmpty().lowercase().replaceFirstChar { it.uppercase() }} ${plan.itemId}".trim()
                    },
                    seconds = seconds,
                    description = plan.description,
                    approvalStatus = plan.approvalStatus,
                )
            }
            .sortedWith(compareBy(nullsLast()) { it.startTime })
    }

    /** The key of issue [issueId], or `null` when Jira does not show it to this account. */
    private fun issueKeyOf(jira: JiraClient, issueId: Long): String? =
        issueKeysById[issueId] ?: try {
            jira.issue(issueId.toString()).key.also { issueKeysById[issueId] = it }
        } catch (e: AtlassianApiException) {
            thisLogger().info("Cannot look up Jira issue $issueId (HTTP ${e.statusCode})")
            null
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
        issueKeysById.clear()
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
