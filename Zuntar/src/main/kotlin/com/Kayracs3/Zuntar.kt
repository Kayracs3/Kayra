package com.Kayracs3

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Zuntar live TV provider.
 * Includes all 36 primary and 176 alternate/event records extracted from the
 * supplied GolgeTV.cs3 catalogue. Original source URLs, names, logos and
 * referer values are preserved in the embedded catalogue.
 */
private data class ZuntarChannel(
    val name: String,
    val url: String,
    val logo: String,
    val headers: Map<String, String> = emptyMap()
)

private object ZuntarCatalogData {
    private const val ENCODED =
        "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"

    val channels: List<ZuntarChannel> by lazy {
        val compressed = Base64.decode(ENCODED, Base64.DEFAULT)
        val catalogue = GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }

        catalogue.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t')
                if (parts.size != 4) {
                    throw IllegalStateException("Zuntar katalog satırı hatalı: ${parts.size} alan")
                }
                val headers = mutableMapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36",
                    "Accept" to "*/*"
                )
                if (parts[3].isNotBlank()) headers["Referer"] = parts[3]
                ZuntarChannel(parts[0], parts[1], parts[2], headers)
            }
            .toList()
    }
}

private object ZuntarCatalog {
    val channels: List<ZuntarChannel> by lazy { ZuntarCatalogData.channels.take(36) }
}

private object ZuntarAltCatalog {
    val channels: List<ZuntarChannel> by lazy { ZuntarCatalogData.channels.drop(36) }
}

class ZuntarProvider : MainAPI() {
    override var mainUrl = "https://zuntar.invalid"
    override var name = "Zuntar"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val mainPage = mainPageOf("zuntar" to "Zuntar")

    private val channels: List<ZuntarChannel> = ZuntarCatalog.channels + ZuntarAltCatalog.channels

    private fun id(channel: ZuntarChannel): String = "zuntar-${channel.name}"

    private fun row(id: String): ZuntarChannel = channels.firstOrNull { id(it) == id }
        ?: throw ErrorLoadingException("Zuntar kanalı bulunamadı.")

    private fun displayName(raw: String): String {
        if (raw == "5\" thumb_square=\"https://i.hizliresim.com/eojp1za.png\" group=\"Canlı\",TV8,5") return "TV8,5"
        if (raw.startsWith("5 (Yedek)\" thumb_square=")) return "TV8,5 (Yedek)"
        var value = raw
            .replace("%7C", " • ")
            .replace("TIVIBU", "Tivibu", ignoreCase = true)
            .replace("S SPORT", "S Sport", ignoreCase = true)
            .replace("SMART SPOR", "Smart Spor", ignoreCase = true)
            .replace("TRT SPOR", "TRT Spor", ignoreCase = true)
            .replace("A SPOR", "A Spor", ignoreCase = true)
            .replace("EURO SPORT", "Eurosport", ignoreCase = true)
            .replace("TABII SPOR", "tabii Spor", ignoreCase = true)
            .replace("Tabi Spor", "tabii Spor", ignoreCase = true)
            .replace("HT-SPOR", "HT Spor", ignoreCase = true)
        value = Regex("\\s+-\\s*([A-Z])$").replace(value) { " • Kaynak ${it.groupValues[1]}" }
        return value
    }

    private fun toSearchResponse(channel: ZuntarChannel): SearchResponse {
        return newLiveSearchResponse(
            name = displayName(channel.name),
            url = id(channel),
            type = TvType.Live,
            fix = false
        ) { posterUrl = channel.logo }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = channels.map(::toSearchResponse)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return channels
            .filter { displayName(it.name).contains(term, ignoreCase = true) }
            .map(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = row(url)
        return newLiveStreamLoadResponse(
            name = displayName(channel.name),
            url = url,
            dataUrl = url
        ) { posterUrl = channel.logo }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = row(data)
        val sourceUrl = if (channel.url.startsWith("golge", ignoreCase = true)) {
            channel.url.substringAfter("://")
        } else channel.url
        val referer = channel.headers["Referer"].orEmpty()
        val headers = channel.headers
        val urls = linkedSetOf<String>()
        var sourceIsPlaylist = false

        if (sourceUrl.contains(".m3u8", ignoreCase = true) || sourceUrl.contains(".mpd", ignoreCase = true)) {
            urls += sourceUrl
        } else {
            val page = app.get(sourceUrl, headers = headers, referer = referer).text
            sourceIsPlaylist = page.trimStart().startsWith("#EXTM3U")
            if (sourceIsPlaylist) urls += sourceUrl

            val encodedConfig = Regex(
                "dmzjsnjsn.*?atob\\(\"([A-Za-z0-9+/=]+)\"",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(page)?.groupValues?.getOrNull(1)
            val decodedConfig = encodedConfig?.let { encoded ->
                runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                }.getOrNull()
            }.orEmpty()
            val template = Regex("\"fdcub\"\\s*:\\s*\"([^\"]+)\"")
                .find(decodedConfig)?.groupValues?.getOrNull(1)
            val sourceId = Regex("[?&]id=([^|&]+)")
                .find(sourceUrl)?.groupValues?.getOrNull(1)
            if (!template.isNullOrBlank()) {
                val candidate = template.replace("%id%", sourceId.orEmpty())
                if (candidate.startsWith("http", ignoreCase = true)) urls += candidate
            }

            val streamRegex = Regex(
                "https?://[^\"\\'\\s<>]+(?:m3u8|mpd)(?:\\?[^\"\\s<>]*)?",
                setOf(RegexOption.IGNORE_CASE)
            )
            streamRegex.findAll(page).forEach { match -> urls += match.value.trimEnd('.', ')', ']') }
            if (decodedConfig.isNotBlank()) {
                streamRegex.findAll(decodedConfig).forEach { match -> urls += match.value.trimEnd('.', ')', ']') }
            }
        }

        val validUrls = urls.filter { candidate ->
            candidate.startsWith("http", ignoreCase = true) &&
                ((sourceIsPlaylist && candidate == sourceUrl) ||
                    candidate.contains(".m3u8", true) || candidate.contains(".mpd", true))
        }.distinct()
        if (validUrls.isEmpty()) {
            throw ErrorLoadingException("Zuntar yayın bağlantısı alınamadı.")
        }

        validUrls.forEachIndexed { index, streamUrl ->
            val type = if (streamUrl.contains(".mpd", true)) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${displayName(channel.name)} #${index + 1}",
                    url = streamUrl
                ) {
                    this.type = type
                    this.quality = getQualityFromName(streamUrl)
                    this.referer = referer
                    this.headers = headers
                }
            )
        }
        return true
    }
}
