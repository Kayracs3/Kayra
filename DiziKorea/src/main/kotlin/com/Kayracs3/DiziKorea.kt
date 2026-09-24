package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * DiziKorea provider
 *
 * https://dizikorea3.com/
 *
 * Destek:
 * - Dizi
 * - Film
 * - Sezon / Bölüm
 * - Vidmoly
 * - Filemoon
 * - Vmbox
 * - Dplayer
 * - M3U8
 * - DASH
 * - Altyazılar
 */
class DiziKorea : MainAPI() {

    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    private data class Candidate(
        val url: String,
        val referer: String,
    )

    // =========================================================
    // ANA SAYFALAR
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/kore-dizileri-izle-dq" to "Kore Dizileri",
        "$mainUrl/cin-dizileri" to "Çin Dizileri",
        "$mainUrl/japon-dizileri" to "Japon Dizileri",
        "$mainUrl/tayland-dizileri" to "Tayland Dizileri",
        "$mainUrl/tayvan-dizileri" to "Tayvan Dizileri",
        "$mainUrl/filipin-dizileri" to "Filipin Dizileri",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/dizi-arsivi" to "Dizi Arşivi",
    )

    // =========================================================
    // ANA SAYFA / PAGINATION
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {

        val url = withPage(
            request.data,
            page
        )

        val document = app.get(
            url,
            headers = requestHeaders,
        ).document

        /*
         * DiziKorea'da pagination:
         *
         * /kore-dizileri-izle-dq1
         * /kore-dizileri-izle-dq1/sayfa/2
         * /kore-dizileri-izle-dq1/sayfa/3
         *
         * şeklinde.
         *
         * Bu nedenle sayfadaki tüm A etiketlerini doğrudan
         * almak yerine pagination bağlantısının bulunduğu
         * ana içerik bloğunu buluyoruz.
         */
        val results = extractPaginatedResults(
            document
        )

        /*
         * Artık:
         *
         * results.isNotEmpty()
         *
         * kullanmıyoruz.
         *
         * Gerçek "Sonraki" bağlantısını kontrol ediyoruz.
         */
        val hasNext = hasNextPage(
            document,
            page
        )

        return newHomePageResponse(
            request.name,
            results.distinctBy {
                it.url
            },
            hasNext = hasNext,
        )
    }

    /**
     * Pagination'ın bulunduğu içerik bölümünü bulur.
     *
     * Böylece:
     *
     * - Haftanın Trendleri
     * - Header
     * - Footer
     * - Navigation
     * - Devam Eden / ayrı widget'lar
     *
     * mümkün olduğunca ana sayfalama sonucuna karışmaz.
     */
    private fun extractPaginatedResults(
        document: Document
    ): List<SearchResponse> {

        /*
         * Önce "Sonraki" bağlantısını bul.
         */
        val nextLink = document
            .select("a[href]")
            .firstOrNull { element ->

                val text =
                    element.text()
                        .trim()
                        .lowercase()

                val href =
                    element.attr("href")
                        .trim()
                        .lowercase()

                text.contains("sonraki") ||
                    href.contains("/sayfa/")
            }

        /*
         * Sonraki bağlantının bulunduğu en küçük
         * içerik container'ını bul.
         */
        val container =
            nextLink?.let {
                findPaginationContainer(it)
            }

        /*
         * Container bulunduysa yalnızca onun içindeki
         * dizi/film bağlantılarını al.
         */
        if (container != null) {

            val results = container
                .select("a[href]")
                .mapNotNull {
                    it.toSearchResponse()
                }
                .filter {
                    it.url.isNotBlank()
                }
                .distinctBy {
                    it.url
                }

            if (results.isNotEmpty()) {
                return results
            }
        }

        /*
         * Fallback:
         *
         * Bazı kategori sayfalarında pagination yapısı
         * farklı olabilir.
         *
         * Bu durumda bütün sayfayı tarıyoruz ama
         * toSearchResponse() zaten:
         *
         * - trend
         * - sidebar
         * - navigation
         * - footer
         * - pagination
         * - bölüm linkleri
         *
         * filtreliyor.
         */
        return document
            .select("a[href]")
            .mapNotNull {
                it.toSearchResponse()
            }
            .distinctBy {
                it.url
            }
    }

    /**
     * "Sonraki" pagination bağlantısından yukarı doğru çıkar
     * ve yeterli sayıda dizi/film kartı içeren en küçük
     * parent'ı seçer.
     */
    private fun findPaginationContainer(
        nextLink: Element
    ): Element? {

        var current =
            nextLink.parent()

        var depth = 0

        while (
            current != null &&
            depth < 8
        ) {

            val validCount =
                current
                    .select("a[href]")
                    .count {
                        isMainContentLink(it)
                    }

            /*
             * Sayfadaki kart listesine ulaştık.
             *
             * DiziKorea sayfalarında liste genellikle
             * 20+ kart içerdiği için 8 yeterli.
             */
            if (
                validCount >= 10
            ) {

                return current
            }

            current =
                current.parent()

            depth++
        }

        return null
    }

    /**
     * Bir A etiketinin gerçek içerik kartı olup olmadığını
     * kontrol eder.
     */
    private fun isMainContentLink(
        element: Element
    ): Boolean {

        val href =
            element
                .attr("href")
                .trim()

        if (href.isBlank()) {
            return false
        }

        val path =
            href.lowercase()

        /*
         * Sadece dizi / film.
         */
        if (
            !path.contains("/dizi/") &&
            !path.contains("/film/")
        ) {
            return false
        }

        /*
         * Bölüm bağlantıları yok.
         */
        if (
            path.contains("/sezon-") ||
            path.contains("/bolum-")
        ) {
            return false
        }

        /*
         * Trend.
         */
        if (
            element.hasClass(
                "site-sidebar-trend-item"
            )
        ) {
            return false
        }

        /*
         * Trend parent.
         */
        if (
            element.parents().any {
                it.hasClass(
                    "site-sidebar-trend-list"
                )
            }
        ) {
            return false
        }

        /*
         * Pagination bağlantıları.
         */
        if (
            path.contains("/sayfa/")
        ) {
            return false
        }

        /*
         * Yaygın navigation/sidebar/footer alanları.
         */
        val parentInfo =
            buildString {

                var parent =
                    element.parent()

                var count = 0

                while (
                    parent != null &&
                    count < 8
                ) {

                    append(" ")
                    append(
                        parent.tagName()
                    )
                    append(" ")
                    append(
                        parent.id()
                    )
                    append(" ")
                    append(
                        parent.className()
                    )

                    parent =
                        parent.parent()

                    count++
                }
            }.lowercase()

        val forbiddenAreas = listOf(
            "sidebar",
            "site-sidebar",
            "trend",
            "site-sidebar-trend",
            "footer",
            "header",
            "navbar",
            "navigation",
            "menu",
            "pagination",
            "pager",
        )

        if (
            forbiddenAreas.any {
                parentInfo.contains(it)
            }
        ) {
            return false
        }

        return true
    }

    /**
     * Gerçek "Sonraki" sayfa var mı?
     *
     * Örnek:
     *
     * Sayfa 1:
     * /sayfa/2
     *
     * Sayfa 2:
     * /sayfa/3
     */
    private fun hasNextPage(
        document: Document,
        currentPage: Int,
    ): Boolean {

        val expectedPath =
            "/sayfa/${currentPage + 1}"

        return document
            .select("a[href]")
            .any { element ->

                val href =
                    element
                        .attr("href")
                        .trim()

                val text =
                    element
                        .text()
                        .trim()
                        .lowercase()

                href.contains(
                    expectedPath,
                    ignoreCase = true
                ) ||
                    text.contains(
                        "sonraki"
                    )
            }
    }

    // =========================================================
    // ARAMA
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded =
            URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val candidates = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?query=$encoded",
            "$mainUrl/ara?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
        )

        for (url in candidates) {

            val document =
                runCatching {

                    app.get(
                        url,
                        headers = requestHeaders,
                    ).document

                }.getOrNull()
                    ?: continue

            val results =
                document
                    .select("a[href]")
                    .mapNotNull {
                        it.toSearchResponse()
                    }
                    .distinctBy {
                        it.url
                    }

            if (
                results.isNotEmpty()
            ) {
                return results
            }
        }

        return emptyList()
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url,
                headers = requestHeaders,
            ).document

        val normalized =
            url.lowercase()

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        val title =
            document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        "meta[property='og:title']"
                    )
                    ?.attr("content")
                    ?.trim()
                ?: return null

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        val poster =
            when {

                "/dizi/" in normalized -> {
                    findSeriesPoster(
                        url
                    )
                }

                else -> {
                    findMoviePoster(
                        document = document,
                        title = title,
                    )
                }
            }

        // -----------------------------------------------------
        // PLOT
        // -----------------------------------------------------

        val plot =
            document
                .selectFirst(
                    "meta[name='description']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        ".description, .plot, article p, main p"
                    )
                    ?.text()
                    ?.trim()

        // -----------------------------------------------------
        // YEAR
        // -----------------------------------------------------

        val text =
            document.text()

        val year =
            Regex(
                "\\b(?:19|20)\\d{2}\\b"
            )
                .find(text)
                ?.value
                ?.toIntOrNull()

        // -----------------------------------------------------
        // SCORE
        // -----------------------------------------------------

        val score =
            Regex(
                "(?i)(?:IMDb|IMDB|puan)\\s*[★:]?\\s*([0-9]+(?:[.,][0-9]+)?)"
            )
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    ',',
                    '.'
                )
                ?.toDoubleOrNull()

        // =====================================================
        // FILM
        // =====================================================

        if (
            "/film/" in normalized
        ) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url,
            ) {

                posterUrl =
                    poster

                this.plot =
                    plot

                this.year =
                    year

                score?.let {
                    this.score =
                        Score.from10(it)
                }
            }
        }

        // =====================================================
        // DİZİ
        // =====================================================

        if (
            "/dizi/" in normalized
        ) {

            val episodes =
                parseEpisodes(
                    document = document,
                    seriesPoster = poster,
                )

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
            ) {

                posterUrl =
                    poster

                this.plot =
                    plot

                this.year =
                    year

                score?.let {
                    this.score =
                        Score.from10(it)
                }
            }
        }

        return null
    }

    // =========================================================
    // DİZİ POSTER
    // =========================================================

    private suspend fun findSeriesPoster(
        pageUrl: String,
    ): String? {

        var slug =
            pageUrl
                .substringAfter(
                    "/dizi/",
                    ""
                )
                .substringBefore("?")
                .substringBefore("#")
                .trim()
                .lowercase()

        if (
            slug.isBlank()
        ) {
            return null
        }

        /*
         * Örnek:
         *
         * the-hidden-shadow-izle
         * =>
         * the-hidden-shadow
         *
         * my-bias-my-boss-izle-dq6
         * =>
         * my-bias-my-boss
         */
        slug =
            slug.replace(
                Regex(
                    "(?i)-izle(?:-dq\\d+)?$"
                ),
                ""
            )

        if (
            slug.isBlank()
        ) {
            return null
        }

        val extensions =
            listOf(
                "webp",
                "jpg",
                "jpeg",
                "png",
            )

        for (
            extension in extensions
        ) {

            val posterUrl =
                "$mainUrl/assets/uploads/series/$slug.$extension"

            val found =
                runCatching {

                    val response =
                        app.get(
                            posterUrl,
                            headers = requestHeaders,
                        )

                    response.code in 200..299

                }.getOrDefault(
                    false
                )

            if (
                found
            ) {
                return posterUrl
            }
        }

        return null
    }

    // =========================================================
    // FİLM POSTER
    // =========================================================

    private fun findMoviePoster(
        document: Document,
        title: String,
    ): String? {

        val normalizedTitle =
            normalizePosterText(
                title
            )

        // -----------------------------------------------------
        // ALT
        // -----------------------------------------------------

        val altPoster =
            document
                .select("img[alt]")
                .asSequence()
                .firstOrNull { img ->

                    val alt =
                        normalizePosterText(
                            img.attr("alt")
                        )

                    alt.isNotBlank() &&
                        (
                            alt == normalizedTitle ||
                                alt.contains(
                                    normalizedTitle
                                ) ||
                                normalizedTitle.contains(
                                    alt
                                )
                            )
                }
                ?.let {
                    posterSource(it)
                }
                ?.takeIf {
                    isValidPosterUrl(it)
                }

        if (
            altPoster != null
        ) {

            return fixUrl(
                altPoster
            )
        }

        // -----------------------------------------------------
        // OG
        // -----------------------------------------------------

        val ogImage =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    isValidPosterUrl(it)
                }

        if (
            ogImage != null
        ) {

            return fixUrl(
                ogImage
            )
        }

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        val titlePoster =
            document
                .select("img[title]")
                .asSequence()
                .firstOrNull { img ->

                    val imageTitle =
                        normalizePosterText(
                            img.attr("title")
                        )

                    imageTitle.isNotBlank() &&
                        (
                            imageTitle == normalizedTitle ||
                                imageTitle.contains(
                                    normalizedTitle
                                ) ||
                                normalizedTitle.contains(
                                    imageTitle
                                )
                            )
                }
                ?.let {
                    posterSource(it)
                }
                ?.takeIf {
                    isValidPosterUrl(it)
                }

        if (
            titlePoster != null
        ) {

            return fixUrl(
                titlePoster
            )
        }

        // -----------------------------------------------------
        // DETAY
        // -----------------------------------------------------

        val selectors =
            listOf(
                "main img",
                "article img",
                ".detail img",
                ".details img",
                ".movie-detail img",
                ".poster img",
                ".cover img",
            )

        for (
            selector in selectors
        ) {

            val poster =
                document
                    .select(selector)
                    .asSequence()
                    .mapNotNull {
                        posterSource(it)
                    }
                    .firstOrNull {
                        isValidPosterUrl(it)
                    }

            if (
                poster != null
            ) {

                return fixUrl(
                    poster
                )
            }
        }

        return null
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        val candidates =
            LinkedHashMap<String, Candidate>()

        val scannedPages =
            HashSet<String>()

        // -----------------------------------------------------
        // CANDIDATE
        // -----------------------------------------------------

        fun addCandidate(
            rawUrl: String?,
            referer: String,
        ) {

            if (
                rawUrl.isNullOrBlank()
            ) {
                return
            }

            var url =
                decodeEmbeddedText(
                    rawUrl.trim()
                )

            url =
                url
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "\\u002F",
                        "/",
                        ignoreCase = true
                    )
                    .replace(
                        "\\u003A",
                        ":",
                        ignoreCase = true
                    )
                    .replace(
                        "\\u0026",
                        "&",
                        ignoreCase = true
                    )

            if (
                url.startsWith("//")
            ) {

                url =
                    "https:$url"

            } else if (
                url.startsWith("/")
            ) {

                url =
                    fixUrl(url)
            }

            if (
                !url.startsWith(
                    "http://"
                ) &&
                !url.startsWith(
                    "https://"
                )
            ) {
                return
            }

            url =
                url.trimEnd(
                    ')',
                    ']',
                    ';',
                    ',',
                    '"',
                    '\''
                )

            if (
                url.isBlank()
            ) {
                return
            }

            candidates.putIfAbsent(
                url,
                Candidate(
                    url = url,
                    referer = referer,
                )
            )
        }

        // -----------------------------------------------------
        // SCAN HTML
        // -----------------------------------------------------

        fun scanHtml(
            html: String,
            referer: String,
        ) {

            var decoded =
                decodeEmbeddedText(
                    html
                )

            decoded =
                decoded.replace(
                    Regex(
                        """\\u([0-9a-fA-F]{4})"""
                    )
                ) { match ->

                    runCatching {

                        match
                            .groupValues[1]
                            .toInt(16)
                            .toChar()
                            .toString()

                    }.getOrDefault(
                        match.value
                    )
                }

            decoded =
                decoded.replace(
                    Regex(
                        """\\x([0-9a-fA-F]{2})"""
                    )
                ) { match ->

                    runCatching {

                        match
                            .groupValues[1]
                            .toInt(16)
                            .toChar()
                            .toString()

                    }.getOrDefault(
                        match.value
                    )
                }

            Regex(
                """https?://[^\s"'<>\\]+"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            Regex(
                """(?i)https?://[a-z0-9.-]+\.vmbox\.space[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            Regex(
                """(?i)https?://[a-z0-9.-]*dplayer82\.site[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            Regex(
                """(?i)https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            Regex(
                """(?i)https?://[^\s"'<>\\]+/master\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }
        }

        // -----------------------------------------------------
        // SCAN ELEMENT
        // -----------------------------------------------------

        fun scanElement(
            element: Element,
            referer: String,
        ) {

            val attributes =
                listOf(
                    "href",
                    "src",
                    "data-src",
                    "data-url",
                    "data-href",
                    "data-link",
                    "data-video",
                    "data-iframe",
                    "data-embed",
                    "data-player",
                    "data-file",
                    "data-stream",
                    "data-source",
                    "onclick",
                )

            for (
                attribute in attributes
            ) {

                val value =
                    element
                        .attr(attribute)
                        .trim()

                if (
                    value.isBlank()
                ) {
                    continue
                }

                addCandidate(
                    value,
                    referer
                )

                Regex(
                    """https?://[^\s"'<>\\]+"""
                )
                    .findAll(
                        decodeEmbeddedText(
                            value
                        )
                    )
                    .forEach {

                        addCandidate(
                            it.value,
                            referer
                        )
                    }
            }
        }

        // =====================================================
        // 1. BÖLÜM SAYFASI
        // =====================================================

        val response =
            runCatching {

                app.get(
                    data,
                    headers =
                        requestHeaders +
                            mapOf(
                                "Referer" to mainUrl
                            )
                )

            }.getOrNull()
                ?: return false

        val document =
            response.document

        scanHtml(
            response.text,
            data
        )

        document
            .select(
                "iframe, a, button, video, source, script, " +
                    "template, noscript, " +
                    "[data-url], [data-href], [data-src], " +
                    "[data-video], [data-player], [data-iframe], " +
                    "[data-embed], [data-link], [onclick]"
            )
            .forEach {

                scanElement(
                    it,
                    data
                )
            }

        document
            .select(
                "script, template, noscript"
            )
            .forEach {

                scanHtml(
                    it.data(),
                    data
                )

                scanHtml(
                    it.html(),
                    data
                )
            }

        // =====================================================
        // 2. PLAYER
        // =====================================================

        val initialCandidates =
            candidates.values.toList()

        for (
            candidate in initialCandidates
        ) {

            val url =
                candidate.url

            val lower =
                url.lowercase()

            if (
                isMediaUrl(url)
            ) {
                continue
            }

            val isPlayer =
                lower.contains(
                    "vidmoly"
                ) ||
                    lower.contains(
                        "filemoon"
                    ) ||
                    lower.contains(
                        "dplayer82.site"
                    ) ||
                    lower.contains(
                        "vmbox.space"
                    )

            if (
                !isPlayer
            ) {
                continue
            }

            if (
                !scannedPages.add(
                    url
                )
            ) {
                continue
            }

            val playerResponse =
                runCatching {

                    app.get(
                        url,
                        headers =
                            requestHeaders +
                                mapOf(
                                    "Referer" to
                                        candidate.referer
                                )
                    )

                }.getOrNull()
                    ?: continue

            scanHtml(
                playerResponse.text,
                url
            )

            val playerDocument =
                playerResponse.document

            playerDocument
                .select(
                    "iframe, video, source, script, " +
                        "[src], [href], " +
                        "[data-src], [data-url], " +
                        "[data-video], " +
                        "[data-player], " +
                        "[data-iframe], " +
                        "[data-embed]"
                )
                .forEach {

                    scanElement(
                        it,
                        url
                    )
                }

            playerDocument
                .select(
                    "script, template, noscript"
                )
                .forEach {

                    scanHtml(
                        it.data(),
                        url
                    )

                    scanHtml(
                        it.html(),
                        url
                    )
                }
        }

        // =====================================================
        // 3. MEDYA
        // =====================================================

        var found =
            false

        for (
            candidate in candidates.values
        ) {

            val url =
                candidate.url

            if (
                !isMediaUrl(url)
            ) {
                continue
            }

            val lower =
                url.lowercase()

            val type =
                when {

                    lower.contains(
                        ".m3u8"
                    ) ||
                        lower.contains(
                            "/master.m3u8"
                        ) ||
                        lower.contains(
                            "/hls/"
                        ) -> {

                        ExtractorLinkType.M3U8
                    }

                    lower.contains(
                        ".mpd"
                    ) -> {

                        ExtractorLinkType.DASH
                    }

                    else -> {

                        ExtractorLinkType.VIDEO
                    }
                }

            val mediaReferer =
                candidate.referer
                    .ifBlank {
                        data
                    }

            callback(
                newExtractorLink(
                    source = name,
                    name = hostLabel(
                        url
                    ),
                    url = url,
                    type = type,
                ) {

                    referer =
                        mediaReferer

                    quality =
                        when {

                            lower.contains(
                                "1080"
                            ) ->
                                Qualities.P1080.value

                            lower.contains(
                                "720"
                            ) ->
                                Qualities.P720.value

                            lower.contains(
                                "480"
                            ) ->
                                Qualities.P480.value

                            lower.contains(
                                "360"
                            ) ->
                                Qualities.P360.value

                            else ->
                                Qualities.Unknown.value
                        }

                    headers =
                        mapOf(
                            "User-Agent" to
                                USER_AGENT,
                            "Referer" to
                                mediaReferer,
                            "Accept" to
                                "*/*",
                            "Accept-Language" to
                                "tr-TR,tr;q=0.9,en;q=0.8",
                        )
                }
            )

            found =
                true
        }

        // =====================================================
        // 4. EXTRACTOR
        // =====================================================

        for (
            candidate in candidates.values
        ) {

            val url =
                candidate.url

            val lower =
                url.lowercase()

            if (
                isMediaUrl(url)
            ) {
                continue
            }

            val supportedExtractor =
                lower.contains(
                    "vidmoly"
                ) ||
                    lower.contains(
                        "vidmoly.net"
                    ) ||
                    lower.contains(
                        "vidmoly.to"
                    ) ||
                    lower.contains(
                        "vidmoly.me"
                    ) ||
                    lower.contains(
                        "filemoon"
                    ) ||
                    lower.contains(
                        "filemoon.sx"
                    )

            if (
                !supportedExtractor
            ) {
                continue
            }

            val ok =
                runCatching {

                    loadExtractor(
                        url,
                        candidate.referer,
                        subtitleCallback,
                        callback,
                    )

                }.getOrDefault(
                    false
                )

            if (
                ok
            ) {
                found =
                    true
            }
        }

        // =====================================================
        // 5. ALTYAZI
        // =====================================================

        document
            .select(
                "track[src], track[data-src]"
            )
            .forEach { track ->

                val subtitleUrl =
                    track
                        .attr("src")
                        .ifBlank {
                            track.attr(
                                "data-src"
                            )
                        }
                        .trim()

                if (
                    subtitleUrl.isNotBlank()
                ) {

                    subtitleCallback(
                        newSubtitleFile(
                            track
                                .attr("label")
                                .ifBlank {
                                    "Türkçe"
                                },
                            fixUrl(
                                subtitleUrl
                            ),
                        )
                    )
                }
            }

        return found
    }

    // =========================================================
    // EPISODES
    // =========================================================

    private fun parseEpisodes(
        document: Document,
        seriesPoster: String?,
    ): List<Episode> {

        return document
            .select("a[href]")
            .mapNotNull { element ->

                val href =
                    element
                        .attr("href")
                        .trim()

                val absolute =
                    fixUrlNull(href)
                        ?: return@mapNotNull null

                val match =
                    Regex(
                        "(?i)/sezon-(\\d+)/bolum-(\\d+)"
                    )
                        .find(
                            absolute
                        )
                        ?: return@mapNotNull null

                val season =
                    match
                        .groupValues[1]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val episode =
                    match
                        .groupValues[2]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val label =
                    element
                        .text()
                        .trim()
                        .ifBlank {
                            "$episode. Bölüm"
                        }

                newEpisode(
                    absolute
                ) {

                    name =
                        label

                    this.season =
                        season

                    this.episode =
                        episode

                    /*
                     * Bölümün kendi görselini almıyoruz.
                     * Dizi ana posteri kullanılıyor.
                     */
                    posterUrl =
                        seriesPoster
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 0
                }.thenBy {
                    it.episode ?: 0
                }
            )
    }

    // =========================================================
    // SEARCH RESPONSE
    // =========================================================

    private fun Element.toSearchResponse():
        SearchResponse? {

        /*
         * Önce gerçek içerik bağlantısı kontrolü.
         */
        if (
            !isMainContentLink(this)
        ) {
            return null
        }

        val href =
            attr("href")
                .trim()

        val absolute =
            fixUrlNull(href)
                ?: return null

        val path =
            absolute.lowercase()

        val type =
            when {

                "/film/" in path ->
                    TvType.Movie

                "/dizi/" in path ->
                    TvType.TvSeries

                else ->
                    return null
            }

        /*
         * Başlık.
         */
        val title =
            text()
                .trim()
                .ifBlank {

                    selectFirst("img")
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

        if (
            title.isBlank()
        ) {
            return null
        }

        /*
         * Yalnızca mevcut A elementinin
         * kendi posterini al.
         */
        val poster =
            selectFirst("img")
                ?.let {
                    posterOf(it)
                }

        return when (
            type
        ) {

            TvType.Movie -> {

                newMovieSearchResponse(
                    title,
                    absolute,
                    TvType.Movie,
                ) {

                    posterUrl =
                        poster
                }
            }

            else -> {

                newTvSeriesSearchResponse(
                    title,
                    absolute,
                    TvType.TvSeries,
                ) {

                    posterUrl =
                        poster
                }
            }
        }
    }

    // =========================================================
    // POSTER
    // =========================================================

    private fun posterOf(
        element: Element
    ): String? {

        val img =
            if (
                element.tagName()
                    .equals(
                        "img",
                        ignoreCase = true
                    )
            ) {
                element
            } else {
                element.selectFirst(
                    "img"
                ) ?: return null
            }

        return posterSource(
            img
        )?.let(
            ::fixUrl
        )
    }

    private fun posterSource(
        element: Element
    ): String? {

        val values =
            listOf(
                element.attr(
                    "data-src"
                ),
                element.attr(
                    "data-lazy-src"
                ),
                element.attr(
                    "data-original"
                ),
                element.attr(
                    "data-image"
                ),
                element.attr(
                    "data-poster"
                ),
                element.attr(
                    "data-fallback"
                ),
                element.attr(
                    "src"
                ),
            )

        return values
            .asSequence()
            .map {
                it.trim()
            }
            .firstOrNull {
                isValidPosterUrl(
                    it
                )
            }
    }

    private fun isValidPosterUrl(
        value: String?
    ): Boolean {

        if (
            value.isNullOrBlank()
        ) {
            return false
        }

        val url =
            value.trim()

        if (
            url == "#" ||
            url.equals(
                "about:blank",
                ignoreCase = true
            )
        ) {
            return false
        }

        val lower =
            url.lowercase()

        val forbidden =
            listOf(
                "placeholder",
                "blank.gif",
                "transparent.gif",
                "spacer.gif",
                "pixel.gif",
                "data:image/gif;base64",
            )

        return forbidden.none {
            lower.contains(
                it
            )
        }
    }

    private fun normalizePosterText(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex(
                    """[^a-z0-9çğıöşü]+"""
                ),
                ""
            )
    }

    // =========================================================
    // MEDIA URL
    // =========================================================

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return Regex(
            """(?i)\.(m3u8|mpd|mp4)(?:$|[?#])"""
        )
            .containsMatchIn(
                value
            ) ||

            value.contains(
                ".urlset/master.m3u8"
            ) ||

            value.contains(
                "/master.m3u8"
            ) ||

            (
                value.contains(
                    "/hls/"
                ) &&
                    (
                        value.contains(
                            ".m3u8"
                        ) ||
                            value.contains(
                                "master"
                            )
                        )
                )
    }

    // =========================================================
    // EXTERNAL PLAYER
    // =========================================================

    private fun isExternalPlayer(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(
            "vidmoly"
        ) ||
            value.contains(
                "filemoon"
            ) ||
            value.contains(
                "vidmolyme"
            ) ||
            value.contains(
                "vidmolyto"
            ) ||
            value.contains(
                "vidmolybiz"
            ) ||
            value.contains(
                "vidmoly.net"
            ) ||
            value.contains(
                "dplayer82.site"
            ) ||
            value.contains(
                "vmbox.space"
            )
    }

    // =========================================================
    // EMBEDDED TEXT
    // =========================================================

    private fun decodeEmbeddedText(
        value: String
    ): String {

        return value
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "\\\"",
                "\""
            )
            .replace(
                "\\'",
                "'"
            )
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true
            )
            .replace(
                "\\u003A",
                ":",
                ignoreCase = true
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true
            )
            .replace(
                "&quot;",
                "\"",
                ignoreCase = true
            )
            .replace(
                "&#x2F;",
                "/",
                ignoreCase = true
            )
            .replace(
                "&#47;",
                "/",
                ignoreCase = true
            )
            .replace(
                "\n",
                " "
            )
            .replace(
                "\r",
                " "
            )
    }

    // =========================================================
    // HOST
    // =========================================================

    private fun hostLabel(
        url: String
    ): String {

        return runCatching {
            URI(url).host
        }
            .getOrNull()
            ?.ifBlank {
                "DiziKorea"
            }
            ?: "DiziKorea"
    }

    // =========================================================
    // PAGINATION URL
    // =========================================================

    private fun withPage(
        url: String,
        page: Int
    ): String {

        /*
         * İlk sayfa:
         *
         * /kore-dizileri-izle-dq1
         */

        if (
            page <= 1
        ) {
            return url
        }

        /*
         * Query ve hash'i ayır.
         */
        val hash =
            url
                .substringAfter(
                    "#",
                    ""
                )
                .takeIf {
                    it.isNotBlank()
                }

        val withoutHash =
            url
                .substringBefore(
                    "#"
                )

        val query =
            withoutHash
                .substringAfter(
                    "?",
                    ""
                )
                .takeIf {
                    it.isNotBlank()
                }

        var base =
            withoutHash
                .substringBefore("?")
                .trimEnd('/')

        /*
         * Eğer URL zaten:
         *
         * /sayfa/2
         *
         * şeklindeyse sayfa numarasını değiştir.
         */
        base =
            base.replace(
                Regex(
                    "/sayfa/\\d+$"
                ),
                ""
            )

        /*
         * DiziKorea gerçek pagination:
         *
         * /sayfa/2
         */
        var result =
            "$base/sayfa/$page"

        /*
         * Query varsa koru.
         */
        if (
            query != null
        ) {

            result +=
                "?$query"
        }

        /*
         * Hash varsa koru.
         */
        if (
            hash != null
        ) {

            result +=
                "#$hash"
        }

        return result
    }
}
