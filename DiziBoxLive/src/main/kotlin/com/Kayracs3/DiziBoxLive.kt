package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziBoxLive : MainAPI() {

    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBOX Live"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/efsane-diziler/" to "Evsane Diziler",
        "$mainUrl/arsiv/" to "Arşiv",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = requestDocument(url)
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = document
            .select("a[href*='/diziler/']")
            .asSequence()
            .filterNot { isEpisodeAnchor(it) }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
        )

        for (url in urls) {
            val document = requestDocument(url) ?: continue

            val results = document
                .select("a[href*='/diziler/']")
                .asSequence()
                .filterNot { isEpisodeAnchor(it) }
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .toList()

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = fixUrl(url)
        val document = requestDocument(normalized) ?: return null
        val title = pageTitle(document) ?: return null
        val episodes = parseEpisodes(document)

        val poster = posterOf(document)
        val plot = pagePlot(document)
        val releaseYear = pageYear(document)
        val rating = pageRating(document)
        val genres = pageGenres(document)
        val actorData = pageActors(document)

        if (episodes.isEmpty()) {
            return newTvSeriesLoadResponse(
                title,
                normalized,
                TvType.TvSeries,
                listOf(
                    newEpisode(normalized) {
                        name = title
                        season = episodeSeason(normalized)
                        episode = episodeNumber(normalized)
                        posterUrl = poster
                    }
                ),
            ) {
                posterUrl = poster
                this.plot = plot
                year = releaseYear
                rating?.let { score = Score.from10(it) }
                if (genres.isNotEmpty()) tags = genres
                if (actorData.isNotEmpty()) actors = actorData
            }
        }

        return newTvSeriesLoadResponse(
            title,
            normalized,
            TvType.TvSeries,
            episodes,
        ) {
            posterUrl = poster
            this.plot = plot
            year = releaseYear
            rating?.let { score = Score.from10(it) }
            if (genres.isNotEmpty()) tags = genres
            if (actorData.isNotEmpty()) actors = actorData
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val response = runCatching {
            app.get(
                episodeUrl,
                headers = headers,
                referer = "$mainUrl/",
            )
        }.getOrNull() ?: return false

        val document = response.document
        collectSubtitles(document, subtitleCallback)

        var found = false
        var emitted = 0

        val report: (ExtractorLink) -> Unit = { link ->
            emitted++
            found = true
            callback(link)
        }

        val candidates = LinkedHashSet<String>()

        document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src], " +
                "a[href], [data-src], [data-url], [data-href], [data-embed], [data-player]"
        ).forEach { element ->
            extractUrl(element)?.let(candidates::add)
        }

        candidates.removeIf {
            it.contains("youtube.com", ignoreCase = true) ||
                it.contains("youtu.be", ignoreCase = true) ||
                it.contains("facebook.com", ignoreCase = true)
        }

        for (candidate in candidates) {
            val clean = candidate.decodeEmbedded()
            if (clean.isBlank()) continue

            if (isMediaUrl(clean)) {
                emitMedia(clean, episodeUrl, report)
                continue
            }

            val before = emitted

            runCatching {
                loadExtractor(
                    clean,
                    episodeUrl,
                    subtitleCallback,
                    report,
                )
            }

            if (emitted == before && isPlayerUrl(clean)) {
                if (interceptProvider(clean, episodeUrl, subtitleCallback, report)) {
                    found = true
                }
            }
        }

        if (!found) {
            if (interceptProvider(episodeUrl, "$mainUrl/", subtitleCallback, report)) {
                found = true
            }
        }

        return found
    }

    private suspend fun interceptProvider(
        providerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resolver = runCatching {
            WebViewResolver(
                interceptUrl = Regex(
                    """(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)"""
                ),
                additionalUrls = listOf(
                    Regex(
                        """(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)"""
                    )
                ),
                useOkhttp = false,
                timeout = 25_000L,
            )
        }.getOrNull() ?: return false

        val response = runCatching {
            app.get(
                providerUrl,
                headers = headers + mapOf("Referer" to referer),
                referer = referer,
                interceptor = resolver,
            )
        }.getOrNull() ?: return false

        collectSubtitles(response.document, subtitleCallback)

        val intercepted = response.url.orEmpty()
        if (!isMediaUrl(intercepted)) return false

        emitMedia(intercepted, providerUrl, callback)
        return true
    }

    private suspend fun emitMedia(
        url: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lower = url.lowercase()

        val type = when {
            lower.contains(".m3u8") || lower.contains("/hls") ->
                ExtractorLinkType.M3U8
            lower.contains(".mpd") ->
                ExtractorLinkType.DASH
            else ->
                ExtractorLinkType.VIDEO
        }

        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to sourcePage,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        originOf(sourcePage)?.let { mediaHeaders["Origin"] = it }

        callback(
            newExtractorLink(
                source = name,
                name = hostOf(url),
                url = url,
                type = type,
            ) {
                referer = sourcePage
                headers = mediaHeaders
                quality = qualityFromUrl(url)
            }
        )
    }

    private suspend fun requestDocument(url: String): Document? {
        return runCatching {
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/",
            ).document
        }.getOrNull()
    }

    private fun pageTitle(document: Document): String? {
        return document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[name='description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .select("article p, .description, .summary")
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun metadataLine(document: Document): String? {
        val yearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

        return document
            .getAllElements()
            .asSequence()
            .map { it.ownText().trim().replace(Regex("\\s+"), " ") }
            .filter { value ->
                value.contains("|") && yearRegex.containsMatchIn(value)
            }
            .minByOrNull { it.length }
    }

    private fun pageYear(document: Document): Int? {
        val line = metadataLine(document) ?: return null
        val parts = line.split("|").map { it.trim() }

        parts.getOrNull(1)
            ?.let {
                Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }
            ?.let { return it }

        return Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
            .find(line)
            ?.value
            ?.toIntOrNull()
    }

    private fun pageGenres(document: Document): List<String> {
        val line = metadataLine(document) ?: return emptyList()
        val parts = line.split("|").map { it.trim() }

        return parts
            .getOrNull(2)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(12)
            ?: emptyList()
    }

    private fun pageActors(document: Document): List<ActorData> {
        val line = metadataLine(document) ?: return emptyList()
        val parts = line.split("|").map { it.trim() }

        if (parts.size < 4) return emptyList()

        return parts
            .drop(3)
            .joinToString(" | ")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .map { ActorData(Actor(it)) }
    }

    private fun pageRating(document: Document): Double? {
        val pattern = Regex(
            "(?i)\\bimdb\\s*[:/]?\\s*([0-9]+(?:[.,][0-9]+)?)\\b"
        )

        return document
            .getAllElements()
            .asSequence()
            .map { it.ownText().trim() }
            .mapNotNull { pattern.find(it) }
            .mapNotNull {
                it.groupValues
                    .getOrNull(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
            }
            .firstOrNull()
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val pattern = Regex(
            "(?i)(\\d+)\\.?\\s*Sezon\\s*(\\d+)\\.?\\s*Bölüm"
        )

        return document
            .select("a[href]")
            .mapNotNull { element ->
                val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val text = element.text().trim()
                val match = pattern.find(text) ?: return@mapNotNull null

                val season = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    name = text
                    this.season = season
                    this.episode = episode
                    posterUrl = posterOf(document)
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun isEpisodeAnchor(element: Element): Boolean {
        return Regex(
            "(?i)\\d+\\.?\\s*Sezon\\s*\\d+\\.?\\s*Bölüm"
        ).containsMatchIn(element.text())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val path = href.lowercase()

        if (!path.contains("/diziler/") || isEpisodeAnchor(this)) return null

        val title = text()
            .trim()
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        if (title.isBlank() || title.length > 120) return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries,
        ) {
            posterUrl = posterFromElement(this@toSearchResponse)
        }
    }

    private fun posterOf(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document
                .select("img")
                .asSequence()
                .mapNotNull { posterOfElement(it) }
                .firstOrNull()
    }

    private fun posterFromElement(element: Element): String? {
        element.selectFirst("img")?.let {
            posterOfElement(it)?.let { value -> return value }
        }

        var current: Element? = element.parent()

        repeat(4) {
            val parent = current ?: return@repeat

            parent.select("img").firstOrNull()?.let {
                posterOfElement(it)?.let { value -> return value }
            }

            current = parent.parent()
        }

        return null
    }

    private fun posterOfElement(element: Element): String? {
        val raw = listOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("src"),
            element.attr("data-srcset"),
            element.attr("srcset"),
        ).firstOrNull { it.isNotBlank() } ?: return null

        return raw
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
    }

    private fun extractUrl(element: Element): String? {
        val attrs = listOf(
            "href",
            "src",
            "data-src",
            "data-url",
            "data-href",
            "data-embed",
            "data-player",
        )

        for (attrName in attrs) {
            val raw = element.attr(attrName).trim()
            if (raw.isBlank()) continue

            val value = raw.decodeEmbedded()

            Regex(
                "https?://[^\\s\\\"'<>]+",
                RegexOption.IGNORE_CASE
            )
                .find(value)
                ?.value
                ?.trimEnd(')', ']', '}', ';', ',')
                ?.let { return it }

            if (value.startsWith("//")) {
                return "https:$value"
            }

            if (value.startsWith("/")) {
                return fixUrl(value)
            }
        }

        return null
    }

    private fun collectSubtitles(
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        document.select("track[src], track[data-src]").forEach { track ->
            val src = track
                .attr("src")
                .ifBlank { track.attr("data-src") }
                .takeIf { it.isNotBlank() }
                ?: return@forEach

            subtitleCallback(
                newSubtitleFile(
                    track.attr("label").ifBlank { "Türkçe" },
                    fixUrl(src),
                )
            )
        }
    }

    private fun isPlayerUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vidmoly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki") ||
            value.contains("streamtape") ||
            value.contains("doodstream") ||
            value.contains("filemoon") ||
            value.contains("/embed") ||
            value.contains("player")
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains(".m3u8") ||
            value.contains(".mpd") ||
            Regex("(?i)\\.(?:mp4|webm)(?:$|[?#])").containsMatchIn(value) ||
            value.contains("/hls/")
    }

    private fun qualityFromUrl(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1440") -> Qualities.P1440.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun hostOf(url: String): String {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "DiziBOX"
    }

    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                return@runCatching null
            }
            uri.scheme + "://" + uri.host
        }.getOrNull()
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "$base/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val next = page + 1

        return document.select("a[href]").any { element ->
            val href = fixUrlNull(element.attr("href")).orEmpty()
            val label = element.text().trim().lowercase()

            label.contains("sonraki") ||
                label.contains("next") ||
                href.contains("/page/$next/")
        }
    }

    private fun episodeSeason(url: String): Int? {
        return Regex("(?i)(\\d+)[-_]?sezon")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun episodeNumber(url: String): Int? {
        return listOf(
            Regex("(?i)(?:bolum|bölüm)[-_]?(\\d+)"),
            Regex("(?i)(\\d+)[-_]?bolum"),
        )
            .asSequence()
            .mapNotNull { pattern ->
                pattern.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .firstOrNull()
    }

    private fun String.decodeEmbedded(): String {
        return this
            .replace("\\\\/", "/")
            .replace("\\\\u002F", "/", ignoreCase = true)
            .replace("\\\\u003A", ":", ignoreCase = true)
            .replace("\\\\u0026", "&", ignoreCase = true)
            .replace("\\\\\"", "\"")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
    }
}
