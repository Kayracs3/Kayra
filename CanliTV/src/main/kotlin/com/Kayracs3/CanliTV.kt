package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Locale

class CanliTV : MainAPI() {
    override var mainUrl = "https://github.com/Kayracs3/Kayra"
    override var name = "Canlı TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    /** Kanal listenizin (M3U) ham adresi. BURAYI KENDİ ADRESİNİZLE DEĞİŞTİRİN. */
    private val playlistUrl = "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"

    private data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val category: String,
    )

    @Volatile
    private var cache: List<Channel>? = null

    // ---------------------------------------------------------------- liste

    private suspend fun channels(): List<Channel> {
        cache?.let { if (it.isNotEmpty()) return it }
        val text = runCatching { app.get(playlistUrl).text }.getOrNull() ?: ""
        val list = parseM3u(text)
        if (list.isNotEmpty()) cache = list
        return list
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""
        var logo = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title")
                    name = line.substringAfterLast(',', "").trim()
                        .ifBlank { attr(line, "tvg-name") }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (line.startsWith("http", ignoreCase = true) && name.isNotBlank()) {
                        out.add(Channel(name, line, logo, group, mapGroupToCategory(group)))
                    }
                    name = ""; logo = ""; group = ""
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String =
        Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim() ?: ""

    private fun mapGroupToCategory(group: String): String {
        val g = group.lowercase(Locale("tr"))
        return when {
            listOf("spor", "sport", "futbol", "maç").any { g.contains(it) } -> "Spor"
            listOf("sinema", "film", "movie").any { g.contains(it) } -> "Sinema"
            listOf("dizi", "series").any { g.contains(it) } -> "Dizi"
            listOf("haber", "news").any { g.contains(it) } -> "Haber"
            listOf("belgesel", "documentary").any { g.contains(it) } -> "Belgesel"
            listOf("çocuk", "cocuk", "kids", "çizgi").any { g.contains(it) } -> "Çocuk"
            listOf("müzik", "muzik", "music").any { g.contains(it) } -> "Müzik"
            else -> "Diğer"
        }
    }

    // ------------------------------------------------------------ ana sayfa

    private fun toResult(c: Channel): SearchResponse =
        newLiveSearchResponse(c.name, c.url, TvType.Live) {
            posterUrl = c.logo.takeIf { it.startsWith("http") }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val all = channels()
        if (all.isEmpty()) throw ErrorLoadingException("Kanal listesi yüklenemedi")

        val order = listOf("Spor", "Sinema", "Dizi", "Haber", "Belgesel", "Çocuk", "Müzik", "Diğer")
        val grouped = all.groupBy { it.category }
        val lists = order.mapNotNull { cat ->
            grouped[cat]?.takeIf { it.isNotEmpty() }
                ?.let { HomePageList(cat, it.map(::toResult), false) }
        }
        return newHomePageResponse(lists, false)
    }

    // ---------------------------------------------------------------- arama

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(Locale("tr"))
        if (q.isBlank()) return emptyList()
        return channels()
            .filter { it.name.lowercase(Locale("tr")).contains(q) }
            .map(::toResult)
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val c = channels().firstOrNull { it.url == url }
        return newLiveStreamLoadResponse(c?.name ?: name, url, url) {
            posterUrl = c?.logo?.takeIf { it.startsWith("http") }
            plot = c?.group?.ifBlank { null }
            type = TvType.Live
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val c = channels().firstOrNull { it.url == data }
        callback(
            newExtractorLink(
                name,
                c?.name ?: name,
                data,
                ExtractorLinkType.M3U8,
            ) {
                this.referer = ""
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
