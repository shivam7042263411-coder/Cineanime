package com.megix

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.megix.settings.SettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

@CloudstreamPlugin
open class CineStream : Plugin() {

    companion object {
        @JvmStatic
        fun getCfCookies(): String =
            getKey<String>("ANIMEPAHE_CF_COOKIES") ?: ""

        @JvmStatic
        fun setCfCookies(value: String) {
            setKey("ANIMEPAHE_CF_COOKIES", value)
        }

        @JvmStatic
        fun getCfUserAgent(): String =
            getKey<String>("ANIMEPAHE_CF_USER_AGENT") ?: ""

        @JvmStatic
        fun setCfUserAgent(value: String) {
            setKey("ANIMEPAHE_CF_USER_AGENT", value)
        }

        @JvmStatic
        fun getCfCookieHost(): String =
            getKey<String>("ANIMEPAHE_CF_COOKIE_HOST") ?: ""

        @JvmStatic
        fun setCfCookieHost(value: String) {
            setKey("ANIMEPAHE_CF_COOKIE_HOST", value)
        }

        @JvmStatic
        fun getCfWebviewEnabled(): Boolean =
            getKey<Boolean>("ANIMEPAHE_CF_WEBVIEW_ENABLED") ?: true

        @JvmStatic
        fun setCfWebviewEnabled(value: Boolean) {
            setKey("ANIMEPAHE_CF_WEBVIEW_ENABLED", value)
        }
    }

    override fun load(context: Context) {
        // Store context so Cloudflare bypass can find the foreground Activity
        CineStreamExtractors.context = context

        // Register MainAPI (CineAnime) and Extractor APIs (Kwik, Pahe)
        registerMainAPI(CineAnimeProvider())
        // Kwik and Pahe extractors temporarily disabled - will be re-enabled
        // once their invoke functions are implemented in ProviderRegistry.
        // registerExtractorAPI(Kwik())
        // registerExtractorAPI(Pahe())

        // Open settings dialog
        this.openSettings = { ctx: Context ->
            if (ctx is AppCompatActivity) {
                SettingsDialog.show(ctx) { }
            }
        }

        // ── Validate the saved Cloudflare cookie on app open ──────────────
        // Age alone is not proof: cf_clearance can be revoked or expire
        // server-side while the timestamp is still young. A cookie that
        // merely *exists* used to skip both the bypass and the playback
        // probe, which left "Saved" cookies scraping nothing. Validate with
        // one real request instead, off the main thread, and only open the
        // bypass when the probe is actually challenged.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Plugin.load runs while the app is still starting, so the
            // stored context is the Application. Wait for a real Activity
            // instead of probing with no way to show the bypass (that is
            // why the WebView sometimes never appeared at app open).
            var waited = 0L
            while (!CineStreamExtractors.hasForegroundActivity() && waited < 20_000L) {
                delay(500)
                waited += 500
            }
            delay(2_000) // let the Activity settle before any dialog
            runCatching {
                CineStreamExtractors.probeAndResolveIfNeeded("https://animepahe.pw/")
            }.onFailure {
                Log.e("CineStreamAutoBypass", "Cookie validation failed: ${it.message}")
            }
        }
    }
}
