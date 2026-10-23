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
 * IN-STREAM ERROR SURFACING + V2 PROTOCOL (the "silent empty Done" fix):
 * The completions endpoint reports rejections (FRONTEND_CAPTCHA_REQUIRED,
 * "Model not available for current user level", auth errors) as HTTP 200
 * bodies containing an error object NESTED in the payload (error /
 * data.error / data.data.error — live-verified shapes). It also streams
 * content as v2 deltas (data.delta_content + data.phase), NOT the OpenAI
 * v1 choices shape. The old reader understood neither: v2 errors were
 * skipped as undecodable, v2 deltas dropped as empty chunks, and the UI
 * said "Empty response from model" while the backend had replied with a
 * precise, actionable rejection. Both protocols are now decoded, and an
 * error payload terminates the stream with [StreamEvent.Error] carrying
 * the backend's own message.
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

            // V2 protocol deltas (live-verified): data.delta_content + data.phase.
            // "answer" → visible text, "thinking" → reasoning channel,
            // tool_call/tool_response → internal plumbing the UI never renders.
            chunk.deltaContent?.takeIf { it.isNotEmpty() }?.let { delta ->
                when (chunk.deltaPhase) {
                    "thinking" -> {
                        reasoning.append(delta)
                        emit(StreamEvent.ReasoningDelta(delta))
                    }
                    "answer", null, "" -> {
                        content.append(delta)
                        emit(StreamEvent.ContentDelta(delta))
                    }
                    else -> Unit                                       // tool phases — not user-facing
                }
            }

            // V2 full-content messages (non-incremental).
            chunk.fullContent?.takeIf { it.isNotEmpty() }?.let { full ->
                content.append(full)
                emit(StreamEvent.ContentDelta(full))
            }

            // V1/OpenAI-compat fallback (choices[].delta) for older gateways.
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
