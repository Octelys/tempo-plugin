package com.octelys.plugins.tempo.submission

import com.octelys.plugins.tempo.settings.TempoConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

object TempoNotifications {
    private const val GROUP_ID = "Tempo"
    private const val TITLE = "Tempo"

    fun info(project: Project?, content: String) = notify(project, TITLE, content, NotificationType.INFORMATION)

    fun error(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.ERROR)

    fun configurationMissing(project: Project?, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(TITLE, content, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TempoConfigurable::class.java)
            })
            .notify(project)
    }

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
