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
    }

    @Tag("session")
    class SessionEntry {
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

    // A copy, so the platform can serialize it while tracking keeps mutating the live state.
    override fun getState(): LedgerState = synchronized(lock) {
        LedgerState().also { copy ->
            copy.pending = state.pending.toMutableList()
            copy.submitted = state.submitted.toMutableList()
        }
    }

    override fun loadState(loaded: LedgerState) = synchronized(lock) { state = loaded }

    fun add(session: WorkSession) = synchronized(lock) {
        state.pending += SessionEntry().apply {
            issueKey = session.issueKey
            startMillis = session.startMillis
            endMillis = session.endMillis
        }
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
