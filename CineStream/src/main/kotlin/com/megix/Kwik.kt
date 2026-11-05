package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import kotlin.text.Regex

object Kwik : ExtractorApi() {

    override val name: String = "Kwik"
    override val mainUrl: String = "https://kwik.cx"
    override val requiresReferer: Boolean = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val res = CineStreamExtractors.cfGet(
                    url,
                    headers = mapOf("referer" to "https://$mainUrl/"),
                    allowRedirects = true,
                ) ?: return@launch

                val html = res.text
                val doc = Jsoup.parse(html)
                val title = doc.title()

                // Find packed JS (p,a,c,k,e pattern)
                var script = doc.select("script").map { it.data() }.firstOrNull {
                    it.contains("function(p,a,c,k,e,") || it.contains("eval(function")
                }
                if (script.isNullOrBlank()) {
                    script = doc.selectFirst("script:containsData(eval)")?.data()
                }

                val unpacked = script ?: ""

                // Extract media URL
                val mediaUrl = Regex("""source\s*=\s*['"]([^'"]+)['"]""").find(unpacked)?.groupValues?.getOrNull(1)
                    ?: Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""").find(unpacked)?.groupValues?.getOrNull(1)
                    ?: Regex("""(https?://[^\s'"<>]+\.(?:mp4|m3u8)[^\s'"<>]*)""").find(unpacked)?.groupValues?.getOrNull(1)
                    ?: return@launch

                val isM3u8 = mediaUrl.contains(".m3u8")

                callback(
                    newExtractorLink(
                        "Kwik",
                        "Kwik",
                        mediaUrl,
                        ExtractorLinkType.VIDEO,
                    ).apply {
                        this.referer = "https://kwik.cx"
                        this.headers = mapOf("origin" to "https://kwik.cx")
                    }
                )

                // Download variant (/stream/ -> /mp4/)
                if (mediaUrl.contains("/stream/")) {
                    val fileName = title.substringBeforeLast(".mp4") + ".mp4"
                    val mp4Url = mediaUrl.replace("/stream/", "/mp4/")
                        .substringBeforeLast("/") + "?file=" + java.net.URLEncoder.encode(fileName, "UTF-8")
                    callback(
                        newExtractorLink(
                            "Kwik Download",
                            "Kwik Download",
                            mp4Url,
                            ExtractorLinkType.VIDEO,
                        ).apply {
                            this.referer = url
                            this.headers = mapOf("Referer" to url, "Origin" to "https://kwik.cx")
                        }
                    )
                }
            } catch (e: Exception) {
                Log.d("Kwik", "Error: ${e.message}")
            }
        }
    }
}
