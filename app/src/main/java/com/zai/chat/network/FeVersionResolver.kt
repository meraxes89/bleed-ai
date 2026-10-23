package com.zai.chat.network

import android.content.Context
import android.util.Log
import com.zai.chat.config.ZaiConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime auto-discovery for the volatile `X-FE-Version` header value.
 *
 * WHY THIS EXISTS:
 * The z.ai backend captcha gate rejects requests whose `X-FE-Version` does not
 * match the currently deployed frontend (the rejection literally says
 * "Please refresh the page to update the app"). The value rotates on EVERY
 * z.ai frontend deploy (observed: prod-fe-1.1.93 -> prod-fe-1.1.112 within six
 * weeks), so any value baked into the APK is a silent time bomb: the app
 * compiles, passes CI, ships — and then dies in the field the day z.ai ships.
 *
 * STRATEGY:
 * 1. Seed: compiled fallback constant (ZaiConfig.FE_VERSION_HEADER_VALUE),
 *    synchronously upgraded from a persisted last-known-good value at init.
 * 2. Discover: fetch the chat.z.ai homepage HTML (ungated, unauthenticated,
 *    tiny) and regex out `prod-fe-x.y.z`. Persist + promote on success.
 * 3. TTL: re-discover at most every [ZaiConfig.FE_VERSION_CACHE_TTL_MS] via
 *    [maybeRefreshAsync] — a cheap no-op when fresh. Refresh also runs once at
 *    init on a background scope.
 * 4. Self-heal: [refreshNow] is called by the completions transport right
 *    before a captcha-rejection retry, so a mid-session frontend rotation is
 *    recovered within one round-trip.
 *
 * The interceptor path is always synchronous and never blocks: [current]
 * returns the in-memory value instantly; discovery only improves FUTURE
 * requests.
 */
@Singleton
class FeVersionResolver @Inject constructor(
    @ApplicationContext context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Dedicated bare client: must NOT be the app's instrumented OkHttpClient,
     * because that one's AuthInterceptor depends on this resolver (circular
     * dependency) and stamps bearer/cookie headers we do not want here.
     */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentVersion: String = loadPersistedOrDefault()

    @Volatile
    private var lastFetchAttemptMs: Long = 0L

    init {
        // Fire-and-forget discovery at first construction (app startup path).
        maybeRefreshAsync(force = true)
    }

    /** Always-instant value for stamping `X-FE-Version`. Never blocks, never fails. */
    fun current(): String = currentVersion

    /**
     * Background refresh, rate-limited by the TTL. Safe to call from the
     * request path (interceptor) — worst case it spawns one coroutine.
     */
    fun maybeRefreshAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetchAttemptMs < ZaiConfig.FE_VERSION_CACHE_TTL_MS) return
        // Optimistically mark the attempt BEFORE launching so a burst of
        // concurrent interceptor calls coalesces into one homepage fetch.
        lastFetchAttemptMs = now
        scope.launch { refreshNow() }
    }

    /**
     * Immediate (suspending) re-discovery. Used on the self-healing captcha
     * retry path: by the time the retry fires, the fresh version is promoted.
     * Mutex-guarded so concurrent callers cannot stampede the homepage.
     */
    suspend fun refreshNow() = refreshMutex.withLock {
        lastFetchAttemptMs = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(ZaiConfig.BASE_URL + "/")
                .header("User-Agent", ZaiConfig.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Homepage probe HTTP ${response.code}; keeping ${currentVersion}")
                    return@withLock
                }
                val html = response.body?.string() ?: return@withLock
                val discovered = FE_VERSION_REGEX.find(html)?.value ?: run {
                    Log.w(TAG, "No prod-fe-x.y.z marker in homepage; keeping ${currentVersion}")
                    return@withLock
                }
                if (discovered != currentVersion) {
                    currentVersion = discovered
                    prefs.edit()
                        .putString(KEY_VERSION, discovered)
                        .putLong(KEY_FETCHED_AT, lastFetchAttemptMs)
                        .apply()
                    Log.i(TAG, "FE version refreshed: $discovered (was stale relative to live site)")
                } else {
                    prefs.edit().putLong(KEY_FETCHED_AT, lastFetchAttemptMs).apply()
                }
            }
        } catch (e: Exception) {
            // Network unavailable etc. — the compiled/persisted fallback still
            // serves every request; discovery simply retried on the next TTL.
            Log.w(TAG, "FE version discovery failed: ${e.message}")
        }
    }

    private fun loadPersistedOrDefault(): String {
        val persisted = prefs.getString(KEY_VERSION, null)
        return if (persisted.isNullOrBlank()) ZaiConfig.FE_VERSION_HEADER_VALUE else persisted
    }

    companion object {
        private const val TAG = "BleedAI-FeVersion"
        private const val PREFS_NAME = "zai_fe_version_cache"
        private const val KEY_VERSION = "fe_version"
        private const val KEY_FETCHED_AT = "fe_version_fetched_at"

        /** `prod-fe-1.1.112` etc. — anchored to the marker the live site embeds in its HTML. */
        private val FE_VERSION_REGEX = Regex("prod-fe-[0-9]+(?:\\.[0-9]+)+")
    }
}
