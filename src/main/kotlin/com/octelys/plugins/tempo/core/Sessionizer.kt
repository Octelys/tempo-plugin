package com.octelys.plugins.tempo.core

/** A closed span of active work on one issue. Times are epoch milliseconds. */
data class WorkSession(val issueKey: String, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * Turns a stream of activity instants into [WorkSession]s.
 *
 * A session stays open while activity keeps arriving on the same issue with gaps shorter than
 * the idle threshold. It ends at the last activity before an idle gap (idle time is never
 * counted), or at the moment the issue changes.
 *
 * Not thread-safe; callers serialize access.
 */
class Sessionizer(private val idleThresholdMillis: () -> Long) {
    private var issueKey: String? = null
    private var startMillis = 0L
    private var lastActivityMillis = 0L

    val currentIssueKey: String? get() = issueKey

    /** Records activity on [activeIssueKey] at [nowMillis]; returns the session this closes, if any. */
    fun onActivity(nowMillis: Long, activeIssueKey: String): WorkSession? {
        val openKey = issueKey
        if (openKey == null) {
            open(activeIssueKey, nowMillis)
            return null
        }

        if (nowMillis - lastActivityMillis > idleThresholdMillis()) {
            val closed = closedAt(lastActivityMillis)
            open(activeIssueKey, nowMillis)
            return closed
        }

        if (openKey != activeIssueKey) {
            val closed = closedAt(nowMillis)
            open(activeIssueKey, nowMillis)
            return closed
        }

        lastActivityMillis = maxOf(lastActivityMillis, nowMillis)
        return null
    }

    /** Closes the open session when no activity happened within the idle threshold. */
    fun closeIfIdle(nowMillis: Long): WorkSession? {
        if (issueKey == null || nowMillis - lastActivityMillis <= idleThresholdMillis()) return null
        return close()
    }

    /** Closes the open session at its last activity. */
    fun close(): WorkSession? {
        if (issueKey == null) return null
        val closed = closedAt(lastActivityMillis)
        issueKey = null
        return closed
    }

    /**
     * Emits the time accumulated so far while keeping the session open, so that pending work can
     * be submitted without waiting for the session to end.
     */
    fun checkpoint(): WorkSession? {
        val openKey = issueKey ?: return null
        val closed = closedAt(lastActivityMillis)
        open(openKey, lastActivityMillis)
        return closed
    }

    private fun open(key: String, atMillis: Long) {
        issueKey = key
        startMillis = atMillis
        lastActivityMillis = atMillis
    }

    private fun closedAt(endMillis: Long): WorkSession? =
        issueKey?.takeIf { endMillis > startMillis }?.let { WorkSession(it, startMillis, endMillis) }
}
