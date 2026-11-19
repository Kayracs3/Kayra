package com.Kayracs3.daddylive

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val LOG_TAG = "DaddyLive"

data class DlIptvChannel(
    val id: String? = null,
    val name: String? = null,
    @JsonProperty("alt_names") val altNames: List<String>? = null
)

data class DlIptvLogo(
    val channel: String? = null,
    val url: String? = null
)

// ======================================================================
// MainAPI
// ======================================================================
class DaddyLive : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive 24/7"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        private const val PAGE_SIZE = 100
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val DOMAINS = listOf(
            "https://thedaddy.li",
            "https://daddylive.pk",
            "https://dlhd.pk",
            "https://dlhd.st",
            "https://dlstreams.st",
            "https://dlive.sx"
        )

        private val channelIdRegex = Regex("""[?&]id=(\d+)""")

        private val DROP_TOKENS = setOf(
            "hd", "fhd", "sd", "uhd", "4k", "tv", "live", "canli", "channel", "kanal",
            "uk", "usa", "us", "tr", "turkey", "de", "fr", "es"
        )

        private val DEAD_DOMAINS = listOf("ksohls.ru")
        private val PRIORITY_DOMAINS = listOf("optimserve.shop")
    }

    private fun requestHeaders(base: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$base/"
    )

    // ------------------------------------------------------------------ alan adı
    private var resolvedBase: String? = null

    private suspend fun baseUrl(): String {
        resolvedBase?.let { return it }
        for (d in (listOf(mainUrl) + DOMAINS).distinct()) {
            val ok = runCatching {
                app.get(d, headers = requestHeaders(d), timeout = 8).code in 200..399
            }.getOrDefault(false)
            if (ok) {
                resolvedBase = d
                return d
            }
        }
        return mainUrl
    }

    // ------------------------------------------------------------------ afişler
    private fun normalize(name: String): String {
        val n = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ı", "i")
        return n.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in DROP_TOKENS }
            .joinToString("")
    }

    private val logoMutex = Mutex()
    private var logoMap: Map<String, String>? = null

    private suspend fun logos(): Map<String, String> {
        logoMap?.let { return it }
        return logoMutex.withLock {
            logoMap ?: buildLogoMap().also { if (it.isNotEmpty()) logoMap = it }
        }
    }

    private suspend fun buildLogoMap(): Map<String, String> = runCatching {
        val base = "https://iptv-org.github.io/api"
        val channels = app.get("$base/channels.json", timeout = 30)
            .parsedSafe<Array<DlIptvChannel>>() ?: return@runCatching emptyMap<String, String>()

        val logoById = HashMap<String, String>()
        app.get("$base/logos.json", timeout = 30)
            .parsedSafe<Array<DlIptvLogo>>()?.forEach {
                val c = it.channel
                val u = it.url
                if (!c.isNullOrBlank() && !u.isNullOrBlank()) logoById.putIfAbsent(c, u)
            }

        val out = HashMap<String, String>()
        for (ch in channels) {
            val url = logoById[ch.id ?: continue] ?: continue
            for (n in listOfNotNull(ch.name) + (ch.altNames ?: emptyList())) {
                val key = normalize(n)
                if (key.length >= 3) out.putIfAbsent(key, url)
            }
        }
        out
    }.onFailure { Log.w(LOG_TAG, "Logo haritası oluşturulamadı", it) }
        .getOrDefault(emptyMap())

    // ------------------------------------------------------------------ katalog
    private val cacheMutex = Mutex()
    private var cache: List<LiveSearchResponse> = emptyList()
    private var cacheTime = 0L

    private fun cacheFresh() =
        cache.isNotEmpty() && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS

    private suspend fun getChannels(): List<LiveSearchResponse> {
        if (cacheFresh()) return cache
        return cacheMutex.withLock {
            if (cacheFresh()) return@withLock cache
            val fresh = fetchChannels()
            if (fresh.isNotEmpty()) {
                cache = fresh
                cacheTime = System.currentTimeMillis()
            }
            if (fresh.isNotEmpty()) fresh else cache
        }
    }

    private suspend fun fetchChannels(): List<LiveSearchResponse> {
        val base = baseUrl()
        val response = app.get("$base/24-7-channels.php", headers = requestHeaders(base))
        val document = response.document

        if (document.select("a.card").isEmpty()) {
            val title = document.title()
            if (title.contains("Just a moment", true) ||
                title.contains("Attention Required", true) ||
                response.code == 403 || response.code == 503
            ) {
                throw ErrorLoadingException(
                    "Cloudflare engeli tespit edildi (HTTP ${response.code}). VPN deneyin."
                )
            }
        }

        val cards = document.select("div.grid > a, a.card, a[href*='watch.php?id=']")
        val logoDb = logos()
        val uniqueChannels = linkedMapOf<String, LiveSearchResponse>()

        for (card in cards) {
            val href = card.attr("href").ifBlank {
                card.selectFirst("a[href]")?.attr("href").orEmpty()
            }
            val channelId = channelIdRegex.find(href)?.groupValues?.getOrNull(1) ?: continue

            val channelName = card.selectFirst(
                ".card__title, .card-title, .channel-title, .channel-name"
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: card.attr("data-title").trim().takeIf { it.isNotBlank() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.text().substringBefore("ID:").trim().takeIf { it.isNotBlank() }
                ?: continue

            val channelUrl = card.absUrl("href").ifBlank { "$base/${href.trimStart('/')}" }

            val pageImg = card.selectFirst("img")?.let { i ->
                i.absUrl("data-src").ifBlank { i.absUrl("src") }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            val poster = pageImg ?: logoDb[normalize(channelName)]

            uniqueChannels[channelId] = newLiveSearchResponse(channelName, channelUrl, TvType.Live) {
                this.posterUrl = poster
            }
        }

        return uniqueChannels.values.sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val pageChannels = channels.drop(start).take(PAGE_SIZE)
        return newHomePageResponse(
            listOf(HomePageList("24/7 Channels", pageChannels, false)),
            start + pageChannels.size < channels.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getChannels().filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    // ------------------------------------------------------------------ load
    override suspend fun load(url: String): LoadResponse {
        val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("DaddyLive kanal ID bulunamadı")

        val base = baseUrl()

        val response = runCatching {
            app.get(url, headers = requestHeaders(base), interceptor = CloudflareKiller())
        }.onFailure { Log.d(LOG_TAG, "watch.php çekilemedi: $url (${it.message})") }
            .getOrNull()

        val document = response?.document

        val title = document?.selectFirst("h2")?.text()
            ?.replace(Regex("""\(ID\s*\d+\)"""), "")
            ?.substringBefore("(")?.trim()?.takeIf { it.isNotBlank() }
            ?: "Channel $channelId"

        val playerUrls = linkedSetOf<String>()

        document?.select("button.player-btn[data-url]")?.forEach { btn ->
            val u = btn.attr("data-url").trim()
            if (u.isNotBlank()) playerUrls.add(u)
        }

        document?.selectFirst("iframe#playerFrame[src]")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { playerUrls.add(it) }

        if (playerUrls.isEmpty()) {
            listOf("stream", "cast", "watch", "plus", "casting", "player", "hub").forEach { f ->
                playerUrls.add("$base/$f/stream-$channelId.php")
            }
        }

        val sorted = playerUrls
            .filterNot { u -> DEAD_DOMAINS.any { u.contains(it) } }
            .sortedByDescending { u -> PRIORITY_DOMAINS.any { u.contains(it) } }

        val data = sorted.joinToString("\n")
        return newLiveStreamLoadResponse(title, url, data) { /* poster yok */ }
    }

    // ------------------------------------------------------------------ loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = baseUrl()
        val playerUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val extractor = DaddyLivePlayerExtractor()
        var emitted = 0

        for (playerUrl in playerUrls) {
            Log.d(LOG_TAG, "Deneniyor: $playerUrl")
            runCatching {
                extractor.getUrl(playerUrl, "$base/", subtitleCallback) { link ->
                    callback(link)
                    emitted++
                }
            }.onFailure { Log.d(LOG_TAG, "Oynatıcı başarısız: $playerUrl (${it.message})") }
            if (emitted > 0) break
        }

        return emitted > 0
    }
}

// ======================================================================
// Extractor
// ======================================================================
class DaddyLivePlayerExtractor : ExtractorApi() {
    override val mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive Player"
    override val requiresReferer = false

    companion object {
        private val mediaRegex = Regex(
            """(?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val mediaCheck = Regex("""(?i)\.(m3u8|mp4)""")
        private val deadDomains = listOf("ksohls.ru")
    }

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    private fun absoluteUrl(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return runCatching { URI(base).resolve(raw).toString() }.getOrNull()
    }

    /**
     * [DÜZELTME 1] Origin başlığı eklendi. 403 hatasının asıl nedeni buydu.
     * [DÜZELTME 2] M3u8Helper kullanılarak master playlist'in alt parçaları çözülüyor.
     */
    private suspend fun emit(
        mediaUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Origin başlığı için sayfanın kendi domainini kullan (örn: https://dembed.st)
        val origin = runCatching {
            val uri = URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: mainUrl

        val commonHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to pageUrl,
            "Origin" to origin
        )

        if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
            // Master playlist ise alt varyantları çöz, değilse doğrudan linki ilet
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = mediaUrl,
                referer = pageUrl,
                headers = commonHeaders
            )
            if (links.isEmpty()) return false

            links.forEach { m3u8Link ->
                callback(m3u8Link)
            }
            return true
        } else {
            // .mp4 gibi doğrudan medya linkleri için normal ExtractorLink
            callback(
                newExtractorLink(
                    source = name,
                    name = "DaddyLive",
                    url = mediaUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = commonHeaders
                }
            )
            return true
        }
    }

    /** HTML içinden m3u8/mp4 linki çıkarır (video etiketi öncelikli). */
    private fun extractFromHtml(html: String, pageUrl: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")

        runCatching {
            val doc = org.jsoup.Jsoup.parse(normalized, pageUrl)
            doc.select("video source[src], video[src], source[src]").forEach { el ->
                val s = el.absUrl("src").ifBlank { el.attr("src") }
                if (s.contains(mediaCheck)) return s
            }
        }

        mediaRegex.findAll(normalized).forEach { m ->
            val raw = m.value.trimEnd(';', ')', ']')
            val abs = absoluteUrl(raw, pageUrl) ?: raw
            if (abs.startsWith("http", true)) return abs
        }
        return null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "$mainUrl/"

        if (deadDomains.any { url.contains(it) }) {
            Log.d(LOG_TAG, "Ölü domain atlandı: $url")
            return
        }

        val response = runCatching {
            app.get(url, headers = headers(ref), interceptor = CloudflareKiller())
        }.onFailure { Log.d(LOG_TAG, "CF isteği başarısız: $url (${it.message})") }
            .getOrNull()

        if (response != null && response.code in 200..299) {
            extractFromHtml(response.text, url)?.let {
                Log.d(LOG_TAG, "Doğrudan akış: $it")
                val emitted = runCatching { emit(it, url, callback) }
                    .onFailure { Log.d(LOG_TAG, "Statik akış doğrulanamadı: $url (${it.message})") }
                    .getOrDefault(false)
                if (emitted) return
                Log.d(LOG_TAG, "Statik medya URL'si oynatılabilir değil; dinamik oynatıcı isteği denenecek: $it")
            }

            val iframes = response.document.select("iframe[src]")
                .mapNotNull { absoluteUrl(it.attr("src"), url) }
                .filterNot { u -> deadDomains.any { u.contains(it) } }
                .distinct()

            for (iframeUrl in iframes) {
                val iframeResp = runCatching {
                    app.get(iframeUrl, referer = url, headers = headers(url),
                        interceptor = CloudflareKiller())
                }.onFailure { Log.d(LOG_TAG, "iframe CF başarısız: $iframeUrl") }
                    .getOrNull()

                if (iframeResp != null && iframeResp.code in 200..299) {
                    extractFromHtml(iframeResp.text, iframeUrl)?.let {
                        Log.d(LOG_TAG, "iframe içinden akış: $it")
                        val emitted = runCatching { emit(it, iframeUrl, callback) }
                            .onFailure { Log.d(LOG_TAG, "iframe akışı doğrulanamadı: $iframeUrl (${it.message})") }
                            .getOrDefault(false)
                        if (emitted) return
                        Log.d(LOG_TAG, "iframe içindeki statik medya URL'si geçerli değil; devam ediliyor: $iframeUrl")
                    }
                    iframeResp.document.select("iframe[src]").forEach { nested ->
                        val nestedUrl = absoluteUrl(nested.attr("src"), iframeUrl) ?: return@forEach
                        if (deadDomains.any { nestedUrl.contains(it) }) return@forEach
                        val nestedResp = runCatching {
                            app.get(nestedUrl, referer = iframeUrl, headers = headers(iframeUrl),
                                interceptor = CloudflareKiller())
                        }.getOrNull()
                        if (nestedResp != null && nestedResp.code in 200..299) {
                            extractFromHtml(nestedResp.text, nestedUrl)?.let {
                                Log.d(LOG_TAG, "nested iframe akışı: $it")
                                val emitted = runCatching { emit(it, nestedUrl, callback) }
                                    .onFailure { Log.d(LOG_TAG, "nested iframe akışı doğrulanamadı: $nestedUrl (${it.message})") }
                                    .getOrDefault(false)
                                if (emitted) return
                                Log.d(LOG_TAG, "nested iframe statik medya URL'si geçerli değil; devam ediliyor: $nestedUrl")
                            }
                        }
                    }
                }
            }
        } else {
            Log.d(LOG_TAG, "CF sonrası HTTP ${response?.code}: $url")
        }

        Log.d(LOG_TAG, "WebView fallback: $url")
        val sniffed = runCatching {
            app.get(
                url,
                referer = ref,
                headers = headers(ref),
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                    timeout = 30_000L
                )
            ).url
        }.onFailure { Log.d(LOG_TAG, "WebView başarısız: $url (${it.message})") }
            .getOrNull()

        if (!sniffed.isNullOrBlank() && sniffed.contains(mediaCheck)) {
            Log.d(LOG_TAG, "WebView akışı: $sniffed")
            val emitted = runCatching { emit(sniffed, url, callback) }
                .onFailure { Log.d(LOG_TAG, "Yakalanan medya akışı doğrulanamadı: $url (${it.message})") }
                .getOrDefault(false)
            if (!emitted) Log.w(LOG_TAG, "Yakalanan medya URL'si geçerli playlist üretmedi: $sniffed")
        } else {
            Log.w(LOG_TAG, "Akış bulunamadı: $url")
        }
    }
}

/*
 * Plugin kayıt sınıfı (başka dosyadaysa sil):
 *
 * import android.content.Context
 * import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
 * import com.lagradost.cloudstream3.plugins.Plugin
 *
 * @CloudstreamPlugin
 * class DaddyLivePlugin : Plugin() {
 *     override fun load(context: Context) {
 *         registerMainAPI(DaddyLive())
 *     }
 * }
 */
