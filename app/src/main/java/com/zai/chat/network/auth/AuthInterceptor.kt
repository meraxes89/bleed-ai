package com.zai.chat.network.auth

import android.util.Log
import com.zai.chat.config.ZaiConfig
import com.zai.chat.data.local.preferences.JwtParser
import com.zai.chat.data.local.preferences.TokenManager
import com.zai.chat.network.FeVersionResolver
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps every request with browser-identical headers + bearer token.
 *
 * Header parity with the live frontend (verified against prod-fe-1.1.112):
 *  - `X-FE-Version` on all requests, value resolved at RUNTIME by
 *    [FeVersionResolver] so z.ai frontend rotations can never stale-brick us.
 *  - `X-Signature` is NOT attached here: the real frontend only signs the
 *    completions endpoint (full payload spec, handled by RequestSigner in the
 *    native transport). The previous interceptor computed a signature over the
 *    bare timestamp — cryptographically invalid per the verified spec and sent
 *    on unsigned GETs where the browser sends none. Removed.
 *
 * Session-expiry detection (the "stranded user" fix):
 *  - Reactive: the z.ai backend answers auth failures with BOTH codes
 *    (live-probed 2026-10): HTTP 403 `{"detail":"Not authenticated"}` when
 *    no/absent credential, HTTP 401 `{"detail":"401 Unauthorized"}` when an
 *    invalid or expired bearer token is presented. Either — when we actually
 *    attached an Authorization header — now publishes TokenExpired so the
 *    reconnect screen takes over instead of every request silently failing
 *    forever.
 *  - Proactive: the stored JWT's `exp` claim is checked before the request
 *    flies; a locally-expired token publishes the same event (rate-limited),
 *    so the user is routed to re-auth BEFORE the first doomed round-trip.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventManager: AuthEventManager,
    private val feVersionResolver: FeVersionResolver
) : Interceptor {

    // Rate-limit for the proactive expiry signal — otherwise every request of
    // an expired session would tryEmit (harmless but noisy in logs/flow).
    @Volatile
    private var lastExpiryEmitMs: Long = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("User-Agent", ZaiConfig.USER_AGENT)
            .header("Origin", ZaiConfig.ORIGIN)
            .header("Referer", ZaiConfig.REFERER)

        builder.header("Accept-Language", "en-US")

        // Runtime-resolved frontend version — never a stale compiled snapshot.
        // Non-blocking: always returns the in-memory current value.
        if (ZaiConfig.FE_VERSION_HEADER_KEY.isNotEmpty()) {
            builder.header(ZaiConfig.FE_VERSION_HEADER_KEY, feVersionResolver.current())
            feVersionResolver.maybeRefreshAsync()
        }

        val token = tokenManager.getStoredToken()
        var bearerAttached = false
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
            bearerAttached = true
            maybeEmitLocalExpiry(token)
        }

        val response = chain.proceed(builder.build())

        // Body untouched (streaming stays intact) — we only observe the code.
        // 403 is what z.ai actually returns for "Not authenticated" (probed
        // live); 401 is kept for Open WebUI upstream parity / future drift.
        if (bearerAttached && (response.code == 401 || response.code == 403)) {
            Log.w("BleedAI-Auth", "HTTP ${response.code} with bearer attached -> session expired signal")
            authEventManager.emitTokenExpired()
        }
        return response
    }

    /**
     * Publish TokenExpired when the stored JWT is already past its `exp` claim.
     * Local pre-flight beats a guaranteed-failing round-trip. Still lets the
     * request proceed (clock skew / server-side key rotation tolerance) — the
     * reactive 401/403 check remains the source of truth.
     */
    private fun maybeEmitLocalExpiry(token: String) {
        val claims = JwtParser.parse(token) ?: return
        if (!claims.isExpired) return
        val now = System.currentTimeMillis()
        if (now - lastExpiryEmitMs < EXPIRY_EMIT_MIN_INTERVAL_MS) return
        lastExpiryEmitMs = now
        Log.w("BleedAI-Auth", "Stored JWT is locally expired (${claims.humanReadableExpiry}) -> session expired signal")
        authEventManager.emitTokenExpired()
    }

    private companion object {
        const val EXPIRY_EMIT_MIN_INTERVAL_MS = 30_000L
    }
}
