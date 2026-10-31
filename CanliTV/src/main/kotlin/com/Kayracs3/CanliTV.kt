package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class CanliTV : MainAPI() {
    // M3U listesinin kaynağını temsil eden nötr bir ana URL
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

    private var cachedChannels: List<Channel>? = null

    private suspend fun getChannels(): List<Channel> {
        return cachedChannels ?: fetchChannels().also { cachedChannels = it }
    }

    private suspend fun fetchChannels(): List<Channel> {
        val content = app.get(m3uUrl).text
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

    override val mainPage = mainPageOf(
        "all" to "Tüm Kanallar"
    )

    private fun Channel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(title, stream, TvType.Live) {
            posterUrl = logo
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        return newHomePageResponse(
            request.name,
            channels.map { it.toSearchResponse() },
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
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
