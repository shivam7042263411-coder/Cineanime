package com.megix

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.megix.settings.SettingsDialog

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
    }
}
