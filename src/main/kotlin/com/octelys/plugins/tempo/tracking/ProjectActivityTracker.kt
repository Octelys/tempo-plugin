package com.octelys.plugins.tempo.tracking

import com.octelys.plugins.tempo.core.Sessionizer
import com.octelys.plugins.tempo.core.WorkSession
import com.octelys.plugins.tempo.ledger.WorklogLedger
import com.octelys.plugins.tempo.settings.TempoSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Fired whenever tracked or pending time changes, so UI can refresh. */
fun interface TempoListener {
    fun changed()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic(TempoListener::class.java, Topic.BroadcastDirection.NONE)

        fun publish() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
    }
}

/** Accumulates active time in one project into sessions, and hands closed ones to the ledger. */
@Service(Service.Level.PROJECT)
class ProjectActivityTracker(private val project: Project) : Disposable {
    private val sessionizer = Sessionizer { TempoSettings.getInstance().idleThresholdMillis }

    @Volatile
    var currentIssue: ResolvedIssue? = null
        private set

    fun recordActivity(nowMillis: Long) {
        val issue = IssueResolver.resolve(project)
        val issueChanged = issue != currentIssue
        currentIssue = issue

        synchronized(sessionizer) {
            if (issue == null) sessionizer.close() else sessionizer.onActivity(nowMillis, issue.key)
        }?.let(::store)

        if (issueChanged) TempoListener.publish()
    }

    fun closeIfIdle(nowMillis: Long) {
        synchronized(sessionizer) { sessionizer.closeIfIdle(nowMillis) }?.let(::store)
    }

    /** Moves time tracked so far into the ledger without ending the session. */
    fun checkpoint() {
        synchronized(sessionizer) { sessionizer.checkpoint() }?.let(::store)
    }

    /** Ends the current session, for example after the issue was changed by the user. */
    fun close() {
        synchronized(sessionizer) { sessionizer.close() }?.let(::store)
    }

    /** Re-resolves the issue after a settings change, closing the session on the previous one. */
    fun refreshIssue() {
        val issue = IssueResolver.resolve(project)
        if (issue?.key != currentIssue?.key) close()
        currentIssue = issue
        TempoListener.publish()
    }

    /** Counts the open session's time so far into today's total, so a restart does not lose it. */
    fun countOpenSession() {
        val open = synchronized(sessionizer) { sessionizer.openSession } ?: return
        if (!project.isDisposed) ProjectTimeToday.getInstance(project).add(open)
    }

    /** Active time in this project today, including the session still open. */
    fun millisToday(): Long {
        val open = synchronized(sessionizer) { sessionizer.openSession }
        return ProjectTimeToday.getInstance(project).millis(open)
    }

    override fun dispose() = close()

    private fun store(session: WorkSession) {
        WorklogLedger.getInstance().add(session)
        // On project close the session still reaches the ledger, but project services may be gone.
        if (!project.isDisposed) ProjectTimeToday.getInstance(project).add(session)
    }

    companion object {
        fun getInstance(project: Project): ProjectActivityTracker = project.service()
    }
}
