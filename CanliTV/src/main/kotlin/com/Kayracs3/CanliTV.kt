package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class CanliTV : MainAPI() {
    override var mainUrl = "https://github.com/sunarproje/support"
    override var name = "Canlı TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val m3uUrl = "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"

    private data class Channel(
        val title: String,
        val category: String,
        val stream: String,
        val logo: String
    )

    // -----------------------------------------------------------------
    // 🎯 EKSTRA KANALLAR — Buraya istediğin kadar elle kanal ekleyebilirsin
    // -----------------------------------------------------------------
    private val extraChannels: List<Channel> = listOf(
        Channel(
            title = "beIN Sports 1",
            category = "Spor",
            stream = "https://css.d6e3eae5e065d5.help//beintv/tracks-v1a1/mono.m3u8",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-1-tr.png"
        ),
        Channel(
            title = "Tivibu Spor 1",
            category = "Spor",
            stream = "http://65.108.239.207/t1/index.m3u8",
            logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-tr.png"
        ),
        Channel(
            title = "Sinema HD",
            category = "Sinema",
            stream = "http://65.108.239.207/sinema/index.m3u8",
            logo = "https://ui-avatars.com/api/?name=Sinema+HD&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
        ),
        Channel(
            title = "BS Series 1",
            category = "Dizi",
            // SSL sorunu nedeniyle HTTP deniyoruz; olmazsa HTTPS'e geri dönebilirsin
            stream = "http://65.108.239.207/bsseries1/index.m3u8",
            logo = "https://ui-avatars.com/api/?name=BS+Series+1&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
        ),
        // ↓↓↓ Yeni kanal eklemek için aşağıdaki şablonu kopyala ↓↓↓
        // Channel(
        //     title = "Kanal Adı",
        //     category = "Kategori",
        //     stream = "https://ornek.com/kanal/index.m3u8",
        //     logo = "https://ornek.com/logo.png"
        // ),
    )

    // Ana sayfada gösterilecek kategoriler (extraChannels ve M3U'daki group-title ile eşleşir)
    private val categories = listOf(
        "Tüm Kanallar" to "all",
        "Spor" to "Spor",
        "Sinema" to "Sinema",
        "Dizi" to "Dizi",
        "Haber" to "Haber",
        "Belgesel" to "Belgesel",
        "Çocuk" to "Çocuk",
        "Müzik" to "Müzik",
        "Diğer" to "Diğer"
    )

    override val mainPage = mainPageOf(
        *categories.map { (name, data) -> data to name }.toTypedArray()
    )

    private var cachedChannels: List<Channel>? = null

    private suspend fun getChannels(): List<Channel> {
        return cachedChannels ?: loadAllChannels().also { cachedChannels = it }
    }

    private suspend fun loadAllChannels(): List<Channel> {
        val fromM3u = fetchChannelsFromM3u()
        // Ekstra kanallar önce görünsün, ardından M3U'dan gelenler
        return (extraChannels + fromM3u).distinctBy { it.stream }
    }

    private suspend fun fetchChannelsFromM3u(): List<Channel> {
        val content = runCatching { app.get(m3uUrl).text }.getOrNull() ?: return emptyList()
        val lines = content.lines()
        val channels = mutableListOf<Channel>()
        var currentInfo: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF")) {
                currentInfo = line
            } else if (line.isNotBlank() && !line.startsWith("#") && currentInfo != null) {
                val url = line.trim()
                val title = currentInfo.substringAfterLast(',').trim()
                val logo = Regex("""tvg-logo="([^"]+)"""").find(currentInfo)?.groupValues?.get(1) ?: ""
                val category = Regex("""group-title="([^"]+)"""").find(currentInfo)?.groupValues?.get(1) ?: "Diğer"
                channels.add(Channel(title, category, url, logo))
                currentInfo = null
            }
        }
        return channels
    }

    private fun Channel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(title, stream, TvType.Live) {
            posterUrl = logo
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val allChannels = getChannels()
        val items = when (request.data) {
            "all" -> allChannels
            else -> allChannels.filter { it.category.equals(request.data, ignoreCase = true) }
        }
        return newHomePageResponse(
            request.name,
            items.map { it.toSearchResponse() },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = getChannels()
        if (query.isBlank()) return channels.map { it.toSearchResponse() }
        return channels.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.category.contains(query, ignoreCase = true)
        }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channels = getChannels()
        val channel = channels.firstOrNull { it.stream == url } ?: return null
        return newLiveStreamLoadResponse(channel.title, url, channel.stream) {
            posterUrl = channel.logo
            plot = "Canlı TV yayını."
            tags = listOf(channel.category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channels = getChannels()
        if (channels.none { it.stream == data }) return false

        // Yayının kendi origin'ini referer olarak kullan
        val origin = runCatching {
            val uri = URI(data)
            "${uri.scheme}://${uri.host}/"
        }.getOrNull() ?: mainUrl

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                referer = origin
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
