package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {

    override var mainUrl = "https://dizipal1588.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/hd-film-izle" to "Filmler",
        "$mainUrl/kanal/exxen" to "Exxen",
        "$mainUrl/kanal/disney" to "Disney+",
        "$mainUrl/kanal/netflix" to "Netflix",
        "$mainUrl/kanal/amazon" to "Amazon",
        "$mainUrl/kanal/apple-tv" to "Apple TV+",
        "$mainUrl/kanal/max" to "Max",
        "$mainUrl/kanal/hulu" to "Hulu",
        "$mainUrl/kanal/tod" to "TOD",
        "$mainUrl/kanal/tabii" to "tabii",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = runCatching {
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false,
        )

        val baseUrl = documentBase(document, url)
        val results = parseListing(document, baseUrl)
        Log.d(
            "DiziPal",
            "Ana sayfa yükleme: ${results.size} kayıt, ${results.count { !it.posterUrl.isNullOrBlank() }} afiş",
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = page < 50 && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/arama-yap?q=$encoded",
            "$mainUrl/arama-yap?search=$encoded",
        )

        for (url in urls) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = "$mainUrl/arama-yap",
                    allowRedirects = true,
                ).document
            }.getOrNull() ?: continue

            val baseUrl = documentBase(document, url)
            val results = parseListing(document, baseUrl)
                .filterNot { isEpisodeUrl(it.url) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = normalizeUrl(url, mainUrl)

        val response = runCatching {
            app.get(
                pageUrl,
                headers = headers,
                referer = "$mainUrl/",
                allowRedirects = true,
            )
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document

        if (isEpisodeUrl(pageUrl)) {
            return loadEpisode(pageUrl, document)
        }

        val title = pageTitle(document, pageUrl) ?: return null
        val poster = posterOf(document)
        val plot = pagePlot(document)
        val year = pageYear(document)
        val rating = pageRating(document)

        val lower = pageUrl.lowercase()
        val isMovie = lower.contains("/movies/") || lower.contains("/movie/")

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl,
            ) {
                posterUrl = poster
                posterHeaders = posterRequestHeaders(documentBase(document, pageUrl))
                this.plot = plot
                this.year = year
                rating?.let { score = Score.from10(it) }
            }
        }

        val episodes = parseEpisodes(document, poster, documentBase(document, pageUrl))

        return newTvSeriesLoadResponse(
            title,
            pageUrl,
            TvType.TvSeries,
            episodes,
        ) {
            posterUrl = poster
            posterHeaders = posterRequestHeaders(documentBase(document, pageUrl))
            this.plot = plot
            this.year = year
            rating?.let { score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = normalizeUrl(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        val response = runCatching {
            app.get(
                sourceUrl,
                headers = headers,
                referer = "$mainUrl/",
                timeout = 15000,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        val document = response.document
        val raw = buildString {
            append(response.text)
            append("\n")
            append(document.html())
            document.select("script, noscript, template").forEach {
                append("\n")
                append(it.data())
                append("\n")
                append(it.html())
            }
        }.decodeEscapes()

        var found = false

        // Direct media fallback.
        for (media in findMediaUrls(raw)) {
            if (emitMediaLink(media, sourceUrl, callback)) {
                found = true
            }
        }

        val encrypted = extractEncryptedConfig(raw)

        if (encrypted != null) {
            val player = decryptDizipalConfig(encrypted)

            if (player.isNotBlank()) {
                Log.d("DiziPal", "Player URL: " + player.take(200))

                val iframeUrl = normalizeUrl(player, sourceUrl)

                val iframeResponse = runCatching {
                    app.get(
                        iframeUrl,
                        headers = headers + (
                            "Referer" to sourceUrl
                        ),
                        timeout = 15000,
                        allowRedirects = true,
                    )
                }.getOrNull()

                if (iframeResponse != null && iframeResponse.isSuccessful) {
                    val iframeDocument = iframeResponse.document
                    val iframeHtml = buildString {
                        append(iframeResponse.text)
                        append("\n")
                        append(iframeDocument.html())
                        iframeDocument.select("script, noscript, template").forEach {
                            append("\n")
                            append(it.data())
                            append("\n")
                            append(it.html())
                        }
                    }.decodeEscapes()

                    if (extractSubtitles(
                            iframeHtml,
                            iframeUrl,
                            subtitleCallback,
                        )
                    ) {
                        found = true
                    }

                    // Standard DPlayer flow:
                    // window.openPlayer("playlistId") -> source2.php?v=playlistId
                    val playlistId = Regex(
                        """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""",
                        RegexOption.IGNORE_CASE,
                    ).find(iframeHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (!playlistId.isNullOrBlank()) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val source2Url =
                            origin.trimEnd('/') +
                                "/source2.php?v=" +
                                URLEncoder.encode(playlistId, "UTF-8")

                        val source2Response = runCatching {
                            app.get(
                                source2Url,
                                headers = headers + mapOf(
                                    "Referer" to iframeUrl,
                                    "Accept" to "application/json,text/plain,*/*",
                                ),
                                timeout = 15000,
                                allowRedirects = true,
                            )
                        }.getOrNull()

                        if (source2Response != null && source2Response.isSuccessful) {
                            val source2Text =
                                source2Response.text.decodeEscapes()

                            for (media in extractFileUrls(source2Text)) {
                                if (emitMediaLink(
                                        media,
                                        iframeUrl,
                                        callback,
                                    )
                                ) {
                                    found = true
                                }
                            }

                            if (extractSubtitles(
                                    source2Text,
                                    iframeUrl,
                                    subtitleCallback,
                                )
                            ) {
                                found = true
                            }
                        }
                    }

                    // Some player revisions put the final URL directly in the iframe.
                    for (media in findMediaUrls(iframeHtml)) {
                        if (emitMediaLink(
                                media,
                                iframeUrl,
                                callback,
                            )
                        ) {
                            found = true
                        }
                    }

                    // HTML video/source fallback.
                    iframeDocument.select(
                        "video[src], video source[src], source[src], " +
                            "video[data-src], source[data-src]"
                    ).forEach { element ->
                        val media = element.attr("src")
                            .ifBlank { element.attr("data-src") }

                        if (media.isNotBlank()) {
                            val finalUrl = normalizeUrl(media, iframeUrl)
                            if (isMediaUrl(finalUrl) &&
                                emitMediaLink(
                                    finalUrl,
                                    iframeUrl,
                                    callback,
                                )
                            ) {
                                found = true
                            }
                        }
                    }
                }
            }
        }

        // Direct iframe/media fallback on the source page.
        document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], " +
                "source[src], [data-video-url], [data-stream]"
        ).forEach { element ->
            val rawUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video-url") }
                .ifBlank { element.attr("data-stream") }

            if (rawUrl.isBlank()) return@forEach

            val target = normalizeUrl(rawUrl, sourceUrl)

            if (isMediaUrl(target) &&
                emitMediaLink(target, sourceUrl, callback)
            ) {
                found = true
            }
        }

        // Public HTML subtitle tracks.
        document.select("track[src], track[data-src]").forEach { track ->
            val rawUrl = track.attr("src")
                .ifBlank { track.attr("data-src") }

            if (rawUrl.isBlank()) return@forEach

            runCatching {
                subtitleCallback(
                    newSubtitleFile(
                        lang = track.attr("label").ifBlank { "Türkçe" },
                        url = normalizeUrl(rawUrl, sourceUrl),
                    ) {
                        headers = mediaHeaders(sourceUrl)
                    },
                )
            }
        }

        return found
    }

    private suspend fun loadEpisode(
        url: String,
        document: Document,
    ): LoadResponse {
        val title = pageTitle(document, url) ?: "DiziPal Bölüm"
        val poster = posterOf(document)
        val numbers = episodeNumbersFrom(title + " " + url)

        val episode = newEpisode(url) {
            name = if (numbers != null) {
                "Bölüm " + numbers.second
            } else {
                title
            }

            if (numbers != null) {
                season = numbers.first
                this.episode = numbers.second
            }

            posterUrl = poster
        }

        val seriesTitle = title
            .replace(
                Regex(
                    """(?i)\s*\d+\s*[xX]\s*\d+.*$"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?i)\s*\d+\.\s*Sezon\s*\d+\.\s*Bölüm.*$"""
                ),
                "",
            )
            .trim()
            .ifBlank { title }

        return newTvSeriesLoadResponse(
            seriesTitle,
            url,
            TvType.TvSeries,
            listOf(episode),
        ) {
            posterUrl = poster
            posterHeaders = posterRequestHeaders(documentBase(document, url))
            plot = pagePlot(document)
            year = pageYear(document)
            pageRating(document)?.let { score = Score.from10(it) }
        }
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
        baseUrl: String,
    ): List<Episode> {
        val result = ArrayList<Episode>()

        // Group every anchor for the same canonical episode URL first.
        // Many site themes render the thumbnail and episode title as sibling
        // <a> elements. Processing only the first one made the result depend
        // on markup order and could borrow a neighbouring episode's image.
        val linksByEpisode = LinkedHashMap<String, MutableList<Element>>()
        document.select("a[href]").forEach { link ->
            val href = normalizeUrl(link.attr("href"), baseUrl)
            if (!isEpisodeUrl(href)) return@forEach

            val key = canonicalContentPath(href)
            linksByEpisode.getOrPut(key) { ArrayList() }.add(link)
        }

        val seen = HashSet<String>()
        linksByEpisode.values.forEach { links ->
            val href = normalizeUrl(links.first().attr("href"), baseUrl)
            val key = canonicalContentPath(href)
            if (!seen.add(key)) return@forEach

            val context = buildString {
                links.forEach { link ->
                    append(' ')
                    append(link.text())
                    append(' ')
                    append(link.attr("title"))
                    append(' ')
                    append(link.attr("aria-label"))
                    append(' ')
                    append(link.selectFirst("img")?.attr("alt").orEmpty())
                }
                append(' ')
                append(href)
            }

            val numbers = episodeNumbersFrom(context)
                ?: return@forEach

            // Only use artwork inside a link that itself points to this exact
            // episode. Never inspect a broad ancestor: it may contain another
            // episode's poster or a carousel image. The stable series poster
            // is the fallback when the episode has no directly linked image.
            val episodePoster = links.asSequence()
                .mapNotNull { link ->
                    posterFromElement(link, baseUrl)
                        ?.takeIf {
                            it.startsWith("http://", true) ||
                                it.startsWith("https://", true)
                        }
                }
                .firstOrNull()
                ?: poster

            result += newEpisode(href) {
                name = "Bölüm " + numbers.second
                season = numbers.first
                episode = numbers.second
                posterUrl = episodePoster
            }
        }

        // Some episodes are only listed in scripts/templates and have no
        // visible card image. Keep them, but use only the stable series cover.
        val html = document.html().decodeEscapes()
        Regex(
            """(?i)(?:https?:)?//[^"'<>\\s]+/bolum/[^"'<>\\s]+"""
        ).findAll(html).forEach { match ->
            val href = normalizeUrl(match.value, baseUrl)
            if (!isEpisodeUrl(href)) return@forEach

            val key = canonicalContentPath(href)
            if (!seen.add(key)) return@forEach

            val numbers = episodeNumbersFrom(href)
                ?: return@forEach

            result += newEpisode(href) {
                name = "Bölüm " + numbers.second
                season = numbers.first
                episode = numbers.second
                posterUrl = poster
            }
        }

        return result
            .distinctBy {
                canonicalContentPath(it.data)
            }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun parseListing(
        document: Document,
        baseUrl: String,
    ): List<SearchResponse> {
        val resultsByUrl = LinkedHashMap<String, SearchResponse>()
        val posterSelector = posterImageSelector()
        val trendUrls = findTrendUrls(document, baseUrl)
        val anchors = listingAnchors(document, baseUrl, trendUrls)

        var posterCount = 0
        anchors.forEach { link ->
            val href = normalizeUrl(link.attr("href"), baseUrl)
            if (href.isBlank() || !isCatalogItemUrl(href)) return@forEach
            if (trendUrls.any { sameContentUrl(it, href) }) return@forEach
            if (isInsideExcludedSection(link, baseUrl)) return@forEach

            val lower = href.lowercase()
            if (lower.contains("/arama-yap") ||
                lower.contains("/profil") ||
                lower.contains("/iletisim") ||
                lower.contains("discord.gg") ||
                lower.contains("twitter.com")
            ) return@forEach

            val isSeries = lower.contains("/series/")
            val isMovie = lower.contains("/movies/") || lower.contains("/movie/")
            if (!isSeries && !isMovie) return@forEach

            // First inspect the exact detail link. Only if it has no image,
            // allow borrowing a single image from its own one-title card.
            val directPoster = posterFromElement(link, baseUrl)
            val poster = directPoster ?: posterFromNearbyCard(link, href, baseUrl)
            val card = if (directPoster != null || poster == null) {
                null
            } else {
                link.parents().firstOrNull { ancestor ->
                    val targets = ancestor.select("a[href]")
                        .mapNotNull { candidate ->
                            normalizeUrl(candidate.attr("href"), baseUrl)
                                .takeIf { isContentTargetUrl(it) }
                        }
                        .distinctBy { canonicalContentPath(it) }

                    val posterUrls = ancestor.select(posterSelector)
                        .mapNotNull { candidate ->
                            posterRawFromElement(candidate)
                                ?.let { normalizeUrl(it, baseUrl) }
                                ?.takeIf { it.startsWith("http", true) }
                        }
                        .distinct()

                    targets.size == 1 &&
                        sameContentUrl(targets.first(), href) &&
                        posterUrls.size == 1 &&
                        posterUrls.first() == poster
                }
            }

            val title = cleanCardTitle(
                listOf(
                    link.selectFirst("img")?.attr("alt"),
                    link.selectFirst("h1,h2,h3,h4,.title,.name")?.text(),
                    link.attr("title"),
                    link.attr("aria-label"),
                    link.text(),
                    card?.selectFirst("[title]")?.attr("title"),
                ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
            )
            if (title.isBlank()) return@forEach

            val existing = resultsByUrl[href]
            if (existing != null) {
                // Repeated links to the same title may fill a missing poster,
                // but may not replace a poster that already belongs to it.
                if (existing.posterUrl.isNullOrBlank() && !poster.isNullOrBlank()) {
                    existing.posterUrl = poster
                    existing.posterHeaders = posterRequestHeaders(baseUrl)
                    posterCount++
                }
                return@forEach
            }

            val rating = scoreFromText(
                listOf(link.text(), link.attr("title"), card?.text())
                    .joinToString(" ")
            )
            val item: SearchResponse = if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = posterRequestHeaders(baseUrl)
                    rating?.let { score = Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = posterRequestHeaders(baseUrl)
                    rating?.let { score = Score.from10(it) }
                }
            }

            resultsByUrl[href] = item
            if (!poster.isNullOrBlank()) posterCount++
        }

        Log.d(
            "DiziPal",
            "Katalog: " + resultsByUrl.size + " kayıt, " + posterCount +
                " afiş; " + trendUrls.size + " trend bağlantısı listeden çıkarıldı",
        )
        return resultsByUrl.values.toList()
    }

    private fun listingAnchors(
        document: Document,
        baseUrl: String,
        trendUrls: Set<String>,
    ): List<Element> {
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        val resultsHeading = headings.firstOrNull {
            val title = normalizeTitleForMatch(it.text())
            title.contains("filtrelenmis sonuclar") ||
                title == "diziler" ||
                title.contains("yabanci dizi") ||
                title.contains("dizi arsivi") ||
                title.contains("tum diziler") ||
                title.contains("tum filmler")
        }

        // Platform pages have an explicit "Filtrelenmiş Sonuçlar" heading.
        // Use its nearest bounded results container, not every link in the
        // complete document (which also contains the Trending footer).
        if (resultsHeading != null) {
            for (ancestor in resultsHeading.parents()) {
                val hasOtherSection = ancestor.select("h1,h2,h3,h4,h5,h6").any { heading ->
                    heading !== resultsHeading && isExcludedSectionHeading(heading.text())
                }
                if (hasOtherSection) continue

                val candidates = ancestor.select("a[href]").filter { link ->
                    val url = normalizeUrl(link.attr("href"), baseUrl)
                    isCatalogItemUrl(url) &&
                        trendUrls.none { sameContentUrl(it, url) } &&
                        !isInsideExcludedSection(link, baseUrl)
                }
                val distinctCount = candidates
                    .map { normalizeUrl(it.attr("href"), baseUrl) }
                    .distinctBy { canonicalContentPath(it) }
                    .size

                if (distinctCount in 3..120) return candidates
                if (distinctCount > 120) break
            }
        }

        // Fallback: use the page's main content region when it exists.
        val main = document.selectFirst(
            "main, #main, #content, #primary, .site-main, .main-content, " +
                ".content-area, .archive-content, .catalog-content"
        )
        val source = main?.select("a[href]") ?: document.select("a[href]")
        return source.filter { link ->
            val url = normalizeUrl(link.attr("href"), baseUrl)
            isCatalogItemUrl(url) &&
                trendUrls.none { sameContentUrl(it, url) } &&
                !isInsideExcludedSection(link, baseUrl)
        }
    }

    private fun findTrendUrls(
        document: Document,
        baseUrl: String,
    ): Set<String> {
        val result = LinkedHashSet<String>()
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        for (heading in headings) {
            if (!isTrendHeading(heading.text())) continue

            // The first ancestor containing actual title links is the trend
            // component; stop before climbing into a page-wide content wrapper.
            for (ancestor in heading.parents()) {
                val urls = ancestor.select("a[href]")
                    .mapNotNull { link ->
                        normalizeUrl(link.attr("href"), baseUrl)
                            .takeIf { isCatalogItemUrl(it) }
                    }
                    .distinctBy { canonicalContentPath(it) }

                if (urls.isNotEmpty() && urls.size <= 18) {
                    result.addAll(urls)
                    break
                }
                if (urls.size > 18) break
            }
        }

        return result
    }

    private fun isTrendHeading(value: String): Boolean {
        val title = normalizeTitleForMatch(value)
        return title.contains("trend diz") ||
            title.contains("populer diz") ||
            title.contains("trending") ||
            title.contains("onerilen diz") ||
            title.contains("benzer diz")
    }

    private fun isExcludedSectionHeading(value: String): Boolean {
        val title = normalizeTitleForMatch(value)
        return isTrendHeading(value) ||
            title.contains("son eklenen") ||
            title.contains("benzer") ||
            title.contains("onerilen")
    }

    private fun isInsideExcludedSection(
        element: Element,
        baseUrl: String,
    ): Boolean {
        val excludedClassOrId = Regex(
            "(?i)(trend|trending|popular|populer|sidebar|footer|recommend|" +
                "related|carousel|swiper|slick|owl|breadcrumb|navbar|navigation|menu)"
        )

        var current: Element? = element
        repeat(8) {
            val node = current ?: return false
            if (excludedClassOrId.containsMatchIn(node.attr("class")) ||
                excludedClassOrId.containsMatchIn(node.id())
            ) return true

            current = node.parent()
        }
        return false
    }

    private fun canonicalContentPath(url: String): String {
        return runCatching {
            URI(url).path.orEmpty().lowercase().trimEnd('/')
        }.getOrDefault(url.lowercase().trimEnd('/'))
    }

    private fun sameContentUrl(first: String, second: String): Boolean {
        return canonicalContentPath(first) == canonicalContentPath(second)
    }

    private fun hasNextPage(
        document: Document,
        page: Int,
    ): Boolean {
        if (document.select("a[href]").any { element ->
                val text = element.text().trim().lowercase()
                text.contains("sonraki") ||
                    text == "next" ||
                    element.attr("rel").equals("next", true)
            }
        ) return true

        val next = page + 1

        return document.select("a[href]").any {
            val href = it.attr("href")
            href.contains("page=" + next) ||
                href.contains("sayfa=" + next) ||
                href.contains("/page/" + next + "/")
        }
    }

    private fun buildPageUrl(
        base: String,
        page: Int,
    ): String {
        if (page <= 1) return base

        return if (base.contains("?")) {
            base + "&page=" + page
        } else {
            base + "?page=" + page
        }
    }

    private fun documentBase(
        document: Document,
        fallback: String,
    ): String {
        val location = document.location().trim()
        return location.takeIf {
            it.startsWith("http://", true) ||
                it.startsWith("https://", true)
        } ?: fallback
    }

    private fun srcSetCandidate(
        value: String,
    ): String? {
        if (value.isBlank()) return null

        return value.split(",")
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull().orEmpty()
                if (url.isBlank()) return@mapNotNull null

                val descriptor = parts.getOrNull(1).orEmpty()
                val size = descriptor.removeSuffix("w")
                    .removeSuffix("x")
                    .toDoubleOrNull() ?: 0.0

                url to size
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun posterRawFromElement(
        image: Element,
    ): String? {
        val styleUrl = Regex(
            """(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)"""
        ).find(image.attr("style"))
            ?.groupValues
            ?.getOrNull(1)

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-lazy"),
            image.attr("data-lazyload"),
            image.attr("data-lazyload-src"),
            image.attr("data-src-original"),
            image.attr("data-original"),
            image.attr("data-original-src"),
            image.attr("data-original-url"),
            image.attr("data-image"),
            image.attr("data-image-src"),
            image.attr("data-image-original"),
            image.attr("data-img"),
            image.attr("data-img-url"),
            image.attr("data-thumb"),
            image.attr("data-thumb-url"),
            image.attr("data-poster"),
            image.attr("data-poster-url"),
            image.attr("data-poster-src"),
            image.attr("data-flickity-lazyload"),
            image.attr("data-flickity-lazyload-src"),
            image.attr("data-echo"),
            image.attr("data-echo-lazy"),
            image.attr("data-background"),
            image.attr("data-background-image"),
            image.attr("data-background-src"),
            image.attr("data-bg"),
            image.attr("data-bg-src"),
            image.attr("data-url"),
            image.attr("data-cfsrc"),
            image.attr("data-cf-src"),
            srcSetCandidate(image.attr("data-srcset")),
            srcSetCandidate(image.attr("data-lazy-srcset")),
            image.attr("src"),
            srcSetCandidate(image.attr("srcset")),
            image.attr("content"),
            styleUrl,
        )

        return candidates.firstOrNull { raw ->
            val value = raw.orEmpty().trim()
            value.isNotBlank() &&
                !value.startsWith("data:", true) &&
                !value.equals("about:blank", true) &&
                !value.contains("placeholder", true) &&
                !value.contains("loading.gif", true) &&
                !value.contains("transparent.gif", true) &&
                !value.contains("spacer.gif", true) &&
                !value.contains("pixel.gif", true)
        }
    }

    private fun posterFromElement(
        element: Element?,
        baseUrl: String = mainUrl,
    ): String? {
        if (element == null) return null

        val selfRaw = posterRawFromElement(element)
        val image = if (
            element.tagName().equals("img", true) ||
            element.tagName().equals("source", true) ||
            !selfRaw.isNullOrBlank()
        ) {
            element
        } else {
            element.select(posterImageSelector()).firstOrNull { candidate ->
                posterRawFromElement(candidate) != null
            } ?: return null
        }

        val raw = posterRawFromElement(image) ?: return null
        val url = normalizeUrl(raw, baseUrl)

        return url.takeIf {
            it.startsWith("http://", true) ||
                it.startsWith("https://", true)
        }
    }

    private fun posterFromNearbyCard(
        link: Element,
        targetUrl: String,
        baseUrl: String,
    ): String? {
        val targetPath = canonicalContentPath(normalizeUrl(targetUrl, baseUrl))

        for (ancestor in link.parents()) {
            // The cover and title are often separate links inside the same
            // card. Prefer an image explicitly linked to this exact series or
            // movie; this works even when the card also contains badges/logos.
            val matchingLinks = ancestor.select("a[href]").filter { candidate ->
                val candidateUrl = normalizeUrl(candidate.attr("href"), baseUrl)
                isCatalogItemUrl(candidateUrl) &&
                    canonicalContentPath(candidateUrl) == targetPath
            }
            for (candidate in matchingLinks) {
                // Because this anchor points to the exact catalog item, trust
                // its non-placeholder image URL even when the server uses a
                // URL without a .jpg/.webp suffix.
                val linkedPoster = posterFromElement(candidate, baseUrl)
                    ?.takeIf {
                        it.startsWith("http://", true) ||
                            it.startsWith("https://", true)
                    }
                if (!linkedPoster.isNullOrBlank()) return linkedPoster
            }

            // Fallback for cards that use an unlinked background image. Only
            // accept it when this small card points to one catalog item and
            // has one unambiguous image, so a neighbouring Trend poster cannot
            // be borrowed.
            val contentTargets = ancestor.select("a[href]")
                .mapNotNull { candidate ->
                    normalizeUrl(candidate.attr("href"), baseUrl)
                        .takeIf { isCatalogItemUrl(it) }
                }
                .distinctBy { canonicalContentPath(it) }

            if (contentTargets.size != 1 ||
                canonicalContentPath(contentTargets.first()) != targetPath
            ) {
                continue
            }

            val posters = ancestor.select(posterImageSelector())
                .mapNotNull { candidate ->
                    posterRawFromElement(candidate)
                        ?.let { normalizeUrl(it, baseUrl) }
                        ?.takeIf {
                            it.startsWith("http://", true) ||
                                it.startsWith("https://", true)
                        }
                }
                .toSet()

            if (posters.size == 1) return posters.first()
        }
        return null
    }

    private fun isContentTargetUrl(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return false
        }
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
            .trimEnd('/')
        if (path.isBlank() || path == "/") return false

        val excludedPrefixes = listOf(
            "/kanal/", "/category/", "/tag/", "/arama", "/search", "/profil",
            "/iletisim", "/login", "/register", "/yabanci-dizi-izle",
            "/hd-film-izle", "/page/", "/wp-", "/feed",
        )
        if (excludedPrefixes.any { path.startsWith(it) }) return false

        return isCatalogItemUrl(url) || isEpisodeUrl(url) ||
            path.trim('/').split('/').filter { it.isNotBlank() }.size >= 2
    }

    private fun posterRequestHeaders(baseUrl: String = mainUrl): Map<String, String> {
        val siteOrigin = originOf(baseUrl) ?: mainUrl
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$siteOrigin/",
            "Origin" to siteOrigin,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        )
    }

    private fun posterOf(
        document: Document,
    ): String? {
        val baseUrl = documentBase(document, mainUrl)
        val title = pageTitle(document, baseUrl).orEmpty()

        // If Open Graph points to a trending poster with a different title,
        // reject it instead of copying that image into every episode.
        val ogImage = document.selectFirst("meta[property='og:image'], meta[name='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it, baseUrl) }

        if (!ogImage.isNullOrBlank() && isLikelyPosterUrl(ogImage)) {
            val correspondingImage = document.select(posterImageSelector())
                .firstOrNull { element ->
                    posterRawFromElement(element)
                        ?.let { normalizeUrl(it, baseUrl) == ogImage } == true
                }
            if (correspondingImage != null &&
                isDetailPosterContext(correspondingImage, baseUrl) &&
                isPosterLabelCompatible(correspondingImage, title)
            ) {
                return ogImage
            }

            if (posterUrlContainsTitle(ogImage, title)) return ogImage
        }

        // Use title-specific poster containers first. A labelled image whose
        // name belongs to Reacher/another Trending show must not be reused for
        // an unrelated page.
        val selectors = listOf(
            "[itemprop='image']",
            ".detail-poster img",
            ".detail-poster",
            ".series-poster img",
            ".movie-poster img",
            ".poster-container img",
            ".poster-container",
            ".single-poster img",
            ".entry-poster img",
            ".post-thumbnail img",
            ".film-poster img",
            ".cover-image img",
            ".cover img",
            ".poster img",
            ".poster",
        )

        for (selector in selectors) {
            for (element in document.select(selector)) {
                val image = if (
                    element.tagName().equals("img", true) ||
                    element.tagName().equals("source", true) ||
                    !posterRawFromElement(element).isNullOrBlank()
                ) {
                    element
                } else {
                    element.select(posterImageSelector()).firstOrNull { candidate ->
                        posterRawFromElement(candidate) != null
                    }
                } ?: continue

                // Validate the actual image node, not just its wrapper. The
                // wrapper normally has no alt/title and could hide a Trending
                // poster labelled with a different show's name.
                if (!isDetailPosterContext(image, baseUrl)) continue
                if (!isPosterLabelCompatible(image, title)) continue
                posterFromElement(image, baseUrl)
                    ?.takeIf(::isLikelyPosterUrl)
                    ?.let { return it }
            }
        }

        // A hero image may sit close to the title, but only accept an ancestor
        // that contains no links to other content items and an unambiguous
        // image, so a Trend carousel can never lend its first poster.
        val heading = document.selectFirst("h1")
        var ancestor = heading?.parent()
        repeat(4) {
            val container = ancestor ?: return@repeat
            val itemUrls = container.select("a[href]")
                .mapNotNull { link ->
                    normalizeUrl(link.attr("href"), baseUrl)
                        .takeIf { isContentTargetUrl(it) }
                }
                .toSet()
            val images = container.select(posterImageSelector())
                .filter { posterRawFromElement(it) != null }
            val imageUrls = images.mapNotNull { element ->
                posterRawFromElement(element)
                    ?.let { normalizeUrl(it, baseUrl) }
                    ?.takeIf { it.startsWith("http", true) }
            }.toSet()

            if (itemUrls.isEmpty() && imageUrls.size == 1) {
                val candidate = images.firstOrNull()
                if (candidate != null && isPosterLabelCompatible(candidate, title)) {
                    posterFromElement(candidate, baseUrl)
                        ?.takeIf(::isLikelyPosterUrl)
                        ?.let { return it }
                }
            }
            ancestor = container.parent()
        }

        // Missing is preferable to displaying a poster for a different show.
        return null
    }

    private fun isDetailPosterContext(element: Element, pageUrl: String): Boolean {
        var current: Element? = element
        val excludedSection = Regex(
            "(?i)(trend|trending|popular|populer|recommend|related|carousel|swiper|slick|owl|sidebar|footer)"
        )
        val pageBoundary = Regex(
            "(?i)(site-main|site-content|content-area|main-content|page-content|archive-content|primary-content|entry-content)"
        )

        repeat(8) {
            val node = current ?: return true
            val classesAndId = node.attr("class") + " " + node.id()

            // Reject a poster inside an identified Trend/recommendation card.
            if (excludedSection.containsMatchIn(classesAndId)) return false

            // Detail pages often include their season/episode links alongside
            // the real cover. Stop before scanning the whole page, where those
            // links (and recommendations) would incorrectly invalidate it.
            if (node.tagName().lowercase() in setOf("main", "body", "html", "footer") ||
                pageBoundary.containsMatchIn(classesAndId)
            ) return true

            // Jsoup Element.select() excludes the current element itself.
            // Check an enclosing anchor directly so a linked Trend image fails.
            if (node.tagName().equals("a", true) && node.hasAttr("href")) {
                val target = normalizeUrl(node.attr("href"), pageUrl)
                if (isCatalogItemUrl(target) && !sameContentUrl(target, pageUrl)) {
                    return false
                }
            }

            // Only other series/movie links can invalidate a detail poster.
            // Episode links on the current series page are expected and safe.
            val targets = node.select("a[href]")
                .mapNotNull { link ->
                    normalizeUrl(link.attr("href"), pageUrl)
                        .takeIf { isCatalogItemUrl(it) }
                }
                .distinctBy { canonicalContentPath(it) }

            if (targets.any { !sameContentUrl(it, pageUrl) }) return false
            current = node.parent()
        }
        return true
    }

    private fun isPosterLabelCompatible(element: Element, pageTitle: String): Boolean {
        val labels = listOf(
            element.attr("alt"),
            element.attr("title"),
            element.attr("data-title"),
            element.attr("data-name"),
            element.attr("aria-label"),
        )
        val label = labels.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (label.isBlank()) return true

        val normalizedLabel = normalizeTitleForMatch(label)
        val normalizedTitle = normalizeTitleForMatch(pageTitle)
        if (normalizedLabel in setOf("poster", "image", "thumbnail", "afis", "cover")) {
            return true
        }
        if (normalizedLabel.isBlank() || normalizedTitle.isBlank()) return true

        if (normalizedLabel.contains(normalizedTitle) ||
            normalizedTitle.contains(normalizedLabel)
        ) return true

        val titleTokens = normalizedTitle.split(" ").filter { it.length >= 3 }.toSet()
        val labelTokens = normalizedLabel.split(" ").filter { it.length >= 3 }.toSet()
        if (titleTokens.isEmpty() || labelTokens.isEmpty()) return true
        return titleTokens.intersect(labelTokens).size.toDouble() /
            titleTokens.size.toDouble() >= 0.5
    }

    private fun normalizeTitleForMatch(value: String): String =
        value.lowercase()
            .replace("&", " and ")
            .replace(Regex("(?i)\\b(izle|hd|dizi|film|poster|afis|cover|image)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun posterUrlContainsTitle(url: String, title: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .substringAfterLast('/')
        if (path.isBlank()) return false
        val titleTokens = normalizeTitleForMatch(title)
            .split(" ").filter { it.length >= 3 }.toSet()
        val pathTokens = normalizeTitleForMatch(path)
            .split(" ").filter { it.length >= 3 }.toSet()
        if (titleTokens.isEmpty() || pathTokens.isEmpty()) return false
        return titleTokens.intersect(pathTokens).size.toDouble() /
            titleTokens.size.toDouble() >= 0.5
    }

    private fun posterImageSelector(): String =
        "img, picture source, " +
            "[data-src], [data-lazy-src], [data-lazyload], [data-lazyload-src], " +
            "[data-original], [data-original-src], [data-original-url], " +
            "[data-image], [data-image-src], [data-image-original], " +
            "[data-img], [data-img-url], [data-thumb], [data-thumb-url], " +
            "[data-poster], [data-poster-url], [data-poster-src], " +
            "[data-flickity-lazyload], [data-flickity-lazyload-src], " +
            "[data-echo], [data-echo-lazy], [data-background], " +
            "[data-background-image], [data-background-src], [data-bg], " +
            "[data-bg-src], [data-srcset], [data-lazy-srcset], [style*=background]"

    private fun isCatalogItemUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
        return path.contains("/series/") ||
            path.contains("/movies/") ||
            path.contains("/movie/")
    }

    private fun isLikelyPosterUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
        if (path.isBlank()) return false
        if (path.startsWith("/series/") ||
            path.startsWith("/movies/") ||
            path.startsWith("/movie/") ||
            path.startsWith("/bolum/")
        ) return false

        val extension = path.substringAfterLast('.', "")
        return extension in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") ||
            path.contains("image") ||
            path.contains("poster") ||
            path.contains("thumb") ||
            path.contains("cover") ||
            path.contains("upload") ||
            path.contains("cdn")
    }

    private fun pageTitle(
        document: Document,
        url: String,
    ): String? {
        return listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")
                ?.attr("content"),
            document.selectFirst("title")?.text(),
            url.substringAfterLast('/')
                .replace('-', ' '),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.let(::cleanCardTitle)
    }

    private fun pagePlot(
        document: Document,
    ): String? {
        return listOf(
            document.selectFirst("meta[property='og:description']")
                ?.attr("content"),
            document.selectFirst("meta[name='description']")
                ?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
            document.selectFirst(".summary")?.text(),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
    }

    private fun pageYear(
        document: Document,
    ): Int? {
        val focused = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")
                ?.attr("content"),
            document.selectFirst("meta[name='description']")
                ?.attr("content"),
            document.selectFirst(".year")?.text(),
        )
            .filterNotNull()
            .joinToString(" ")

        return Regex(
            """(?<!\d)(?:19|20)\d{2}(?!\d)"""
        )
            .find(focused)
            ?.value
            ?.toIntOrNull()
    }

    private fun pageRating(
        document: Document,
    ): Double? {
        return scoreFromText(document.text())
    }

    private fun scoreFromText(
        text: String?,
    ): Double? {
        if (text.isNullOrBlank()) return null

        val imdb = Regex(
            """(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        )
            .find(text)

        val imdbScore = imdb
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

        if (imdbScore != null) return imdbScore

        // Kanal/listing kartlarında puan çoğu zaman "2026 7.7 Başlık"
        // şeklinde IMDb etiketi olmadan gösteriliyor.
        val yearScore = Regex(
            """(?<!\d)(?:19|20)\d{2}\s+([0-9](?:[.,][0-9])?)(?!\d)"""
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

        return yearScore
    }

    private fun cleanCardTitle(
        raw: String,
    ): String {
        var title = raw
            .replace(Regex("\\s+"), " ")
            .trim()

        title = title
            .replace(
                Regex("(?i)^Dublaj\\s+"),
                "",
            )
            .replace(
                Regex("(?i)^Altyazı\\s+"),
                "",
            )

        // Current film cards can contain:
        // Dublaj Altyazı 2025 7.0 Barselo
        title = title.replace(
            Regex(
                """(?i)^(?:19|20)\d{2}\s+\d+(?:[.,]\d+)?\s+"""
            ),
            "",
        )

        title = title.replace(
            Regex(
                """(?i)\s+\d+\.\s*Sezon\s+\d+\.\s*Bölüm\s*$"""
            ),
            "",
        )

        return title.trim()
    }

    private fun episodeNumbersFrom(
        text: String,
    ): Pair<Int, Int>? {
        val source = text
            .replace("\\/", "/")
            .replace(Regex("\\s+"), " ")
            .trim()

        val patterns = listOf(
            Regex(
                """(?ix)(?:sezon|season)\s*[-._ ]?\s*(\d+)\D{0,30}?(?:bölüm|bolum|episode)\s*[-._ ]?\s*(\d+)"""
            ),
            Regex(
                """(?ix)(?:bölüm|bolum|episode)\s*[-._ ]?\s*(\d+)\D{0,30}?(?:sezon|season)\s*[-._ ]?\s*(\d+)"""
            ),
            Regex(
                """(?ix)\b(\d+)\s*[xX]\s*(\d+)\b"""
            ),
        )

        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(source) ?: continue
            val first = match.groupValues[1]
                .toIntOrNull()
                ?: continue
            val second = match.groupValues[2]
                .toIntOrNull()
                ?: continue

            return if (index == 1) {
                second to first
            } else {
                first to second
            }
        }

        return null
    }

    private fun isEpisodeUrl(
        url: String,
    ): Boolean {
        return runCatching {
            URI(url).path
                ?.lowercase()
                ?.startsWith("/bolum/") == true
        }.getOrDefault(false)
    }

    private fun isMediaUrl(
        url: String,
    ): Boolean {
        val value = url.lowercase()
        return Regex(
            """(?i)\.(?:m3u8|mpd|mp4|webm)(?:[?#]|$)"""
        ).containsMatchIn(value) ||
            value.contains("/hls/") ||
            value.contains("/hls2/")
    }

    private fun findMediaUrls(
        text: String,
    ): Set<String> {
        val found = LinkedHashSet<String>()

        listOf(
            Regex(
                """https?://[^"'<>\\s]+?\.m3u8(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.mp4(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.mpd(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.webm(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
        ).forEach { regex ->
            regex.findAll(text).forEach { match ->
                val url = cleanUrl(match.value)
                if (url.isNotBlank()) found += url
            }
        }

        return found
    }

    private fun extractFileUrls(
        text: String,
    ): Set<String> {
        val found = LinkedHashSet<String>()

        Regex(
            """"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(text)
            .forEach { match ->
                val url = cleanUrl(match.groupValues[1])

                if (url.isNotBlank() &&
                    (isMediaUrl(url) || url.contains("m.php", true))
                ) {
                    found += url
                }
            }

        Regex(
            """(?i)"(?:source|src|url|hls)"\s*:\s*"([^"]+)""""
        )
            .findAll(text)
            .forEach { match ->
                val url = cleanUrl(match.groupValues[1])

                if (url.isNotBlank() &&
                    (isMediaUrl(url) || url.contains("m.php", true))
                ) {
                    found += url
                }
            }

        return found
    }

    private suspend fun emitMediaLink(
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var url = cleanUrl(rawUrl)

        if (url.startsWith("//")) {
            url = "https:" + url
        }

        if (url.contains("m.php", true)) {
            url = url.replace(
                Regex("(?i)m\\.php"),
                "master.m3u8",
            )
        }

        if (!url.startsWith("http", true)) return false

        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "DiziPal",
                url = url,
                type = type,
            ) {
                quality = qualityFromUrl(url)
                this.referer = referer
                headers = mediaHeaders(referer)
            }
        )

        return true
    }

    private fun mediaHeaders(
        referer: String,
    ): Map<String, String> {
        val result = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to referer,
        )

        originOf(referer)?.let {
            result["Origin"] = it
        }

        return result
    }

    private fun qualityFromUrl(
        url: String,
    ): Int {
        val value = url.lowercase()

        return when {
            "2160" in value || "4k" in value ->
                Qualities.P2160.value

            "1440" in value ->
                Qualities.P1440.value

            "1080" in value ->
                Qualities.P1080.value

            "720" in value ->
                Qualities.P720.value

            "480" in value ->
                Qualities.P480.value

            "360" in value ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private suspend fun emitSubtitle(
        url: String,
        lang: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return try {
            subtitleCallback(
                newSubtitleFile(
                    lang = lang,
                    url = url,
                ) {
                    headers = mediaHeaders(referer)
                }
            )
            true
        } catch (e: Exception) {
            Log.d("DiziPal", "Subtitle skipped: " + e.message)
            false
        }
    }

    private suspend fun extractSubtitles(
        text: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        var found = false
        val seen = HashSet<String>()

        suspend fun register(rawUrl: String?, rawLang: String? = null) {
            var url = normalizeUrl(rawUrl, referer)
            if (url.isBlank() || !url.startsWith("http", true)) return

            url = cleanUrl(url)
            if (url.isBlank() || !seen.add(url)) return

            val lower = url.lowercase()
            val subtitleLike =
                lower.endsWith(".srt") ||
                    lower.endsWith(".vtt") ||
                    lower.endsWith(".ass") ||
                    lower.endsWith(".ssa") ||
                    lower.contains("subtitle") ||
                    lower.contains("subtitles") ||
                    lower.contains("/subs/") ||
                    lower.contains("/sub/") ||
                    lower.contains("caption") ||
                    lower.contains("captions") ||
                    lower.contains("sub.php") ||
                    lower.contains("subtitle.php")

            if (!subtitleLike) return

            val lang = decodeJsonText(rawLang.orEmpty()).trim()
                .ifBlank { languageFromSubtitleUrl(url) }

            if (emitSubtitle(
                    url,
                    lang.ifBlank { "Türkçe" },
                    referer,
                    subtitleCallback,
                )
            ) {
                found = true
            }
        }

        // Common DPlayer/JSON layouts:
        // {"file":"...vtt","label":"Türkçe"}
        val fileLabelRegex = Regex(
            """"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        for (match in fileLabelRegex.findAll(text)) {
            register(
                match.groupValues[1],
                match.groupValues[2],
            )
        }

        // Some versions use label before file.
        val labelFileRegex = Regex(
            """"label"\s*:\s*"([^"]+)"\s*,\s*"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        for (match in labelFileRegex.findAll(text)) {
            register(
                match.groupValues[2],
                match.groupValues[1],
            )
        }

        // tracks/captions/subtitle objects can use url/src instead of file.
        val trackObjectRegex = Regex(
            """(?is)"(?:track|tracks|caption|captions|subtitle|subtitles?)"\s*:\s*(?:\[[^\]]*\]|\{[^}]*\})"""
        )
        val trackUrlRegex = Regex(
            """(?i)"(?:file|src|url|source)"\s*:\s*"([^"]+)""""
        )
        val trackLabelRegex = Regex(
            """(?i)"(?:label|lang|language|name)"\s*:\s*"([^"]+)""""
        )
        for (block in trackObjectRegex.findAll(text)) {
            val value = block.value
            val label = trackLabelRegex.find(value)
                ?.groupValues
                ?.getOrNull(1)

            for (urlMatch in trackUrlRegex.findAll(value)) {
                register(urlMatch.groupValues[1], label)
            }
        }

        // Scalar subtitle fields.
        val scalarSubtitleRegex = Regex(
            """(?i)"(?:subtitle|subtitles?|caption|captions?|subtitle_url|subtitleUrl|caption_url|captionUrl|sub_url|subUrl)"\s*:\s*"([^"]+)""""
        )
        for (match in scalarSubtitleRegex.findAll(text)) {
            register(match.groupValues[1])
        }

        // Bare subtitle URLs, including protocol-relative and extensionless
        // subtitle endpoints used by some player revisions.
        val subtitleUrlRegex = Regex(
            """(?i)(?:(?:https?:)?//|/)[^"'<>\\s]+(?:\.srt|\.vtt|\.ass|\.ssa)(?:\?[^"'<>\\s]*)?"""
        )
        for (match in subtitleUrlRegex.findAll(text)) {
            register(match.value)
        }

        val subtitleEndpointRegex = Regex(
            """(?i)(?:(?:https?:)?//)[^"'<>\\s]*(?:subtitle|subtitles|caption|captions|sub\.php|subtitle\.php)[^"'<>\\s]*"""
        )
        for (match in subtitleEndpointRegex.findAll(text)) {
            register(match.value)
        }

        // HTML <track> elements that survived Jsoup parsing.
        val trackDocument = org.jsoup.Jsoup.parse(text)
        for (track in trackDocument.select(
            "track[src], track[data-src], track[kind='subtitles'], track[kind='captions']"
        )) {
            val rawUrl = track.attr("src")
                .ifBlank { track.attr("data-src") }

            register(
                rawUrl,
                track.attr("label")
                    .ifBlank { track.attr("srclang") }
                    .ifBlank { track.attr("lang") },
            )
        }

        return found
    }

    private fun languageFromSubtitleUrl(
        url: String,
    ): String {
        val value = url.lowercase()

        return when {
            Regex("""(?:^|[^a-z])(?:tr|tur|turkish)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Türkçe"

            Regex("""(?:^|[^a-z])(?:en|eng|english)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "English"

            Regex("""(?:^|[^a-z])(?:de|ger|german)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Deutsch"

            Regex("""(?:^|[^a-z])(?:fr|fre|french)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Français"

            else -> "Altyazı"
        }
    }

    private data class EncryptedConfig(
        val ciphertext: String,
        val iv: String,
        val salt: String,
    )

    private fun extractEncryptedConfig(
        raw: String,
    ): EncryptedConfig? {
        val text = raw
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .decodeEscapes()

        val ciphertext = Regex(
            """"ciphertext"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val iv = Regex(
            """"iv"\s*:\s*"([0-9a-fA-F]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val salt = Regex(
            """"salt"\s*:\s*"([0-9a-fA-F]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return EncryptedConfig(
            ciphertext = ciphertext,
            iv = iv,
            salt = salt,
        )
    }

    private fun decryptDizipalConfig(
        config: EncryptedConfig,
    ): String {
        val passphrase =
            "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

        return runCatching {
            val salt = hexToBytes(config.salt)
            val iv = hexToBytes(config.iv)
            val ciphertext = Base64.decode(
                config.ciphertext,
                Base64.DEFAULT,
            )

            val factory = SecretKeyFactory.getInstance(
                "PBKDF2WithHmacSHA512"
            )

            val keySpec = PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                999,
                256,
            )

            val key = factory
                .generateSecret(keySpec)
                .encoded

            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )

            String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8,
            )
                .replace("\\/", "/")
                .trim()
        }.onFailure {
            Log.e(
                "DiziPal",
                "Decrypt failed: " + it.message,
            )
        }.getOrDefault("")
    }

    private fun hexToBytes(
        value: String,
    ): ByteArray {
        val clean = value.trim()

        if (clean.length % 2 != 0) {
            throw IllegalArgumentException("Invalid hex string")
        }

        return ByteArray(clean.length / 2) { index ->
            clean.substring(
                index * 2,
                index * 2 + 2,
            ).toInt(16).toByte()
        }
    }

    private fun normalizeUrl(
        raw: String?,
        base: String,
    ): String {
        var url = raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim('"', '\'')

        return when {
            url.startsWith("//") ->
                "https:" + url

            url.startsWith("http://", true) ||
                url.startsWith("https://", true) ->
                url

            url.startsWith("/") ->
                (originOf(base) ?: mainUrl) + url

            url.isBlank() ->
                ""

            else ->
                runCatching {
                    URI(base).resolve(url).toString()
                }.getOrElse {
                    mainUrl + "/" + url.removePrefix("./")
                }
        }
    }

    private fun cleanUrl(
        raw: String,
    ): String {
        return raw
            .trim()
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', ')', ']', '}', ',', ';')
    }

    private fun String.decodeEscapes(): String {
        return this
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
    }

    private fun decodeJsonText(
        value: String,
    ): String {
        return value
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u00fc", "ü")
            .replace("\\u00e7", "ç")
            .replace("\\u011f", "ğ")
            .replace("\\u015f", "ş")
            .decodeEscapes()
    }

    private fun originOf(
        url: String,
    ): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            scheme + "://" + host
        }.getOrNull()
    }
}
