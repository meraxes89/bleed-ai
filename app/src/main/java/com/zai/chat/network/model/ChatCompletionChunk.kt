package com.zai.chat.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Inbound SSE chunk for the v2 completions protocol.
 *
 * LIVE-VERIFIED shapes (probed against chat.z.ai 2026-10, cross-checked with
 * the deployed frontend bundle):
 *
 *  SUCCESS delta:
 *    {"type":"chat:completion","data":{"delta_content":"...","phase":"answer"|"thinking"|"tool_call"|"tool_response","done":false}}
 *  FULL content (non-delta):
 *    {"type":"chat:completion","data":{"content":"...","done":true}}
 *  MODEL-GATE rejection (HTTP 200 + in-stream):
 *    {"type":"chat:completion","data":{"data":{...},"error":{"detail":"Model not available for current user level","code":403},"done":true}}
 *  CAPTCHA-GATE rejection (HTTP 200 + in-stream):
 *    {"data":{"data":{...},"error":{"captcha_error_type":"missing_param","code":"FRONTEND_CAPTCHA_REQUIRED","detail":"Please refresh...","error_code":"FRONTEND_CAPTCHA_REQUIRED"},"done":true}}
 *  LEGACY flat shapes (older gateway):
 *    {"code":"FRONTEND_CAPTCHA_REQUIRED","detail":"..."} | {"error":{"code":...,"detail":...}}
 *
 * The old model only understood the OpenAI v1 shape (choices[].delta) plus a
 * top-level code/detail pair — which is why every v2 error above was silently
 * skipped and every v2 delta invisibly dropped: the stream "completed" empty
 * while the backend had actually replied with a precise rejection.
 */
@Serializable
data class ChatCompletionChunk(
    @SerialName("type") val type: String? = null,
    @SerialName("data") val data: JsonElement? = null,
    // v1/OpenAI-compat fallback (kept for older gateways / local proxies).
    @SerialName("choices") val choices: List<ChunkChoice> = emptyList(),
    @SerialName("usage") val usage: UsageInfo? = null,
    @SerialName("citations") val citations: List<CitationItem>? = null,
    // Flat legacy error shapes.
    @SerialName("error") val error: JsonElement? = null,
    @SerialName("code") val code: JsonElement? = null,
    @SerialName("detail") val detail: String? = null
) {

    /** Normalized `data`: direct object, or stringified JSON unpacked one level. */
    private val dataObj: JsonObject? by lazy {
        when (val d = data) {
            is JsonObject -> d
            is JsonPrimitive -> d.contentOrNull?.let { raw ->
                runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            }
            else -> null
        }
    }

    /**
     * Deepest recognized error object, searched across every live-verified
     * nesting: `error` → `data.error` → `data.data.error`, with flat
     * `code`+`detail` as the legacy fallback. `null` when this chunk is not
     * an error payload.
     */
    val errorPayload: ErrorPayload? by lazy {
        fun JsonObject.errorObj(): JsonObject? =
            (this["error"] as? JsonObject)

        val found: JsonObject? = (error as? JsonObject)
            ?: dataObj?.let { d ->
                d.errorObj() ?: ((d["data"] as? JsonObject)?.errorObj())
            }
            ?: flatLegacyError()

        found?.let { obj ->
            val codeEl = obj["code"] ?: obj["error_code"]
            val code = when (codeEl) {
                is JsonPrimitive -> codeEl.longOrNull?.toString() ?: codeEl.contentOrNull
                else -> null
            }
            val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull
            val captchaType = (obj["captcha_error_type"] as? JsonPrimitive)?.contentOrNull
            if (code != null || detail != null) {
                ErrorPayload(code = code, detail = detail, captchaErrorType = captchaType)
            } else null
        }
    }

    private fun flatLegacyError(): JsonObject? {
        if (code == null && detail == null) return null
        return JsonObject(
            buildMap {
                code?.let { put("code", it) }
                detail?.let { put("detail", JsonPrimitive(it)) }
            }
        )
    }

    /** True when this chunk is an in-stream error object, not a completion delta. */
    val isErrorPayload: Boolean get() = errorPayload != null

    /** Human-readable one-liner for logs / error banners. */
    val errorDescription: String
        get() = errorPayload?.let { e ->
            val codePart = e.code ?: "unknown"
            val detailPart = e.detail ?: e.captchaErrorType ?: "no detail"
            "Backend rejected: [$codePart] $detailPart"
        } ?: "Unknown backend rejection"

    /** v2 delta text (`data.delta_content`). */
    val deltaContent: String?
        get() = (dataObj?.get("delta_content") as? JsonPrimitive)?.contentOrNull

    /** v2 delta phase (`answer` → visible text, `thinking` → reasoning, tool phases → internal). */
    val deltaPhase: String?
        get() = (dataObj?.get("phase") as? JsonPrimitive)?.contentOrNull

    /** v2 full-content message (`data.content`), delivered non-incrementally. */
    val fullContent: String?
        get() = (dataObj?.get("content") as? JsonPrimitive)?.contentOrNull

    /** Structured in-stream rejection. */
    data class ErrorPayload(
        val code: String?,
        val detail: String?,
        val captchaErrorType: String?
    )
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
