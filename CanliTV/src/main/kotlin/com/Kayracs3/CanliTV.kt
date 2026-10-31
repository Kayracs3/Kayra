package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class CanliTV : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "Canlı TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private data class Channel(
        val title: String,
        val category: String,
        val stream: String,
        val logo: String
    )

    /*
     * Yalnızca TRT'nin kendi medya.trt.com.tr alan adındaki resmî
     * yayın uçları listelenir. Üçüncü taraf IPTV/proxy adresleri yoktur.
     */
    private val channels = listOf(
        Channel(
            "TRT 1",
            "Genel",
            "https://tv-trt1.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt1.png"
        ),
        Channel(
            "TRT 2",
            "Kültür",
            "https://tv-trt2.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt2.png"
        ),
        Channel(
            "TRT Belgesel",
            "Belgesel",
            "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtbelgesel.png"
        ),
        Channel(
            "TRT Haber",
            "Haber",
            "https://tv-trt-haber.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trthaber.png"
        ),
        Channel(
            "TRT Spor",
            "Spor",
            "https://tv-trt-spor.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtspor.png"
        ),
        Channel(
            "TRT Spor Yıldız",
            "Spor",
            "https://tv-trt-spor-yildiz.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtsporyildiz.png"
        ),
        Channel(
            "TRT Çocuk",
            "Çocuk",
            "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtcocuk.png"
        ),
        Channel(
            "TRT Diyanet Çocuk",
            "Çocuk",
            "https://tv-trt-diyanet-cocuk.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtdiyanet.png"
        ),
        Channel(
            "TRT Müzik",
            "Müzik",
            "https://tv-trtmuzik.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtmuzik.png"
        ),
        Channel(
            "TRT Türk",
            "Uluslararası",
            "https://tv-trt-turk.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtturk.png"
        ),
        Channel(
            "TRT Avaz",
            "Uluslararası",
            "https://tv-trtavaz.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtavaz.png"
        ),
        Channel(
            "TRT Kurdî",
            "Uluslararası",
            "https://tv-trt-kurdi.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtkurdi.png"
        ),
        Channel(
            "TRT World",
            "Uluslararası",
            "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtworld.png"
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
            stream,
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
        val filtered = if (selected.equals("all", ignoreCase = true)) {
            channels
        } else {
            channels.filter { it.category.equals(selected, ignoreCase = true) }
        }

        return newHomePageResponse(
            request.name,
            filtered.map { it.toSearchResponse() },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return channels.map { it.toSearchResponse() }

        return channels
            .filter {
                it.title.contains(normalized, ignoreCase = true) ||
                    it.category.contains(normalized, ignoreCase = true)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = channels.firstOrNull { it.stream == url } ?: return null

        return newLiveStreamLoadResponse(
            channel.title,
            url,
            channel.stream
        ) {
            posterUrl = channel.logo
            plot = "Resmî TRT canlı yayını."
            tags = listOf("TRT", channel.category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUri = runCatching { URI(data) }.getOrNull() ?: return false
        val host = streamUri.host?.lowercase().orEmpty()

        // Eklentinin yalnızca tanımlı resmî TRT yayın sunucularını açmasına izin ver.
        if (!host.endsWith(".medya.trt.com.tr") ||
            !streamUri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        ) {
            return false
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
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
