package com.zai.chat.config

/**
 * Centralized network and operational configuration for chat.z.ai (Open WebUI fork).
 *
 * ── SINGLE SOURCE OF TRUTH ──────────────────────────────────────────────
 * EVERY network constant lives here. When live recon (Phase 12) reveals
 * real values, ONLY this file changes. Nothing else in the app is touched.
 *
 * Placeholder values below are best-guess from the Open WebUI API surface.
 * They are marked [RECON] and MUST be replaced after live verification.
 * The app compiles and runs with placeholders; network calls will simply
 * fail until P12 swaps in verified values.
 * ────────────────────────────────────────────────────────────────────────
 */
object ZaiConfig {

    // ── Endpoints ────────────────────────────────────────────────────
    const val BASE_URL: String = "https://chat.z.ai"

    /** Verified Open WebUI v2 completions path. */
    const val COMPLETIONS_PATH: String = "/api/v2/chat/completions"

    /** Canonical Open WebUI chat list path with trailing slash. */
    const val CHATS_PATH: String = "/api/v1/chats/"

    /** Open WebUI standard models path. */
    const val MODELS_PATH: String = "/api/models"

    /** Open WebUI standard file upload path. */
    const val UPLOAD_FILE_PATH: String = "/api/v1/files/"

    // ── Request identity headers ─────────────────────────────────────
    /** Desktop Chrome UA. */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val ORIGIN: String = "https://chat.z.ai"
    const val REFERER: String = "https://chat.z.ai/"

    /**
     * Frontend version header — COMPILED FALLBACK ONLY.
     *
     * The live site embeds its current version in the homepage HTML; it rotates
     * on every z.ai deploy (observed 1.1.93 -> 1.1.112 in six weeks). The real
     * value used on the wire comes from [com.zai.chat.network.FeVersionResolver]
     * (runtime discovery + TTL cache + self-healing refresh). This constant is
     * only the cold-start / offline-discovery fallback, kept at the last
     * live-verified value (2026-10-23) so even the first request is plausible.
     */
    const val FE_VERSION_HEADER_KEY: String = "X-FE-Version"
    const val FE_VERSION_HEADER_VALUE: String = "prod-fe-1.1.112"

    /** How long a discovered FE version is trusted before re-probing the homepage. */
    const val FE_VERSION_CACHE_TTL_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * HMAC salt deobfuscated from live frontend bundle.
     */
    val SIGN_SALT: String? = "key-@@@@)))()((9))-xxxx&&&%%%%%"

    /**
     * Default model identifier from live frontend.
     */
    const val MODEL_DEFAULT: String = "glm-5.3"

    // ── Transport mode ────────────────────────────────────────────────
    /**
     * "webview": Zone 2 Dual-Transport (handles Alibaba Cloud Captcha + session in headless runtime)
     * "okhttp": Direct native OkHttp completion transport
     */
    const val COMPLETIONS_TRANSPORT: String = "webview"

    // ── DOM selectors for fallback / automation ──────────────────────
    const val DOM_TEXTAREA_SELECTOR: String = "textarea, [contenteditable='true']"
    const val DOM_SEND_BUTTON_SELECTOR: String = "button[type='submit'], button[aria-label*='Send']"

    // ── Timeouts ─────────────────────────────────────────────────────
    const val CONNECT_TIMEOUT_SECONDS: Long = 30L
    const val READ_TIMEOUT_SECONDS: Long = 120L   // long: SSE streams stay open
    const val WRITE_TIMEOUT_SECONDS: Long = 60L
}

