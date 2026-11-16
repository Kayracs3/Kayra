package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 350L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "" to "Yeni Eklenen Diziler",
        "aksiyon" to "Aksiyon Dizi",
        "bilim-kurgu" to "Bilim Kurgu Dizi",
        "fantastik" to "Fantastik Dizi",
        "gerilim" to "Gerilim Dizi",
        "gizem" to "Gizem Dizi",
        "korku" to "Korku Dizi",
        "komedi" to "Komedi Dizi",
        "romantik" to "Romantik Dizi"
    )

    private val chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val chromeUaBrand = "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\""

    private val siteHeaders = mapOf(
        "User-Agent" to chromeUserAgent,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to chromeUserAgent,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private val fourPichiveOrigin = "https://four.pichive.online"

    private val fourPichiveResolver = WebViewResolver(
        Regex("https://(four\\.)?pichive\\.online/.*")
    )

    @Volatile private var powSalt = "Tu6UwcgeK8"

    private data class PageData(
        val document: Document,
        val secureData: JSONObject?,
        val isRateLimited: Boolean = false
    )
    private data class SourceItem(val raw: String, val quality: String)

    @Volatile private var rateLimitedUntil = 0L
    @Volatile private var lastPosterFallbackAt = 0L
    private val posterFallbackCache = ConcurrentHashMap<String, String>()
    private val noPosterSentinel = "__NO_ASYAWATCH_POSTER__"
    private val powCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun isRateLimitResponse(code: Int, body: String): Boolean {
        val lower = body.lowercase()
        return code == 1015 || code == 429 ||
            lower.contains("error 1015") ||
            lower.contains("you are being rate limited") ||
            (lower.contains("cloudflare") && lower.contains("rate limit"))
    }

    private fun noteRateLimit() {
        rateLimitedUntil = maxOf(rateLimitedUntil, System.currentTimeMillis() + 5 * 60 * 1000L)
    }

    private fun isCoolingDown(): Boolean = System.currentTimeMillis() < rateLimitedUntil

    private fun titleFromUrl(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?'))
        val slug = path.trimEnd('/').substringAfterLast('/').substringBeforeLast('.', missingDelimiterValue = path.substringAfterLast('/'))
        return slug.replace(Regex("""[-_+]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "AsyaWatch içeriği" }
    }

    private fun field(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            if (raw == JSONObject.NULL) continue
            val value = raw.toString().trim()
            if (value.isNotEmpty() && !value.equals("null", true)) return value
        }
        return null
    }

    private fun objectField(obj: JSONObject?, vararg keys: String): JSONObject? {
        if (obj == null) return null
        for (key in keys) obj.optJSONObject(key)?.let { return it }
        return null
    }

    private fun arrayField(obj: JSONObject?, vararg keys: String): JSONArray? {
        if (obj == null) return null
        for (key in keys) obj.optJSONArray(key)?.let { return it }
        return null
    }

    private fun parseObject(raw: String?): JSONObject? =
        if (raw.isNullOrBlank()) null else runCatching { JSONObject(raw) }.getOrNull()

    private fun decodeBase64Text(raw: String): String? {
        val value = raw.trim().removeSurrounding("\"")
        if (value.isBlank()) return null
        return runCatching {
            val decoded = base64Decode(value)
            String(decoded.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeApiResponse(raw: String): JSONObject? {
        val envelope = parseObject(raw) ?: return null
        val encodedResponse = field(envelope, "response") ?: return envelope
        val decoded = decodeBase64Text(encodedResponse)
        return parseObject(decoded) ?: envelope
    }

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        var value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value = value.replace("\\/", "/").replace("&amp;", "&").trim()
        if (value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("about:", true)
        ) return null

        val absolute = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> {
                val origin = runCatching {
                    val uri = URI(base)
                    "${uri.scheme}://${uri.authority}"
                }.getOrDefault(mainUrl)
                origin + value
            }
            else -> base.trimEnd('/') + "/" + value.trimStart('/')
        }
        return absolute.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
    }

    private fun imageUrl(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .trim()
            .trim('"', '\'')

        if (Regex("""(?i)^(?:[a-z0-9-]+\.)*macellan\.online(?=[:/?#]|$)""").containsMatchIn(value)) {
            value = "https://$value"
        }

        val macellanPosterPath = Regex(
            """(?i)^/?images/\d+/\d+/\d+/{1,2}tt\d+-poster-\d+\.(?:jpe?g|png|webp)(?:[?#].*)?$"""
        )
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true) &&
            macellanPosterPath.matches(value)
        ) {
            value = "https://file.macellan.online/${value.trimStart('/')}"
        }

        if (value.startsWith("https%3A%2F%2F", true)) {
            value = value.replaceFirst("https%3A%2F%2F", "https://", true)
        } else if (value.startsWith("http%3A%2F%2F", true)) {
            value = value.replaceFirst("http%3A%2F%2F", "http://", true)
        }
        return absoluteUrl(value)
    }

    private fun imageFromJson(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 5) return null
        val imageKeys = listOf(
            "poster_url", "posterUrl", "object_poster_url", "objectPosterUrl",
            "object_poster", "objectPoster", "poster_image", "posterImage",
            "poster", "img", "p", "sq", "wide", "poster_path", "posterPath", "image_url", "imageUrl",
            "image_path", "imagePath", "thumbnail_url", "thumbnailUrl",
            "thumbnail_path", "thumbnailPath", "thumbnail", "cover_url", "coverUrl",
            "cover_image", "coverImage", "cover", "image", "thumb", "picture", "photo", "src"
        )

        if (value is JSONObject) {
            for (key in imageKeys) {
                val candidate = value.opt(key)
                if (candidate is String) {
                    imageUrl(candidate)?.let { return it }
                } else if (candidate is JSONObject || candidate is JSONArray) {
                    imageFromJson(candidate, depth + 1)?.let { return it }
                }
            }
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = value.opt(key) ?: continue
                val normalizedKey = key.lowercase()
                val isImageField = listOf("poster", "image", "thumbnail", "thumb", "cover", "picture", "photo")
                    .any { normalizedKey.contains(it) }
                if (isImageField && child is String) {
                    imageUrl(child)?.let { return it }
                }
                if (child is JSONObject || child is JSONArray) {
                    imageFromJson(child, depth + 1)?.let { return it }
                }
            }
        } else if (value is JSONArray) {
            for (index in 0 until value.length()) {
                imageFromJson(value.opt(index), depth + 1)?.let { return it }
            }
        } else if (value is String) {
            return imageUrl(value)
        }
        return null
    }

    private fun imageFromElement(element: org.jsoup.nodes.Element, base: String): String? {
        val attributes = listOf(
            "data-poster", "data-cover", "data-thumbnail", "data-thumb",
            "data-original", "data-original-src", "data-lazy-src", "data-src",
            "data-srcset", "srcset", "content", "href", "src"
        )
        for (attribute in attributes) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) continue
            val first = raw.split(',').firstOrNull()?.trim()?.substringBefore(' ') ?: raw
            imageUrl(first)?.let { return it }
        }
        val style = element.attr("style")
        val cssUrl = Regex("""(?i)url\(['"]?([^)'"]+)""").find(style)?.groupValues?.getOrNull(1)
        return imageUrl(cssUrl)
    }

    private fun scoreFrom(raw: String?): Score? {
        val number = raw?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (number !in 0.0..10.0) return null
        return Score.from10(number.toString())
    }

    private fun pageSecureData(document: Document): JSONObject? {
        val script = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("script#__NEXT_DATA__")?.html()?.takeIf { it.isNotBlank() }
            ?: return null
        val pageProps = objectField(parseObject(script)?.optJSONObject("props"), "pageProps")
            ?: return null
        val secure = pageProps.opt("secureData")
        if (secure is JSONObject) return secure
        val raw = secure?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return parseObject(decodeBase64Text(raw)) ?: parseObject(raw)
    }

    private fun nextFlightPayloads(document: Document): List<String> {
        val result = ArrayList<String>()
        val marker = "self.__next_f.push([1,"
        for (script in document.select("script")) {
            val source = script.data().ifBlank { script.html() }
            var position = 0
            while (position < source.length) {
                val markerIndex = source.indexOf(marker, position)
                if (markerIndex < 0) break

                var quoteStart = markerIndex + marker.length
                while (quoteStart < source.length && source[quoteStart].isWhitespace()) quoteStart++
                if (quoteStart >= source.length || source[quoteStart] != '"') {
                    position = quoteStart + 1
                    continue
                }

                var quoteEnd = quoteStart + 1
                var escaped = false
                while (quoteEnd < source.length) {
                    val char = source[quoteEnd]
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        break
                    }
                    quoteEnd++
                }

                if (quoteEnd < source.length) {
                    val literal = source.substring(quoteStart, quoteEnd + 1)
                    val decoded = runCatching {
                        JSONArray("[$literal]").optString(0)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank()) result.add(decoded)
                    position = quoteEnd + 1
                } else {
                    break
                }
            }
        }
        return result
    }

    private fun jsonObjectAfterKey(source: String, key: String): JSONObject? {
        val token = "\"$key\""
        var searchFrom = 0
        while (searchFrom < source.length) {
            val keyIndex = source.indexOf(token, searchFrom)
            if (keyIndex < 0) return null

            val colon = source.indexOf(':', keyIndex + token.length)
            if (colon < 0) return null
            var start = colon + 1
            while (start < source.length && source[start].isWhitespace()) start++
            if (start >= source.length || source[start] != '{') {
                searchFrom = keyIndex + token.length
                continue
            }

            var depth = 0
            var inString = false
            var escaped = false
            var end = start
            while (end < source.length) {
                val char = source[end]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                return runCatching {
                                    JSONObject(source.substring(start, end + 1))
                                }.getOrNull()
                            }
                        }
                    }
                }
                end++
            }
            return null
        }
        return null
    }

    private fun nextFlightObject(document: Document, key: String): JSONObject? {
        for (payload in nextFlightPayloads(document)) {
            jsonObjectAfterKey(payload, key)?.let { return it }
        }
        return null
    }

    private fun detailCatalogEntry(document: Document, pageUrl: String): JSONObject? {
        val catalog = nextFlightObject(document, "cat") ?: return null
        val entries = arrayField(catalog, "entries") ?: return null
        val targetPath = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrDefault(pageUrl.substringBefore('?'))
            .trimEnd('/')
            .lowercase()
            .replace(Regex("""(?i)/sezon-\d+(?:/bolum-\d+)?$"""), "")

        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val rawPath = field(item, "url", "u", "href") ?: continue
            val itemPath = runCatching { URI(rawPath).path.orEmpty() }
                .getOrDefault(rawPath.substringBefore('?'))
                .trimEnd('/')
                .lowercase()
            if (itemPath == targetPath) return item
        }

        return entries.optJSONObject(0)?.takeIf { entries.length() == 1 }
    }

    private fun mergeDetailObjects(vararg sources: JSONObject?): JSONObject {
        val merged = JSONObject()
        for (source in sources) {
            if (source == null) continue
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = source.opt(key) ?: continue
                val current = merged.opt(key)
                val shouldFill = !merged.has(key) ||
                    current == null ||
                    current == JSONObject.NULL ||
                    (current is String && current.isBlank()) ||
                    (current is JSONArray && current.length() == 0 &&
                        value is JSONArray && value.length() > 0)
                if (shouldFill) merged.put(key, value)
            }
        }
        return merged
    }

    private suspend fun getPageData(url: String): PageData {
        if (isCoolingDown()) return PageData(Jsoup.parse("", url), null, isRateLimited = true)

        val response = app.get(
            url,
            headers = siteHeaders,
            referer = "$mainUrl/",
            allowRedirects = true
        )
        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            noteRateLimit()
            return PageData(Jsoup.parse(body, url), null, isRateLimited = true)
        }

        val document = response.document
        return PageData(document, pageSecureData(document))
    }

    private suspend fun postJson(url: String): JSONObject? {
        val endpoint = url.substringBefore('?').removePrefix(mainUrl)
        if (isCoolingDown()) {
            Log.w("AsyaWatch", "API isteği soğuma süresi nedeniyle atlandı: " + endpoint)
            return null
        }
        val response = runCatching {
            app.post(url, headers = apiHeaders, referer = "$mainUrl/")
        }.onFailure {
            Log.e("AsyaWatch", "API bağlantı hatası: " + endpoint + " (" + it.javaClass.simpleName + ": " + it.message + ")")
        }.getOrNull() ?: return null
        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            Log.w("AsyaWatch", "API hız sınırı yanıtı: HTTP " + response.code + " - " + endpoint)
            noteRateLimit()
            return null
        }
        if (!response.isSuccessful) {
            Log.e("AsyaWatch", "API HTTP " + response.code + " - " + endpoint)
            return null
        }
        val decoded = decodeApiResponse(body)
        if (decoded == null) {
            Log.e("AsyaWatch", "API yanıtı çözülemedi - " + endpoint)
        } else if (decoded.optJSONArray("result") == null) {
            Log.e("AsyaWatch", "API yanıtında result dizisi yok - " + endpoint)
        } else {
            Log.d("AsyaWatch", "API yanıtı alındı - " + endpoint + "; kayıt=" + decoded.optJSONArray("result")!!.length())
        }
        return decoded
    }

    private fun searchResponseFrom(item: JSONObject, forceSeries: Boolean = false): SearchResponse? {
        val rawUrl = field(item, "used_slug", "slug", "url", "u", "href", "path") ?: return null
        val url = absoluteUrl(rawUrl) ?: return null
        val originalTitle = field(item, "original_title", "object_name", "title", "name", "t") ?: return null
        val cultureTitle = field(item, "culture_title", "cultureTitle")
        val title = if (!cultureTitle.isNullOrBlank() && !cultureTitle.equals(originalTitle, true)) {
            "$originalTitle - $cultureTitle"
        } else originalTitle
        val poster = imageFromJson(item)
            ?: imageUrl(field(
                item,
                "poster_url", "posterUrl", "object_poster_url", "objectPosterUrl",
                "poster", "img", "p", "sq", "wide", "image_url", "imageUrl", "thumbnail", "thumbnail_url", "cover"
            ))
        val score = scoreFrom(field(item, "imdb_point", "imdbPoint", "score", "rating", "im", "sc"))
        val type = field(item, "used_type", "type", "content_type", "kind").orEmpty()
        val isSeries = when {
            type.contains("movie", true) || type.contains("film", true) -> false
            type.contains("series", true) || type.contains("dizi", true) || type.contains("tv", true) -> true
            else -> forceSeries || url.contains("/dizi/", true)
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }
        }
    }

    private suspend fun getExploreJson(url: String): JSONObject? {
        val endpoint = url.substringBefore('?').removePrefix(mainUrl)
        if (isCoolingDown()) {
            Log.w("AsyaWatch", "Keşfet isteği soğuma süresi nedeniyle atlandı: " + endpoint)
            return null
        }

        val response = runCatching {
            app.get(url, headers = apiHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull() ?: return null

        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            noteRateLimit()
            return null
        }
        if (!response.isSuccessful) return null

        val root = parseObject(body) ?: return null
        if (!root.optBoolean("ok", true)) return null

        val result = root.optJSONObject("result")
        val items = result?.optJSONArray("items")
        if (items != null) {
            Log.d("AsyaWatch", "Keşfet - " + endpoint +
                "; kayıt=" + items.length() +
                "; sayfa=" + result.optInt("page", 1) +
                "/" + result.optInt("pages", 1))
        }
        return root
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val isSeries = request.name.contains("Dizi", true)
        val kind = if (isSeries) "dizi" else "film"
        val category = URLEncoder.encode(request.data.trim(), "UTF-8")
        val endpointUrl = "$mainUrl/api/asya/kesfet?kind=$kind&ulke=&tur=$category&sirala=yeni&page=$page"

        val root = getExploreJson(endpointUrl)
            ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val resultNode = root.optJSONObject("result")
        val items = resultNode?.optJSONArray("items")
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val response = searchResponseFrom(item, isSeries) ?: continue
                if (seen.add(response.url)) results.add(response)
            }
        }

        val completedResults = fillMissingPosters(results)
        val totalPages = resultNode?.optInt("pages", -1) ?: -1
        val hasNext = if (totalPages >= 0) {
            page < totalPages
        } else {
            completedResults.size >= (resultNode?.optInt("pageSize", 42) ?: 42)
        }

        Log.d("AsyaWatch", "Ana sayfa: " + request.name + "; bulunan=" + completedResults.size + "; sonraki=" + hasNext)
        return newHomePageResponse(request, completedResults, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = postJson("$mainUrl/api/bg/searchcontent?searchterm=$encoded") ?: return emptyList()
        val items = arrayField(root, "result") ?: return emptyList()
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val response = searchResponseFrom(item) ?: continue
            if (seen.add(response.url)) results.add(response)
        }
        return fillMissingPosters(results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun detailTitle(content: JSONObject?, document: Document, pageUrl: String): String {
        val original = field(content, "original_title", "originalTitle", "title", "t", "name")
        val culture = field(content, "culture_title", "cultureTitle")
        if (!original.isNullOrBlank() && !original.contains("Error 1015", true)) {
            return if (!culture.isNullOrBlank() && !culture.equals(original, true)) {
                "$original - $culture"
            } else original
        }
        return document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: titleFromUrl(pageUrl)
    }

    private fun normalizedImageTitle(value: String): String =
        value.lowercase().replace("&amp;", "and").replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun detailPoster(content: JSONObject?, document: Document): String? {
        imageFromJson(content)?.let { return it }

        for (selector in listOf(
            "meta[property=og:image]", "meta[name=twitter:image]",
            "meta[property=og:image:secure_url]", "link[rel=image_src]"
        )) {
            document.selectFirst(selector)?.let { el ->
                imageFromElement(el, mainUrl)?.let { return it }
            }
        }

        for (selector in listOf(
            ".poster img", ".movie-poster img", ".drama-poster img",
            "img[class*=poster]", "img[data-poster]"
        )) {
            for (el in document.select(selector)) {
                imageFromElement(el, mainUrl)?.let { return it }
            }
        }
        return null
    }

    private suspend fun fillMissingPosters(items: List<SearchResponse>): List<SearchResponse> {
        if (items.none { it.posterUrl.isNullOrBlank() }) return items
        for (item in items) {
            if (!item.posterUrl.isNullOrBlank()) continue
            val cached = posterFallbackCache[item.url]
            if (cached != null) {
                if (cached != noPosterSentinel) item.posterUrl = cached
                continue
            }
            if (isCoolingDown()) continue
            val page = runCatching { getPageData(item.url) }.getOrNull() ?: continue
            if (page.isRateLimited) continue
            val poster = detailPoster(objectField(page.secureData, "contentItem"), page.document)
            posterFallbackCache[item.url] = poster ?: noPosterSentinel
            if (!poster.isNullOrBlank()) item.posterUrl = poster
        }
        return items
    }

    private fun detailYear(content: JSONObject?, document: Document): Int? {
        field(content, "release_year", "year", "y")?.toIntOrNull()?.let { return it }
        return Regex("""\b(?:19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()
    }

    private fun detailTags(content: JSONObject?): List<String>? {
        val genreArray = arrayField(content, "g", "genres", "categories")
        if (genreArray != null) {
            val values = (0 until genreArray.length()).mapNotNull { 
                genreArray.optString(it).trim().takeIf(String::isNotBlank)
            }.distinct()
            if (values.isNotEmpty()) return values
        }
        val raw = field(content, "categories", "tags", "genres", "g") ?: return null
        return raw.removePrefix("[").removeSuffix("]").split(',', '|')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() && !it.equals("null", true) }
            .distinct().takeIf { it.isNotEmpty() }
    }

    private fun detailActors(related: JSONObject?): List<Actor> {
        val rows = arrayField(related, "cast", "casts")
            ?: arrayField(objectField(related, "getMovieCastsById"), "result")
            ?: return emptyList()
        val actors = ArrayList<Actor>()
        val seen = HashSet<String>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val actorName = field(item, "name", "actor_name") ?: continue
            if (seen.add(actorName)) {
                actors.add(Actor(actorName, imageUrl(field(item, "cast_image", "image", "poster", "ph"))))
            }
        }
        return actors
    }

    private fun detailTrailer(related: JSONObject?): String? {
        val rows = arrayField(objectField(related, "getContentTrailers"), "result") ?: return null
        for (i in 0 until rows.length()) {
            field(rows.optJSONObject(i), "raw_url", "url")?.let { return absoluteUrl(it) }
        }
        return null
    }

    private fun parseEpisodesFromDocument(document: Document, poster: String?, pageUrl: String): List<Episode> {
        val result = LinkedHashMap<String, Episode>()
        val pagePath = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrDefault(pageUrl.substringBefore('?')).trimEnd('/')
        val pageSeason = Regex("""(?i)/sezon-(\d+)""")
            .find(pagePath)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        for (element in document.select(".eps a[href*='/bolum-'], .elist a.er[href*='/bolum-']")) {
            if (element.attr("aria-disabled").equals("true", true) || element.hasClass("fut")) continue
            val rawUrl = element.attr("href").trim()
            val number = Regex("""(?i)/bolum-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val season = Regex("""(?i)/sezon-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
            val label = element.selectFirst(".tx b, .t1")?.text()?.trim()
            val absolute = absoluteUrl(rawUrl, pageUrl) ?: continue
            if (result.containsKey(absolute)) continue
            result[absolute] = newEpisode(absolute) {
                name = label?.takeIf { it.isNotBlank() } ?: "Bölüm $number"
                this.season = season
                episode = number
                posterUrl = poster
            }
        }
        return result.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    override suspend fun load(url: String): LoadResponse {
        val page = getPageData(url)
        val root = page.secureData
        val flightDrama = nextFlightObject(page.document, "drama")
        val flightFilm = nextFlightObject(page.document, "film")
        val legacyContent = objectField(root, "contentItem")
        val content = mergeDetailObjects(flightDrama, flightFilm, legacyContent)
        val related = objectField(root, "RelatedResults") ?: content

        val title = detailTitle(content, page.document, url)
        val poster = detailPoster(content, page.document)
        val description = field(content, "syn", "description", "plot", "overview")
            ?: page.document.selectFirst("meta[name=description]")?.attr("content")
        val year = detailYear(content, page.document)
        val score = scoreFrom(field(content, "imdb_point", "score", "rating", "sc"))
        val tags = detailTags(content)
        val actors = detailActors(content).ifEmpty { detailActors(related) }
        val trailer = detailTrailer(related)
        val episodes = parseEpisodesFromDocument(page.document, poster, url)
        val isSeries = url.contains("/dizi/", true) || episodes.isNotEmpty()

        Log.d("AsyaWatch", "Detay: $title; bölüm=${episodes.size}")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.score = score
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.score = score
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun looksLikePlayer(url: String): Boolean {
        val value = url.lowercase()
        return Regex("""\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(value) ||
            listOf("embed", "player", "iframe", "four.pichive.online", "pichive.online", "nm.php")
                .any { value.contains(it) }
    }

    private fun extractCandidates(raw: String, base: String): List<String> {
        val result = LinkedHashSet<String>()
        val normalized = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val doc = runCatching { Jsoup.parse(normalized, base) }.getOrNull()

        doc?.select("iframe, video, source")?.forEach { el ->
            for (attr in listOf("src", "data-src")) {
                absoluteUrl(el.attr(attr), base)?.let { result.add(it) }
            }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+\.(?:m3u8|mp4|php\?[^\s"'<>]+)""")
            .findAll(normalized).forEach { m ->
                absoluteUrl(m.value, base)?.let { 
                    if (looksLikePlayer(it)) result.add(it) 
                }
            }
        return result.toList()
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(url)

    private fun isNmPhpUrl(url: String): Boolean =
        url.contains("/nm.php", true)

    private fun qualityValue(label: String, url: String): Int {
        val q = Regex("""(?i)(2160|1080|720|480|360|240|4k)""")
            .find("$label $url")?.value?.lowercase() ?: return Qualities.Unknown.value
        return when (q) {
            "2160", "4k" -> Qualities.P2160.value
            "1080" -> Qualities.P1080.value
            "720" -> Qualities.P720.value
            "480" -> Qualities.P480.value
            "360" -> Qualities.P360.value
            "240" -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun solvePow(id: String, algorithm: String, variant: String): Pair<Long, String>? {
        val cacheKey = "$id|$algorithm|$variant|$powSalt"
        powCache[cacheKey]?.let { return it }

        val digest = MessageDigest.getInstance(algorithm)
        val maxIter = 500_000
        val label = "$algorithm-$variant"

        for (i in 0 until maxIter) {
            val input = "$powSalt$id.$i"
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            val value = when (variant) {
                "wordBE" -> ((hash[16].toInt() and 0xFF) shl 24) or
                            ((hash[17].toInt() and 0xFF) shl 16) or
                            ((hash[18].toInt() and 0xFF) shl 8) or
                            (hash[19].toInt() and 0xFF)
                "wordLE" -> ((hash[19].toInt() and 0xFF) shl 24) or
                            ((hash[18].toInt() and 0xFF) shl 16) or
                            ((hash[17].toInt() and 0xFF) shl 8) or
                            (hash[16].toInt() and 0xFF)
                else -> return null
            }
            if ((value and 0xFFF) == 0) {
                val result = i.toLong() to label
                powCache[cacheKey] = result
                if (powCache.size > 32) powCache.keys.firstOrNull { it != cacheKey }?.let(powCache::remove)
                Log.d("AsyaWatch", "PoW bulundu: n=$i ($label)")
                return result
            }
        }
        return null
    }

    private fun extractCookiesFromHeaders(headers: Headers?): String? {
        if (headers == null) return null
        val cookies = mutableListOf<String>()
        for (name in headers.names()) {
            if (name.equals("Set-Cookie", true)) {
                for (v in headers.values(name)) {
                    val part = v.substringBefore(';').trim()
                    if (part.isNotBlank()) cookies.add(part)
                }
            }
        }
        return cookies.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private fun browserLikeHeaders(iframeUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to chromeUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to iframeUrl,
            "Origin" to fourPichiveOrigin,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Storage-Access" to "active",
            "sec-ch-ua" to chromeUaBrand,
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Priority" to "u=1, i"
        )
    }

    private fun parseNpConfig(html: String): JSONObject? {
        val regex = Regex("""window\.NP_CONFIG\s*=\s*(\{.+?\});\s*(?:window\.|</script>|$)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        return parseObject(match.groupValues[1].trim())
    }

    // =====================================================================
    // TEŞHİS: iframe HTML'deki PoW JS'i logla
    // =====================================================================
    private fun logPowJs(html: String) {
        val patterns = listOf(
            "salt", "nonce", "difficulty", "0xFFF", "0xfff", "4095",
            "SHA-256", "SHA256", "sha256", "substring", "substr", "slice",
            "toLowerCase", "toString(16)", "& 0xFFF", "& 4095"
        )
        for (pattern in patterns) {
            val idx = html.indexOf(pattern, ignoreCase = true)
            if (idx >= 0) {
                val start = maxOf(0, idx - 150)
                val end = minOf(html.length, idx + 250)
                val context = html.substring(start, end).replace("\n", " ").replace(Regex("\\s+"), " ")
                Log.d("AsyaWatch", "🔍 HTML'de '$pattern' → ...$context...")
            }
        }
    }

    // =====================================================================
    // TEŞHİS: NP_CONFIG tam içeriği logla
    // =====================================================================
    private fun logNpConfig(npConfig: JSONObject?) {
        if (npConfig == null) {
            Log.d("AsyaWatch", "📋 NP_CONFIG null")
            return
        }
        val pretty = runCatching { npConfig.toString(2) }.getOrNull() ?: npConfig.toString()
        Log.d("AsyaWatch", "📋 NP_CONFIG tam: ${pretty.take(3000)}")

        // Bilinen alanları ayrıca logla
        for (key in listOf("id", "salt", "s", "difficulty", "algorithm", "rounds", "sources", "subtitles")) {
            val value = npConfig.opt(key)
            if (value != null && value != JSONObject.NULL) {
                Log.d("AsyaWatch", "   NP_CONFIG.$key = ${value.toString().take(200)}")
            }
        }
    }

    private suspend fun tryPowOnUrl(
        nmUrl: String,
        iframeUrl: String,
        id: String,
        cookies: String?
    ): String? {
        val variants = listOf(
            Triple("SHA-256", "wordBE", solvePow(id, "SHA-256", "wordBE")),
            Triple("SHA-256", "wordLE", solvePow(id, "SHA-256", "wordLE")),
            Triple("SHA-1", "wordBE", solvePow(id, "SHA-1", "wordBE")),
            Triple("SHA-1", "wordLE", solvePow(id, "SHA-1", "wordLE"))
        ).filter { it.third != null }

        if (variants.isEmpty()) {
            Log.w("AsyaWatch", "Hiçbir PoW varyantı çözülemedi")
            return null
        }

        val vMatch = Regex("""[?&]v=([^&]+)""").find(nmUrl)
        val vToken = vMatch?.groupValues?.get(1) ?: ""
        val baseUrl = nmUrl.substringBefore('?')

        for ((alg, variantName, solution) in variants) {
            val (powValue, _) = solution ?: continue
            val label = "$alg-$variantName"

            val correctUrl = if (vToken.isNotBlank())
                "$baseUrl?v=$vToken&p=$powValue"
            else nmUrl

            val headers = browserLikeHeaders(iframeUrl) + buildMap {
                put("X-PoW", powValue.toString())
                if (!cookies.isNullOrBlank()) put("Cookie", cookies)
            }

            // 1. DENEME: app.get direct
            val directResp = runCatching {
                app.get(correctUrl, headers = headers, referer = iframeUrl)
            }.getOrNull()

            if (directResp != null) {
                Log.d("AsyaWatch", "PoW [$label] (direct) p=$powValue: HTTP ${directResp.code}")
                if (directResp.code == 200 && directResp.text.startsWith("#EXTM3U")) {
                    Log.d("AsyaWatch", "✔ PoW BAŞARILI (direct)")
                    return correctUrl
                }
            }

            // 2. DENEME: WebViewResolver (Cloudflare challenge çözer)
            val wvResp = runCatching {
                app.get(
                    correctUrl,
                    headers = headers,
                    referer = iframeUrl,
                    interceptor = fourPichiveResolver
                )
            }.getOrNull()

            if (wvResp != null) {
                Log.d("AsyaWatch", "PoW [$label] (WebView) p=$powValue: HTTP ${wvResp.code}, bodyLen=${wvResp.text.length}")
                if (wvResp.code == 200) {
                    val body = wvResp.text.trim()
                    if (body.startsWith("#EXTM3U")) {
                        Log.d("AsyaWatch", "✔ PoW BAŞARILI (WebView)")
                        return correctUrl
                    }
                    if (body.contains(".m3u8", true) || body.contains(".mp4", true)) {
                        Regex("""(?i)https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
                            .find(body)?.value?.let { 
                                Log.d("AsyaWatch", "✔ PoW BAŞARILI (WebView, içerik URL)")
                                return it 
                            }
                    }
                }
            }
        }

        return null
    }

    private suspend fun extractFourPichive(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = browserLikeHeaders(iframeUrl)

        // ⭐ ADIM 1: Cloudflare warmup — challenge'ı tetikle
        Log.d("AsyaWatch", "🔥 Cloudflare warmup başlıyor...")
        runCatching {
            app.get(
                "https://four.pichive.online/",
                headers = browserLikeHeaders("https://four.pichive.online/"),
                interceptor = fourPichiveResolver
            )
        }.onFailure { Log.w("AsyaWatch", "warmup hata: ${it.message}") }

        // ⭐ ADIM 2: iframe.php'yi WebViewResolver ile yükle (cf_clearance için)
        Log.d("AsyaWatch", "📥 iframe.php WebViewResolver ile yükleniyor...")
        val response = runCatching {
            app.get(
                iframeUrl,
                headers = headers,
                referer = referer,
                allowRedirects = true,
                interceptor = fourPichiveResolver
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) {
            Log.e("AsyaWatch", "FourPichive iframe HTTP ${response.code}")
            return false
        }

        val html = response.text
        if (html.isBlank()) {
            Log.e("AsyaWatch", "FourPichive iframe boş yanıt")
            return false
        }

        Log.d("AsyaWatch", "iframe HTML: ${html.length} bytes, NP_CONFIG: ${html.contains("NP_CONFIG")}")

        val cookies = extractCookiesFromHeaders(response.headers)
        Log.d("AsyaWatch", "iframe cookies: ${cookies?.take(300) ?: "yok"}")

        val npConfig = parseNpConfig(html)
        logNpConfig(npConfig)
        logPowJs(html)

        val npId = npConfig?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
        val npSalt = npConfig?.optString("salt")?.takeIf { it.isNotBlank() && it != "null" }
            ?: npConfig?.optString("s")?.takeIf { it.isNotBlank() && it != "null" }
            ?: npConfig?.optString("secret")?.takeIf { it.isNotBlank() && it != "null" }

        if (npSalt != null && npSalt != powSalt) {
            Log.d("AsyaWatch", "⚠ Salt güncellendi: $powSalt → $npSalt")
            powSalt = npSalt
            powCache.clear()
        }

        Log.d("AsyaWatch", "NP_CONFIG özet: id=$npId, salt=${npSalt ?: "(varsayılan $powSalt)"}")

        // Altyazılar
        val localSeenSubs = HashSet<String>()
        runCatching {
            npConfig?.optJSONArray("subtitles")?.let { subsArray ->
                for (i in 0 until subsArray.length()) {
                    val sub = subsArray.optJSONObject(i) ?: continue
                    val label = sub.optString("label", "Altyazı")
                    val url = sub.optString("url")
                    if (url.isNotBlank()) {
                        val subtitle = SubtitleFile(lang = label, url = url)
                        val subHeaders = siteHeaders.toMutableMap()
                        subHeaders["Referer"] = iframeUrl
                        subHeaders["Origin"] = fourPichiveOrigin
                        subHeaders["Accept"] = "text/vtt, text/plain, */*"
                        if (!cookies.isNullOrBlank()) subHeaders["Cookie"] = cookies
                        subtitle.headers = subHeaders
                        if (localSeenSubs.add(url)) subtitleCallback(subtitle)
                    }
                }
            }
        }

        val masterUrl = npConfig?.optJSONArray("sources")?.optJSONObject(0)?.optString("master")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: Regex("""(?i)"master"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() }

        if (masterUrl.isNullOrBlank()) {
            Log.e("AsyaWatch", "FourPichive: master URL yok")
            return false
        }

        Log.d("AsyaWatch", "master: ${masterUrl.take(100)}")

        var finalUrl = masterUrl
        if (html.contains("NP_GATE") && npId != null) {
            Log.d("AsyaWatch", "NP_GATE PoW deneniyor...")
            val powResult = tryPowOnUrl(masterUrl, iframeUrl, npId, cookies)
            if (powResult != null) {
                finalUrl = powResult
                Log.d("AsyaWatch", "✔ PoW BAŞARILI → ${finalUrl.take(100)}")
            } else {
                Log.w("AsyaWatch", "⚠ PoW başarısız, fallback URL verilecek")
            }
        }

        val isHls = finalUrl.contains(".m3u8", true) || isNmPhpUrl(finalUrl)
        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val linkHeaders = headers.toMutableMap()
        if (!cookies.isNullOrBlank()) linkHeaders["Cookie"] = cookies

        callback(
            newExtractorLink(source = name, name = "$name FourPichive", url = finalUrl, type = type) {
                this.referer = iframeUrl
                this.headers = linkHeaders
                this.quality = qualityValue("", finalUrl)
            }
        )
        Log.d("AsyaWatch", "URL verildi (type=$type)")
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val emittedUrls = HashSet<String>()
        val seenCandidates = HashSet<String>()
        val seenSubtitleUrls = LinkedHashSet<String>()
        val chosenSubtitles = LinkedHashMap<String, SubtitleFile>()

        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val lang = subtitle.lang
            if (lang == "Türkçe" || lang == "English") {
                chosenSubtitles.putIfAbsent(lang, subtitle)
            }
        }

        suspend fun processCandidate(raw: String, referer: String, quality: String) {
            val candidate = absoluteUrl(raw, referer) ?: return
            if (!seenCandidates.add(candidate)) return
            if (candidate.startsWith("<", true)) return

            if (isDirectMedia(candidate) && !isNmPhpUrl(candidate)) {
                if (emittedUrls.add(candidate)) {
                    val type = if (candidate.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(source = name, name = "$name ${quality.ifBlank { "Kaynak" }}", url = candidate, type = type) {
                        this.referer = referer
                        this.quality = qualityValue(quality, candidate)
                    })
                    found = true
                }
                return
            }

            val host = runCatching { URI(candidate).host?.lowercase() }.getOrNull()
            val isFourPichive = host == "four.pichive.online" || host?.endsWith(".four.pichive.online") == true ||
                host == "pichive.online" || host?.endsWith(".pichive.online") == true

            if (isFourPichive) {
                Log.d("AsyaWatch", "FourPichive extractor: ${candidate.take(100)}")
                val ok = runCatching {
                    extractFourPichive(candidate, referer, emitSubtitle) { link ->
                        if (emittedUrls.add(link.url)) { callback(link); found = true }
                    }
                }.getOrDefault(false)
                if (!ok) Log.w("AsyaWatch", "FourPichive başarısız")
                return
            }

            runCatching {
                loadExtractor(candidate, referer, emitSubtitle) { link ->
                    if (emittedUrls.add(link.url)) callback(link)
                    found = true
                }
            }
        }

        if (isDirectMedia(data) && !isNmPhpUrl(data)) processCandidate(data, "$mainUrl/", "")

        val page = runCatching { getPageData(data) }.getOrNull()
        if (page != null) {
            val pageCandidates = extractCandidates(page.document.html(), data)
            Log.d("AsyaWatch", "Sayfa adayları: ${pageCandidates.size}")
            for (candidate in pageCandidates) {
                processCandidate(candidate, data, "")
            }
        }

        chosenSubtitles.values.forEach { subtitleCallback(it) }
        return found
    }
}
