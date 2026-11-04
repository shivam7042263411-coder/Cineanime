package com.megix.settings

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.megix.ProviderRegistry

object Settings {

    // ── Global keys ──────────────────────────────────────────
    const val CONCURRENCY_KEY          = "ScrapeConcurrency"
    const val DOWNLOAD_ENABLE          = "DownloadEnable"
    const val PROVIDER_CINESTREAM      = "ProviderCineStream"
    const val PROVIDER_SIMKL           = "ProviderSimkl"
    const val PROVIDER_TMDB            = "ProviderTmdb"
    const val PROVIDER_ANIME           = "ProviderCineAnime"
    const val SHOWBOX_TOKEN_KEY        = "showbox_ui_token"
    const val WYZIE_SUBS_KEY           = "wyzie_subs_api_key"
    const val GRAMCINEMA_TOKEN_KEY     = "gramcinema_bearer_token"
    const val STREMIO_ADDONS_KEY       = "stremio_addons"
    const val NEW_PROVIDER_DEFAULT_ON  = "new_provider_default_on"
    const val CF_BYPASS_ENABLED        = "cloudflare_webview_bypass_enabled"
    const val CF_USER_AGENT_KEY        = "cf_user_agent"
    const val CF_WEBVIEW_HIDDEN        = "cloudflare_webview_hidden"

    // ── Per-domain cookie timestamp (Cloudflare bypass staleness) ────────
    private const val CF_COOKIE_SAVED_AT_PREFIX = "cf_cookie_saved_at_"

    private fun cookieTsKeyForDomain(urlOrDomain: String): String {
        val domain = if (urlOrDomain.contains("://")) {
            Uri.parse(urlOrDomain).host?.lowercase()?.removePrefix("www.")
                ?: normalizeBypassDomain(urlOrDomain)
        } else {
            normalizeBypassDomain(urlOrDomain)
        }
        return "$CF_COOKIE_SAVED_AT_PREFIX$domain"
    }

    /** Save the time (epoch ms) when a cookie was last refreshed for a domain. */
    fun saveCookieTimestamp(urlOrDomain: String, ts: Long = System.currentTimeMillis()) {
        setKey(cookieTsKeyForDomain(urlOrDomain), ts)
    }

    /**
     * Last saved time for a domain, used for diagnostics/logging only.
     * Validity is decided by a real request (CineStreamExtractors.isCookieValid),
     * never by age: a cookie can be revoked while its timestamp is young.
     */
    fun getCookieTimestamp(urlOrDomain: String): Long? =
        getKey<Long>(cookieTsKeyForDomain(urlOrDomain))

    /** Is the Cloudflare bypass WebView hidden (invisible solve)? When true, no UI is shown. */
    fun cloudflareWebViewHidden(): Boolean = getKey<Boolean>(CF_WEBVIEW_HIDDEN) ?: true

    /** Is the Cloudflare bypass WebView visible (shown)? Opposite of cloudflareWebViewHidden(). */
    fun cloudflareWebViewVisible(): Boolean = !cloudflareWebViewHidden()

    fun saveCfUserAgent(userAgent: String) {
        if (userAgent.isNotBlank()) setKey(CF_USER_AGENT_KEY, userAgent.trim())
    }

    fun getCfUserAgent(): String? =
        getKey<String>(CF_USER_AGENT_KEY)?.takeIf { it.isNotBlank() }

    private const val SEEN_PROVIDERS_KEY = "seen_providers"
    private const val PROVIDER_ORDER_KEY = "provider_order"

    // ── Configuration Getters ────────────────────────────────
    fun getConcurrency(): Int = getKey<Int>(CONCURRENCY_KEY) ?: 10

    val allowDownloadLinks: Boolean
        get() = getKey<Boolean>(DOWNLOAD_ENABLE) ?: false

    val activeProviderOrder: List<String>
        get() = getOrder().filter { enabled(it) }

    fun cloudflareBypassEnabled(): Boolean = getKey<Boolean>(CF_BYPASS_ENABLED) ?: false

    private fun normalizeBypassDomain(domain: String): String {
        return domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .lowercase()
            .trim()
    }

    data class BypassDomain(val domain: String, val displayName: String)

    private val CLOUDFLARE_BYPASS_SITES = listOf(
        BypassDomain("animepahe.pw", "AnimePahe"),
        BypassDomain("animepahe.org", "AnimePahe (mirror)"),
        BypassDomain("animepahe.com", "AnimePahe (mirror)"),
        BypassDomain("kwik.cx", "Kwik"),
    )

    fun getCloudflareBypassDomains(): List<BypassDomain> = CLOUDFLARE_BYPASS_SITES

    fun hasCloudflareBypassForUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.trim().orEmpty()
        if (host.isBlank()) return false
        return CLOUDFLARE_BYPASS_SITES.any { site ->
            val normalizedDomain = normalizeBypassDomain(site.domain)
            normalizedDomain.isNotBlank() && (host == normalizedDomain || host.endsWith(".$normalizedDomain"))
        }
    }

    // ── Per-domain cookie persistence (Cloudflare bypass) ────
    private const val CF_COOKIE_KEY_PREFIX = "cf_cookie_"

    private fun cookieKeyForDomain(urlOrDomain: String): String {
        val domain = if (urlOrDomain.contains("://")) {
            Uri.parse(urlOrDomain).host?.lowercase()?.removePrefix("www.")
                ?: normalizeBypassDomain(urlOrDomain)
        } else {
            normalizeBypassDomain(urlOrDomain)
        }
        return "$CF_COOKIE_KEY_PREFIX$domain"
    }

    fun saveCookieForDomain(urlOrDomain: String, cookies: String) {
        setKey(cookieKeyForDomain(urlOrDomain), cookies.trim())
        saveCookieTimestamp(urlOrDomain)
    }

    fun getCookieForDomain(urlOrDomain: String): String? =
        getKey<String>(cookieKeyForDomain(urlOrDomain))?.takeIf { it.isNotBlank() }

    fun hasSavedCookieForDomain(urlOrDomain: String): Boolean =
        !getCookieForDomain(urlOrDomain).isNullOrBlank()

    fun removeCookieForDomain(urlOrDomain: String) {
        setKey(cookieKeyForDomain(urlOrDomain), null as String?)
        setKey(cookieTsKeyForDomain(urlOrDomain), null as Long?)
    }

    /**
     * Remove every Cloudflare cookie this plugin saved, plus the clearance
     * cookies still sitting in the shared WebView jar. Clearing only the
     * stored copy would not be enough: [CineStreamExtractors.injectWebviewCookies]
     * falls back to the jar, so a still-valid clearance would keep working and
     * "clear cookies" would look like a no-op.
     *
     * @return how many domains had a saved cookie.
     */
    fun clearAllCookies(): Int {
        var cleared = 0
        getCloudflareBypassDomains().forEach { site ->
            if (hasSavedCookieForDomain(site.domain)) cleared++
            setKey(cookieKeyForDomain(site.domain), null as String?)
            setKey(cookieTsKeyForDomain(site.domain), null as Long?)
        }

        runCatching {
            val cookieManager = CookieManager.getInstance()
            getCloudflareBypassDomains().forEach { site ->
                linkedSetOf(
                    "https://${site.domain}",
                    "https://www.${site.domain}",
                ).forEach { host ->
                    val url = "$host/"
                    val existing = cookieManager.getCookie(url) ?: return@forEach
                    existing.split(";").forEach { part ->
                        val name = part.trim().substringBefore('=').trim()
                        if (name == "cf_clearance" || name == "__cf_bm" || name.startsWith("cf_chl")) {
                            // A host-only delete misses a cookie stored with
                            // an explicit Domain attribute, which made "Clear
                            // all cookies" leave a usable token behind.
                            listOf("", "; Domain=.${site.domain}", "; Domain=${site.domain}").forEach { domainAttr ->
                                cookieManager.setCookie(
                                    url,
                                    "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/$domainAttr",
                                )
                            }
                        }
                    }
                }
            }
            cookieManager.flush()
        }
        return cleared
    }

    // ── Dynamic Provider Maps ────────────────────────────────
    // We dynamically pull these from our single source of truth (ProviderRegistry)!
    val TORRENT_KEYS: Set<String> get() = ProviderRegistry.torrentKeys
    val PROVIDER_NAMES: Map<String, String> get() = ProviderRegistry.namesMap
    val DEFAULT_ORDER: List<String> get() = ProviderRegistry.keys

    // ── Provider ordering ────────────────────────────────────

    fun newProviderDefaultOn(): Boolean = getKey<Boolean>(NEW_PROVIDER_DEFAULT_ON) ?: true

    fun getSeenProviders(): Set<String> =
        getKey<String>(SEEN_PROVIDERS_KEY)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    fun initSeenProviders() {
        val stremioKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown    = (DEFAULT_ORDER + stremioKeys).toSet()
        val currentSeen = getSeenProviders()

        if (currentSeen.isEmpty()) {
            setKey(SEEN_PROVIDERS_KEY, allKnown.joinToString(","))
            return
        }

        val defaultState = newProviderDefaultOn()

        allKnown.forEach { key ->
            if (key !in currentSeen
                && !key.startsWith("stremio_")
                && key !in TORRENT_KEYS
                && getKey<Boolean>(key) == null
            ) {
                setKey(key, defaultState)
            }
        }

        val removedKeys = currentSeen - allKnown
        removedKeys.forEach { setKey(it, null as Boolean?) }
        val prunedSeen  = (currentSeen - removedKeys) + allKnown
        setKey(SEEN_PROVIDERS_KEY, prunedSeen.joinToString(","))
    }

    fun markProvidersSeen(keys: Collection<String>) {
        val currentlySeen = getSeenProviders()
        val defaultState  = newProviderDefaultOn()
        keys.forEach { key ->
            if (key !in currentlySeen
                && !key.startsWith("stremio_")
                && key !in TORRENT_KEYS
                && getKey<Boolean>(key) == null
            ) {
                setKey(key, defaultState)
            }
        }
        val merged = currentlySeen + keys
        setKey(SEEN_PROVIDERS_KEY, merged.joinToString(","))
    }

    fun enabled(key: String): Boolean {
        val explicit = getKey<Boolean>(key)
        if (explicit != null) return explicit
        if (key.startsWith("stremio_")) return true
        if (key in TORRENT_KEYS) return false
        if (key !in getSeenProviders()) return newProviderDefaultOn()
        return true
    }

    fun getOrder(): List<String> {
        val stremioKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown    = DEFAULT_ORDER + stremioKeys
        val saved       = getKey<String>(PROVIDER_ORDER_KEY)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: return allKnown

        val validSaved = saved.filter { it in allKnown }
        return validSaved + (allKnown - validSaved.toSet())
    }

    fun saveOrder(order: List<String>) =
        setKey(PROVIDER_ORDER_KEY, order.joinToString(","))

    // ── Stremio addon helpers ────────────────────────────────

    enum class AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }

    data class StremioAddon(val name: String, val url: String, val type: AddonType)

    fun stremioAddonKey(name: String): String =
        "stremio_${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    fun providerDisplayName(key: String): String {
        PROVIDER_NAMES[key]?.let { return it }
        if (key.startsWith("stremio_")) {
            val addon = getStremioAddons().firstOrNull { stremioAddonKey(it.name) == key }
            if (addon != null) {
                val icon = when (addon.type) {
                    AddonType.TORRENT  -> "🧲"
                    AddonType.DEBRID   -> "☁️"
                    AddonType.SUBTITLE -> "📝"
                    AddonType.HTTPS    -> "🔌"
                }
                return "$icon ${addon.name}"
            }
            return "🔌 " + key.removePrefix("stremio_").replace("_", " ")
                .replaceFirstChar { it.uppercaseChar() }
        }
        return key
    }

    fun isStremioTorrent(key: String): Boolean {
        if (!key.startsWith("stremio_")) return false
        return getStremioAddons().firstOrNull { stremioAddonKey(it.name) == key }
            ?.type == AddonType.TORRENT
    }

    fun getStremioAddons(): MutableList<StremioAddon> {
        val raw = getKey<String>(STREMIO_ADDONS_KEY) ?: return mutableListOf()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val type = runCatching { AddonType.valueOf(parts[2]) }.getOrDefault(AddonType.HTTPS)
            StremioAddon(name = parts[0], url = parts[1], type = type)
        }.toMutableList()
    }

    fun saveStremioAddons(addons: List<StremioAddon>) {
        if (addons.isEmpty()) setKey(STREMIO_ADDONS_KEY, null as String?)
        else setKey(STREMIO_ADDONS_KEY, addons.joinToString("\n") { "${it.name}|${it.url}|${it.type}" })
        val validKeys = addons.map { stremioAddonKey(it.name) }.toSet()
        val current   = getKey<String>(PROVIDER_ORDER_KEY)
            ?.split(",")?.filter { it.isNotBlank() } ?: return
        val pruned = current.filter { !it.startsWith("stremio_") || it in validKeys }
        if (pruned.size != current.size) saveOrder(pruned)
    }

    // ── ShowBox token helpers ────────────────────────────────
    fun saveShowboxToken(token: String) = setKey(SHOWBOX_TOKEN_KEY, token.trim())
    fun getShowboxToken(): String?       = getKey<String>(SHOWBOX_TOKEN_KEY)?.takeIf { it.isNotBlank() }
    fun clearShowboxToken()              = setKey(SHOWBOX_TOKEN_KEY, null)

    // ── Wyzie Subs API key helpers ───────────────────────────
    fun saveWyzieSubsKey(key: String) = setKey(WYZIE_SUBS_KEY, key.trim())
    fun getWyzieSubsKey(): String?     = getKey<String>(WYZIE_SUBS_KEY)?.takeIf { it.isNotBlank() }
    fun clearWyzieSubsKey()            = setKey(WYZIE_SUBS_KEY, null)

    // ── GramCinema bearer token helpers ──────────────────────
    fun saveGramCinemaToken(token: String) = setKey(GRAMCINEMA_TOKEN_KEY, token.trim())
    fun getGramCinemaToken(): String?       = getKey<String>(GRAMCINEMA_TOKEN_KEY)?.takeIf { it.isNotBlank() }
    fun clearGramCinemaToken()              = setKey(GRAMCINEMA_TOKEN_KEY, null)

    // ── Entry point ──────────────────────────────────────────
    fun showSettingsDialog(context: Context, onSave: () -> Unit) =
        SettingsDialog.show(context, onSave)
}
