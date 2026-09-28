package com.Kayracs3

import android.util.Base64
import android.util.Log

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

import okhttp3.Interceptor
import okhttp3.Response

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy {
        CloudflareKiller()
    }

    private val interceptor by lazy {
        CloudflareInterceptor(cloudflareKiller)
    }

    private val defaultEmbedOrigin =
        "https://hdfilmcehennemi.mobi"

    private val browserHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to
            "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private val mediaUserAgent =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {

        override fun intercept(
            chain: Interceptor.Chain
        ): Response {

            val request =
                chain.request()

            val response =
                chain.proceed(
                    request
                )

            val body =
                response
                    .peekBody(
                        1024 * 1024
                    )
                    .string()

            val doc =
                Jsoup.parse(
                    body
                )

            if (
                doc.html().contains(
                    "Just a moment"
                )
            ) {
                return cloudflareKiller.intercept(
                    chain
                )
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to
            "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to
            "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/" to
            "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to
            "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to
            "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to
            "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val objectMapper =
            ObjectMapper()
                .registerModule(
                    KotlinModule.Builder().build()
                )

        objectMapper.configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )

        val url =
            request.data.replace(
                "sayfano",
                page.toString()
            )

        val headers =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "user-agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to
                    "*/*",
                "X-Requested-With" to
                    "fetch"
            )

        val doc =
            app.get(
                url,
                headers = headers,
                referer = mainUrl,
                interceptor = interceptor
            )

        if (
            doc.toString().contains(
                "Sayfa Bulunamadı"
            )
        ) {
            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        val data: HDFC =
            objectMapper.readValue(
                doc.toString()
            )

        val document =
            Jsoup.parse(
                data.html
            )

        val home =
            document
                .select("a")
                .mapNotNull {
                    it.toSearchResult()
                }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val title =
            attr("title").trim()

        if (title.isBlank()) {
            return null
        }

        val href =
            fixUrlNull(
                attr("href")
            ) ?: return null

        val poster =
            fixUrlNull(
                selectFirst("img")
                    ?.attr("data-src")
            )
                ?: fixUrlNull(
                    selectFirst("img")
                        ?.attr("src")
                )

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response =
            app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf(
                    "X-Requested-With" to
                        "fetch"
                )
            ).parsedSafe<Results>()
                ?: return emptyList()

        val result =
            mutableListOf<SearchResponse>()

        response.results.forEach { html ->

            val document =
                Jsoup.parse(html)

            val title =
                document
                    .selectFirst("h4.title")
                    ?.text()
                    ?.trim()
                    ?: return@forEach

            val href =
                fixUrlNull(
                    document
                        .selectFirst("a")
                        ?.attr("href")
                ) ?: return@forEach

            val poster =
                fixUrlNull(
                    document
                        .selectFirst("img")
                        ?.attr("src")
                )
                    ?: fixUrlNull(
                        document
                            .selectFirst("img")
                            ?.attr("data-src")
                    )

            result.add(
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl =
                        poster?.replace(
                            "/thumb/",
                            "/list/"
                        )
                }
            )
        }

        return result
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url,
                interceptor = interceptor
            ).document

        val title =
            document
                .selectFirst("h1.section-title")
                ?.text()
                ?.substringBefore(" izle")
                ?.trim()
                ?: return null

        val poster =
            fixUrlNull(
                document
                    .select(
                        "aside.post-info-poster img.lazyload"
                    )
                    .lastOrNull()
                    ?.attr("data-src")
            )
                ?: fixUrlNull(
                    document
                        .select(
                            "aside.post-info-poster img"
                        )
                        .lastOrNull()
                        ?.attr("src")
                )

        val tags =
            document
                .select("div.post-info-genres a")
                .map { it.text().trim() }

        val year =
            document
                .selectFirst("div.post-info-year-country a")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val isSeries =
            document
                .select("div.seasons")
                .isNotEmpty()

        val description =
            document
                .selectFirst("article.post-info-content > p")
                ?.text()
                ?.trim()

        val actors =
            document
                .select("div.post-info-cast a")
                .mapNotNull {

                    val actorName =
                        it
                            .selectFirst("strong")
                            ?.text()
                            ?.trim()
                            ?: return@mapNotNull null

                    Actor(
                        actorName,
                        it.select("img").attr("data-src")
                    )
                }

        val recommendations =
            document
                .select(
                    "div.section-slider-container div.slider-slide"
                )
                .mapNotNull {

                    val recName =
                        it
                            .selectFirst("a")
                            ?.attr("title")
                            ?.trim()
                            ?: return@mapNotNull null

                    val recHref =
                        fixUrlNull(
                            it
                                .selectFirst("a")
                                ?.attr("href")
                        ) ?: return@mapNotNull null

                    val recPoster =
                        fixUrlNull(
                            it
                                .selectFirst("img")
                                ?.attr("data-src")
                        )
                            ?: fixUrlNull(
                                it
                                    .selectFirst("img")
                                    ?.attr("src")
                            )

                    newTvSeriesSearchResponse(
                        recName,
                        recHref,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = recPoster
                    }
                }

        val trailer =
            document
                .selectFirst("div.post-info-trailer button")
                ?.attr("data-modal")
                ?.substringAfter("trailer/", "")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    "https://www.youtube.com/watch?v=$it"
                }

        if (isSeries) {

            val episodes =
                document
                    .select("div.seasons-tab-content a")
                    .mapNotNull {

                        val epName =
                            it
                                .selectFirst("h4")
                                ?.text()
                                ?.trim()
                                ?: return@mapNotNull null

                        val epHref =
                            fixUrlNull(
                                it.attr("href")
                            ) ?: return@mapNotNull null

                        val epEpisode =
                            Regex(
                                """(\d+)\.?\s*Bölüm"""
                            )
                                .find(epName)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()

                        val epSeason =
                            Regex(
                                """(\d+)\.?\s*Sezon"""
                            )
                                .find(epName)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 1

                        newEpisode(epHref) {
                            this.name = epName
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private data class DecodeStep(
        val op: String,
        val a: Int = 0,
        val b: Int = 0
    )

    private data class ParsedDecoder(
        val steps: List<DecodeStep>,
        val parts: List<String>
    )

    private fun decodeText(
        value: String
    ): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)
            .trim()
    }

    private fun parseInlineDecoders(
        html: String
    ): List<ParsedDecoder> {

        val result =
            mutableListOf<ParsedDecoder>()

        val functionRegex =
            Regex(
                """function\s+(dc_\w+)\s*\(\s*[\w${'$'}]+\s*\)\s*\{([\s\S]*?)\n\}"""
            )

        for (functionMatch in functionRegex.findAll(html)) {

            val functionName =
                functionMatch.groupValues[1]

            val body =
                functionMatch.groupValues[2]

            val callRegex =
                Regex(
                    """${Regex.escape(functionName)}\s*\(\s*\[([^\]]+)\]\s*\)"""
                )

            val call =
                callRegex.find(html)
                    ?: continue

            val quoted =
                Regex("\"([^\"]*)\"")
                    .findAll(call.groupValues[1])
                    .map {
                        decodeText(it.groupValues[1])
                    }
                    .toList()

            if (quoted.isEmpty()) {
                continue
            }

            val operations =
                mutableListOf<Pair<Int, DecodeStep>>()

            Regex("""=\s*atob\(\s*result\s*\)""")
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep("base64")
                    )
                }

            Regex(
                """result\.split\(''\)\.reverse\(\)\.join\(''\)"""
            )
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep("reverse")
                    )
                }

            Regex(
                """\(o\s*-\s*base\s*\+\s*(\d+)\)\s*%\s*26"""
            )
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep(
                                "rot",
                                it.groupValues[1].toIntOrNull() ?: 0
                            )
                    )
                }

            Regex("""charCodeAt\(0\)\s*\+\s*(\d+)""")
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep(
                                "rot",
                                it.groupValues[1].toIntOrNull() ?: 0
                            )
                    )
                }

            Regex(
                """charCode\s*-\s*\((\d+)\s*%\s*\(i\s*\+\s*(\d+)\)\)"""
            )
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep(
                                "unmix",
                                it.groupValues[1].toIntOrNull() ?: 0,
                                it.groupValues[2].toIntOrNull() ?: 0
                            )
                    )
                }

            Regex(
                """(?:var|let|const)\s+acc\s*=\s*(\d+)[\s\S]{0,260}?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256"""
            )
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep(
                                "xor",
                                it.groupValues[1].toIntOrNull() ?: 0,
                                it.groupValues[2].toIntOrNull() ?: 0
                            )
                    )
                }

            if (operations.isEmpty()) {
                continue
            }

            result.add(
                ParsedDecoder(
                    steps = operations
                        .sortedBy { it.first }
                        .map { it.second },
                    parts = quoted
                )
            )
        }

        return result
    }

    private fun applyDecodeSteps(
        parts: List<String>,
        steps: List<DecodeStep>
    ): String {

        var value = parts.joinToString("")

        for (step in steps) {

            when (step.op) {

                "base64" -> {
                    var padded = value

                    while (padded.length % 4 != 0) {
                        padded += "="
                    }

                    value =
                        runCatching {
                            String(
                                Base64.decode(
                                    padded,
                                    Base64.NO_WRAP
                                ),
                                Charsets.ISO_8859_1
                            )
                        }.getOrElse {
                            return ""
                        }
                }

                "reverse" -> {
                    value = value.reversed()
                }

                "rot" -> {

                    val shift =
                        ((step.a % 26) + 26) % 26

                    value =
                        buildString(value.length) {
                            for (c in value) {
                                when {
                                    c in 'a'..'z' -> {
                                        val n =
                                            (c.code - 'a'.code + shift) % 26
                                        append(
                                            ('a'.code + n).toChar()
                                        )
                                    }

                                    c in 'A'..'Z' -> {
                                        val n =
                                            (c.code - 'A'.code + shift) % 26
                                        append(
                                            ('A'.code + n).toChar()
                                        )
                                    }

                                    else -> append(c)
                                }
                            }
                        }
                }

                "unmix" -> {

                    val offset = step.b

                    if (offset <= 0) {
                        return ""
                    }

                    val out =
                        StringBuilder(value.length)

                    for (i in value.indices) {

                        val charCode =
                            value[i].code.toLong()

                        val divisor =
                            i + offset

                        if (divisor <= 0) {
                            return ""
                        }

                        val plain =
                            (
                                charCode -
                                    (step.a.toLong() % divisor) +
                                    256L
                            ) % 256L

                        out.append(
                            plain.toInt().toChar()
                        )
                    }

                    value = out.toString()
                }

                "xor" -> {

                    var acc = step.a
                    val out = StringBuilder(value.length)

                    for (c in value) {

                        val byte = c.code and 0xFF

                        acc =
                            (acc + step.b) % 256

                        out.append(
                            (byte xor acc).toChar()
                        )

                        acc =
                            (acc + byte) % 256
                    }

                    value = out.toString()
                }
            }
        }

        return value
    }

    private fun isValidVideoUrl(
        value: String
    ): Boolean {

        val url = value.trim()

        if (
            !url.startsWith("https://") &&
            !url.startsWith("http://")
        ) {
            return false
        }

        val lower = url.lowercase()

        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.contains("master.txt") ||
            lower.endsWith(".mp4") ||
            lower.contains(".mp4?")
    }

    private fun cleanUrl(
        value: String
    ): String {

        return decodeText(value)
            .replace("\\\"", "\"")
            .trim()
            .trim(
                '"',
                '\'',
                '`',
                '\\'
            )
            .trimEnd(
                ')',
                ']',
                '}',
                ';',
                ',',
                '\\'
            )
    }

    private fun normalizePlayerUrl(
        raw: String?
    ): String? {

        if (raw.isNullOrBlank()) {
            return null
        }

        var value =
            decodeText(raw)
                .replace("\\\"", "\"")
                .trim()

        value =
            value.trim(
                '"',
                '\'',
                '`',
                '\\'
            )

        val urls =
            Regex(
                """https?://[^\s"'<>\\]+""",
                RegexOption.IGNORE_CASE
            )
                .findAll(value)
                .map { it.value }
                .toList()

        val candidate =
            urls.lastOrNull()
                ?: if (value.startsWith("//")) {
                    "https:$value"
                } else {
                    value
                }

        val cleaned = cleanUrl(candidate)

        return fixUrlNull(cleaned)
    }

    private fun originOf(
        url: String
    ): String {

        return runCatching {

            val uri = java.net.URI(url)
            val scheme = uri.scheme
            val host = uri.host

            if (
                scheme.isNullOrBlank() ||
                host.isNullOrBlank()
            ) {
                ""
            } else {
                "$scheme://$host"
            }
        }.getOrDefault("")
    }

    private fun resolvePlayerOrigin(
        source: String,
        playerUrl: String
    ): String {

        val playerOrigin = originOf(playerUrl)

        if (
            playerOrigin.isNotBlank() &&
            !playerUrl.contains(
                "/rplayer/",
                ignoreCase = true
            ) &&
            !playerUrl.contains(
                "/playerr/",
                ignoreCase = true
            )
        ) {
            return playerOrigin
        }

        if (
            playerUrl.contains(
                "/rplayer/",
                ignoreCase = true
            ) ||
            playerUrl.contains(
                "/playerr/",
                ignoreCase = true
            )
        ) {
            return originOf(mainUrl)
        }

        if (playerOrigin.isNotBlank()) {
            return playerOrigin
        }

        return defaultEmbedOrigin
    }

    private fun resolveMediaReferer(
        source: String,
        playerUrl: String
    ): String {

        val origin =
            resolvePlayerOrigin(
                source,
                playerUrl
            )

        return origin.trimEnd('/') + "/"
    }

    private fun mediaHeaders(
        source: String,
        playerUrl: String
    ): Map<String, String> {

        val origin =
            resolvePlayerOrigin(
                source,
                playerUrl
            )

        val referer =
            origin.trimEnd('/') + "/"

        return mapOf(
            "User-Agent" to mediaUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to
                "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Origin" to origin
        )
    }

    private fun mediaTypeForUrl(
        url: String
    ): ExtractorLinkType {

        val lower = url.lowercase()

        return if (
            lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.contains("master.txt")
        ) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
    }

    private fun extractCandidateUrls(
        html: String
    ): List<String> {

        val result = LinkedHashSet<String>()
        val normalized = decodeText(html)

        val regex =
            Regex(
                """https?://[^\s"'<>\\]+(?:\.m3u8|\.mp4|master\.txt)(?:\?[^\s"'<>\\]+)?""",
                RegexOption.IGNORE_CASE
            )

        for (match in regex.findAll(normalized)) {

            val url = cleanUrl(match.value)

            if (isValidVideoUrl(url)) {
                result.add(url)
            }
        }

        return result.toList()
    }

    private suspend fun addPlayerSubtitles(
        html: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {

        val tracksMatch =
            Regex(
                """tracks\s*:\s*(\[[\s\S]*?\])"""
            )
                .find(html)

        if (tracksMatch != null) {

            val block =
                tracksMatch
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()

            val trackRegex =
                Regex(
                    """\{\s*(?:[^{}]*?)?(?:file|src)\s*:\s*[\"']([^\"']+)[\"'][^{}]*?(?:label|language|srclang)\s*:\s*[\"']([^\"']+)[\"'][^{}]*?\}""",
                    RegexOption.IGNORE_CASE
                )

            for (match in trackRegex.findAll(block)) {

                val raw =
                    decodeText(match.groupValues[1])

                val subtitleUrl =
                    try {
                        java.net.URI(playerUrl)
                            .resolve(raw)
                            .toString()
                    } catch (_: Exception) {
                        fixUrlNull(raw)
                    }

                if (subtitleUrl.isNullOrBlank()) {
                    continue
                }

                val language =
                    match.groupValues
                        .getOrNull(2)
                        .orEmpty()
                        .trim()
                        .ifBlank { "Türkçe" }

                subtitleCallback(
                    newSubtitleFile(
                        language,
                        subtitleUrl
                    )
                )
            }
        }

        val document = Jsoup.parse(html)

        for (track in document.select("video track")) {

            val raw =
                track.attr("src").trim()

            if (raw.isBlank()) {
                continue
            }

            val subtitleUrl =
                try {
                    java.net.URI(playerUrl)
                        .resolve(raw)
                        .toString()
                } catch (_: Exception) {
                    fixUrlNull(raw)
                }

            if (subtitleUrl.isNullOrBlank()) {
                continue
            }

            val language =
                track
                    .attr("label")
                    .ifBlank {
                        track.attr("srclang")
                    }
                    .ifBlank {
                        "Türkçe"
                    }

            subtitleCallback(
                newSubtitleFile(
                    language,
                    subtitleUrl
                )
            )
        }
    }

    private suspend fun emitVideoLink(
        source: String,
        playerUrl: String,
        videoUrl: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val clean = cleanUrl(videoUrl)

        if (!isValidVideoUrl(clean)) {
            return false
        }

        val referer =
            resolveMediaReferer(
                source,
                playerUrl
            )

        val origin =
            resolvePlayerOrigin(
                source,
                playerUrl
            )

        /*
         * master.txt is already the HLS media playlist.
         * Chrome receives it as application/vnd.apple.mpegurl
         * and the response body starts with #EXTM3U.
         *
         * Do not download master.txt and search for another
         * .m3u8 URL. Pass master.txt directly as M3U8.
         */
        val type =
            if (
                clean.contains(
                    "master.txt",
                    ignoreCase = true
                )
            ) {
                ExtractorLinkType.M3U8
            } else {
                mediaTypeForUrl(clean)
            }

        val linkName =
            if (suffix.isBlank()) {
                source
            } else {
                "$source $suffix"
            }

        Log.d(
            "HDFilmCehennemi",
            "VIDEO URL=$clean"
        )

        Log.d(
            "HDFilmCehennemi",
            "VIDEO TYPE=$type"
        )

        Log.d(
            "HDFilmCehennemi",
            "VIDEO REFERER=$referer"
        )

        Log.d(
            "HDFilmCehennemi",
            "VIDEO ORIGIN=$origin"
        )

        Log.d(
            "HDFilmCehennemi",
            "VIDEO MEDIA UA=$mediaUserAgent"
        )

        callback(
            newExtractorLink(
                source = linkName,
                name = linkName,
                url = clean,
                type = type
            ) {

                this.referer = referer

                this.headers =
                    mediaHeaders(
                        source,
                        playerUrl
                    )

                quality =
                    Qualities.Unknown.value
            }
        )

        return true
    }

    private suspend fun extractFromPlayer(
        source: String,
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "HDFilmCehennemi",
            "PLAYER=$playerUrl"
        )

        val referer =
            resolveMediaReferer(
                source,
                playerUrl
            )

        val response =
            runCatching {
                app.get(
                    playerUrl,
                    headers = browserHeaders,
                    referer = referer,
                    allowRedirects = true,
                    interceptor = interceptor
                )
            }.getOrNull()
                ?: run {
                    Log.e(
                        "HDFilmCehennemi",
                        "Player GET başarısız=$playerUrl"
                    )
                    return false
                }

        val html = response.text

        if (html.isBlank()) {
            Log.e(
                "HDFilmCehennemi",
                "Player HTML boş=$playerUrl"
            )
            return false
        }

        Log.d(
            "HDFilmCehennemi",
            "PLAYER HTML LENGTH=${html.length}"
        )

        addPlayerSubtitles(
            html,
            playerUrl,
            subtitleCallback
        )

        val inlineCandidates =
            LinkedHashSet<String>()

        val inlineDecoders =
            parseInlineDecoders(html)

        Log.d(
            "HDFilmCehennemi",
            "INLINE DECODER COUNT=${inlineDecoders.size}"
        )

        for (decoder in inlineDecoders) {

            val decoded =
                runCatching {
                    applyDecodeSteps(
                        decoder.parts,
                        decoder.steps
                    )
                }.getOrDefault("")

            val clean = cleanUrl(decoded)

            Log.d(
                "HDFilmCehennemi",
                "INLINE RESULT=$clean"
            )

            if (isValidVideoUrl(clean)) {
                inlineCandidates.add(clean)
            }
        }

        if (inlineCandidates.isNotEmpty()) {

            var emittedCount = 0
            var index = 1

            for (videoUrl in inlineCandidates) {

                if (
                    emitVideoLink(
                        source = source,
                        playerUrl = playerUrl,
                        videoUrl = videoUrl,
                        suffix = "Inline $index",
                        callback = callback
                    )
                ) {
                    emittedCount++
                }

                index++
            }

            if (emittedCount > 0) {
                return true
            }
        }

        val unpacked =
            runCatching {
                getAndUnpack(html)
            }.getOrNull()

        if (
            !unpacked.isNullOrBlank() &&
            unpacked != html
        ) {

            val packedDecoders =
                parseInlineDecoders(unpacked)

            Log.d(
                "HDFilmCehennemi",
                "PACKED DECODER COUNT=${packedDecoders.size}"
            )

            val packedCandidates =
                LinkedHashSet<String>()

            for (decoder in packedDecoders) {

                val decoded =
                    runCatching {
                        applyDecodeSteps(
                            decoder.parts,
                            decoder.steps
                        )
                    }.getOrDefault("")

                val clean = cleanUrl(decoded)

                Log.d(
                    "HDFilmCehennemi",
                    "PACKED RESULT=$clean"
                )

                if (isValidVideoUrl(clean)) {
                    packedCandidates.add(clean)
                }
            }

            if (packedCandidates.isNotEmpty()) {

                var emittedCount = 0
                var index = 1

                for (videoUrl in packedCandidates) {

                    if (
                        emitVideoLink(
                            source = source,
                            playerUrl = playerUrl,
                            videoUrl = videoUrl,
                            suffix = "Packed $index",
                            callback = callback
                        )
                    ) {
                        emittedCount++
                    }

                    index++
                }

                if (emittedCount > 0) {
                    return true
                }
            }
        }

        val directCandidates =
            extractCandidateUrls(html)

        if (directCandidates.isNotEmpty()) {

            var emittedCount = 0
            var index = 1

            for (videoUrl in directCandidates) {

                if (
                    emitVideoLink(
                        source = source,
                        playerUrl = playerUrl,
                        videoUrl = videoUrl,
                        suffix = "Direct $index",
                        callback = callback
                    )
                ) {
                    emittedCount++
                }

                index++
            }

            if (emittedCount > 0) {
                return true
            }
        }

        val jsonLdUrl =
            Regex(
                """[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeText)

        if (!jsonLdUrl.isNullOrBlank()) {

            val clean = cleanUrl(jsonLdUrl)

            Log.d(
                "HDFilmCehennemi",
                "JSON-LD FALLBACK=$clean"
            )

            if (
                isValidVideoUrl(clean) &&
                emitVideoLink(
                    source = source,
                    playerUrl = playerUrl,
                    videoUrl = clean,
                    suffix = "JSON-LD",
                    callback = callback
                )
            ) {
                return true
            }
        }

        Log.e(
            "HDFilmCehennemi",
            "VIDEO BULUNAMADI player=$playerUrl page=$pageUrl"
        )

        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "HDFilmCehennemi",
            "LOAD LINKS=$data"
        )

        val pageDocument =
            runCatching {
                app.get(
                    data,
                    headers = browserHeaders,
                    referer = "$mainUrl/",
                    interceptor = interceptor
                ).document
            }.getOrNull()
                ?: run {
                    Log.e(
                        "HDFilmCehennemi",
                        "Ana video sayfası alınamadı=$data"
                    )
                    return false
                }

        var found = false

        val alternativeBlocks =
            pageDocument.select(
                "div.alternative-links"
            )

        Log.d(
            "HDFilmCehennemi",
            "ALTERNATIVE BLOCKS=${alternativeBlocks.size}"
        )

        alternativeBlocks.forEach { element ->

            val langCode =
                element
                    .attr("data-lang")
                    .uppercase()
                    .ifBlank { "TR" }

            val buttons =
                element.select(
                    "button.alternative-link"
                )

            buttons.forEach { button ->

                val source =
                    "${
                        button
                            .text()
                            .replace(
                                "(HDrip Xbet)",
                                ""
                            )
                            .trim()
                    } $langCode".trim()

                val videoId =
                    button
                        .attr("data-video")
                        .trim()

                if (videoId.isBlank()) {
                    return@forEach
                }

                Log.d(
                    "HDFilmCehennemi",
                    "SOURCE=$source VIDEO_ID=$videoId"
                )

                val apiHtml =
                    runCatching {
                        app.get(
                            "${mainUrl}/video/$videoId/",
                            headers = mapOf(
                                "Content-Type" to
                                    "application/json",
                                "X-Requested-With" to
                                    "fetch"
                            ),
                            referer = data,
                            interceptor = interceptor
                        ).text
                    }.getOrNull()
                        ?: run {
                            Log.e(
                                "HDFilmCehennemi",
                                "Video API alınamadı=$videoId"
                            )
                            return@forEach
                        }

                val playerCandidates =
                    LinkedHashSet<String>()

                val dataSrcRaw =
                    Regex(
                        """data-src\s*=\s*\\?[\"']([^\"']+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(apiHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                val dataSrc =
                    normalizePlayerUrl(
                        dataSrcRaw
                    )

                if (!dataSrc.isNullOrBlank()) {
                    playerCandidates.add(dataSrc)
                }

                val apiDocument =
                    Jsoup.parse(apiHtml)

                val frames =
                    apiDocument.select(
                        "iframe[data-src], iframe[src]"
                    )

                for (frame in frames) {

                    val frameUrl =
                        normalizePlayerUrl(
                            frame
                                .attr("data-src")
                                .ifBlank {
                                    frame.attr("src")
                                }
                                .trim()
                        )

                    if (!frameUrl.isNullOrBlank()) {
                        playerCandidates.add(frameUrl)
                    }
                }

                val rapidrameId =
                    Regex(
                        """rapidrame_id=([^&\"']+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(apiHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                if (!rapidrameId.isNullOrBlank()) {

                    playerCandidates.add(
                        "${mainUrl}/rplayer/$rapidrameId/"
                    )

                    playerCandidates.add(
                        "${mainUrl}/playerr/$rapidrameId"
                    )
                }

                val rpMatch =
                    Regex(
                        """/(?:rplayer|playerr)/([^/?#]+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(apiHtml)

                if (rpMatch != null) {

                    val id = rpMatch.groupValues[1]

                    if (id.isNotBlank()) {
                        playerCandidates.add(
                            "${mainUrl}/rplayer/$id/"
                        )
                        playerCandidates.add(
                            "${mainUrl}/playerr/$id"
                        )
                    }
                }

                Log.d(
                    "HDFilmCehennemi",
                    "PLAYER CANDIDATES=$playerCandidates"
                )

                for (playerUrl in playerCandidates) {

                    Log.d(
                        "HDFilmCehennemi",
                        "TRY PLAYER=$playerUrl"
                    )

                    val success =
                        extractFromPlayer(
                            source = source,
                            playerUrl = playerUrl,
                            pageUrl = data,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )

                    if (success) {

                        found = true

                        break
                    }
                }
            }
        }

        Log.d(
            "HDFilmCehennemi",
            "LOAD LINKS RESULT=$found"
        )

        return found
    }

    private data class SubSource(
        @JsonProperty("file")
        val file: String? = null,
        @JsonProperty("label")
        val label: String? = null,
        @JsonProperty("language")
        val language: String? = null,
        @JsonProperty("kind")
        val kind: String? = null
    )

    data class Results(
        @JsonProperty("results")
        val results: List<String> =
            arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html")
        val html: String,
        @JsonProperty("meta")
        val meta: Meta
    )

    data class Meta(
        @JsonProperty("title")
        val title: String,
        @JsonProperty("canonical")
        val canonical: String,
        @JsonProperty("keywords")
        val keywords: Boolean
    )
}
