package com.Kayracs3

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

class FilmKutusu : MainAPI() {
    override var mainUrl = "https://filmkutusu.com"
    override var name = "FilmKutusu"
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

    // -----------------------------------------------------------------
    //  URL YARDIMCILARI
    // -----------------------------------------------------------------
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
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    // -----------------------------------------------------------------
    //  KART AYRIŞTIRMA  (li.movie-card)
    // -----------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!sameSite(url)) return null

        // Başlık: <strong> içindeki "izle" metnini temizle
        val rawTitle = sequenceOf(
            selectFirst("span.card-caption strong")?.text(),
            link.attr("title"),
            selectFirst("img")?.attr("alt")
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length >= 2 }
            ?: return null

        val title = rawTitle
            .replace(Regex("(?i)\\s+izle$"), "")
            .replace(Regex("(?i)\\s+film bilgileri$"), "")
            .trim()

        if (title.length < 2) return null

        // Afiş: doğrudan img src (poster-wrap içinde)
        val poster = selectFirst("span.poster-wrap img")?.let { img ->
            fixUrl(img.attr("src"), url)
        } ?: selectFirst("img")?.let { img ->
            fixUrl(img.attr("src"), url)
        }

        // Tür: data-title veya URL'de dizi/series geçiyorsa TvSeries
        val allText = "$title $url ${text()}"
        val type = if (Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()

        return newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun matchesQuery(query: String, title: String): Boolean {
        val n = title.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        val q = query.lowercase().trim()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        if (q.isBlank()) return true
        return n.contains(q) || q.split(Regex("\\s+")).filter { it.length > 1 }.all { n.contains(it) }
    }

    private fun parseResults(document: Document, query: String = ""): List<SearchResponse> {
        // Sadece film kartlarını al (menü/footer linklerini karıştırmaz)
        val cards = document.select("li.movie-card, article.movie-card, .movie-card")
            .mapNotNull { it.toSearchResult() }

        return cards.distinctBy { it.url }
            .filter { matchesQuery(query, it.name) }
            .take(100)
    }

    // -----------------------------------------------------------------
    //  ANA SAYFA / ARAMA
    // -----------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data.trimEnd('/') + "/sayfa/$page"
            else -> "$mainUrl/sayfa/$page"
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
        val enc = URLEncoder.encode(q, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$enc"
        val document = runCatching {
            app.get(searchUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return emptyList()
        return parseResults(document, q)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // -----------------------------------------------------------------
    //  DETAY SAYFASI
    // -----------------------------------------------------------------
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

        // Afiş: og:image → poster img → ilk img
        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.let { fixUrl(it, pageUrl) }
            ?: document.selectFirst("img[class*=poster], .poster img, .movie-poster img")
                ?.let { fixUrl(it.attr("src"), pageUrl) }
            ?: document.selectFirst("img")?.let { fixUrl(it.attr("src"), pageUrl) }

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

        val tags = document.select("a[rel=tag], a[href*=kategori], a[href*=tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)

        val episodes = document.select("a[href]").mapNotNull { anchor ->
            val epUrl = fixUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            if (!sameSite(epUrl) || epUrl.trimEnd('/') == pageUrl.trimEnd('/')) return@mapNotNull null
            val epName = sequenceOf(anchor.text().trim(), anchor.attr("title").trim())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val descriptor = "$epUrl $epName"
            if (!Regex("(?i)(bölüm|bolum|episode|/episodes?/|/bolum/|s\\d+\\s*e\\d+)").containsMatchIn(descriptor)) {
                return@mapNotNull null
            }
            val numbers = Regex("(?i)\\bs(\\d+)\\s*e(\\d+)\\b").find(descriptor)?.let {
                it.groupValues[1].toIntOrNull()?.let { s ->
                    it.groupValues[2].toIntOrNull()?.let { e -> s to e }
                }
            }
            newEpisode(epUrl) {
                name = epName.ifBlank { "Bölüm" }
                season = numbers?.first
                episode = numbers?.second
                posterUrl = poster
            }
        }.distinctBy { "${it.name}|${it.season}|${it.episode}" }

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

    // -----------------------------------------------------------------
    //  LİNK ÇIKARMA
    //  NOT: Bu sitede film detay sayfalarında gerçek bir video
    //  kaynağı (<video>, <iframe>, .m3u8) bulunmuyor.
    //  Site yalnızca film bilgisi arşivi gibi çalışıyor.
    // -----------------------------------------------------------------
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

        // Altyazılar (varsa)
        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitleUrl = fixUrl(raw, data) ?: continue
            if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                val language = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                subtitleCallback(SubtitleFile(language, subtitleUrl))
            }
        }

        // Video kaynağı adayları
        val candidates = linkedSetOf<String>()
        for (element in document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src], " +
            "a[href*='.m3u8'], a[href*='.mp4']"
        )) {
            val raw = when {
                element.hasAttr("src") -> element.attr("src")
                element.hasAttr("data-src") -> element.attr("data-src")
                else -> element.attr("href")
            }
            fixUrl(raw, data)?.let { candidates.add(it) }
        }

        val html = document.html().replace("\\/", "/")
            .replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
        val mediaPattern = Regex("(?i)(?:https?:)?//[^\\s\"'<>]+\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>]*)?")
        mediaPattern.findAll(html).forEach { m -> fixUrl(m.value, data)?.let { candidates.add(it) } }

        for (candidate in candidates) {
            if (Regex("(?i)\\.(m3u8|mp4)(\\?.*)?$").containsMatchIn(candidate)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate,
                        type = if (candidate.substringBefore('?').endsWith(".m3u8", true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = data
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
