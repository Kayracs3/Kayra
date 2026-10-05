package com.Kayracs3

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

private const val TAG = "NetShort"

@CloudstreamPlugin
class NetShortPlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)
        registerMainAPI(NetShort())
    }
}

class NetShort : MainAPI() {
    override var mainUrl = "https://netshort.com"
    override var name = "NetShort"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val locale = mainUrl + "/tr"
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        locale to "Popüler",
        locale + "/all-episodes" to "Tüm Diziler",
        locale + "/all-episodes/page/" to "Yeni Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/page/") -> request.data + page
            page <= 1 -> request.data
            else -> request.data.trimEnd('/') + "/page/" + page
        }

        val doc = getDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseSeries(doc)
        return newHomePageResponse(request.name, items, page < 50 && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            locale + "/search?keyword=" + encoded,
            locale + "/search?q=" + encoded,
            locale + "/search?query=" + encoded,
        )

        for (url in urls) {
            val doc = getDocument(url) ?: continue
            val found = parseSeries(doc)
            if (found.isNotEmpty()) return found
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = normalize(url)
        val doc = getDocument(pageUrl) ?: return null
        val title = sequenceOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property='og:title']")?.attr("content"),
            doc.selectFirst("title")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val poster = sequenceOf(
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
            doc.select("img[src*='awscover.netshort.com']").firstOrNull()?.attr("src"),
            doc.selectFirst("main img[src]")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::normalize)

        val plot = sequenceOf(
            doc.selectFirst("meta[property='og:description']")?.attr("content"),
            doc.selectFirst("meta[name='description']")?.attr("content"),
            doc.selectFirst("main p")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        if (pageUrl.contains("/episode/", true)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.plot = plot
            }
        }

        val fullUrl = findFullEpisodesUrl(doc, pageUrl)
        val episodes = parseEpisodes(fullUrl, pageUrl, poster)

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = normalize(data)
        val response = runCatching {
            app.get(pageUrl, headers = headers + ("Referer" to locale + "/"))
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        var found = false
        val seen = hashSetOf<String>()

        suspend fun emit(raw: String, label: String = "NetShort") {
            val url = normalize(raw)
            if (!url.startsWith("http", true) || !seen.add(url)) return

            val lower = url.lowercase()
            val hls = lower.contains(".m3u8") || lower.contains("m3u8")
            val quality = when {
                lower.contains("1080") -> Qualities.P1080.value
                lower.contains("720") -> Qualities.P720.value
                lower.contains("480") -> Qualities.P480.value
                lower.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = url,
                    type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = pageUrl
                    this.quality = quality
                }
            )
            found = true
        }

        response.document.select(
            "video[src], video source[src], source[src], video[data-src], source[data-src]"
        ).forEach {
            val value = it.attr("data-src").ifBlank { it.attr("src") }
            if (value.isNotBlank()) emit(value)
        }

        val patterns = listOf(
            Regex("""(?i)["'](?:videoUrl|video_url|playUrl|play_url|streamUrl|stream_url|playbackUrl|playback_url|m3u8|mp4|hls)["']\s*:\s*["']([^"']+)["']"""),
            Regex("""(?i)(?:src|url)\s*[:=]\s*["']([^"']+(?:m3u8|\.mp4)[^"']*)["']"""),
            Regex("""(?i)(?:https?:)?//[^"'<>\s]+?(?:\.m3u8(?:\?[^"'<>\s]*)?|\.mp4(?:\?[^"'<>\s]*)?)"""),
        )

        for (pattern in patterns) {
            pattern.findAll(response.text).forEach { match ->
                val value = match.groupValues.getOrNull(1) ?: match.value
                emit(value)
            }
        }

        response.document.select("iframe[src], iframe[data-src], [data-video-url], [data-play-url]")
            .mapNotNull {
                sequenceOf(
                    it.attr("data-video-url"),
                    it.attr("data-play-url"),
                    it.attr("data-src"),
                    it.attr("src"),
                ).firstOrNull { value -> value.isNotBlank() }
            }
            .distinct()
            .forEach { iframe ->
                runCatching {
                    loadExtractor(
                        normalize(iframe),
                        pageUrl,
                        subtitleCallback,
                        { link ->
                            found = true
                            callback(link)
                        },
                    )
                }
            }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+?(?:\.vtt|\.srt)(?:\?[^"'<>\s]*)?""")
            .findAll(response.text)
            .forEach { match ->
                val subUrl = normalize(match.value)
                if (!subUrl.startsWith("http", true)) return@forEach
                val lang = if (match.value.contains("tr", true)) "Türkçe" else "Altyazı"
                subtitleCallback(
                    newSubtitleFile(lang, subUrl) {
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to pageUrl,
                        )
                    }
                )
            }

        Log.d(TAG, "loadLinks found=" + found + " url=" + pageUrl)
        return found
    }

    private suspend fun getDocument(url: String): Document? {
        return runCatching {
            app.get(normalize(url), headers = headers + ("Referer" to locale + "/")).document
        }.getOrNull()
    }

    private fun parseSeries(doc: Document): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()

        doc.select(
            "a[href*='/tr/full-episodes/'], a[href*='/tr/drama/'], a[href*='/tr/series/']"
        ).forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank() || href.contains("/episode/", true) || href.contains("/page/", true)) return@forEach

            val url = normalize(href)
            val title = sequenceOf(
                anchor.selectFirst("img")?.attr("alt"),
                anchor.selectFirst("h2,h3,h4")?.text(),
                anchor.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return@forEach

            val poster = sequenceOf(
                anchor.selectFirst("img")?.attr("data-src"),
                anchor.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let(::normalize)

            result.putIfAbsent(url, newMovieSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            })
        }

        return result.values.toList()
    }

    private suspend fun parseEpisodes(
        fullUrl: String,
        seriesUrl: String,
        poster: String?,
    ): List<Episode> {
        if (fullUrl.isBlank()) return emptyList()

        val result = LinkedHashMap<String, Episode>()
        val id = Regex("""(\d{18,20})""").find(seriesUrl)?.value

        for (page in 1..20) {
            val url = if (page == 1) fullUrl else fullUrl.trimEnd('/') + "/page/" + page
            val doc = getDocument(url) ?: break

            doc.select("a[href*='/episode/']").forEach { anchor ->
                val episodeUrl = normalize(anchor.attr("href"))
                if (episodeUrl.isBlank()) return@forEach
                if (!id.isNullOrBlank() && !episodeUrl.contains(id)) return@forEach

                val number = Regex("""(?i)(?:-ep-|episode\s*|bölüm\s*)(\d+)""")
                    .find(episodeUrl + " " + anchor.text())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@forEach

                result.putIfAbsent(episodeUrl, newEpisode(episodeUrl) {
                    name = "Bölüm " + number
                    season = 1
                    episode = number
                    posterUrl = poster
                })
            }

            if (doc.select("a[href*='/page/" + (page + 1) + "']").isEmpty()) break
        }

        return result.values.sortedBy { it.episode ?: 0 }
    }

    private fun findFullEpisodesUrl(doc: Document, pageUrl: String): String {
        val found = doc.select("a[href*='/full-episodes/']")
            .map { normalize(it.attr("href")) }
            .firstOrNull { it.contains("/full-episodes/") }

        return found ?: if (pageUrl.contains("/full-episodes/")) {
            pageUrl.substringBefore("/page/")
        } else {
            pageUrl
        }
    }

    private fun normalize(raw: String?): String {
        var value = raw.orEmpty()
            .trim()
            .replace("\\/","/")
            .replace("\\u002F","/")
            .replace("\\u0026","&")
            .replace("&amp;","&")
            .trim('"', '\'', ' ', '\n', '\r', '\t')

        if (value.isBlank()) return ""

        return when {
            value.startsWith("//") -> "https:" + value
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            else -> runCatching { URI(mainUrl).resolve(value).toString() }
                .getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("./") }
        }
    }
}
