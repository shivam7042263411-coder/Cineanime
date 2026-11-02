package com.megix

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.megix.settings.Settings
import com.megix.settings.SettingsWebView.showCloudflareBypass
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
open class CineStream: Plugin() {
    override fun load(context: Context) {

        // Load dynamic API URLs once
        runBlocking { init() }

        // Seed seen-providers on every load so reinstalls/updates
        // don't treat existing providers as new
        Settings.initSeenProviders()

        if (getKey<Boolean>(Settings.PROVIDER_CINESTREAM) ?: true) {
            registerMainAPI(CineStreamProvider())
        }

        if (getKey<Boolean>(Settings.PROVIDER_SIMKL) ?: true) {
            registerMainAPI(CineSimklProvider())
        }

        if (getKey<Boolean>(Settings.PROVIDER_TMDB) ?: true) {
            registerMainAPI(CineTmdbProvider())
        }

        if (getKey<Boolean>(Settings.PROVIDER_ANIME) ?: true) {
            registerMainAPI(CineAnimeProvider())
        }

        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(Wootly())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Videostr())
        registerExtractorAPI(Streameeeeee())
        registerExtractorAPI(PpzjYoutube())
        registerExtractorAPI(Asianload())
        registerExtractorAPI(Embedload())
        registerExtractorAPI(FlixCloud())
        registerExtractorAPI(VibePlayer())
        registerExtractorAPI(Bibiemb())
        registerExtractorAPI(Vivibebe())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Otakuvid())
        registerExtractorAPI(Otakuhg())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(Modiplay())
        registerExtractorAPI(Iqsmartgames())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())

        this.openSettings = { ctx: Context ->
            Settings.showSettingsDialog(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }

        // Store context (Activity) for Cloudflare bypass dialogs during playback
        CineStreamExtractors.context = context

        // ── Auto Cloudflare bypass popup ─────────────────────────────────────
        // If no AnimePahe cookies are saved yet, pop the bypass WebView so the
        // user can solve Cloudflare once. After that, saved cookies restore
        // automatically on every launch and playback works directly.
        val needsBypass = listOf("animepahe.pw", "animepahe.org", "animepahe.com")
            .none { Settings.hasSavedCookieForDomain(it) }

        if (needsBypass && context is Activity) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    showCloudflareBypass(context, "https://animepahe.pw/") {
                        Log.d("CineStreamAutoBypass", "Cookies saved via bypass dialog")
                    }
                } catch (e: Throwable) {
                    Log.e("CineStreamAutoBypass", "Failed to show bypass dialog: ${e.message}")
                }
            }, 5000) // 5s delay so the Activity is fully ready for Dialogs
        }
    }
}
