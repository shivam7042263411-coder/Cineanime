package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.megix.settings.Settings

object AnimePahe {

    private val mirrors = listOf(
        "https://animepahe.pw",
        "https://animepahe.org",
        "https://animepahe.com",
    )

    private fun headers(base: String): Map<String, String> {
        val savedUa = Settings.getCfUserAgent()
        val ua = if (savedUa.isNotEmpty()) savedUa else
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        return mapOf(
            "User-Agent" to ua,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$base/",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
        )
    }

    private suspend fun fetchWithRetry(
        url: String,
        headers: Map<String, String>,
        expectJson: Boolean,
        attempts: Int = 3,
    ): NiceResponse? {
        var last: NiceResponse? = null
        repeat(attempts) { n ->
            val res = runCatching {
                CineStreamExtractors.cfGet(url, headers = headers, allowRedirects = true)
            }.getOrNull()
            last = res
            if (res?.code == 429) {
                Log.d("AnimePahe", "HTTP 429 rate limited: $url")
                return null
            }
            val text = res?.text
            val challenged = res == null || text == null ||
                text.contains("Just a moment") ||
                (expectJson && !text.trimStart().startsWith("{"))
            if (!challenged) return res
            Log.d("AnimePahe", "fetch retry ${n + 1}/$attempts challenged: $url")
            kotlinx.coroutines.delay(if (n == 0) 2000L else 4000L)
        }
        val text = last?.text
        return last?.takeIf {
            text != null && !text.contains("Just a moment") &&
                (!expectJson || text.trimStart().startsWith("{"))
        }
    }

    // ── MainAPI.load ────────────────────────────────────────────────────

    suspend fun load(url: String): AnimeLoadResponse? {
        val base = mirrors.first()
        val res = fetchWithRetry("$url", headers(base), expectJson = false)
            ?.text?.let { Jsoup.parse(it) } ?: return null

        val title = res.title()
        val doc = res

        // Extract metadata from the page
        var anilistId: Int? = null
        var malId: Int? = null
        doc.select(".external-links > a").forEach { a ->
            val href = a.attr("href")
            if (href.contains("anilist.co")) {
                anilistId = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
            }
            if (href.contains("myanimelist.net")) {
                malId = Regex("""/anime/(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }

        val poster = doc.selectFirst("img")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".synopsis")?.text() ?: ""
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()
        val status = doc.selectFirst(".status")?.text() ?: "Unknown"
        val type = doc.selectFirst(".type")?.text() ?: "TV Series"

        val episodes = fetchEpisodes(base, doc)

        return AnimeLoadResponse(
            title = title,
            poster = poster,
            synopsis = synopsis,
            year = year,
            status = status,
            type = type,
            anilistId = anilistId,
            malId = malId,
            episodes = episodes,
        )
    }

    private suspend fun fetchEpisodes(base: String, doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select(".episode-list .episode").forEach { el ->
            val epNum = el.attr("data-episode").toIntOrNull() ?: 0
            val epSession = el.attr("data-session") ?: ""
            val epTitle = el.text()
            if (epSession.isNotEmpty()) {
                episodes.add(Episode(epNum, epSession, epTitle))
            }
        }
        return episodes
    }

    // ── MainAPI.loadLinks ───────────────────────────────────────────────

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = tryParseJson<LinkData>(data) ?: return false
        val episodeUrl = parsed.url ?: return false

        Log.d("AnimePahe", "loadLinks: $episodeUrl")

        val base = mirrors.first()
        val res = fetchWithRetry(episodeUrl, headers(base), expectJson = false)
            ?.text?.let { Jsoup.parse(it) } ?: return false

        var emitted = false

        // Resolution menu buttons (kwik streams)
        res.select("#resolutionMenu button").forEach { btn ->
            val src = btn.attr("data-src")
            if (src.isEmpty() || !src.contains("kwik")) return@forEach
            val audio = btn.attr("data-audio")
            val badge = btn.selectFirst("span.badge-warning")?.text().orEmpty()
            val text = btn.text()
            val type = if (audio.equals("eng", ignoreCase = true) ||
                badge.contains("eng", ignoreCase = true) ||
                badge.contains("dub", ignoreCase = true) ||
                text.contains("eng", ignoreCase = true) ||
                text.contains("dub", ignoreCase = true)
            ) "DUB" else "SUB"
            val quality = Regex("(.+?)\\s+·\\s+(\\d{3,4})p").find("$text $badge")?.groupValues?.getOrNull(2)
                ?.toIntOrNull() ?: Qualities.Unknown.value

            val link = newExtractorLink(
                "AnimePahe $type",
                "AnimePahe $type [$type]",
                src,
                ExtractorLinkType.VIDEO,
            ).apply {
                this.referer = "https://kwik.cx"
                this.quality = quality
                this.headers = mapOf("origin" to "https://kwik.cx")
            }
            callback(link)
            emitted = true
        }

        // Download links
        res.select("div#pickDownload > a").forEach { a ->
            val href = a.attr("href")
            if (href.isEmpty()) return@forEach
            val text = a.text()
            val type = if (text.contains("eng", ignoreCase = true)) "DUB" else "SUB"
            val quality = Regex("(.+?)\\s+·\\s+(\\d{3,4})p").find(text)?.groupValues?.getOrNull(2)
                ?.toIntOrNull() ?: Qualities.Unknown.value

            if (href.contains("kwik")) {
                val link = newExtractorLink(
                    "AnimePahe Download $type",
                    "AnimePahe Download $type [$type]",
                    href,
                    ExtractorLinkType.VIDEO,
                ).apply {
                    this.referer = href
                    this.quality = quality
                    this.headers = mapOf("Referer" to href, "Origin" to "https://kwik.cx")
                }
                callback(link)
                emitted = true
            }
        }

        return emitted
    }

    // ── DTOs ────────────────────────────────────────────────────────────

    data class SearchResponse(
        @JsonProperty("data") val data: List<SearchItem>? = null,
    )

    data class SearchItem(
        @JsonProperty("session") val session: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    data class LinkData(
        @JsonProperty("url") val url: String? = null,
    )

    data class AnimeLoadResponse(
        val title: String,
        val poster: String,
        val synopsis: String,
        val year: Int?,
        val status: String,
        val type: String,
        val anilistId: Int?,
        val malId: Int?,
        val episodes: List<Episode>,
    )

    data class Episode(
        val episode: Int,
        val session: String,
        val title: String,
    )
}
