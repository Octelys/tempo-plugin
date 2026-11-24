package com.octelys.plugins.tempo.auth

import com.octelys.plugins.tempo.api.TempoOAuthClient
import com.octelys.plugins.tempo.api.TempoOAuthTokens
import com.octelys.plugins.tempo.core.TempoOAuth
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Logs in to Tempo through the browser: opens Tempo's consent page, catches the redirect on
 * [TempoOAuth.REDIRECT_URI] with a short-lived local server, and trades the code for tokens.
 */
object TempoOAuthLogin {
    private const val TIMEOUT_MILLIS = 5 * 60 * 1000L

    /** Blocks until the user answers in the browser, [indicator] is cancelled, or 5 minutes pass. */
    fun logIn(clientId: String, clientSecret: String?, jiraSiteUrl: String, indicator: ProgressIndicator): TempoOAuthTokens {
        val state = UUID.randomUUID().toString()
        val answer = CompletableFuture<TempoOAuth.CallbackResult>()

        val server = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), TempoOAuth.REDIRECT_PORT), 0)
        } catch (e: BindException) {
            throw IOException(
                "Port ${TempoOAuth.REDIRECT_PORT} is used by another program, so the IDE cannot receive Tempo's answer. " +
                    "Close that program and try again.",
                e,
            )
        }
        server.createContext(TempoOAuth.REDIRECT_PATH) { exchange ->
            val callback = TempoOAuth.parseCallback(exchange.requestURI.rawQuery)
            val accepted = callback.code != null && callback.error == null && callback.matches(state)
            respond(exchange, accepted)
            if (callback.code != null || callback.error != null) answer.complete(callback)
        }
        server.start()

        try {
            BrowserUtil.browse(TempoOAuth.authorizeUrl(clientId, jiraSiteUrl, state))
            val callback = await(answer, indicator)
            callback.error?.let { throw IOException("Tempo did not grant access: $it") }
            if (!callback.matches(state)) throw IOException("The answer from Tempo did not belong to this login. Try again.")
            indicator.text = "Getting the access token from Tempo…"
            return TempoOAuthClient(clientId, clientSecret).exchangeCode(callback.code!!)
        } finally {
            server.stop(0)
        }
    }

    private fun await(answer: CompletableFuture<TempoOAuth.CallbackResult>, indicator: ProgressIndicator): TempoOAuth.CallbackResult {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (true) {
            indicator.checkCanceled()
            if (System.currentTimeMillis() > deadline) throw IOException("No answer from Tempo within 5 minutes. Try again.")
            try {
                return answer.get(200, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Keep waiting, checking for cancellation.
            }
        }
    }

    private fun respond(exchange: HttpExchange, accepted: Boolean) {
        val message = if (accepted) "You are logged in to Tempo. You can close this tab and return to the IDE."
        else "The Tempo login did not complete. Return to the IDE for details."
        val body = """<!doctype html><html><head><meta charset="utf-8"><title>Tempo</title></head>
            |<body style="font-family: sans-serif; margin: 3em">$message</body></html>""".trimMargin()
            .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(if (accepted) 200 else 400, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
