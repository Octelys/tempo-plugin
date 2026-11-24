package com.octelys.plugins.tempo.auth

import com.octelys.plugins.tempo.api.AtlassianApiException
import com.octelys.plugins.tempo.api.TempoOAuthClient
import com.octelys.plugins.tempo.api.TempoOAuthTokens
import com.octelys.plugins.tempo.core.TempoOAuth
import com.octelys.plugins.tempo.settings.TempoAuthMethod
import com.octelys.plugins.tempo.settings.TempoCredentials
import com.octelys.plugins.tempo.settings.TempoSettings
import com.intellij.openapi.diagnostic.thisLogger

/** Something the user has to set up or redo in the settings before the plugin can continue. */
class ConfigurationMissingException(message: String) : Exception(message)

/**
 * Hands out the bearer token for the Tempo API, renewing OAuth tokens as they near expiry.
 * Every call may block on the keychain or the network, so never call it on the EDT.
 */
object TempoAuth {
    private val lock = Any()

    fun bearerToken(
        settings: TempoSettings = TempoSettings.getInstance(),
        method: TempoAuthMethod = settings.tempoAuthMethod,
    ): String = when (method) {
        TempoAuthMethod.API_TOKEN -> TempoCredentials.tempoApiToken
            ?: throw ConfigurationMissingException("Set your Tempo API token, or log in to Tempo, to submit worklogs.")
        TempoAuthMethod.OAUTH -> oauthAccessToken(settings)
    }

    /** Saves a fresh login. Call off the EDT; [TempoSettings] gets the client id and expiry. */
    fun storeLogin(settings: TempoSettings, clientId: String, clientSecret: String?, tokens: TempoOAuthTokens) {
        synchronized(lock) {
            TempoCredentials.tempoOAuthClientSecret = clientSecret
            store(settings, tokens)
            settings.state.tempoOAuthClientId = clientId
            settings.state.tempoAuthMethod = TempoAuthMethod.OAUTH
        }
    }

    /** Revokes the tokens at Tempo when possible, then forgets them. Call off the EDT. */
    fun logOut(settings: TempoSettings) {
        synchronized(lock) {
            val refresh = TempoCredentials.tempoOAuthRefreshToken
            val access = TempoCredentials.tempoOAuthAccessToken
            val secret = TempoCredentials.tempoOAuthClientSecret
            val clientId = settings.tempoOAuthClientId
            if (clientId.isNotEmpty()) {
                val client = TempoOAuthClient(clientId, secret)
                runCatching { refresh?.let { client.revoke(it, "refresh_token") } }
                    .onFailure { thisLogger().info("Could not revoke the Tempo refresh token", it) }
                runCatching { access?.let { client.revoke(it, "access_token") } }
                    .onFailure { thisLogger().info("Could not revoke the Tempo access token", it) }
            }
            clear(settings)
        }
    }

    private fun oauthAccessToken(settings: TempoSettings): String = synchronized(lock) {
        val access = TempoCredentials.tempoOAuthAccessToken
        val refresh = TempoCredentials.tempoOAuthRefreshToken
        val expiresAt = settings.state.tempoOAuthExpiresAt
        val now = System.currentTimeMillis()
        val accessStillValid = access != null && (expiresAt <= 0 || now < expiresAt)

        if (access == null && refresh == null) throw notLoggedIn()
        if (access != null && !TempoOAuth.needsRefresh(expiresAt, now)) return access
        if (refresh == null) return access?.takeIf { accessStillValid } ?: throw loginExpired(settings)

        val clientId = settings.tempoOAuthClientId
        if (clientId.isEmpty()) {
            throw ConfigurationMissingException("Set the Tempo OAuth client ID, then log in to Tempo.")
        }

        val tokens = try {
            TempoOAuthClient(clientId, TempoCredentials.tempoOAuthClientSecret).refresh(refresh)
        } catch (e: AtlassianApiException) {
            // Tempo rejected the refresh token (revoked, expired, or issued to another client).
            if (e.statusCode == 400 || e.statusCode == 401) throw loginExpired(settings)
            if (accessStillValid) return access!!
            throw e
        } catch (e: Exception) {
            if (accessStillValid) return access!!
            throw e
        }
        store(settings, tokens)
        tokens.accessToken
    }

    private fun store(settings: TempoSettings, tokens: TempoOAuthTokens) {
        TempoCredentials.tempoOAuthAccessToken = tokens.accessToken
        TempoCredentials.tempoOAuthRefreshToken = tokens.refreshToken
        settings.state.tempoOAuthExpiresAt = tokens.expiresAtMillis
    }

    private fun clear(settings: TempoSettings) {
        TempoCredentials.tempoOAuthAccessToken = null
        TempoCredentials.tempoOAuthRefreshToken = null
        settings.state.tempoOAuthExpiresAt = 0L
    }

    private fun notLoggedIn() =
        ConfigurationMissingException("Log in to Tempo in Settings › Tools › Tempo to submit worklogs.")

    private fun loginExpired(settings: TempoSettings): ConfigurationMissingException {
        clear(settings)
        return ConfigurationMissingException("Your Tempo login has expired. Log in again in Settings › Tools › Tempo.")
    }
}
