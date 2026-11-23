package com.octelys.plugins.tempo.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API tokens, kept in the IDE password safe (system keychain by default).
 *
 * Reads may block on the keychain, so prefer calling them off the EDT.
 */
object TempoCredentials {
    private val tempoToken = CredentialAttributes(generateServiceName("Tempo", "Tempo API token"))
    private val jiraToken = CredentialAttributes(generateServiceName("Tempo", "Jira API token"))

    var tempoApiToken: String?
        get() = PasswordSafe.instance.getPassword(tempoToken)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.set(tempoToken, value?.takeIf { it.isNotBlank() }?.let { Credentials("tempo", it) })

    var jiraApiToken: String?
        get() = PasswordSafe.instance.getPassword(jiraToken)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.set(jiraToken, value?.takeIf { it.isNotBlank() }?.let { Credentials("jira", it) })
}
