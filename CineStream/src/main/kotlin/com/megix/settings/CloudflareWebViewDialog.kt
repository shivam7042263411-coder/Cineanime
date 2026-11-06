package com.megix.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import java.util.Locale
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet
import kotlin.jvm.internal.Ref
import kotlin.text.Regex

/**
 * Phisher's AnimePahe Cloudflare WebView Dialog - exact port.
 *
 * Two modes:
 *   VISIBLE: BottomSheetDialogFragment with a 70% WebView — user solves CAPTCHA.
 *   INVISIBLE: Full-size alpha=0 WebView attached behind the app content
 *              (CloudStream's proven pattern). Solves silently, user sees nothing.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class CloudflareWebViewDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CFWebViewDialog"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 120_000L
        private const val ARG_URL = "target_url"
        private const val ARG_EXPANDED = "start_expanded"
        private const val ARG_VISIBLE = "web_view_visible"

        private val CHALLENGE_TITLES = listOf(
            "just a moment",
            "just a moment...",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "one more step",
        )

        fun isChallengeTitle(title: String): Boolean {
            val lower = title.lowercase(Locale.ROOT)
            return CHALLENGE_TITLES.any { lower.contains(it) }
        }

        // Phisher only accepts a real token: a cf_clearance shorter than 15
        // chars is a placeholder set during the challenge, not a clearance.
        fun extractClearanceToken(cookieStr: String): String? =
            Regex("cf_clearance=([^;]{15,})").find(cookieStr)
                ?.groupValues?.getOrNull(1)?.trim()

        fun hasClearance(cookieStr: String): Boolean =
            extractClearanceToken(cookieStr) != null

        fun isUsableClearance(
            cookieStr: String,
            previousToken: String?,
            title: String,
            pageLoaded: Boolean,
        ): Boolean {
            val token = extractClearanceToken(cookieStr) ?: return false
            if (token != previousToken) return true
            return pageLoaded && title.isNotBlank() && !isChallengeTitle(title)
        }

        fun isCloudflareOwnHost(url: String): Boolean {
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
                ?: return false
            return host == "cloudflare.com" || host.endsWith(".cloudflare.com")
        }

        fun newInstance(
            targetUrl: String,
            startExpanded: Boolean = false,
            webViewVisible: Boolean = true,
            onFinished: ((Boolean) -> Unit)? = null,
        ): CloudflareWebViewDialog = CloudflareWebViewDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, targetUrl)
                putBoolean(ARG_EXPANDED, startExpanded)
                putBoolean(ARG_VISIBLE, webViewVisible)
            }
            this.onFinished = onFinished
        }

        /**
         * Solve Cloudflare cookies with no UI shown. Creates a headless WebView,
         * loads the challenge URL, polls for cf_clearance, saves it + the solving
         * UA, then destroys the WebView. Used when the user enables "Hide Cloudflare
         * WebView" in settings.
         */
        fun solveCookiesHeadless(
            context: Context,
            targetUrl: String,
            onFinished: (Boolean) -> Unit
        ) {
            val activity = findActivity(context) ?: run {
                Log.e(TAG, "Headless CF solve: no Activity found")
                onFinished(false)
                return
            }
            if (activity.isFinishing || activity.isDestroyed) {
                onFinished(false)
                return
            }

            val targetHost = runCatching {
                val uri = Uri.parse(targetUrl)
                "${uri.scheme}://${uri.host}"
            }.getOrDefault(targetUrl)
            val targetDomain = runCatching { Uri.parse(targetHost).host }.getOrNull() ?: targetHost

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false

                    val originalUa = userAgentString ?: ""
                    var cleanUa = Regex("Version/[0-9.]+\\s*")
                        .replace(originalUa.replace("; wv", ""), "")
                    cleanUa = cleanUa.replace("Android TV", "Android")
                    if (cleanUa.contains("Android") && !cleanUa.contains("Mobile")) {
                        cleanUa = cleanUa.replace("Safari/", "Mobile Safari/")
                    }
                    userAgentString = cleanUa

                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                }
            }

            cookieManager.setAcceptThirdPartyCookies(webView, true)

            // Clear WebView cache to force fresh challenge
            webView.clearCache(true)
            webView.clearHistory()

            // Snapshot any cf_clearance that existed before the purge. The
            // purge is asynchronous, so a stale token can survive it; without
            // this snapshot the first poll would accept that stale token as
            // freshly minted and save an invalid cookie.
            var initialClearance = extractClearanceToken(
                runCatching { cookieManager.getCookie("$targetHost/") }.getOrNull().orEmpty()
            )
            Log.d(TAG, "Headless CF solve: initialClearance=${initialClearance?.take(12)}")

            // Purge any stale CF cookies from the jar to force a fresh challenge
            val domain = targetDomain.removePrefix("www.")
            val names = listOf("cf_clearance", "__cf_bm", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid", "__cflb")
            names.forEach { name ->
                cookieManager.setCookie(targetHost, "$name=; domain=$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                cookieManager.setCookie(targetHost, "$name=; domain=.$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                cookieManager.setCookie(targetHost, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            cookieManager.flush()

            // Force load the challenge page — for API endpoints, load root to get
            // the challenge. The load itself happens once below, after the client
            // is attached, so onPageFinished fires for this navigation.
            val challengeUrl = if (targetUrl.contains("/api/") || targetUrl.contains("api?")) {
                "$targetHost/"
            } else {
                targetUrl
            }

            // A full-size WebView is required: Cloudflare's challenge checks
            // that the page is rendered at a real size. The proven CloudStream
            // pattern is to attach it behind the app content at full size with
            // container alpha 0 — invisible to the user, still rendered by
            // Chromium. Without alpha=0 the page showed through translucent TV
            // home screens, which is exactly the overlap users reported.
            val container = object : FrameLayout(context) {
                // Never swallow touches the app content ignores.
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                alpha = 0f
                isClickable = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            container.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))

            // CloudStream's own resolver prefers the decorView root; fall back
            // to the content frame if the window is not available.
            val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
            val attachmentParent = (activity.window?.decorView as? ViewGroup) ?: contentRoot
            try {
                if (attachmentParent == null) {
                    Log.e(TAG, "Headless CF solve: no content root to attach WebView")
                    runCatching { webView.destroy() }
                    onFinished(false)
                    return
                }
                // Index 0 = behind whatever the Activity is showing.
                attachmentParent.addView(container, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Headless CF solve: failed to attach WebView: ${e.message}")
                runCatching { webView.destroy() }
                onFinished(false)
                return
            }

            val handler = Handler(Looper.getMainLooper())
            var cookiesSaved = false
            var hasNotified = false
            var pollElapsedMs = 0L
            val POLL_INTERVAL_MS = 1_000L
            val POLL_TIMEOUT_MS = 120_000L

            // One exit path for every outcome. WebView.destroy() can throw if
            // the Activity went away first (common on TV when the player is
            // closed mid-solve), so never let cleanup escape.
            fun teardown() {
                handler.removeCallbacksAndMessages(null)
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
                runCatching { container.removeView(webView) }
                runCatching { (container.parent as? ViewGroup)?.removeView(container) }
            }

            fun extractCookies(): String {
                cookieManager.flush()

                // Read cookies for the host that is actually solving the
                // challenge. Never merge other AnimePahe mirrors into one
                // map: cf_clearance is host-bound, and a stale token from a
                // mirror would overwrite the fresh one (last write wins),
                // producing a "saved" cookie that Cloudflare then rejects.
                val pageUrl = webView.url
                    ?.takeIf { it.isNotBlank() && !isCloudflareOwnHost(it) }
                    ?: targetUrl
                val host = runCatching { Uri.parse(pageUrl).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: targetDomain
                val bareHost = host.removePrefix("www.")

                val cookies = LinkedHashMap<String, String>()
                linkedSetOf("https://$bareHost/", "https://www.$bareHost/").forEach { url ->
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

            // Clearance already in the jar before this solve. Deleting it
            // up-front is racy (setCookie deletions are queued and can land
            // after Cloudflare writes the new token, killing the first
            // successful solve), so keep it; the poll accepts it only once
            // the page has loaded past the challenge (see isUsableClearance).
            // `initialClearance` was snapshotted before the purge above.

            val pollRunnable = object : Runnable {
                override fun run() {
                    if (cookiesSaved || hasNotified || activity.isFinishing || activity.isDestroyed) {
                        if (!hasNotified) {
                            hasNotified = true
                            teardown()
                            onFinished(false)
                        }
                        return
                    }

                    val cookieStr = extractCookies()
                    val title = webView.title ?: ""
                    Log.d(TAG, "Headless poll [$pollElapsedMs ms] title='$title' cookies → $cookieStr")

                    // A token can appear while the interstitial is still up;
                    // save only after the page has left the challenge (same as
                    // Phisher). A blank title, common on TV WebViews, is not a
                    // challenge title and is accepted.
                    val hasClearanceCookie = isUsableClearance(
                        cookieStr, initialClearance, title, false,
                    ) && !isChallengeTitle(title)

                    if (hasClearanceCookie) {
                        cookiesSaved = true
                        val currentUrl = webView.url
                            ?.takeIf { it.isNotBlank() && !isCloudflareOwnHost(it) }
                            ?: targetUrl
                        val solvedHost = runCatching { Uri.parse(currentUrl).host }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: targetDomain
                        val saveHost = solvedHost.removePrefix("www.")

                        // cf_clearance is host-bound: persist it only for the
                        // host that issued it. Copying it to the other mirrors
                        // is invalid there and would shadow a later fresh solve.
                        Settings.saveCookieForDomain(saveHost, cookieStr)

                        webView.settings.userAgentString
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Settings.saveCfUserAgent(it) }

                        Log.d(TAG, "Headless CF solve: ✅ Saved cookies for $saveHost")
                        hasNotified = true
                        teardown()
                        onFinished(true)
                        return
                    }

                    pollElapsedMs += POLL_INTERVAL_MS
                    if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                        Log.w(TAG, "Headless CF solve: ⏱️ Timed out after 120s")
                        hasNotified = true
                        teardown()
                        onFinished(false)
                        return
                    }

                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }

            // Remember the pre-solve clearance; see isUsableClearance for when
            // an unchanged token may be accepted. Already snapshotted before
            // the purge — do not overwrite it here with a post-purge read.

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!cookiesSaved && !hasNotified) {
                        handler.post(pollRunnable)
                    }
                }
            }

            // Load the challenge page exactly once, now that the client is
            // attached. Loading targetUrl here (as this used to) cancelled the
            // challenge navigation and, for API endpoints, loaded raw JSON.
            webView.loadUrl(challengeUrl)
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }

        private fun findActivity(context: Context): Activity? {
            var ctx: Context? = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }

    private var onFinished: ((Boolean) -> Unit)? = null
    private var targetUrl: String = ""
    private var startExpanded: Boolean = false
    private var webViewVisible: Boolean = true

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var hasNotified = false
    private var pollElapsedMs = 0L

    // cf_clearance value that was already in the jar before this solve. A
    // stale clearance must not be mistaken for the fresh one, and deleting it
    // up-front is racy (setCookie deletions are queued and can land after
    // Cloudflare writes the new token). So keep it and require a new value.
    private var initialClearance: String? = null

    // True once the visible WebView has completed a load. Used to accept the
    // pre-existing token only when the page actually got past the challenge.
    private var pageLoaded = false

    private val targetHost: String by lazy {
        runCatching {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(targetUrl)
    }

    /** Bare host (no scheme), for the `domain=` cookie attribute. */
    private val targetDomain: String by lazy {
        runCatching { Uri.parse(targetHost).host }.getOrNull() ?: targetHost
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            val cookieStr = extractCookies()
            val currentTitle = webView?.title ?: ""
            Log.d(TAG, "Poll [$pollElapsedMs ms] title='$currentTitle' cookies → $cookieStr")

            val hasClearanceCookie = isUsableClearance(
                cookieStr, initialClearance, currentTitle, pageLoaded,
            )
            if (hasClearanceCookie) {
                Log.d(TAG, "✅ Clearance cookie found! Saving.")
                saveCookiesAndDismiss(cookieStr)
                return
            }

            if (pollElapsedMs < POLL_TIMEOUT_MS) {
                scheduleNextPoll()
                return
            }

            Log.w(TAG, "⏱️ CF bypass timed out after 120s")
            notifyFinished(false)
            runCatching { dismissAllowingStateLoss() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString(ARG_URL).orEmpty()
        startExpanded = arguments?.getBoolean(ARG_EXPANDED) ?: false
        webViewVisible = arguments?.getBoolean(ARG_VISIBLE) ?: true
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Waiting for verification… (${pollElapsedMs / POLL_INTERVAL_MS}s)")

        if (pollElapsedMs >= 2_500L && !startExpanded) {
            dialog?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                window.setDimAmount(0.5f)
            }
            (dialog as? BottomSheetDialog)?.behavior?.let { behavior ->
                behavior.skipCollapsed = true
                behavior.peekHeight = ViewGroup.LayoutParams.MATCH_PARENT
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )

        if (startExpanded) {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            dialog.window?.setDimAmount(0.5f)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.peekHeight = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            dialog.window?.setDimAmount(0f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            dialog.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            dialog.behavior.skipCollapsed = false
            dialog.behavior.peekHeight = 0
        }

        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.layoutParams?.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        bottomSheet?.requestLayout()
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val screenH = requireContext().resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.7).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Invisible mode: skip all UI views, WebView runs in background.
        if (webViewVisible) {
            root.addView(TextView(requireContext()).apply {
                text = "🛡️ AnimePahe – Cloudflare Bypass"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 8)
            })

            statusText = TextView(requireContext()).apply {
                text = "Loading challenge page…"
                textSize = 13f
                setTextColor(Color.parseColor("#A0A0B0"))
                setPadding(0, 0, 0, 4)
            }
            root.addView(statusText)

            root.addView(TextView(requireContext()).apply {
                text = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
                textSize = 11f
                setTextColor(Color.parseColor("#707080"))
                setPadding(0, 0, 0, 12)
            })

            progressBar = ProgressBar(
                requireContext(),
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 12 }
            }
            root.addView(progressBar)

        }

        val webViewContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                webViewHeight,
            )
        }

        webView = buildWebView()
        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Only show the drawn mouse cursor when the WebView is visible.
        if (webViewVisible) {
            val cursorView = ImageView(requireContext())
            val cursorSize = (26f * resources.displayMetrics.density).toInt()
            cursorView.layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)

            val cursorPath = Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, 22f)
                lineTo(5.5f, 16.5f)
                lineTo(10.5f, 27f)
                lineTo(14.5f, 25f)
                lineTo(9.5f, 14.5f)
                lineTo(16.5f, 14.5f)
                close()
            }
            Matrix().apply {
                setScale(cursorSize / 30f, cursorSize / 30f)
                cursorPath.transform(this)
            }

            cursorView.setImageDrawable(object : Drawable() {
                private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    strokeJoin = Paint.Join.ROUND
                }

                override fun draw(canvas: Canvas) {
                    canvas.drawPath(cursorPath, fillPaint)
                    canvas.drawPath(cursorPath, strokePaint)
                }

                override fun setAlpha(alpha: Int) {
                    fillPaint.alpha = alpha
                    strokePaint.alpha = alpha
                }

                override fun setColorFilter(colorFilter: ColorFilter?) {
                    fillPaint.colorFilter = colorFilter
                    strokePaint.colorFilter = colorFilter
                }

                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            })
            cursorView.elevation = 100f
            webViewContainer.addView(cursorView)

            var cursorX = 0f
            var cursorY = 0f
            cursorView.post {
                if (cursorX == 0f && cursorY == 0f && webViewContainer.width > 0) {
                    cursorX = webViewContainer.width / 2f
                    cursorY = webViewContainer.height / 2f
                    cursorView.translationX = cursorX
                    cursorView.translationY = cursorY
                }
            }

            fun handleCursorKey(event: KeyEvent): Boolean {
                val view = webView ?: return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> cursorY = (cursorY - 40f).coerceAtLeast(0f)
                        KeyEvent.KEYCODE_DPAD_DOWN -> cursorY = (cursorY + 40f)
                            .coerceIn(0f, (view.height - 24f).coerceAtLeast(0f))
                        KeyEvent.KEYCODE_DPAD_LEFT -> cursorX = (cursorX - 40f).coerceAtLeast(0f)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX = (cursorX + 40f)
                            .coerceIn(0f, (view.width - 24f).coerceAtLeast(0f))
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            val time = SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(
                                time, time, MotionEvent.ACTION_DOWN,
                                cursorX + 2f, cursorY + 2f, 0,
                            )
                            val up = MotionEvent.obtain(
                                time, time + 100, MotionEvent.ACTION_UP,
                                cursorX + 2f, cursorY + 2f, 0,
                            )
                            view.dispatchTouchEvent(down)
                            view.dispatchTouchEvent(up)
                            down.recycle()
                            up.recycle()
                        }
                        else -> return false
                    }
                    cursorView.translationX = cursorX
                    cursorView.translationY = cursorY
                    return true
                }

                if (event.action == KeyEvent.ACTION_UP) {
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> true
                        else -> false
                    }
                }

                return false
            }

            dialog?.setOnKeyListener { _, _, event -> handleCursorKey(event) }
        }
        root.addView(webViewContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        webView?.let { cookieManager.setAcceptThirdPartyCookies(it, true) }
        cookieManager.flush()

        // Snapshot any cf_clearance that already existed before this solve.
        // The purge below is asynchronous (setCookie + flush are queued), so a
        // stale token can survive it. Without this snapshot the first poll at
        // 2s would treat that stale token as freshly minted and close the
        // dialog with a cookie Cloudflare had already rejected — the "says
        // done in 2 seconds but Settings shows invalid" bug on TV.
        initialClearance = extractClearanceToken(extractCookies())
        Log.d(TAG, "onViewCreated: initialClearance=${initialClearance?.take(12)}")

        // Purge ALL Cloudflare cookies from the jar to force a fresh challenge.
        // This is critical: if old cookies exist, Cloudflare won't issue a new challenge.
        val host = targetDomain.removePrefix("www.")
        val names = listOf("cf_clearance", "__cf_bm", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid", "__cflb", "cf_clearance", "cf_chl_prog", "cf_chl_rc_ni")
        names.forEach { name ->
            cookieManager.setCookie(targetHost, "$name=; domain=$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cookieManager.setCookie(targetHost, "$name=; domain=.$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cookieManager.setCookie(targetHost, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        cookieManager.flush()

        // Clear WebView cache to force fresh load
        webView?.clearCache(true)
        webView?.clearHistory()

        // Force load the challenge page — for API endpoints, load root to get challenge
        val challengeUrl = if (targetUrl.contains("/api/") || targetUrl.contains("api?")) {
            "$targetHost/"
        } else {
            targetUrl
        }
        webView?.loadUrl(challengeUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        // Cloudflare sets cf_clearance asynchronously after JS runs — check
        // at multiple intervals to catch it early regardless of timing.
        val checkDelays = longArrayOf(1000, 2500, 5000, 8000, 12000)
        checkDelays.forEach { delay ->
            handler.postDelayed({
                if (!cookiesSaved) {
                    webView?.let { wv ->
                        checkForClearance(wv.url, wv.title ?: "")
                    }
                }
            }, delay)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Invisible mode: WebView solves in the background, user sees nothing.
            if (!webViewVisible) {
                visibility = View.GONE
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            blockNetworkImage = false
            blockNetworkLoads = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            // Use the raw WebView UA with minimal cleaning.
            // Over-cleaning makes Cloudflare fingerprint the client as suspicious
            // and it never issues a cf_clearance cookie.
            userAgentString = userAgentString ?: ""
            var cleanUa = Regex("Version/[0-9.]+\\s*")
                .replace(userAgentString.replace("; wv", ""), "")
            cleanUa = cleanUa.replace("Android TV", "Android")
            if (cleanUa.contains("Android") && !cleanUa.contains("Mobile")) {
                cleanUa = cleanUa.replace("Safari/", "Mobile Safari/")
            }
            userAgentString = cleanUa

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) updateStatus("Loading… $newProgress%")
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cookiesSaved) return

                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished title='$title' url=$url")
                pageLoaded = true
                checkForClearance(url, title)
            }
        }

        return wv
    }

    /**
     * Check for cf_clearance using both CookieManager and direct JavaScript
     * document.cookie access. Cloudflare sometimes sets cookies via JS that
     * CookieManager doesn't immediately see, so we read document.cookie too.
     */
    private fun checkForClearance(url: String?, title: String) {
        val cookieStr = extractCookies()
        val hasClearanceCookie = isUsableClearance(
            cookieStr, initialClearance, title, pageLoaded,
        )
        val isChallenge = isChallengeTitle(title)

        Log.d(TAG, "check title='$title' url=$url clearance=$hasClearanceCookie challenge=$isChallenge cookies=$cookieStr")

        if (hasClearanceCookie && !isChallenge) {
            Log.d(TAG, "Clearance verified! Saving cookies.")
            saveCookiesAndDismiss(cookieStr)
            return
        }

        // Also probe document.cookie directly via JavaScript — Cloudflare
        // may set cf_clearance via document.cookie which CookieManager
        // might not expose right away.
        webView?.evaluateJavascript("document.cookie") { cookieResult ->
            val jsCookie = cookieResult ?: ""
            val jsHasClearance = isUsableClearance(
                jsCookie, initialClearance, title, pageLoaded,
            )
            Log.d(TAG, "JS document.cookie='$jsCookie' clearance=$jsHasClearance")
            if (jsHasClearance && !isChallenge) {
                saveCookiesAndDismiss(jsCookie)
            }
        }

        if (isChallenge) {
            updateStatus("🔄 Challenge active – solve the CAPTCHA above")
        } else {
            updateStatus("⏳ Waiting for verification…")
        }
    }

    private fun extractCookies(): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        // Read cookies for the host that is actually solving the challenge —
        // never merge other AnimePahe mirrors into one map. cf_clearance is
        // host-specific, and a stale token from a mirror would overwrite the
        // fresh one (last write wins), producing a "saved" cookie that
        // Cloudflare then rejects.
        val pageUrl = webView?.url
            ?.takeIf { it.isNotBlank() && !isCloudflareOwnHost(it) }
            ?: targetUrl
        val host = runCatching { Uri.parse(pageUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: targetDomain
        val bareHost = host.removePrefix("www.")

        // CookieManager needs the URL form; cover both www and apex.
        val cookies = LinkedHashMap<String, String>()
        linkedSetOf("https://$bareHost/", "https://www.$bareHost/").forEach { url ->
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

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        if (!hasClearance(cookieStr) && !startExpanded) {
            Log.w(TAG, "saveCookiesAndDismiss called without clearance cookie, skipping")
            return
        }

        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        // Save only the host that minted the clearance. Copying it to the
        // other AnimePahe mirrors is useless (cf_clearance is host-bound) and
        // lets a stale token masquerade as valid there.
        val solvedHost = runCatching {
            val uri = Uri.parse(
                webView?.url?.takeIf { it.isNotBlank() && !isCloudflareOwnHost(it) } ?: targetUrl
            )
            uri.host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: targetDomain
        val saveHost = solvedHost.removePrefix("www.")
        Settings.saveCookieForDomain(saveHost, cookieStr)
        Log.d(TAG, "Saved cookie for $saveHost")

        // Save the cleaned UA that was actually used to solve the challenge.
        webView?.settings?.userAgentString
            ?.takeIf { it.isNotBlank() }
            ?.let { Settings.saveCfUserAgent(it) }

        Log.d(TAG, "✅ Saved cookies for $saveHost: $cookieStr")
        updateStatus("✅ Done! Cloudflare bypassed.")
        notifyFinished(true)

        webView?.postDelayed({
            if (isAdded) {
                runCatching { dismissAllowingStateLoss() }
            }
        }, 500L)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        handler.removeCallbacks(cookiePollRunnable)
        notifyFinished(cookiesSaved)
    }

    private fun notifyFinished(success: Boolean) {
        if (hasNotified) return
        hasNotified = true
        onFinished?.invoke(success)
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = View.VISIBLE
                statusText?.setTextColor(Color.parseColor("#A0A0B0"))
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        statusText = null
        progressBar = null
        super.onDestroyView()
    }
}