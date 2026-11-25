package com.octelys.plugins.tempo.ledger

import com.octelys.plugins.tempo.core.PlannedWorklog
import com.octelys.plugins.tempo.core.SubmittedWorklog
import com.octelys.plugins.tempo.core.WorkSession
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.time.LocalDate
import java.time.LocalTime

/**
 * Durable record of tracked time: sessions not yet in Tempo, and recent worklogs already sent so
 * later time on the same issue and day updates them. Survives IDE restarts, so nothing is lost
 * when the IDE closes before a submission.
 */
@Service(Service.Level.APP)
@State(name = "TempoWorklogLedger", storages = [Storage("tempo-worklogs.xml", roamingType = RoamingType.DISABLED)])
class WorklogLedger : PersistentStateComponent<WorklogLedger.LedgerState> {

    @Tag("ledger")
    class LedgerState {
        @get:XCollection(style = XCollection.Style.v2)
        var pending: MutableList<SessionEntry> = mutableListOf()

        @get:XCollection(style = XCollection.Style.v2)
        var submitted: MutableList<SubmittedEntry> = mutableListOf()

        @get:XCollection(style = XCollection.Style.v2)
        var open: MutableList<OpenEntry> = mutableListOf()
    }

    @Tag("session")
    class SessionEntry {
        var issueKey: String = ""
        var startMillis: Long = 0
        var endMillis: Long = 0
    }

    /** A project's session still being tracked, up to its last activity when it was recorded. */
    @Tag("open")
    class OpenEntry {
        var projectId: String = ""
        var issueKey: String = ""
        var startMillis: Long = 0
        var endMillis: Long = 0
    }

    @Tag("worklog")
    class SubmittedEntry {
        var issueKey: String = ""
        var date: String = ""
        var startTime: String = ""
        var tempoWorklogId: Long = 0
        var rawSeconds: Long = 0
    }

    private val lock = Any()
    private var state = LedgerState()
    private var initialized = false

    // A copy, so the platform can serialize it while tracking keeps mutating the live state.
    override fun getState(): LedgerState = synchronized(lock) {
        LedgerState().also { copy ->
            copy.pending = state.pending.toMutableList()
            copy.submitted = state.submitted.toMutableList()
            copy.open = state.open.toMutableList()
        }
    }

    override fun loadState(loaded: LedgerState) = synchronized(lock) {
        if (initialized) {
            // A reload while running: the live open sessions are newer than the file's.
            loaded.open = state.open
        } else {
            // Open sessions in the file were left by an IDE that did not shut down cleanly.
            loaded.open.forEach { loaded.pending += sessionEntry(it.issueKey, it.startMillis, it.endMillis) }
            loaded.open.clear()
        }
        state = loaded
    }

    override fun initializeComponent() = synchronized(lock) { initialized = true }

    /**
     * Adds [closed] to the pending sessions and replaces the open session of [projectId] with
     * [open], in one step so a save never sees the same time both pending and open.
     */
    fun record(projectId: String?, closed: WorkSession?, open: WorkSession?) = synchronized(lock) {
        closed?.let { state.pending += sessionEntry(it.issueKey, it.startMillis, it.endMillis) }
        if (projectId == null) return@synchronized

        state.open.removeAll { it.projectId == projectId }
        open?.let {
            state.open += OpenEntry().apply {
                this.projectId = projectId
                issueKey = it.issueKey
                startMillis = it.startMillis
                endMillis = it.endMillis
            }
        }
    }

    fun open(): List<WorkSession> = synchronized(lock) {
        state.open.map { WorkSession(it.issueKey, it.startMillis, it.endMillis) }
    }

    private fun sessionEntry(key: String, start: Long, end: Long) = SessionEntry().apply {
        issueKey = key
        startMillis = start
        endMillis = end
    }

    fun pending(): List<WorkSession> = synchronized(lock) {
        state.pending.map { WorkSession(it.issueKey, it.startMillis, it.endMillis) }
    }

    fun submitted(): List<SubmittedWorklog> = synchronized(lock) {
        state.submitted.mapNotNull { entry ->
            runCatching {
                SubmittedWorklog(
                    issueKey = entry.issueKey,
                    date = LocalDate.parse(entry.date),
                    startTime = LocalTime.parse(entry.startTime),
                    tempoWorklogId = entry.tempoWorklogId,
                    rawSeconds = entry.rawSeconds,
                )
            }.getOrNull()
        }
    }

    /** Moves the planned sessions from pending to the submitted record of [tempoWorklogId]. */
    fun markSubmitted(worklog: PlannedWorklog, tempoWorklogId: Long) = synchronized(lock) {
        removePending(worklog.sessions)
        state.submitted.removeAll { it.issueKey == worklog.issueKey && it.date == worklog.date.toString() }
        state.submitted += SubmittedEntry().apply {
            issueKey = worklog.issueKey
            date = worklog.date.toString()
            startTime = worklog.startTime.toString()
            this.tempoWorklogId = tempoWorklogId
            rawSeconds = worklog.totalRawSeconds
        }
    }

    /** Forgets a submitted worklog, for example after it was deleted in Tempo. */
    fun forgetSubmitted(issueKey: String, date: LocalDate) = synchronized(lock) {
        state.submitted.removeAll { it.issueKey == issueKey && it.date == date.toString() }
    }

    fun removePending(sessions: Collection<WorkSession>) = synchronized(lock) {
        val keys = sessions.mapTo(HashSet()) { Triple(it.issueKey, it.startMillis, it.endMillis) }
        state.pending.removeAll { Triple(it.issueKey, it.startMillis, it.endMillis) in keys }
    }

    fun clearPending() = synchronized(lock) { state.pending.clear() }

    /** Drops submitted records older than [keepFrom]; they will not be updated anymore. */
    fun pruneSubmitted(keepFrom: LocalDate) = synchronized(lock) {
        state.submitted.removeAll { entry ->
            runCatching { LocalDate.parse(entry.date).isBefore(keepFrom) }.getOrDefault(true)
        }
    }

    companion object {
        fun getInstance(): WorklogLedger = service()
    }
}
