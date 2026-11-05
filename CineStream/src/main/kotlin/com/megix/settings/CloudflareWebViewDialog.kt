package com.megix.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import java.util.Locale
import kotlin.text.Regex
import kotlin.jvm.internal.Ref

/**
 * Phisher's AnimePahe Cloudflare WebView Dialog.
 *
 * Two modes:
 *   VISIBLE: BottomSheetDialogFragment with a 70% WebView — user solves CAPTCHA.
 *   INVISIBLE: Full-size alpha=0 WebView attached behind the app content
 *              (CloudStream's proven pattern). Solves silently, user sees nothing.
 *
 * The invisible WebView uses dispatchTouchEvent=false so it never swallows taps
 * the app content ignores. alpha=0 keeps it invisible while Chromium still
 * renders and runs the Cloudflare challenge.
 *
 * On TV, the invisible mode is the default. If Cloudflare serves an interactive
 * checkbox a headless WebView cannot press, the solve simply fails silently —
 * no visible popup ever appears over the player.
 */
internal class CloudflareWebViewDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CFWebViewDialog"
        private const val POLL_INTERVAL_MS = 1000L
        private const val POLL_TIMEOUT_MS = 30000L // fail fast, not 2 minutes

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

        fun newInstance(
            targetUrl: String,
            webViewVisible: Boolean = true,
        ): CloudflareWebViewDialog = CloudflareWebViewDialog().apply {
            arguments = Bundle().apply {
                putString("target_url", targetUrl)
                putBoolean("web_view_visible", webViewVisible)
            }
        }

        /**
         * Solve Cloudflare cookies with no UI shown. Attaches a full-size alpha=0
         * WebView behind the Activity content at index 0. Polls for cf_clearance,
         * saves it + the solving UA, then destroys the WebView.
         */
        fun solveCookiesHeadless(
            activity: Activity,
            targetUrl: String,
            onFinished: (Boolean) -> Unit
        ) {
            if (activity.isFinishing || activity.isDestroyed) {
                onFinished(false)
                return@solveCookiesHeadless
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val targetHost = try {
                val uri = Uri.parse(targetUrl)
                "${uri.scheme}://${uri.host}"
            } catch (_: Exception) {
                targetUrl
            }

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false

                    var ua = userAgentString ?: ""
                    ua = ua.replace("; wv", "").replace("Android TV", "Android")
                    userAgentString = ua

                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                }
            }

            cookieManager.setAcceptThirdPartyCookies(webView, true)

            val container = object : FrameLayout(activity) {
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

            val attachmentParent = (activity.window?.decorView as? ViewGroup)
                ?: activity.findViewById<ViewGroup>(android.R.id.content)
            if (attachmentParent == null) {
                Log.e(TAG, "Headless CF solve: no content root to attach WebView")
                onFinished(false)
                return@solveCookiesHeadless
            }
            attachmentParent.addView(container, 0)

            var cookiesSavedLocal = false
            var notified = false
            val handler = Handler(Looper.getMainLooper())
            var pollElapsedMs = 0L
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (cookiesSavedLocal || notified || activity.isFinishing || activity.isDestroyed) {
                        if (!notified) {
                            notified = true
                            handler.removeCallbacks(this)
                            webView.stopLoading()
                            webView.destroy()
                            container.removeView(webView)
                            try { (container.parent as? ViewGroup)?.removeView(container) } catch (_: Exception) {}
                            onFinished(false)
                        }
                        return@run
                    }

                    cookieManager.flush()
                    val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""

                    if (Regex("cf_clearance=[^;]{15,}").containsMatchIn(cookieStr)) {
                        cookiesSavedLocal = true
                        notified = true
                        handler.removeCallbacks(this)
                        webView.stopLoading()
                        webView.destroy()
                        container.removeView(webView)
                        try { (container.parent as? ViewGroup)?.removeView(container) } catch (_: Exception) {}

                        Settings.saveCfCookies(cookieStr)
                        Settings.saveCfCookieHost(targetHost)
                        webView.settings.userAgentString?.let { ua ->
                            if (ua.isNotBlank()) Settings.saveCfUserAgent(ua)
                        }

                        Log.d(TAG, "Headless CF solve: ✅ Saved cookies for $targetHost")
                        onFinished(true)
                        return@run
                    }

                    pollElapsedMs += POLL_INTERVAL_MS
                    if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                        Log.w(TAG, "Headless CF solve: ⏱️ Timed out after 30s")
                        notified = true
                        handler.removeCallbacks(this)
                        webView.stopLoading()
                        webView.destroy()
                        container.removeView(webView)
                        try { (container.parent as? ViewGroup)?.removeView(container) } catch (_: Exception) {}
                        onFinished(false)
                        return@run
                    }

                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!cookiesSavedLocal && !notified) {
                        handler.post(pollRunnable)
                    }
                }
            }

            webView.loadUrl(targetUrl)
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private var onFinished: ((Boolean) -> Unit)? = null
    private var targetUrl: String = ""
    private var webViewVisible: Boolean = true

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            targetUrl
        }
    }

    private val cookiePollRunnable = Runnable {
        if (cookiesSaved || !isAdded) return@Runnable

        CookieManager.getInstance().flush()
        val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""

        Log.d(TAG, "Poll [$pollElapsedMs ms] cookies for $targetHost → $cookieStr")

        if (Regex("cf_clearance=[^;]{15,}").containsMatchIn(cookieStr)) {
            saveCookiesAndDismiss(cookieStr)
            return@Runnable
        }

        if (cookieStr.contains("__ddg2_") || cookieStr.contains("__ddg1_")) {
            if (pollElapsedMs >= 60000L) {
                saveCookiesAndDismiss(cookieStr)
            } else {
                scheduleNextPoll()
            }
            return@Runnable
        }

        if (pollElapsedMs >= POLL_TIMEOUT_MS) {
            updateStatus("⏱️ Timed out. Try solving then tap Bypass again.")
            return@Runnable
        }

        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Waiting for cookies… (${pollElapsedMs / 1000}s)")

        if (pollElapsedMs >= POLL_INTERVAL_MS && webViewVisible) {
            val dialog = getDialog()
            val window = dialog?.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window?.setDimAmount(0.5f)

            val bottomSheetDialog = dialog as? BottomSheetDialog
            val behavior = bottomSheetDialog?.behavior
            behavior?.setSkipCollapsed(true)
            behavior?.setPeekHeight(ViewGroup.LayoutParams.MATCH_PARENT)
            behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }

        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    fun setOnFinished(callback: (Boolean) -> Unit) {
        onFinished = callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString("target_url").orEmpty()
        webViewVisible = arguments?.getBoolean("web_view_visible") ?: true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        if (webViewVisible) {
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
        val dialog = getDialog()
        val window = dialog?.window
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet?.requestLayout()
    }

    @SuppressLint("SetJavaScriptEnabled")
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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

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

            val cursorView = View(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(28, 28)
            }
            val cursorBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8022C55E"))
                setStroke(2, Color.parseColor("#22C55E"))
            }
            cursorView.background = cursorBg
            cursorView.elevation = 100f

            val cursorX = Ref.FloatRef()
            val cursorY = Ref.FloatRef()
            cursorView.post {
                if (cursorX.element == 0f && cursorY.element == 0f) {
                    cursorX.element = 140f
                    cursorY.element = 140f
                    cursorView.translationX = cursorX.element
                    cursorView.translationY = cursorY.element
                }
            }

            dialog?.setOnKeyListener { _, keyCode, event ->
                val v = webView ?: false
                if (v == false) return@setOnKeyListener false
                val webViewInst = webView!!
                if (event.action == KeyEvent.ACTION_DOWN) {
                    var handled = false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            cursorY.element = (cursorY.element - 50f).coerceAtLeast(0f)
                            if (cursorY.element <= 10f) webViewInst.scrollBy(0, -50)
                            handled = true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            cursorY.element = (cursorY.element + 50f).coerceAtMost(webViewInst.height - 24f)
                            if (cursorY.element >= webViewInst.height - 34f) webViewInst.scrollBy(0, 50)
                            handled = true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            cursorX.element = (cursorX.element - 50f).coerceAtLeast(0f)
                            handled = true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            cursorX.element = (cursorX.element + 50f).coerceAtMost(webViewInst.width - 24f)
                            handled = true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            val time = SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, cursorX.element + 12f, cursorY.element + 12f, 0)
                            val up = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, cursorX.element + 12f, cursorY.element + 12f, 0)
                            webViewInst.dispatchTouchEvent(down)
                            webViewInst.dispatchTouchEvent(up)
                            down.recycle()
                            up.recycle()
                            handled = true
                        }
                    }
                    if (handled) {
                        cursorView.translationX = cursorX.element
                        cursorView.translationY = cursorY.element
                    }
                    return@setOnKeyListener handled
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    return@setOnKeyListener when (keyCode) {
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
                return@setOnKeyListener false
            }

            val cursorContainer = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            }
            cursorContainer.addView(cursorView)
            root.addView(cursorContainer)
        }

        val wvContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
        }

        webView = buildWebView()
        wvContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(wvContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        val host = targetHost.removePrefix("www.")
        val names = listOf("cf_clearance", "__cf_bm", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid")
        names.forEach { name ->
            cm.setCookie(targetHost, "$name=; domain=$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(targetHost, "$name=; domain=.$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(targetHost, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        cm.flush()

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
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

            var ua = userAgentString ?: ""
            ua = ua.replace("; wv", "").replace("Android TV", "Android")
            userAgentString = ua

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) {
                    updateStatus("Loading… $newProgress%")
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cookiesSaved) return

                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished title='$title' url=$url")

                if (isChallengeTitle(title)) {
                    updateStatus("🔄 Challenge active – solve the CAPTCHA above")
                    return
                }

                updateStatus("✏️ Page loaded – checking cookies…")
                CookieManager.getInstance().flush()

                val cookiesFromTarget = CookieManager.getInstance().getCookie(targetHost) ?: ""
                var bestCookies: String? = null

                if (Regex("cf_clearance=[^;]{15,}").containsMatchIn(cookiesFromTarget)) {
                    bestCookies = cookiesFromTarget
                } else if (url != null) {
                    try {
                        val uri = Uri.parse(url)
                        val cookiesFromUrl = CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}") ?: ""
                        if (Regex("cf_clearance=[^;]{15,}").containsMatchIn(cookiesFromUrl)) {
                            bestCookies = cookiesFromUrl
                        }
                    } catch (_: Exception) {}
                }

                if (bestCookies == null && webViewVisible) {
                    bestCookies = cookiesFromTarget
                }

                if (bestCookies != null) {
                    handler.removeCallbacks(cookiePollRunnable)
                    saveCookiesAndDismiss(bestCookies)
                }
            }
        }

        return wv
    }

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        Settings.saveCfCookies(cookieStr)
        Settings.saveCfCookieHost(targetHost)

        webView?.settings?.userAgentString?.let { ua ->
            if (ua.isNotBlank()) Settings.saveCfUserAgent(ua)
        }

        Log.d(TAG, "✅ Saved cookies: $cookieStr")
        updateStatus("✅ Done! Cookies saved.")

        webView?.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                runCatching { dismissAllowingStateLoss() }
            }
        }, 1500L)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        handler.removeCallbacks(cookiePollRunnable)
        if (!cookiesSaved) {
            onFinished?.invoke(false)
        }
    }

    private fun updateStatus(msg: String) {
        val activity = requireActivity()
        activity.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = android.view.View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = android.view.View.VISIBLE
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

    private fun runCatching(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {}
    }
}
