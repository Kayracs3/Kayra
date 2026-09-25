package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
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
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/yeni-filmler/" to "Yeni Filmler",
        "$mainUrl/yeni-diziler/" to "Yeni Diziler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun hdLog(message: String) {
        debugPrint("HDCH") {
            message
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

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
                        element
                            .selectFirst(
                                "h2, h3, h4, .title, .film-title, .movie-title"
                            )
                            ?.text()
                            ?.trim()
                            .takeUnless { it.isNullOrBlank() }
                            ?: linkElement
                                .attr("title")
                                .trim()
                                .takeUnless { it.isBlank() }
                            ?: linkElement
                                .text()
                                .trim()

                    if (title.isBlank()) {
                        continue
                    }

                    val poster = element
                        .selectFirst("img")
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

    // =========================================================
    // LOAD
    // =========================================================

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
                document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: document
                        .selectFirst(
                            "meta[property=og:title]"
                        )
                        ?.attr("content")
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
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
                    .takeUnless { it.isNullOrBlank() }
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

            // =================================================
            // ACTORS
            // =================================================

            val actors = document
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
                                element.attr("src")

                            else ->
                                element.attr("href")
                        }
                    }
                    ?.let {
                        decodeUrl(it)
                    }

            // =================================================
            // TV SERIES
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
                                .find(episodeTitle)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                            ?: (index + 1)

                    episodes += newEpisode(
                        episodeUrl
                    ) {

                        this.name = episodeTitle

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

            // =================================================
            // MOVIE
            // =================================================

            return newMovieLoadResponse(
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
            "HDCH D data » $data"
        )

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

            // =================================================
            // MAIN IFRAME
            // =================================================

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

            // =================================================
            // ALTERNATIVE LINKS
            // =================================================

            document
                .select(
                    "div.alternative-links button.alternative-link"
                )
                .forEach { button ->

                    val videoId =
                        button
                            .attr("data-video-id")
                            .ifBlank {
                                button.attr("data-id")
                            }

                    hdLog(
                        "HDCH D ALTERNATIVE » source=${button.text()} id=$videoId"
                    )

                    if (videoId.isBlank()) {
                        return@forEach
                    }

                    try {

                        val videoResponse =
                            app.get(
                                "$mainUrl/video/$videoId/",
                                headers = requestHeaders
                            )

                        hdLog(
                            "HDCH D VIDEO API LENGTH » ${videoResponse.text.length}"
                        )

                        val iframeUrl =
                            videoResponse.document
                                .selectFirst("iframe")
                                ?.attr("src")
                                ?.let {
                                    decodeUrl(it)
                                }

                        if (!iframeUrl.isNullOrBlank()) {

                            val fixedIframe =
                                fixUrl(iframeUrl)

                            hdLog(
                                "HDCH D EXTRACTED IFRAME » $fixedIframe"
                            )

                            playerUrls.add(
                                fixedIframe to data
                            )
                        }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }

            // =================================================
            // PLAYERS
            // =================================================

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

                    hdLog(
                        "HDCH D FINAL PLAYER » $finalPlayerUrl"
                    )
                }

                // =================================================
                // EXTRACTOR
                // =================================================

                try {

                    hdLog(
                        "HDCH D loadExtractor » $finalPlayerUrl"
                    )

                    loadExtractor(
                        finalPlayerUrl,
                        referer,
                        subtitleCallback,
                        callback
                    )

                    hdLog(
                        "HDCH D loadExtractor SUCCESS » $finalPlayerUrl"
                    )

                } catch (e: Exception) {

                    logError(e)
                }

                // =================================================
                // DIRECT PLAYER
                // =================================================

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

    // =========================================================
    // DIRECT PLAYER EXTRACTION
    // =========================================================

    private suspend fun extractDirectPlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            hdLog(
                "HDCH D PLAYER INSPECT START"
            )

            hdLog(
                "HDCH D PLAYER URL » $playerUrl"
            )

            val response =
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Referer" to referer
                    )
                )

            val html =
                response.text

            hdLog(
                "HDCH D PLAYER HTTP RESPONSE RECEIVED"
            )

            hdLog(
                "HDCH D PLAYER HTML LENGTH » ${html.length}"
            )

            hdLog(
                "HDCH D PLAYER HAS M3U8 » ${
                    html.contains(
                        ".m3u8",
                        ignoreCase = true
                    )
                }"
            )

            hdLog(
                "HDCH D PLAYER HAS MP4 » ${
                    html.contains(
                        ".mp4",
                        ignoreCase = true
                    )
                }"
            )

            hdLog(
                "HDCH D PLAYER HAS SOURCE » ${
                    html.contains(
                        "source",
                        ignoreCase = true
                    )
                }"
            )

            hdLog(
                "HDCH D PLAYER HAS FILE » ${
                    html.contains(
                        "file",
                        ignoreCase = true
                    )
                }"
            )

            hdLog(
                "HDCH D PLAYER HAS SOURCES » ${
                    html.contains(
                        "sources",
                        ignoreCase = true
                    )
                }"
            )

            hdLog(
                "HDCH D PLAYER HTML PREVIEW » ${html.take(2500)}"
            )

            // =================================================
            // MEDIA CANDIDATES
            // =================================================

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

                if (
                    !decoded.startsWith("http://") &&
                    !decoded.startsWith("https://") &&
                    !decoded.startsWith("/")
                ) {
                    return
                }

                val isMedia =
                    decoded.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) ||
                    decoded.contains(
                        ".mp4",
                        ignoreCase = true
                    ) ||
                    decoded.contains(
                        ".m3u",
                        ignoreCase = true
                    )

                if (!isMedia) {
                    return
                }

                candidates.add(
                    fixUrlNull(decoded)
                        ?: decoded
                )
            }

            // =================================================
            // DIRECT MEDIA URLS
            // =================================================

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

            // =================================================
            // RELATIVE MEDIA URLS
            // =================================================

            Regex(
                """["'](/[^"'\s<>]+\.(m3u8|mp4|m3u)(\?[^"'\s<>]*)?)["']""",
                RegexOption.IGNORE_CASE
            )
                .findAll(html)
                .forEach {
                    addCandidate(
                        it.groupValues[1]
                    )
                }

            // =================================================
            // SCRIPTS
            // =================================================

            val scripts =
                response.document.select("script")

            for (
                (index, script)
                in scripts.withIndex()
            ) {

                val scriptText =
                    script.data()

                hdLog(
                    "HDCH D SCRIPT[$index] LENGTH » ${scriptText.length}"
                )

                if (scriptText.isBlank()) {
                    continue
                }

                val looksLikePlayerScript =
                    scriptText.contains(
                        "jwplayer",
                        ignoreCase = true
                    ) ||
                    scriptText.contains(
                        "sources",
                        ignoreCase = true
                    ) ||
                    scriptText.contains(
                        "playlist",
                        ignoreCase = true
                    ) ||
                    scriptText.contains(
                        "file:",
                        ignoreCase = true
                    ) ||
                    scriptText.contains(
                        "eval(function(p,a,c,k,e,d)",
                        ignoreCase = true
                    )

                if (!looksLikePlayerScript) {
                    continue
                }

                hdLog(
                    "HDCH D SCRIPT[$index] LOOKS LIKE PLAYER SCRIPT"
                )

                hdLog(
                    "HDCH D PLAYER SCRIPT PREVIEW[$index] » ${scriptText.take(3000)}"
                )

                // file: "..."
                Regex(
                    """(?i)\bfile\s*:\s*["']([^"']+)["']"""
                )
                    .findAll(scriptText)
                    .forEach {
                        addCandidate(
                            it.groupValues[1]
                        )
                    }

                // sources: [{file: "..."}]
                Regex(
                    """(?i)\bsources\s*:\s*\[[^\]]*?\bfile\s*:\s*["']([^"']+)["']"""
                )
                    .findAll(scriptText)
                    .forEach {
                        addCandidate(
                            it.groupValues[1]
                        )
                    }

                // Direct media URL
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

                // =================================================
                // PACKED JAVASCRIPT
                // =================================================

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

                        hdLog(
                            "HDCH D UNPACKED LENGTH SCRIPT[$index] » ${unpacked.length}"
                        )

                        hdLog(
                            "HDCH D UNPACKED SCRIPT[$index] PREVIEW » ${unpacked.take(4000)}"
                        )

                        Regex(
                            """(?i)\bfile\s*:\s*["']([^"']+)["']"""
                        )
                            .findAll(unpacked)
                            .forEach {
                                addCandidate(
                                    it.groupValues[1]
                                )
                            }

                        Regex(
                            """(?i)\bsources\s*:\s*\[[^\]]*?\bfile\s*:\s*["']([^"']+)["']"""
                        )
                            .findAll(unpacked)
                            .forEach {
                                addCandidate(
                                    it.groupValues[1]
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

                        Regex(
                            """["'](/[^"'\s]+\.(m3u8|mp4|m3u)(\?[^"'\s]*)?)["']""",
                            RegexOption.IGNORE_CASE
                        )
                            .findAll(unpacked)
                            .forEach {
                                addCandidate(
                                    it.groupValues[1]
                                )
                            }

                    } catch (e: Exception) {

                        logError(e)
                    }
                }
            }

            // =================================================
            // VIDEO / SOURCE TAGS
            // =================================================

            response.document
                .select(
                    "video source, video, source"
                )
                .forEach { element ->

                    val src =
                        element
                            .attr("src")
                            .ifBlank {
                                element.attr("data-src")
                            }
                            .ifBlank {
                                element.attr("data-video")
                            }

                    addCandidate(src)
                }

            // =================================================
            // SUBTITLES
            // =================================================

            response.document
                .select(
                    "track[src], track[data-src]"
                )
                .forEach { track ->

                    val subtitleUrl =
                        track
                            .attr("src")
                            .ifBlank {
                                track.attr("data-src")
                            }
                            .let {
                                decodeUrl(it)
                            }
                            .let {
                                fixUrlNull(it)
                            }

                    if (!subtitleUrl.isNullOrBlank()) {

                        val label =
                            track
                                .attr("label")
                                .ifBlank {
                                    track.attr("srclang")
                                }
                                .ifBlank {
                                    "Türkçe"
                                }

                        try {

                            subtitleCallback(
                                newSubtitleFile(
                                    label,
                                    subtitleUrl
                                )
                            )

                        } catch (e: Exception) {

                            logError(e)
                        }
                    }
                }

            // =================================================
            // CREATE LINKS
            // =================================================

            hdLog(
                "HDCH D FINAL CANDIDATE COUNT » ${candidates.size}"
            )

            for (
                (index, candidate)
                in candidates.withIndex()
            ) {

                hdLog(
                    "HDCH D CANDIDATE[$index] » $candidate"
                )

                val type =
                    if (
                        candidate.contains(
                            ".m3u8",
                            ignoreCase = true
                        ) ||
                        candidate.contains(
                            ".m3u",
                            ignoreCase = true
                        )
                    ) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }

                val quality =
                    when {

                        Regex(
                            "(?i)1080"
                        )
                            .containsMatchIn(candidate) ->
                            Qualities.P1080.value

                        Regex(
                            "(?i)720"
                        )
                            .containsMatchIn(candidate) ->
                            Qualities.P720.value

                        Regex(
                            "(?i)480"
                        )
                            .containsMatchIn(candidate) ->
                            Qualities.P480.value

                        Regex(
                            "(?i)360"
                        )
                            .containsMatchIn(candidate) ->
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

                    hdLog(
                        "HDCH D EXTRACTOR LINK CREATED » $candidate"
                    )

                } catch (e: Exception) {

                    logError(e)
                }
            }

        } catch (e: Exception) {

            logError(e)
        }
    }

    // =========================================================
    // URL HELPERS
    // =========================================================

    private fun decodeUrl(
        value: String
    ): String {

        var result = value

        try {

            result =
                result
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("\\u002f", "/")
                    .replace("\\u003A", ":")
                    .replace("\\u003a", ":")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")

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

        return fixUrlNull(url)
            ?: url
    }
}
