package com.megix.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import java.util.Locale

/**
 * Phisher's AnimePahe Cloudflare WebView, copied into CineStream.
 *
 * The UI, WebView settings, cookie purge/poll/save/dismiss flow and TV cursor
 * are the same as `com.phisher98.CloudflareWebViewDialog` (GPLv3). Only the
 * persistence target is CineStream's: cookies and the solving UA go through
 * [Settings] so the existing AnimePahe provider and request plumbing keep
 * working unchanged.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class CloudflareWebViewDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CFWebViewDialog"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val POLL_TIMEOUT_MS = 120_000L
        private const val ARG_URL = "target_url"
        private const val ARG_EXPANDED = "start_expanded"

        private val CHALLENGE_TITLES = listOf(
            "just a moment",
            "just a moment...",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "one more step",
        )

        fun newInstance(
            targetUrl: String,
            startExpanded: Boolean = false,
            onFinished: ((Boolean) -> Unit)? = null,
        ): CloudflareWebViewDialog = CloudflareWebViewDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, targetUrl)
                putBoolean(ARG_EXPANDED, startExpanded)
            }
            this.onFinished = onFinished
        }

        fun isChallengeTitle(title: String): Boolean {
            val lower = title.lowercase(Locale.ROOT)
            return CHALLENGE_TITLES.any { lower.contains(it) }
        }

        fun extractClearanceToken(cookieStr: String): String? =
            Regex("cf_clearance=([^;]+)").find(cookieStr)
                ?.groupValues?.getOrNull(1)?.trim()

        fun hasClearance(cookieStr: String): Boolean =
            extractClearanceToken(cookieStr)?.length?.let { it >= 10 } == true
    }

    private var onFinished: ((Boolean) -> Unit)? = null
    private var targetUrl: String = ""
    private var startExpanded: Boolean = false

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var hasNotified = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        runCatching {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(targetUrl)
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            val cookieStr = extractCookies()
            val currentTitle = webView?.title ?: ""
            Log.d(TAG, "Poll [$pollElapsedMs ms] title='$currentTitle' cookies → $cookieStr")

            val hasClearanceCookie = hasClearance(cookieStr)
            val isChallenge = isChallengeTitle(currentTitle)
            if (hasClearanceCookie && !isChallenge) {
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
                    KeyEvent.KEYCODE_DPAD_DOWN -> cursorY = (cursorY + 40f).coerceAtMost(view.height - 24f)
                    KeyEvent.KEYCODE_DPAD_LEFT -> cursorX = (cursorX - 40f).coerceAtLeast(0f)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX = (cursorX + 40f).coerceAtMost(view.width - 24f)
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
        root.addView(webViewContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        webView?.let { cookieManager.setAcceptThirdPartyCookies(it, true) }
        cookieManager.flush()
        purgeCfCookiesFromWebViewJar()
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
                val cookieStr = extractCookies()
                val hasClearanceCookie = hasClearance(cookieStr)
                val isChallenge = isChallengeTitle(title)

                if (hasClearanceCookie && !isChallenge) {
                    Log.d(TAG, "onPageFinished: Clearance verified! Saving cookies.")
                    saveCookiesAndDismiss(cookieStr)
                    return
                }

                if (isChallenge) {
                    updateStatus("🔄 Challenge active – solve the CAPTCHA above")
                } else {
                    updateStatus("⏳ Waiting for verification…")
                }
            }
        }

        return wv
    }

    private fun extractCookies(): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val urls = linkedSetOf<String>()
        webView?.url?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
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
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            cookies[name] = value
                        }
                    }
                }
            }
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun purgeCfCookiesFromWebViewJar() {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            val hosts = linkedSetOf(
                targetHost,
                "https://animepahe.pw",
                "https://animepahe.org",
                "https://animepahe.com",
            )

            hosts.forEach { host ->
                val url = "$host/"
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

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        if (!hasClearance(cookieStr) && !startExpanded) {
            Log.w(TAG, "saveCookiesAndDismiss called without clearance cookie, skipping")
            return
        }

        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        val currentUrl = webView?.url ?: targetUrl
        val currentHost = runCatching {
            val uri = Uri.parse(currentUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(targetHost)

        Settings.saveCookieForDomain(currentHost, cookieStr)
        webView?.settings?.userAgentString
            ?.takeIf { it.isNotBlank() }
            ?.let { Settings.saveCfUserAgent(it) }

        Log.d(TAG, "✅ Saved cookies for $currentHost: $cookieStr")
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
