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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import kotlin.text.Regex

object Pahe : ExtractorApi() {

    override val name: String = "Pahe"
    override val mainUrl: String = "https://pahe.win"
    override val requiresReferer: Boolean = true

    private val kwikParamsRegex = Regex("\\(\"(\\w+)\",\\d+,\"(\\w+)\",(\\d+),(\\d+),\\d+\\)")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")
    private val client = OkHttpClient()

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String? {
        if (key.isEmpty() || v2 !in 2..36) return null
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val toFind = key.getOrNull(v2) ?: return null
        val sb = StringBuilder()
        var i = 0
        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            if (nextIndex == -1) break
            var decodedCharStr = ""
            for (j in i until nextIndex) {
                val num = keyIndexMap[fullString[j]] ?: return null
                decodedCharStr += num
            }
            if (decodedCharStr.isEmpty()) return null
            sb.append((decodedCharStr.toInt(v2) - v1).toChar())
            i = nextIndex + 1
        }
        return sb.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val noRedirects = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                // Step 1: GET {url}/i (no redirect) -> Location -> kwik page
                val initialRequest = Request.Builder()
                    .url("$url/i")
                    .get()
                    .build()
                val firstResponse: Response = noRedirects.newCall(initialRequest).execute()
                val loc1 = firstResponse.header("location") ?: return@launch
                val kwikUrl = "https://" + loc1.substringAfterLast("https://")
                firstResponse.close()

                // Step 2: GET kwik page
                val fContentRequest = Request.Builder()
                    .url(kwikUrl)
                    .header("referer", "https://kwik.cx/")
                    .get()
                    .build()
                val fRes: Response = client.newCall(fContentRequest).execute()
                val fText = fRes.body?.string() ?: return@launch

                // Step 3: Extract kwik params and decrypt
                val pm = kwikParamsRegex.find(fText) ?: return@launch
                val fullString = pm.groupValues[1]
                val key = pm.groupValues[2]
                val v1 = pm.groupValues[3].toIntOrNull() ?: return@launch
                val v2 = pm.groupValues[4].toIntOrNull() ?: return@launch
                val decrypted = decrypt(fullString, key, v1, v2) ?: return@launch

                // Step 4: Extract action URL and token
                val uri = kwikDUrl.find(decrypted)?.groupValues?.getOrNull(1) ?: return@launch
                val tok = kwikDToken.find(decrypted)?.groupValues?.getOrNull(1) ?: return@launch

                // Step 5: POST action with _token, retry to 302
                val cookie = fRes.headers("set-cookie").firstOrNull() ?: ""
                val noRedirectClient = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .cookieJar(client.cookieJar)
                    .build()

                var code = 419
                var tries = 0
                var location: String? = null
                while (code != 302 && tries < 20) {
                    val postRequest = Request.Builder()
                        .url(uri)
                        .header("user-agent", " Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .header("referer", kwikUrl)
                        .header("cookie", cookie)
                        .post(FormBody.Builder().add("_token", tok).build())
                        .build()
                    val postResponse: Response = noRedirectClient.newCall(postRequest).execute()
                    code = postResponse.code
                    location = postResponse.header("location") ?: postResponse.header("Location")
                    postResponse.close()
                    tries++
                }

                val finalUrl = location ?: return@launch

                callback(
                    newExtractorLink(
                        "Pahe Download",
                        "Pahe Download",
                        finalUrl,
                        ExtractorLinkType.VIDEO,
                    ).apply {
                        this.referer = "https://kwik.cx/"
                        this.headers = mapOf("Referer" to "https://kwik.cx/")
                    }
                )
            } catch (e: Exception) {
                Log.d("Pahe", "Error: ${e.message}")
            }
        }
    }
}
