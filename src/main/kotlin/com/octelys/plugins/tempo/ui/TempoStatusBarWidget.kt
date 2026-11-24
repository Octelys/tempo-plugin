package com.octelys.plugins.tempo.ui

import com.octelys.plugins.tempo.core.Durations
import com.octelys.plugins.tempo.ledger.WorklogLedger
import com.octelys.plugins.tempo.tracking.IssueResolver
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.octelys.plugins.tempo.tracking.TempoListener
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

class TempoStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = TempoStatusBarWidget.ID
    override fun getDisplayName() = "Tempo"
    override fun createWidget(project: Project): StatusBarWidget = TempoStatusBarWidget(project)
}

/**
 * Shows the issue time is going to, the time spent in this project today, and the time not yet
 * sent to Tempo; click for actions.
 */
class TempoStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID() = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TempoListener.TOPIC,
            TempoListener { ApplicationManager.getApplication().invokeLater({ statusBar.updateWidget(ID) }, project.disposed) },
        )
    }

    override fun getPresentation() = this

    override fun getText(): String {
        val issue = IssueResolver.resolve(project)?.key ?: "no issue"
        val todaySeconds = ProjectActivityTracker.getInstance(project).millisToday() / 1000
        val pendingSeconds = WorklogLedger.getInstance().pending().sumOf { it.durationMillis } / 1000
        val pending = if (pendingSeconds >= 30) " · ${Durations.format(pendingSeconds)} pending" else ""
        return "Tempo: $issue · ${Durations.format(todaySeconds)} today$pending"
    }

    override fun getTooltipText(): String {
        val today = Durations.format(ProjectActivityTracker.getInstance(project).millisToday() / 1000)
        val spent = "$today spent in ${project.name} today."
        val issue = IssueResolver.resolve(project)
            ?: return "No issue to log time against. Click to set one, or check out a branch named after an issue. $spent"
        return "Logging time on ${issue.key} (${issue.source.label}). $spent Click for Tempo actions."
    }

    override fun getAlignment() = Component.LEFT_ALIGNMENT

    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        val group = ActionManager.getInstance().getAction(ACTION_GROUP_ID) as? ActionGroup ?: return@Consumer
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Tempo",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        // Open upwards, since the status bar sits at the bottom of the window.
        popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "TempoStatusBarWidget"
        const val ACTION_GROUP_ID = "Tempo.Actions"
    }
}
