package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class AsyaFilmIzle : MainAPI() {
    override var mainUrl = "https://asyafilmizle.com"
    override var name = "AsyaFilmİzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "https://asyafilmizle.com/diziler/" to "Yeni Diziler",
        "https://asyafilmizle.com/tur/kore/" to "Kore Dizileri",
        "https://asyafilmizle.com/tur/aksiyon/" to "Aksiyon",
        "https://asyafilmizle.com/tur/bilim-kurgu/" to "Bilim Kurgu",
        "https://asyafilmizle.com/tur/fantastik/" to "Fantastik",
        "https://asyafilmizle.com/tur/gerilim/" to "Gerilim",
        "https://asyafilmizle.com/tur/komedi/" to "Komedi",
        "https://asyafilmizle.com/tur/romantik/" to "Romantik"
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    private fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val value = url.trim()
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl + value
            else -> "$mainUrl/$value"
        }
    }

    private fun posterFrom(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return absolute(
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("src") }
        )
    }

    private fun scoreFrom(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        val value = Regex(
            """(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\d)"""
        ).findAll(text)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }
            ?: return null
        return Score.from10(value.toString())
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text)?.value?.toIntOrNull()
    }

    private fun qualityFrom(url: String): Int {
        return when {
            Regex("""(?i)(2160|4k)""").containsMatchIn(url) -> Qualities.P2160.value
            Regex("""(?i)1080""").containsMatchIn(url) -> Qualities.P1080.value
            Regex("""(?i)720""").containsMatchIn(url) -> Qualities.P720.value
            Regex("""(?i)480""").containsMatchIn(url) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractEpisodeNumbers(text: String, url: String): Pair<Int, Int>? {
        val source = "$text $url"
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("\\n", " ")
            .trim()

        val patterns = listOf(
            Regex("""(?ix)(?:sezon|season)\s*[._-]?\s*(\d+)\D{0,30}?(?:bölüm|bolum|episode)\s*[._-]?\s*(\d+)"""),
            Regex("""(?ix)(?:bölüm|bolum|episode)\s*[._-]?\s*(\d+)\D{0,30}?(?:sezon|season)\s*[._-]?\s*(\d+)"""),
            Regex("""(?ix)\bs\s*(\d+)\s*[-_.]?\s*e\s*(\d+)\b"""),
            Regex("""(?ix)\b(\d+)\s*[-_.]?\s*(?:sezon|season)\D{0,30}?(?:bölüm|bolum|episode)\s*(\d+)\b""")
        )

        for (regex in patterns) {
            val match = regex.find(source) ?: continue
            val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val second = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue

            return if (regex == patterns[1]) {
                second to first
            } else {
                first to second
            }
        }

        return null
    }

    private fun parseSearchCard(card: Element): SearchResponse? {
        val link = card.selectFirst("a[href*='/dizi/'], a[href*='/film/']")
            ?: card.selectFirst("a[href]")
            ?: return null

        val url = absolute(link.attr("href")) ?: return null
        if (!url.contains("/dizi/", true) && !url.contains("/film/", true)) return null

        val title = link.selectFirst("img")?.attr("alt")
            ?.trim()
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("h1,h2,h3,h4,.title,.name")?.text()?.trim()
            ?: link.text().trim()

        if (title.isBlank()) return null

        val poster = posterFrom(card)
        val score = scoreFrom(card.text())
        return if (url.contains("/film/", true)) {
            newMovieSearchResponse(title, url) {
                posterUrl = poster
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, url) {
                posterUrl = poster
                this.score = score
            }
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { link ->
            val href = absolute(link.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/", true) && !href.contains("/film/", true)) return@forEach
            if (!seen.add(href)) return@forEach

            val title = link.selectFirst("img")?.attr("alt")
                ?.trim()
                ?.removeSuffix(" izle")
                ?.removeSuffix(" İzle")
                ?.takeIf { it.isNotBlank() }
                ?: link.selectFirst(".title,.name,h1,h2,h3,h4")?.text()?.trim()
                ?: link.text().trim()

            if (title.isBlank()) return@forEach

            val card = link.closest("article, .item, .post, .card, .film, .dizi, li, div") ?: link
            val poster = posterFrom(card)
            val score = scoreFrom(card.text())

            results += if (href.contains("/film/", true)) {
                newMovieSearchResponse(title, href) {
                    posterUrl = poster
                    this.score = score
                }
            } else {
                newTvSeriesSearchResponse(title, href) {
                    posterUrl = poster
                    this.score = score
                }
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> "${request.data}page/$page/"
            else -> "${request.data}/page/$page/"
        }

        val response = runCatching {
            app.get(pageUrl, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null
        val results = parseListing(response.document)
        return newHomePageResponse(request, results, hasNext = results.isNotEmpty())
    }

    private suspend fun searchPage(candidate: String): List<SearchResponse> {
        val response = runCatching {
            app.get(candidate, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return emptyList()

        if (!response.isSuccessful) return emptyList()
        return parseListing(response.document)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null

        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/ara/?q=$encoded",
            "$mainUrl/?q=$encoded"
        )

        val seen = HashSet<String>()
        val merged = ArrayList<SearchResponse>()

        for (url in candidates) {
            searchPage(url).forEach { item ->
                if (seen.add(item.url)) merged.add(item)
            }
            if (merged.isNotEmpty()) break
        }

        return newSearchResponseList(merged, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    private fun parseGenres(document: Document): List<String> {
        return document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot {
                it.equals("Kore Dizileri", true) ||
                    it.equals("Çin Dizileri", true) ||
                    it.equals("Japon Dizileri", true) ||
                    it.equals("Tayland Dizileri", true)
            }
    }

    private fun parseTrailer(document: Document): String? {
        val direct = document.select("a").firstOrNull {
            it.text().contains("Fragman", true) ||
                it.attr("href").contains("youtube.com", true) ||
                it.attr("href").contains("youtu.be", true)
        }?.attr("href")?.trim()

        if (!direct.isNullOrBlank() && (direct.startsWith("http", true) || direct.startsWith("//"))) {
            return absolute(direct)
        }

        val dataLink = document.select("[data-trailer], [data-trailer-url], [data-video], [data-url]")
            .flatMap { element ->
                listOf("data-trailer", "data-trailer-url", "data-video", "data-url")
                    .mapNotNull { key -> element.attr(key).takeIf(String::isNotBlank) }
            }
            .firstOrNull()

        return absolute(dataLink)
    }

    private fun collectActors(document: Document): List<String> {
        return document.select("a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun collectDirectors(document: Document): List<String> {
        return document.select("a[href*='/yonetmen/'], a[href*='/director/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
        seriesUrl: String
    ): List<Episode> {
        val result = ArrayList<Episode>()
        val seen = HashSet<String>()

        fun addEpisode(
            rawUrl: String?,
            displayText: String?,
            fallbackTitle: String? = null
        ) {
            val href = absolute(rawUrl) ?: return
            if (!href.contains("/bolum/", true)) return
            if (!seen.add(href)) return

            val text = listOf(displayText, fallbackTitle, href)
                .filterNot { it.isNullOrBlank() }
                .joinToString(" ")

            val numbers = extractEpisodeNumbers(text, href) ?: return
            val season = numbers.first
            val episode = numbers.second

            val runtimeText = displayText.orEmpty()

            result += newEpisode(href) {
                name = "Bölüm $episode"
                this.season = season
                this.episode = episode
                posterUrl = poster
                runTime = getDurationFromString(runtimeText)
            }
        }

        // Normal episode links.
        document.select("a[href*='/bolum/'], a[href*='/episode/']").forEach { link ->
            val href = link.attr("href")
                .ifBlank { link.attr("data-href") }
                .ifBlank { link.attr("data-url") }
                .ifBlank { link.attr("data-src") }

            val context = buildString {
                append(link.text())
                append(" ")
                append(link.attr("title"))
                append(" ")
                append(link.attr("aria-label"))

                val card = link.closest(
                    "article, li, .item, .episode, .episode-item, .post, .card, div"
                )

                if (card != null) {
                    append(" ")
                    append(card.text())
                }
            }

            addEpisode(href, context)
        }

        // Some versions place the episode URL in data attributes instead of href.
        document.select("[data-href], [data-url], [data-src]").forEach { element ->
            val candidates = listOf(
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-src")
            )

            candidates.forEach { raw ->
                if (raw.contains("/bolum/", true)) {
                    addEpisode(raw, element.text() + " " + element.attr("title"))
                }
            }
        }

        // Scan the complete HTML for hidden episode URLs.
        val html = document.html()

        Regex(
            """(?i)(?:https?:)?//[^"']*?/bolum/[^"'\s<>]+|/bolum/[^"'\s<>]+"""
        ).findAll(html).forEach { match ->
            val raw = match.value
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .trimEnd('\\', '"', '\'', '>', '<')

            addEpisode(raw, raw)
        }

        /*
         * The site lazy-loads older episodes behind "Daha Fazla".
         * Do not request every missing episode page here. That caused a
         * significant delay when opening a series.
         *
         * The site's episode URL format is stable:
         * /bolum/{series-slug}-{season}-sezon-{episode}-bolum/
         *
         * For single-season series, the page also exposes the total episode
         * count in "Dizi Bilgileri", so missing episodes can be created locally.
         */
        val seriesSlug = seriesUrl
            .trimEnd('/')
            .substringAfterLast("/dizi/")
            .substringBefore('?')
            .trim('/')

        if (seriesSlug.isNotBlank()) {
            val pageText = document.text()

            val totalEpisodes = Regex(
                "(?i)Bölümler\\s+(\\d+)"
            ).find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val detectedSeasons = Regex(
                "(?i)Sezon\\s+(\\d+)"
            ).findAll(pageText)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..20 }
                .toSortedSet()

            val visibleBySeason = result
                .mapNotNull { episode ->
                    val season = episode.season
                    val number = episode.episode
                    if (season != null && number != null) {
                        season to number
                    } else {
                        null
                    }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.toSet() }

            fun addGeneratedEpisode(season: Int, episode: Int) {
                if (season <= 0 || episode <= 0) return
                if (result.any {
                        it.season == season && it.episode == episode
                    }
                ) return

                val candidate =
                    "$mainUrl/bolum/$seriesSlug-$season-sezon-$episode-bolum/"

                result += newEpisode(candidate) {
                    name = "Bölüm $episode"
                    this.season = season
                    this.episode = episode
                    posterUrl = poster
                }
            }

            // Single-season series: fill the complete 1..N range instantly.
            if (detectedSeasons.size <= 1) {
                val season = detectedSeasons.firstOrNull()
                    ?: visibleBySeason.keys.firstOrNull()
                    ?: 1

                val maxKnown = visibleBySeason[season]?.maxOrNull() ?: 0
                val count = totalEpisodes ?: maxKnown

                if (count > 0) {
                    for (episode in 1..minOf(count, 100)) {
                        addGeneratedEpisode(season, episode)
                    }
                }
            } else {
                // For every season already present, fill gaps up to its
                // currently visible highest episode without network requests.
                for ((season, numbers) in visibleBySeason) {
                    val maxKnown = numbers.maxOrNull() ?: 0

                    for (episode in 1..minOf(maxKnown, 100)) {
                        addGeneratedEpisode(season, episode)
                    }
                }

                /*
                 * Current AsyaFilmIzle multi-season pages expose the latest
                 * season first. With exactly two seasons, the total episode
                 * count allows the hidden first season count to be recovered:
                 * total = hidden season + visible season.
                 */
                if (detectedSeasons.size == 2 && totalEpisodes != null) {
                    val visibleSeason = visibleBySeason.keys.maxOrNull()
                    val hiddenSeason = detectedSeasons.firstOrNull {
                        it != visibleSeason
                    }

                    if (visibleSeason != null && hiddenSeason != null) {
                        val visibleCount =
                            visibleBySeason[visibleSeason]?.maxOrNull() ?: 0

                        val hiddenCount = (totalEpisodes - visibleCount)
                            .coerceAtLeast(0)

                        for (episode in 1..minOf(hiddenCount, 100)) {
                            addGeneratedEpisode(hiddenSeason, episode)
                        }
                    }
                }
            }
        }
        return result
            .distinctBy { "${it.season ?: 0}-${it.episode ?: 0}-${it.data}" }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?: return null

        val pageText = document.text()
        val poster = document.select("img[src], img[data-src], img[data-lazy-src], img[data-original]")
            .mapNotNull {
                absolute(
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-lazy-src") }
                        .ifBlank { it.attr("data-original") }
                )
            }
            .firstOrNull { it.contains("image.tmdb.org", true) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let(::absolute)

        val year = yearFrom(document.selectFirst("h1")?.parent()?.parent()?.text() ?: pageText)
        val score = scoreFrom(pageText)
        val plot = document.selectFirst(
            "meta[property='og:description'], .description, .plot, .summary"
        )?.let {
            it.attr("content").takeIf(String::isNotBlank) ?: it.text()
        }?.trim()

        val genres = parseGenres(document)
        val trailer = parseTrailer(document)
        val actors = collectActors(document)
        val directors = collectDirectors(document)

        if (url.contains("/dizi/", true)) {
            val episodes = parseEpisodes(document, poster, url)
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score
                tags = genres
                if (trailer != null) addTrailer(trailer)
                addActors(actors)

                if (directors.isNotEmpty()) {
                    this.plot = buildString {
                        if (!plot.isNullOrBlank()) append(plot)
                        if (isNotEmpty()) append("\n\n")
                        append("Yönetmen: ")
                        append(directors.joinToString(", "))
                    }
                }
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            this.duration = getDurationFromString(pageText)
            tags = genres
            if (trailer != null) addTrailer(trailer)
            addActors(actors)

            if (directors.isNotEmpty()) {
                this.plot = buildString {
                    if (!plot.isNullOrBlank()) append(plot)
                    if (isNotEmpty()) append("\n\n")
                    append("Yönetmen: ")
                    append(directors.joinToString(", "))
                }
            }
        }
    }

    private fun normalizeUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&")
            .trim()
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".m3u") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.contains("/stream/") ||
            lower.contains("/video/") && lower.contains("master") ||
            lower.contains("/master.txt")
    }

    private fun mediaUrls(text: String): List<String> {
        val input = normalizeUrl(text)
        val result = LinkedHashSet<String>()

        val patterns = listOf(
            Regex("""https?://[^\"'\\s<>]+?\.m3u8(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?\.mp4(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?\.m3u(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?/hls(?:2)?/[^\"'\\s<>]+(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?/master\.txt(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""[\"'](https?://[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(input).forEach { match ->
                val url = normalizeUrl(match.groupValues.last())
                if (looksLikeMedia(url)) result.add(url)
            }
        }

        return result.toList()
    }

    private fun extractQuotedUrls(text: String): List<String> {
        val result = LinkedHashSet<String>()

        // Keep these patterns deliberately simple. Android's ICU regex engine can
        // reject some complex Java regex constructs; a regex failure must never
        // abort loadLinks().
        val patterns = listOf(
            Regex("""(?i)(?:file|src|source|url|hls|stream|playlist|video|streamUrl|playUrl)\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""(?i)["'](?:file|src|source|url|hls|stream|playlist|video|streamUrl|playUrl)["']\s*:\s*["']([^"']+)["']"""),
            Regex("""(?i)(?:fetch|\$\.(?:get|ajax)|axios\.get|XMLHttpRequest\.open)\s*\(\s*["']([^"']+)["']"""),
            Regex("""(?i)https?://[^"'<>\s]+(?:\.m3u8|\.mp4|\.m3u)(?:\?[^"'<>\s]*)?"""),
            Regex("""(?i)https?://[^"'<>\s]+/(?:hls|hls2)/[^"'<>\s]+"""),
            Regex("""(?i)https?://[^"'<>\s]+/master\.txt(?:\?[^"'<>\s]*)?""")
        )

        for (pattern in patterns) {
            runCatching {
                pattern.findAll(text).forEach { match ->
                    val candidate = normalizeUrl(match.groupValues.last())
                    if (
                        candidate.startsWith("http://") ||
                        candidate.startsWith("https://") ||
                        candidate.startsWith("/") ||
                        candidate.startsWith("//")
                    ) {
                        result.add(candidate)
                    }
                }
            }
        }

        return result.toList()
    }

    private fun decodeBase64Candidates(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val tokenRegex = Regex("""(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{40,}={0,2})(?![A-Za-z0-9+/=])""")

        tokenRegex.findAll(text).forEach { match ->
            val token = match.groupValues[1]
            runCatching {
                val decoded = normalizeUrl(base64Decode(token))
                mediaUrls(decoded).forEach(result::add)
                extractQuotedUrls(decoded).forEach(result::add)
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    result.add(decoded)
                }
            }
        }

        return result.toList()
    }

    private fun decodeUrlCandidates(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val encodedRegex = Regex("""(?:https?%3A%2F%2F|https?://)[^\"'\\s<>]{20,1000}""", RegexOption.IGNORE_CASE)

        encodedRegex.findAll(text).forEach { match ->
            runCatching {
                val decoded = normalizeUrl(URLDecoder.decode(match.value, "UTF-8"))
                if (looksLikeMedia(decoded)) result.add(decoded)
            }
        }

        return result.toList()
    }

    private fun absoluteFor(base: String, value: String): String? {
        val cleaned = normalizeUrl(value)
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) return cleaned
        if (cleaned.startsWith("//")) return "https:$cleaned"

        return runCatching { URI(base).resolve(cleaned).toString() }.getOrNull()
    }

    private fun iframeHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to chromeUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
    }

    private fun playerHeaders(referer: String): Map<String, String> {
        val origin = runCatching {
            val uri = URI(referer)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)

        return mapOf(
            "User-Agent" to chromeUserAgent,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    private suspend fun addDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaUrl = normalizeUrl(url)
        if (!looksLikeMedia(mediaUrl)) return

        callback(
            newExtractorLink(
                source = "AsyaFilmİzle",
                name = when {
                    mediaUrl.contains(".m3u8", true) ||
                        mediaUrl.contains(".m3u", true) ||
                        mediaUrl.contains("/hls/", true) ||
                        mediaUrl.contains("/hls2/", true) ||
                        mediaUrl.contains("/master.txt", true) -> "Katre HLS"
                    else -> "Katre MP4"
                },
                url = mediaUrl,
                type = if (
                    mediaUrl.contains(".m3u8", true) ||
                    mediaUrl.contains(".m3u", true) ||
                    mediaUrl.contains("/hls/", true) ||
                    mediaUrl.contains("/hls2/", true) ||
                    mediaUrl.contains("/master.txt", true)
                ) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                quality = qualityFrom(mediaUrl)
                headers = playerHeaders(referer)
            }
        )
    }

    private fun looksLikeSubtitle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".vtt") ||
            lower.contains(".srt") ||
            lower.contains(".ass") ||
            lower.contains(".ssa") ||
            lower.contains(".ttml") ||
            lower.contains(".dfxp") ||
            lower.contains("webvtt") ||
            lower.contains("/subtitle") ||
            lower.contains("/subtitles") ||
            lower.contains("/caption") ||
            lower.contains("/captions")
    }

    private fun subtitleLanguage(value: String): String {
        val lower = value.lowercase()
        return when {
            lower.contains("turk") ||
                lower.contains("türk") ||
                lower.contains("turkish") ||
                lower.contains("turkce") ||
                lower.contains("tr-") ||
                lower.contains("_tr") -> "Türkçe"
            lower.contains("english") ||
                lower.contains("ingiliz") ||
                lower.contains("en-") ||
                lower.contains("_en") -> "English"
            else -> "Türkçe"
        }
    }

    private fun subtitleUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        val input = normalizeUrl(text)

        fun addCandidate(raw: String?, label: String = "Türkçe") {
            if (raw.isNullOrBlank()) return
            val cleaned = normalizeUrl(raw.trim())
                .trimEnd('\\', '"', '\'', '>', '<', ',', ';')
            val absolute = absoluteFor(baseUrl, cleaned) ?: return
            if (!looksLikeSubtitle(absolute)) return
            result.putIfAbsent(absolute, subtitleLanguage(label))
        }

        val directRegex = Regex(
            """https?://[^"'<>\s]+(?:\.vtt|\.srt|\.ass|\.ssa|\.ttml|\.dfxp|\.webvtt)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        )
        directRegex.findAll(input).forEach { match ->
            addCandidate(match.value)
        }

        val fieldRegex = Regex(
            """(?i)(?:subtitle|subtitles|caption|captions|subtitleUrl|subtitle_url|captionUrl|caption_url|track|file)\s*[:=]\s*["']([^"']+)["']"""
        )
        fieldRegex.findAll(input).forEach { match ->
            val label = match.groupValues[0]
            addCandidate(match.groupValues[1], label)
        }

        val quotedRegex = Regex(
            """["']([^"']+(?:\.vtt|\.srt|\.ass|\.ssa|\.ttml|\.dfxp|\.webvtt)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        quotedRegex.findAll(input).forEach { match ->
            addCandidate(match.groupValues[1])
        }

        // HLS master manifests expose subtitle tracks as:
        // #EXT-X-MEDIA:TYPE=SUBTITLES,...,URI="..."
        input.lineSequence()
            .filter { it.contains("TYPE=SUBTITLES", true) }
            .forEach { line ->
                Regex("""(?i)URI\s*=\s*["']([^"']+)["']""")
                    .findAll(line)
                    .forEach { match ->
                        val labelMatch = Regex("""(?i)(?:NAME|LANGUAGE)\s*=\s*["']([^"']+)["']""")
                            .find(line)
                        addCandidate(
                            match.groupValues[1],
                            labelMatch?.groupValues?.getOrNull(1) ?: "Türkçe"
                        )
                    }
            }

        val encodedRegex = Regex(
            """(?:https?%3A%2F%2F|https?://)[^"'<>\s]{10,1200}""",
            RegexOption.IGNORE_CASE
        )
        encodedRegex.findAll(input).forEach { match ->
            runCatching {
                val decoded = URLDecoder.decode(match.value, "UTF-8")
                addCandidate(decoded)
            }
        }

        return result.map { it.key to it.value }
    }

    private suspend fun emitSubtitle(
        rawUrl: String,
        label: String,
        baseUrl: String,
        referer: String,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val url = absoluteFor(baseUrl, rawUrl) ?: return
        if (!looksLikeSubtitle(url)) return
        if (!seen.add(url)) return

        runCatching {
            subtitleCallback(
                newSubtitleFile(
                    subtitleLanguage(label),
                    url
                ) {
                    headers = playerHeaders(referer)
                }
            )
        }
    }

    private suspend fun extractSubtitleFromUrl(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seen: MutableSet<String>
    ) {
        runCatching {
            val uri = URI(iframeUrl)
            val query = uri.rawQuery.orEmpty()

            val names = setOf(
                "sub",
                "subtitle",
                "subtitles",
                "caption",
                "captions",
                "vtt",
                "srt",
                "track"
            )

            query.split("&").forEach { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size != 2 || !names.contains(parts[0].lowercase())) return@forEach

                var decoded = runCatching {
                    URLDecoder.decode(parts[1], "UTF-8")
                }.getOrElse { parts[1] }

                decoded = runCatching {
                    base64Decode(decoded)
                }.getOrElse { decoded }

                emitSubtitle(
                    rawUrl = decoded,
                    label = parts[0],
                    baseUrl = iframeUrl,
                    referer = iframeUrl,
                    seen = seen,
                    subtitleCallback = subtitleCallback
                )
            }
        }
    }

    private suspend fun extractSubtitlesFromManifest(
        manifestUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seen: MutableSet<String>,
        depth: Int = 0
    ) {
        if (depth > 1) return

        val response = runCatching {
            app.get(
                manifestUrl,
                headers = playerHeaders(referer),
                referer = referer,
                allowRedirects = true
            )
        }.getOrNull() ?: return

        if (!response.isSuccessful) return

        val body = response.text

        subtitleUrls(body, manifestUrl).forEach { (url, label) ->
            emitSubtitle(
                rawUrl = url,
                label = label,
                baseUrl = manifestUrl,
                referer = referer,
                seen = seen,
                subtitleCallback = subtitleCallback
            )
        }

        val nestedManifests = mediaUrls(body)
            .filter { it.contains(".m3u8", true) }
            .distinct()
            .take(2)

        for (nested in nestedManifests) {
            val absolute = absoluteFor(manifestUrl, nested) ?: continue
            if (absolute == manifestUrl) continue
            extractSubtitlesFromManifest(
                manifestUrl = absolute,
                referer = referer,
                subtitleCallback = subtitleCallback,
                seen = seen,
                depth = depth + 1
            )
        }
    }

    private fun findIframeUrls(document: Document, baseUrl: String): List<String> {
        val result = LinkedHashSet<String>()

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            val raw = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .ifBlank { iframe.attr("data-lazy-src") }

            absoluteFor(baseUrl, raw)?.let { result.add(it) }
        }

        return result.toList()
    }

    private suspend fun extractFromResponseText(
        baseUrl: String,
        body: String,
        iframeReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        subtitleSeen: MutableSet<String>
    ): Boolean {
        var found = false

        // 1) Subtitle URLs can live directly in HTML/JSON/JS.
        subtitleUrls(body, baseUrl).forEach { (url, label) ->
            emitSubtitle(
                rawUrl = url,
                label = label,
                baseUrl = baseUrl,
                referer = iframeReferer,
                seen = subtitleSeen,
                subtitleCallback = subtitleCallback
            )
        }

        // 2) Base64/URL-encoded player data may contain subtitle URLs.
        val decodedBodies = ArrayList<String>()
        runCatching {
            decodeBase64Candidates(body).forEach { decodedBodies.add(it) }
        }
        decodedBodies.forEach { decoded ->
            subtitleUrls(decoded, baseUrl).forEach { (url, label) ->
                emitSubtitle(
                    rawUrl = url,
                    label = label,
                    baseUrl = baseUrl,
                    referer = iframeReferer,
                    seen = subtitleSeen,
                    subtitleCallback = subtitleCallback
                )
            }
        }

        // 3) Direct media URLs.
        val mediaCandidates = LinkedHashSet<String>()
        mediaCandidates.addAll(mediaUrls(body))
        mediaCandidates.addAll(decodeBase64Candidates(body))
        mediaCandidates.addAll(decodeUrlCandidates(body))
        mediaCandidates.addAll(extractQuotedUrls(body))

        for (raw in mediaCandidates) {
            val url = absoluteFor(baseUrl, raw) ?: continue
            if (!looksLikeMedia(url)) continue

            addDirectMedia(url, iframeReferer, callback)
            found = true
        }

        // 4) Fetch HLS manifests and read EXT-X-MEDIA subtitle tracks.
        mediaCandidates
            .mapNotNull { absoluteFor(baseUrl, it) }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains("/hls/", true) ||
                    it.contains("/hls2/", true) ||
                    it.contains("/master.txt", true)
            }
            .distinct()
            .take(4)
            .forEach { manifest ->
                extractSubtitlesFromManifest(
                    manifestUrl = manifest,
                    referer = iframeReferer,
                    subtitleCallback = subtitleCallback,
                    seen = subtitleSeen
                )
            }

        // 5) Standard HTML5 <track> elements.
        val document = runCatching { org.jsoup.Jsoup.parse(body, baseUrl) }.getOrNull()
        if (document != null) {
            document.select("track[src], source[src], video[src]").forEach { element ->
                val raw = element.attr("src")
                val url = absoluteFor(baseUrl, raw) ?: return@forEach

                if (looksLikeSubtitle(url)) {
                    val label = element.attr("label")
                        .ifBlank { element.attr("srclang") }
                        .ifBlank { "Türkçe" }

                    emitSubtitle(
                        rawUrl = url,
                        label = label,
                        baseUrl = baseUrl,
                        referer = iframeReferer,
                        seen = subtitleSeen,
                        subtitleCallback = subtitleCallback
                    )
                }
            }
        }

        return found
    }

    private suspend fun processIframe(
        iframe: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
        visited: MutableSet<String> = LinkedHashSet(),
        subtitleSeen: MutableSet<String> = LinkedHashSet()
    ): Boolean {
        if (depth > 2) return false
        if (!visited.add(iframe)) return false

        var found = false
        extractSubtitleFromUrl(iframe, subtitleCallback, subtitleSeen)

        // First let installed CloudStream extractors try the player URL.
        runCatching {
            if (loadExtractor(iframe, pageUrl, subtitleCallback, callback)) {
                found = true
            }
        }

        fun responseHeaders(): Map<String, String> = iframeHeaders(pageUrl)

        val iframeResponse = runCatching {
            app.get(
                iframe,
                headers = responseHeaders(),
                referer = pageUrl,
                allowRedirects = true
            )
        }.getOrNull()

        // Some anti-hotlink setups reject the first header profile. Retry without
        // the browser fetch metadata while keeping the real page Referer.
        val finalResponse = if (iframeResponse == null || !iframeResponse.isSuccessful) {
            val fallbackHeaders = listOf(
                mapOf(
                    "User-Agent" to chromeUserAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to pageUrl,
                    "Origin" to runCatching {
                        val uri = URI(pageUrl)
                        "${uri.scheme}://${uri.host}"
                    }.getOrDefault(mainUrl)
                ),
                mapOf(
                    "User-Agent" to chromeUserAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to pageUrl
                )
            )

            fallbackHeaders.firstNotNullOfOrNull { headers ->
                runCatching {
                    app.get(
                        iframe,
                        headers = headers,
                        referer = pageUrl,
                        allowRedirects = true
                    ).takeIf { it.isSuccessful }
                }.getOrNull()
            }
        } else {
            iframeResponse
        }

        if (finalResponse == null) return found

        if (finalResponse.isSuccessful) {
            val body = finalResponse.text

            if (extractFromResponseText(
                    iframe,
                    body,
                    iframe,
                    subtitleCallback,
                    callback,
                    subtitleSeen
                )
            ) {
                found = true
            }

            val document = finalResponse.document

            // Follow nested player iframes, including KSD/Katre style embeds.
            findIframeUrls(document, iframe)
                .filterNot { it == iframe }
                .take(8)
                .forEach { nested ->
                    if (processIframe(
                            nested,
                            iframe,
                            subtitleCallback,
                            callback,
                            depth + 1,
                            visited,
                            subtitleSeen
                        )
                    ) {
                        found = true
                    }
                }

            // Inspect external JS files. A number of Katre versions put the media
            // endpoint in player JavaScript rather than directly in the HTML.
            val scriptUrls = document.select("script[src]")
                .mapNotNull { absoluteFor(iframe, it.attr("src")) }
                .distinct()
                .take(12)

            for (scriptUrl in scriptUrls) {
                val jsResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = iframeHeaders(iframe),
                        referer = iframe,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!jsResponse.isSuccessful) continue

                if (extractFromResponseText(
                        scriptUrl,
                        jsResponse.text,
                        iframe,
                        subtitleCallback,
                        callback,
                        subtitleSeen
                    )
                ) {
                    found = true
                }
            }

            // Follow obvious player API endpoints discovered in the HTML/JS.
            val apiCandidates = extractQuotedUrls(body)
                .mapNotNull { absoluteFor(iframe, it) }
                .filter {
                    !looksLikeMedia(it) &&
                        !it.contains(".js", true) &&
                        (
                            it.contains(".php", true) ||
                                it.contains("/api/", true) ||
                                it.contains("ajax", true) ||
                                it.contains("source", true) ||
                                it.contains("video", true)
                            )
                }
                .distinct()
                .take(8)

            for (api in apiCandidates) {
                val apiResponse = runCatching {
                    app.get(
                        api,
                        headers = iframeHeaders(iframe),
                        referer = iframe,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!apiResponse.isSuccessful) continue

                if (extractFromResponseText(
                        api,
                        apiResponse.text,
                        iframe,
                        subtitleCallback,
                        callback,
                        subtitleSeen
                    )
                ) {
                    found = true
                }
            }
        }

        return found
    }

    private fun isPossiblePlayer(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("embed") ||
            lower.contains("player") ||
            lower.contains("rplayer") ||
            lower.contains("dzembed") ||
            lower.contains("ksdpictures.site") ||
            lower.contains("katre") ||
            lower.contains("yabancidizim.com")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val playerUrls = LinkedHashSet<String>()
        val visited = LinkedHashSet<String>()
        val subtitleSeen = LinkedHashSet<String>()

        fun addPlayer(raw: String?, base: String) {
            if (raw.isNullOrBlank()) return
            absoluteFor(base, raw)?.let { url ->
                val lower = url.lowercase()
                if (
                    lower.startsWith("http://") || lower.startsWith("https://")
                ) {
                    playerUrls.add(url)
                }
            }
        }

        // 1) The episode page itself.
        val pageResponse = runCatching {
            app.get(
                data,
                headers = siteHeaders,
                referer = mainUrl,
                allowRedirects = true
            )
        }.getOrNull()

        if (pageResponse != null && pageResponse.isSuccessful) {
            val document = pageResponse.document
            val html = pageResponse.text

            // Read every iframe, not only iframes whose URL already contains
            // "player/embed". The site can change the iframe filename/host.
            document.select("iframe").forEach { frame ->
                addPlayer(
                    frame.attr("src")
                        .ifBlank { frame.attr("data-src") }
                        .ifBlank { frame.attr("data-lazy-src") }
                        .ifBlank { frame.attr("data-url") }
                        .ifBlank { frame.attr("data-embed") }
                        .ifBlank { frame.attr("data-player") },
                    data
                )
            }

            // Player URLs may also be hidden in data attributes.
            document.select("[data-src], [data-lazy-src], [data-url], [data-embed], [data-player], [data-video]")
                .forEach { element ->
                    listOf(
                        element.attr("data-src"),
                        element.attr("data-lazy-src"),
                        element.attr("data-url"),
                        element.attr("data-embed"),
                        element.attr("data-player"),
                        element.attr("data-video")
                    ).forEach { raw ->
                        addPlayer(raw, data)
                    }
                }

            // Raw HTML fallback. This catches iframe URLs generated by script/JSON.
            val urlRegex = Regex(
                """(?i)(?:https?:)?//[^"'<\s]+?(?:dzembed\.php|embed[^"'\s]*|player[^"'\s]*|/[^"'\s]*player[^"'\s]*)[^"'\s<>]*"""
            )
            urlRegex.findAll(html).forEach { match ->
                addPlayer(match.value, data)
            }

            // Explicitly support the current KSD/Katre style embed.
            Regex(
                """(?i)(?:https?:)?//(?:www\.)?ksdpictures\.site/[^"'<\s]+"""
            ).findAll(html).forEach { match ->
                addPlayer(match.value, data)
            }

            Regex(
                """(?i)(?:https?:)?//[^"'<\s]+/dzembed\.php(?:\?[^"'<\s]+)?"""
            ).findAll(html).forEach { match ->
                addPlayer(match.value, data)
            }

            // Direct media on the episode page, if present.
            if (extractFromResponseText(
                    data,
                    html,
                    data,
                    subtitleCallback,
                    callback,
                    subtitleSeen
                )
            ) {
                found = true
            }

            // Try the episode URL as an extractor source too.
            runCatching {
                if (loadExtractor(data, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // CloudStream can pass a player URL directly as data.
        if (isPossiblePlayer(data) ||
            data.contains("dzembed.php", true) ||
            data.contains("ksdpictures.site", true)
        ) {
            playerUrls.add(data)
        }

        // De-duplicate and process every candidate. Do not require isPossiblePlayer()
        // here because the domain/filename can change independently.
        for (playerUrl in playerUrls) {
            if (!visited.add(playerUrl)) continue

            extractSubtitleFromUrl(playerUrl, subtitleCallback, subtitleSeen)

            // Native CloudStream extractor first.
            runCatching {
                if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }

            // Then our own Katre/KSD extraction.
            if (processIframe(
                    iframe = playerUrl,
                    pageUrl = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    depth = 0,
                    visited = LinkedHashSet(),
                    subtitleSeen = subtitleSeen
                )
            ) {
                found = true
            }
        }

        return found
    }}
