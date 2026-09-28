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
import java.util.Locale

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val defaultEmbedOrigin = "https://hdfilmcehennemi.mobi"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private val mediaUserAgent =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    companion object {
        private const val TAG = "HDFilmCehennemi"
        private const val MAX_EXTERNAL_SCRIPTS = 10
        private const val MAX_DYNAMIC_ENDPOINTS = 12
        private const val MAX_DYNAMIC_RESPONSES = 12
        private const val MAX_JS_DEPTH = 8
        private const val MAX_JW_CONTEXT = 30000
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024 * 1024).string()
            if (body.contains("Just a moment", ignoreCase = true)) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .also {
                it.configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false
                )
            }

        val url = request.data.replace("sayfano", page.toString())
        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to browserHeaders["User-Agent"].orEmpty(),
                "Accept" to "*/*",
                "X-Requested-With" to "fetch"
            ),
            referer = mainUrl,
            interceptor = interceptor
        )

        if (response.text.contains("Sayfa Bulunamadı", ignoreCase = true)) {
            return newHomePageResponse(request.name, emptyList())
        }

        val data: HDFC = try {
            mapper.readValue<HDFC>(response.text)
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val document = Jsoup.parse(data.html)
        return newHomePageResponse(
            request.name,
            document.select("a").mapNotNull { it.toSearchResult() }
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title").trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { html ->
            val document = Jsoup.parse(html)
            val title = document.selectFirst("h4.title")?.text()?.trim()
                ?: return@mapNotNull null
            val href = fixUrlNull(document.selectFirst("a")?.attr("href"))
                ?: return@mapNotNull null
            val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster?.replace("/thumb/", "/list/")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.section-title")?.text()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src")
        ) ?: fixUrlNull(
            document.select("aside.post-info-poster img").lastOrNull()?.attr("src")
        )

        val tags = document.select("div.post-info-genres a").map { it.text().trim() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val isSeries = document.select("div.seasons").isNotEmpty()
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()

        val actors = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.select("img").attr("data-src")))
        }

        val recommendations = document.select(
            "div.section-slider-container div.slider-slide"
        ).mapNotNull {
            val recName = it.selectFirst("a")?.attr("title")?.trim()
                ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href"))
                ?: return@mapNotNull null
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                posterUrl = recPoster
            }
        }

        val trailerId = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")
            ?.substringAfter("trailer/", "")
            ?.trim()

        val trailer = trailerId?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { "https://www.youtube.com/watch?v=$it" }

        if (isSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim()
                    ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\.?\s*Bölüm""")
                    .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\.?\s*Sezon""")
                    .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    name = epName
                    season = epSeason
                    episode = epEpisode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun logChunks(label: String, value: String) {
        value.chunked(1400).forEachIndexed { index, chunk ->
            Log.d(TAG, "$label[$index]=$chunk")
        }
    }

    private fun decodeText(value: String): String {
        var out = value
            .replace("\\/", "/")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)

        out = Regex("\\\\u([0-9a-fA-F]{4})").replace(out) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        out = Regex("\\\\x([0-9a-fA-F]{2})").replace(out) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

        return out.trim()
    }

    private fun decodeUrlEncoded(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun decodeBase64Text(value: String): String {
        val input = value.trim()
        if (input.length < 8) return ""
        val padded = input + "=".repeat((4 - input.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrElse {
            runCatching {
                String(Base64.decode(padded, Base64.NO_WRAP), Charsets.ISO_8859_1)
            }.getOrDefault("")
        }
    }

    private fun cleanUrl(value: String): String {
        return decodeUrlEncoded(decodeText(value))
            .trim()
            .trim('"', '\'', '`', '\\')
            .replace("\\/", "/")
    }

    private fun isRejectedMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/master.txt") ||
            lower.contains(".mp4/master.txt") ||
            lower.contains("master.txt?")
    }

    private fun isValidVideoUrl(url: String): Boolean {
        if (url.isBlank() || isRejectedMediaUrl(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("http://") && (
            lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("/hls/") || lower.contains("/hls2/")
            ) || lower.startsWith("https://") && (
            lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("/hls/") || lower.contains("/hls2/")
            )
    }

    private fun isHlsCandidate(url: String): Boolean {
        if (url.isBlank() || isRejectedMediaUrl(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains(".mp4")) return false
        return lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("/hls2/")
    }

    private fun mediaTypeForUrl(url: String): ExtractorLinkType {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains(".m3u8") -> ExtractorLinkType.M3U8
            lower.contains(".mp4") -> ExtractorLinkType.VIDEO
            lower.contains("/hls/") || lower.contains("/hls2/") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) ""
        else "${uri.scheme}://${uri.host}"
    }.getOrDefault("")

    private fun resolvePlayerOrigin(playerUrl: String): String {
        val origin = originOf(playerUrl)
        return when {
            playerUrl.contains("/rplayer/", true) || playerUrl.contains("/playerr/", true) -> originOf(mainUrl)
            origin.isNotBlank() -> origin
            else -> defaultEmbedOrigin
        }
    }

    private fun resolveMediaReferer(playerUrl: String): String =
        resolvePlayerOrigin(playerUrl).trimEnd('/') + "/"

    private fun mediaHeaders(playerUrl: String): Map<String, String> {
        val origin = resolvePlayerOrigin(playerUrl)
        return mapOf(
            "User-Agent" to mediaUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to origin.trimEnd('/') + "/",
            "Origin" to origin
        )
    }

    private fun resolveAbsoluteUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val value = cleanUrl(raw)
        if (value.isBlank()) return null
        return runCatching { URI(baseUrl).resolve(value).toString() }
            .getOrNull() ?: fixUrlNull(value)
    }

    private fun addCandidate(
        result: MutableSet<String>,
        raw: String?,
        baseUrl: String
    ) {
        if (raw.isNullOrBlank()) return
        val value = cleanUrl(raw)
        if (value.isBlank()) return

        val possibilities = linkedSetOf(value, decodeUrlEncoded(value))
        possibilities.forEach { possibility ->
            val absolute = resolveAbsoluteUrl(possibility, baseUrl) ?: return@forEach
            val clean = cleanUrl(absolute)
            if (isValidVideoUrl(clean)) result.add(clean)
        }
    }

    private fun splitTopLevel(value: String, delimiter: Char = '+'): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var quote: Char? = null
        var escaped = false

        value.forEachIndexed { index, c ->
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                return@forEachIndexed
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                return@forEachIndexed
            }

            when (c) {
                '(' -> depthParen++
                ')' -> depthParen--
                '{' -> depthBrace++
                '}' -> depthBrace--
                '[' -> depthBracket++
                ']' -> depthBracket--
                delimiter -> if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                    result.add(value.substring(start, index).trim())
                    start = index + 1
                }
            }
        }

        result.add(value.substring(start).trim())
        return result.filter { it.isNotBlank() }
    }

    private fun findBalancedEnd(text: String, start: Int, open: Char, close: Char): Int {
        if (start !in text.indices || text[start] != open) return -1
        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                continue
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                continue
            }
            when (c) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun readJsExpression(text: String, start: Int): String {
        var i = start
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var quote: Char? = null
        var escaped = false

        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                i++
                continue
            }
            when (c) {
                '(' -> depthParen++
                ')' -> if (depthParen > 0) depthParen-- else return text.substring(start, i).trim()
                '{' -> depthBrace++
                '}' -> if (depthBrace > 0) depthBrace-- else return text.substring(start, i).trim()
                '[' -> depthBracket++
                ']' -> if (depthBracket > 0) depthBracket-- else return text.substring(start, i).trim()
                ';' -> if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) return text.substring(start, i).trim()
                ',' -> if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) return text.substring(start, i).trim()
            }
            i++
        }
        return text.substring(start).trim()
    }

    private fun collectJsVariables(text: String): MutableMap<String, String> {
        val variables = linkedMapOf<String, String>()

        val declarationRegex = Regex(
            "\\b(?:var|let|const)\\s+([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*=",
            RegexOption.MULTILINE
        )

        declarationRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val expressionStart = match.range.last + 1
            val expression = readJsExpression(text, expressionStart)
            if (expression.isNotBlank()) {
                variables[name] = expression
            }
        }

        /*
         * Önemli: rplayer kodunda kaynak değişkenleri bazen
         * `var sources = []` ile tanımlanıp daha sonra
         * `sources = ...` şeklinde yeniden atanabiliyor.
         * Bildirim olmayan atamaları da yakala.
         */
        val assignmentRegex = Regex(
            """(?<![.\w$])([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\s*=\s*""",
            RegexOption.MULTILINE
        )

        assignmentRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name in setOf(
                    "if", "for", "while", "switch", "return",
                    "function", "var", "let", "const"
                )
            ) {
                return@forEach
            }

            val expressionStart = match.range.last + 1
            val expression = readJsExpression(text, expressionStart)
            if (expression.isNotBlank()) {
                variables[name] = expression
            }
        }

        return variables
    }

    private fun collectJsPushes(text: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()

        val regex = Regex(
            "\\b([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\.(?:push|unshift|concat)\\s*\\(",
            RegexOption.MULTILINE
        )

        regex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val start = match.range.last + 1
            val end = findBalancedEnd(text, start, '(', ')')
            if (end < 0) return@forEach

            val argument = text.substring(start, end).trim()
            if (argument.isBlank()) return@forEach

            result.getOrPut(name) { mutableListOf() }.add(argument)
        }

        return result
    }
    private fun collectJsFunctions(text: String): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("(?is)(?:function\\s+([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\([^)]*\\)|([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*=\\s*function\\s*\\([^)]*\\))\\s*\\{")

        regex.findAll(text).forEach { match ->
            val name = match.groupValues[1].ifBlank { match.groupValues[2] }
            val brace = text.indexOf('{', match.range.last)
            if (brace < 0) return@forEach
            val end = findBalancedEnd(text, brace, '{', '}')
            if (end < 0) return@forEach
            result[name] = text.substring(brace + 1, end)
        }
        return result
    }

    private fun quotedValue(expression: String): String? {
        val value = expression.trim()
        if (value.length < 2) return null
        val first = value.first()
        val last = value.last()
        if (first !in charArrayOf('\'', '"', '`') || first != last) return null
        return decodeText(value.substring(1, value.length - 1))
    }

    private fun extractPropertyExpressions(text: String, keys: Set<String>): List<String> {
        val result = mutableListOf<String>()
        val keyPattern = keys.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?is)(?:[\\\"'](?:$keyPattern)[\\\"']|\\b(?:$keyPattern))\\s*:")

        regex.findAll(text).forEach { match ->
            val start = match.range.last + 1
            val expression = readJsExpression(text, start)
            if (expression.isNotBlank()) result.add(expression)
        }
        return result
    }

    private fun extractDirectStrings(text: String, result: MutableSet<String>, baseUrl: String) {
        Regex("https?://[^\\s\"'`<>\\\\]+", RegexOption.IGNORE_CASE)
            .findAll(decodeText(text))
            .forEach { addCandidate(result, it.value, baseUrl) }

        Regex("(?:https?:)?//[^\\s\"'`<>\\\\]+", RegexOption.IGNORE_CASE)
            .findAll(decodeText(text))
            .forEach { addCandidate(result, it.value, baseUrl) }

        Regex("(?:/|https?://)[^\\s\"'`<>]+/(?:hls2?/)[^\\s\"'`<>]+", RegexOption.IGNORE_CASE)
            .findAll(decodeText(text))
            .forEach { addCandidate(result, it.value, baseUrl) }
    }

    private fun extractMediaFromDecoded(value: String, result: MutableSet<String>, baseUrl: String) {
        addCandidate(result, value, baseUrl)
        extractDirectStrings(value, result, baseUrl)

        Regex("(?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)\\s*[:=]\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
            .findAll(value)
            .forEach { addCandidate(result, it.groupValues[1], baseUrl) }
    }

    private fun resolveJsExpression(
        expression: String,
        variables: Map<String, String>,
        functions: Map<String, String>,
        result: MutableSet<String>,
        baseUrl: String,
        depth: Int = 0
    ) {
        if (depth > MAX_JS_DEPTH) return
        var value = expression.trim().trimEnd(';').trim()
        if (value.isBlank()) return

        while (value.startsWith('(') && value.endsWith(')')) {
            val end = findBalancedEnd(value, 0, '(', ')')
            if (end != value.lastIndex) break
            value = value.substring(1, value.length - 1).trim()
        }

        val directQuoted = quotedValue(value)
        if (directQuoted != null) {
            extractMediaFromDecoded(directQuoted, result, baseUrl)
            val decoded = decodeBase64Text(directQuoted)
            if (decoded.isNotBlank()) extractMediaFromDecoded(decoded, result, baseUrl)
            return
        }

        val parts = splitTopLevel(value, '+')
        if (parts.size > 1) {
            val combined = StringBuilder()
            for (part in parts) {
                val temp = linkedSetOf<String>()
                resolveJsExpression(part, variables, functions, temp, baseUrl, depth + 1)
                if (temp.isNotEmpty()) combined.append(temp.first())
                else {
                    val q = quotedValue(part)
                    if (q != null) combined.append(q)
                    else {
                        val id = part.trim()
                        val varExpr = variables[id]
                        if (varExpr != null) {
                            val fragment = linkedSetOf<String>()
                            resolveJsExpression(varExpr, variables, functions, fragment, baseUrl, depth + 1)
                            if (fragment.isNotEmpty()) combined.append(fragment.first())
                        }
                    }
                }
            }
            if (combined.isNotBlank()) extractMediaFromDecoded(combined.toString(), result, baseUrl)
            return
        }

        val atobRegex = Regex("(?is)^(?:window\\.)?atob\\s*\\((.*)\\)$")
        atobRegex.matchEntire(value)?.let { match ->
            val temp = linkedSetOf<String>()
            resolveJsExpression(match.groupValues[1], variables, functions, temp, baseUrl, depth + 1)
            if (temp.isNotEmpty()) {
                temp.forEach { candidate ->
                    val decoded = decodeBase64Text(candidate)
                    if (decoded.isNotBlank()) extractMediaFromDecoded(decoded, result, baseUrl)
                }
            } else {
                val arg = quotedValue(match.groupValues[1])
                if (arg != null) extractMediaFromDecoded(decodeBase64Text(arg), result, baseUrl)
            }
            return
        }

        val decodeRegex = Regex("(?is)^(?:decodeURIComponent|decodeURI)\\s*\\((.*)\\)$")
        decodeRegex.matchEntire(value)?.let { match ->
            val q = quotedValue(match.groupValues[1])
            if (q != null) extractMediaFromDecoded(decodeUrlEncoded(q), result, baseUrl)
            else {
                val temp = linkedSetOf<String>()
                resolveJsExpression(match.groupValues[1], variables, functions, temp, baseUrl, depth + 1)
                temp.forEach { extractMediaFromDecoded(decodeUrlEncoded(it), result, baseUrl) }
            }
            return
        }

        val charCodeRegex = Regex("(?is)^String\\.fromCharCode\\s*\\((.*)\\)$")
        charCodeRegex.matchEntire(value)?.let { match ->
            val decoded = splitTopLevel(match.groupValues[1], ',')
                .mapNotNull { it.trim().toIntOrNull() }
                .map { it.toChar() }
                .joinToString("")
            extractMediaFromDecoded(decoded, result, baseUrl)
            return
        }

        /*
         * Array ifadeleri: [ {file: ...}, {file: ...} ]
         */
        if (value.startsWith("[") && value.endsWith("]")) {
            val inner = value.substring(1, value.length - 1)
            splitTopLevel(inner, ',').forEach { item ->
                resolveJsExpression(item, variables, functions, result, baseUrl, depth + 1)
            }
            return
        }

        /*
         * Object ifadeleri: { sources: ..., file: ... }
         */
        if (value.startsWith("{") && value.endsWith("}")) {
            extractPropertyExpressions(
                value,
                setOf(
                    "file", "src", "source", "url", "media", "stream",
                    "streamUrl", "videoUrl", "video_url", "hls",
                    "playlist", "manifest", "sources"
                )
            ).forEach { property ->
                resolveJsExpression(
                    property,
                    variables,
                    functions,
                    result,
                    baseUrl,
                    depth + 1
                )
            }

            extractMediaFromDecoded(value, result, baseUrl)
            return
        }

        if (value.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"))) {
            variables[value]?.let {
                resolveJsExpression(it, variables, functions, result, baseUrl, depth + 1)
                return
            }
        }

        val callName = Regex("^([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(value)
        if (callName != null) {
            val fn = functions[callName.groupValues[1]]
            if (fn != null) {
                val returns = Regex("(?is)\\breturn\\s+(.+?)(?:;|$)").findAll(fn).map { it.groupValues[1] }
                returns.forEach { resolveJsExpression(it, variables, functions, result, baseUrl, depth + 1) }
            }
        }

        extractPropertyExpressions(value, setOf("file", "src", "source", "url", "media", "stream", "streamUrl", "videoUrl", "video_url", "hls", "playlist", "manifest"))
            .forEach { resolveJsExpression(it, variables, functions, result, baseUrl, depth + 1) }

        extractPropertyExpressions(value, setOf("sources"))
            .forEach { sourceExpression ->
                splitTopLevel(sourceExpression.trim().removePrefix("[").removeSuffix("]"), ',')
                    .forEach { item -> resolveJsExpression(item, variables, functions, result, baseUrl, depth + 1) }
            }

        extractMediaFromDecoded(value, result, baseUrl)
    }

    private fun extractJavascriptMediaSources(
        text: String,
        baseUrl: String
    ): List<String> {
        val result = linkedSetOf<String>()
        val variables = collectJsVariables(text)
        val functions = collectJsFunctions(text)
        val pushes = collectJsPushes(text)

        Log.d(TAG, "JS VARIABLES=${variables.keys}")

        extractDirectStrings(text, result, baseUrl)

        /*
         * Değişkenleri çöz. Özellikle sources/configs önemli.
         */
        variables.forEach { (name, expression) ->
            if (name == "sources" || name == "configs" || name == "player") {
                Log.d(TAG, "JS SPECIAL=$name EXPR=${expression.take(2500)}")
            }
            resolveJsExpression(
                expression,
                variables,
                functions,
                result,
                baseUrl
            )
        }

        /*
         * Sonradan yapılan sources.push({...}) vb.
         */
        pushes.forEach { (name, expressions) ->
            Log.d(TAG, "JS PUSHES=$name COUNT=${expressions.size}")
            expressions.forEach { expression ->
                resolveJsExpression(
                    expression,
                    variables,
                    functions,
                    result,
                    baseUrl
                )
            }
        }

        /*
         * file/src/source/... property'leri.
         */
        extractPropertyExpressions(
            text,
            setOf(
                "file", "src", "source", "url", "media", "stream",
                "streamUrl", "videoUrl", "video_url", "hls",
                "playlist", "manifest", "sources"
            )
        ).forEach { expression ->
            resolveJsExpression(
                expression,
                variables,
                functions,
                result,
                baseUrl
            )
        }

        /*
         * jwplayer(...).setup(...) yanında player.setup(...),
         * herhangiBirDegisken.setup(...) gibi kullanımları da yakala.
         */
        val setupRegex = Regex(
            "(?is)(?:[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*(?:\\([^)]*\\))?)\\s*\\.\\s*setup\\s*\\("
        )

        setupRegex.findAll(text).forEach { match ->
            val start = match.range.last + 1
            if (start !in text.indices) return@forEach

            val end = findBalancedEnd(text, start, '(', ')')
            if (end < 0) return@forEach

            val argument = text.substring(start, end).trim()
            Log.d(TAG, "JW SETUP ARG=${argument.take(2500)}")
            Log.d(TAG, "JW SOURCE BLOCK LENGTH=${argument.length}")

            if (argument.isNotBlank()) {
                resolveJsExpression(
                    argument,
                    variables,
                    functions,
                    result,
                    baseUrl
                )
            }

            if (argument.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"))) {
                val resolved = variables[argument]
                if (!resolved.isNullOrBlank()) {
                    Log.d(
                        TAG,
                        "JW SETUP RESOLVED=$argument -> ${resolved.take(2500)}"
                    )
                    logChunks("JW RESOLVED DEBUG", resolved)
                    resolveJsExpression(
                        resolved,
                        variables,
                        functions,
                        result,
                        baseUrl
                    )
                }
            }
        }

        /*
         * Doğrudan atob() çağrıları.
         */
        Regex(
            "(?is)(?:window\\.)?atob\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)"
        )
            .findAll(text)
            .forEach { match ->
                val decoded = decodeBase64Text(match.groupValues[1])
                if (decoded.isNotBlank()) {
                    extractMediaFromDecoded(
                        decoded,
                        result,
                        baseUrl
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

    private fun extractStructuredVideoUrls(text: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val doc = Jsoup.parse(text, baseUrl)

        doc.select("video[src], source[src], video[data-src], source[data-src]").forEach { element ->
            addCandidate(result, element.attr("data-src").ifBlank { element.attr("src") }, baseUrl)
        }

        Regex("(?is)(?:file|src|source|url|media|stream)\\s*[:=]\\s*['\"]([^'\"]+)['\"]")
            .findAll(text)
            .forEach { addCandidate(result, it.groupValues[1], baseUrl) }

        return result.filter { isValidVideoUrl(it) && !isRejectedMediaUrl(it) }.toList()
    }

    private fun extractDirectVideoUrlsFromText(text: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        extractDirectStrings(text, result, baseUrl)
        return result.filter { isValidVideoUrl(it) && !isRejectedMediaUrl(it) }.toList()
    }

    private fun extractScriptEndpoints(html: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val patterns = listOf(
            Regex("(?i)fetch\\s*\\(\\s*['\"]([^'\"]+)['\"]"),
            Regex("(?i)(?:axios|jquery|\\$)\\s*\\.\\s*(?:get|post)\\s*\\(\\s*['\"]([^'\"]+)['\"]"),
            Regex("(?i)axios\\s*\\(\\s*\\{\\s*url\\s*:\\s*['\"]([^'\"]+)['\"]"),
            Regex("(?i)open\\s*\\(\\s*['\"]GET['\"]\\s*,\\s*['\"]([^'\"]+)['\"]"),
            Regex("(?i)(?:url|endpoint|apiUrl|api_url|requestUrl|request_url|sourceEndpoint|source_endpoint)\\s*[:=]\\s*['\"]([^'\"]+)['\"]")
        )

        patterns.forEach { regex ->
            regex.findAll(html).forEach { match ->
                val absolute = resolveAbsoluteUrl(match.groupValues[1], baseUrl) ?: return@forEach
                val lower = absolute.lowercase(Locale.ROOT)
                if (!isValidVideoUrl(absolute) && !lower.endsWith(".js") && !lower.contains(".js?") && !lower.endsWith(".css")) {
                    result.add(absolute)
                }
            }
        }

        return result.take(MAX_DYNAMIC_ENDPOINTS)
    }

    private suspend fun extractDynamicPlayerSources(html: String, playerUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val document = Jsoup.parse(html, playerUrl)

        document.select("script:not([src])").forEach { script ->
            val body = script.data().ifBlank { script.html() }
            if (body.isNotBlank()) result.addAll(extractJavascriptMediaSources(body, playerUrl))
        }

        val scripts = document.select("script[src]")
            .mapNotNull { resolveAbsoluteUrl(it.attr("src"), playerUrl) }
            .distinct()
            .take(MAX_EXTERNAL_SCRIPTS)

        scripts.forEach { scriptUrl ->
            Log.d(TAG, "PLAYER SCRIPT=$scriptUrl")
            val body = runCatching {
                app.get(
                    scriptUrl,
                    headers = browserHeaders,
                    referer = playerUrl,
                    allowRedirects = true,
                    interceptor = interceptor
                ).text
            }.getOrNull().orEmpty()
            if (body.isBlank()) return@forEach
            result.addAll(extractJavascriptMediaSources(body, scriptUrl))
        }

        val endpoints = linkedSetOf<String>()
        endpoints.addAll(extractScriptEndpoints(html, playerUrl))
        scripts.forEach { scriptUrl ->
            val body = runCatching {
                app.get(
                    scriptUrl,
                    headers = browserHeaders,
                    referer = playerUrl,
                    allowRedirects = true,
                    interceptor = interceptor
                ).text
            }.getOrNull().orEmpty()
            if (body.isNotBlank()) endpoints.addAll(extractScriptEndpoints(body, scriptUrl))
        }

        Log.d(TAG, "SCRIPT ENDPOINTS=$endpoints")

        var count = 0
        for (endpoint in endpoints) {
            if (count++ >= MAX_DYNAMIC_RESPONSES) break
            Log.d(TAG, "DYNAMIC ENDPOINT=$endpoint")
            val body = runCatching {
                app.get(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to browserHeaders["User-Agent"].orEmpty(),
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to playerUrl
                    ),
                    referer = playerUrl,
                    allowRedirects = true,
                    interceptor = interceptor
                ).text
            }.getOrNull().orEmpty()

            if (body.isBlank()) continue
            result.addAll(extractDirectVideoUrlsFromText(body, endpoint))
            result.addAll(extractStructuredVideoUrls(body, endpoint))
            result.addAll(extractJavascriptMediaSources(body, endpoint))

            val unpacked = runCatching { getAndUnpack(body) }.getOrNull()
            if (!unpacked.isNullOrBlank() && unpacked != body) {
                result.addAll(extractDirectVideoUrlsFromText(unpacked, endpoint))
                result.addAll(extractStructuredVideoUrls(unpacked, endpoint))
                result.addAll(extractJavascriptMediaSources(unpacked, endpoint))
            }
        }

        return result.filter { isValidVideoUrl(it) && !isRejectedMediaUrl(it) }.distinct()
    }

    private fun extractFallbackVideoUrls(text: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val normalized = decodeText(text)
        val regex = Regex(
            "https?://[^\\s\"'<>\\\\]+?(?:\\.m3u8(?:\\?[^\\s\"'<>\\\\]+)?|\\.mp4(?:\\?[^\\s\"'<>\\\\]+)?)(?!/master\\.txt)",
            RegexOption.IGNORE_CASE
        )
        regex.findAll(normalized).forEach { addCandidate(result, it.value, baseUrl) }
        return result.filter { isValidVideoUrl(it) && !isRejectedMediaUrl(it) }.toList()
    }

    private suspend fun prepareHlsUrl(url: String, playerUrl: String): String? {
        val clean = cleanUrl(url)
        if (!isHlsCandidate(clean)) return clean
        val headers = mediaHeaders(playerUrl)
        val referer = resolveMediaReferer(playerUrl)

        return runCatching {
            val body = app.get(
                clean,
                headers = headers,
                referer = referer,
                allowRedirects = true
            ).text

            if (!body.contains("#EXTM3U")) {
                Log.e(TAG, "MEDIA PREFLIGHT NOT HLS=$clean")
                return@runCatching null
            }

            if (body.contains("#EXT-X-STREAM-INF")) {
                val variant = body.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (!variant.isNullOrBlank()) {
                    val variantUrl = resolveAbsoluteUrl(variant, clean)
                    if (!variantUrl.isNullOrBlank()) {
                        val variantBody = runCatching {
                            app.get(
                                variantUrl,
                                headers = headers,
                                referer = referer,
                                allowRedirects = true
                            ).text
                        }.getOrNull().orEmpty()
                        if (variantBody.contains("#EXTM3U")) {
                            Log.d(TAG, "MEDIA PREFLIGHT MASTER=$clean VARIANT=$variantUrl")
                            return@runCatching variantUrl
                        }
                    }
                }
            }

            Log.d(TAG, "MEDIA PREFLIGHT OK=$clean")
            clean
        }.getOrElse {
            Log.e(TAG, "MEDIA PREFLIGHT FAIL=$clean ERROR=${it.message}")
            null
        }
    }

    private suspend fun addPlayerSubtitles(
        html: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex("""(?is)(?:file|src)\s*:\s*['"]([^'"]+?\.(?:vtt|srt)(?:\?[^'"]*)?)['"]""")
            .findAll(html)
            .forEach { match ->
                val url = resolveAbsoluteUrl(match.groupValues[1], playerUrl) ?: return@forEach
                subtitleCallback(newSubtitleFile("Türkçe", url))
            }

        Jsoup.parse(html, playerUrl).select("track[src]").forEach { track ->
            val url = resolveAbsoluteUrl(track.attr("src"), playerUrl) ?: return@forEach
            val lang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Türkçe" }
            subtitleCallback(newSubtitleFile(lang, url))
        }
    }

    private suspend fun emitVideoLink(
        source: String,
        playerUrl: String,
        videoUrl: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var clean = cleanUrl(videoUrl)
        if (isRejectedMediaUrl(clean) || !isValidVideoUrl(clean)) {
            if (isRejectedMediaUrl(clean)) Log.d(TAG, "STALE MEDIA REJECTED=$clean")
            return false
        }

        val referer = resolveMediaReferer(playerUrl)
        val origin = resolvePlayerOrigin(playerUrl)
        val headers = mediaHeaders(playerUrl)

        if (isHlsCandidate(clean)) {
            clean = prepareHlsUrl(clean, playerUrl) ?: run {
                Log.e(TAG, "VIDEO REJECTED=$clean")
                return false
            }
        }

        if (isRejectedMediaUrl(clean) || !isValidVideoUrl(clean)) return false

        val type = mediaTypeForUrl(clean)
        val linkName = if (suffix.isBlank()) source else "$source $suffix"

        Log.d(TAG, "VIDEO URL=$clean")
        Log.d(TAG, "VIDEO TYPE=$type")
        Log.d(TAG, "VIDEO REFERER=$referer")
        Log.d(TAG, "VIDEO ORIGIN=$origin")

        callback(
            newExtractorLink(
                source = linkName,
                name = linkName,
                url = clean,
                type = type
            ) {
                this.referer = referer
                this.headers = headers
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private suspend fun tryEmitCandidates(
        candidates: Collection<String>,
        source: String,
        playerUrl: String,
        prefix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var index = 1
        var emitted = false
        candidates.forEach { candidate ->
            if (emitVideoLink(source, playerUrl, candidate, "$prefix $index", callback)) emitted = true
            index++
        }
        return emitted
    }

    private fun normalizePlayerUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = cleanUrl(raw)
        if (value.isBlank()) return null
        val absolute = resolveAbsoluteUrl(value, mainUrl) ?: return null
        return absolute
            .replace("\\/", "/")
            .trimEnd('\\')
    }

    private suspend fun extractFromPlayer(
        source: String,
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "PLAYER=$playerUrl")

        val referer = resolveMediaReferer(playerUrl)
        val response = runCatching {
            app.get(
                playerUrl,
                headers = browserHeaders,
                referer = referer,
                allowRedirects = true,
                interceptor = interceptor
            )
        }.getOrNull() ?: return false

        val html = response.text
        Log.d(TAG, "PLAYER HTML LENGTH=${html.length}")
        if (html.isBlank()) return false

        addPlayerSubtitles(html, playerUrl, subtitleCallback)

        val inline = extractJavascriptMediaSources(html, playerUrl)
        Log.d(TAG, "JAVASCRIPT CANDIDATES=$inline")
        if (tryEmitCandidates(inline, source, playerUrl, "JW", callback)) return true

        val packed = runCatching { getAndUnpack(html) }.getOrNull()
        if (!packed.isNullOrBlank() && packed != html) {
            val packedCandidates = extractJavascriptMediaSources(packed, playerUrl)
            Log.d(TAG, "PACKED CANDIDATES=$packedCandidates")
            if (tryEmitCandidates(packedCandidates, source, playerUrl, "Packed", callback)) return true
        }

        val structured = extractStructuredVideoUrls(html, playerUrl)
        Log.d(TAG, "STRUCTURED CANDIDATES=$structured")
        if (tryEmitCandidates(structured, source, playerUrl, "Structured", callback)) return true

        val dynamic = extractDynamicPlayerSources(html, playerUrl)
        Log.d(TAG, "DYNAMIC CANDIDATES=$dynamic")
        if (tryEmitCandidates(dynamic, source, playerUrl, "Dynamic", callback)) return true

        val direct = extractDirectVideoUrlsFromText(html, playerUrl)
        Log.d(TAG, "DIRECT CANDIDATES=$direct")
        if (tryEmitCandidates(direct, source, playerUrl, "Direct", callback)) return true

        val fallback = extractFallbackVideoUrls(html, playerUrl)
        Log.d(TAG, "FALLBACK CANDIDATES=$fallback")
        if (tryEmitCandidates(fallback, source, playerUrl, "Fallback", callback)) return true

        Log.e(TAG, "VIDEO BULUNAMADI player=$playerUrl page=$pageUrl")
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "LOAD LINKS=$data")

        val pageDocument = runCatching {
            app.get(
                data,
                headers = browserHeaders,
                referer = "$mainUrl/",
                interceptor = interceptor
            ).document
        }.getOrNull() ?: return false

        var found = false
        val alternativeBlocks = pageDocument.select("div.alternative-links")
        Log.d(TAG, "ALTERNATIVE BLOCKS=${alternativeBlocks.size}")

        alternativeBlocks.forEach { element ->
            if (found) return@forEach

            val langCode = element.attr("data-lang").uppercase().ifBlank { "TR" }
            element.select("button.alternative-link").forEach { button ->
                if (found) return@forEach

                val source = "${button.text().replace("(HDrip Xbet)", "").trim()} $langCode".trim()
                val videoId = button.attr("data-video").trim()
                if (videoId.isBlank()) return@forEach

                Log.d(TAG, "SOURCE=$source VIDEO_ID=$videoId")

                val apiHtml = runCatching {
                    app.get(
                        "${mainUrl}/video/$videoId/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch",
                            "Accept" to "*/*"
                        ),
                        referer = data,
                        interceptor = interceptor
                    ).text
                }.getOrNull().orEmpty()

                if (apiHtml.isBlank()) return@forEach

                val apiDocument = Jsoup.parse(apiHtml, data)
                val playerCandidates = linkedSetOf<String>()

                apiDocument.select("iframe[data-src], iframe[src]").forEach { frame ->
                    normalizePlayerUrl(frame.attr("data-src").ifBlank { frame.attr("src") })
                        ?.let(playerCandidates::add)
                }

                listOf("data-src", "data-player", "data-embed", "data-url").forEach { attr ->
                    apiDocument.select("[$attr]").forEach { node ->
                        normalizePlayerUrl(node.attr(attr))?.let(playerCandidates::add)
                    }
                }

                Regex("rapidrame_id=([^&\"']+)", RegexOption.IGNORE_CASE)
                    .find(apiHtml)?.groupValues?.getOrNull(1)?.let { id ->
                        playerCandidates.add("${mainUrl}/rplayer/$id/")
                        playerCandidates.add("${mainUrl}/playerr/$id")
                    }

                Regex("/(?:rplayer|playerr)/([^/?#\"' ]+)", RegexOption.IGNORE_CASE)
                    .findAll(apiHtml)
                    .forEach { match ->
                        val id = match.groupValues[1]
                        playerCandidates.add("${mainUrl}/rplayer/$id/")
                        playerCandidates.add("${mainUrl}/playerr/$id")
                    }

                Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
                    .findAll(apiHtml)
                    .map { cleanUrl(it.value) }
                    .filter {
                        val lower = it.lowercase(Locale.ROOT)
                        lower.contains("player") || lower.contains("embed") || lower.contains("rapidrame")
                    }
                    .mapNotNull { normalizePlayerUrl(it) }
                    .forEach(playerCandidates::add)

                Log.d(TAG, "PLAYER CANDIDATES=$playerCandidates")

                playerCandidates.forEach { playerUrl ->
                    if (found) return@forEach
                    Log.d(TAG, "TRY PLAYER=$playerUrl")
                    if (extractFromPlayer(source, playerUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        Log.d(TAG, "LOAD LINKS RESULT=$found")
        return found
    }

    data class Results(
        @JsonProperty("results")
        val results: List<String> = emptyList()
    )

    data class HDFC(
        @JsonProperty("html")
        val html: String,
        @JsonProperty("meta")
        val meta: Meta
    )

    data class Meta(
        @JsonProperty("title")
        val title: String = "",
        @JsonProperty("canonical")
        val canonical: String = "",
        @JsonProperty("keywords")
        val keywords: Boolean = false
    )
}
