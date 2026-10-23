package com.zai.chat.network.transport

import android.util.Log
import android.webkit.CookieManager
import com.zai.chat.config.ZaiConfig
import com.zai.chat.data.local.preferences.JwtParser
import com.zai.chat.data.local.preferences.TokenManager
import com.zai.chat.network.FeVersionResolver
import com.zai.chat.network.auth.AuthEventManager
import com.zai.chat.network.model.ChatCompletionRequest
import com.zai.chat.network.sse.ManualSseReader
import com.zai.chat.network.sse.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Component 4: High-Performance Native SSE Transport.
 * Streams chat completions directly from the chat.z.ai HTTP/2 endpoint using OkHttp and coroutines.
 * Connects with single-use `captcha_verify_param` minted via `CaptchaTokenPool` and features
 * automatic single-turn self-healing retry on `FRONTEND_CAPTCHA_REQUIRED` — the retry also
 * force-refreshes the runtime `X-FE-Version` so a mid-session z.ai frontend rotation is
 * recovered within one round-trip instead of bricking completions until the next app update.
 */
@Singleton
class NativeCompletionTransport @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenPool: CaptchaTokenPool,
    private val tokenManager: TokenManager,
    private val requestSigner: RequestSigner,
    private val authEventManager: AuthEventManager,
    private val feVersionResolver: FeVersionResolver,
    private val json: Json
) : CompletionTransport {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun stream(request: ChatCompletionRequest): Flow<StreamEvent> =
        streamInternal(request, isRetry = false)

    private fun streamInternal(
        request: ChatCompletionRequest,
        isRetry: Boolean
    ): Flow<StreamEvent> = flow {
        val token = tokenManager.getStoredToken()
        if (token.isNullOrBlank()) {
            Log.e("BleedAI-NativeTransport", "No active session token configured")
            emit(StreamEvent.Error(IOException("No active session token configured"), "", ""))
            return@flow
        }

        val claims = JwtParser.parse(token)
        val userId = claims?.subject ?: "user"
        val promptText = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty()

        // Captcha mint FIRST, signature LAST: the mint can block for many
        // seconds (on-demand risk round-trip). Signing before the wait used
        // to dispatch requests with a 48s-stale signature_timestamp — the
        // timestamp must be generated as close to dispatch as possible.
        val ticket = tokenPool.acquireToken()
        Log.d("BleedAI-NativeTransport", "Acquired captcha ticket (len=${ticket.length}): ${ticket.take(16)}...")

        val signed = requestSigner.sign(
            prompt = promptText,
            userId = userId,
            token = token
        )

        val targetUrl = "${ZaiConfig.BASE_URL}${ZaiConfig.COMPLETIONS_PATH}?${signed.queryParams}&signature_timestamp=${signed.timestamp}"
        val cookies = CookieManager.getInstance().getCookie(ZaiConfig.BASE_URL).orEmpty()

        val bodyObj = buildJsonObject {
            put("stream", true)
            put("model", request.model.ifBlank { ZaiConfig.MODEL_DEFAULT })
            put("messages", json.encodeToJsonElement(request.messages))
            putJsonObject("params") {}
            putJsonObject("features") {
                put("image_generation", false)
                put("web_search", request.webSearch)
                put("auto_web_search", false)
                put("preview_mode", false)
                put("enable_thinking", request.reasoning)
            }
            putJsonObject("variables") {}
            put("chat_id", request.chatId)
            put("captcha_verify_param", ticket)
            request.fileIds?.let { put("files", json.encodeToJsonElement(it)) }
        }

        val bodyJson = json.encodeToString(bodyObj)
        Log.d("BleedAI-NativeTransport", "Sending HTTP/2 SSE request to $targetUrl")

        val httpRequest = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US")
            .header("X-FE-Version", feVersionResolver.current())
            .header("X-Signature", signed.signature)
            .apply {
                if (cookies.isNotBlank()) {
                    header("Cookie", cookies)
                }
            }
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            Log.d("BleedAI-NativeTransport", "Coroutine cancelled -> cancelling in-flight OkHttp call")
            call.cancel()
        }

        try {
            val response = call.execute()
            response.use { resp ->
                Log.d("BleedAI-NativeTransport", "HTTP Response: ${resp.code} ${resp.message}")

                // z.ai answers dead bearer tokens with 403 "Not authenticated";
                // 401 kept for upstream Open WebUI parity. Both route the user
                // to the reconnect screen instead of looping silent failures.
                if (resp.code == 401 || resp.code == 403) {
                    Log.w("BleedAI-NativeTransport", "HTTP ${resp.code} -> emitting token expired event")
                    authEventManager.emitTokenExpired()
                    emit(
                        StreamEvent.Error(
                            throwable = IOException("Session expired (HTTP ${resp.code})"),
                            partialContent = "",
                            partialReasoning = ""
                        )
                    )
                    return@flow
                }

                if (!resp.isSuccessful) {
                    val errSnippet = resp.body?.string().orEmpty().take(400)
                    Log.w("BleedAI-NativeTransport", "HTTP Error ${resp.code}: $errSnippet")

                    // Self-healing retry on FRONTEND_CAPTCHA_REQUIRED: fresh ticket
                    // AND a forced FE-version re-discovery, since a rotated frontend
                    // is a prime cause of captcha-gate rejection.
                    if (errSnippet.contains("CAPTCHA") && !isRetry) {
                        Log.i("BleedAI-NativeTransport", "Captcha challenge triggered -> retrying with fresh ticket + FE version refresh...")
                        feVersionResolver.refreshNow()
                        streamInternal(request, isRetry = true).collect { emit(it) }
                        return@flow
                    }

                    emit(
                        StreamEvent.Error(
                            throwable = IOException("HTTP ${resp.code}: $errSnippet"),
                            partialContent = "",
                            partialReasoning = ""
                        )
                    )
                    return@flow
                }

                val responseBody = resp.body
                if (responseBody == null) {
                    emit(StreamEvent.Error(IOException("Empty response body from completions endpoint"), "", ""))
                    return@flow
                }

                // Stream SSE chunks directly via ManualSseReader. In-stream
                // rejections (HTTP 200 + nested error JSON: captcha gate,
                // "Model not available for current user level", ...) now
                // surface as Error events — same self-healing captcha retry
                // applies to captcha rejections.
                var retryForCaptcha = false
                ManualSseReader(responseBody, json).streamEvents().collect { event ->
                    val isCaptchaRejection = event is StreamEvent.Error &&
                        event.throwable.message?.contains("CAPTCHA", ignoreCase = true) == true
                    if (isCaptchaRejection && !isRetry) {
                        Log.i("BleedAI-NativeTransport", "In-stream captcha rejection -> will retry with fresh ticket + FE version refresh")
                        retryForCaptcha = true
                    } else {
                        emit(event)
                    }
                }
                if (retryForCaptcha) {
                    feVersionResolver.refreshNow()
                    streamInternal(request, isRetry = true).collect { emit(it) }
                    return@flow
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e("BleedAI-NativeTransport", "IO exception during completion stream: ${e.message}")
            emit(StreamEvent.Error(e, "", ""))
        }
    }.flowOn(Dispatchers.IO)
}
