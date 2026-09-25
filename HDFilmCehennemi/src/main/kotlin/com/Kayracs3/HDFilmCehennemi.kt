package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes: Set<TvType> = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/load/page/sayfano/home/" to "Yeni Filmler",
        "$mainUrl/load/page/sayfano/home-series/" to "Yeni Diziler",
        "$mainUrl/load/page/sayfano/categories/nette-ilk-filmler/" to "Filmler",
        "$mainUrl/load/page/sayfano/categories/tavsiye-filmler-izle2/" to "Önerilen Filmler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "X-Requested-With" to "fetch",
        "Referer" to "$mainUrl/"
    )

    // =========================================================
    // LOG
    // =========================================================

    private fun hdLog(
        message: String
    ) {
        debugPrint("HDCH") {
            message
        }
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {

            val targetUrl =
                request.data.replace(
                    "sayfano",
                    page.toString()
                )

            hdLog(
                "HDCH D MAIN PAGE URL » $targetUrl"
            )

            val response =
                app.get(
                    targetUrl,
                    headers = requestHeaders,
                    referer = "$mainUrl/"
                )

            val rawText =
                response.text.trim()

            hdLog(
                "HDCH D MAIN PAGE RESPONSE LENGTH » ${rawText.length}"
            )

            if (rawText.isBlank()) {

                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }

            val html =
                try {

                    if (
                        rawText.startsWith("{") &&
                        rawText.endsWith("}")
                    ) {

                        JSONObject(
                            rawText
                        ).optString(
                            "html",
                            ""
                        )

                    } else {

                        rawText
                    }

                } catch (e: Exception) {

                    hdLog(
                        "HDCH D JSON PARSE ERROR » ${e.message}"
                    )

                    rawText
                }

            hdLog(
                "HDCH D MAIN PAGE HTML LENGTH » ${html.length}"
            )

            if (html.isBlank()) {

                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }

            val document =
                Jsoup.parse(html)

            val results =
                mutableListOf<SearchResponse>()

            // =================================================
            // CARDS
            // =================================================

            document
                .select("a[href]")
                .forEach { element ->

                    try {

                        val href =
                            element
                                .attr("href")
                                .trim()

                        if (href.isBlank()) {
                            return@forEach
                        }

                        val fixedHref =
                            fixUrl(href)

                        if (
                            fixedHref == mainUrl ||
                            fixedHref == "$mainUrl/"
                        ) {
                            return@forEach
                        }

                        val image =
                            element.selectFirst("img")

                        val poster =
                            image
                                ?.let {

                                    it.attr("data-src")
                                        .ifBlank {
                                            it.attr("data-lazy-src")
                                        }
                                        .ifBlank {
                                            it.attr("data-original")
                                        }
                                        .ifBlank {
                                            it.attr("data-original-src")
                                        }
                                        .ifBlank {
                                            it.attr("src")
                                        }
                                }
                                ?.let {
                                    fixUrlNull(it)
                                }

                        if (poster.isNullOrBlank()) {
                            return@forEach
                        }

                        val title =
                            element
                                .attr("title")
                                .trim()
                                .ifBlank {

                                    element
                                        .selectFirst(
                                            "h2, h3, h4, h5, .title, .film-title, .movie-title"
                                        )
                                        ?.text()
                                        ?.trim()
                                        ?: ""
                                }
                                .ifBlank {

                                    image
                                        ?.attr("alt")
                                        ?.trim()
                                        ?: ""
                                }

                        if (title.isBlank()) {
                            return@forEach
                        }

                        val lowerHref =
                            fixedHref.lowercase(
                                Locale.ROOT
                            )

                        val isSeries =
                            lowerHref.contains("/dizi/") ||
                            lowerHref.contains("/series/") ||
                            lowerHref.contains("/tv/")

                        val scoreText =
                            element
                                .selectFirst(
                                    "span.imdb, .imdb, .rating, .puan"
                                )
                                ?.text()
                                ?.trim()

                        val score =
                            scoreText
                                ?.replace(",", ".")
                                ?.let {

                                    Regex(
                                        """\d+(?:\.\d+)?"""
                                    )
                                        .find(it)
                                        ?.value
                                        ?.toDoubleOrNull()
                                }

                        if (isSeries) {

                            results +=
                                newTvSeriesSearchResponse(
                                    name = title,
                                    url = fixedHref,
                                    type = TvType.TvSeries,
                                    fix = false
                                ) {

                                    this.posterUrl =
                                        poster

                                    if (score != null) {

                                        this.score =
                                            Score.from(
                                                score,
                                                10
                                            )
                                    }
                                }

                        } else {

                            results +=
                                newMovieSearchResponse(
                                    name = title,
                                    url = fixedHref,
                                    type = TvType.Movie,
                                    fix = false
                                ) {

                                    this.posterUrl =
                                        poster

                                    if (score != null) {

                                        this.score =
                                            Score.from(
                                                score,
                                                10
                                            )
                                    }
                                }
                        }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }

            val finalResults =
                results.distinctBy {
                    it.url
                }

            hdLog(
                "HDCH D MAIN PAGE RESULT COUNT » ${finalResults.size}"
            )

            newHomePageResponse(
                request.name,
                finalResults,
                hasNext = finalResults.size >= 20
            )

        } catch (e: Exception) {

            logError(e)

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            query
                .trim()
                .replace(
                    " ",
                    "+"
                )

        val searchUrls =
            listOf(
                "$mainUrl/?s=$encodedQuery",
                "$mainUrl/arama/$encodedQuery/",
                "$mainUrl/arama?q=$encodedQuery"
            )

        val results =
            mutableListOf<SearchResponse>()

        for (searchUrl in searchUrls) {

            try {

                val response =
                    app.get(
                        searchUrl,
                        headers = requestHeaders
                    )

                val document =
                    response.document

                val elements =
                    document.select(
                        """
                        article,
                        .film,
                        .movie,
                        .dizi,
                        .serie,
                        .poster,
                        .film-box,
                        .film-container,
                        .film-item,
                        .movie-item,
                        .post
                        """.trimIndent()
                    )

                for (element in elements) {

                    val linkElement =
                        element.selectFirst(
                            "a[href]"
                        )
                            ?: continue

                    val href =
                        fixUrl(
                            linkElement.attr("href")
                        )

                    if (href.isBlank()) {
                        continue
                    }

                    val title =
                        element
                            .selectFirst(
                                "h2, h3, h4, .title, .film-title, .movie-title"
                            )
                            ?.text()
                            ?.trim()
                            .takeUnless {
                                it.isNullOrBlank()
                            }
                            ?: linkElement
                                .attr("title")
                                .trim()
                                .takeUnless {
                                    it.isBlank()
                                }
                            ?: linkElement
                                .text()
                                .trim()

                    if (title.isBlank()) {
                        continue
                    }

                    val poster =
                        element
                            .selectFirst("img")
                            ?.let {

                                it.attr("data-src")
                                    .ifBlank {
                                        it.attr("data-lazy-src")
                                    }
                                    .ifBlank {
                                        it.attr("data-original")
                                    }
                                    .ifBlank {
                                        it.attr("src")
                                    }
                            }
                            ?.let {
                                fixUrlNull(it)
                            }

                    val isSeries =
                        href.contains(
                            "/dizi/",
                            ignoreCase = true
                        )

                    if (isSeries) {

                        results +=
                            newTvSeriesSearchResponse(
                                name = title,
                                url = href,
                                type = TvType.TvSeries,
                                fix = false
                            ) {

                                this.posterUrl =
                                    poster
                            }

                    } else {

                        results +=
                            newMovieSearchResponse(
                                name = title,
                                url = href,
                                type = TvType.Movie,
                                fix = false
                            ) {

                                this.posterUrl =
                                    poster
                            }
                    }
                }

                if (results.isNotEmpty()) {
                    break
                }

            } catch (e: Exception) {

                logError(e)
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        return try {

            val fixedUrl =
                fixUrl(url)

            val response =
                app.get(
                    fixedUrl,
                    headers = requestHeaders
                )

            val document =
                response.document

            val title =
                document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .takeUnless {
                        it.isNullOrBlank()
                    }
                    ?: document
                        .selectFirst(
                            "meta[property=og:title]"
                        )
                        ?.attr("content")
                        ?.trim()
                        .takeUnless {
                            it.isNullOrBlank()
                        }
                    ?: document.title()

            val poster =
                document
                    .selectFirst(
                        "meta[property=og:image]"
                    )
                    ?.attr("content")
                    ?.trim()
                    ?.let {
                        fixUrlNull(it)
                    }
                    ?: document
                        .selectFirst(
                            """
                            img.poster,
                            .poster img,
                            .film-poster img,
                            .movie-poster img
                            """.trimIndent()
                        )
                        ?.let {

                            it.attr("data-src")
                                .ifBlank {
                                    it.attr("data-lazy-src")
                                }
                                .ifBlank {
                                    it.attr("src")
                                }
                        }
                        ?.let {
                            fixUrlNull(it)
                        }

            val plot =
                document
                    .selectFirst(
                        """
                        .description,
                        .plot,
                        .film-description,
                        .movie-description,
                        article p,
                        main p
                        """.trimIndent()
                    )
                    ?.text()
                    ?.trim()
                    .takeUnless {
                        it.isNullOrBlank()
                    }
                    ?: document
                        .selectFirst(
                            "meta[name=description]"
                        )
                        ?.attr("content")
                        ?.trim()

            val year =
                Regex(
                    """\b(19\d{2}|20\d{2})\b"""
                )
                    .find(
                        document.text()
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val rating =
                Regex(
                    """(?i)(?:imdb|puan|rating)[^\d]{0,20}(\d+(?:[.,]\d+)?)"""
                )
                    .find(
                        document.text()
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(
                        ",",
                        "."
                    )
                    ?.toDoubleOrNull()

            // =================================================
            // ACTORS
            // =================================================

            val actors =
                document
                    .select(
                        """
                        .cast a,
                        .actors a,
                        .actor a,
                        .oyuncular a,
                        .cast .name,
                        .actors .name
                        """.trimIndent()
                    )
                    .mapNotNull { actorElement ->

                        val actorName =
                            actorElement
                                .text()
                                .trim()

                        if (actorName.isBlank()) {

                            null

                        } else {

                            ActorData(
                                Actor(
                                    actorName,
                                    actorElement
                                        .selectFirst("img")
                                        ?.attr("src")
                                )
                            )
                        }
                    }
                    .distinctBy {
                        it.actor.name
                    }

            // =================================================
            // TRAILER
            // =================================================

            val trailer =
                document
                    .select(
                        """
                        a[href*="youtube.com"],
                        a[href*="youtu.be"],
                        iframe[src*="youtube.com"],
                        iframe[src*="youtu.be"]
                        """.trimIndent()
                    )
                    .firstOrNull()
                    ?.let { element ->

                        when (element.tagName()) {

                            "iframe" ->
                                element.attr(
                                    "src"
                                )

                            else ->
                                element.attr(
                                    "href"
                                )
                        }
                    }
                    ?.let {
                        decodeUrl(it)
                    }

            // =================================================
            // SERIES
            // =================================================

            val isSeries =
                fixedUrl.contains(
                    "/dizi/",
                    ignoreCase = true
                )

            if (isSeries) {

                val episodes =
                    mutableListOf<Episode>()

                val episodeElements =
                    document.select(
                        """
                        .episode,
                        .episodes a,
                        .episode-list a,
                        .bolum-list a,
                        .bolumler a,
                        a[href*="/bolum-"],
                        a[href*="/bolum/"]
                        """.trimIndent()
                    )

                for (
                    (index, episodeElement)
                    in episodeElements.withIndex()
                ) {

                    val episodeUrl =
                        fixUrl(
                            episodeElement.attr("href")
                        )

                    if (episodeUrl.isBlank()) {
                        continue
                    }

                    val episodeTitle =
                        episodeElement
                            .text()
                            .trim()
                            .ifBlank {
                                "Bölüm ${index + 1}"
                            }

                    val episodeNumber =
                        Regex(
                            """(?:bölüm|bolum|episode)[^\d]*(\d+)"""
                        )
                            .find(
                                episodeTitle.lowercase(
                                    Locale.ROOT
                                )
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: Regex(
                                """(\d+)"""
                            )
                                .find(
                                    episodeTitle
                                )
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                            ?: (index + 1)

                    episodes +=
                        newEpisode(
                            episodeUrl
                        ) {

                            this.name =
                                episodeTitle

                            this.episode =
                                episodeNumber
                        }
                }

                return newTvSeriesLoadResponse(
                    name = title,
                    url = fixedUrl,
                    type = TvType.TvSeries,
                    episodes = episodes
                ) {

                    this.posterUrl =
                        poster

                    this.plot =
                        plot

                    this.year =
                        year

                    this.actors =
                        actors

                    if (rating != null) {

                        this.score =
                            Score.from(
                                rating,
                                10
                            )
                    }

                    if (!trailer.isNullOrBlank()) {

                        this.trailers.add(
                            TrailerData(
                                extractorUrl = trailer,
                                referer = null,
                                raw = false
                            )
                        )
                    }
                }
            }

            // =================================================
            // MOVIE
            // =================================================

            newMovieLoadResponse(
                name = title,
                url = fixedUrl,
                type = TvType.Movie,
                dataUrl = fixedUrl
            ) {

                this.posterUrl =
                    poster

                this.plot =
                    plot

                this.year =
                    year

                this.actors =
                    actors

                if (rating != null) {

                    this.score =
                        Score.from(
                            rating,
                            10
                        )
                }

                if (!trailer.isNullOrBlank()) {

                    this.trailers.add(
                        TrailerData(
                            extractorUrl = trailer,
                            referer = null,
                            raw = false
                        )
                    )
                }
            }

        } catch (e: Exception) {

            logError(e)

            null
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        hdLog(
            "HDCH D LOAD LINKS START"
        )

        hdLog(
            "HDCH D DATA » $data"
        )

        return try {

            val document =
                app.get(
                    fixUrl(data),
                    headers = requestHeaders
                ).document

            // -------------------------------------------------
            // SAYFADAKİ IFRAME'LER
            // -------------------------------------------------

            document
                .select(
                    "div.video-container iframe, iframe"
                )
                .forEach { iframe ->

                    val iframeUrl =
                        iframe.attr("src")

                    if (
                        iframeUrl.isBlank()
                    ) {
                        return@forEach
                    }

                    val fixedIframe =
                        fixUrl(
                            decodeUrl(
                                iframeUrl
                            )
                        )

                    hdLog(
                        "HDCH D IFRAME » $fixedIframe"
                    )

                    try {

                        loadExtractor(
                            fixedIframe,
                            data,
                            subtitleCallback,
                            callback
                        )

                        hdLog(
                            "HDCH D IFRAME EXTRACTOR CALLED » $fixedIframe"
                        )

                    } catch (e: Exception) {

                        logError(e)
                    }
                }

            // -------------------------------------------------
            // ALTERNATİF KAYNAKLAR
            // -------------------------------------------------

            document
                .select(
                    "div.alternative-links"
                )
                .forEach { alternativeBlock ->

                    alternativeBlock
                        .select(
                            "button.alternative-link"
                        )
                        .forEach { button ->

                            val videoId =
                                button
                                    .attr("data-video")
                                    .ifBlank {
                                        button.attr(
                                            "data-video-id"
                                        )
                                    }
                                    .ifBlank {
                                        button.attr(
                                            "data-id"
                                        )
                                    }

                            if (
                                videoId.isBlank()
                            ) {
                                return@forEach
                            }

                            hdLog(
                                "HDCH D VIDEO ID » $videoId"
                            )

                            try {

                                val apiResponse =
                                    app.get(
                                        "$mainUrl/video/$videoId/",
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Accept" to "*/*",
                                            "Content-Type" to "application/json",
                                            "X-Requested-With" to "fetch"
                                        ),
                                        referer = data
                                    )

                                val apiText =
                                    apiResponse.text

                                hdLog(
                                    "HDCH D VIDEO API LENGTH » ${apiText.length}"
                                )

                                var iframeUrl =
                                    apiResponse
                                        .document
                                        .selectFirst(
                                            "iframe"
                                        )
                                        ?.attr(
                                            "src"
                                        )

                                // data-src=\"...\"
                                if (
                                    iframeUrl.isNullOrBlank()
                                ) {

                                    iframeUrl =
                                        Regex(
                                            """data-src=\\"([^"]+)"""
                                        )
                                            .find(
                                                apiText
                                            )
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.replace(
                                                "\\",
                                                ""
                                            )
                                }

                                // data-src="..."
                                if (
                                    iframeUrl.isNullOrBlank()
                                ) {

                                    iframeUrl =
                                        Regex(
                                            """data-src="([^"]+)"""
                                        )
                                            .find(
                                                apiText
                                            )
                                            ?.groupValues
                                            ?.getOrNull(1)
                                }

                                if (
                                    !iframeUrl.isNullOrBlank()
                                ) {

                                    val fixedIframe =
                                        fixUrl(
                                            decodeUrl(
                                                iframeUrl
                                            )
                                        )

                                    hdLog(
                                        "HDCH D ALTERNATIVE IFRAME » $fixedIframe"
                                    )

                                    loadExtractor(
                                        fixedIframe,
                                        data,
                                        subtitleCallback,
                                        callback
                                    )
                                }

                            } catch (e: Exception) {

                                logError(e)
                            }
                        }
                }

            true

        } catch (e: Exception) {

            logError(e)

            false
        }
    }

    // =========================================================
    // URL HELPERS
    // =========================================================

    private fun decodeUrl(
        value: String
    ): String {

        var result =
            value

        try {

            result =
                result
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "\\u002F",
                        "/"
                    )
                    .replace(
                        "\\u002f",
                        "/"
                    )
                    .replace(
                        "\\u003A",
                        ":"
                    )
                    .replace(
                        "\\u003a",
                        ":"
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
                        "&amp;",
                        "&"
                    )
                    .replace(
                        "&sol;",
                        "/"
                    )
                    .replace(
                        "&#x2F;",
                        "/"
                    )
                    .replace(
                        "&#47;",
                        "/"
                    )

            Regex(
                """\\u([0-9a-fA-F]{4})"""
            )
                .findAll(result)
                .toList()
                .reversed()
                .forEach { match ->

                    val code =
                        match
                            .groupValues[1]
                            .toInt(16)

                    result =
                        result.replace(
                            match.value,
                            code.toChar().toString()
                        )
                }

            Regex(
                """\\x([0-9a-fA-F]{2})"""
            )
                .findAll(result)
                .toList()
                .reversed()
                .forEach { match ->

                    val code =
                        match
                            .groupValues[1]
                            .toInt(16)

                    result =
                        result.replace(
                            match.value,
                            code.toChar().toString()
                        )
                }

            result =
                result
                    .removeSuffix("\\")
                    .trim()

            result =
                try {

                    URLDecoder.decode(
                        result,
                        "UTF-8"
                    )

                } catch (_: Exception) {

                    result
                }

        } catch (_: Exception) {
        }

        return result
    }

    private fun fixUrlNull(
        url: String?
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        val decoded =
            decodeUrl(url)

        return when {

            decoded.startsWith(
                "http://"
            ) ||
            decoded.startsWith(
                "https://"
            ) ->
                decoded

            decoded.startsWith(
                "//"
            ) ->
                "https:$decoded"

            decoded.startsWith(
                "/"
            ) ->
                mainUrl + decoded

            else ->
                decoded
        }
    }

    private fun fixUrl(
        url: String
    ): String {

        return fixUrlNull(url)
            ?: url
    }
}
