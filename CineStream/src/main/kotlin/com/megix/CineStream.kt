package com.megix

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.megix.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
open class CineStream: Plugin() {
    companion object {
        // Plugin.load() normally runs once per process, but a plugin reload or
        // an in-place update can call it again. Only auto-open the bypass once.
        @Volatile
        private var autoBypassShown = false
    }

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

        // ── Validate the saved Cloudflare cookie on app open ──────────────
        // Age alone is not proof: cf_clearance can be revoked or expire
        // server-side while the timestamp is still young. A cookie that
        // merely *exists* used to skip both the bypass and the playback
        // probe, which left "Saved" cookies scraping nothing. Validate with
        // one real request instead, off the main thread, and only open the
        // bypass when the probe is actually challenged.
        if (!autoBypassShown) {
            autoBypassShown = true
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
}
