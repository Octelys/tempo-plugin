package com.octelys.plugins.tempo.tracking

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import git4idea.repo.GitRepositoryManager

/** Registered only when the Git plugin is enabled (see `tempo-withGit.xml`). */
class GitBranchNameProvider : BranchNameProvider {
    override fun currentBranchName(project: Project): String? {
        val manager = GitRepositoryManager.getInstance(project)
        val repository = project.guessProjectDir()?.let(manager::getRepositoryForRootQuick)
            ?: manager.repositories.firstOrNull()
        return repository?.currentBranchName
    }
}
