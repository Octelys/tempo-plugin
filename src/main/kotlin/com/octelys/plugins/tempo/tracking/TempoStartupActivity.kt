package com.octelys.plugins.tempo.tracking

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TempoStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ActivityMonitor.getInstance().start()
        ProjectActivityTracker.getInstance(project).refreshIssue()
    }
}
