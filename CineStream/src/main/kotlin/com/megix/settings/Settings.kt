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

    // ── CF cookie storage keys (Phisher pattern) ─────────────────────────
    private const val CF_COOKIES_KEY = "ANIMEPAHE_CF_COOKIES"
    private const val CF_USER_AGENT_KEY = "ANIMEPAHE_CF_USER_AGENT"
    private const val CF_COOKIE_HOST_KEY = "ANIMEPAHE_CF_COOKIE_HOST"
    private const val CF_WEBVIEW_ENABLED_KEY = "ANIMEPAHE_CF_WEBVIEW_ENABLED"
    private const val CF_BYPASS_ENABLED        = "cloudflare_webview_bypass_enabled"

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

    /** Last saved time for a domain, used for diagnostics/logging only. */
    fun getCookieTimestamp(urlOrDomain: String): Long? =
        getKey<Long>(cookieTsKeyForDomain(urlOrDomain))

    fun cloudflareWebViewHidden(): Boolean = getKey<Boolean>(CF_WEBVIEW_ENABLED_KEY) ?: true

    /** Is the Cloudflare bypass WebView visible (shown)? Opposite of hidden. */
    fun cloudflareWebViewVisible(): Boolean = !cloudflareWebViewHidden()

    /** Is the Cloudflare bypass WebView hidden (invisible solve)? Alias for cloudflareWebViewHidden(). */
    fun isCfWebviewHidden(): Boolean = cloudflareWebViewHidden()

    // ── CF cookie persistence ──────────────────────────────────────────────

    fun saveCfCookies(cookies: String) {
        if (cookies.isNotBlank()) {
            setKey(CF_COOKIES_KEY, cookies.trim())
        }
    }

    fun getCfCookies(): String =
        getKey<String>(CF_COOKIES_KEY) ?: ""

    fun saveCfUserAgent(ua: String) {
        if (ua.isNotBlank()) {
            setKey(CF_USER_AGENT_KEY, ua.trim())
        }
    }

    fun getCfUserAgent(): String =
        getKey<String>(CF_USER_AGENT_KEY) ?: ""

    fun saveCfCookieHost(host: String) {
        if (host.isNotBlank()) {
            setKey(CF_COOKIE_HOST_KEY, host.trim())
        }
    }

    fun getCfCookieHost(): String =
        getKey<String>(CF_COOKIE_HOST_KEY) ?: ""

    fun saveCfWebviewEnabled(enabled: Boolean) {
        setKey(CF_WEBVIEW_ENABLED_KEY, enabled)
    }

    fun isCfWebviewEnabled(): Boolean =
        getKey<Boolean>(CF_WEBVIEW_ENABLED_KEY) ?: true

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
     * Purges CF cookies from the shared WebView jar. Clears under host-only,
     * Domain=.host, and Domain=host forms so nothing lingers.
     */
    fun clearJarCookies(host: String) {
        val domain = host.removePrefix("www.")
        val urls = listOf(
            "https://$host/",
            "https://www.$host/",
        )
        val cookieNames = listOf("cf_clearance", "__cf_bm", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid")

        val manager = CookieManager.getInstance()
        urls.forEach { url ->
            cookieNames.forEach { name ->
                manager.setCookie(url, "$name=; domain=$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                manager.setCookie(url, "$name=; domain=.$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                manager.setCookie(url, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
        }
        manager.flush()
    }

    fun clearAll() {
        setKey(CF_COOKIES_KEY, null as String?)
        setKey(CF_USER_AGENT_KEY, null as String?)
        setKey(CF_COOKIE_HOST_KEY, null as String?)
        // Per-domain keys are the ones the solver actually writes; clearing
        // only the legacy global keys left every saved cf_clearance behind.
        CLOUDFLARE_BYPASS_SITES.forEach { removeCookieForDomain(it.domain) }
    }

    fun hasCookies(): Boolean =
        getCfCookies().isNotBlank()

    // ── Dynamic Provider Maps ────────────────────────────────
    val TORRENT_KEYS: Set<String> get() = ProviderRegistry.torrentKeys
    val PROVIDER_NAMES: Map<String, String> get() = ProviderRegistry.namesMap
    val DEFAULT_ORDER: List<String> get() = ProviderRegistry.keys

    // ── Provider ordering ────────────────────────────────────

    fun newProviderDefaultOn(): Boolean = getKey<Boolean>(NEW_PROVIDER_DEFAULT_ON) ?: true

    fun getSeenProviders(): Set<String> =
        getKey<String>("seen_providers")
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    fun initSeenProviders() {
        val stremioKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown    = (DEFAULT_ORDER + stremioKeys).toSet()
        val currentSeen = getSeenProviders()

        if (currentSeen.isEmpty()) {
            setKey("seen_providers", allKnown.joinToString(","))
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
        setKey("seen_providers", prunedSeen.joinToString(","))
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
        setKey("seen_providers", merged.joinToString(","))
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
        val saved       = getKey<String>("provider_order")
            ?.split(",")?.filter { it.isNotBlank() }
            ?: return allKnown

        val validSaved = saved.filter { it in allKnown }
        return validSaved + (allKnown - validSaved.toSet())
    }

    fun saveOrder(order: List<String>) =
        setKey("provider_order", order.joinToString(","))

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
        val current   = getKey<String>("provider_order")
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
