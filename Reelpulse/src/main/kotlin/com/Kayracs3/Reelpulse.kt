package com.Kayracs3

import com.lagradost.cloudstream3.newEpisode

import com.lagradost.cloudstream3.newHomePageResponse

import com.lagradost.cloudstream3.utils.loadExtractor

import com.lagradost.cloudstream3.utils.ExtractorLink

import com.lagradost.cloudstream3.newMovieSearchResponse

import com.lagradost.cloudstream3.newTvSeriesLoadResponse

import com.lagradost.cloudstream3.newMovieLoadResponse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Reelpulse : MainAPI() {
    override var mainUrl = "https://reelpulse.org"
    override var name = "ReelPulse"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/" to "Ana Sayfa")

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'')
            ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("mailto:", true)
        ) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    private val redBullPosterUrl =
        "https://res.cloudinary.com/hhhkasdw4/image/upload/f_png,w_480,q_auto/streaming-platform/smarttv/rbtv/logos/logo.png"

    private fun normalizeLabel(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    // Site navigation and promotion buttons must never be treated as channel cards.
    private fun isUiActionTitle(title: String): Boolean {
        val normalized = normalizeLabel(title)
        if (normalized.isBlank()) return true

        val exactOrPhraseMatches = listOf(
            "windows desktop application", "windows desktop app", "desktop application",
            "desktop app", "windows app", "mobile application", "mobile app",
            "mobil uygulamamiz", "mobil uygulama", "uygulamamizi indir",
            "uygulamayi indir", "become a publisher", "yayinci ol",
            "download apk", "apk indir", "apk download", "download app", "app indir",
            "advertisement", "advertising", "reklam ver", "reklam alanlari"
        )
        if (exactOrPhraseMatches.any { normalized == it || normalized.contains(it) }) return true

        val words = normalized.split(Regex("\\s+")).toSet()
        return words.any { it in setOf("browse", "gozat", "ads", "reklam", "publisher") }
    }

    private fun isContentUrl(url: String): Boolean {
        if (!sameSite(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        // ReelPulse channel detail pages use /watch/{slug}. Restricting the catalogue
        // to these routes prevents app-download, publisher, browse and ad buttons
        // from accidentally appearing as channel tiles.
        if (!Regex("(?i)^/watch/[^/]+/?$").matches(path)) return false
        return true
    }

    private fun posterUrl(raw: String?, title: String, base: String = mainUrl): String? {
        val resolved = fixUrl(raw, base)
        val normalizedTitle = normalizeLabel(title)
        val isRedBull = normalizedTitle.contains("red bull") ||
            normalizedTitle.contains("redbull") ||
            raw.orEmpty().contains("/rbtv/logos/logo.svg", ignoreCase = true) ||
            resolved.orEmpty().contains("/rbtv/logos/logo.svg", ignoreCase = true)

        // ReelPulse supplies Red Bull TV as SVG, which CloudStream/Coil can render
        // as an empty poster on some devices. Request the same image as PNG instead.
        if (isRedBull) return redBullPosterUrl
        return resolved
    }

    private fun posterFrom(element: Element, link: Element, title: String): String? {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        val raw = sequenceOf(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-original-src"),
            image?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) }
        return posterUrl(raw, title)
    }

    private fun titleFrom(element: Element, link: Element): String {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        return sequenceOf(
            element.selectFirst("h1, h2, h3, .title, .film-title, .movie-title")?.text(),
            link.attr("title"),
            image?.attr("alt"),
            link.text(),
            element.attr("title")
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length >= 2 }
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .substringBefore(" | ")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName().equals("a", true)) this else {
            select("a[href]").firstOrNull { anchor ->
                val resolved = fixUrl(anchor.attr("href"))
                resolved != null && isContentUrl(resolved)
            }
        } ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!isContentUrl(url)) return null

        val title = titleFrom(this, link)
        if (title.length < 2 || isUiActionTitle(title) || title.lowercase() in setOf(
                "ana sayfa", "home", "filmler", "diziler", "kategoriler", "giriş", "giris",
                "kayıt ol", "kayit ol", "iletişim", "iletisim", "hakkımızda", "hakkimizda"
            )
        ) return null

        val allText = "${title} ${url} ${text()}"
        val type = if (Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val poster = posterFrom(this, link, title)
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()
        return newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun matchesQuery(query: String, title: String): Boolean {
        val normalizedTitle = title.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        val normalizedQuery = query.lowercase().trim()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        if (normalizedQuery.isBlank()) return true
        return normalizedTitle.contains(normalizedQuery) ||
            normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }.all { normalizedTitle.contains(it) }
    }

    private fun parseResults(document: Document, query: String = ""): List<SearchResponse> {
        val cards = document.select(
            "article, .movie-item, .film-item, .video-item, .series-item, .film-card, .movie-card, .post-item, .movie, .film, .series, .card, .thumbnail, .list-item"
        ).mapNotNull { it.toSearchResult() }
        val results = if (cards.isNotEmpty()) cards else {
            document.select("a[href]").mapNotNull { it.toSearchResult() }
        }
        return results.distinctBy { it.url }
            .filter { matchesQuery(query, it.name) }
            .take(100)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data.trimEnd('/') + "/page/$page/"
            else -> "$mainUrl/page/$page/"
        }
        val document = runCatching {
            app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseResults(document)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/arama/$encoded/",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?ara=$encoded"
        ).distinct()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            val results = parseResults(document, q)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeNumbers(value: String): Pair<Int, Int>? {
        val source = value.replace('_', ' ').replace('-', ' ')
        val first = Regex("(?i)(?:sezon|season|s)\\s*(\\d+)\\D{0,40}?(?:bölüm|bolum|episode|ep|e)\\s*(\\d+)")
            .find(source)
        if (first != null) {
            return first.groupValues[1].toIntOrNull()?.let { season ->
                first.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
            }
        }
        val compact = Regex("(?i)\\bs(\\d+)\\s*e(\\d+)\\b").find(value)
        return compact?.let { it.groupValues[1].toIntOrNull()?.let { s -> it.groupValues[2].toIntOrNull()?.let { e -> s to e } } }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url) ?: return null
        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title()
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length > 1 }
            ?.substringBefore(" | ")
            ?.trim()
            ?: return null

        val posterRaw = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[class*=poster]")?.attr("data-src"),
            document.selectFirst("img[class*=poster]")?.attr("src"),
            document.selectFirst("img")?.attr("data-src"),
            document.selectFirst("img")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }
        val poster = posterUrl(posterRaw, title, pageUrl)

        val description = sequenceOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".description, .plot, .synopsis, [class*=description]")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val pageText = document.text()
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(pageText)?.value?.toIntOrNull()
        val ratingText = document.selectFirst(".imdb, .rating, .score, [class*=rating], [class*=imdb]")?.text().orEmpty()
        val rating = Regex("(?<!\\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\\d)")
            .find(ratingText)?.groupValues?.getOrNull(1)?.replace(',', '.')
        val score = rating?.let { runCatching { Score.from10(it) }.getOrNull() }
        val tags = document.select("a[rel=tag], a[href*=genre], a[href*=tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)

        val episodes = document.select("a[href]").mapNotNull { anchor ->
            val episodeUrl = fixUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            if (!sameSite(episodeUrl) || episodeUrl.trimEnd('/') == pageUrl.trimEnd('/')) return@mapNotNull null
            val episodeName = sequenceOf(anchor.text().trim(), anchor.attr("title").trim())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val descriptor = "$episodeUrl $episodeName"
            if (!Regex("(?i)(bölüm|bolum|episode|/episodes?/|/bolum/|s\\d+\\s*e\\d+)").containsMatchIn(descriptor)) {
                return@mapNotNull null
            }
            val numbers = parseEpisodeNumbers(descriptor)
            newEpisode(episodeUrl) {
                name = episodeName.ifBlank { "Bölüm" }
                season = numbers?.first
                episode = numbers?.second
                posterUrl = poster
            }
        }.distinctBy { it.name + "|" + it.season + "|" + it.episode }

        val isSeries = episodes.isNotEmpty() ||
            Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl)
        return if (isSeries) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(data, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return false
        var found = false

        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitleUrl = fixUrl(raw, data) ?: continue
            if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                val language = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                subtitleCallback(SubtitleFile(language, subtitleUrl))
            }
        }

        val candidates = linkedSetOf<String>()
        for (element in document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src], a[href*='.m3u8'], a[href*='.mp4']"
        )) {
            val raw = when {
                element.hasAttr("src") -> element.attr("src")
                element.hasAttr("data-src") -> element.attr("data-src")
                else -> element.attr("href")
            }
            fixUrl(raw, data)?.let { candidates.add(it) }
        }

        val html = document.html().replace("\\/", "/").replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
        val mediaPattern = Regex("(?i)(?:https?:)?//[^\\s\\\"'<>]+\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>]*)?")
        mediaPattern.findAll(html).forEach { match ->
            fixUrl(match.value, data)?.let { candidates.add(it) }
        }

        for (candidate in candidates) {
            if (Regex("(?i)\\.(m3u8|mp4)(\\?.*)?$").containsMatchIn(candidate)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate,
                        type = if (candidate.substringBefore('?').endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else {
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }
        }
        return found
    }
}
