package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val nuvioVidrockApi = "https://vidrock.ru"
const val nuvioTorrentioApi = "https://torrentio.strem.fun/limit=4"
const val nuvioUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

fun nuvioBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

fun nuvioQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") || lower.contains("2160") -> 2160
        lower.contains("1440") || lower.contains("2k") -> 1440
        lower.contains("1080") -> 1080
        lower.contains("720") -> 720
        lower.contains("480") -> 480
        lower.contains("360") -> 360
        else -> Qualities.Unknown.value
    }
}

fun decryptNuvioVidrockUrl(encryptedPayload: String): String? {
    return try {
        val aesKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        val keyBytes = aesKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var standardBase64 = encryptedPayload.replace("-", "+").replace("_", "/")
        while (standardBase64.length % 4 != 0) standardBase64 += "="
        val encryptedData = base64DecodeArray(standardBase64)
        val nonce = encryptedData.copyOfRange(0, 12)
        val cipherTextWithTag = encryptedData.copyOfRange(12, encryptedData.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        String(cipher.doFinal(cipherTextWithTag), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("Nuvio", "Vidrock decryption failed: ${e.message}")
        null
    }
}

data class NuvioTorrentioResponse(val streams: List<NuvioTorrentioStream> = emptyList())

data class NuvioTorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
)

data class NuvioSubtitleResponse(val subtitles: List<NuvioSubtitle> = emptyList())

data class NuvioSubtitle(
    val lang: String? = null,
    val lang_code: String? = null,
    val url: String? = null,
)

fun buildNuvioMagnet(stream: NuvioTorrentioStream): String? {
    val hash = stream.infoHash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val trackers = stream.sources
        ?.asSequence()
        ?.filter { it.startsWith("tracker:", ignoreCase = true) }
        ?.map { it.substringAfter("tracker:").trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.joinToString("") { "&tr=$it" }
        ?: ""
    return "magnet:?xt=urn:btih:$hash&dn=$hash$trackers&index=${stream.fileIdx ?: 0}"
}

object NuvioExtractors {
    suspend fun invokeVidrock(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val query = if (type == "movie") "$tmdbId" else "${tmdbId}_${season}_${episode}"
        val apiUrl = "$nuvioVidrockApi/api/$type/$query/"
        val headers = mapOf(
            "Origin" to nuvioVidrockApi,
            "Referer" to "$nuvioVidrockApi/",
            "User-Agent" to nuvioUserAgent,
        )
        val responseText = try {
            app.get(apiUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e("Nuvio", "Vidrock request failed: ${e.message}")
            return
        }
        val jsonObject = try {
            JSONObject(responseText)
        } catch (e: Exception) {
            Log.e("Nuvio", "Vidrock bad json: ${e.message}")
            return
        }
        jsonObject.keys().forEach { serverName ->
            val serverData = jsonObject.optJSONObject(serverName) ?: return@forEach
            val encryptedUrl = serverData.optString("url", "")
            if (encryptedUrl.isEmpty() || encryptedUrl == "error" || encryptedUrl == "null") return@forEach
            val decryptedUrl = decryptNuvioVidrockUrl(encryptedUrl) ?: return@forEach
            Log.d("Nuvio", "Vidrock[$serverName]: $decryptedUrl")
            val linkType =
                if (decryptedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            callback.invoke(
                newExtractorLink(
                    "Nuvio Vidrock[$serverName]",
                    "Nuvio Vidrock[$serverName]",
                    decryptedUrl,
                    linkType,
                ) {
                    this.headers = headers
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    suspend fun invokeTorrentio(
        imdbId: String? = null,
        kitsuId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = if (!kitsuId.isNullOrBlank()) "kitsu:$kitsuId" else imdbId?.trim()
        if (id.isNullOrBlank()) return
        val url = if (season == null) {
            "$nuvioTorrentioApi/stream/movie/$id.json"
        } else if (id.startsWith("kitsu:")) {
            "$nuvioTorrentioApi/stream/series/$id:${episode ?: 1}.json"
        } else {
            "$nuvioTorrentioApi/stream/series/$id:$season:$episode.json"
        }
        val res = try {
            app.get(url, timeout = 200L).parsedSafe<NuvioTorrentioResponse>()
        } catch (e: Exception) {
            Log.e("Nuvio", "Torrentio request failed: ${e.message}")
            null
        } ?: return
        res.streams.forEach { stream ->
            val rawTitle = stream.title ?: stream.description ?: stream.name ?: ""
            val seeders = Regex("""[👤👥]\s*(\d+)""").find(rawTitle)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            if (seeders < 10) return@forEach
            val size = Regex("""💾\s*([0-9.]+\s*[A-Za-z]+)""").find(rawTitle)
                ?.groupValues?.getOrNull(1) ?: ""
            val magnet = buildNuvioMagnet(stream) ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Nuvio Torrentio🧲",
                    "Nuvio Torrentio 🧲 | 👤 $seeders | $size".trim(),
                    magnet,
                    ExtractorLinkType.MAGNET,
                ) {
                    this.quality = nuvioQuality(stream.name ?: rawTitle)
                }
            )
        }
    }

    suspend fun invokeSubtitles(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val subEndpoints = listOf(
            "https://opensubtitles.stremio.homes/en|hi|de|ar|tr|es|ta|te|ru|ko/ai-translated=true|from=all|auto-adjustment=true",
            "https://subsense.nepiraw.com/n0tcjfba-{\"languages\":[\"en\",\"hi\",\"ta\",\"es\",\"ar\"],\"maxSubtitles\":10}",
        )
        coroutineScope {
            subEndpoints.map { endpoint ->
                async {
                    try {
                        val url = if (season != null) {
                            "$endpoint/subtitles/series/$id:$season:$episode.json"
                        } else {
                            "$endpoint/subtitles/movie/$id.json"
                        }
                        val parsed = app.get(url).parsedSafe<NuvioSubtitleResponse>() ?: return@async
                        parsed.subtitles.forEach {
                            val lang = it.lang ?: it.lang_code ?: "Unknown"
                            val fileUrl = it.url ?: return@forEach
                            subtitleCallback.invoke(newSubtitleFile(lang, fileUrl))
                        }
                    } catch (e: Exception) {
                        Log.d("Nuvio", "Subtitle fetch failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }
}
