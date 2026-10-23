package com.zai.chat.network.transport

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zai.chat.config.ZaiConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Component 2: Hardware-Attached WebView Signer.
 * Operates in V8 context strictly as a local cryptographic signer and telemetry environment
 * to mint valid `captcha_verify_param` tokens via Aliyun Captcha 2.0.
 */
@Singleton
class WebViewSigner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val webView: WebView = WebView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeContinuation: CancellableContinuation<String>? = null

    private val _isInteractive = MutableStateFlow(false)
    val isInteractive: StateFlow<Boolean> = _isInteractive.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        mainHandler.post { configureWebView() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false

            // Mask Android WebView signature (strip '; wv' to prevent bot detection)
            val defaultUa = userAgentString
            userAgentString = defaultUa.replace("; wv", "")
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(NativeBridge(), TransportScripts.BRIDGE_NAME)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                val msg = cm?.message().orEmpty()
                val line = cm?.lineNumber() ?: 0
                Log.d("BleedAI-SignerJS", "[Line $line] $msg")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("BleedAI-Signer", "Signer environment loaded: $url")
            }
        }

        // Load through base URL to allow same-origin session synchronization & cookies
        webView.loadDataWithBaseURL(
            ZaiConfig.BASE_URL,
            TransportScripts.SIGNER_HTML_PAYLOAD,
            "text/html",
            "UTF-8",
            null
        )
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun onSignerReady() {
            Log.i("BleedAI-Signer", "Aliyun Captcha 2.0 Engine ready.")
            _isReady.value = true
        }

        @JavascriptInterface
        fun onTicketSuccess(ticket: String) {
            Log.i("BleedAI-Signer", "Fresh ticket minted: ${ticket.take(16)}...")
            _isInteractive.value = false
            activeContinuation?.let { cont ->
                if (cont.isActive) cont.resume(ticket)
            }
            activeContinuation = null
        }

        @JavascriptInterface
        fun onInteractiveRequired() {
            Log.w("BleedAI-Signer", "Risk elevated. Demanding interactive slider challenge.")
            _isInteractive.value = true
        }

        @JavascriptInterface
        fun onInteractiveDismiss() {
            Log.d("BleedAI-Signer", "Interactive challenge dismissed.")
            _isInteractive.value = false
        }

        @JavascriptInterface
        fun onTicketError(reason: String) {
            Log.e("BleedAI-Signer", "Ticket minting failed: $reason")
            _isInteractive.value = false
            activeContinuation?.let { cont ->
                if (cont.isActive) cont.resume("")
            }
            activeContinuation = null
        }
    }

    /**
     * Mints a single-use `captcha_verify_param` ticket.
     *
     * The JS harness resolves EVERY path through the bridge callbacks
     * (success / sdk-error / closed / fail-retry-exhausted / mint-timeout),
     * so this continuation can no longer hang in silence the way the old
     * harness did (user logcat 2026-10-23: six mints, zero callbacks, every
     * one timing out at exactly 10s with a len=0 ticket).
     *
     * Timeout budget: the JS fail-safe fires at 12s (covering the SDK's own
     * 10s timeout plus the one automatic fail-retry), so 15s here guarantees
     * the bridge error callback — with its precise reason — arrives first.
     */
    suspend fun mint(): String {
        val started = android.os.SystemClock.elapsedRealtime()
        val ticket = withTimeoutOrNull(MINT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                activeContinuation = continuation
                mainHandler.post {
                    webView.evaluateJavascript(
                        "(window.__ZaiSigner && window.__ZaiSigner.mint()) ? 1 : 0",
                        null
                    )
                }
            }
        } ?: ""

        if (ticket.isEmpty()) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            Log.w("BleedAI-Signer", "Mint produced no ticket after ${elapsed}ms; diagnosing engine state...")
            mainHandler.post {
                webView.evaluateJavascript(
                    "window.__ZaiSigner ? (window.__ZaiSigner.isReady() ? 'engine_ready' : 'engine_not_ready') : 'page_dead'"
                ) { state ->
                    Log.w("BleedAI-Signer", "Signer engine state: $state")
                }
            }
        }
        return ticket
    }

    private companion object {
        const val MINT_TIMEOUT_MS = 15_000L
    }
}
