package com.octelys.plugins.tempo.tracking

import com.octelys.plugins.tempo.core.DailyTotal
import com.octelys.plugins.tempo.core.WorkSession
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Active time spent in this project today, across all issues and whether or not it was sent to
 * Tempo. Kept per project because the ledger records sessions per issue only. Persisted in the
 * workspace file so a restart during the day keeps the total.
 */
@Service(Service.Level.PROJECT)
@State(name = "TempoProjectTimeToday", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectTimeToday : SimplePersistentStateComponent<ProjectTimeToday.State>(State()) {

    class State : BaseState() {
        var date by string()
        var millis by property(0L)
        var countedUntilMillis by property(0L)
    }

    private val lock = Any()

    /**
     * Counts the part of [session] not counted yet. Called for closed sessions and periodically for
     * the open one, so a restart loses at most the time since the last call.
     */
    fun add(session: WorkSession, zone: ZoneId = ZoneId.systemDefault()) = synchronized(lock) {
        val updated = current().plus(session, LocalDate.now(zone), zone)
        state.date = updated.date.toString()
        state.millis = updated.millis
        state.countedUntilMillis = updated.countedUntilMillis
    }

    /** Today's total including [openSession], the part of the current session not yet stored. */
    fun millis(openSession: WorkSession?, zone: ZoneId = ZoneId.systemDefault()): Long = synchronized(lock) {
        current().millisOn(LocalDate.now(zone), openSession, zone)
    }

    private fun current(): DailyTotal {
        val date = state.date ?: return DailyTotal(LocalDate.MIN, 0)
        return try {
            DailyTotal(LocalDate.parse(date), state.millis.coerceAtLeast(0), state.countedUntilMillis)
        } catch (e: DateTimeParseException) {
            thisLogger().warn("Ignoring an unreadable stored date for today's project time", e)
            DailyTotal(LocalDate.MIN, 0)
        }
    }

    companion object {
        fun getInstance(project: Project): ProjectTimeToday = project.service()
    }
}
