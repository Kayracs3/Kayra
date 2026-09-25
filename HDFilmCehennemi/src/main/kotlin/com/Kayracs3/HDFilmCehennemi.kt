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
        debugPrint("HDCH") { message }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {

            val targetUrl = request.data.replace(
                "sayfano",
                page.toString()
            )

            hdLog("HDCH D MAIN PAGE URL » $targetUrl")

            val response = app.get(
                targetUrl,
                headers = requestHeaders,
                referer = "$mainUrl/"
            )

            val rawText = response.text.trim()

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

            val html = try {

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

            val document = Jsoup.parse(html)

            val results = mutableListOf<SearchResponse>()

            document
                .select("a[href]")
                .forEach { element ->

                    try {

                        val href = element.attr("href").trim()

                        if (href.isBlank()) {
                            return@forEach
                        }

                        val fixedHref = fixUrl(href)

                        if (
                            fixedHref == mainUrl ||
                            fixedHref == "$mainUrl/"
                        ) {
                            return@forEach
                        }

                        val image =
                            element.selectFirst("img")

                        val poster = image
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
                            element.attr("title")
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

                            results +=
                                newTvSeriesSearchResponse(
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

                            results +=
                                newMovieSearchResponse(
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
                results.distinctBy { it.url }

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
            query.trim().replace(" ", "+")

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/arama/$encodedQuery/",
            "$mainUrl/arama?q=$encodedQuery"
        )

        val results = mutableListOf<SearchResponse>()

        for (searchUrl in searchUrls) {

            try {

                val response = app.get(
                    searchUrl,
                    headers = requestHeaders
                )

                val document = response.document

                val elements = document.select(
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

                    val href = fixUrl(
                        linkElement.attr("href")
                    )

                    if (href.isBlank()) {
                        continue
                    }

                    val title =
                        element.selectFirst(
                            "h2, h3, h4, .title, .film-title, .movie-title"
                        )
                            ?.text()
                            ?.trim()
                            .takeUnless {
                                it.isNullOrBlank()
                            }
                            ?: linkElement.attr("title").trim()
                                .takeUnless {
                                    it.isBlank()
                                }
                            ?: linkElement.text().trim()

                    if (title.isBlank()) {
                        continue
                    }

                    val poster =
                        element.selectFirst("img")
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
                                this.posterUrl = poster
                            }

                    } else {

                        results +=
                            newMovieSearchResponse(
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

        return results.distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        return try {

            val fixedUrl = fixUrl(url)

            val response = app.get(
                fixedUrl,
                headers = requestHeaders
            )

            val document = response.document

            val title =
                document.selectFirst("h1")
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
                document.selectFirst(
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
                document.selectFirst(
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
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val rating =
                Regex(
                    """(?i)(?:imdb|puan|rating)[^\d]{0,20}(\d+(?:[.,]\d+)?)"""
                )
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(",", ".")
                    ?.toDoubleOrNull()

            val actors =
                document.select(
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

            val trailer =
                document.select(
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

                    val episodeUrl = fixUrl(
                        episodeElement.attr("href")
                    )

                    if (episodeUrl.isBlank()) {
                        continue
                    }

                    val episodeTitle =
                        episodeElement.text()
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
                            ?: Regex("""(\d+)""")
                                .find(episodeTitle)
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

        hdLog("HDCH D LOAD LINKS START")
        hdLog("HDCH D data » $data")

        return try {

            val response = app.get(
                fixUrl(data),
                headers = requestHeaders
            )

            val document = response.document

            hdLog(
                "HDCH D PAGE TITLE » ${document.title()}"
            )

            val playerUrls =
                linkedSetOf<Pair<String, String?>>()

            document
                .select(
                    "div.video-container iframe, iframe"
                )
                .forEach { iframe ->

                    val iframeUrl =
                        iframe.attr("src")

                    if (iframeUrl.isNotBlank()) {

                        val fixedIframe =
                            fixUrl(iframeUrl)

                        hdLog(
                            "HDCH D IFRAME FOUND » $fixedIframe"
                        )

                        playerUrls.add(
                            fixedIframe to data
                        )
                    }
                }

            document
                .select(
                    "div.alternative-links button.alternative-link"
                )
                .forEach { button ->

                    val videoId =
                        button.attr("data-video-id")
                            .ifBlank {
                                button.attr("data-id")
                            }
                            .ifBlank {
                                button.attr("data-video")
                            }

                    if (videoId.isBlank()) {
                        return@forEach
                    }

                    try {

                        val videoResponse =
                            app.get(
                                "$mainUrl/video/$videoId/",
                                headers = requestHeaders,
                                referer = data
                            )

                        var iframeUrl =
                            videoResponse.document
                                .selectFirst("iframe")
                                ?.attr("src")

                        if (iframeUrl.isNullOrBlank()) {

                            iframeUrl =
                                Regex(
                                    """data-src=\\"([^"]+)"""
                                )
                                    .find(
                                        videoResponse.text
                                    )
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.replace(
                                        "\\",
                                        ""
                                    )
                        }

                        if (iframeUrl.isNullOrBlank()) {

                            iframeUrl =
                                Regex(
                                    """data-src="([^"]+)"""
                                )
                                    .find(
                                        videoResponse.text
                                    )
                                    ?.groupValues
                                    ?.getOrNull(1)
                        }

                        if (!iframeUrl.isNullOrBlank()) {

                            val fixedIframe =
                                fixUrl(
                                    decodeUrl(
                                        iframeUrl
                                    )
                                )

                            playerUrls.add(
                                fixedIframe to data
                            )
                        }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }

            for (
                (playerUrl, referer)
                in playerUrls
            ) {

                var finalPlayerUrl =
                    playerUrl

                val rapidrameId =
                    Regex(
                        """rapidrame_id=([^&#"\s]+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(playerUrl)
                        ?.groupValues
                        ?.getOrNull(1)

                if (!rapidrameId.isNullOrBlank()) {

                    finalPlayerUrl =
                        "$mainUrl/rplayer/$rapidrameId"
                }

                try {

                    loadExtractor(
                        finalPlayerUrl,
                        referer,
                        subtitleCallback,
                        callback
                    )

                } catch (e: Exception) {

                    logError(e)
                }

                extractDirectPlayer(
                    playerUrl = finalPlayerUrl,
                    referer = referer ?: data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            true

        } catch (e: Exception) {

            logError(e)

            false
        }
    }

    private suspend fun extractDirectPlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val response =
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Referer" to referer
                    ),
                    referer = referer
                )

            val html =
                response.text

            val candidates =
                linkedSetOf<String>()

            fun addCandidate(
                value: String?
            ) {

                if (value.isNullOrBlank()) {
                    return
                }

                val decoded =
                    decodeUrl(value)
                        .trim()
                        .removeSuffix("\\")
                        .removePrefix("\"")
                        .removeSuffix("\"")

                if (
                    !decoded.startsWith("http://") &&
                    !decoded.startsWith("https://") &&
                    !decoded.startsWith("/")
                ) {
                    return
                }

                val lower =
                    decoded.lowercase(Locale.ROOT)

                val isStatic =
                    lower.endsWith(".js") ||
                    lower.contains(".js?") ||
                    lower.endsWith(".css") ||
                    lower.contains(".css?") ||
                    lower.endsWith(".jpg") ||
                    lower.contains(".jpg?") ||
                    lower.endsWith(".jpeg") ||
                    lower.contains(".jpeg?") ||
                    lower.endsWith(".png") ||
                    lower.contains(".png?") ||
                    lower.endsWith(".gif") ||
                    lower.contains(".gif?") ||
                    lower.endsWith(".svg") ||
                    lower.contains(".svg?") ||
                    lower.endsWith(".ico") ||
                    lower.contains(".ico?") ||
                    lower.endsWith(".woff") ||
                    lower.contains(".woff?") ||
                    lower.endsWith(".woff2") ||
                    lower.contains(".woff2?") ||
                    lower.endsWith(".ttf") ||
                    lower.contains(".ttf?")

                if (isStatic) {
                    return
                }

                val looksLikeMedia =
                    lower.contains(".m3u8") ||
                    lower.contains(".mp4") ||
                    lower.contains(".m3u") ||
                    lower.contains("/hls/") ||
                    lower.contains("/stream") ||
                    lower.contains("/playlist") ||
                    lower.contains("/manifest") ||
                    lower.contains("/master") ||
                    lower.contains("/source") ||
                    lower.contains("/media/") ||
                    lower.contains("/file/") ||
                    lower.contains("m3u8") ||
                    lower.contains("playlist") ||
                    lower.contains("manifest") ||
                    lower.contains("stream")

                if (!looksLikeMedia) {
                    return
                }

                val fixed =
                    fixUrlNull(decoded) ?: decoded

                candidates.add(fixed)
            }

            Regex(
                """https?://[^"'\s<>]+?\.(m3u8|mp4|m3u)(\?[^"'\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )
                .findAll(html)
                .forEach {
                    addCandidate(
                        it.groupValues[0]
                    )
                }

            val scripts =
                response.document.select("script")

            for (script in scripts) {

                val scriptText =
                    script.data()

                if (scriptText.isBlank()) {
                    continue
                }

                Regex(
                    """(?i)\b(file|source|src|url)\s*:\s*["']([^"']+)["']"""
                )
                    .findAll(scriptText)
                    .forEach {

                        addCandidate(
                            it.groupValues[2]
                        )
                    }

                Regex(
                    """(?i)"(?:file|source|src|url)"\s*:\s*"([^"]+)""""
                )
                    .findAll(scriptText)
                    .forEach {

                        addCandidate(
                            it.groupValues[1]
                        )
                    }

                Regex(
                    """https?://[^"'\s]+\.(m3u8|mp4|m3u)(\?[^"'\s]*)?""",
                    RegexOption.IGNORE_CASE
                )
                    .findAll(scriptText)
                    .forEach {

                        addCandidate(
                            it.groupValues[0]
                        )
                    }

                if (
                    scriptText.contains(
                        "eval(function(p,a,c,k,e,d)",
                        ignoreCase = true
                    )
                ) {

                    try {

                        val unpacked =
                            getAndUnpack(
                                scriptText
                            )

                        Regex(
                            """(?i)\b(file|source|src|url)\s*:\s*["']([^"']+)["']"""
                        )
                            .findAll(unpacked)
                            .forEach {

                                addCandidate(
                                    it.groupValues[2]
                                )
                            }

                        Regex(
                            """https?://[^"'\s]+\.(m3u8|mp4|m3u)(\?[^"'\s]*)?""",
                            RegexOption.IGNORE_CASE
                        )
                            .findAll(unpacked)
                            .forEach {

                                addCandidate(
                                    it.groupValues[0]
                                )
                            }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }
            }

            response.document
                .select(
                    "video, video source, source"
                )
                .forEach { element ->

                    addCandidate(
                        element.attr("src")
                            .ifBlank {
                                element.attr("data-src")
                            }
                            .ifBlank {
                                element.attr("data-file")
                            }
                            .ifBlank {
                                element.attr("data-url")
                            }
                            .ifBlank {
                                element.attr("data-video")
                            }
                    )
                }

            response.document
                .select(
                    "track[src], track[data-src]"
                )
                .forEach { track ->

                    val subtitleUrl =
                        fixUrlNull(
                            track.attr("src")
                                .ifBlank {
                                    track.attr("data-src")
                                }
                        )

                    if (!subtitleUrl.isNullOrBlank()) {

                        subtitleCallback(
                            newSubtitleFile(
                                track.attr("label")
                                    .ifBlank {
                                        track.attr("srclang")
                                    }
                                    .ifBlank {
                                        "Türkçe"
                                    },
                                subtitleUrl
                            )
                        )
                    }
                }

            hdLog(
                "HDCH D FINAL CANDIDATE COUNT » ${candidates.size}"
            )

            for (candidate in candidates) {

                val type =
                    if (
                        candidate.contains(
                            ".m3u8",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            ".m3u",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            "playlist",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            "manifest",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            "/hls/",
                            ignoreCase = true
                        )
                    ) {

                        ExtractorLinkType.M3U8

                    } else {

                        ExtractorLinkType.VIDEO
                    }

                val quality =
                    when {

                        candidate.contains(
                            "2160",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            "4k",
                            ignoreCase = true
                        ) ->
                            Qualities.P2160.value

                        candidate.contains(
                            "1080",
                            ignoreCase = true
                        ) ->
                            Qualities.P1080.value

                        candidate.contains(
                            "720",
                            ignoreCase = true
                        ) ->
                            Qualities.P720.value

                        candidate.contains(
                            "480",
                            ignoreCase = true
                        ) ->
                            Qualities.P480.value

                        candidate.contains(
                            "360",
                            ignoreCase = true
                        ) ->
                            Qualities.P360.value

                        else ->
                            Qualities.Unknown.value
                    }

                try {

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = candidate,
                            type = type
                        ) {

                            this.referer =
                                playerUrl

                            this.quality =
                                quality

                            this.headers =
                                mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to playerUrl,
                                    "Accept" to "*/*",
                                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                                )
                        }
                    )

                } catch (e: Exception) {

                    logError(e)
                }
            }

        } catch (e: Exception) {

            logError(e)
        }
    }

    private fun decodeUrl(
        value: String
    ): String {

        var result =
            value

        try {

            result = result
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u003A", ":")
                .replace("\\u003a", ":")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("&amp;", "&")
                .replace("&sol;", "/")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/")

            Regex(
                """\\u([0-9a-fA-F]{4})"""
            )
                .findAll(result)
                .toList()
                .reversed()
                .forEach { match ->

                    val code =
                        match.groupValues[1]
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
                        match.groupValues[1]
                            .toInt(16)

                    result =
                        result.replace(
                            match.value,
                            code.toChar().toString()
                        )
                }

            result =
                result.removeSuffix("\\").trim()

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
}
