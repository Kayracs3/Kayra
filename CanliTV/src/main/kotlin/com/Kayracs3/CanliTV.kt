package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CanliTV : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"
    override var name = "Canlı TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.None

    private val m3uUrl = "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"

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

    data class Channel(
        val name: String,
        val url: String,
        val logo: String?,
        val category: String
    )

    private val extraChannels = listOf(
        Channel(
            name = "Bein TV Test",
            url = "https://css.d6e3eae5e065d5.help//beintv/tracks-v1a1/mono.m3u8",
            logo = null,
            category = "Spor"
        )
    )

    private var cachedChannels: List<Channel>? = null

    private fun mapGroupToCategory(groupTitle: String): String {
        val g = groupTitle.trim().lowercase()
        return when {
            g.contains("spor") || g.contains("sport") -> "Spor"
            g.contains("sinema") || g.contains("film") || g.contains("movie") || g.contains("vizyon") -> "Sinema"
            g.contains("dizi") || g.contains("series") || g.contains("7/24") -> "Dizi"
            g.contains("haber") || g.contains("news") -> "Haber"
            g.contains("belgesel") || g.contains("documentary") || g.contains("docu") -> "Belgesel"
            g.contains("çocuk") || g.contains("cocuk") || g.contains("kids") || g.contains("cartoon") -> "Çocuk"
            g.contains("müzik") || g.contains("muzik") || g.contains("music") -> "Müzik"
            else -> "Diğer"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = cachedChannels ?: fetchAllChannels().also { cachedChannels = it }

        // HomePageList listesi oluşturuluyor
        val homeLists = categories.map { (title, key) ->
            val filtered = if (key == "all") channels else channels.filter { it.category == key }
            HomePageList(
                name = title,
                list = filtered.map { ch ->
                    newLiveSearchResponse(ch.name, ch.url, TvType.Live) {
                        this.posterUrl = ch.logo
                    }
                }
            )
        }

        // HomePageList listesi ile newHomePageResponse çağrılıyor
        return newHomePageResponse(homeLists)
    }

    private suspend fun fetchAllChannels(): List<Channel> {
        val m3uChannels = fetchChannelsFromM3u()
        val merged = mutableListOf<Channel>()
        merged.addAll(extraChannels)
        val existingUrls = extraChannels.map { it.url }.toMutableSet()
        for (ch in m3uChannels) {
            if (existingUrls.add(ch.url)) merged.add(ch)
        }
        return merged
    }

    private suspend fun fetchChannelsFromM3u(): List<Channel> {
        val content = runCatching { app.get(m3uUrl).text }.getOrNull() ?: return emptyList()
        val list = mutableListOf<Channel>()
        var name: String? = null
        var logo: String? = null
        var category = "Diğer"

        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                name = line.substringAfterLast(",", "").trim().ifEmpty { null }
                logo = Regex("""tvg-logo="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val rawGroup = Regex("""group-title="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1) ?: ""
                category = mapGroupToCategory(rawGroup)
            } else if (!line.startsWith("#") && name != null) {
                list.add(Channel(name!!, line, logo, category))
                name = null
                logo = null
                category = "Diğer"
            }
        }
        return list
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = cachedChannels ?: fetchAllChannels().also { cachedChannels = it }
        return channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { ch ->
                newLiveSearchResponse(ch.name, ch.url, TvType.Live) {
                    this.posterUrl = ch.logo
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val channels = cachedChannels ?: fetchAllChannels().also { cachedChannels = it }
        val ch = channels.firstOrNull { it.url == url }

        // LiveStreamLoadResponse doğrudan kullanılıyor
        return LiveStreamLoadResponse(
            name = ch?.name ?: "Canlı Yayın",
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = ch?.logo,
            type = TvType.Live
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
                // Doğru kalite tanımı
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
