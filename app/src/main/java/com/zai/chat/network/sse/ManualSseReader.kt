package com.zai.chat.network.sse

import android.util.Log
import com.zai.chat.network.model.ChatCompletionChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import java.io.IOException

/**
 * Deliberately hand-rolled SSE parser (no library) because we need:
 *  - per-line tolerance (skip malformed lines, never kill the stream),
 *  - access to partial accumulations on mid-stream failure,
 *  - cooperative cancellation between blocking reads.
 *
 * Handles: "data:" payloads, ":" keepalive comments, [DONE], implicit
 * end-of-stream (server closed without [DONE] -> treated as clean Done).
 *
 * IN-STREAM ERROR SURFACING (the "silent empty Done" fix):
 * The completions endpoint reports rejections (FRONTEND_CAPTCHA_REQUIRED,
 * auth errors, model errors) as HTTP 200 bodies containing a JSON object with
 * `code`/`detail` — either bare or wrapped in a `data:` line. The reader used
 * to skip undecodable payloads and non-"data:" lines, so the stream ended
 * clean and empty and the UI said "Empty response from model" while the real
 * cause stayed invisible. Both shapes are now decoded: a payload that parses
 * as an error object (no choices + code/detail present) terminates the stream
 * with [StreamEvent.Error] carrying the backend's own message.
 */
class ManualSseReader(
    private val responseBody: ResponseBody,
    private val json: Json
) {
    fun streamEvents(): Flow<StreamEvent> = flow {
        val source = responseBody.source()
        val content = StringBuilder()
        val reasoning = StringBuilder()

        // Local helpers capture the FlowCollector receiver so they can emit.

        suspend fun emitChunk(chunk: ChatCompletionChunk): Boolean {
            if (chunk.isErrorPayload) {
                Log.w("BleedAI-Sse", "In-stream backend error: ${chunk.errorDescription}")
                emit(
                    StreamEvent.Error(
                        throwable = IOException(chunk.errorDescription),
                        partialContent = content.toString(),
                        partialReasoning = reasoning.toString()
                    )
                )
                return true                                            // terminal — stop reading
            }

            chunk.choices.firstOrNull()?.delta?.let { delta ->
                delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it)
                    emit(StreamEvent.ReasoningDelta(it))
                }
                delta.content?.takeIf { it.isNotEmpty() }?.let {
                    content.append(it)
                    emit(StreamEvent.ContentDelta(it))
                }
            }

            chunk.citations?.takeIf { it.isNotEmpty() }?.let {
                emit(StreamEvent.Citations(it))
            }
            chunk.usage?.let { emit(StreamEvent.Usage(it)) }
            return false
        }

        fun decodeChunk(payload: String): ChatCompletionChunk? = try {
            json.decodeFromString<ChatCompletionChunk>(payload)
        } catch (e: Exception) {
            null                                                      // not a chunk — caller skips it
        }

        try {
            while (currentCoroutineContext().isActive && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                when {
                    line.isBlank() -> continue
                    line.startsWith(":") -> continue                 // SSE comment/keepalive

                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") {
                            emit(StreamEvent.Done)
                            return@flow
                        }
                        if (decodeChunk(payload)?.let { emitChunk(it) } == true) return@flow
                    }

                    else -> {
                        // Bare (non-SSE-prefixed) line. Some error responses
                        // arrive as raw JSON on a 200 stream — recognize them
                        // instead of silently discarding. Anything else
                        // (event:/id:/retry: frames, junk) safely no-ops.
                        if (decodeChunk(line)?.let { emitChunk(it) } == true) return@flow
                    }
                }
            }
            // Server closed the stream without [DONE] — still a clean end.
            emit(StreamEvent.Done)
        } catch (e: CancellationException) {
            throw e                                                   // never swallow cancellation
        } catch (e: IOException) {
            emit(
                StreamEvent.Error(
                    throwable = e,
                    partialContent = content.toString(),
                    partialReasoning = reasoning.toString()
                )
            )
        } finally {
            runCatching { responseBody.close() }
        }
    }.flowOn(Dispatchers.IO)
}
