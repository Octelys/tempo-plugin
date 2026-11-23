package com.octelys.plugins.tempo.actions

import com.octelys.plugins.tempo.core.Durations
import com.octelys.plugins.tempo.core.IssueKeys
import com.octelys.plugins.tempo.ledger.WorklogLedger
import com.octelys.plugins.tempo.settings.TempoConfigurable
import com.octelys.plugins.tempo.settings.TempoProjectSettings
import com.octelys.plugins.tempo.submission.WorklogSubmitter
import com.octelys.plugins.tempo.tracking.ActivityMonitor
import com.octelys.plugins.tempo.tracking.IssueResolver
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.octelys.plugins.tempo.tracking.TempoListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

class SubmitWorklogsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) =
        WorklogSubmitter.getInstance().submit(e.project, interactive = true)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class SetIssueAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = TempoProjectSettings.getInstance(project)

        val input = Messages.showInputDialog(
            project,
            "Issue to log time against in this project.\nLeave empty to follow the branch name again.",
            "Set Tempo Issue",
            null,
            settings.issueOverride ?: IssueResolver.resolve(project)?.key.orEmpty(),
            object : InputValidator {
                override fun checkInput(text: String?) = text.isNullOrBlank() || IssueKeys.normalize(text) != null
                override fun canClose(text: String?) = checkInput(text)
            },
        ) ?: return

        settings.issueOverride = IssueKeys.normalize(input)
        ProjectActivityTracker.getInstance(project).refreshIssue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class DiscardPendingAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ActivityMonitor.openProjects().forEach { ProjectActivityTracker.getInstance(it).checkpoint() }
        val ledger = WorklogLedger.getInstance()
        val pendingSeconds = ledger.pending().sumOf { it.durationMillis } / 1000
        if (pendingSeconds == 0L) {
            Messages.showInfoMessage(e.project, "There is no pending time.", "Tempo")
            return
        }

        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Discard ${Durations.format(pendingSeconds)} of tracked time that was not sent to Tempo yet?",
            "Discard Pending Time",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (confirmed) {
            ledger.clearPending()
            TempoListener.publish()
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class OpenSettingsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) =
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, TempoConfigurable::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
