package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
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
 *   opaque per-anime string. Queries run over an alias pool (EN + romaji +
 *   native + AniList synonyms, deduped/capped) in two tiers: strict
 *   (exact/containment + generic season-marker gate + year/count/movie
 *   guards), then token-overlap fallback (ID verification mandatory).
 *   Candidates rank by score; each is ID-verified against the anime page's
 *   anilist/mal external links when present and tried until its episode list
 *   contains the requested episode. No verifiable match -> no stream.
 * - Episode pages jump to the estimated release page (long entries) instead
 *   of walking 1..N; paginated calls are paced like the reference.
 * - pickDownload hrefs resolve via kwik, or via the pahe.win token form
 *   (faithful port); unsupported hosts are skipped, never fabricated.
 * - Episode numbers are used verbatim (absolute per anime entry, same
 *   per-season granularity as AniList, so AniList episode N == AnimePahe N).
 * - Only kwik (.cx) hrefs are forwarded; no m=links API exists in this path.
 * - AnimePahe provides NO subtitles (verified: subtitleCallback never invoked).
 * - Cloudflare: reuses CineStreamExtractors.cfGet (CloudflareKiller + saved
 *   webview cookies). First-run/interactive challenges are solved via
 *   Settings → Cloudflare Bypass → AnimePahe/Kwik (visible WebView, cookies
 *   saved per-domain and injected on later requests).
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

    // 429 (rate limit) handling: hammering a limited IP extends the flag, so
    // a 429 fails fast and parks all attempts behind a cooldown instead.
    private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L
    @Volatile private var rateLimitedUntil = 0L

    private fun isRateLimited(): Boolean {
        val limited = System.currentTimeMillis() < rateLimitedUntil
        if (limited) Log.d("AnimePahe", "rate-limit cooldown active, backing off")
        return limited
    }

    private fun headers(base: String) = mapOf(
        "Cookie" to "__ddg2_=1234567890",
        // MUST be CF_BYPASS_USER_AGENT (the solver's agent): Cloudflare binds
        // cf_clearance to the solving UA, and a mismatched UA gets challenged
        // even with valid cookies (Phisher replays its solving UA likewise).
        "User-Agent" to CF_BYPASS_USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$base/",
    )

    // First attempts often land on a Cloudflare challenge page ("Just a
    // moment") until clearance warms up — users report success on manual
    // retry 2-3. Absorb that inside one attempt: bounded retries with
    // backoff when the body is a challenge (or not JSON where JSON is
    // required). Exhaustion returns null -> fail closed, as before.
    private suspend fun fetchWithRetry(
        url: String,
        headers: Map<String, String>,
        expectJson: Boolean,
        allowRedirects: Boolean = true,
        attempts: Int = 3,
        postData: Map<String, String>? = null,
    ): NiceResponse? {
        var last: NiceResponse? = null
        repeat(attempts) { n ->
            val res = runCatching {
                if (postData != null) {
                    CineStreamExtractors.cfPost(
                        url, headers = headers,
                        data = postData, allowRedirects = allowRedirects,
                    )
                } else {
                    CineStreamExtractors.cfGet(url, headers, allowRedirects)
                }
            }.getOrNull()
            last = res
            if (res?.code == 429) {
                Log.d("AnimePahe", "HTTP 429 rate limited, backing off: $url")
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }
            val text = res?.text
            val challenged = res == null || text == null ||
                text.contains("Just a moment") ||
                (expectJson && !text.trimStart().startsWith("{"))
            if (!challenged) return res
            Log.d("AnimePahe", "fetch retry ${n + 1}/$attempts challenged: $url")
            delay(if (n == 0) 2000L else 4000L)
        }
        val text = last?.text
        return last?.takeIf {
            text != null && !text.contains("Just a moment") &&
                (!expectJson || text.trimStart().startsWith("{"))
        }
    }

    private fun normalize(s: String?): String =
        s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""

    /**
     * Entry point called from CineAnimeProvider.loadLinks.
     * Returns true if at least one real ExtractorLink was emitted.
     *
     * Matching runs in two tiers per mirror: strict (exact/containment +
     * season-marker gate) first, token-overlap fallback second (ID
     * verification mandatory there). Every ranked candidate session is tried
     * in order until its episode list actually contains the requested
     * episode, so a wrong-season pick can never poison the result. Failures
     * return false — never a similar-looking wrong season.
     */
    suspend fun invoke(
        res: CineAnimeProvider.CineAnimeEpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Per-season number first: AnimePahe numbers each season entry from 1,
        // while absoluteEpisodeNumber continues the franchise count for
        // sequels (e.g. sequel E1 = abs 50) and would never match. Identical
        // for single-entry shows, so no behavior change there.
        val epNum = res.episode ?: res.absoluteEpisode ?: 1
        val aliases = buildAliases(res)
        if (aliases.isEmpty()) {
            Log.d("AnimePahe", "invoke: no usable titles in payload, aborting")
            return false
        }

        var emitted = 0
        val counting: (ExtractorLink) -> Unit = {
            emitted++
            callback(it)
        }

        if (isRateLimited()) return false
        for (base in mirrors) {
            if (isRateLimited()) return emitted > 0
            val searchCache = mutableMapOf<String, List<PaheSearchItem>>()
            suspend fun resultsFor(alias: String): List<PaheSearchItem> =
                searchCache.getOrPut(alias) { searchPahe(base, alias) }

            val strict = linkedMapOf<String, ScoredSession>()
            for (alias in aliases) {
                for (s in scoreStrict(alias, resultsFor(alias), res)) {
                    strict.merge(s.session, s) { a, b -> if (b.score > a.score) b else a }
                }
            }
            val strictRanked = strict.values.sortedByDescending { it.score }
            Log.d("AnimePahe", "strict pool for '$aliases' -> ${strictRanked.map { "${it.title}:${it.score}" }}")

            suspend fun attempt(ranked: List<ScoredSession>, requireVerified: Boolean): Boolean {
                for (s in ranked) {
                    if (!verifySession(base, s.session, res.anilistId, res.malId, requireVerified)) continue
                    if (resolveEpisode(base, s.session, epNum, subtitleCallback, counting)) {
                        Log.d("AnimePahe", "invoke success via $base session=${s.session} ep=$epNum emitted=$emitted")
                        return true
                    }
                }
                return false
            }

            // Fallback runs whenever strict yields no streams — not only when
            // strict is empty (a wrong strict pick must not block fallback).
            if (strictRanked.isNotEmpty() && attempt(strictRanked, false)) return true
            val fallback = linkedMapOf<String, ScoredSession>()
            for (alias in aliases) {
                for (s in scoreFallback(alias, resultsFor(alias), res)) {
                    fallback.merge(s.session, s) { a, b -> if (b.score > a.score) b else a }
                }
            }
            val fbRanked = fallback.values.sortedByDescending { it.score }.take(6)
            Log.d("AnimePahe", "fallback pool -> ${fbRanked.map { "${it.title}:${it.score}" }}")
            if (fbRanked.isEmpty()) {
                Log.d("AnimePahe", "invoke: no candidates at all, fail closed")
            }
            if (fbRanked.isNotEmpty() && attempt(fbRanked, true)) return true
            // If search worked nowhere on this mirror, trying the next mirror
            // with the same titles is still worthwhile (mirror-specific index).
            if (emitted > 0) return true
        }
        Log.d("AnimePahe", "invoke: no streams for ${aliases.firstOrNull()} ep=$epNum")
        return emitted > 0
    }

    private data class ScoredSession(val session: String, val title: String?, val score: Int)

    // Alias pool: EN + romaji always; synonyms only if useful — sharing >=2
    // content tokens with the EN/romaji base, or short codes (JJK3/SnK/AoT).
    // Foreign-language synonyms return junk from AnimePahe's English-first
    // index while multiplying request volume into rate limits, so they go.
    // Latin-signal filtered, deduped, capped.
    private fun buildAliases(res: CineAnimeProvider.CineAnimeEpisodeData): List<String> {
        val english = res.titleEnglish?.trim().takeIf { !it.isNullOrBlank() }
        val romaji = res.titleRomaji?.trim().takeIf { !it.isNullOrBlank() }
        val baseTokens = listOfNotNull(english, romaji).flatMap { contentTokens(it) }.toSet()
        fun usable(s: String): Boolean {
            if (s == english || s == romaji) return true
            if (normalize(s).length <= 6) return true
            return (contentTokens(s) intersect baseTokens).size >= 2
        }
        val raw = listOfNotNull(english, romaji) + res.synonyms.orEmpty()
        val latin = raw.mapNotNull { s ->
            val t = s.trim()
            if (t.isBlank()) null
            else if (t.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } >= 2) t
            else null
        }
        val pool = latin.ifEmpty { raw.filter { it.isNotBlank() }.take(2) }
        return pool.filter { usable(it) }.distinctBy { normalize(it) }.take(8)
            .ifEmpty { pool.distinctBy { normalize(it) }.take(2) }
    }

    // ── Season markers (generic; no hardcoded titles) ─────────────────────
    // "Season 2"->S2, "Part 2"->P2, "Cour 2"->C2, standalone "II"->S2,
    // "2nd Season"->S2, "Final Season"->FINAL, bare "Season 1"->S1.
    private fun seasonMarkers(title: String): Set<String> {
        val t = title.lowercase()
        val out = mutableSetOf<String>()
        Regex("""season\s*(\d+)""").findAll(t).forEach { out += "S${it.groupValues[1]}" }
        Regex("""\bpart\s*(\d+)""").findAll(t).forEach { out += "P${it.groupValues[1]}" }
        Regex("""\bcour\s*(\d+)""").findAll(t).forEach { out += "C${it.groupValues[1]}" }
        Regex("""(\d+)(?:st|nd|rd|th)\s*season""").findAll(t).forEach { out += "S${it.groupValues[1]}" }
        val romans = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6)
        Regex("""\b(ii|iii|iv|v|vi)\b""").findAll(t).forEach {
            romans[it.groupValues[1]]?.let { n -> out += "S$n" }
        }
        if (Regex("""\bfinal\b""").containsMatchIn(t)) out += "FINAL"
        return out
    }

    // Marker-stripped form for "X Season 3: Subtitle" vs "X: Subtitle"
    // comparisons. The marker gate below still applies — stripping alone
    // never accepts (S1-vs-S2 stays blocked).
    private fun stripMarkers(title: String): String {
        var t = " ${title.lowercase()} "
        t = t.replace(Regex("""\bseason\s*\d+"""), " ")
        t = t.replace(Regex("""\bpart\s*\d+"""), " ")
        t = t.replace(Regex("""\bcour\s*\d+"""), " ")
        t = t.replace(Regex("""\b\d+(?:st|nd|rd|th)\s*season"""), " ")
        t = t.replace(Regex("""\b(ii|iii|iv|v|vi)\b"""), " ")
        t = t.replace(Regex("""\bfinal\b"""), " ")
        return t.replace(Regex("[^a-z0-9]+"), "")
    }

    // A Season-1-style query (no markers) matches only unmarked candidates
    // or explicit "Season 1". A marked query needs a marked candidate sharing
    // at least one marker (subset-tolerant: "Season 3: Part 1" matches a plain
    // "Season 3" entry). This kills the structural false positive where "X"
    // is always a substring of "X Season 2".
    private fun markersCompatible(query: Set<String>, candidate: Set<String>): Boolean {
        if (query.isEmpty()) return candidate.isEmpty() || candidate == setOf("S1")
        if (candidate.isEmpty()) return false
        return candidate.intersect(query).isNotEmpty()
    }

    private suspend fun searchPahe(base: String, alias: String): List<PaheSearchItem> {
        val q = URLEncoder.encode(alias, "UTF-8")
        val json = fetchWithRetry(
            "$base/api?m=search&l=8&q=$q",
            headers(base), expectJson = true,
        )?.text ?: return emptyList()
        val items = tryParseJson<PaheSearchResponse>(json)?.data
            .orEmpty().filter { !it.session.isNullOrBlank() && !it.title.isNullOrBlank() }
        Log.d("AnimePahe", "searchPahe '$alias' -> ${items.size}: ${items.take(8).map { it.title }}")
        return items
    }

    // ── Tier 1: strict (exact/containment + marker gate + guards) ──────────
    private fun scoreStrict(
        alias: String,
        results: List<PaheSearchItem>,
        res: CineAnimeProvider.CineAnimeEpisodeData,
    ): List<ScoredSession> {
        val normQuery = normalize(alias)
        val qMarkers = seasonMarkers(alias)
        val strippedQuery = stripMarkers(alias)
        val isMovie = res.format == "MOVIE"
        return results.mapNotNull { c ->
            val normTitle = normalize(c.title)
            var score = when {
                normTitle == normQuery -> 3
                normTitle.contains(normQuery) || normQuery.contains(normTitle) -> 2
                else -> {
                    // Marker-stripped comparison: catches "X Season 3: Sub"
                    // vs "X: Sub" in either direction. Gate still mandatory.
                    val strippedTitle = stripMarkers(c.title ?: "")
                    if (strippedQuery.isNotBlank() && strippedTitle.isNotBlank() &&
                        (strippedQuery == strippedTitle ||
                            strippedQuery.contains(strippedTitle) ||
                            strippedTitle.contains(strippedQuery))
                    ) 2 else return@mapNotNull null
                }
            }
            if (!markersCompatible(qMarkers, seasonMarkers(c.title ?: ""))) {
                Log.d("AnimePahe", "scoreStrict: marker mismatch, skip '${c.title}' for '$alias'")
                return@mapNotNull null
            }
            if (qMarkers.isNotEmpty()) score += 1 // explicit season agreement
            if (res.seasonYear != null && c.year == res.seasonYear) score += 1
            if (res.totalEpisodes != null && c.episodes == res.totalEpisodes) score += 1
            if (isMovie && c.type?.contains("movie", true) == true) score += 1
            ScoredSession(c.session!!, c.title, score)
        }
    }

    // ── Tier 2: token-overlap fallback (arc/subtitle naming) ───────────────
    // Only reached when strict finds nothing. Candidates MUST still pass ID
    // verification (requireVerified) — overlap alone never accepts. The gate
    // stays loose on purpose (overlap >= 2): verification rejects wrong
    // seasons deterministically, and the pool is capped.
    private val similarityStopwords = setOf(
        "season", "part", "cour", "arc", "the", "a", "an", "no", "ni", "na",
        "to", "o", "wa", "ga", "de", "la", "le", "les", "des", "der", "die",
        "das", "el", "hen", "movie", "tv", "ova", "special", "specials",
    )

    private fun contentTokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in similarityStopwords }.toSet()

    private fun scoreFallback(
        alias: String,
        results: List<PaheSearchItem>,
        res: CineAnimeProvider.CineAnimeEpisodeData,
    ): List<ScoredSession> {
        val qTokens = contentTokens(alias)
        if (qTokens.size < 3) return emptyList() // too generic to try loosely
        return results.mapNotNull { c ->
            if (c.session.isNullOrBlank() || c.title.isNullOrBlank()) return@mapNotNull null
            val overlap = (contentTokens(c.title!!) intersect qTokens).size
            if (overlap < 2) return@mapNotNull null
            var score = overlap
            if (res.seasonYear != null && c.year == res.seasonYear) score += 2
            if (res.totalEpisodes != null && c.episodes == res.totalEpisodes) score += 2
            ScoredSession(c.session!!, c.title, score)
        }
    }

    // ── Session verification via AnimePahe external links (best-effort) ────
    // The anime page carries anilist/mal outbound links (same selector the
    // reference uses for enrichment). A PROVEN mismatch rejects the session.
    // When requireVerified is set (fallback tier), sessions whose IDs cannot
    // be proven are also skipped — fail closed. Otherwise absent links accept
    // gracefully (never fail-closed on layout drift).
    private suspend fun verifySession(
        base: String,
        session: String,
        anilistId: Int?,
        malId: Int?,
        requireVerified: Boolean = false,
    ): Boolean {
        if (anilistId == null && malId == null) return !requireVerified
        val doc = fetchWithRetry(
            "$base/anime/$session", headers(base), expectJson = false,
        )?.text?.let { Jsoup.parse(it) } ?: return !requireVerified
        var sawId = false
        for (a in doc.select(".external-links > a")) {
            val href = a.attr("href")
            if (href.contains("anilist.co") && anilistId != null) {
                val id = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
                if (id != null) {
                    sawId = true
                    if (id != anilistId) {
                        Log.d("AnimePahe", "verifySession: anilist mismatch $id != $anilistId, reject $session")
                        return false
                    }
                }
            }
            if (href.contains("myanimelist.net") && malId != null) {
                val id = Regex("""/anime/(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (id != null) {
                    sawId = true
                    if (id != malId) {
                        Log.d("AnimePahe", "verifySession: mal mismatch $id != $malId, reject $session")
                        return false
                    }
                }
            }
        }
        if (sawId) Log.d("AnimePahe", "verifySession: $session ID-verified")
        return if (requireVerified) sawId else true
    }

    // ── Episode resolution (verbatim absolute numbering) ──────────────────
    // Long lists are jumped to the estimated page (not walked 1..N) so late
    // episodes in 1000+ episode entries resolve before loadLinks times out.
    private suspend fun fetchRelease(
        base: String,
        session: String,
        page: Int
    ): PaheReleaseResponse? {
        val json = fetchWithRetry(
            "$base/api?m=release&id=$session&sort=episode_asc&page=$page",
            headers(base), expectJson = true,
        )?.text ?: return null
        return tryParseJson<PaheReleaseResponse>(json)
    }

    private suspend fun findPaheEpisode(
        base: String,
        session: String,
        epNum: Int
    ): PaheEpisode? {
        var page = 1
        var lastPage = 1
        var jumped = false
        var fetches = 0
        while (fetches < 8) {
            if (fetches > 0) delay(500) // reference paces paginated release calls
            val release = fetchRelease(base, session, page) ?: return null
            fetches++
            lastPage = release.last_page ?: 1
            val data = release.data.orEmpty()
            data.firstOrNull { it.episode == epNum }?.let { return it }
            if (data.isEmpty()) return null
            if (!jumped) {
                jumped = true
                val perPage = release.per_page?.takeIf { it > 0 } ?: data.size
                val first = data.mapNotNull { it.episode }.minOrNull()
                if (first != null && perPage > 0) {
                    val est = ((epNum - first) / perPage) + 1
                    if (est in 2..lastPage && est != page) {
                        page = est
                        continue
                    }
                }
            }
            val minEp = data.mapNotNull { it.episode }.minOrNull() ?: return null
            val maxEp = data.mapNotNull { it.episode }.maxOrNull() ?: return null
            page = when {
                epNum < minEp && page > 1 -> page - 1
                epNum > maxEp && page < lastPage -> page + 1
                else -> return null // in range but absent: truly missing
            }
        }
        return null
    }

    private suspend fun resolveEpisode(
        base: String,
        session: String,
        epNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = findPaheEpisode(base, session, epNum) ?: run {
            Log.d("AnimePahe", "resolveEpisode: ep=$epNum not in session=$session")
            return false
        }
        val epSession = ep.session?.takeIf { it.isNotBlank() } ?: return false
        val playUrl = "$base/play/$session/$epSession"

        val doc = fetchWithRetry(playUrl, headers(base), expectJson = false)
            ?.text?.let { Jsoup.parse(it) } ?: return false

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
            if (resolveKwik(base, src, playUrl, "AnimePahe $type", "[$type]", quality, callback)) ok = true
        }

        doc.select("div#pickDownload > a").safeAmap { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@safeAmap
            val text = a.text()
            val quality = qualityRegex.find(text)?.groupValues?.getOrNull(2)
                ?.toIntOrNull() ?: Qualities.Unknown.value
            // DUB only from the anchor's own marker, never from the URL alone.
            val type = if (text.contains("eng", true)) "DUB" else "SUB"
            val qualityName = if (quality > 0) " • ${quality}p" else ""
            val emitted = when {
                href.contains("kwik") -> resolveKwik(
                    base, href, playUrl,
                    "AnimePahe Download $type", "[Download] [$type]",
                    quality, callback,
                )
                href.contains("pahe.win") -> resolvePaheDownload(
                    href,
                    "AnimePahe Download $type",
                    "AnimePahe Download $type$qualityName",
                    quality, callback,
                )
                else -> {
                    Log.d("AnimePahe", "pickDownload: unsupported host, skip $href")
                    false
                }
            }
            if (emitted) ok = true
        }

        subtitleCallback.let { } // AnimePahe provides no subtitles (reference-verified).
        return ok
    }

    // ── Kwik resolver (adapted; PACKER unpack + source regexes) ───────────
    // `source` is deliberately distinct per category (SUB/DUB/Download):
    // CloudStream's Profile → Edit source priority is keyed on
    // ExtractorLink.source, so each category is independently reorderable.
    private suspend fun resolveKwik(
        paheBase: String,
        kwikUrl: String,
        referer: String,
        source: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = fetchWithRetry(
            kwikUrl, mapOf("referer" to "$paheBase/"), expectJson = false,
        ) ?: return false
        val html = res.text
        val doc = Jsoup.parse(html)
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
                source,
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
                    source,
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

    // ── pahe.win download resolver (faithful port of reference Pahe) ───────
    // pickDownload hrefs that are not kwik go through pahe.win's token form:
    //   GET {href}/i (no redirect) -> Location -> kwik page
    //   kwik page params regex -> char-index decrypt -> action + _token
    //   POST action (_token, no redirect, retry to 302) -> Location (media)
    // Any step failing yields no link (never a fabricated one).
    private suspend fun resolvePaheDownload(
        paheUrl: String,
        source: String,
        name: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val noRedirect = false
        val first = fetchWithRetry(
            "$paheUrl/i", emptyMap(), expectJson = false,
            allowRedirects = noRedirect,
        ) ?: return false
        val loc1 = first.headers["Location"] ?: first.headers["location"] ?: return false
        val kwikUrl = "https://" + loc1.substringAfterLast("https://")
        val fRes = fetchWithRetry(
            kwikUrl, mapOf("referer" to "https://kwik.cx/"), expectJson = false,
        ) ?: return false
        val fText = fRes.text
        val pm = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""").find(fText) ?: return false
        val decrypted = paheDecrypt(
            pm.groupValues[1], pm.groupValues[2],
            pm.groupValues[3].toIntOrNull() ?: return false,
            pm.groupValues[4].toIntOrNull() ?: return false,
        ) ?: return false
        val uri = Regex("""action="([^"]+)"""").find(decrypted)
            ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return false
        val tok = Regex("""value="([^"]+)"""").find(decrypted)
            ?.groupValues?.getOrNull(1) ?: return false
        val cookie = fRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        var code = 419
        var tries = 0
        var location: String? = null
        while (code != 302 && tries < 20) {
            val post = fetchWithRetry(
                uri,
                headers = mapOf(
                    "user-agent" to " Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                    "referer" to kwikUrl,
                    "cookie" to cookie,
                ),
                expectJson = false,
                allowRedirects = false,
                attempts = 2,
                postData = mapOf("_token" to tok),
            ) ?: return false
            code = post.code
            location = post.headers["Location"] ?: post.headers["location"]
            tries++
        }
        val finalUrl = location?.takeIf { it.isNotBlank() } ?: return false
        callback(
            newExtractorLink(source, name, finalUrl, INFER_TYPE) {
                this.referer = "https://kwik.cx/"
                this.quality = quality
            }
        )
        Log.d("AnimePahe", "resolvePaheDownload: $name q=$quality")
        return true
    }

    // Char-index cipher from the reference: each fullString segment (split on
    // key[v2]) maps chars to key indexes, joined digits parse as base-v2,
    // minus v1 gives the char. Bounds-guarded; null on any anomaly.
    private fun paheDecrypt(fullString: String, key: String, v1: Int, v2: Int): String? {
        if (key.isEmpty() || v2 !in 2..36) return null
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val toFind = key.getOrNull(v2) ?: return null
        val sb = StringBuilder()
        var i = 0
        return runCatching {
            while (i < fullString.length) {
                val nextIndex = fullString.indexOf(toFind, i)
                if (nextIndex == -1) break
                var decodedCharStr = ""
                for (j in i until nextIndex) {
                    decodedCharStr += keyIndexMap[fullString[j]] ?: return null
                }
                if (decodedCharStr.isEmpty()) return null
                sb.append((decodedCharStr.toInt(v2) - v1).toChar())
                i = nextIndex + 1
            }
            sb.toString()
        }.getOrNull()
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
