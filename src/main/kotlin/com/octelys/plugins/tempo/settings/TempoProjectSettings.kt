package com.octelys.plugins.tempo.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per-project, per-user choices of which issue to log time against. Not shared via VCS. */
@Service(Service.Level.PROJECT)
@State(name = "TempoProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TempoProjectSettings : SimplePersistentStateComponent<TempoProjectSettings.State>(State()) {

    class State : BaseState() {
        /** Set explicitly by the user; wins over the branch name. */
        var issueOverride by string()

        /** Used when neither an override nor the branch name gives an issue. */
        var defaultIssue by string()
    }

    var issueOverride: String?
        get() = state.issueOverride?.takeIf { it.isNotBlank() }
        set(value) {
            state.issueOverride = value
        }

    var defaultIssue: String?
        get() = state.defaultIssue?.takeIf { it.isNotBlank() }
        set(value) {
            state.defaultIssue = value
        }

    companion object {
        fun getInstance(project: Project): TempoProjectSettings = project.service()
    }
}
