package com.octelys.plugins.tempo.tracking

import com.octelys.plugins.tempo.core.IssueKeys
import com.octelys.plugins.tempo.settings.TempoProjectSettings
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Supplies the current branch name of a project. Implemented by VCS integrations that are
 * optional at runtime, so the plugin still works in IDEs without them.
 */
interface BranchNameProvider {
    fun currentBranchName(project: Project): String?

    companion object {
        val EP_NAME = ExtensionPointName.create<BranchNameProvider>("com.octelys.plugins.tempo.branchNameProvider")
    }
}

enum class IssueSource(val label: String) {
    OVERRIDE("set manually"),
    BRANCH("from branch"),
    DEFAULT("project default"),
}

data class ResolvedIssue(val key: String, val source: IssueSource)

/** Picks the issue time is logged against: manual override, then branch name, then project default. */
object IssueResolver {
    fun resolve(project: Project): ResolvedIssue? {
        val settings = TempoProjectSettings.getInstance(project)

        IssueKeys.normalize(settings.issueOverride)?.let { return ResolvedIssue(it, IssueSource.OVERRIDE) }

        BranchNameProvider.EP_NAME.extensionList.firstNotNullOfOrNull { provider ->
            IssueKeys.find(runCatching { provider.currentBranchName(project) }.getOrNull())
        }?.let { return ResolvedIssue(it, IssueSource.BRANCH) }

        IssueKeys.normalize(settings.defaultIssue)?.let { return ResolvedIssue(it, IssueSource.DEFAULT) }

        return null
    }
}
