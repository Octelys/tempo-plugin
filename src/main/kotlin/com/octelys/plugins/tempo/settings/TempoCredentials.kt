package com.octelys.plugins.tempo.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Secrets, kept in the IDE password safe (system keychain by default).
 *
 * Reads and writes may block on the keychain, so never call them on the EDT.
 */
object TempoCredentials {
    private val tempoToken = CredentialAttributes(generateServiceName("Tempo", "Tempo API token"))
    private val jiraToken = CredentialAttributes(generateServiceName("Tempo", "Jira API token"))
    private val oauthClientSecret = CredentialAttributes(generateServiceName("Tempo", "Tempo OAuth client secret"))
    private val oauthAccessToken = CredentialAttributes(generateServiceName("Tempo", "Tempo OAuth access token"))
    private val oauthRefreshToken = CredentialAttributes(generateServiceName("Tempo", "Tempo OAuth refresh token"))

    var tempoApiToken: String?
        get() = read(tempoToken)
        set(value) = write(tempoToken, "tempo", value)

    var jiraApiToken: String?
        get() = read(jiraToken)
        set(value) = write(jiraToken, "jira", value)

    var tempoOAuthClientSecret: String?
        get() = read(oauthClientSecret)
        set(value) = write(oauthClientSecret, "tempo-oauth-client", value)

    var tempoOAuthAccessToken: String?
        get() = read(oauthAccessToken)
        set(value) = write(oauthAccessToken, "tempo-oauth", value)

    var tempoOAuthRefreshToken: String?
        get() = read(oauthRefreshToken)
        set(value) = write(oauthRefreshToken, "tempo-oauth", value)

    private fun read(attributes: CredentialAttributes): String? =
        PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    private fun write(attributes: CredentialAttributes, user: String, value: String?) =
        PasswordSafe.instance.set(attributes, value?.takeIf { it.isNotBlank() }?.let { Credentials(user, it) })
}
