package com.Kayracs3.daddylive

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
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// iptv-org API şemasının kullandığımız kısmı (https://iptv-org.github.io/api/)
data class DlIptvChannel(
    val id: String? = null,
    val name: String? = null,
    @JsonProperty("alt_names") val altNames: List<String>? = null
)

data class DlIptvLogo(
    val channel: String? = null,
    val url: String? = null
)

/**
 * DaddyLive 24/7 kanal kataloğu.
 *
 * Sitenin JSON kanal uç noktası özel API anahtarı istediği için herkese açık
 * HTML kataloğu okunur. Korumalı API'yi veya oynatıcı doğrulamasını atlatmaya
 * çalışmaz.
 */
class DaddyLive : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive 24/7"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = true
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        private const val PAGE_SIZE = 100
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        // İlk çalışan adres kullanılır
        private val DOMAINS = listOf(
            "https://thedaddy.li",
            "https://daddylive.pk",
            "https://dlhd.pk",
            "https://dlhd.st",
            "https://dlstreams.st",
            "https://dlive.sx"
        )

        private val channelIdRegex = Regex("""[?&]id=(\d+)""")

        // Afiş eşleştirmede atılan ekler
        private val DROP_TOKENS = setOf(
            "hd", "fhd", "sd", "uhd", "4k", "tv", "live", "canli", "channel", "kanal",
            "uk", "usa", "us", "tr", "turkey", "de", "fr", "es"
        )
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
    }.getOrDefault(emptyMap())

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
        val document = app.get(
            "$base/24-7-channels.php",
            headers = requestHeaders(base)
        ).document

        val cards = document.select(
            "div.grid > a, a.card, a[href*='watch.php?id=']"
        )

        val logoDb = logos()
        val uniqueChannels = linkedMapOf<String, LiveSearchResponse>()
        for (card in cards) {
            val href = card.attr("href").ifBlank {
                card.selectFirst("a[href]")?.attr("href").orEmpty()
            }
            val channelId = channelIdRegex.find(href)?.groupValues?.getOrNull(1)
                ?: continue

            val channelName = card.selectFirst(
                ".card__title, .card-title, .channel-title, .channel-name"
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.text().trim()

            if (channelName.isBlank()) continue

            val channelUrl = card.absUrl("href").ifBlank {
                "$base/${href.trimStart('/')}"
            }

            // Afiş: önce sayfadaki görsel, yoksa iptv-org logosu
            val pageImg = card.selectFirst("img")?.let { i ->
                i.absUrl("data-src").ifBlank { i.absUrl("src") }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            val poster = pageImg ?: logoDb[normalize(channelName)]

            uniqueChannels[channelId] = newLiveSearchResponse(
                channelName,
                channelUrl,
                TvType.Live
            ) {
                this.posterUrl = poster
            }
        }

        return uniqueChannels.values.sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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
        return getChannels().filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("DaddyLive kanal ID bulunamadı")

        val known = runCatching {
            getChannels().firstOrNull {
                channelIdRegex.find(it.url)?.groupValues?.getOrNull(1) == channelId
            }
        }.getOrNull()

        val base = baseUrl()
        val title = known?.name ?: runCatching {
            app.get(url, headers = requestHeaders(base))
                .document
                .selectFirst("h1, h2")
                ?.text()
                ?.substringBefore("(")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Channel $channelId"

        // Oynatıcı klasörleri loadLinks içinde sırayla denenir.
        val playerTemplate = "$base/{folder}/stream-$channelId.php"
        return newLiveStreamLoadResponse(title, url, playerTemplate) {
            this.posterUrl = known?.posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = baseUrl()
        val playerFolders = listOf(
            "stream",   // Player 1
            "cast",     // Player 2
            "watch",    // Player 3
            "plus",     // Player 4
            "casting",  // Player 5
            "player",   // Player 6
            "hub"       // Player 7
        )

        // Alan adı yedeklemesi nedeniyle loadExtractor'a güvenmeden doğrudan çağır
        val extractor = DaddyLivePlayerExtractor()
        var emittedLinks = 0
        for (folder in playerFolders) {
            val playerUrl = data.replace("{folder}", folder)
            runCatching {
                extractor.getUrl(playerUrl, "$base/", subtitleCallback) { link ->
                    callback(link)
                    emittedLinks++
                }
            }
            // İlk çalışan oynatıcıda dur
            if (emittedLinks > 0) break
        }
        return emittedLinks > 0
    }
}

/**
 * DaddyLive oynatıcı sayfalarını çözer: önce doğrudan medya adresi arar, sonra
 * bilinen embed'leri CloudStream extractor'larına devreder, en son
 * WebViewResolver ile oynatıcının normal .m3u8 isteğini yakalar.
 */
class DaddyLivePlayerExtractor : ExtractorApi() {
    override val mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive Player"
    override val requiresReferer = false

    companion object {
        private val m3u8Regex = Regex(
            """https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
        else "${uri.scheme}://${uri.host}"
    }.getOrNull()

    private fun absoluteUrl(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return runCatching { URI(base).resolve(raw).toString() }.getOrNull()
    }

    private suspend fun emitM3u8(
        mediaUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        // CDN'ler Origin olarak genelde oynatıcı sayfasının adresini bekler
        val origin = originOf(pageUrl) ?: originOf(mediaUrl) ?: mainUrl

        callback(
            newExtractorLink(
                source = name,
                name = "DaddyLive",
                url = mediaUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Origin" to origin
                )
            }
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(url, headers = headers(referer ?: "$mainUrl/"))
        }.getOrNull() ?: return

        // Olmayan oynatıcı yolu iframe/WebView çözümlemesini tetiklemesin
        if (response.code !in 200..299) return

        val document = response.document
        val normalizedHtml = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val directUrls = linkedSetOf<String>()
        m3u8Regex.findAll(normalizedHtml).forEach {
            directUrls.add(it.value.trimEnd(';', ')', ']'))
        }

        document.select("video source[src], video[src]").forEach { element ->
            val source = element.absUrl("src").ifBlank { element.attr("src") }
            if (source.contains(".m3u8", ignoreCase = true)) {
                directUrls.add(source)
            }
        }

        val direct = directUrls.firstOrNull()
        if (direct != null) {
            emitM3u8(direct, url, callback)
            return
        }

        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { element -> absoluteUrl(element.attr("src"), url) }
            .distinct()

        for (iframeUrl in iframeUrls) {
            var emitted = 0
            runCatching {
                loadExtractor(iframeUrl, url, subtitleCallback) {
                    callback(it)
                    emitted++
                }
            }
            if (emitted > 0) return

            // Kayıtlı extractor'u olmayan embed'ler (dembed dahil) için normal
            // HLS isteğini gözlemle
            val sniffedUrl = runCatching {
                app.get(
                    iframeUrl,
                    referer = url,
                    headers = headers(url),
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                        timeout = 8_000L
                    )
                ).url
            }.getOrNull()

            if (!sniffedUrl.isNullOrBlank() &&
                sniffedUrl.contains(".m3u8", ignoreCase = true)
            ) {
                emitM3u8(sniffedUrl, iframeUrl, callback)
                return
            }
        }
    }
}

/*
 * Plugin sınıfında şu kadarı yeterli (extractor'u ayrıca kaydetmek gerekmez,
 * loadLinks doğrudan çağırıyor):
 *
 * @CloudstreamPlugin
 * class DaddyLivePlugin : Plugin() {
 *     override fun load(context: Context) {
 *         registerMainAPI(DaddyLive())
 *     }
 * }
 */
