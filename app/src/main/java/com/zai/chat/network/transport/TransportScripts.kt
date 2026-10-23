package com.zai.chat.network.transport

/**
 * Embedded JavaScript signer environment evaluated in the WebView runtime.
 * Implements the Aliyun Captcha 2.0 harness, reverse-engineered from the
 * DEPLOYED chat.z.ai frontend bundle (prod-fe-1.1.114) and live-verified.
 *
 * WHY THE OLD HARNESS COULD NEVER MINT A TICKET (user logcat forensics,
 * 2026-10-23: six mints, zero bridge callbacks, 10s Kotlin timeouts, len=0):
 *
 *  1. The SDK <script> sat in <head> with NO onerror handler. If
 *     o.alicdn.com failed or was slow, DOMContentLoaded raced ahead, found
 *     initAliyunCaptcha undefined, and init() returned SILENTLY — forever.
 *     The real FE injects the script dynamically with onload/onerror.
 *  2. `button: null` was passed to initAliyunCaptcha. The SDK in popup mode
 *     binds its verification trigger to the button element — with null,
 *     NOTHING is bound and nothing can ever start verification. The real FE
 *     creates a hidden <button id="chat-captcha-trigger"> and CLICKS it.
 *  3. mint() called `instance.verify()` — an API the Aliyun instance does
 *     not expose (live-inspected keys: captchaVerifyCallback, success, fail,
 *     refresh, ...). The real FE triggers via the button click and refreshes
 *     via instance.refresh().
 *  4. Every failure path resolved a JS Promise that Kotlin never consumed
 *     (evaluateJavascript result discarded) — the continuation only resumed
 *     on bridge callbacks that could never fire. Total silence.
 *
 * This harness replicates the FE exactly: dynamic script injection with
 * onerror + retry, hidden #chat-captcha-element + #chat-captcha-trigger,
 * click-triggered verification, instance.refresh() after success, one
 * automatic fail-retry (FE parity), and a bridge callback on EVERY path.
 */
object TransportScripts {

    const val BRIDGE_NAME: String = "ZaiBridge"

    val SIGNER_HTML_PAYLOAD: String = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * { box-sizing: border-box; }
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            background: transparent;
            overflow: hidden;
        }
    </style>
</head>
<body>
    <!-- FE-identical harness nodes: hidden container + hidden trigger button -->
    <div id="chat-captcha-element"></div>
    <button id="chat-captcha-trigger" type="button" aria-hidden="true" tabindex="-1"></button>
    <script>
    (function () {
        var SDK_URL = 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js';
        var ELEMENT_ID = 'chat-captcha-element';
        var TRIGGER_ID = 'chat-captcha-trigger';

        var state = {
            instance: null,
            ready: false,
            pending: null,
            initPromise: null
        };

        function report(level, msg) {
            try { console.log('[ZaiSigner][' + level + '] ' + msg); } catch (e) { /* noop */ }
        }

        function bridge(fn, arg) {
            if (window.ZaiBridge && window.ZaiBridge[fn]) {
                try { window.ZaiBridge[fn](arg); } catch (e) { /* noop */ }
            }
        }

        // Hide the harness nodes exactly like the real FE (offscreen, inert).
        try {
            var el = document.getElementById(ELEMENT_ID);
            el.style.cssText = 'position:absolute;left:-99999px;top:-99999px;width:0;height:0;overflow:hidden;pointer-events:none;';
            var trig = document.getElementById(TRIGGER_ID);
            trig.style.cssText = 'position:absolute;left:-99999px;top:-99999px;width:1px;height:1px;opacity:0;';
        } catch (e) { /* noop */ }

        // FE parity: dynamic script injection WITH error handling + retries.
        function loadSdkOnce() {
            return new Promise(function (resolve, reject) {
                if (typeof window.initAliyunCaptcha === 'function') { resolve(); return; }
                window.AliyunCaptchaConfig = { region: 'sgp', prefix: 'no8xfe' };
                var s = document.querySelector('script[src="' + SDK_URL + '"]');
                if (!s) {
                    s = document.createElement('script');
                    s.src = SDK_URL;
                    document.head.appendChild(s);
                }
                if (s.dataset.loaded === '1') { resolve(); return; }
                s.addEventListener('load', function () { s.dataset.loaded = '1'; resolve(); });
                s.addEventListener('error', function () { reject(new Error('captcha script load failed')); });
            });
        }

        function loadSdkWithRetries(tries) {
            return loadSdkOnce().catch(function (e) {
                if (tries > 0) {
                    report('warn', 'SDK load failed, retrying (' + (3 - tries) + ')...');
                    return new Promise(function (r) { setTimeout(r, 1500); })
                        .then(function () { return loadSdkWithRetries(tries - 1); });
                }
                throw e;
            });
        }

        function initOnce() {
            if (state.initPromise) return state.initPromise;
            state.initPromise = loadSdkWithRetries(3).then(function () {
                return new Promise(function (resolve) {
                    window.initAliyunCaptcha({
                        SceneId: 'didk33e0',
                        mode: 'popup',
                        element: '#' + ELEMENT_ID,
                        button: '#' + TRIGGER_ID,
                        timeout: 10000,
                        delayBeforeSuccess: false,
                        getInstance: function (inst) {
                            state.instance = inst;
                            state.ready = true;
                            report('info', 'Aliyun Captcha instance ready');
                            bridge('onSignerReady');
                            resolve(true);
                        },
                        success: function (res) {
                            var ticket = (res && res.captchaVerifyParam) ||
                                         (typeof res === 'string' ? res : '');
                            report('info', 'Ticket minted (len=' + (ticket ? ticket.length : 0) + ')');
                            // FE parity: refresh so the next mint re-evaluates risk freshly.
                            try {
                                if (state.instance && typeof state.instance.refresh === 'function') {
                                    state.instance.refresh();
                                }
                            } catch (e) { /* noop */ }
                            bridge('onInteractiveDismiss');
                            bridge('onTicketSuccess', ticket);
                            var p = state.pending; state.pending = null;
                            if (p) p(ticket);
                        },
                        fail: function (err) {
                            report('warn', 'Risk escalation: ' + JSON.stringify(err));
                            bridge('onInteractiveRequired');
                            // FE parity: one automatic retry by re-clicking the trigger.
                            var p = state.pending;
                            if (p && !p.retried) {
                                p.retried = true;
                                setTimeout(function () { triggerVerify(); }, 400);
                            } else if (p) {
                                state.pending = null;
                                p('');
                                bridge('onTicketError', 'risk_escalation_retry_exhausted');
                            }
                        },
                        onError: function (err) {
                            report('error', 'SDK error: ' + JSON.stringify(err));
                            bridge('onInteractiveDismiss');
                            bridge('onTicketError', JSON.stringify(err || ''));
                            var p = state.pending; state.pending = null;
                            if (p) p('');
                        },
                        onClose: function () {
                            report('info', 'Challenge closed');
                            bridge('onInteractiveDismiss');
                            var p = state.pending; state.pending = null;
                            if (p) p('');
                        }
                    });
                    // Safety: never let init hang the pipeline if getInstance never fires.
                    setTimeout(function () { resolve(state.ready); }, 12000);
                });
            }).catch(function (e) {
                report('error', 'SDK unavailable: ' + String(e && e.message || e));
                bridge('onTicketError', 'sdk_load_failed: ' + String(e && e.message || e));
                return false;
            });
            return state.initPromise;
        }

        function triggerVerify() {
            var b = document.getElementById(TRIGGER_ID);
            if (b) b.click();
        }

        window.__ZaiSigner = {
            init: function () { return initOnce(); },
            mint: function () {
                return initOnce().then(function (ok) {
                    if (!ok || !state.ready) {
                        bridge('onTicketError', 'engine_not_ready');
                        return '';
                    }
                    return new Promise(function (resolve) {
                        var p = function (ticket) { resolve(ticket); };
                        p.retried = false;
                        state.pending = p;
                        triggerVerify();
                        // Hard fail-safe: never hang the native caller.
                        setTimeout(function () {
                            if (state.pending === p) {
                                state.pending = null;
                                resolve('');
                                bridge('onTicketError', 'mint_timeout');
                            }
                        }, 12000);
                    });
                });
            },
            isReady: function () { return !!state.ready; }
        };

        initOnce();
    })();
    </script>
</body>
</html>
    """.trimIndent()
}
