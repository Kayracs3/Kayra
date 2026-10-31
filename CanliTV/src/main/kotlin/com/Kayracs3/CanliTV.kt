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
        val name: String,
        val url: String,
        val category: String,
        val logo: String? = null
    )

    /*
     * Yalnızca TRT'nin kendi yayın alan adlarında yayımlanan,
     * resmî TRT canlı kanal akışları listelenir.
     * Üçüncü taraf IPTV listeleri veya doğrulanmamış yayın adresleri kullanılmaz.
     */
    private val channels = listOf(
        Channel(
            "TRT 1",
            "https://tv-trt1.medya.trt.com.tr/master_720.m3u8",
            "Genel",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"
        ),
        Channel(
            "TRT 2",
            "https://tv-trt2.medya.trt.com.tr/master_720.m3u8",
            "Kültür",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-2-tr.png"
        ),
        Channel(
            "TRT 3",
            "https://tv-trt3.live.trt.com.tr/master.m3u8",
            "Spor",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-3-tr.png"
        ),
        Channel(
            "TRT Haber",
            "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8",
            "Haber",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-haber-tr.png"
        ),
        Channel(
            "TRT Belgesel",
            "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8",
            "Belgesel",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-belgesel-tr.png"
        ),
        Channel(
            "TRT Spor",
            "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
            "Spor",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png"
        ),
        Channel(
            "TRT Spor Yıldız",
            "https://tv-trtspor2.medya.trt.com.tr/master.m3u8",
            "Spor",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png"
        ),
        Channel(
            "TRT Çocuk",
            "https://tv-trtcocuk.live.trt.com.tr/master_720.m3u8",
            "Çocuk",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-cocuk-tr.png"
        ),
        Channel(
            "TRT Avaz",
            "https://tv-trtavaz.medya.trt.com.tr/master.m3u8",
            "Uluslararası",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-avaz-tr.png"
        ),
        Channel(
            "TRT Türk",
            "https://tv-trtturk.medya.trt.com.tr/master_720.m3u8",
            "Uluslararası",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-turk-tr.png"
        ),
        Channel(
            "TRT Kurdî",
            "https://tv-trtkurdi.medya.trt.com.tr/master_720.m3u8",
            "Uluslararası",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-kurdi-tr.png"
        ),
        Channel(
            "TRT Arabi",
            "https://tv-trtarabi.medya.trt.com.tr/master_720.m3u8",
            "Uluslararası",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-arabi-tr.png"
        ),
        Channel(
            "TRT World",
            "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8",
            "Uluslararası",
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
        "Uluslararası" to "Uluslararası"
    )

    private fun Channel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name,
            url,
            TvType.Live
        ) {
            posterUrl = logo
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val selected = request.data
        val filtered = if (selected == "all") {
            channels
        } else {
            channels.filter { it.category == selected }
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
                it.name.contains(text, ignoreCase = true) ||
                    it.category.contains(text, ignoreCase = true)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = channels.firstOrNull { it.url == url } ?: return null

        return newLiveStreamLoadResponse(
            channel.name,
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
        val channel = channels.firstOrNull { it.url == data } ?: return false
        val host = runCatching {
            java.net.URI(channel.url).host.orEmpty().lowercase()
        }.getOrDefault("")

        // Yalnızca bu dosyada tanımlı TRT yayınlarının oynatılmasına izin ver.
        if (!host.endsWith(".trt.com.tr") ||
            !channel.url.endsWith(".m3u8", ignoreCase = true)
        ) {
            return false
        }

        callback(
            newExtractorLink(
                source = name,
                name = channel.name,
                url = channel.url,
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
