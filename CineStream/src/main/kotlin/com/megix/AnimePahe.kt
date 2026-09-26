package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

/**
 * AnimePahe streaming adapter (Phase 3 — streaming only).
 *
 * Adapted from the Phisher AnimePahe reference (bundled AnimePahe.cs3,
 * decompiled for interop). Only the streaming path is adapted:
 *
 *   search:   GET {base}/api?m=search&l=8&q={title}
 *   episodes: GET {base}/api?m=release&id={session}&sort=episode_asc&page={n}
 *   episode:  GET {base}/play/{session}/{episodeSession}
 *   streams:  #resolutionMenu button[data-src] (kwik) + div#pickDownload > a
 *   kwik:     unpack eval(...) -> source/m3u8/mp4 regex -> ExtractorLink
 *
 * Findings carried over from the reference:
 * - AnimePahe search is title-only (no MAL/AniList id lookup); session is an
 *   opaque per-anime string. Matching is verified by year/episode-count guards.
 * - Episode numbers are used verbatim (absolute per anime entry, same
 *   per-season granularity as AniList, so AniList episode N == AnimePahe N).
 * - Only kwik (.cx) hrefs are forwarded; no m=links API exists in this path.
 * - AnimePahe provides NO subtitles (verified: subtitleCallback never invoked).
 * - Cloudflare: reuses CineStreamExtractors.cfGet (CloudflareKiller + saved
 *   webview cookies) instead of the reference's WebView dialog.
 *
 * AnimePahe never provides catalogs/search UI/metadata/artwork — those stay
 * on AniList/TMDB/fanart.tv in CineAnimeProvider.
 */
object AnimePahe {
    private val mirrors = listOf(
        "https://animepahe.pw",
        "https://animepahe.org",
        "https://animepahe.com",
    )

    private fun headers(base: String) = mapOf(
        "Cookie" to "__ddg2_=1234567890",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$base/",
    )

    private fun normalize(s: String?): String =
        s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""

    /**
     * Entry point called from CineAnimeProvider.loadLinks.
     * Returns true if at least one real ExtractorLink was emitted.
     */
    suspend fun invoke(
        res: CineAnimeProvider.CineAnimeEpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epNum = res.absoluteEpisode ?: res.episode ?: 1
        val titles = listOfNotNull(
            res.titleEnglish?.takeIf { it.isNotBlank() },
            res.titleRomaji?.takeIf { it.isNotBlank() },
        ).distinct()
        if (titles.isEmpty()) {
            Log.d("AnimePahe", "invoke: no titles in payload, aborting")
            return false
        }

        var emitted = 0
        val counting: (ExtractorLink) -> Unit = {
            emitted++
            callback(it)
        }

        for (base in mirrors) {
            for (title in titles) {
                val session = findSession(base, title, res.seasonYear, res.totalEpisodes)
                    ?: continue
                if (resolveEpisode(base, session, epNum, subtitleCallback, counting)) {
                    Log.d("AnimePahe", "invoke success via $base session=$session ep=$epNum emitted=$emitted")
                    return emitted > 0
                }
            }
            // If search worked nowhere on this mirror, trying the next mirror
            // with the same titles is still worthwhile (mirror-specific index).
            if (emitted > 0) return true
        }
        Log.d("AnimePahe", "invoke: no streams for ${titles.firstOrNull()} ep=$epNum")
        return emitted > 0
    }

    // ── Anime lookup: title search + year/episode-count guards ────────────
    private suspend fun findSession(
        base: String,
        title: String,
        year: Int?,
        totalEpisodes: Int?
    ): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val json = runCatching {
            CineStreamExtractors.cfGet(
                "$base/api?m=search&l=8&q=$q",
                headers(base)
            ).text
        }.getOrNull() ?: return null
        val results = tryParseJson<PaheSearchResponse>(json)?.data
            .orEmpty().filter { !it.session.isNullOrBlank() && !it.title.isNullOrBlank() }
        if (results.isEmpty()) return null

        val normQuery = normalize(title)
        var best: PaheSearchItem? = null
        var bestScore = 0
        for (c in results) {
            val normTitle = normalize(c.title)
            var score = 0
            score += when {
                normTitle == normQuery -> 3
                normTitle.contains(normQuery) || normQuery.contains(normTitle) -> 2
                else -> 0
            }
            if (score == 0) continue
            if (year != null && c.year == year) score += 1
            if (totalEpisodes != null && c.episodes == totalEpisodes) score += 1
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        // Require at least a containment match (score >= 2) — never blind-pick.
        if (bestScore < 2) {
            Log.d("AnimePahe", "findSession: no confident match for '$title' on $base")
            return null
        }
        Log.d("AnimePahe", "findSession: '$title' -> '${best?.title}' session=${best?.session} score=$bestScore")
        return best?.session
    }

    // ── Episode resolution (verbatim absolute numbering) ──────────────────
    private suspend fun resolveEpisode(
        base: String,
        session: String,
        epNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var page = 1
        var lastPage = 1
        var found: PaheEpisode? = null
        do {
            val json = runCatching {
                CineStreamExtractors.cfGet(
                    "$base/api?m=release&id=$session&sort=episode_asc&page=$page",
                    headers(base)
                ).text
            }.getOrNull() ?: return false
            val release = tryParseJson<PaheReleaseResponse>(json) ?: return false
            lastPage = release.last_page ?: 1
            found = release.data?.firstOrNull { it.episode == epNum }
            page++
        } while (found == null && page <= lastPage)

        val ep = found ?: run {
            Log.d("AnimePahe", "resolveEpisode: ep=$epNum not in session=$session")
            return false
        }
        val epSession = ep.session?.takeIf { it.isNotBlank() } ?: return false
        val playUrl = "$base/play/$session/$epSession"

        val doc = runCatching {
            CineStreamExtractors.cfGet(playUrl, headers(base)).document
        }.getOrNull() ?: return false

        var ok = false
        val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4})p""")

        doc.select("#resolutionMenu button").safeAmap { btn ->
            val src = btn.attr("data-src").takeIf { it.contains("kwik") } ?: return@safeAmap
            val audio = btn.attr("data-audio")
            val badge = btn.selectFirst("span.badge-warning")?.text().orEmpty()
            val text = btn.text()
            val quality = qualityRegex.find("$text $badge")?.groupValues?.getOrNull(2)
                ?.toIntOrNull() ?: Qualities.Unknown.value
            val type = if (
                audio.equals("eng", true) ||
                badge.contains("eng", true) || badge.contains("dub", true) ||
                text.contains("eng", true) || text.contains("dub", true)
            ) "DUB" else "SUB"
            if (resolveKwik(base, src, playUrl, "[$type]", quality, callback)) ok = true
        }

        doc.select("div#pickDownload > a").safeAmap { a ->
            val href = a.attr("href").takeIf { it.contains("kwik") } ?: return@safeAmap
            val text = a.text()
            val quality = qualityRegex.find(text)?.groupValues?.getOrNull(2)
                ?.toIntOrNull() ?: Qualities.Unknown.value
            val type = if (text.contains("eng", true)) "[Download] [DUB]" else "[Download] [SUB]"
            if (resolveKwik(base, href, playUrl, type, quality, callback)) ok = true
        }

        subtitleCallback.let { } // AnimePahe provides no subtitles (reference-verified).
        return ok
    }

    // ── Kwik resolver (adapted; PACKER unpack + source regexes) ───────────
    private suspend fun resolveKwik(
        paheBase: String,
        kwikUrl: String,
        referer: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching {
            CineStreamExtractors.cfGet(
                kwikUrl,
                mapOf("referer" to "$paheBase/")
            )
        }.getOrNull() ?: return false
        val html = res.text
        val doc = res.document
        val title = doc.title()

        val scripts = doc.select("script").map { it.data() }
        var script = scripts.firstOrNull {
            it.contains("function(p,a,c,k,e,") || it.contains("eval(function")
        }
        if (script.isNullOrBlank()) {
            script = doc.selectFirst("script:containsData(eval)")?.data()
        }
        val unpacked = if (script.isNullOrBlank()) html else runCatching {
            getAndUnpack(script)
        }.getOrDefault(html)

        val mediaUrl = Regex("""source\s*=\s*['"]([^'"]+)['"]""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: Regex("""(https?://[^\s'"<>]+\.(?:mp4|m3u8)[^\s'"<>]*)""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return false

        val isM3u8 = mediaUrl.contains(".m3u8")
        callback(
            newExtractorLink(
                "AnimePahe",
                "AnimePahe $label",
                mediaUrl,
                INFER_TYPE
            ) {
                this.referer = "https://kwik.cx"
                this.quality = quality
                this.headers = mapOf("origin" to "https://kwik.cx")
            }
        )

        // Download variant, same derivation as the reference (/stream/ -> /mp4/).
        if (mediaUrl.contains("/stream/")) {
            val fileName = title.substringBeforeLast(".mp4") + ".mp4"
            val mp4Url = mediaUrl.replace("/stream/", "/mp4/")
                .substringBeforeLast("/") + "?file=" + URLEncoder.encode(fileName, "UTF-8")
            callback(
                newExtractorLink(
                    "AnimePahe",
                    "AnimePahe $label [Download]",
                    mp4Url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = kwikUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to kwikUrl,
                        "Origin" to "https://kwik.cx",
                    )
                }
            )
        }
        Log.d("AnimePahe", "resolveKwik: $label q=$quality m3u8=$isM3u8")
        return true
    }

    // ── AnimePahe API DTOs ────────────────────────────────────────────────
    data class PaheSearchResponse(
        @param:JsonProperty("total") val total: Int? = null,
        @param:JsonProperty("data") val data: List<PaheSearchItem>? = null,
    )

    data class PaheSearchItem(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("slug") val slug: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("episodes") val episodes: Int? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("season") val season: String? = null,
        @param:JsonProperty("year") val year: Int? = null,
        @param:JsonProperty("score") val score: Double? = null,
        @param:JsonProperty("poster") val poster: String? = null,
        @param:JsonProperty("session") val session: String? = null,
    )

    data class PaheReleaseResponse(
        @param:JsonProperty("total") val total: Int? = null,
        @param:JsonProperty("per_page") val per_page: Int? = null,
        @param:JsonProperty("current_page") val current_page: Int? = null,
        @param:JsonProperty("last_page") val last_page: Int? = null,
        @param:JsonProperty("data") val data: List<PaheEpisode>? = null,
    )

    data class PaheEpisode(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("anime_id") val anime_id: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("snapshot") val snapshot: String? = null,
        @param:JsonProperty("session") val session: String? = null,
        @param:JsonProperty("filler") val filler: Int? = null,
        @param:JsonProperty("created_at") val created_at: String? = null,
    )
}
