package com.megix

import android.app.Activity
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.megix.settings.Settings
import com.megix.settings.SettingsWebView.showCloudflareBypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

object CineStreamExtractors {

    private const val CF_LOG_TAG = "CineStreamCloudflare"

    // ── CF Bypass Interceptor (OkHttp) ──────────────────────────────────
    // Injects saved CF cookies + UA into every request, matching Phisher's
    // CFBypassInterceptor exactly.
    private object CFBypassInterceptor : Interceptor {
        @JvmField
        val instance = this

        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .removeHeader("X-Requested-With")
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")

            val savedUa = Settings.getCfUserAgent()
            if (savedUa.isNotEmpty()) {
                builder.header("User-Agent", savedUa)
            }

            val savedCookies = Settings.getCfCookies()
            if (savedCookies.isNotEmpty()) {
                val existingCookie = original.header("Cookie") ?: ""

                val existingParts = existingCookie.split(";").toMutableList()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { !it.startsWith("cf_clearance=", ignoreCase = true) }

                val freshParts = savedCookies.split(";").toMutableList()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val merged = (existingParts + freshParts).distinct()
                builder.header("Cookie", merged.joinToString("; "))
            }

            return chain.proceed(builder.build())
        }
    }

    // ── cfGet / cfPost with CF bypass ───────────────────────────────────

    suspend fun cfGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
    ): NiceResponse {
        val headersWithAgent = headers.toMutableMap()
        if (!headersWithAgent.containsKey("User-Agent")) {
            headersWithAgent["User-Agent"] = Settings.getCfUserAgent().takeIf { it.isNotEmpty() }
                ?: "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        }

        val allHeaders = headersWithAgent.toMutableMap()
        val savedCookies = Settings.getCfCookies()
        if (savedCookies.isNotEmpty()) {
            val existingCookie = allHeaders["Cookie"] ?: ""
            val existingParts = existingCookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .filter { !it.startsWith("cf_clearance=", ignoreCase = true) }
            val freshParts = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val merged = (existingParts + freshParts).distinct()
            allHeaders["Cookie"] = merged.joinToString("; ")
        }

        return app.get(
            url,
            headers = allHeaders,
            interceptor = CFBypassInterceptor.instance,
            allowRedirects = allowRedirects,
        )
    }

    suspend fun cfPost(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        json: Any? = null,
        allowRedirects: Boolean = true,
    ): NiceResponse {
        val headersWithAgent = headers.toMutableMap()
        if (!headersWithAgent.containsKey("User-Agent")) {
            headersWithAgent["User-Agent"] = Settings.getCfUserAgent().takeIf { it.isNotEmpty() }
                ?: "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        }

        val allHeaders = headersWithAgent.toMutableMap()
        val savedCookies = Settings.getCfCookies()
        if (savedCookies.isNotEmpty()) {
            val existingCookie = allHeaders["Cookie"] ?: ""
            val existingParts = existingCookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .filter { !it.startsWith("cf_clearance=", ignoreCase = true) }
            val freshParts = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val merged = (existingParts + freshParts).distinct()
            allHeaders["Cookie"] = merged.joinToString("; ")
        }

        return app.post(
            url,
            headers = allHeaders,
            data = data,
            json = json,
            interceptor = CFBypassInterceptor.instance,
            allowRedirects = allowRedirects,
        )
    }

    // ── Cookie validation ───────────────────────────────────────────────

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        if (response.code in listOf(403, 503)) return true
        if (response.headers["cf-mitigated"]?.contains("challenge", ignoreCase = true) == true) return true

        val body = runCatching { response.text }.getOrNull() ?: return false
        val lower = body.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("cf-chl") ||
            lower.contains("attention required") ||
            lower.contains("enable javascript and cookies") ||
            lower.contains("verifying you are human") ||
            lower.contains("checking your browser") ||
            lower.contains("one more step") ||
            lower.contains("cf-mitigated")
    }

    /**
     * One cheap request that answers: does AnimePahe currently accept the
     * saved cookies? Follows redirects so a mirror hop into a challenge is
     * still detected. Returns true (fail open) on network errors so the app
     * isn't blocked offline.
     */
    suspend fun isCookieValid(url: String = "https://animepahe.pw/"): Boolean {
        val headers = mapOf(
            "User-Agent" to Settings.getCfUserAgent(),
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to url,
        )
        val response = runCatching {
            app.get(url, headers = headers, interceptor = CFBypassInterceptor.instance, allowRedirects = true)
        }.getOrNull() ?: return true

        val valid = !isCloudflarePage(response)
        Log.d(CF_LOG_TAG, "isCookieValid: $url valid=$valid code=${response.code}")
        return valid
    }

    // ── Current foreground Activity ─────────────────────────────────────

    /** Context stored at plugin load time (usually the Application context). */
    var context: android.content.Context? = null

    /** Current foreground Activity: the plugin's stored context is the app context. */
    private fun currentActivity(): Activity? {
        (context as? Activity)?.let {
            if (!it.isFinishing && !it.isDestroyed) return it
        }
        return runCatching { CommonActivity.activity }
            .getOrNull()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    /** True once an Activity exists, so app-open validation can wait for it. */
    fun hasForegroundActivity(): Boolean = currentActivity() != null

    // ── Pre-flight probe ────────────────────────────────────────────────

    /**
     * Proactive pre-flight probe: fires before every playback attempt.
     * If Cloudflare challenges (cookies expired/invalid), shows the bypass
     * WebView so the user can solve immediately.
     */
    suspend fun probeAndResolveIfNeeded(
        url: String = "https://animepahe.pw/",
        force: Boolean = false,
    ): Boolean {
        Log.d(CF_LOG_TAG, "probeAndResolveIfNeeded: validating $url")

        // Never load an API/JSON endpoint in the solver: it must fetch a page
        // that can present the challenge. Site root is the safe target.
        val solveUrl = "https://animepahe.pw/"

        if (isCookieValid(solveUrl)) {
            Log.d(CF_LOG_TAG, "probeAndResolveIfNeeded: cookie valid, skipping bypass")
            return false
        }

        Log.d(CF_LOG_TAG, "probeAndResolveIfNeeded: Cloudflare detected, showing bypass WebView")

        val ctx = currentActivity()
        if (ctx == null) {
            Log.w(CF_LOG_TAG, "probeAndResolveIfNeeded: no Activity available for bypass")
            return false
        }

        // Show the bypass WebView on the main thread. The callback saves
        // cookies when the solve succeeds; we don't need to wait for it.
        withContext(Dispatchers.Main.immediate) {
            showCloudflareBypass(
                ctx,
                solveUrl,
                webViewVisible = Settings.isCfWebviewEnabled(),
            ) { success ->
                Log.d(CF_LOG_TAG, "probeAndResolveIfNeeded: bypass finished, success=$success")
            }
        }

        return true
    }

    fun invalidateCookieCache() {
        // No-op: validation state is not persisted in memory
    }

    // ── Stub invoke functions (providers configured via ProviderRegistry) ─

    suspend fun invokeAllSources(
        res: Any,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(CF_LOG_TAG, "invokeAllSources: no providers configured")
    }

    suspend fun invokeAllAnimeSources(
        res: Any,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(CF_LOG_TAG, "invokeAllAnimeSources: no providers configured")
    }
}
