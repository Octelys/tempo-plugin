package com.octelys.plugins.tempo.settings

import com.octelys.plugins.tempo.core.IssueKeys
import com.octelys.plugins.tempo.submission.WorklogSubmitter
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected

class TempoConfigurable(private val project: Project) : BoundConfigurable("Tempo") {
    private val settings = TempoSettings.getInstance().state
    private val projectSettings = TempoProjectSettings.getInstance(project)

    private var jiraToken = ""
    private var tempoToken = ""
    private var defaultIssue = ""

    override fun createPanel(): DialogPanel {
        jiraToken = TempoCredentials.jiraApiToken.orEmpty()
        tempoToken = TempoCredentials.tempoApiToken.orEmpty()
        defaultIssue = projectSettings.defaultIssue.orEmpty()

        lateinit var automatic: JBRadioButton

        return panel {
            group("Jira Cloud") {
                row("Site URL:") {
                    textField().bindText({ settings.jiraSiteUrl.orEmpty() }, { settings.jiraSiteUrl = it.trim() })
                        .align(AlignX.FILL)
                        .comment("For example https://your-domain.atlassian.net")
                }
                row("Email:") {
                    textField().bindText({ settings.jiraEmail.orEmpty() }, { settings.jiraEmail = it.trim() })
                        .align(AlignX.FILL)
                }
                row("API token:") {
                    passwordField().bindText(::jiraToken)
                        .align(AlignX.FILL)
                        .comment("Create one at <a href=\"https://id.atlassian.com/manage-profile/security/api-tokens\">id.atlassian.com</a>. Used to resolve issue keys and your account id.")
                }
            }

            group("Tempo") {
                row("API token:") {
                    passwordField().bindText(::tempoToken)
                        .align(AlignX.FILL)
                        .comment("In Tempo: Settings › API integration › New token.")
                }
                row("API URL:") {
                    textField().bindText({ settings.tempoApiUrl.orEmpty() }, { settings.tempoApiUrl = it.trim() })
                        .align(AlignX.FILL)
                }
            }

            group("Tracking") {
                row("Stop counting after:") {
                    intTextField(1..120).bindIntText(settings::idleThresholdMinutes).columns(4)
                    label("minutes without input")
                }
                row("Round worklogs to:") {
                    intTextField(1..60).bindIntText(settings::roundingMinutes).columns(4)
                    label("minutes")
                }
                row("Description:") {
                    textField().bindText({ settings.descriptionTemplate.orEmpty() }, { settings.descriptionTemplate = it })
                        .align(AlignX.FILL)
                        .comment("Placeholders: {issue}, {summary}")
                }
            }

            group("Submission") {
                buttonsGroup {
                    row {
                        automatic = radioButton("Automatically, every", SubmissionMode.AUTOMATIC).component
                        intTextField(5..24 * 60).bindIntText(settings::autoSubmitIntervalMinutes).columns(4)
                            .enabledIf(automatic.selected)
                        label("minutes")
                    }
                    row {
                        radioButton("On demand, with Tools › Tempo › Submit Worklogs", SubmissionMode.MANUAL)
                    }
                }.bind(settings::submissionMode)
            }

            group("This Project") {
                row("Default issue:") {
                    textField().bindText(::defaultIssue)
                        .columns(12)
                        .comment("Used when the branch name has no issue key and none was set manually.")
                        .validationOnInput { field ->
                            if (field.text.isBlank() || IssueKeys.normalize(field.text) != null) null
                            else error("Expected an issue key such as ABC-123")
                        }
                }
            }
        }
    }

    override fun apply() {
        val before = connectionSnapshot()
        super.apply()

        TempoCredentials.jiraApiToken = jiraToken
        TempoCredentials.tempoApiToken = tempoToken
        projectSettings.defaultIssue = IssueKeys.normalize(defaultIssue)

        // A different Jira site or user means a different account id and issue ids.
        if (connectionSnapshot() != before) WorklogSubmitter.getInstance().resetCaches()
        ProjectActivityTracker.getInstance(project).refreshIssue()
    }

    private fun connectionSnapshot() = settings.jiraSiteUrl.orEmpty() to settings.jiraEmail.orEmpty()
}
