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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

data class DlIptvChannel(
    val id: String? = null,
    val name: String? = null,
    @JsonProperty("alt_names") val altNames: List<String>? = null
)

data class DlIptvLogo(
    val channel: String? = null,
    val url: String? = null
)

class DaddyLiveProvider : MainAPI() {
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
        private const val TOTAL_TIMEOUT_MS = 40_000L

        private val DOMAINS = listOf(
            "https://thedaddy.li",
            "https://daddylive.pk",
            "https://dlhd.pk",
            "https://dlhd.st",
            "https://dlstreams.st",
            "https://dlive.sx",
            "https://daddylive.dad"
        )

        private val channelIdRegex = Regex("""[?&]id=(\d+)""")

        private val DROP_TOKENS = setOf(
            "hd", "fhd", "sd", "uhd", "4k", "tv", "live", "canli", "channel", "kanal",
            "uk", "usa", "us", "tr", "turkey", "de", "fr", "es"
        )

        private val DEAD_DOMAINS = listOf("ksohls.ru")
    }

    private fun requestHeaders(base: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$base/"
    )

    private val baseMutex = Mutex()
    @Volatile
    private var resolvedBase: String? = null

    private suspend fun baseUrl(): String {
        resolvedBase?.let { return it }
        return baseMutex.withLock {
            resolvedBase?.let { return@withLock it }
            for (d in (listOf(mainUrl) + DOMAINS).distinct()) {
                val ok = try {
                    app.get(d, headers = requestHeaders(d), timeout = 8).code in 200..399
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    resolvedBase = d
                    return@withLock d
                }
            }
            mainUrl
        }
    }

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
    
    // [FIX] OOM hatasını önlemek için textLarge kullan
    val channelsText = app.get("$base/channels.json", timeout = 30).textLarge
    val channels = channelsText.parsedSafe<Array<DlIptvChannel>>() 
        ?: return@runCatching emptyMap<String, String>()
    
    val logosText = app.get("$base/logos.json", timeout = 30).textLarge
    val logos = logosText.parsedSafe<Array<DlIptvLogo>>()

    val logoById = HashMap<String, String>()
    logos?.forEach {
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
        val response = app.get(
            "$base/24-7-channels.php",
            headers = requestHeaders(base)
        )

        val document = response.document

        if (document.select("a.card").isEmpty()) {
            val title = document.title()
            if (title.contains("Just a moment", true) ||
                title.contains("Attention Required", true) ||
                response.code == 403 || response.code == 503
            ) {
                throw ErrorLoadingException(
                    "Cloudflare engeli tespit edildi (HTTP ${response.code}). " +
                        "VPN veya 'Clone site' deneyin."
                )
            }
        }

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
                ?: card.attr("data-title").trim().takeIf { it.isNotBlank() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.text().substringBefore("ID:").trim().takeIf { it.isNotBlank() }
                ?: continue

            val channelUrl = card.absUrl("href").ifBlank {
                "$base/${href.trimStart('/')}"
            }

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

        val pageResponse = runCatching {
            app.get(url, headers = requestHeaders(base), interceptor = CloudflareKiller())
        }.getOrNull()

        val document = pageResponse?.document

        val title = known?.name
            ?: document?.selectFirst("h2")?.text()
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
            listOf("stream", "cast", "watch", "plus", "casting", "player", "hub")
                .forEach { f -> playerUrls.add("$base/$f/stream-$channelId.php") }
        }

        val data = playerUrls
            .filterNot { u -> DEAD_DOMAINS.any { u.contains(it) } }
            .joinToString("\n")

        return newLiveStreamLoadResponse(title, url, data) {
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
        val playerUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }

        val resolver = EmbedResolver()
        var emittedLinks = 0

        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            for (playerUrl in playerUrls) {
                try {
                    resolver.getUrl(playerUrl, "$base/", subtitleCallback) { link ->
                        callback(link)
                        emittedLinks++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sıradaki oynatıcıya geç
                }
                if (emittedLinks > 0) break
            }
        }

        return emittedLinks > 0
    }
}
