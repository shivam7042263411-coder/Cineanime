package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import org.json.JSONObject
import java.net.URLEncoder

/**
 * CineAnime — AniList-powered anime provider (Phase 2).
 *
 * Pipeline:
 *   AniList (catalog/search/identity/metadata)
 *     -> Simkl (details poster/cover via ID lookup, AniList fallback)
 *     -> TMDB (logo via ID bridge, never title-assumed)
 *     -> fanart.tv (details background, optional)
 *     -> episodes (numbering preserved for future AnimePahe phase)
 *
 * Streaming is provided by AnimePahe only (Phase 3, see AnimePahe.kt):
 * [loadLinks] parses the episode payload and delegates to the adapter.
 */
class CineAnimeProvider : MainAPI() {
    override var name = "CineAnime"
    override var mainUrl = "https://anilist.co"
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Torrent
    )
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        private const val tmdbKey = BuildConfig.TMDB_KEY
        private const val tmdbApi = "https://api.themoviedb.org/3"
        private const val perPage = 20
    }

    // ── Catalog definitions (AniList-native sort/status, easy to extend) ──
    override val mainPage = mainPageOf(
        "sort:TRENDING_DESC" to "Trending Anime",
        "sort:POPULARITY_DESC" to "Popular Anime",
        "status:RELEASING|sort:POPULARITY_DESC" to "Currently Airing",
        "status:NOT_YET_RELEASED|sort:POPULARITY_DESC" to "Upcoming Anime",
    )

    private fun parseCatalog(data: String): Pair<String?, String?> {
        var sort: String? = null
        var status: String? = null
        data.split("|").forEach { part ->
            val kv = part.split(":", limit = 2)
            if (kv.size != 2) return@forEach
            when (kv[0]) {
                "sort" -> sort = kv[1].takeIf { it.isNotBlank() }
                "status" -> status = kv[1].takeIf { it.isNotBlank() }
            }
        }
        return sort to status
    }

    private fun mediaToSearchResponse(media: CineAnimeMedia): SearchResponse? {
        val title = media.title?.english?.takeIf { !it.isBlank() }
            ?: media.title?.romaji?.takeIf { !it.isBlank() }
            ?: media.title?.native?.takeIf { !it.isBlank() }
            ?: return null
        return newAnimeSearchResponse(
            title,
            CineAnimeRef(anilistId = media.id, malId = media.idMal).toJson(),
            TvType.Anime,
        ) {
            // Catalog posters stay AniList: a Simkl lookup per item would
            // add 20+ API calls per catalog page (rate-limit + latency).
            // Simkl covers apply on the details page (one lookup per open),
            // which also feeds Continue Watching and player art.
            // AniList covers are reliable; banner is a cheap last resort.
            // Per-item TMDB lookups are deliberately NOT done here (20+
            // extra API calls per catalog page for negligible gain).
            this.posterUrl = media.coverImage?.extraLarge
                ?: media.coverImage?.large
                ?: media.bannerImage?.takeIf { it.isNotBlank() }
            this.score = media.averageScore?.let { Score.from10(it / 10.0) }
        }
    }

    private suspend fun fetchCatalogPage(
        page: Int,
        sort: String?,
        status: String?,
        search: String?
    ): Pair<List<CineAnimeMedia>, Boolean> {
        val query = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}status: MediaStatus, ${'$'}search: String) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    media(type: ANIME, sort: ${'$'}sort, status: ${'$'}status, search: ${'$'}search) {
                        id idMal
                        title { english romaji native }
                        coverImage { extraLarge large }
                        bannerImage averageScore genres format status episodes
                        season seasonYear
                    }
                }
            }
        """.trimIndent()
        // NOTE: AniList ignores *omitted* variables but treats an explicit
        // `"status": null` as a filter matching nothing (verified: 0 results
        // for catalog AND search). Nulls must be omitted, not sent.
        val variables = mutableMapOf<String, Any?>(
            "page" to page,
            "perPage" to perPage,
        )
        sort?.let { variables["sort"] = listOf(it) }
        status?.let { variables["status"] = it }
        search?.let { variables["search"] = it }
        val res = runCatching {
            app.post(anilistAPI, json = mapOf("query" to query, "variables" to variables))
                .parsedSafe<CineAnimePageResponse>()
                ?.data?.page
        }.getOrNull() ?: return emptyList<CineAnimeMedia>() to false
        return (res.media ?: emptyList()) to (res.pageInfo?.hasNextPage == true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (sort, status) = parseCatalog(request.data)
        val (media, hasNext) = fetchCatalogPage(page, sort, status, null)
        val list = media.mapNotNull { mediaToSearchResponse(it) }
        return newHomePageResponse(
            HomePageList(request.name, list),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (media, hasNext) = fetchCatalogPage(page, null, null, query.takeIf { it.isNotBlank() })
        return newSearchResponseList(
            media.mapNotNull { mediaToSearchResponse(it) },
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    private fun showStatusOf(status: String?): ShowStatus? = when (status) {
        "RELEASING" -> ShowStatus.Ongoing
        "FINISHED" -> ShowStatus.Completed
        else -> null
    }

    // ── ID bridge: AniList -> TMDB/TVDB/IMDb ─────────────────────────────
    // Primary: ARM (same host/pattern as CineStreamProvider.getExternalIds).
    // Fallback: api.ani.zip mappings (already used for AnimeTosho mapping).
    // Last resort: TMDB title+year search (fragile, documented as such).
    private suspend fun resolveExternalIds(
        anilistId: Int,
        malId: Int?
    ): CineAnimeExternalIds {
        // 1) ARM: /ids?source=anilist&id= — returns themoviedb/thetvdb/imdb.
        runCatching {
            val json = JSONObject(
                app.get("$armIdsAPI/ids?source=anilist&id=$anilistId").text
            )
            val tmdb = json.opt("themoviedb")?.toString()?.toIntOrNull()
            val tvdb = json.opt("thetvdb")?.toString()?.toIntOrNull()
            val imdb = json.optString("imdb").takeIf { it.isNotBlank() }
            if (tmdb != null || tvdb != null || imdb != null) {
                return CineAnimeExternalIds(tmdb, tvdb, imdb, "arm")
            }
        }
        // 2) ani.zip mappings (anilist_id preferred, mal_id fallback).
        runCatching {
            val url = if (malId != null) "$anizipAPI/mappings?mal_id=$malId"
            else "$anizipAPI/mappings?anilist_id=$anilistId"
            val mappings = JSONObject(app.get(url).text).optJSONObject("mappings")
            if (mappings != null) {
                val tmdb = mappings.opt("themoviedb_id")?.toString()?.toIntOrNull()
                val tvdb = mappings.opt("thetvdb_id")?.toString()?.toIntOrNull()
                val imdb = mappings.optString("imdb_id").takeIf { it.isNotBlank() }
                if (tmdb != null || tvdb != null || imdb != null) {
                    return CineAnimeExternalIds(tmdb, tvdb, imdb, "anizip")
                }
            }
        }
        return CineAnimeExternalIds(null, null, null, null)
    }

    private suspend fun fetchTmdbDetail(
        tmdbId: Int,
        isMovie: Boolean
    ): JSONObject? {
        val kind = if (isMovie) "movie" else "tv"
        return runCatching {
            JSONObject(
                app.get("$tmdbApi/$kind/$tmdbId?api_key=$tmdbKey&append_to_response=external_ids").text
            )
        }.getOrNull()
    }

    // Fragile last resort: TMDB search by title + year. Only used when both ID
    // bridges fail. Takes the top result of a title+year query; callers must
    // treat the resulting TMDB id as unverified.
    private suspend fun searchTmdbId(
        title: String?,
        year: Int?,
        isMovie: Boolean
    ): Int? {
        if (title.isNullOrBlank()) return null
        val kind = if (isMovie) "movie" else "tv"
        val yearParam = if (year != null) {
            if (isMovie) "&year=$year" else "&first_air_date_year=$year"
        } else ""
        val q = URLEncoder.encode(title, "UTF-8")
        return runCatching {
            app.get("$tmdbApi/search/$kind?api_key=$tmdbKey&language=en-US&query=$q$yearParam")
                .parsedSafe<CineAnimeTmdbSearch>()?.results?.firstOrNull()?.id
        }.getOrNull()
    }

    // ── Simkl cover for the details page (covers only) ────────────────
    // Lookup by AniList/MAL id via /search/id (no title matching), then
    // build the poster URL with the same pattern CineSimklProvider uses
    // (wsrv-proxied simkl.in medium webp). One lookup per details open —
    // never per catalog item. Null on any failure: callers fall back to
    // the AniList cover.
    private suspend fun fetchSimklPosterUrl(anilistId: Int?, malId: Int?): String? {
        val key = com.lagradost.cloudstream3.BuildConfig.SIMKL_CLIENT_ID
        suspend fun byId(param: String, id: Int): String? {
            val json = runCatching {
                app.get("https://api.simkl.com/search/id?$param=$id&client_id=$key").text
            }.getOrNull() ?: return null
            val posterId = runCatching {
                parseJson<List<SimklIdLookup>>(json)
            }.getOrNull().orEmpty().firstOrNull { !it.poster.isNullOrBlank() }?.poster
            return posterId?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_m.webp" }
        }
        anilistId?.let { byId("anilist", it)?.let { url -> return url } }
        malId?.let { byId("mal", it)?.let { url -> return url } }
        return null
    }

    private data class SimklIdLookup(
        val poster: String? = null,
    )

    // ── Per-episode metadata from ani.zip (keyed by episode number) ───────
    // ani.zip is the only source with a COMPLETE number-keyed episode map
    // (titles, overviews, images, air dates). AniList `streamingEpisodes` is
    // only a partial reverse-chronological window and must never be treated
    // as the episode list.
    private suspend fun fetchAnizip(anilistId: Int, malId: Int?): CineAnimeAnizip? {
        val urls = listOfNotNull(
            "$anizipAPI/mappings?anilist_id=$anilistId",
            malId?.let { "$anizipAPI/mappings?mal_id=$it" },
        )
        for (url in urls) {
            val parsed = runCatching {
                tryParseJson<CineAnimeAnizip>(app.get(url).text)
            }.getOrNull()
            if (!parsed?.episodes.isNullOrEmpty()) return parsed
        }
        return null
    }

    private fun buildAnimeEpisodes(
        anizip: CineAnimeAnizip?,
        media: CineAnimeMedia,
        anilistId: Int,
        malId: Int?,
        tmdbId: Int?,
        tvdbId: Int?,
        imdbId: String?,
        titleEnglish: String?,
        titleRomaji: String?,
        score: Score?,
        totalEpCount: Int?,
    ): List<Episode> {
        val synonyms = media.synonyms?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        val numbered = anizip?.episodes
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
            ?.sortedBy { it.first }
            .orEmpty()
        if (numbered.isNotEmpty()) {
            return numbered.map { (num, meta) ->
                val epTitle = meta.title?.en?.takeIf { !it.isBlank() }
                    ?: meta.title?.xjat?.takeIf { !it.isBlank() }
                    ?: meta.title?.ja?.takeIf { !it.isBlank() }
                newEpisode(
                    CineAnimeEpisodeData(
                        anilistId = anilistId,
                        malId = malId,
                        tmdbId = tmdbId,
                        tvdbId = tvdbId,
                        imdbId = imdbId,
                        titleEnglish = titleEnglish,
                        titleRomaji = titleRomaji,
                        synonyms = synonyms,
                        season = media.season,
                        seasonYear = media.seasonYear,
                        format = media.format,
                        episode = num,
                        absoluteEpisode = meta.absoluteEpisodeNumber ?: num,
                        totalEpisodes = totalEpCount,
                        episodeTitle = epTitle,
                        thumbnail = meta.image?.takeIf { !it.isBlank() },
                    ).toJson()
                ) {
                    // EpisodeAdapter renders "N. name"; null name renders
                    // "Episode N". Never synthesize a title.
                    this.name = epTitle
                    this.season = meta.seasonNumber ?: 1
                    this.episode = num
                    this.posterUrl = meta.image?.takeIf { !it.isBlank() }
                    this.description = meta.overview?.takeIf { !it.isBlank() }
                    this.score = score
                    this.runTime = meta.runtime ?: meta.length
                    addDate(meta.airDate)
                }
            }
        }
        // Fallback: bare numbered entries from the AniList count (never
        // streamingEpisodes positions — that field is a partial window).
        val count = totalEpCount?.takeIf { it > 0 } ?: 1
        return (1..count).map { num ->
            newEpisode(
                CineAnimeEpisodeData(
                    anilistId = anilistId,
                    malId = malId,
                    tmdbId = tmdbId,
                    tvdbId = tvdbId,
                    imdbId = imdbId,
                    titleEnglish = titleEnglish,
                    titleRomaji = titleRomaji,
                    synonyms = synonyms,
                    season = media.season,
                    seasonYear = media.seasonYear,
                    format = media.format,
                    episode = num,
                    absoluteEpisode = num,
                    totalEpisodes = totalEpCount,
                ).toJson()
            ) {
                this.name = null
                this.season = 1
                this.episode = num
                this.score = score
            }
        }
    }

    // ── fanart.tv background (optional; null key/url/id => skip gracefully) ──
    private suspend fun resolveFanartBackground(
        isMovie: Boolean,
        tmdbId: Int?,
        tvdbId: Int?
    ): String? {
        val key = FANART_KEY.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val url = if (isMovie && tmdbId != null) {
            "$fanartAPI/movies/$tmdbId?api_key=$key"
        } else if (!isMovie && tvdbId != null) {
            "$fanartAPI/tv/$tvdbId?api_key=$key"
        } else return null
        return runCatching {
            val json = JSONObject(app.get(url).text)
            val arr = json.optJSONArray(if (isMovie) "moviebackground" else "showbackground")
                ?: return null
            var bestUrl: String? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                // Bare filenames (no host) cannot be resolved reliably — skip.
                val lang = o.optString("lang")
                val likes = o.optString("likes").toIntOrNull() ?: 0
                val langBonus = if (lang == "en" || lang.isBlank()) 10_000 else 0
                val score = langBonus + likes
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = u
                }
            }
            bestUrl
        }.getOrNull()
    }

    // ── Details ──────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val ref = runCatching { parseJson<CineAnimeRef>(url) }.getOrNull()
            ?: throw ErrorLoadingException("Invalid CineAnime url")
        val anilistId = ref.anilistId ?: throw ErrorLoadingException("Missing AniList id")

        val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id idMal
                    title { english romaji native }
                    description(asHtml: false)
                    coverImage { extraLarge large }
                    bannerImage genres format status episodes duration
                    season seasonYear synonyms
                    startDate { year month day }
                    averageScore
                    streamingEpisodes { title thumbnail url site }
                }
            }
        """.trimIndent()
        val media = runCatching {
            app.post(anilistAPI, json = mapOf("query" to query, "variables" to mapOf("id" to anilistId)))
                .parsedSafe<CineAnimeDetailResponse>()?.data?.media
        }.getOrNull() ?: throw ErrorLoadingException("Invalid AniList response")

        val titleEnglish = media.title?.english?.takeIf { !it.isBlank() }
        val titleRomaji = media.title?.romaji?.takeIf { !it.isBlank() }
        val titleNative = media.title?.native?.takeIf { !it.isBlank() }
        val displayTitle = titleEnglish ?: titleRomaji ?: titleNative ?: return null
        val malId = media.idMal
        val isMovie = media.format == "MOVIE"
        val year = media.seasonYear ?: media.startDate?.year
        val score = media.averageScore?.let { Score.from10(it / 10.0) }
        val totalEpisodes = media.episodes
        val durationEach = media.duration
        val totalDuration = if (totalEpisodes != null && durationEach != null) {
            totalEpisodes * durationEach
        } else durationEach

        // ID bridge (no AniList==TMDB assumption).
        val ext = resolveExternalIds(anilistId, malId)
        var tmdbId = ext.tmdbId
        var tvdbId = ext.tvdbId
        var imdbId = ext.imdbId
        var tmdbSource: String? = ext.source
        if (tmdbId == null) {
            tmdbId = searchTmdbId(titleEnglish ?: titleRomaji, year, isMovie)
            if (tmdbId != null) tmdbSource = "tmdb-search"
        }
        val tmdbDetail = tmdbId?.let { fetchTmdbDetail(it, isMovie) }
        if (tvdbId == null) {
            tvdbId = tmdbDetail?.optJSONObject("external_ids")?.opt("tvdb_id")
                ?.toString()?.toIntOrNull()
        }
        if (imdbId == null) {
            imdbId = tmdbDetail?.optJSONObject("external_ids")?.optString("imdb_id")
                ?.takeIf { it.isNotBlank() }
        }

        // Artwork hierarchy (CineAnime-owned, independent of Simkl/TMDB):
        // poster: Simkl cover first (AniList covers are often low-res),
        // AniList cover fallback. This is the details poster, so Continue
        // Watching (which caches it) picks up Simkl covers too. Catalog
        // rows intentionally keep AniList covers (see mediaToSearchResponse).
        // TMDB still provides logo + ID enrichment below.
        // background: fanart.tv -> AniList banner -> AniList cover.
        val poster = fetchSimklPosterUrl(anilistId, malId)
            ?: media.coverImage?.extraLarge
            ?: media.coverImage?.large
        val logo = fetchTmdbLogoUrl(
            tmdbApi,
            tmdbKey,
            if (isMovie) TvType.Movie else TvType.TvSeries,
            tmdbId,
            "en"
        )
        val background = resolveFanartBackground(isMovie, tmdbId, tvdbId)
            ?: media.bannerImage?.takeIf { it.isNotBlank() }
            ?: media.coverImage?.extraLarge

        Log.d(
            "CineAnime",
            "load id=$anilistId mal=$malId tmdb=$tmdbId(via=$tmdbSource) tvdb=$tvdbId imdb=$imdbId"
        )

        // Per-episode metadata (titles/overviews/images keyed by TRUE episode
        // number). Fetched once here; also feeds the total count below.
        val anizip = fetchAnizip(anilistId, malId)
        val totalEpCount = anizip?.episodeCount ?: totalEpisodes

        if (isMovie) {
            val data = CineAnimeEpisodeData(
                anilistId = anilistId,
                malId = malId,
                tmdbId = tmdbId,
                tvdbId = tvdbId,
                imdbId = imdbId,
                titleEnglish = titleEnglish,
                titleRomaji = titleRomaji,
                synonyms = media.synonyms?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                season = media.season,
                seasonYear = media.seasonYear,
                format = media.format,
                totalEpisodes = totalEpCount,
            ).toJson()
            return newMovieLoadResponse(displayTitle, url, TvType.AnimeMovie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = media.description
                this.tags = media.genres
                this.score = score
                this.year = year
                this.duration = totalDuration
                addAniListId(anilistId)
                addMalId(malId)
                addImdbId(imdbId)
            }
        }

        // Episodes are built from ani.zip metadata keyed by TRUE episode
        // number (never array position). Non-numeric keys (S1..Sn specials)
        // are skipped, mirroring how CineSimkl drops Simkl "special" types.
        val episodes = buildAnimeEpisodes(
            anizip = anizip,
            media = media,
            anilistId = anilistId,
            malId = malId,
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            imdbId = imdbId,
            titleEnglish = titleEnglish,
            titleRomaji = titleRomaji,
            score = score,
            totalEpCount = totalEpCount,
        )

        return newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.logoUrl = logo
            this.plot = media.description
            this.tags = media.genres
            this.score = score
            this.year = year
            this.duration = totalDuration
            this.showStatus = showStatusOf(media.status)
            addAniListId(anilistId)
            addMalId(malId)
            addImdbId(imdbId)
        }
    }

    // ── Streaming: AnimePahe only (Phase 3) ──────────────────────────────
    // Artwork/metadata APIs are never touched here; this parses the episode
    // payload and delegates to the AnimePahe streaming adapter.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching { parseJson<CineAnimeEpisodeData>(data) }.getOrNull()
            ?: return false
        // No episode-number gate here: movies legitimately carry none, and
        // AnimePahe.invoke defaults them to episode 1. A missing-numbers
        // early return once silently killed ALL movie playback.
        return AnimePahe.invoke(res, subtitleCallback, callback)
    }

    // ── CineAnime DTOs (file-local; shared parser classes untouched) ─────
    data class CineAnimeRef(
        val anilistId: Int? = null,
        val malId: Int? = null,
    )

    /**
     * Episode/movie payload for CineAnime.loadLinks (Phase 3+).
     * episode = per-entry (per-season) number; absoluteEpisode = franchise-
     * absolute number from ani.zip (continues across sequel entries).
     * AnimePahe tries both (per-season first): its entries use either
     * convention depending on the show.
     */
    data class CineAnimeEpisodeData(
        val anilistId: Int? = null,
        val malId: Int? = null,
        val tmdbId: Int? = null,
        val tvdbId: Int? = null,
        val imdbId: String? = null,
        val titleEnglish: String? = null,
        val titleRomaji: String? = null,
        val synonyms: List<String>? = null,
        val season: String? = null,
        val seasonYear: Int? = null,
        val format: String? = null,
        val episode: Int? = null,
        val absoluteEpisode: Int? = null,
        val totalEpisodes: Int? = null,
        val episodeTitle: String? = null,
        val thumbnail: String? = null,
    )

    data class CineAnimeExternalIds(
        val tmdbId: Int?,
        val tvdbId: Int?,
        val imdbId: String?,
        val source: String?,
    )

    data class CineAnimePageResponse(
        @param:JsonProperty("data") val data: CineAnimePageData? = null,
    )

    data class CineAnimePageData(
        @param:JsonProperty("Page") val page: CineAnimePage? = null,
    )

    data class CineAnimePage(
        @param:JsonProperty("pageInfo") val pageInfo: CineAnimePageInfo? = null,
        @param:JsonProperty("media") val media: List<CineAnimeMedia>? = null,
    )

    data class CineAnimePageInfo(
        @param:JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    )

    data class CineAnimeMedia(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("idMal") val idMal: Int? = null,
        @param:JsonProperty("title") val title: CineAnimeTitle? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("coverImage") val coverImage: CineAnimeCover? = null,
        @param:JsonProperty("bannerImage") val bannerImage: String? = null,
        @param:JsonProperty("genres") val genres: List<String>? = null,
        @param:JsonProperty("format") val format: String? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("episodes") val episodes: Int? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("season") val season: String? = null,
        @param:JsonProperty("seasonYear") val seasonYear: Int? = null,
        @param:JsonProperty("startDate") val startDate: CineAnimeDate? = null,
        @param:JsonProperty("averageScore") val averageScore: Int? = null,
        @param:JsonProperty("synonyms") val synonyms: List<String>? = null,
        @param:JsonProperty("streamingEpisodes") val streamingEpisodes: List<CineAnimeStreamingEpisode>? = null,
    )

    data class CineAnimeDetailResponse(
        @param:JsonProperty("data") val data: CineAnimeDetailData? = null,
    )

    data class CineAnimeDetailData(
        @param:JsonProperty("Media") val media: CineAnimeMedia? = null,
    )

    data class CineAnimeTitle(
        @param:JsonProperty("english") val english: String? = null,
        @param:JsonProperty("romaji") val romaji: String? = null,
        @param:JsonProperty("native") val native: String? = null,
    )

    data class CineAnimeCover(
        @param:JsonProperty("extraLarge") val extraLarge: String? = null,
        @param:JsonProperty("large") val large: String? = null,
    )

    data class CineAnimeDate(
        @param:JsonProperty("year") val year: Int? = null,
        @param:JsonProperty("month") val month: Int? = null,
        @param:JsonProperty("day") val day: Int? = null,
    )

    data class CineAnimeStreamingEpisode(
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("thumbnail") val thumbnail: String? = null,
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("site") val site: String? = null,
    )

    data class CineAnimeTmdbSearch(
        @param:JsonProperty("results") val results: List<CineAnimeTmdbResult>? = null,
    )

    data class CineAnimeTmdbResult(
        @param:JsonProperty("id") val id: Int? = null,
    )

    data class CineAnimeAnizip(
        @param:JsonProperty("episodeCount") val episodeCount: Int? = null,
        @param:JsonProperty("episodes") val episodes: Map<String, CineAnimeAnizipEpisode>? = null,
    )

    data class CineAnimeAnizipEpisode(
        @param:JsonProperty("title") val title: CineAnimeAnizipTitle? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("image") val image: String? = null,
        @param:JsonProperty("absoluteEpisodeNumber") val absoluteEpisodeNumber: Int? = null,
        @param:JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @param:JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @param:JsonProperty("airDate") val airDate: String? = null,
        @param:JsonProperty("runtime") val runtime: Int? = null,
        @param:JsonProperty("length") val length: Int? = null,
    )

    data class CineAnimeAnizipTitle(
        @param:JsonProperty("en") val en: String? = null,
        @param:JsonProperty("x-jat") val xjat: String? = null,
        @param:JsonProperty("ja") val ja: String? = null,
    )
}
