package com.zai.chat.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [RECON] Inbound SSE chunk. GLM streams content and reasoning as two
 *  independent delta channels inside the same chunk format.
 *
 *  [code]/[detail] model the in-stream REJECTION payloads the completions
 *  endpoint returns with HTTP 200 (e.g. FRONTEND_CAPTCHA_REQUIRED), verified
 *  by live probes: the gate answers 200 + error JSON in the body rather than
 *  a 4xx, so the SSE reader must be able to recognize and surface them. */
@Serializable
data class ChatCompletionChunk(
    @SerialName("id") val id: String? = null,
    @SerialName("choices") val choices: List<ChunkChoice> = emptyList(),
    @SerialName("usage") val usage: UsageInfo? = null,
    @SerialName("citations") val citations: List<CitationItem>? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("detail") val detail: String? = null
) {
    /** True when this chunk is an in-stream error object, not a completion delta. */
    val isErrorPayload: Boolean
        get() = choices.isEmpty() && (code != null || detail != null)

    /** Human-readable one-liner for logs / error banners. */
    val errorDescription: String
        get() = "Backend rejected: [$code] ${detail ?: "no detail"}"
}

@Serializable
data class ChunkChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("delta") val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChunkDelta(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

/** [RECON] Web-search citation shape — verified against a live capture in P12. */
@Serializable
data class CitationItem(
    @SerialName("index") val index: Int,
    @SerialName("url") val url: String,
    @SerialName("title") val title: String,
    @SerialName("snippet") val snippet: String? = null
)
