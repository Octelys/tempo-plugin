package com.octelys.plugins.tempo.tracking

import com.octelys.plugins.tempo.settings.SubmissionMode
import com.octelys.plugins.tempo.settings.TempoSettings
import com.octelys.plugins.tempo.submission.WorklogSubmitter
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Watches user input across the IDE and credits it to the project whose window has focus.
 * Also drives the periodic idle check and automatic submissions.
 */
@Service(Service.Level.APP)
class ActivityMonitor : Disposable {
    @Volatile
    private var lastRecordedMillis = 0L

    // Zero, so the first tick after startup submits what earlier IDE sessions left pending.
    @Volatile
    private var lastAutoSubmitMillis = 0L

    private var tick: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (tick != null) return

        IdeEventQueue.getInstance().addActivityListener(::onUserActivity, this)

        tick = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::onTick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
    }

    // Called on the EDT for every input event; throttled since a second is plenty of resolution.
    private fun onUserActivity() {
        val now = System.currentTimeMillis()
        if (now - lastRecordedMillis < THROTTLE_MILLIS) return
        lastRecordedMillis = now

        val project = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project ?: return
        if (project.isDisposed || project.isDefault) return
        ProjectActivityTracker.getInstance(project).recordActivity(now)
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        openProjects().forEach { project ->
            val tracker = ProjectActivityTracker.getInstance(project)
            tracker.closeIfIdle(now)
            tracker.persistOpenSession()
        }

        val settings = TempoSettings.getInstance()
        if (settings.submissionMode == SubmissionMode.AUTOMATIC &&
            now - lastAutoSubmitMillis >= settings.autoSubmitIntervalMinutes * 60_000L
        ) {
            lastAutoSubmitMillis = now
            WorklogSubmitter.getInstance().submit(project = null, interactive = false)
        }

        TempoListener.publish()
    }

    @Synchronized
    override fun dispose() {
        tick?.cancel(false)
        tick = null
    }

    companion object {
        private const val THROTTLE_MILLIS = 1_000L
        private const val TICK_SECONDS = 30L

        fun getInstance(): ActivityMonitor = service()

        fun openProjects() = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
    }
}
