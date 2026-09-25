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

    private fun hdLog(message: String) {
        debugPrint("HDCH") {
            message
        }
    }

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

            val response = app.get(
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
                        JSONObject(rawText).optString(
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

            document
                .select("a[href]")
                .forEach { element ->

                    try {

                        val href =
                            element.attr("href").trim()

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
                            image?.let {
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
                            }?.let {
                                fixImageUrlNull(it)
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
                            fixedHref.lowercase(Locale.ROOT)

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

                            results += newTvSeriesSearchResponse(
                                name = title,
                                url = fixedHref,
                                type = TvType.TvSeries,
                                fix = false
                            ) {

                                this.posterUrl = poster

                                if (score != null) {
                                    this.score =
                                        Score.from(
                                            score,
                                            10
                                        )
                                }
                            }

                        } else {

                            results += newMovieSearchResponse(
                                name = title,
                                url = fixedHref,
                                type = TvType.Movie,
                                fix = false
                            ) {

                                this.posterUrl = poster

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
                        element.selectFirst("a[href]")
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
                                fixImageUrlNull(it)
                            }

                    val isSeries =
                        href.contains(
                            "/dizi/",
                            ignoreCase = true
                        )

                    if (isSeries) {

                        results += newTvSeriesSearchResponse(
                            name = title,
                            url = href,
                            type = TvType.TvSeries,
                            fix = false
                        ) {
                            this.posterUrl = poster
                        }

                    } else {

                        results += newMovieSearchResponse(
                            name = title,
                            url = href,
                            type = TvType.Movie,
                            fix = false
                        ) {
                            this.posterUrl = poster
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

    override suspend fun load(
        url: String
    ): LoadResponse? {

        return try {

            val fixedUrl =
                fixUrl(url)

            hdLog(
                "HDCH D LOAD URL » $fixedUrl"
            )

            val response =
                app.get(
                    fixedUrl,
                    headers = requestHeaders,
                    referer = "$mainUrl/"
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
                        fixImageUrlNull(it)
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
                                    it.attr("data-original")
                                }
                                .ifBlank {
                                    it.attr("src")
                                }
                        }
                        ?.let {
                            fixImageUrlNull(it)
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
                    ?.replace(",", ".")
                    ?.toDoubleOrNull()

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
                            actorElement.text().trim()

                        if (actorName.isBlank()) {
                            null
                        } else {
                            ActorData(
                                Actor(
                                    actorName,
                                    fixImageUrlNull(
                                        actorElement
                                            .selectFirst("img")
                                            ?.attr("src")
                                    )
                                )
                            )
                        }
                    }
                    .distinctBy {
                        it.actor.name
                    }

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
                                element.attr("src")

                            else ->
                                element.attr("href")
                        }
                    }
                    ?.let {
                        decodeUrl(it)
                    }

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

                    episodes += newEpisode(
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

                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.actors = actors

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

            newMovieLoadResponse(
                name = title,
                url = fixedUrl,
                type = TvType.Movie,
                dataUrl = fixedUrl
            ) {

                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.actors = actors

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

            val response =
                app.get(
                    fixUrl(data),
                    headers = requestHeaders,
                    referer = "$mainUrl/"
                )

            val document =
                response.document

            var foundSource =
                false

            document
                .select(
                    "div.alternative-links"
                )
                .forEach { alternativeBlock ->

                    try {

                        val langCode =
                            alternativeBlock
                                .attr("data-lang")
                                .trim()
                                .uppercase(Locale.ROOT)

                        alternativeBlock
                            .select(
                                "button.alternative-link"
                            )
                            .forEach { button ->

                                try {

                                    val videoId =
                                        button
                                            .attr("data-video")
                                            .trim()
                                            .ifBlank {
                                                button.attr(
                                                    "data-video-id"
                                                ).trim()
                                            }
                                            .ifBlank {
                                                button.attr(
                                                    "data-id"
                                                ).trim()
                                            }

                                    if (videoId.isBlank()) {
                                        return@forEach
                                    }

                                    val sourceName =
                                        button
                                            .text()
                                            .replace(
                                                Regex(
                                                    """\(\s*HDrip.*?\)"""
                                                ),
                                                ""
                                            )
                                            .trim()
                                            .ifBlank {
                                                "HDFilmCehennemi"
                                            }
                                            .let {
                                                if (
                                                    langCode.isNotBlank()
                                                ) {
                                                    "$it $langCode"
                                                } else {
                                                    it
                                                }
                                            }

                                    hdLog(
                                        "HDCH D VIDEO ID » $videoId"
                                    )

                                    val apiResponse =
                                        app.get(
                                            "$mainUrl/video/$videoId/",
                                            headers = mapOf(
                                                "User-Agent" to USER_AGENT,
                                                "Accept" to "*/*",
                                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
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

                                    var playerUrl: String? = null

                                    playerUrl =
                                        Regex(
                                            """data-src=\\"([^"]+)"""
                                        )
                                            .find(apiText)
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.replace(
                                                "\\",
                                                ""
                                            )

                                    if (playerUrl.isNullOrBlank()) {

                                        playerUrl =
                                            Regex(
                                                """data-src="([^"]+)"""
                                            )
                                                .find(apiText)
                                                ?.groupValues
                                                ?.getOrNull(1)
                                    }

                                    if (playerUrl.isNullOrBlank()) {

                                        val iframe =
                                            apiResponse.document
                                                .selectFirst(
                                                    "iframe[data-src], iframe[src]"
                                                )

                                        playerUrl =
                                            iframe
                                                ?.attr("data-src")
                                                ?.ifBlank {
                                                    iframe.attr("src")
                                                }
                                    }

                                    if (playerUrl.isNullOrBlank()) {

                                        hdLog(
                                            "HDCH D PLAYER NOT FOUND » $videoId"
                                        )

                                        return@forEach
                                    }

                                    playerUrl =
                                        decodeUrl(playerUrl!!)

                                    hdLog(
                                        "HDCH D RAW PLAYER » $playerUrl"
                                    )

                                    if (
                                        playerUrl.contains(
                                            "rapidrame",
                                            ignoreCase = true
                                        )
                                    ) {

                                        val rapidId =
                                            playerUrl
                                                .substringAfter(
                                                    "?rapidrame_id=",
                                                    ""
                                                )
                                                .substringBefore("&")
                                                .trim()

                                        if (rapidId.isNotBlank()) {

                                            playerUrl =
                                                "$mainUrl/rplayer/$rapidId"

                                            hdLog(
                                                "HDCH D RAPIDRAME PLAYER » $playerUrl"
                                            )
                                        }
                                    }

                                    if (
                                        playerUrl.contains(
                                            "mobi",
                                            ignoreCase = true
                                        )
                                    ) {

                                        val mobiIframe =
                                            apiResponse.document
                                                .selectFirst(
                                                    "iframe[data-src], iframe[src]"
                                                )

                                        val mobiUrl =
                                            mobiIframe
                                                ?.attr("data-src")
                                                ?.ifBlank {
                                                    mobiIframe.attr("src")
                                                }

                                        if (
                                            !mobiUrl.isNullOrBlank()
                                        ) {

                                            playerUrl =
                                                decodeUrl(
                                                    mobiUrl
                                                )

                                            hdLog(
                                                "HDCH D MOBI PLAYER » $playerUrl"
                                            )
                                        }
                                    }

                                    val produced =
                                        invokeLocalSource(
                                            sourceName,
                                            playerUrl,
                                            subtitleCallback,
                                            callback
                                        )

                                    if (produced) {
                                        foundSource = true
                                    }

                                } catch (e: Exception) {

                                    hdLog(
                                        "HDCH D SOURCE ERROR » ${e.message}"
                                    )

                                    logError(e)
                                }
                            }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }

            if (!foundSource) {

                hdLog(
                    "HDCH D NO ALTERNATIVE SOURCE, FALLBACK IFRAME"
                )

                document
                    .select(
                        "iframe[src], iframe[data-src]"
                    )
                    .forEach { iframe ->

                        try {

                            var iframeUrl =
                                iframe
                                    .attr("data-src")
                                    .ifBlank {
                                        iframe.attr("src")
                                    }

                            if (iframeUrl.isBlank()) {
                                return@forEach
                            }

                            iframeUrl =
                                decodeUrl(
                                    iframeUrl
                                )

                            hdLog(
                                "HDCH D FALLBACK IFRAME » $iframeUrl"
                            )

                            if (
                                iframeUrl.contains(
                                    "rapidrame",
                                    ignoreCase = true
                                )
                            ) {

                                val rapidId =
                                    iframeUrl
                                        .substringAfter(
                                            "?rapidrame_id=",
                                            ""
                                        )
                                        .substringBefore("&")
                                        .trim()

                                if (rapidId.isNotBlank()) {

                                    iframeUrl =
                                        "$mainUrl/rplayer/$rapidId"
                                }
                            }

                            val produced =
                                invokeLocalSource(
                                    "HDFilmCehennemi",
                                    iframeUrl,
                                    subtitleCallback,
                                    callback
                                )

                            if (produced) {
                                foundSource = true
                            }

                        } catch (e: Exception) {

                            logError(e)
                        }
                    }
            }

            hdLog(
                "HDCH D LOAD LINKS RESULT » $foundSource"
            )

            foundSource

        } catch (e: Exception) {

            hdLog(
                "HDCH D LOAD LINKS ERROR » ${e.message}"
            )

            logError(e)

            false
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            hdLog(
                "HDCH D OPEN PLAYER » $playerUrl"
            )

            val playerResponse =
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                    ),
                    referer = "$mainUrl/"
                )

            val document =
                playerResponse.document

            val script =
                document
                    .select("script")
                    .firstOrNull {
                        it.data().contains(
                            "sources:"
                        )
                    }
                    ?.data()

            if (script.isNullOrBlank()) {

                hdLog(
                    "HDCH D SOURCE SCRIPT NOT FOUND » $playerUrl"
                )

                return false
            }

            hdLog(
                "HDCH D SOURCE SCRIPT FOUND"
            )

            val unpackedScript =
                try {
                    getAndUnpack(script)
                } catch (e: Exception) {

                    hdLog(
                        "HDCH D UNPACK ERROR » ${e.message}"
                    )

                    script
                }

            val decryptedUrl =
                decryptLocalUrl(
                    unpackedScript
                ) ?: return false

            hdLog(
                "HDCH D DECRYPTED URL » $decryptedUrl"
            )

            val mediaUrl =
                Regex(
                    """https?://[^"'\\\s]+"""
                )
                    .find(decryptedUrl)
                    ?.value
                    ?.trim()
                    ?: decryptedUrl
                        .substringAfter(
                            "https",
                            ""
                        )
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            "https$it"
                        }
                    ?: return false

            hdLog(
                "HDCH D M3U8 » $mediaUrl"
            )

            if (
                !mediaUrl.startsWith("http://") &&
                !mediaUrl.startsWith("https://")
            ) {

                hdLog(
                    "HDCH D INVALID MEDIA URL » $mediaUrl"
                )

                return false
            }

            val tracksBlock =
                Regex(
                    """tracks\s*:\s*\[(.*?)\]""",
                    setOf(
                        RegexOption.IGNORE_CASE,
                        RegexOption.DOT_MATCHES_ALL
                    )
                )
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)

            if (!tracksBlock.isNullOrBlank()) {

                Regex(
                    """\{[^{}]*?(?:file|src)\s*:\s*["']([^"']+)["'][^{}]*?(?:(?:label|language)\s*:\s*["']([^"']+)["'])?[^{}]*?\}""",
                    setOf(
                        RegexOption.IGNORE_CASE,
                        RegexOption.DOT_MATCHES_ALL
                    )
                )
                    .findAll(tracksBlock)
                    .forEach { match ->

                        try {

                            val subtitlePath =
                                match
                                    .groupValues
                                    .getOrNull(1)
                                    ?.trim()
                                    ?: return@forEach

                            val language =
                                match
                                    .groupValues
                                    .getOrNull(2)
                                    ?.trim()
                                    .takeUnless {
                                        it.isNullOrBlank()
                                    }
                                    ?: "Türkçe"

                            val subtitleUrl =
                                fixUrl(
                                    decodeUrl(
                                        subtitlePath
                                    )
                                )

                            hdLog(
                                "HDCH D SUBTITLE » $subtitleUrl"
                            )

                            subtitleCallback(
                                newSubtitleFile(
                                    language,
                                    subtitleUrl
                                )
                            )

                        } catch (e: Exception) {

                            hdLog(
                                "HDCH D SUBTITLE ERROR » ${e.message}"
                            )
                        }
                    }
            }

            callback(
                newExtractorLink(
                    source = source,
                    name = source,
                    url = mediaUrl,
                    type = ExtractorLinkType.M3U8
                ) {

                    headers =
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to playerUrl,
                            "Accept" to "*/*",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                        )

                    quality =
                        Qualities.Unknown.value
                }
            )

            hdLog(
                "HDCH D EXTRACTOR LINK ADDED » $mediaUrl"
            )

            true

        } catch (e: Exception) {

            hdLog(
                "HDCH D INVOKE LOCAL ERROR » ${e.message}"
            )

            logError(e)

            false
        }
    }

    private fun decryptLocalUrl(
        unpackedScript: String
    ): String? {

        return try {

            val partsMatch =
                Regex(
                    """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)"""
                )
                    .find(
                        unpackedScript
                    )

            val parts =
                partsMatch
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.split(",")
                    ?.map {
                        it
                            .trim()
                            .trim('\'', '"')
                            .replace(
                                "\\/",
                                "/"
                            )
                    }
                    ?: return null

            var result =
                parts.joinToString("")

            val moduloMatch =
                Regex(
                    """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)"""
                )
                    .find(
                        unpackedScript
                    )

            val magicNum =
                moduloMatch
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?: 399756995L

            val magicOffset =
                moduloMatch
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()
                    ?: 5

            val functionBody =
                unpackedScript
                    .substringAfter(
                        "function dc_",
                        ""
                    )
                    .substringBefore(
                        "function d1x",
                        ""
                    )

            if (functionBody.isBlank()) {

                hdLog(
                    "HDCH D DECRYPT FUNCTION NOT FOUND"
                )

                return null
            }

            data class Operation(
                val index: Int,
                val type: String,
                val shift: Int = 0
            )

            val operations =
                mutableListOf<Operation>()

            var index =
                functionBody.indexOf("atob(")

            while (index >= 0) {

                operations += Operation(
                    index = index,
                    type = "atob"
                )

                index =
                    functionBody.indexOf(
                        "atob(",
                        index + 1
                    )
            }

            index =
                functionBody.indexOf("reverse")

            while (index >= 0) {

                operations += Operation(
                    index = index,
                    type = "reverse"
                )

                index =
                    functionBody.indexOf(
                        "reverse",
                        index + 1
                    )
            }

            index =
                functionBody.indexOf("replace")

            while (index >= 0) {

                val block =
                    functionBody.substring(
                        index,
                        minOf(
                            index + 350,
                            functionBody.length
                        )
                    )

                var shift = 13

                val charCodeMatch =
                    Regex(
                        """charCodeAt\(0\)\s*\+\s*(\d+)"""
                    )
                        .find(block)

                if (charCodeMatch != null) {

                    shift =
                        charCodeMatch
                            .groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: 13

                } else {

                    val rotMatch =
                        Regex(
                            """o\s*-\s*base\s*([+-])\s*(\d+)"""
                        )
                            .find(block)

                    if (rotMatch != null) {

                        val sign =
                            rotMatch
                                .groupValues
                                .getOrNull(1)

                        val amount =
                            rotMatch
                                .groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()
                                ?: 0

                        shift =
                            if (sign == "-") {
                                (26 - amount) % 26
                            } else {
                                amount
                            }
                    }
                }

                operations += Operation(
                    index = index,
                    type = "rot",
                    shift = shift
                )

                index =
                    functionBody.indexOf(
                        "replace",
                        index + 1
                    )
            }

            operations.sortBy {
                it.index
            }

            operations.forEach { operation ->

                when (operation.type) {

                    "reverse" -> {
                        result =
                            result.reversed()
                    }

                    "atob" -> {

                        var padded =
                            result

                        while (
                            padded.length % 4 != 0
                        ) {
                            padded += "="
                        }

                        val decoded =
                            android.util.Base64.decode(
                                padded,
                                android.util.Base64.NO_WRAP
                            )

                        result =
                            String(
                                decoded,
                                Charsets.ISO_8859_1
                            )
                    }

                    "rot" -> {

                        val shift =
                            operation.shift

                        val output =
                            StringBuilder()

                        result.forEach { char ->

                            when {

                                char in 'a'..'z' -> {

                                    val shifted =
                                        char.code + shift

                                    output.append(
                                        if (
                                            shifted > 'z'.code
                                        ) {
                                            (
                                                shifted - 26
                                            ).toChar()
                                        } else {
                                            shifted.toChar()
                                        }
                                    )
                                }

                                char in 'A'..'Z' -> {

                                    val shifted =
                                        char.code + shift

                                    output.append(
                                        if (
                                            shifted > 'Z'.code
                                        ) {
                                            (
                                                shifted - 26
                                            ).toChar()
                                        } else {
                                            shifted.toChar()
                                        }
                                    )
                                }

                                else -> {
                                    output.append(char)
                                }
                            }
                        }

                        result =
                            output.toString()
                    }
                }
            }

            val output =
                StringBuilder()

            result.forEachIndexed { i, char ->

                val charCode =
                    char.code.toLong()

                val decodedCode =
                    (
                        charCode -
                            (
                                magicNum %
                                    (
                                        i +
                                            magicOffset
                                    )
                                ) +
                            256
                        ) % 256

                output.append(
                    decodedCode
                        .toInt()
                        .toChar()
                )
            }

            output.toString()

        } catch (e: Exception) {

            hdLog(
                "HDCH D DECRYPT ERROR » ${e.message}"
            )

            null
        }
    }

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

            decoded.startsWith("http://") ||
            decoded.startsWith("https://") ->
                decoded

            decoded.startsWith("//") ->
                "https:$decoded"

            decoded.startsWith("/") ->
                mainUrl + decoded

            else ->
                decoded
        }
    }

    private fun fixUrl(
        url: String
    ): String {

        return fixUrlNull(url) ?: url
    }

    private fun fixImageUrlNull(
        url: String?
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        var decoded =
            decodeUrl(url).trim()

        if (
            decoded.contains(
                ".cdn.ampproject.org/i/s/",
                ignoreCase = true
            )
        ) {

            val marker =
                "/i/s/"

            val index =
                decoded.indexOf(
                    marker,
                    ignoreCase = true
                )

            if (index >= 0) {

                val original =
                    decoded.substring(
                        index + marker.length
                    )

                decoded =
                    if (
                        original.startsWith("http://") ||
                        original.startsWith("https://")
                    ) {
                        original
                    } else {
                        "https://$original"
                    }
            }
        }

        return when {

            decoded.startsWith("http://") ||
            decoded.startsWith("https://") ->
                decoded

            decoded.startsWith("//") ->
                "https:$decoded"

            decoded.startsWith("/") ->
                mainUrl + decoded

            else ->
                decoded
        }
    }
}
