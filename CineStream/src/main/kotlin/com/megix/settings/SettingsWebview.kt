package com.megix.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import com.megix.CF_BYPASS_USER_AGENT
import com.megix.settings.SettingsTheme.dp


@SuppressLint("SetJavaScriptEnabled")
internal object SettingsWebView {

    private const val TAG = "CineStreamAuth"

    enum class LoginType { SHOWBOX, GRAMCINEMA }

    private data class Config(
        val title: String,
        val startUrl: String,
        val accentColor: Int,
        val accentColorB: Int,
        val tokenLabel: String,
        val tokenCookieName: String,
        val extractJs: String
    )

    private fun configFor(type: LoginType) = when (type) {
        LoginType.SHOWBOX -> Config(
            "Login to Febbox", "https://www.febbox.com/",
            Color.parseColor("#F59E0B"), Color.parseColor("#D97706"),
            "UI Token", "ui",
            "(()=>{let m=document.cookie.match(/(?:^|;\\s*)ui=([^;]*)/);return m?decodeURIComponent(m[1]):''})()"
        )
        LoginType.GRAMCINEMA -> Config(
            "Login to GramCinema", "https://bollywood.eu.org/",
            Color.parseColor("#F472B6"), Color.parseColor("#EC4899"),
            "Bearer Token", "bearer_token",
            "(()=>{const isReal=t=>t&&t.startsWith('eyJ0eXAiOi')&&!t.includes(' ');for(let k of ['token','bearer_token','access_token','auth_token','jwt','bearerToken','authToken','user_token','userToken','accessToken','authAccessToken']){let t=localStorage.getItem(k)||sessionStorage.getItem(k);if(isReal(t)){console.log('Found real token via key: '+k);return t;}}for(let i=0;i<localStorage.length;i++){let v=(localStorage.getItem(localStorage.key(i))||'').replace(/^Bearer\\s+/i,'').replace(/[\"']/g,'').trim();if(isReal(v)){console.log('Found real token hidden in storage!');return v;}}return '';})()"
        )
    }

    // Only shim window.chrome, which some WebView builds lack and Cloudflare
    // fingerprints. Never override navigator.userAgent here: the WebView's UA
    // (settings.userAgentString) is already the device's real Chrome UA, and a
    // JS override that disagrees with the Client Hints the engine sends makes
    // Cloudflare treat the client as spoofed and re-challenge it indefinitely.
    private const val SPOOF_JS = "if(!window.__csxSpoofed){window.__csxSpoofed=!0;if(!window.chrome)window.chrome={runtime:{},loadTimes:()=>{},csi:()=>{}}}"

    // The one bypass dialog currently on screen. Cloudflare marks a challenge
    // in the shared cookie jar; two challenges at once invalidate each other's
    // __cf_chl_* cookies, so the page reloads forever. Only ever show one.
    private var cfBypassDialog: Dialog? = null

    /**
     * The device's real WebView UA, cleaned the same way the app's
     * CloudflareKiller solver cleans it (drop "; wv" and "Version/x.y").
     *
     * cf_clearance is bound to the UA that solved it. Deriving the UA from the
     * engine keeps it consistent with the Sec-CH-UA-* hints Chromium sends, and
     * it matches WebViewResolver.webViewUserAgent, which the plugin's requests
     * already send via CineStreamExtractors.currentCfUserAgent().
     */
    private fun deviceUserAgent(context: Context): String {
        val raw = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: CF_BYPASS_USER_AGENT
        var ua = raw
            .replace("; wv", "")
            .replace(Regex("Version/[0-9.]+\\s*"), "")
            .replace("Android TV", "Android")
        if (ua.contains("Android") && !ua.contains("Mobile")) {
            ua = ua.replace("Safari/", "Mobile Safari/")
        }
        return ua
    }

    private const val GRAMCINEMA_INTERCEPT_JS = "if(!window.__csxTokenHooked){window.__csxTokenHooked=!0;const isReal=s=>s&&t.startsWith('eyJ0eXAiOi')&&!s.includes(' ');let _set=XMLHttpRequest.prototype.setRequestHeader;XMLHttpRequest.prototype.setRequestHeader=function(n,v){if(n.toLowerCase()==='authorization'){let t=v.replace(/^Bearer\\s+/i,'').trim();if(isReal(t)){console.log('Intercepted real XHR token!');try{Android.onToken(t)}catch(e){}}}return _set.apply(this,arguments)};let _f=window.fetch;if(_f)window.fetch=function(u,i){try{if(i&&i.headers){let h=i.headers,a=typeof h.get==='function'?h.get('Authorization'):(h.Authorization||h.authorization||'');if(a){let t=a.replace(/^Bearer\\s+/i,'').trim();if(isReal(t)){console.log('Intercepted real Fetch token!');Android.onToken(t)}}}}catch(e){}return _f.apply(this,arguments)}}"

    private class TokenBridge(private val deliver: (String) -> Unit) {
        @JavascriptInterface
        fun onToken(value: String) {
            Log.d(TAG, "Native Bridge received token! Length: ${value.length}")
            deliver(value)
        }
    }

    // ── Cloudflare bypass ────────────────────────────────────────────────
    // Behaviour ported from Phisher's AnimePahe CloudflareWebViewDialog
    // (GPLv3): purge stale clearance cookies, load the challenge in a clean
    // WebView, poll the title + cookie jar every second, then persist the
    // clearance and the UA that solved it before auto-dismissing.
    private const val CF_POLL_INTERVAL_MS = 1000L
    private const val CF_POLL_TIMEOUT_MS = 120_000L
    private val CF_CHALLENGE_TITLES = listOf(
        "just a moment",
        "checking your browser",
        "attention required",
        "ddos-guard",
        "one more step",
    )

    private fun hostUrl(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) url.trimEnd('/')
        else "${uri.scheme}://${uri.host}"
    }.getOrDefault(url.trimEnd('/'))

    private fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase()
        return CF_CHALLENGE_TITLES.any { lower.contains(it) }
    }

    private fun hasClearance(cookies: String): Boolean =
        Regex("cf_clearance=([^;]+)").find(cookies)
            ?.groupValues?.getOrNull(1)?.trim()?.length?.let { it >= 10 } == true

    /** Merge the cookie jars of the current page, target and all AnimePahe mirrors. */
    private fun extractCookies(webView: WebView, targetUrl: String): String {
        val cookieManager = CookieManager.getInstance()
        runCatching { cookieManager.flush() }
        val targetHost = hostUrl(targetUrl)
        val urls = linkedSetOf<String>()
        webView.url?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        if (targetUrl.isNotBlank()) urls.add(targetUrl)
        if (targetHost.isNotBlank()) {
            urls.add(targetHost)
            urls.add("$targetHost/")
        }
        urls.add("https://animepahe.pw/")
        urls.add("https://animepahe.org/")
        urls.add("https://animepahe.com/")
        urls.add("https://challenges.cloudflare.com/")
        urls.add("https://cloudflare.com/")

        val cookies = LinkedHashMap<String, String>()
        urls.forEach { url ->
            val raw = runCatching { cookieManager.getCookie(url) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val name = trimmed.substring(0, eq).trim()
                        val value = trimmed.substring(eq + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                    }
                }
            }
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Remove only Cloudflare's own cookies from the WebView jar. A stale
     * cf_clearance/__cf_bm makes Cloudflare re-challenge forever, while the
     * site's normal cookies must survive so the page still loads.
     */
    private fun purgeCfCookies(targetUrl: String) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            val hosts = linkedSetOf(
                hostUrl(targetUrl),
                "https://animepahe.pw",
                "https://animepahe.org",
                "https://animepahe.com",
            )
            hosts.filter { it.isNotBlank() }.forEach { host ->
                val url = host.trimEnd('/') + "/"
                val existing = cookieManager.getCookie(url) ?: return@forEach
                existing.split(";").forEach { part ->
                    val name = part.trim().substringBefore('=').trim()
                    if (name == "cf_clearance" || name == "__cf_bm" || name.startsWith("cf_chl")) {
                        cookieManager.setCookie(
                            url,
                            "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/",
                        )
                    }
                }
            }
            cookieManager.flush()
        }
    }

    fun showCloudflareBypass(context: Context, startUrl: String, onCookiesChanged: () -> Unit = {}) {
        if (cfBypassDialog?.isShowing == true) {
            Log.d(TAG, "Cloudflare bypass dialog already open, not stacking another")
            return
        }
        val theme = SettingsTheme
        val mainHandler = Handler(Looper.getMainLooper())
        val targetUrl = startUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        // The UA that solves the challenge must be the UA used for requests later
        // (cf_clearance is UA-bound), so use the engine's real UA here.
        val solvingUserAgent = deviceUserAgent(context)

        var currentUrl = targetUrl
        var dialogRef: Dialog? = null
        var webViewRef: WebView? = null
        var pollRunnable: Runnable? = null
        var finished = false

        val statusText = TextView(context).apply {
            id = View.generateViewId()
            text = "Loading challenge page…"; textSize = 11f; setTextColor(theme.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun updateStatus(message: String) {
            mainHandler.post { statusText.text = message }
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3.dp(context))
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
            max = 100; progress = 10
        }

        val webViewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val cookieText = TextView(context).apply {
            text = "Cookies: <none>"
            textSize = 11f; setTextColor(theme.TEXT_SECONDARY)
            setPadding(16.dp(context), 6.dp(context), 16.dp(context), 6.dp(context))
        }

        fun refreshCookieDisplay(url: String) {
            val cookies = extractCookies(webViewRef ?: return, targetUrl)
                .takeIf { it.isNotBlank() } ?: "<none>"
            mainHandler.post { cookieText.text = "Cookies: $cookies" }
            Log.d(TAG, "Cloudflare bypass cookies for $url: $cookies")
        }

        fun persistAndDismiss(cookies: String) {
            if (finished || !hasClearance(cookies)) return
            finished = true
            pollRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.removeCallbacksAndMessages(null)

            val current = webViewRef?.url?.takeIf { it.startsWith("http") } ?: targetUrl
            val currentHost = hostUrl(current)
            // Only persist under the host that actually issued the clearance.
            // Copying the same cf_clearance to a mirror would be rejected.
            linkedSetOf(current, currentHost).filter { it.isNotBlank() }.forEach {
                Settings.saveCookieForDomain(it, cookies)
            }
            Settings.saveCfUserAgent(webViewRef?.settings?.userAgentString ?: solvingUserAgent)

            Log.d(TAG, "✅ Saved Cloudflare clearance for $currentHost: ${cookies.take(100)}...")
            cookieText.text = "Cookies: $cookies"
            cookieText.setTextColor(Color.parseColor("#4CAF50"))
            statusText.text = "✅ Done! Cloudflare bypassed."
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            progressBar.visibility = View.GONE
            onCookiesChanged()
            mainHandler.postDelayed({ dialogRef?.dismiss() }, 500L)
        }

        fun checkForClearance() {
            val webView = webViewRef ?: return
            if (finished) return
            val cookies = extractCookies(webView, targetUrl)
            val title = webView.title.orEmpty()
            Log.d(TAG, "CF poll title='$title' cookies=${cookies.take(120)}")
            if (hasClearance(cookies) && !isChallengeTitle(title)) {
                persistAndDismiss(cookies)
            } else if (isChallengeTitle(title)) {
                updateStatus("🔄 Challenge active – solve the CAPTCHA above")
            } else {
                updateStatus("⏳ Waiting for verification…")
            }
        }

        val cookieControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(context), 6.dp(context), 16.dp(context), 6.dp(context))
            addView(SettingsWidgets.pillBtn(context, "💾 Save Cookies", Color.parseColor("#10B981"), Color.parseColor("#0B1120"), Color.parseColor("#059669")) {
                val webView = webViewRef
                val cookies = if (webView != null) extractCookies(webView, targetUrl) else ""
                if (hasClearance(cookies)) {
                    persistAndDismiss(cookies)
                } else {
                    Toast.makeText(context, "No cf_clearance yet — solve the challenge first", Toast.LENGTH_SHORT).show()
                }
            })
        }

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
            isFocusable = true; isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true; allowFileAccess = true
                loadsImagesAutomatically = true
                useWideViewPort = true; loadWithOverviewMode = true
                setSupportZoom(false); builtInZoomControls = false
                userAgentString = solvingUserAgent
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (finished) return
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    updateStatus("Loading… $newProgress%")
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    currentUrl = url
                    updateStatus("Loading challenge page…")
                    Log.d(TAG, "Cloudflare bypass WebView started: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    view.evaluateJavascript(SPOOF_JS, null)
                    refreshCookieDisplay(url)
                    checkForClearance()
                    Log.d(TAG, "Cloudflare bypass WebView finished: $url")
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }
        webViewRef = webView
        webViewContainer.addView(webView)

        val tvCursor = TvTouchCursor(context, webViewContainer, webView)

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(context), 12.dp(context), 10.dp(context), 12.dp(context))
            background = theme.colorDrawable(theme.BG_CARD)

            addView(SettingsWidgets.accentBar(context, Color.parseColor("#22C55E"), Color.parseColor("#16A34A")))
            addView(TextView(context).apply {
                text = "🛡️ AnimePahe – Cloudflare Bypass"; textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(theme.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SettingsWidgets.hSpacer(context, 8))
            addView(SettingsWidgets.pillBtn(context, "🖱 TV Cursor", Color.parseColor("#22C55E"), theme.BG_CARD, Color.parseColor("#22C55E")) {
                tvCursor.toggle()
                Toast.makeText(
                    context,
                    if (tvCursor.isActive) "Touch cursor ON — D-pad to move, OK to tap" else "Touch cursor OFF",
                    Toast.LENGTH_SHORT
                ).show()
            })
            addView(SettingsWidgets.hSpacer(context, 8))
            addView(SettingsWidgets.pillBtn(context, "✕ Close", theme.TEXT_SECONDARY, theme.BG_CARD, Color.parseColor("#2A2D3E")) { dialogRef?.dismiss() })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = theme.colorDrawable(theme.BG_DARK)
            addView(titleBar)
            addView(TextView(context).apply {
                text = "Solve any CAPTCHA shown below. Cookies save and the dialog closes automatically once done."
                textSize = 11f; setTextColor(theme.TEXT_SECONDARY)
                setPadding(16.dp(context), 0, 16.dp(context), 6.dp(context))
            })
            addView(progressBar); addView(webViewContainer)
            addView(cookieControls)
            addView(cookieText)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(context), 6.dp(context), 16.dp(context), 8.dp(context))
                background = theme.colorDrawable(theme.BG_CARD)
                addView(statusText)
            })
        }

        dialogRef = object : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.action == android.view.KeyEvent.ACTION_UP
                    && tvCursor.isActive
                ) {
                    tvCursor.hide()
                    return true
                }
                if (tvCursor.onKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root); setCancelable(true)
            setOnDismissListener {
                finished = true
                pollRunnable?.let { mainHandler.removeCallbacks(it) }
                tvCursor.destroy()
                webView.stopLoading(); webView.destroy()
                webViewRef = null
                if (cfBypassDialog === this) cfBypassDialog = null
                onCookiesChanged()
            }
            window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                setBackgroundDrawable(theme.colorDrawable(theme.BG_DARK))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION") setDecorFitsSystemWindows(false)
                    root.setOnApplyWindowInsetsListener { view, insets ->
                        val ime = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars())
                        view.setPadding(ime.left, ime.top, ime.right, ime.bottom)
                        insets
                    }
                } else {
                    @Suppress("DEPRECATION")
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
            show()
        }

        cfBypassDialog = dialogRef

        // Phisher's key step: never let a stale clearance cookie follow us
        // into a fresh challenge, or Cloudflare loops forever.
        purgeCfCookies(targetUrl)
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl(targetUrl)
        webView.requestFocus()

        pollRunnable = object : Runnable {
            var elapsed = 0L
            override fun run() {
                if (finished) return
                checkForClearance()
                if (finished) return
                elapsed += CF_POLL_INTERVAL_MS
                if (elapsed >= CF_POLL_TIMEOUT_MS) {
                    finished = true
                    updateStatus("⏱️ Timed out — close and try again")
                    mainHandler.postDelayed({ dialogRef?.dismiss() }, 1500L)
                    return
                }
                mainHandler.postDelayed(this, CF_POLL_INTERVAL_MS)
            }
        }
        pollRunnable?.let { mainHandler.postDelayed(it, CF_POLL_INTERVAL_MS) }
    }

    fun show(context: Context, type: LoginType, onToken: (String) -> Unit) {
        val cfg = configFor(type)
        val theme = SettingsTheme
        val mainHandler = Handler(Looper.getMainLooper())

        var tokenCaptured = false
        var currentUrl = cfg.startUrl
        var dialogRef: Dialog? = null

        fun deliver(raw: String) {
            if (tokenCaptured) return
            val token = raw.trim().removeSurrounding("\"").trim()
            if (token.isBlank() || token == "null") return

            tokenCaptured = true
            Log.i(TAG, "SUCCESS! Token captured for ${type.name}.")

            mainHandler.post {
                onToken(token)
                Toast.makeText(context, "✓ ${cfg.tokenLabel} captured — tap outer Save to apply", Toast.LENGTH_SHORT).show()
                dialogRef?.dismiss()
            }
        }

        fun clearWebLoginSession(onCleared: () -> Unit) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies { _ ->
                CookieManager.getInstance().flush()
                mainHandler.post { onCleared() }
            }
        }

        val statusText = TextView(context).apply {
            text = cfg.startUrl; textSize = 10f; setTextColor(theme.TEXT_SECONDARY); setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3.dp(context))
            progressTintList = android.content.res.ColorStateList.valueOf(cfg.accentColor)
            max = 100; progress = 10
        }

        val webViewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
            isFocusable = true; isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true; loadWithOverviewMode = true
                setSupportZoom(false); builtInZoomControls = false
                userAgentString = CF_BYPASS_USER_AGENT

                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(TokenBridge(::deliver), "Android")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS: ${consoleMessage?.message()}")
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    Log.d(TAG, "Creating new popup window for OAuth flow.")
                    val popupWebView = WebView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = view.settings.userAgentString
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView) {
                                Log.d(TAG, "Popup window called close(). Destroying overlay.")
                                webViewContainer.removeView(window)
                                window.destroy()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                        }
                    }
                    webViewContainer.addView(popupWebView)
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = popupWebView
                    resultMsg?.sendToTarget()
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                private fun updateUrl(url: String) { currentUrl = url; mainHandler.post { statusText.text = url } }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = updateUrl(url)

                override fun onPageFinished(view: WebView, url: String) {
                    updateUrl(url)
                    view.evaluateJavascript(SPOOF_JS, null)

                    if (type == LoginType.GRAMCINEMA) {
                        view.evaluateJavascript(GRAMCINEMA_INTERCEPT_JS, null)
                        var pollCount = 0
                        val poll = object : Runnable {
                            override fun run() {
                                if (tokenCaptured || pollCount++ >= 40) return
                                view.evaluateJavascript(cfg.extractJs) { res ->
                                    val clean = res?.trim()?.removeSurrounding("\"")?.trim()
                                    if (!clean.isNullOrBlank() && clean != "null") deliver(clean)
                                    else mainHandler.postDelayed(this, 1500)
                                }
                            }
                        }
                        mainHandler.postDelayed(poll, 1500)
                    }

                    val cookieToken = CookieManager.getInstance().getCookie(url)?.split(";")
                        ?.map { it.trim() }?.firstOrNull { it.startsWith("${cfg.tokenCookieName}=") }
                        ?.removePrefix("${cfg.tokenCookieName}=")

                    if (!cookieToken.isNullOrBlank()) { deliver(cookieToken); return }

                    if (!tokenCaptured && cfg.extractJs.isNotBlank()) {
                        view.evaluateJavascript(cfg.extractJs) { res ->
                            val clean = res?.trim()?.removeSurrounding("\"")?.trim()
                            if (!clean.isNullOrBlank() && clean != "null") deliver(clean)
                        }
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }

        webViewContainer.addView(webView)

        val tvCursor = TvTouchCursor(context, webViewContainer, webView)

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(context), 12.dp(context), 10.dp(context), 12.dp(context))
            background = theme.colorDrawable(theme.BG_CARD)

            addView(SettingsWidgets.accentBar(context, cfg.accentColor, cfg.accentColorB))
            addView(TextView(context).apply {
                text = cfg.title; textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(theme.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SettingsWidgets.hSpacer(context, 8))
            addView(SettingsWidgets.pillBtn(context, "🖱 TV Cursor", cfg.accentColor, theme.BG_CARD, cfg.accentColor) {
                tvCursor.toggle()
                Toast.makeText(
                    context,
                    if (tvCursor.isActive) "Touch cursor ON — D-pad to move, OK to tap" else "Touch cursor OFF",
                    Toast.LENGTH_SHORT
                ).show()
            })
            addView(SettingsWidgets.hSpacer(context, 8))
            addView(SettingsWidgets.pillBtn(context, "✕ Close", theme.TEXT_SECONDARY, theme.BG_CARD, Color.parseColor("#2A2D3E")) { dialogRef?.dismiss() })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = theme.colorDrawable(theme.BG_DARK)
            addView(titleBar); addView(progressBar); addView(webViewContainer)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(context), 6.dp(context), 16.dp(context), 8.dp(context))
                background = theme.colorDrawable(theme.BG_CARD)
                addView(statusText)
            })
        }

        dialogRef = object : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.action == android.view.KeyEvent.ACTION_UP
                    && tvCursor.isActive
                ) {
                    tvCursor.hide()
                    return true
                }
                if (tvCursor.onKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root); setCancelable(true)
            setOnDismissListener { tvCursor.destroy(); webView.stopLoading(); webView.destroy() }
            window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                setBackgroundDrawable(theme.colorDrawable(theme.BG_DARK))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION") setDecorFitsSystemWindows(false)
                    root.setOnApplyWindowInsetsListener { view, insets ->
                        val ime = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars())
                        view.setPadding(ime.left, ime.top, ime.right, ime.bottom)
                        insets
                    }
                } else {
                    @Suppress("DEPRECATION")
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
            show()
        }

        clearWebLoginSession {
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(cfg.startUrl)
            webView.requestFocus()
        }
    }
}