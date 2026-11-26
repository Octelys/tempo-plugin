package com.octelys.plugins.tempo.settings

import com.octelys.plugins.tempo.api.JiraClient
import com.octelys.plugins.tempo.api.TempoClient
import com.octelys.plugins.tempo.api.TempoOAuthTokens
import com.octelys.plugins.tempo.auth.TempoAuth
import com.octelys.plugins.tempo.auth.TempoOAuthLogin
import com.octelys.plugins.tempo.core.IssueKeys
import com.octelys.plugins.tempo.core.TempoOAuth
import com.octelys.plugins.tempo.submission.WorklogSubmitter
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.ui.dsl.builder.Row
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.util.IconUtil
import javax.swing.Icon
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.text
import com.intellij.ui.layout.selected
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JButton

class TempoConfigurable(private val project: Project) : BoundConfigurable("Tempo") {
    private val tempoSettings = TempoSettings.getInstance()
    private val settings = tempoSettings.state
    private val projectSettings = TempoProjectSettings.getInstance(project)

    /**
     * A password-safe value edited in a field. The password safe may block, so these are loaded and
     * saved off the EDT and kept out of the panel bindings. The field stays empty until loaded.
     */
    private class SecretField(val read: () -> String?, val write: (String?) -> Unit) {
        lateinit var field: JBPasswordField
        var stored = ""
        val created get() = this::field.isInitialized
        val value get() = String(field.password).trim()
        val modified get() = created && value != stored
    }

    /** A one-line outcome next to a button: busy, success, or failure (full message in the tooltip). */
    internal class StatusLabel {
        val label = JBLabel()

        fun busy(text: String) = show(AnimatedIcon.Default.INSTANCE, text)
        fun ok(text: String) = show(AllIcons.General.InspectionsOK, text)
        fun warning(text: String) = show(AllIcons.General.Warning, text)
        fun error(text: String) = show(AllIcons.General.Error, text)
        fun plain(text: String) = show(null, text)

        private fun show(icon: javax.swing.Icon?, text: String) {
            label.icon = icon
            label.text = if (text.length > MAX_STATUS_LENGTH) text.take(MAX_STATUS_LENGTH - 1) + "…" else text
            label.toolTipText = text.takeIf { it.length > MAX_STATUS_LENGTH }
        }

        companion object {
            const val MAX_STATUS_LENGTH = 90
        }
    }

    private val jiraToken = SecretField({ TempoCredentials.jiraApiToken }, { TempoCredentials.jiraApiToken = it })
    private val tempoToken = SecretField({ TempoCredentials.tempoApiToken }, { TempoCredentials.tempoApiToken = it })
    private val oauthClientSecret = SecretField({ TempoCredentials.tempoOAuthClientSecret }, { TempoCredentials.tempoOAuthClientSecret = it })
    private val secrets = listOf(jiraToken, tempoToken, oauthClientSecret)

    private lateinit var jiraSiteField: JBTextField
    private lateinit var jiraEmailField: JBTextField
    private lateinit var tempoApiUrlField: JBTextField
    private lateinit var oauthClientIdField: JBTextField
    private lateinit var oauthRadio: JBRadioButton
    private lateinit var logOutButton: JButton
    private val jiraStatus = StatusLabel()
    private val tempoStatus = StatusLabel()
    private val loginStatus = StatusLabel()
    private var defaultIssue = ""
    private var uiDisposable: Disposable? = null

    override fun createPanel(): DialogPanel {
        defaultIssue = projectSettings.defaultIssue.orEmpty()
        val today = TodayOverview(Disposer.newDisposable("Tempo settings").also { uiDisposable = it })

        lateinit var automatic: JBRadioButton
        lateinit var apiTokenRadio: JBRadioButton

        return panel {
            row {
                icon(IconUtil.scale(IconLoader.getIcon("/META-INF/pluginIcon.svg", TempoConfigurable::class.java), null, 0.8f))
                    .gap(RightGap.SMALL)
                panel {
                    row { label("IDE Connector for Tempo").bold() }
                    row { comment("Counts the time you actively spend in the IDE and logs it to Tempo, one worklog per issue and day.") }
                }
            }

            group("Today") {
                row { label("Tracked by this plugin") }
                row { cell(today.trackedTable).align(AlignX.FILL) }
                    .comment("Earlier days are listed while they still have time waiting to be sent.")
                row { cell(today.trackedTotal) }
                row {
                    label("Logged in Tempo")
                    iconButton("Reload from Tempo", AllIcons.Actions.Refresh) { today.refreshTempo() }
                }
                row { cell(today.tempoTable).align(AlignX.FILL) }
                row { cell(today.tempoStatus.label) }
            }

            group("Jira Cloud") {
                row("Site URL:") {
                    jiraSiteField = textField()
                        .bindText({ settings.jiraSiteUrl.orEmpty() }, { settings.jiraSiteUrl = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { emptyText.text = "https://your-domain.atlassian.net" }
                        .validationOnInput { field ->
                            val url = field.text.trim()
                            if (url.isNotEmpty() && !url.startsWith("https://")) warning("Use the https:// address of your Jira site") else null
                        }
                        .component
                    iconButton("Open Jira Site in Browser", AllIcons.General.Web) {
                        jiraSiteField.text.trim().takeIf { it.startsWith("https://") }?.let(BrowserUtil::browse)
                    }
                }
                row("Email:") {
                    jiraEmailField = textField()
                        .bindText({ settings.jiraEmail.orEmpty() }, { settings.jiraEmail = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { emptyText.text = "you@example.com" }
                        .validationOnInput { field ->
                            val email = field.text.trim()
                            if (email.isNotEmpty() && '@' !in email) warning("Enter the email of your Atlassian account") else null
                        }
                        .component
                }
                row("API token:") {
                    jiraToken.field = passwordField()
                        .columns(COLUMNS_LARGE)
                        .comment("<a href=\"https://id.atlassian.com/manage-profile/security/api-tokens\">Create an API token</a> for your Atlassian account.")
                        .component
                }
                row("") {
                    button("Test Connection") { testJira() }.withIcon(AllIcons.Actions.Execute)
                    cell(jiraStatus.label)
                }
            }

            group("Tempo") {
                buttonsGroup {
                    row("Sign in with:") {
                        oauthRadio = radioButton("OAuth 2.0 login", TempoAuthMethod.OAUTH)
                            .comment("Recommended. Renews itself.")
                            .component
                        apiTokenRadio = radioButton("API token", TempoAuthMethod.API_TOKEN)
                            .comment("Expires; replace it by hand.")
                            .component
                    }
                }.bind(settings::tempoAuthMethod)

                rowsRange {
                    row("Client ID:") {
                        oauthClientIdField = textField()
                            .bindText({ settings.tempoOAuthClientId.orEmpty() }, { settings.tempoOAuthClientId = it.trim() })
                            .columns(COLUMNS_LARGE)
                            .comment("Register an application in Tempo › Settings › OAuth 2.0 Applications, then copy its credentials here.")
                            .component
                    }
                    row("Client secret:") {
                        oauthClientSecret.field = passwordField()
                            .columns(COLUMNS_LARGE)
                            .applyToComponent { emptyText.text = "Not needed for a public client" }
                            .component
                    }
                    row("Redirect URL:") {
                        textField().text(TempoOAuth.REDIRECT_URI)
                            .columns(COLUMNS_LARGE)
                            .applyToComponent { isEditable = false }
                            .comment("Enter this exact URL when registering the application.")
                        iconButton("Copy Redirect URL", AllIcons.Actions.Copy) {
                            CopyPasteManager.getInstance().setContents(StringSelection(TempoOAuth.REDIRECT_URI))
                        }
                    }
                    row("") {
                        button("Log In…") { logIn() }.withIcon(AllIcons.General.User)
                        logOutButton = button("Log Out") { logOut() }.withIcon(AllIcons.Actions.Exit).component
                        cell(loginStatus.label)
                    }
                }.visibleIf(oauthRadio.selected)

                row("API token:") {
                    tempoToken.field = passwordField()
                        .columns(COLUMNS_LARGE)
                        .comment("In Tempo: Settings › API integration › New token.")
                        .component
                }.visibleIf(apiTokenRadio.selected)

                row("") {
                    button("Test Connection") { testTempo() }.withIcon(AllIcons.Actions.Execute)
                    cell(tempoStatus.label)
                }

                collapsibleGroup("Advanced") {
                    row("API URL:") {
                        tempoApiUrlField = textField()
                            .bindText({ settings.tempoApiUrl.orEmpty() }, { settings.tempoApiUrl = it.trim() })
                            .columns(COLUMNS_LARGE)
                            .applyToComponent { emptyText.text = TempoSettings.DEFAULT_TEMPO_API_URL }
                            .comment("Change only if your Tempo account uses a regional endpoint.")
                            .component
                    }
                }
            }

            group("Tracking") {
                row("Stop counting after:") {
                    spinner(1..120).bindIntValue(settings::idleThresholdMinutes)
                    label("minutes without input")
                }
                row("Round worklogs to:") {
                    spinner(5..60).bindIntValue(settings::roundingMinutes)
                    label("minutes")
                }
                row("Worklog description:") {
                    textField()
                        .bindText({ settings.descriptionTemplate.orEmpty() }, { settings.descriptionTemplate = it })
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { emptyText.text = TempoSettings.DEFAULT_DESCRIPTION_TEMPLATE }
                        .comment("Placeholders: <code>{issue}</code> for the key, <code>{summary}</code> for the issue title.")
                }
                row("Branch pattern:") {
                    textField()
                        .bindText({ settings.branchIssuePattern.orEmpty() }, { settings.branchIssuePattern = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { emptyText.text = "Any issue key, such as ABC-123" }
                        .comment("For example <code>^(?:features|bugs)/(?&lt;key&gt;[A-Z]+-\\d+)</code>")
                        .validationOnInput { field ->
                            runCatching { IssueKeys.compilePattern(field.text) }
                                .exceptionOrNull()
                                ?.let { error("Invalid regular expression: ${it.message?.lineSequence()?.first()}") }
                        }
                    contextHelp(
                        "A regular expression that finds the issue key in the branch name. The key is the group " +
                            "named <code>key</code>, else group 1, else the whole match. Add <code>(?i)</code> to " +
                            "ignore case. Leave empty to detect any key.",
                        "Branch Pattern",
                    )
                }
            }

            group("Submission") {
                buttonsGroup {
                    row {
                        automatic = radioButton("Automatically, every", SubmissionMode.AUTOMATIC).component
                        spinner(5..24 * 60, 5).bindIntValue(settings::autoSubmitIntervalMinutes)
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
                        .columns(COLUMNS_SHORT)
                        .applyToComponent { emptyText.text = "ABC-123" }
                        .comment("Used when the branch name has no issue key and none was set manually.")
                        .validationOnInput { field ->
                            if (field.text.isBlank() || IssueKeys.normalize(field.text) != null) null
                            else error("Expected an issue key such as ABC-123")
                        }
                }
            }
        }.also {
            updateLoginStatus()
            loadSecrets()
            today.refreshTracked()
            today.refreshTempo()
        }
    }

    override fun disposeUIResources() {
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
        super.disposeUIResources()
    }

    private fun Cell<JButton>.withIcon(icon: Icon): Cell<JButton> = applyToComponent { this.icon = icon }

    /** A small icon-only button with a tooltip, as used next to fields across the IDE settings. */
    private fun Row.iconButton(text: String, icon: Icon, perform: () -> Unit): Cell<ActionButton> {
        val action = object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
        }
        return cell(ActionButton(action, action.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE))
    }

    private fun loadSecrets() {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val values = secrets.map { it.read().orEmpty() }
            application.invokeLater({
                secrets.zip(values).forEach { (secret, value) ->
                    secret.stored = value
                    // Leave alone what the user typed while the secrets were loading.
                    if (secret.field.password.isEmpty()) secret.field.text = value
                }
            }, ModalityState.any())
        }
    }

    /** Runs [check] off the EDT and shows its result, or its failure, in [status]. */
    private fun runCheck(status: StatusLabel, check: () -> String) {
        status.busy("Checking…")
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val result = runCatching(check)
            application.invokeLater({
                result.fold(status::ok) { status.error(it.message ?: it.toString()) }
            }, ModalityState.any())
        }
    }

    private fun testJira() {
        val site = jiraSiteField.text.trim().trimEnd('/')
        val email = jiraEmailField.text.trim()
        val token = jiraToken.value
        if (site.isEmpty() || email.isEmpty() || token.isEmpty()) {
            jiraStatus.warning("Enter the site URL, email and API token first.")
            return
        }
        runCheck(jiraStatus) {
            val user = JiraClient(site, email, token).myself()
            "Connected as ${user.displayName.ifEmpty { email }}"
        }
    }

    private fun testTempo() {
        val apiUrl = tempoApiUrlField.text.trim().trimEnd('/').ifEmpty { TempoSettings.DEFAULT_TEMPO_API_URL }
        val useOAuth = oauthRadio.isSelected
        val token = tempoToken.value
        if (!useOAuth && token.isEmpty()) {
            tempoStatus.warning("Enter the API token first.")
            return
        }
        runCheck(tempoStatus) {
            val bearer = if (useOAuth) TempoAuth.bearerToken(tempoSettings, TempoAuthMethod.OAUTH) else token
            TempoClient(apiUrl, bearer).ping()
            "Connected to Tempo"
        }
    }

    private fun logIn() {
        val clientId = oauthClientIdField.text.trim()
        val clientSecret = oauthClientSecret.value
        val siteUrl = jiraSiteField.text.trim()
        val missing = listOfNotNull(
            "the Jira site URL".takeIf { siteUrl.isEmpty() },
            "the client ID".takeIf { clientId.isEmpty() },
        )
        if (missing.isNotEmpty()) {
            loginStatus.warning("Enter ${missing.joinToString(" and ")} first.")
            return
        }

        object : Task.Modal(project, "Logging in to Tempo", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Waiting for you to allow access in the browser…"
                val tokens: TempoOAuthTokens = TempoOAuthLogin.logIn(clientId, clientSecret, siteUrl, indicator)
                TempoAuth.storeLogin(tempoSettings, clientId, clientSecret, tokens)
            }

            override fun onSuccess() {
                oauthClientSecret.stored = clientSecret
                updateLoginStatus()
            }

            override fun onThrowable(error: Throwable) {
                loginStatus.error(error.message ?: error.toString())
                Messages.showErrorDialog(loginStatus.label, error.message ?: error.toString(), "Could Not Log In to Tempo")
            }
        }.queue()
    }

    private fun logOut() {
        object : Task.Modal(project, "Logging out of Tempo", false) {
            override fun run(indicator: ProgressIndicator) = TempoAuth.logOut(tempoSettings)
            override fun onFinished() = updateLoginStatus()
        }.queue()
    }

    private fun updateLoginStatus() {
        val expiresAt = settings.tempoOAuthExpiresAt
        logOutButton.isEnabled = expiresAt > 0
        if (expiresAt > 0) {
            val until = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.ofEpochMilli(expiresAt).atZone(ZoneId.systemDefault()))
            loginStatus.ok("Logged in · renews automatically (current token until $until)")
        } else {
            loginStatus.plain("Not logged in")
        }
    }

    override fun isModified(): Boolean = super.isModified() || secrets.any { it.modified }

    override fun reset() {
        super.reset()
        secrets.filter { it.created }.forEach { it.field.text = it.stored }
        if (this::logOutButton.isInitialized) updateLoginStatus()
    }

    override fun apply() {
        val before = connectionSnapshot()
        super.apply()

        val changed = secrets.filter { it.modified }.map { it to it.value }
        if (changed.isNotEmpty()) {
            changed.forEach { (secret, value) -> secret.stored = value }
            ApplicationManager.getApplication().executeOnPooledThread {
                changed.forEach { (secret, value) -> secret.write(value) }
            }
        }
        projectSettings.defaultIssue = IssueKeys.normalize(defaultIssue)

        // A different Jira site or user means a different account id and issue ids.
        if (connectionSnapshot() != before) WorklogSubmitter.getInstance().resetCaches()
        ProjectActivityTracker.getInstance(project).refreshIssue()
    }

    private fun connectionSnapshot() = settings.jiraSiteUrl.orEmpty() to settings.jiraEmail.orEmpty()
}
