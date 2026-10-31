package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CanliTV : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "Canlı TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private data class Channel(
        val title: String,
        val category: String,
        val streamUrl: String,
        val logo: String
    )

    // TRT'nin kendi alan adlarındaki canlı yayın adresleri.
    // Üçüncü taraf IPTV listeleri ve uygulama imza kontrolleri kullanılmaz.
    private val channels = listOf(
        Channel(
            "TRT 1", "Genel",
            "https://tv-trt1.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"
        ),
        Channel(
            "TRT 2", "Kültür",
            "https://tv-trt2.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-2-tr.png"
        ),
        Channel(
            "TRT Haber", "Haber",
            "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-haber-tr.png"
        ),
        Channel(
            "TRT Belgesel", "Belgesel",
            "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-belgesel-tr.png"
        ),
        Channel(
            "TRT Spor", "Spor",
            "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png"
        ),
        Channel(
            "TRT Spor Yıldız", "Spor",
            "https://tv-trtspor2.medya.trt.com.tr/master.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png"
        ),
        Channel(
            "TRT Çocuk", "Çocuk",
            "https://tv-trtcocuk.live.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-cocuk-tr.png"
        ),
        Channel(
            "TRT Müzik", "Müzik",
            "https://tv-trtmuzik.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-muzik-tr.png"
        ),
        Channel(
            "TRT Avaz", "Uluslararası",
            "https://tv-trtavaz.medya.trt.com.tr/master.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-avaz-tr.png"
        ),
        Channel(
            "TRT Türk", "Uluslararası",
            "https://tv-trtturk.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-turk-tr.png"
        ),
        Channel(
            "TRT Kurdî", "Uluslararası",
            "https://tv-trtkurdi.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-kurdi-tr.png"
        ),
        Channel(
            "TRT Arabi", "Uluslararası",
            "https://tv-trtarabi.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-arabi-tr.png"
        ),
        Channel(
            "TRT World", "Uluslararası",
            "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-world-tr.png"
        )
    )

    override val mainPage = mainPageOf(
        "all" to "Tüm Kanallar",
        "Genel" to "Genel",
        "Haber" to "Haber",
        "Spor" to "Spor",
        "Belgesel" to "Belgesel",
        "Kültür" to "Kültür",
        "Çocuk" to "Çocuk",
        "Müzik" to "Müzik",
        "Uluslararası" to "Uluslararası"
    )

    private fun Channel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            title,
            streamUrl,
            TvType.Live
        ) {
            posterUrl = logo
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val selectedCategory = request.data
        val filtered = if (selectedCategory == "all") {
            channels
        } else {
            channels.filter { it.category == selectedCategory }
        }

        return newHomePageResponse(
            request.name,
            filtered.map { it.toSearchResponse() },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = query.trim()
        if (text.isEmpty()) return channels.map { it.toSearchResponse() }

        return channels
            .filter {
                it.title.contains(text, ignoreCase = true) ||
                    it.category.contains(text, ignoreCase = true)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = channels.firstOrNull { it.streamUrl == url } ?: return null

        return newLiveStreamLoadResponse(
            channel.title,
            url,
            url
        ) {
            posterUrl = channel.logo
            plot = "TRT'nin resmî canlı yayını."
            tags = listOf(channel.category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = channels.firstOrNull { it.streamUrl == data } ?: return false

        val host = runCatching {
            java.net.URI(channel.streamUrl).host.orEmpty().lowercase()
        }.getOrDefault("")

        if (!host.endsWith(".trt.com.tr") ||
            !channel.streamUrl.endsWith(".m3u8", ignoreCase = true)
        ) {
            return false
        }

        callback(
            newExtractorLink(
                source = name,
                name = channel.title,
                url = channel.streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://www.trt.net.tr/"
                headers = mapOf(
                    "Referer" to "https://www.trt.net.tr/",
                    "Origin" to "https://www.trt.net.tr",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
                quality = Qualities.P720.value
            }
        )

        return true
    }
}
