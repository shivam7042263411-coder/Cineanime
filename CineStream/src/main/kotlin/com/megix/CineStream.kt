package com.megix

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

        // ── Auto Cloudflare bypass on load ─────────────────────────
        // If auto-bypass is enabled and there's no saved AnimePahe cookie
        // yet, pop the bypass WebView after the app settles so the user
        // can solve Cloudflare once — after that, saved cookies make
        // playback work directly without ever opening settings manually.
        if (Settings.cloudflareAutoBypassOnLoad() &&
            !Settings.hasSavedCookieForDomain("animepahe.pw") &&
            !Settings.hasSavedCookieForDomain("animepahe.org") &&
            !Settings.hasSavedCookieForDomain("animepahe.com")) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    showCloudflareBypass(context, "https://animepahe.pw/") {
                        Log.d("CineStreamAutoBypass", "Cookies changed, dismissing bypass dialog")
                    }
                } catch (e: Exception) {
                    Log.e("CineStreamAutoBypass", "Failed to show bypass dialog: ${e.message}")
                }
            }, 5000) // 5 second delay so the Activity is fully ready for Dialogs
        }
    }
}
