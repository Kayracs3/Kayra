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
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val candidates = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?query=$encoded",
            "$mainUrl/search?search=$encoded",
            "$mainUrl/search?term=$encoded"
        ).distinct()

        for (url in candidates) {
            val response = runCatching {
                app.get(
                    url,
                    headers = browserHeaders + ("X-Requested-With" to "fetch"),
                    referer = mainUrl,
                    allowRedirects = true
                )
            }.onFailure {
                Log.w(TAG, "Search request failed: $url", it)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue

            val data = response.parsedSafe<Results>()
            if (data == null) {
                Log.d(TAG, "Search response could not be parsed: $url")
                continue
            }
            val items = data.results.mapNotNull { html ->
                val document = Jsoup.parse(html, mainUrl)
                val title = document.selectFirst("h4.title, h3.title, .title, h4, h3")
                    ?.text()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: document.selectFirst("a[title]")?.attr("title")?.trim()
                        ?.takeUnless { it.isNullOrBlank() }
                    ?: return@mapNotNull null
                val anchor = document.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val poster = fixUrlNull(document.selectFirst("img")?.attr("data-src"))
                    ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
                val isSeries = href.contains("/dizi", true) || href.contains("dizi-izle", true)

                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        posterUrl = poster?.replace("/thumb/", "/list/")
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        posterUrl = poster?.replace("/thumb/", "/list/")
                    }
                }
            }.distinctBy { it.url }

            Log.d(TAG, "Search url=$url results=${items.size}")
            if (items.isNotEmpty()) return items
        }
        return emptyList()
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
            .replace("\n", "")
            .replace("\r", "")
            .replace("-", "+")
            .replace("_", "/")
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

    /**
     * JavaScript atob davranışına daha yakın, obfuscator adımlarında kullanılacak
     * sıkı Base64 çözücü. Android Base64.DEFAULT geçersiz karakterleri tolere
     * edebildiği için önce girdiyi doğruluyoruz; aksi halde bozuk bir sonuç
     * gerçek URL gibi ilerleyebiliyor.
     */
    private fun decodeAtobStrict(value: String): String? {
        val input = value
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")
            .trim()

        if (input.isBlank()) return ""
        if (input.any {
                it !in 'A'..'Z' &&
                it !in 'a'..'z' &&
                it !in '0'..'9' &&
                it != '+' &&
                it != '/' &&
                it != '='
            }) return null

        if (input.count { it == '=' } > 2) return null
        val firstPadding = input.indexOf('=')
        if (firstPadding >= 0 && input.substring(firstPadding).any { it != '=' }) return null

        val unpadded = input.trimEnd('=')
        if (unpadded.length % 4 == 1) return null

        val padded = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)
        return runCatching {
            String(
                Base64.decode(padded, Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
        }.getOrNull()
    }

    private fun cleanUrl(value: String): String {
        return decodeUrlEncoded(decodeText(value))
            .trim()
            .trim('"', '\'', '`', '\\')
            .replace("\\/", "/")
    }

    private fun isRejectedMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.contains("master.txt")) return false

        // /hls/.../master.txt ve /hls2/.../master.txt gerçek HLS master playlistleri olabilir.
        return !(lower.contains("/hls/") || lower.contains("/hls2/"))
    }

    private fun parsedUri(url: String): URI? {
        if (url.isBlank()) return null
        if (url.any { it.isWhitespace() || it == '"' || it == '\'' || it == '<' || it == '>' }) return null
        return runCatching { URI(url) }.getOrNull()
    }

    private fun isValidVideoUrl(url: String): Boolean {
        if (url.isBlank() || isRejectedMediaUrl(url)) return false
        val uri = parsedUri(url) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank()) return false
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        return path.contains(".m3u8") ||
            path.contains(".mp4") ||
            path.contains("/hls/") ||
            path.contains("/hls2/") ||
            path.endsWith("/master.txt")
    }

    private fun isHlsCandidate(url: String): Boolean {
        if (url.isBlank() || isRejectedMediaUrl(url)) return false
        val uri = parsedUri(url) ?: return false
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (path.contains(".mp4") && !path.contains("/hls/") && !path.contains("/hls2/")) return false
        return path.contains(".m3u8") ||
            path.contains("/hls/") ||
            path.contains("/hls2/") ||
            path.endsWith("/master.txt")
    }

    private fun mediaTypeForUrl(url: String): ExtractorLinkType {
        val path = parsedUri(url)?.path?.lowercase(Locale.ROOT).orEmpty()
        return when {
            path.contains(".m3u8") -> ExtractorLinkType.M3U8
            path.contains("/hls/") || path.contains("/hls2/") || path.endsWith("/master.txt") -> ExtractorLinkType.M3U8
            path.contains(".mp4") -> ExtractorLinkType.VIDEO
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
    private data class JsFunction(
        val parameters: List<String>,
        val body: String
    )

    private fun collectJsFunctions(text: String): MutableMap<String, JsFunction> {
        val result = linkedMapOf<String, JsFunction>()
        val regex = Regex(
            "(?is)(?:function\\s+([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\(([^)]*)\\)|([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*=\\s*function\\s*\\(([^)]*)\\))\\s*\\{"
        )
        regex.findAll(text).forEach { match ->
            val name = match.groupValues[1].ifBlank { match.groupValues[3] }
            val paramsText = match.groupValues[2].ifBlank { match.groupValues[4] }
            val params = splitTopLevel(paramsText, ',')
                .map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*")) }
            val brace = text.indexOf('{', match.range.last)
            if (brace < 0) return@forEach
            val end = findBalancedEnd(text, brace, '{', '}')
            if (end < 0) return@forEach
            result[name] = JsFunction(params, text.substring(brace + 1, end))
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
        val normalized = decodeText(text)

        Regex(
            """https?://[^\s"'`<>\\]+?(?:\.m3u8(?:\?[^\s"'`<>\\]+)?|\.mp4(?:\?[^\s"'`<>\\]+)?)(?!/master\.txt)""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach { addCandidate(result, it.value, baseUrl) }

        Regex(
            """(?:https?:)?//[^\s"'`<>\\]+/(?:hls2?/)[^\s"'`<>\\]+""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach { addCandidate(result, it.value, baseUrl) }

        Regex(
            """https?://[^\s"'`<>\\]+/hls(?:2)?/[^\s"'`<>\\]+/master\.txt(?:\?[^\s"'`<>\\]+)?""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach { addCandidate(result, it.value, baseUrl) }

        Regex(
            """(?:^|["'`=:(,\s])(/[^\s"'`<>]+/(?:hls2?/)[^\s"'`<>]+)""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach { addCandidate(result, it.groupValues[1], baseUrl) }
    }

    private fun extractMediaFromDecoded(value: String, result: MutableSet<String>, baseUrl: String) {
        if (value.isBlank()) return
        addCandidate(result, value, baseUrl)
        extractDirectStrings(value, result, baseUrl)

        val compact = value.trim()
        if (compact.length >= 8 && compact.length <= 8192 &&
            compact.matches(Regex("[A-Za-z0-9+/_=-]+"))) {
            val decoded = decodeBase64Text(compact)
            if (decoded.isNotBlank() && decoded != value) {
                addCandidate(result, decoded, baseUrl)
                extractDirectStrings(decoded, result, baseUrl)
            }
        }

        Regex("""(?:file|src|source|url|media|stream|streamUrl|videoUrl|video_url|hls|playlist|manifest)\\s*[:=]\\s*[\"']([^\"']+)""", RegexOption.IGNORE_CASE)
            .findAll(value)
            .forEach { addCandidate(result, it.groupValues[1], baseUrl) }
    }

    private fun resolveJsStringList(
        expression: String,
        variables: Map<String, String>,
        functions: Map<String, JsFunction>,
        depth: Int = 0
    ): List<String> {
        if (depth > MAX_JS_DEPTH) return emptyList()

        var value = expression.trim().trimEnd(';').trim()
        if (value.isBlank()) return emptyList()

        while (value.startsWith('(') && value.endsWith(')')) {
            val end = findBalancedEnd(value, 0, '(', ')')
            if (end != value.lastIndex) break
            value = value.substring(1, value.length - 1).trim()
        }

        if (value.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"))) {
            variables[value]?.let {
                return resolveJsStringList(it, variables, functions, depth + 1)
            }
        }

        if (value.startsWith("[") && value.endsWith("]")) {
            val inner = value.substring(1, value.length - 1)
            return splitTopLevel(inner, ',').mapNotNull { item ->
                quotedValue(item)
                    ?: evaluateJsStrings(item, variables, functions, depth + 1).firstOrNull()
            }
        }

        val splitMatch = Regex(
            "(?is)^(.*?)\\.split\\(\\s*(['\"])(.*?)\\2\\s*\\)$"
        ).matchEntire(value)
        if (splitMatch != null) {
            val baseExpr = splitMatch.groupValues[1]
            val separator = splitMatch.groupValues[3]
            val baseValue = quotedValue(baseExpr)
                ?: evaluateJsStrings(baseExpr, variables, functions, depth + 1).firstOrNull()
                ?: variables[baseExpr.trim()]?.let {
                    evaluateJsStrings(it, variables, functions, depth + 1).firstOrNull()
                }
            if (!baseValue.isNullOrBlank()) return baseValue.split(separator)
        }

        return evaluateJsStrings(value, variables, functions, depth + 1).toList()
    }

    private fun decodeObfuscatedJsFunction(
        function: JsFunction,
        encodedParts: List<String>
    ): String? {
        if (encodedParts.isEmpty()) return null
        val body = function.body

        // Varyant 1: join + 31/251 hash + b/v operations + 75/74 shuffle.
        if (!body.contains(".splice(") && body.contains("* 31") && body.contains("% 251")) {
            val constants = Regex(
                "\\b(?:var|let|const)\\s+[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*\\s*=\\s*[\"']([^\"']*)[\"']"
            ).findAll(body).map { it.groupValues[1] }.toList()
            if (constants.size < 2) return null

            var value = encodedParts.joinToString("")
            val hashKey = constants[0]
            val operations = constants[1]

            var hash = 0
            var xor = 0
            for (i in hashKey.indices) {
                val c = hashKey[i].code
                hash = (hash * 31 + c) % 251
                xor = (xor xor (c + i)) and 255
            }

            var state = (hash + xor) % 256
            val step = (hash % 13) + 3
            var shuffleSeed = ((hash * 256 + xor) % 65521) + 1

            for (i in operations.length - 1 downTo 0) {
                when (val op = operations[i]) {
                    'b' -> value = decodeAtobStrict(value) ?: return null
                    'v' -> value = value.reversed()
                    else -> {
                        val shift = (26 - ((op.code - 64) % 26)) % 26
                        value = value.map { ch ->
                            when {
                                ch in 'A'..'Z' -> ((ch.code - 65 + shift) % 26 + 65).toChar()
                                ch in 'a'..'z' -> ((ch.code - 97 + shift) % 26 + 97).toChar()
                                else -> ch
                            }
                        }.joinToString("")
                    }
                }
            }

            val chars = value.toCharArray()
            val swaps = IntArray(chars.size)
            for (i in chars.size - 1 downTo 1) {
                shuffleSeed = (shuffleSeed * 75 + 74) % 65537
                swaps[i] = (shuffleSeed % (i + 1)).toInt()
            }
            for (i in 1 until chars.size) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }

            value = chars.concatToString()
            val out = StringBuilder(value.length)
            for (ch in value) {
                val code = ch.code
                state = (state + step) % 256
                out.append((code xor state).toChar())
                state = (state + code) % 256
            }
            return out.toString()
        }

        // Varyant 2: splice + 37/241 hash + 7/3 operations + 97/41 shuffle.
        val hasVariant2Hash =
            Regex("\\*\\s*37\\s*\\+").containsMatchIn(body) &&
                Regex("%\\s*241").containsMatchIn(body)
        if (body.contains(".splice(") && hasVariant2Hash) {
            val sizeMinusTwo = encodedParts.size - 2
            if (sizeMinusTwo < 0) return null

            val operationIndex = 8 + (sizeMinusTwo % 5)
            if (operationIndex !in encodedParts.indices) return null

            val workParts = encodedParts.toMutableList()
            val operationString = workParts.removeAt(operationIndex)
            val secondaryIndex = sizeMinusTwo % 7
            if (secondaryIndex !in workParts.indices) return null
            val hashString = workParts.removeAt(secondaryIndex)

            var value = workParts.joinToString("")
            if (hashString.length > 4096) value = decodeBase64Text(value)

            var hash = 0
            var xor = 0
            for (i in hashString.indices) {
                val c = hashString[i].code
                hash = (hash * 37 + c) % 241
                xor = (xor + ((c shl 1) xor i)) and 255
            }

            var state = (hash * 3 + xor) % 256
            val step = (xor % 11) + 5
            var shuffleSeed = ((xor * 251 + hash) % 65519) + 1

            for (i in operationString.length - 1 downTo 0) {
                when (val op = operationString[i]) {
                    '7' -> value = decodeAtobStrict(value) ?: return null
                    '3' -> value = value.reversed()
                    else -> {
                        val shift = (26 - ((op.code - 96) % 26)) % 26
                        value = value.map { ch ->
                            when {
                                ch in 'A'..'Z' -> ((ch.code - 65 + shift) % 26 + 65).toChar()
                                ch in 'a'..'z' -> ((ch.code - 97 + shift) % 26 + 97).toChar()
                                else -> ch
                            }
                        }.joinToString("")
                    }
                }
            }

            if (operationString.length > 2048) value = value.reversed()

            val chars = value.toCharArray()
            val swaps = IntArray(chars.size)
            for (i in chars.size - 1 downTo 1) {
                shuffleSeed = (shuffleSeed * 97 + 41) % 65519
                swaps[i] = (shuffleSeed % (i + 1)).toInt()
            }
            for (i in 1 until chars.size) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }

            value = chars.concatToString()
            val out = StringBuilder(value.length)
            for (ch in value) {
                val code = ch.code
                state = (state * 5 + step) % 256
                out.append((code xor state).toChar())
                state = (state + code) % 256
            }
            return out.toString()
        }

        return null
    }

    private fun evaluateJsStrings(
        expression: String,
        variables: Map<String, String>,
        functions: Map<String, JsFunction>,
        depth: Int = 0
    ): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        if (depth > 8) return result

        var value = expression.trim().trimEnd(';').trim()
        if (value.isBlank()) return result

        while (value.startsWith('(') && value.endsWith(')')) {
            val end = findBalancedEnd(value, 0, '(', ')')
            if (end != value.lastIndex) break
            value = value.substring(1, value.length - 1).trim()
        }

        quotedValue(value)?.let {
            result.add(it)
            return result
        }

        if (value.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"))) {
            variables[value]?.let {
                result.addAll(evaluateJsStrings(it, variables, functions, depth + 1))
                if (result.isNotEmpty()) return result
            }
        }

        val plusParts = splitTopLevel(value, '+')
        if (plusParts.size > 1) {
            val fragments = plusParts.map { part ->
                evaluateJsStrings(part, variables, functions, depth + 1).ifEmpty {
                    quotedValue(part)?.let { linkedSetOf(it) } ?: linkedSetOf(part.trim())
                }
            }

            val combined = StringBuilder()
            var possible = true
            for (fragment in fragments) {
                val piece = fragment.firstOrNull()
                if (piece == null) {
                    possible = false
                    break
                }
                combined.append(piece)
            }
            if (possible && combined.isNotBlank()) result.add(combined.toString())
            return result
        }

        val atob = Regex("(?is)^(?:window\\.)?atob\\s*\\((.*)\\)$").matchEntire(value)
        if (atob != null) {
            val args = evaluateJsStrings(atob.groupValues[1], variables, functions, depth + 1)
            args.forEach {
                val decoded = decodeBase64Text(it)
                if (decoded.isNotBlank()) result.add(decoded)
            }
            return result
        }

        val decodeUri = Regex("(?is)^(?:decodeURIComponent|decodeURI)\\s*\\((.*)\\)$").matchEntire(value)
        if (decodeUri != null) {
            val args = evaluateJsStrings(decodeUri.groupValues[1], variables, functions, depth + 1)
            args.forEach { result.add(decodeUrlEncoded(it)) }
            return result
        }

        val unescape = Regex("(?is)^unescape\\s*\\((.*)\\)$").matchEntire(value)
        if (unescape != null) {
            val args = evaluateJsStrings(unescape.groupValues[1], variables, functions, depth + 1)
            args.forEach { result.add(decodeUrlEncoded(it)) }
            return result
        }

        val charCode = Regex("(?is)^String\\.fromCharCode\\s*\\((.*)\\)$").matchEntire(value)
        if (charCode != null) {
            val chars = splitTopLevel(charCode.groupValues[1], ',')
                .map { token ->
                    token.trim().toIntOrNull()
                        ?: token.trim().removePrefix("0x").toIntOrNull(16)
                }
            if (chars.all { it != null }) {
                result.add(chars.filterNotNull().map { it.toChar() }.joinToString(""))
                return result
            }
        }

        // Basit string dönüşümleri: x.split('').reverse().join('')
        val reverseChain = Regex("(?is)^(.*?)\\.split\\(\\s*['\"]['\"]\\s*\\)\\.reverse\\(\\)\\.join\\(\\s*['\"]['\"]\\s*\\)$")
            .matchEntire(value)
        if (reverseChain != null) {
            evaluateJsStrings(reverseChain.groupValues[1], variables, functions, depth + 1)
                .forEach { result.add(it.reversed()) }
            return result
        }

        // Basit replace: value.replace('a', 'b')
        val replaceRegex = Regex("(?is)^(.*?)\\.replace\\(\\s*(['\"])(.*?)\\2\\s*,\\s*(['\"])(.*?)\\4\\s*\\)$")
            .matchEntire(value)
        if (replaceRegex != null) {
            val base = evaluateJsStrings(replaceRegex.groupValues[1], variables, functions, depth + 1)
            base.forEach {
                result.add(
                    it.replace(
                        replaceRegex.groupValues[3],
                        replaceRegex.groupValues[5]
                    )
                )
            }
            return result
        }

        // Basit slice/substring
        val sliceRegex = Regex("(?is)^(.*?)\\.(?:slice|substring)\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$")
            .matchEntire(value)
        if (sliceRegex != null) {
            val base = evaluateJsStrings(sliceRegex.groupValues[1], variables, functions, depth + 1)
            val start = sliceRegex.groupValues[2].toIntOrNull() ?: 0
            val end = sliceRegex.groupValues[3].toIntOrNull()
            base.forEach {
                if (start in 0..it.length) {
                    result.add(it.substring(start, end?.coerceIn(start, it.length) ?: it.length))
                }
            }
            return result
        }

        // Fonksiyon çağrısı ve return değeri.
        val call = Regex("^([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(value)
        if (call != null) {
            val functionName = call.groupValues[1]
            val fn = functions[functionName]
                ?: variables[functionName]
                    ?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*")) }
                    ?.let { functions[it] }
            if (fn != null) {
                val args = splitTopLevel(call.groupValues[2], ',')
                val locals = variables.toMutableMap()

                val encodedParts = args.firstOrNull()
                    ?.let { resolveJsStringList(it, variables, functions, depth + 1) }
                    .orEmpty()
                if (encodedParts.isNotEmpty()) {
                    val decoded = runCatching {
                        decodeObfuscatedJsFunction(fn, encodedParts)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank()) {
                        Log.d(TAG, "OBF DECODER=$functionName RESULT=${decoded.take(4000)}")
                        result.add(decoded)
                    }
                }

                fn.parameters.forEachIndexed { index, parameter ->
                    val rawArg = args.getOrNull(index)?.trim() ?: return@forEachIndexed
                    val evaluatedArg = evaluateJsStrings(rawArg, variables, functions, depth + 1)
                        .firstOrNull()
                    locals[parameter] = evaluatedArg ?: rawArg
                }

                // `arg.split('^')` / `arg.split('!')` kullanılan obfuscator fonksiyonlarını
                // doğrudan fonksiyon gövdesine parametre olarak bağla.
                val primaryParam = fn.parameters.firstOrNull()
                val primaryArg = args.firstOrNull()?.trim().orEmpty()
                if (primaryParam != null && primaryArg.contains(".split(")) {
                    val splitPos = primaryArg.lastIndexOf(".split(")
                    val baseExpr = primaryArg.substring(0, splitPos)
                    val sepExpr = primaryArg.substring(splitPos + ".split(".length).removeSuffix(")").trim()
                    val baseValue = evaluateJsStrings(baseExpr, variables, functions, depth + 1).firstOrNull()
                        ?: quotedValue(baseExpr)
                    val separator = quotedValue(sepExpr)
                        ?: evaluateJsStrings(sepExpr, variables, functions, depth + 1).firstOrNull()
                        ?: sepExpr.trim().trim('"', '\'')
                    val items = baseValue?.split(separator).orEmpty()

                    // return param.join('')
                    Regex("(?is)^${Regex.escape(primaryParam)}\\.join\\(\\s*(['\"])(.*?)\\1\\s*\\)$")
                        .findAll(fn.body)
                        .forEach { m -> result.add(items.joinToString(m.groupValues[2])) }

                    // return param.reverse().join('')
                    Regex("(?is)^${Regex.escape(primaryParam)}\\.reverse\\(\\)\\.join\\(\\s*(['\"])(.*?)\\1\\s*\\)$")
                        .findAll(fn.body)
                        .forEach { m -> result.add(items.asReversed().joinToString(m.groupValues[2])) }

                    // return param.map(function(x){ return EXPR; }).join('')
                    val mapJoin = Regex(
                        "(?is)^${Regex.escape(primaryParam)}\\.map\\(\\s*function\\s*\\(([^)]*)\\)\\s*\\{\\s*return\\s+(.+?);?\\s*\\}\\s*\\)\\.join\\(\\s*(['\"])(.*?)\\3\\s*\\)$"
                    )
                    fn.body.lines().map { it.trim() }.forEach { line ->
                        val m = mapJoin.matchEntire(line.removeSuffix(";"))
                        if (m != null) {
                            val itemParam = m.groupValues[1].trim()
                            val bodyExpr = m.groupValues[2].trim()
                            val transformed = items.map { item ->
                                val itemLocals = locals.toMutableMap()
                                itemLocals[itemParam] = item
                                evaluateJsStrings(bodyExpr, itemLocals, functions, depth + 1).firstOrNull() ?: item
                            }
                            result.add(transformed.joinToString(m.groupValues[4]))
                        }
                    }

                    // return param; -> parçaları birleştir.
                    if (Regex("(?is)\\breturn\\s+${Regex.escape(primaryParam)}\\s*;").containsMatchIn(fn.body)) {
                        result.add(items.joinToString(""))
                    }
                }

                Regex("(?is)\\breturn\\s+(.+?)(?:;|$)").findAll(fn.body).forEach { match ->
                    result.addAll(evaluateJsStrings(match.groupValues[1], locals, functions, depth + 1))
                }
                if (result.isNotEmpty()) return result
            }
        }

        // Array index: arr[0]
        val indexMatch = Regex("(?is)^([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\[\\s*(\\d+)\\s*]$").matchEntire(value)
        if (indexMatch != null) {
            val arrayExpression = variables[indexMatch.groupValues[1]]
            val index = indexMatch.groupValues[2].toIntOrNull() ?: -1
            if (arrayExpression != null && index >= 0) {
                val inner = arrayExpression.trim().removePrefix("[").removeSuffix("]")
                val parts = splitTopLevel(inner, ',')
                parts.getOrNull(index)?.let {
                    result.addAll(evaluateJsStrings(it, variables, functions, depth + 1))
                }
            }
            return result
        }

        return result
    }

    private fun resolveJsExpression(
        expression: String,
        variables: Map<String, String>,
        functions: Map<String, JsFunction>,
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
            val evaluated = evaluateJsStrings(value, variables, functions, depth + 1)
            evaluated.forEach {
                extractMediaFromDecoded(it, result, baseUrl)
            }
            variables[value]?.let {
                resolveJsExpression(it, variables, functions, result, baseUrl, depth + 1)
                return
            }
            if (evaluated.isNotEmpty()) return
        }

        val callName = Regex("^([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(value)
        if (callName != null) {
            val evaluated = evaluateJsStrings(value, variables, functions, depth + 1)
            evaluated.forEach { extractMediaFromDecoded(it, result, baseUrl) }
            val functionName = callName.groupValues[1]
            val fn = functions[functionName]
                ?: variables[functionName]
                    ?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*")) }
                    ?.let { functions[it] }
            if (fn != null) {
                Log.d(TAG, "JS FUNCTION CALL=$functionName PARAMS=${fn.parameters} BODY=${fn.body.take(5000)}")
            }
            if (evaluated.isNotEmpty()) return
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

        val sourceRefs = Regex(
            """(?is)(?:file|src|source|url|media|stream)\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)"""
        ).findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        sourceRefs.forEach { ref ->
            variables[ref]?.let { expr ->
                Log.d(TAG, "JS SOURCE REF=$ref EXPR=${expr.take(3000)}")
                evaluateJsStrings(ref, variables, functions).forEach { value ->
                    Log.d(TAG, "JS SOURCE VALUE=$ref -> ${value.take(2000)}")
                    extractMediaFromDecoded(value, result, baseUrl)
                }

                val callName = Regex("^([A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)\\s*\\(").matchEntire(expr.trim())?.groupValues?.getOrNull(1)
                if (callName != null) {
                    functions[callName]?.let { fn ->
                        Log.d(TAG, "JS FUNCTION SOURCE=$callName PARAMS=${fn.parameters} BODY=${fn.body.take(6000)}")
                    }
                }
            }
        }

        extractDirectStrings(text, result, baseUrl)

        /*
         * Değişkenleri çöz. Özellikle sources/configs önemli.
         */
        variables.forEach { (name, expression) ->
            if (name == "sources" || name == "configs" || name == "player" ||
                name == "q10op" || name == "n6i" || name == "tv2" ||
                name == "ojwcp" || name == "b75v2" || name == "jp0" || name == "h4i3") {
                Log.d(TAG, "JS SPECIAL=$name EXPR=${expression.take(3000)}")
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

        Regex(
            """https?://[^\s"'<>\\]+/hls(?:2)?/[^\s"'<>\\]+/master\.txt(?:\?[^\s"'<>\\]+)?""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach { addCandidate(result, it.value, baseUrl) }

        return result.filter { isValidVideoUrl(it) && !isRejectedMediaUrl(it) }.toList()
    }

    private suspend fun prepareHlsUrl(url: String, playerUrl: String): String? {
        val clean = cleanUrl(url)
        if (!isHlsCandidate(clean)) return clean
        val headers = mediaHeaders(playerUrl)
        val referer = resolveMediaReferer(playerUrl)

        return try {
            val body = app.get(
                clean,
                headers = headers,
                referer = referer,
                allowRedirects = true
            ).text

            if (!body.contains("#EXTM3U")) {
                Log.e(TAG, "MEDIA PREFLIGHT NOT HLS=$clean")
                return null
            }

            if (body.contains("#EXT-X-STREAM-INF")) {
                val hasAudioRenditions = body.contains("#EXT-X-MEDIA:TYPE=AUDIO", ignoreCase = true)
                val hasVideoRenditions = body.contains("#EXT-X-STREAM-INF", ignoreCase = true)

                // Master playlist'i koru. Özellikle dizilerde ses ayrı bir
                // #EXT-X-MEDIA:TYPE=AUDIO grubunda bulunabiliyor. İlk video
                // varyantına geçersek ExoPlayer bu ses grubunu göremez.
                if (hasAudioRenditions && hasVideoRenditions) {
                    Log.d(TAG, "MEDIA PREFLIGHT MASTER WITH AUDIO=$clean")
                    return clean
                }

                // Ayrı audio renditions yoksa bile master'ı korumak daha
                // güvenlidir; ExoPlayer kalite/ses seçimlerini kendisi yapar.
                Log.d(TAG, "MEDIA PREFLIGHT MASTER=$clean")
                return clean
            }

            Log.d(TAG, "MEDIA PREFLIGHT OK=$clean")
            clean
        } catch (e: Exception) {
            Log.e(TAG, "MEDIA PREFLIGHT FAIL=$clean ERROR=${e.message}")
            null
        }
    }

    private suspend fun addPlayerSubtitles(
        html: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emitted = linkedSetOf<String>()

        suspend fun emitSubtitle(label: String?, rawUrl: String?) {
            if (rawUrl.isNullOrBlank()) return

            val cleanedUrl = cleanUrl(rawUrl)
                .replace("\\/", "/")
                .trim()

            if (cleanedUrl.isBlank()) return

            val absolute = resolveAbsoluteUrl(cleanedUrl, playerUrl) ?: return
            if (absolute.isBlank()) return

            val key = absolute.lowercase(Locale.ROOT)
            if (!emitted.add(key)) return

            val normalizedLabel = label
                ?.replace("\\u0020", " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    key.contains("turkish") || key.contains("_tr.") || key.contains("-tr.") -> "Türkçe"
                    key.contains("english") || key.contains("_eng.") || key.contains("-eng.") -> "English"
                    key.contains("french") || key.contains("_fr.") || key.contains("-fr.") -> "French"
                    key.contains("german") || key.contains("_de.") || key.contains("-de.") -> "German"
                    else -> "Türkçe"
                }

            Log.d(TAG, "SUBTITLE FOUND=$normalizedLabel URL=$absolute")
            subtitleCallback(newSubtitleFile(normalizedLabel, absolute))
        }

        // 1) HTML <track> elemanları.
        for (track in Jsoup.parse(html, playerUrl).select("track")) {
            val raw = track.attr("src")
                .ifBlank { track.attr("data-src") }
                .ifBlank { track.attr("data-url") }

            val label = track.attr("label")
                .ifBlank { track.attr("srclang") }
                .ifBlank { track.attr("language") }

            emitSubtitle(label, raw)
        }

        // 2) JWPlayer tracks: {file:"...vtt", kind:"captions", label:"Turkish"}
        // JSON/JS içindeki escaped slash biçimleri de desteklenir.
        val trackObjectRegex = Regex(
            """(?is)\{\s*[^{}]{0,250}?[\"']file[\"']?\s*:\s*[\"']([^\"']+?\.(?:vtt|srt)(?:\?[^\"']*)?)[\"'][^{}]{0,500}?[\"']label[\"']?\s*:\s*[\"']([^\"']+)[\"'][^{}]*\}"""
        )

        for (match in trackObjectRegex.findAll(html)) {
            emitSubtitle(match.groupValues[2], match.groupValues[1])
        }

        // 3) label/file sırası ters olan JWPlayer track objeleri.
        val reverseTrackObjectRegex = Regex(
            """(?is)\{\s*[^{}]{0,500}?[\"']label[\"']?\s*:\s*[\"']([^\"']+)[\"'][^{}]{0,500}?[\"']file[\"']?\s*:\s*[\"']([^\"']+?\.(?:vtt|srt)(?:\?[^\"']*)?)[\"'][^{}]*\}"""
        )

        for (match in reverseTrackObjectRegex.findAll(html)) {
            emitSubtitle(match.groupValues[1], match.groupValues[2])
        }

        // 4) label bilgisi olmayan file/src URL'leri.
        val genericSubtitleRegex = Regex(
            """(?is)(?:[\"']?(?:file|src|subtitle|subtitleUrl|subtitle_url)[\"']?)\s*:\s*[\"']([^\"']+?\.(?:vtt|srt)(?:\?[^\"']*)?)[\"']"""
        )

        for (match in genericSubtitleRegex.findAll(html)) {
            emitSubtitle(null, match.groupValues[1])
        }

        // 5) data-src / data-file gibi HTML attribute biçimleri.
        for (element in Jsoup.parse(html, playerUrl).select("[data-src], [data-file], [data-url]")) {
            val raw = sequenceOf(
                element.attr("data-src"),
                element.attr("data-file"),
                element.attr("data-url")
            ).firstOrNull { it.contains(Regex("\\.(?:vtt|srt)(?:\\?|$)", RegexOption.IGNORE_CASE)) }

            if (!raw.isNullOrBlank()) {
                val label = element.attr("label")
                    .ifBlank { element.attr("data-label") }
                    .ifBlank { element.attr("srclang") }

                emitSubtitle(label, raw)
            }
        }

        Log.d(TAG, "SUBTITLE COUNT=${emitted.size}")
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
