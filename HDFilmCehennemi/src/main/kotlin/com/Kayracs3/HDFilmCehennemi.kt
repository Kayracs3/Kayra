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

import java.net.URI
import java.net.URLDecoder

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

    companion object {
        private const val MAX_EXTERNAL_SCRIPTS = 10
        private const val MAX_DYNAMIC_ENDPOINTS = 12
        private const val MAX_DYNAMIC_RESPONSES = 12
        private const val MAX_JW_CONTEXT = 30000
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {

        override fun intercept(
            chain: Interceptor.Chain
        ): Response {

            val request =
                chain.request()

            val response =
                chain.proceed(request)

            val body =
                response
                    .peekBody(1024 * 1024)
                    .string()

            val doc =
                Jsoup.parse(body)

            if (
                doc.html().contains(
                    "Just a moment",
                    ignoreCase = true
                )
            ) {
                response.close()

                return cloudflareKiller.intercept(chain)
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
                "Accept" to "*/*",
                "X-Requested-With" to "fetch"
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
            Jsoup.parse(data.html)

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
            attr("title")
                .trim()

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
                    "X-Requested-With" to "fetch"
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
                .map {
                    it.text().trim()
                }

        val year =
            document
                .selectFirst(
                    "div.post-info-year-country a"
                )
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val isSeries =
            document
                .select("div.seasons")
                .isNotEmpty()

        val description =
            document
                .selectFirst(
                    "article.post-info-content > p"
                )
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
                        it
                            .select("img")
                            .attr("data-src")
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
                        this.posterUrl =
                            recPoster
                    }
                }

        val trailerId =
            document
                .selectFirst(
                    "div.post-info-trailer button"
                )
                ?.attr("data-modal")
                ?.substringAfter(
                    "trailer/",
                    ""
                )
                ?.trim()

        val trailer =
            trailerId
                ?.takeIf {
                    it.isNotBlank() &&
                        it != "0"
                }
                ?.let {
                    "https://www.youtube.com/watch?v=$it"
                }

        if (isSeries) {

            val episodes =
                document
                    .select(
                        "div.seasons-tab-content a"
                    )
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
                "\\u003F",
                "?",
                ignoreCase = true
            )
            .replace(
                "\\u003D",
                "=",
                ignoreCase = true
            )
            .replace(
                "\\x2F",
                "/",
                ignoreCase = true
            )
            .replace(
                "\\x3A",
                ":",
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
            .trim()
    }

    private fun decodeUrlEncoded(
        value: String
    ): String {

        return runCatching {
            URLDecoder.decode(
                value,
                "UTF-8"
            )
        }.getOrDefault(value)
    }

    private fun decodeBase64Text(
        value: String
    ): String {

        return runCatching {

            var padded =
                value.trim()

            while (
                padded.length % 4 != 0
            ) {
                padded += "="
            }

            String(
                Base64.decode(
                    padded,
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )

        }.getOrElse {

            runCatching {

                var padded =
                    value.trim()

                while (
                    padded.length % 4 != 0
                ) {
                    padded += "="
                }

                String(
                    Base64.decode(
                        padded,
                        Base64.NO_WRAP
                    ),
                    Charsets.ISO_8859_1
                )

            }.getOrDefault("")
        }
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

        for (
            functionMatch in
            functionRegex.findAll(html)
        ) {

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
                    .findAll(
                        call.groupValues[1]
                    )
                    .map {
                        decodeText(
                            it.groupValues[1]
                        )
                    }
                    .toList()

            if (quoted.isEmpty()) {
                continue
            }

            val operations =
                mutableListOf<Pair<Int, DecodeStep>>()

            Regex(
                """=\s*atob\(\s*result\s*\)"""
            )
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
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0
                            )
                    )
                }

            Regex(
                """charCodeAt\(0\)\s*\+\s*(\d+)"""
            )
                .findAll(body)
                .forEach {
                    operations.add(
                        it.range.first to
                            DecodeStep(
                                "rot",
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0
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
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0,
                                it.groupValues[2]
                                    .toIntOrNull()
                                    ?: 0
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
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0,
                                it.groupValues[2]
                                    .toIntOrNull()
                                    ?: 0
                            )
                    )
                }

            if (operations.isEmpty()) {
                continue
            }

            result.add(
                ParsedDecoder(
                    steps =
                        operations
                            .sortedBy {
                                it.first
                            }
                            .map {
                                it.second
                            },
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

        var value =
            parts.joinToString("")

        for (step in steps) {

            when (step.op) {

                "base64" -> {

                    var padded =
                        value

                    while (
                        padded.length % 4 != 0
                    ) {
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
                    value =
                        value.reversed()
                }

                "rot" -> {

                    val shift =
                        (
                            (step.a % 26) + 26
                            ) % 26

                    value =
                        buildString(
                            value.length
                        ) {

                            for (c in value) {

                                when {

                                    c in 'a'..'z' -> {

                                        val n =
                                            (
                                                c.code -
                                                    'a'.code +
                                                    shift
                                                ) % 26

                                        append(
                                            (
                                                'a'.code +
                                                    n
                                                ).toChar()
                                        )
                                    }

                                    c in 'A'..'Z' -> {

                                        val n =
                                            (
                                                c.code -
                                                    'A'.code +
                                                    shift
                                                ) % 26

                                        append(
                                            (
                                                'A'.code +
                                                    n
                                                ).toChar()
                                        )
                                    }

                                    else -> {
                                        append(c)
                                    }
                                }
                            }
                        }
                }

                "unmix" -> {

                    val offset =
                        step.b

                    if (offset <= 0) {
                        return ""
                    }

                    val out =
                        StringBuilder(
                            value.length
                        )

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
                                    (
                                        step.a.toLong() %
                                            divisor
                                        ) +
                                    256L
                                ) % 256L

                        out.append(
                            plain.toInt().toChar()
                        )
                    }

                    value =
                        out.toString()
                }

                "xor" -> {

                    var acc =
                        step.a

                    val out =
                        StringBuilder(
                            value.length
                        )

                    for (c in value) {

                        val byte =
                            c.code and 0xFF

                        acc =
                            (
                                acc + step.b
                                ) % 256

                        out.append(
                            (
                                byte xor acc
                                ).toChar()
                        )

                        acc =
                            (
                                acc + byte
                                ) % 256
                    }

                    value =
                        out.toString()
                }
            }
        }

        return value
    }

    private fun isRejectedMediaUrl(
        value: String
    ): Boolean {

        val lower =
            value.lowercase()

        return lower.contains(
            ".mp4/master.txt"
        ) ||
            lower.contains(
                "/master.txt"
            )
    }

    private fun isValidVideoUrl(
        value: String
    ): Boolean {

        val url =
            value.trim()

        if (
            !url.startsWith("https://") &&
            !url.startsWith("http://")
        ) {
            return false
        }

        if (
            isRejectedMediaUrl(url)
        ) {
            return false
        }

        val lower =
            url.lowercase()

        return lower.contains(".m3u8") ||
            lower.endsWith(".mp4") ||
            lower.contains(".mp4?") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/")
    }

    private fun isHlsCandidate(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        if (
            lower.contains(".mp4")
        ) {
            return false
        }

        if (
            isRejectedMediaUrl(url)
        ) {
            return false
        }

        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/")
    }

    private fun cleanUrl(
        value: String
    ): String {

        return decodeText(value)
            .replace(
                "\\\"",
                "\""
            )
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

        val value =
            decodeText(raw)
                .replace(
                    "\\\"",
                    "\""
                )
                .trim()

        val cleanedValue =
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
                .findAll(
                    cleanedValue
                )
                .map {
                    it.value
                }
                .toList()

        val candidate =
            urls.lastOrNull()
                ?: if (
                    cleanedValue.startsWith("//")
                ) {
                    "https:$cleanedValue"
                } else {
                    cleanedValue
                }

        val cleaned =
            cleanUrl(candidate)

        return fixUrlNull(cleaned)
    }

    private fun resolveAbsoluteUrl(
        raw: String?,
        baseUrl: String
    ): String? {

        if (raw.isNullOrBlank()) {
            return null
        }

        val value =
            decodeText(raw)
                .trim()
                .trim(
                    '"',
                    '\'',
                    '`',
                    '\\'
                )

        if (value.isBlank()) {
            return null
        }

        return runCatching {

            URI(baseUrl)
                .resolve(value)
                .toString()

        }.getOrNull()
            ?: fixUrlNull(value)
    }

    private fun originOf(
        url: String
    ): String {

        return runCatching {

            val uri =
                URI(url)

            val scheme =
                uri.scheme

            val host =
                uri.host

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

        val playerOrigin =
            originOf(playerUrl)

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

        if (
            playerOrigin.isNotBlank()
        ) {
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

        return origin
            .trimEnd('/') + "/"
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
            origin
                .trimEnd('/') + "/"

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

        val lower =
            url.lowercase()

        return when {

            lower.contains(".m3u8") ->
                ExtractorLinkType.M3U8

            lower.endsWith(".mp4") ||
                lower.contains(".mp4?") ->
                ExtractorLinkType.VIDEO

            lower.contains("/hls/") ||
                lower.contains("/hls2/") ->
                ExtractorLinkType.M3U8

            else ->
                ExtractorLinkType.VIDEO
        }
    }

    private fun addVideoCandidate(
        result: MutableSet<String>,
        raw: String?,
        baseUrl: String
    ) {

        if (raw.isNullOrBlank()) {
            return
        }

        var value =
            raw.trim()
                .trim(
                    '"',
                    '\'',
                    '`'
                )

        if (value.isBlank()) {
            return
        }

        value =
            decodeText(value)

        val decodedUrl =
            decodeUrlEncoded(value)

        val possibilities =
            LinkedHashSet<String>()

        possibilities.add(value)

        if (
            decodedUrl != value
        ) {
            possibilities.add(
                decodedUrl
            )
        }

        for (
            candidate in
            possibilities
        ) {

            val absolute =
                resolveAbsoluteUrl(
                    candidate,
                    baseUrl
                )

            if (
                absolute.isNullOrBlank()
            ) {
                continue
            }

            val clean =
                cleanUrl(
                    absolute
                )

            if (
                isRejectedMediaUrl(clean)
            ) {
                continue
            }

            if (
                isValidVideoUrl(clean)
            ) {
                result.add(clean)
            }
        }
    }

    private fun extractBase64Candidates(
        value: String,
        result: MutableSet<String>,
        baseUrl: String
    ) {

        if (
            value.isBlank()
        ) {
            return
        }

        val trimmed =
            value.trim()

        if (
            trimmed.length < 8
        ) {
            return
        }

        if (
            !trimmed.matches(
                Regex(
                    """[A-Za-z0-9+/=_-]+"""
                )
            )
        ) {
            return
        }

        val decoded =
            decodeBase64Text(
                trimmed
            )

        if (
            decoded.isBlank() ||
            decoded == trimmed
        ) {
            return
        }

        addVideoCandidate(
            result,
            decoded,
            baseUrl
        )

        /*
         * Base64 içinden URL çıkar.
         */
        Regex(
            """https?://[^\s"'`<>\\]+""",
            RegexOption.IGNORE_CASE
        )
            .findAll(decoded)
            .forEach {

                addVideoCandidate(
                    result,
                    it.value,
                    baseUrl
                )
            }

        /*
         * Base64 içinden file/source.
         */
        Regex(
            """(?is)(?:file|src|source|url|media|stream)\s*[:=]\s*["']([^"']+)["']"""
        )
            .findAll(decoded)
            .forEach {

                addVideoCandidate(
                    result,
                    it.groupValues[1],
                    baseUrl
                )
            }
    }

    private fun extractJavascriptMediaSources(
        text: String,
        baseUrl: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        /*
         * JS değişkenleri
         */
        val variables =
            LinkedHashMap<String, String>()

        val variableRegex =
            Regex(
                """(?is)\b(?:var|let|const)\s+([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\s*=\s*([^;\n]+)"""
            )

        for (
            match in
            variableRegex.findAll(text)
        ) {

            val name =
                match
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()

            val expression =
                match
                    .groupValues
                    .getOrNull(2)
                    .orEmpty()
                    .trim()

            if (
                name.isNotBlank() &&
                expression.isNotBlank()
            ) {
                variables[name] =
                    expression
            }
        }

        /*
         * Bir değeri recursive olarak çöz.
         */
        fun resolveExpression(
            expression: String,
            depth: Int = 0
        ) {

            if (
                depth > 5
            ) {
                return
            }

            var value =
                expression
                    .trim()

            if (
                value.isBlank()
            ) {
                return
            }

            /*
             * Fazla parantez
             */
            while (
                value.startsWith("(") &&
                value.endsWith(")") &&
                value.length > 2
            ) {

                value =
                    value
                        .substring(
                            1,
                            value.length - 1
                        )
                        .trim()
            }

            /*
             * Doğrudan değişken
             */
            if (
                value.matches(
                    Regex(
                        """[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"""
                    )
                )
            ) {

                val variable =
                    variables[value]

                if (
                    !variable.isNullOrBlank() &&
                    variable != value
                ) {

                    resolveExpression(
                        variable,
                        depth + 1
                    )
                }

                return
            }

            /*
             * String.fromCharCode(...)
             */
            val charCodeRegex =
                Regex(
                    """(?is)String\.fromCharCode\s*\(([^)]*)\)"""
                )

            for (
                match in
                charCodeRegex.findAll(value)
            ) {

                val decoded =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                        .split(",")
                        .mapNotNull {
                            it
                                .trim()
                                .toIntOrNull()
                        }
                        .map {
                            it.toChar()
                        }
                        .joinToString("")

                if (
                    decoded.isNotBlank()
                ) {

                    addVideoCandidate(
                        result,
                        decoded,
                        baseUrl
                    )
                }
            }

            /*
             * atob(...)
             */
            val atobRegex =
                Regex(
                    """(?is)(?:window\.)?atob\s*\(\s*([^)]*)\s*\)"""
                )

            for (
                match in
                atobRegex.findAll(value)
            ) {

                val argument =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                        .trim()

                if (
                    argument.matches(
                        Regex(
                            """["'`].*["'`]"""
                        )
                    )
                ) {

                    val encoded =
                        argument
                            .trim()
                            .trim(
                                '"',
                                '\'',
                                '`'
                            )

                    val decoded =
                        decodeBase64Text(
                            encoded
                        )

                    if (
                        decoded.isNotBlank()
                    ) {

                        addVideoCandidate(
                            result,
                            decoded,
                            baseUrl
                        )

                        /*
                         * Decoded içerik tekrar JS olabilir.
                         */
                        if (
                            depth < 3
                        ) {

                            resolveExpression(
                                decoded,
                                depth + 1
                            )
                        }
                    }
                } else {

                    resolveExpression(
                        argument,
                        depth + 1
                    )
                }
            }

            /*
             * decodeURIComponent(...)
             */
            val decodeUriRegex =
                Regex(
                    """(?is)(?:decodeURIComponent|decodeURI)\s*\(\s*([^)]*)\s*\)"""
                )

            for (
                match in
                decodeUriRegex.findAll(value)
            ) {

                val argument =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                        .trim()

                if (
                    argument.matches(
                        Regex(
                            """["'`].*["'`]"""
                        )
                    )
                ) {

                    val decoded =
                        decodeUrlEncoded(
                            argument
                                .trim()
                                .trim(
                                    '"',
                                    '\'',
                                    '`'
                                )
                        )

                    addVideoCandidate(
                        result,
                        decoded,
                        baseUrl
                    )
                } else {

                    resolveExpression(
                        argument,
                        depth + 1
                    )
                }
            }

            /*
             * Direkt quoted string'ler
             */
            val quotedRegex =
                Regex(
                    """["'`]([^"'`]*)["'`]"""
                )

            for (
                match in
                quotedRegex.findAll(value)
            ) {

                val quoted =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()

                if (
                    quoted.isBlank()
                ) {
                    continue
                }

                addVideoCandidate(
                    result,
                    quoted,
                    baseUrl
                )

                /*
                 * Base64 ihtimali
                 */
                extractBase64Candidates(
                    quoted,
                    result,
                    baseUrl
                )
            }

            /*
             * Tam URL'ler
             */
            Regex(
                """https?://[^\s"'`<>()]+""",
                RegexOption.IGNORE_CASE
            )
                .findAll(value)
                .forEach {

                    addVideoCandidate(
                        result,
                        it.value,
                        baseUrl
                    )
                }

            /*
             * Uzantısız HLS
             */
            Regex(
                """(?i)(?:["'`]|^)(?:(?:https?:)?//|/)[^"'`\s<>]+/hls2?/[^"'`\s<>]+"""
            )
                .findAll(value)
                .forEach {

                    addVideoCandidate(
                        result,
                        it.value.trim(
                            '"',
                            '\'',
                            '`'
                        ),
                        baseUrl
                    )
                }
        }

        /*
         * Önce file/source/property alanlarını çöz.
         */
        val propertyRegex =
            Regex(
                """(?is)(?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)\s*:\s*([^,\n}]+)"""
            )

        for (
            match in
            propertyRegex.findAll(text)
        ) {

            resolveExpression(
                match.groupValues[1]
            )
        }

        /*
         * JSON biçimi:
         * "file": ...
         */
        val quotedPropertyRegex =
            Regex(
                """(?is)["'](?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)["']\s*:\s*([^,\n}]+)"""
            )

        for (
            match in
            quotedPropertyRegex.findAll(text)
        ) {

            resolveExpression(
                match.groupValues[1]
            )
        }

        /*
         * Atama:
         * file = ...
         */
        val assignmentRegex =
            Regex(
                """(?is)\b(?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)\s*=\s*([^;\n]+)"""
            )

        for (
            match in
            assignmentRegex.findAll(text)
        ) {

            resolveExpression(
                match.groupValues[1]
            )
        }

        /*
         * jwplayer(...).setup(...)
         *
         * Bloğu ayrı çıkarıyoruz.
         */
        val jwRegex =
            Regex(
                """(?is)jwplayer\s*\([^)]*\)\s*\.\s*setup\s*\(\s*"""
            )

        for (
            match in
            jwRegex.findAll(text)
        ) {

            val start =
                match.range.last + 1

            if (
                start >= text.length
            ) {
                continue
            }

            val end =
                minOf(
                    text.length,
                    start + MAX_JW_CONTEXT
                )

            val jwBlock =
                text.substring(
                    start,
                    end
                )

            Log.d(
                "HDFilmCehennemi",
                "JW SOURCE BLOCK LENGTH=${jwBlock.length}"
            )

            val jwPropertyRegex =
                Regex(
                    """(?is)(?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)\s*:\s*([^,\n}]+)"""
                )

            for (
                property in
                jwPropertyRegex.findAll(jwBlock)
            ) {

                resolveExpression(
                    property
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                )
            }

            /*
             * JW sources array
             */
            val sourceArrayRegex =
                Regex(
                    """(?is)sources?\s*:\s*\[([\s\S]{0,20000})\]"""
                )

            for (
                sourceArray in
                sourceArrayRegex.findAll(jwBlock)
            ) {

                val sourceBody =
                    sourceArray
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()

                for (
                    property in
                    jwPropertyRegex.findAll(sourceBody)
                ) {

                    resolveExpression(
                        property
                            .groupValues
                            .getOrNull(1)
                            .orEmpty()
                    )
                }
            }

            /*
             * JW setup bloğunun içinde atob/decode çağrılarını
             * ayrıca tara.
             */
            resolveExpression(
                jwBlock
            )
        }

        /*
         * Bütün değişkenlerin içeriklerini ayrıca çöz.
         */
        for (
            expression in
            variables.values
        ) {

            resolveExpression(
                expression
            )
        }

        /*
         * Direkt text içindeki atob çağrıları.
         */
        val atobDirect =
            Regex(
                """(?is)(?:window\.)?atob\s*\(\s*["'`]([^"'`]+)["'`]\s*\)"""
            )

        for (
            match in
            atobDirect.findAll(text)
        ) {

            val encoded =
                match
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()

            val decoded =
                decodeBase64Text(
                    encoded
                )

            if (
                decoded.isNotBlank()
            ) {

                addVideoCandidate(
                    result,
                    decoded,
                    baseUrl
                )

                resolveExpression(
                    decoded
                )
            }
        }

        /*
         * decodeURIComponent direkt.
         */
        val uriDirect =
            Regex(
                """(?is)(?:decodeURIComponent|decodeURI)\s*\(\s*["'`]([^"'`]+)["'`]\s*\)"""
            )

        for (
            match in
            uriDirect.findAll(text)
        ) {

            addVideoCandidate(
                result,
                decodeUrlEncoded(
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                ),
                baseUrl
            )
        }

        return result
            .filter {
                isValidVideoUrl(it) &&
                    !isRejectedMediaUrl(it)
            }
            .distinct()
    }

    private fun extractStructuredVideoUrls(
        html: String,
        playerUrl: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        fun addCandidate(
            raw: String?
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            val absolute =
                resolveAbsoluteUrl(
                    raw,
                    playerUrl
                ) ?: return

            val clean =
                cleanUrl(
                    absolute
                )

            if (
                !isRejectedMediaUrl(clean) &&
                isValidVideoUrl(clean)
            ) {
                result.add(clean)
            }
        }

        val document =
            Jsoup.parse(html)

        val selectors =
            listOf(
                "video[src]",
                "video[data-src]",
                "video[data-file]",
                "video[data-video]",
                "video[data-url]",
                "source[src]",
                "source[data-src]",
                "source[data-file]",
                "source[data-video]",
                "source[data-url]",
                "[data-video-url]",
                "[data-stream]",
                "[data-stream-url]",
                "[data-hls]",
                "[data-hls-url]",
                "[data-m3u8]",
                "[data-playlist]",
                "[data-playlist-url]",
                "[data-file]",
                "[data-url]"
            )

        for (
            element in
            document.select(
                selectors.joinToString(",")
            )
        ) {

            val attributes =
                listOf(
                    "src",
                    "data-src",
                    "data-file",
                    "data-video",
                    "data-url",
                    "data-video-url",
                    "data-stream",
                    "data-stream-url",
                    "data-hls",
                    "data-hls-url",
                    "data-m3u8",
                    "data-playlist",
                    "data-playlist-url",
                    "file",
                    "url"
                )

            for (
                attr in
                attributes
            ) {

                val value =
                    element.attr(attr)

                if (
                    value.isNotBlank()
                ) {
                    addCandidate(
                        value
                    )
                }
            }
        }

        for (
            script in
            document.select("script")
        ) {

            val scriptText =
                script
                    .data()
                    .ifBlank {
                        script.html()
                    }

            if (
                scriptText.isBlank()
            ) {
                continue
            }

            result.addAll(
                extractJavascriptMediaSources(
                    scriptText,
                    playerUrl
                )
            )
        }

        return result
            .filter {
                isValidVideoUrl(it) &&
                    !isRejectedMediaUrl(it)
            }
            .distinct()
    }

    private fun extractDirectVideoUrlsFromText(
        text: String,
        baseUrl: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        val normalized =
            decodeText(text)

        fun add(
            raw: String?
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            val absolute =
                resolveAbsoluteUrl(
                    raw,
                    baseUrl
                ) ?: return

            val clean =
                cleanUrl(
                    absolute
                )

            if (
                isRejectedMediaUrl(clean)
            ) {
                return
            }

            if (
                isValidVideoUrl(clean)
            ) {
                result.add(clean)
            }
        }

        val mediaRegex =
            Regex(
                """https?://[^\s"'<>\\]+(?:\.m3u8(?:\?[^\s"'<>\\]+)?|\.mp4(?:\?[^\s"'<>\\]+)?)(?!/master\.txt)""",
                RegexOption.IGNORE_CASE
            )

        for (
            match in
            mediaRegex.findAll(normalized)
        ) {
            add(
                match.value
            )
        }

        /*
         * Burada yalnızca gerçek uzantısız HLS
         * adreslerini kabul ediyoruz.
         */
        val hlsRegex =
            Regex(
                """(?i)(?:["'`]|^)(?:(?:https?:)?//|/)[^"'`\s<>]+/hls2?/(?![^"'`\s<>]*\.mp4/master\.txt)[^"'`\s<>]+"""
            )

        for (
            match in
            hlsRegex.findAll(normalized)
        ) {

            add(
                match.value.trim(
                    '"',
                    '\'',
                    '`'
                )
            )
        }

        result.addAll(
            extractJavascriptMediaSources(
                normalized,
                baseUrl
            )
        )

        return result
            .filter {
                isValidVideoUrl(it) &&
                    !isRejectedMediaUrl(it)
            }
            .distinct()
    }

    private fun isLikelyDynamicEndpoint(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        if (
            !lower.startsWith("http://") &&
            !lower.startsWith("https://")
        ) {
            return false
        }

        if (
            isValidVideoUrl(url)
        ) {
            return false
        }

        if (
            lower.endsWith(".js") ||
            lower.contains(".js?") ||
            lower.endsWith(".css") ||
            lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2")
        ) {
            return false
        }

        val uri =
            runCatching {
                URI(url)
            }.getOrNull()

        val path =
            uri
                ?.path
                ?.lowercase()
                .orEmpty()

        return url.contains("?") ||
            path.contains("/api") ||
            path.contains("/ajax") ||
            path.contains("/source") ||
            path.contains("/stream") ||
            path.contains("/player") ||
            path.contains("/video") ||
            path.contains("/embed") ||
            path.contains("/get")
    }

    private fun extractScriptEndpoints(
        html: String,
        baseUrl: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        fun addEndpoint(
            raw: String?
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            val absolute =
                resolveAbsoluteUrl(
                    raw,
                    baseUrl
                ) ?: return

            val clean =
                cleanUrl(
                    absolute
                )

            if (
                isLikelyDynamicEndpoint(
                    clean
                )
            ) {
                result.add(clean)
            }
        }

        val patterns =
            listOf(

                Regex(
                    """(?i)\bfetch\s*\(\s*["'`]([^"'`]+)["'`]"""
                ),

                Regex(
                    """(?i)\b(?:axios|jquery|\$)\s*\.\s*(?:get|post)\s*\(\s*["'`]([^"'`]+)["'`]"""
                ),

                Regex(
                    """(?i)\baxios\s*\(\s*\{\s*url\s*:\s*["'`]([^"'`]+)["'`]"""
                ),

                Regex(
                    """(?i)\bopen\s*\(\s*["']GET["']\s*,\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)\b(?:url|endpoint|apiUrl|api_url|requestUrl|request_url|sourceEndpoint|source_endpoint)\s*[:=]\s*["'`]([^"'`]+)["'`]"""
                )
            )

        for (
            regex in
            patterns
        ) {

            for (
                match in
                regex.findAll(html)
            ) {

                addEndpoint(
                    match.groupValues
                        .getOrNull(1)
                )
            }
        }

        return result
            .take(
                MAX_DYNAMIC_ENDPOINTS
            )
    }

    private suspend fun extractDynamicPlayerSources(
        html: String,
        playerUrl: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        val document =
            Jsoup.parse(html)

        /*
         * Inline JS
         */
        for (
            script in
            document.select("script")
        ) {

            val scriptText =
                script
                    .data()
                    .ifBlank {
                        script.html()
                    }

            if (
                scriptText.isBlank()
            ) {
                continue
            }

            result.addAll(
                extractJavascriptMediaSources(
                    scriptText,
                    playerUrl
                )
            )
        }

        /*
         * External JS
         */
        val externalScripts =
            document
                .select("script[src]")
                .mapNotNull {
                    resolveAbsoluteUrl(
                        it.attr("src"),
                        playerUrl
                    )
                }
                .distinct()
                .take(
                    MAX_EXTERNAL_SCRIPTS
                )

        for (
            scriptUrl in
            externalScripts
        ) {

            Log.d(
                "HDFilmCehennemi",
                "PLAYER SCRIPT=$scriptUrl"
            )

            val scriptText =
                runCatching {

                    app.get(
                        scriptUrl,
                        headers = browserHeaders,
                        referer = playerUrl,
                        allowRedirects = true,
                        interceptor = interceptor
                    ).text

                }.getOrNull()
                    .orEmpty()

            if (
                scriptText.isBlank()
            ) {
                continue
            }

            result.addAll(
                extractJavascriptMediaSources(
                    scriptText,
                    scriptUrl
                )
            )
        }

        /*
         * Endpoint keşfi
         */
        val endpointSet =
            LinkedHashSet<String>()

        endpointSet.addAll(
            extractScriptEndpoints(
                html,
                playerUrl
            )
        )

        for (
            scriptUrl in
            externalScripts
        ) {

            val scriptText =
                runCatching {

                    app.get(
                        scriptUrl,
                        headers = browserHeaders,
                        referer = playerUrl,
                        allowRedirects = true,
                        interceptor = interceptor
                    ).text

                }.getOrNull()
                    .orEmpty()

            if (
                scriptText.isNotBlank()
            ) {

                endpointSet.addAll(
                    extractScriptEndpoints(
                        scriptText,
                        scriptUrl
                    )
                )
            }
        }

        Log.d(
            "HDFilmCehennemi",
            "SCRIPT ENDPOINTS=$endpointSet"
        )

        var responseCount =
            0

        for (
            endpoint in
            endpointSet
        ) {

            if (
                responseCount >=
                MAX_DYNAMIC_RESPONSES
            ) {
                break
            }

            responseCount++

            Log.d(
                "HDFilmCehennemi",
                "DYNAMIC ENDPOINT=$endpoint"
            )

            val endpointBody =
                runCatching {

                    app.get(
                        endpoint,
                        headers = mapOf(
                            "User-Agent" to
                                browserHeaders["User-Agent"].orEmpty(),
                            "Accept" to "*/*",
                            "Accept-Language" to
                                "tr-TR,tr;q=0.9,en;q=0.8",
                            "X-Requested-With" to
                                "XMLHttpRequest",
                            "Referer" to
                                playerUrl
                        ),
                        referer = playerUrl,
                        allowRedirects = true,
                        interceptor = interceptor
                    ).text

                }.getOrNull()

            if (
                endpointBody.isNullOrBlank()
            ) {
                continue
            }

            result.addAll(
                extractDirectVideoUrlsFromText(
                    endpointBody,
                    endpoint
                )
            )

            result.addAll(
                extractStructuredVideoUrls(
                    endpointBody,
                    endpoint
                )
            )

            result.addAll(
                extractJavascriptMediaSources(
                    endpointBody,
                    endpoint
                )
            )

            val unpacked =
                runCatching {
                    getAndUnpack(
                        endpointBody
                    )
                }.getOrNull()

            if (
                !unpacked.isNullOrBlank() &&
                unpacked != endpointBody
            ) {

                result.addAll(
                    extractDirectVideoUrlsFromText(
                        unpacked,
                        endpoint
                    )
                )

                result.addAll(
                    extractStructuredVideoUrls(
                        unpacked,
                        endpoint
                    )
                )

                result.addAll(
                    extractJavascriptMediaSources(
                        unpacked,
                        endpoint
                    )
                )
            }
        }

        return result
            .filter {
                isValidVideoUrl(it) &&
                    !isRejectedMediaUrl(it)
            }
            .distinct()
    }

    private fun extractFallbackVideoUrls(
        html: String
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        val normalized =
            decodeText(html)

        val regex =
            Regex(
                """https?://[^\s"'<>\\]+(?:\.m3u8(?:\?[^\s"'<>\\]+)?|\.mp4(?:\?[^\s"'<>\\]+)?)(?!/master\.txt)""",
                RegexOption.IGNORE_CASE
            )

        for (
            match in
            regex.findAll(normalized)
        ) {

            val url =
                cleanUrl(
                    match.value
                )

            if (
                isRejectedMediaUrl(url)
            ) {
                continue
            }

            if (
                isValidVideoUrl(url)
            ) {
                result.add(url)
            }
        }

        return result.toList()
    }

    private suspend fun prepareHlsUrl(
        url: String,
        headers: Map<String, String>,
        referer: String
    ): String? {

        val clean =
            cleanUrl(url)

        if (
            !isHlsCandidate(clean)
        ) {
            return clean
        }

        if (
            isRejectedMediaUrl(clean)
        ) {
            return null
        }

        return runCatching {

            val response =
                app.get(
                    clean,
                    headers = headers,
                    referer = referer,
                    allowRedirects = true
                )

            val body =
                response.text

            if (
                body.isBlank()
            ) {

                Log.e(
                    "HDFilmCehennemi",
                    "MEDIA PREFLIGHT EMPTY=$clean"
                )

                return@runCatching null
            }

            if (
                !body.contains(
                    "#EXTM3U"
                )
            ) {

                Log.e(
                    "HDFilmCehennemi",
                    "MEDIA PREFLIGHT NOT HLS=$clean"
                )

                return@runCatching null
            }

            if (
                body.contains(
                    "#EXT-X-STREAM-INF"
                )
            ) {

                val variant =
                    body
                        .lineSequence()
                        .map {
                            it.trim()
                        }
                        .firstOrNull {
                            it.isNotBlank() &&
                                !it.startsWith("#") &&
                                (
                                    it.contains(
                                        ".m3u8",
                                        ignoreCase = true
                                    ) ||
                                    it.startsWith("/")
                                )
                        }

                if (
                    !variant.isNullOrBlank()
                ) {

                    val variantUrl =
                        resolveAbsoluteUrl(
                            variant,
                            clean
                        )

                    if (
                        !variantUrl.isNullOrBlank()
                    ) {

                        val variantCheck =
                            runCatching {

                                app.get(
                                    variantUrl,
                                    headers = headers,
                                    referer = referer,
                                    allowRedirects = true
                                ).text

                            }.getOrNull()

                        if (
                            !variantCheck.isNullOrBlank() &&
                            variantCheck.contains(
                                "#EXTM3U"
                            )
                        ) {

                            Log.d(
                                "HDFilmCehennemi",
                                "MEDIA PREFLIGHT MASTER=$clean VARIANT=$variantUrl"
                            )

                            return@runCatching variantUrl
                        }
                    }
                }
            }

            Log.d(
                "HDFilmCehennemi",
                "MEDIA PREFLIGHT OK=$clean"
            )

            clean

        }.getOrElse {

            Log.e(
                "HDFilmCehennemi",
                "MEDIA PREFLIGHT FAIL=$clean ERROR=${it.message}"
            )

            null
        }
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

        if (
            tracksMatch != null
        ) {

            val block =
                tracksMatch
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()

            val trackRegex =
                Regex(
                    """\{\s*(?:[^{}]*?)?(?:file|src)\s*:\s*["']([^"']+)["'][^{}]*?(?:label|language|srclang)\s*:\s*["']([^"']+)["'][^{}]*?\}""",
                    RegexOption.IGNORE_CASE
                )

            for (
                match in
                trackRegex.findAll(block)
            ) {

                val raw =
                    decodeText(
                        match.groupValues[1]
                    )

                val subtitleUrl =
                    resolveAbsoluteUrl(
                        raw,
                        playerUrl
                    )

                if (
                    subtitleUrl.isNullOrBlank()
                ) {
                    continue
                }

                val language =
                    match
                        .groupValues
                        .getOrNull(2)
                        .orEmpty()
                        .trim()
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

        val document =
            Jsoup.parse(html)

        for (
            track in
            document.select(
                "video track"
            )
        ) {

            val raw =
                track
                    .attr("src")
                    .trim()

            if (
                raw.isBlank()
            ) {
                continue
            }

            val subtitleUrl =
                resolveAbsoluteUrl(
                    raw,
                    playerUrl
                )

            if (
                subtitleUrl.isNullOrBlank()
            ) {
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

        val clean =
            cleanUrl(videoUrl)

        if (
            isRejectedMediaUrl(clean)
        ) {

            Log.d(
                "HDFilmCehennemi",
                "STALE MEDIA REJECTED=$clean"
            )

            return false
        }

        if (
            !isValidVideoUrl(clean)
        ) {
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

        val headers =
            mediaHeaders(
                source,
                playerUrl
            )

        val finalUrl =
            if (
                isHlsCandidate(clean)
            ) {

                prepareHlsUrl(
                    clean,
                    headers,
                    referer
                )

            } else {
                clean
            }

        if (
            finalUrl.isNullOrBlank()
        ) {

            Log.e(
                "HDFilmCehennemi",
                "VIDEO REJECTED=$clean"
            )

            return false
        }

        if (
            isRejectedMediaUrl(finalUrl)
        ) {

            Log.e(
                "HDFilmCehennemi",
                "INVALID FINAL URL=$finalUrl"
            )

            return false
        }

        val type =
            mediaTypeForUrl(
                finalUrl
            )

        val linkName =
            if (
                suffix.isBlank()
            ) {
                source
            } else {
                "$source $suffix"
            }

        Log.d(
            "HDFilmCehennemi",
            "VIDEO URL=$finalUrl"
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

        callback(
            newExtractorLink(
                source = linkName,
                name = linkName,
                url = finalUrl,
                type = type
            ) {

                this.referer =
                    referer

                this.headers =
                    headers

                quality =
                    Qualities.Unknown.value
            }
        )

        return true
    }

    private suspend fun tryEmitCandidates(
        candidates: Collection<String>,
        source: String,
        playerUrl: String,
        suffixPrefix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var emittedCount =
            0

        var index =
            1

        for (
            videoUrl in
            candidates
        ) {

            if (
                emitVideoLink(
                    source = source,
                    playerUrl = playerUrl,
                    videoUrl = videoUrl,
                    suffix = "$suffixPrefix $index",
                    callback = callback
                )
            ) {
                emittedCount++
            }

            index++
        }

        return emittedCount > 0
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

        val html =
            response.text

        if (
            html.isBlank()
        ) {

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

        /*
         * 1. Inline özel decoder
         */
        val inlineCandidates =
            LinkedHashSet<String>()

        val inlineDecoders =
            parseInlineDecoders(html)

        Log.d(
            "HDFilmCehennemi",
            "INLINE DECODER COUNT=${inlineDecoders.size}"
        )

        for (
            decoder in
            inlineDecoders
        ) {

            val decoded =
                runCatching {

                    applyDecodeSteps(
                        decoder.parts,
                        decoder.steps
                    )

                }.getOrDefault("")

            val clean =
                cleanUrl(
                    decoded
                )

            if (
                isValidVideoUrl(clean)
            ) {
                inlineCandidates.add(clean)
            }
        }

        if (
            tryEmitCandidates(
                candidates = inlineCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "Inline",
                callback = callback
            )
        ) {
            return true
        }

        /*
         * 2. Packed JS
         */
        val unpacked =
            runCatching {
                getAndUnpack(html)
            }.getOrNull()

        if (
            !unpacked.isNullOrBlank() &&
            unpacked != html
        ) {

            val packedDecoders =
                parseInlineDecoders(
                    unpacked
                )

            Log.d(
                "HDFilmCehennemi",
                "PACKED DECODER COUNT=${packedDecoders.size}"
            )

            val packedCandidates =
                LinkedHashSet<String>()

            for (
                decoder in
                packedDecoders
            ) {

                val decoded =
                    runCatching {

                        applyDecodeSteps(
                            decoder.parts,
                            decoder.steps
                        )

                    }.getOrDefault("")

                val clean =
                    cleanUrl(
                        decoded
                    )

                if (
                    isValidVideoUrl(clean)
                ) {
                    packedCandidates.add(clean)
                }
            }

            if (
                tryEmitCandidates(
                    candidates = packedCandidates,
                    source = source,
                    playerUrl = playerUrl,
                    suffixPrefix = "Packed",
                    callback = callback
                )
            ) {
                return true
            }
        }

        /*
         * 3. Structured
         */
        val structuredCandidates =
            extractStructuredVideoUrls(
                html,
                playerUrl
            )

        Log.d(
            "HDFilmCehennemi",
            "STRUCTURED CANDIDATES=$structuredCandidates"
        )

        if (
            tryEmitCandidates(
                candidates = structuredCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "Structured",
                callback = callback
            )
        ) {
            return true
        }

        /*
         * 4. JW / JavaScript
         */
        val javascriptCandidates =
            extractJavascriptMediaSources(
                html,
                playerUrl
            )

        Log.d(
            "HDFilmCehennemi",
            "JAVASCRIPT CANDIDATES=$javascriptCandidates"
        )

        if (
            tryEmitCandidates(
                candidates = javascriptCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "JW",
                callback = callback
            )
        ) {
            return true
        }

        /*
         * 5. Dynamic / external JS / endpoint
         */
        val dynamicCandidates =
            extractDynamicPlayerSources(
                html,
                playerUrl
            )

        Log.d(
            "HDFilmCehennemi",
            "DYNAMIC CANDIDATES=$dynamicCandidates"
        )

        if (
            tryEmitCandidates(
                candidates = dynamicCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "Dynamic",
                callback = callback
            )
        ) {
            return true
        }

        /*
         * 6. Direct
         */
        val directCandidates =
            extractDirectVideoUrlsFromText(
                html,
                playerUrl
            )

        Log.d(
            "HDFilmCehennemi",
            "DIRECT CANDIDATES=$directCandidates"
        )

        if (
            tryEmitCandidates(
                candidates = directCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "Direct",
                callback = callback
            )
        ) {
            return true
        }

        /*
         * 7. Final fallback
         */
        val fallbackCandidates =
            extractFallbackVideoUrls(
                html
            )

        Log.d(
            "HDFilmCehennemi",
            "FALLBACK CANDIDATES=$fallbackCandidates"
        )

        if (
            tryEmitCandidates(
                candidates = fallbackCandidates,
                source = source,
                playerUrl = playerUrl,
                suffixPrefix = "Fallback",
                callback = callback
            )
        ) {
            return true
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

        var found =
            false

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
                    .ifBlank {
                        "TR"
                    }

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

                if (
                    videoId.isBlank()
                ) {
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
                                    "fetch",
                                "Accept" to
                                    "*/*"
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

                /*
                 * data-src
                 */
                val dataSrcRaw =
                    Regex(
                        """data-src\s*=\s*\\?["']([^"']+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(apiHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                val dataSrc =
                    normalizePlayerUrl(
                        dataSrcRaw
                    )

                if (
                    !dataSrc.isNullOrBlank()
                ) {
                    playerCandidates.add(
                        dataSrc
                    )
                }

                /*
                 * iframe
                 */
                val apiDocument =
                    Jsoup.parse(
                        apiHtml
                    )

                val frames =
                    apiDocument.select(
                        "iframe[data-src], iframe[src]"
                    )

                for (
                    frame in
                    frames
                ) {

                    val frameUrl =
                        normalizePlayerUrl(
                            frame
                                .attr("data-src")
                                .ifBlank {
                                    frame.attr("src")
                                }
                                .trim()
                        )

                    if (
                        !frameUrl.isNullOrBlank()
                    ) {
                        playerCandidates.add(
                            frameUrl
                        )
                    }
                }

                /*
                 * data-player / data-embed / data-url
                 */
                val genericAttributes =
                    listOf(
                        "data-player",
                        "data-embed",
                        "data-url"
                    )

                for (
                    attrName in
                    genericAttributes
                ) {

                    val attrValue =
                        apiDocument
                            .select(
                                "[$attrName]"
                            )
                            .firstOrNull()
                            ?.attr(
                                attrName
                            )

                    val normalized =
                        normalizePlayerUrl(
                            attrValue
                        )

                    if (
                        !normalized.isNullOrBlank()
                    ) {
                        playerCandidates.add(
                            normalized
                        )
                    }
                }

                /*
                 * rapidrame_id
                 */
                val rapidrameId =
                    Regex(
                        """rapidrame_id=([^&"']+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(apiHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                if (
                    !rapidrameId.isNullOrBlank()
                ) {

                    playerCandidates.add(
                        "${mainUrl}/rplayer/$rapidrameId/"
                    )

                    playerCandidates.add(
                        "${mainUrl}/playerr/$rapidrameId"
                    )
                }

                /*
                 * rplayer / playerr
                 */
                val rpMatches =
                    Regex(
                        """/(?:rplayer|playerr)/([^/?#"' ]+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .findAll(apiHtml)

                for (
                    match in
                    rpMatches
                ) {

                    val id =
                        match
                            .groupValues
                            .getOrNull(1)
                            .orEmpty()

                    if (
                        id.isBlank()
                    ) {
                        continue
                    }

                    playerCandidates.add(
                        "${mainUrl}/rplayer/$id/"
                    )

                    playerCandidates.add(
                        "${mainUrl}/playerr/$id"
                    )
                }

                /*
                 * API cevabındaki player URL'leri
                 */
                val rawUrls =
                    Regex(
                        """https?://[^\s"'<>\\]+""",
                        RegexOption.IGNORE_CASE
                    )
                        .findAll(apiHtml)
                        .map {
                            cleanUrl(
                                it.value
                            )
                        }
                        .filter {

                            val lower =
                                it.lowercase()

                            lower.contains(
                                "player"
                            ) ||
                                lower.contains(
                                    "embed"
                                ) ||
                                lower.contains(
                                    "rapidrame"
                                )
                        }
                        .mapNotNull {
                            normalizePlayerUrl(
                                it
                            )
                        }
                        .toList()

                playerCandidates.addAll(
                    rawUrls
                )

                Log.d(
                    "HDFilmCehennemi",
                    "PLAYER CANDIDATES=$playerCandidates"
                )

                for (
                    playerUrl in
                    playerCandidates
                ) {

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

                    if (
                        success
                    ) {

                        found =
                            true

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
