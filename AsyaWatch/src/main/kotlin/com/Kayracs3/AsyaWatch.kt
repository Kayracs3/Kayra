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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * AsyaWatch sağlayıcısı. API yanıtları, detay metadatası, bölüm listeleri ve
 * oynatıcı kaynakları ayrı ele alınır. Kaynak yoksa sahte bir oynatma bağlantısı
 * üretilmez; bir player başarısız olduğunda kalan kaynaklar yine denenir.
 */
class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Kategori isteklerini paralel göndermek Cloudflare 1015 riskini artırıyor.
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

    private val siteHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

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

        // Some API poster fields contain the Macellan host but omit the URL scheme.
        // Without this, the host is incorrectly appended to the AsyaWatch origin.
        if (Regex("""(?i)^(?:[a-z0-9-]+\.)*macellan\.online(?=[:/?#]|$)""").containsMatchIn(value)) {
            value = "https://$value"
        }

        // The Macellan CDN sometimes stores a poster path without its hostname.
        // Only apply this CDN rule to its recognizable IMDb-poster path shape.
        val macellanPosterPath = Regex(
            """(?i)^/?images/\d+/\d+/\d+/{1,2}tt\d+-poster-\d+\.(?:jpe?g|png|webp)(?:[?#].*)?$"""
        )
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true) &&
            macellanPosterPath.matches(value)
        ) {
            value = "https://file.macellan.online/${value.trimStart('/')}"
        }

        // AsyaWatch kartları AMP görsel proxy URL'sini doğrudan kullanıyor.
        // Proxy alan adını kaynak CDN'ye çevirmek bazı istemcilerde görseli bozuyor;
        // bu yüzden gelen AMP URL'sini aynen koruyoruz.

        // The API occasionally percent-encodes the scheme and slashes.
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
            // srcset may contain multiple candidates, each followed by a width descriptor.
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

    /**
     * Next.js App Router payloads are streamed in self.__next_f.push([1, "..."]).
     * Decode those string chunks so structured page data can be read without relying
     * on the removed __NEXT_DATA__/secureData format.
     */
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

    /**
     * Extract a balanced JSON object after a quoted property name inside a
     * decoded React Server Components payload.
     */
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

    /**
     * AsyaWatch App Router detail pages keep the card/catalogue record in data.cat.entries,
     * while data.drama/data.film contains supplementary detail metadata.
     */
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

    /**
     * Keep the catalogue record as the primary source (img, g, sc, cast, sch),
     * then fill absent fields from the drama/film detail object and legacy data.
     */
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
            val isHtml = body.trimStart().startsWith("<") ||
                body.contains("<html", true) ||
                body.contains("Attention Required", true)
            Log.e("AsyaWatch", "API HTTP " + response.code + " - " + endpoint + "; bodyLength=" + body.length + "; htmlOrChallenge=" + isHtml)
            return null
        }
        val decoded = decodeApiResponse(body)
        if (decoded == null) {
            val isHtml = body.trimStart().startsWith("<") ||
                body.contains("<html", true) ||
                body.contains("Attention Required", true)
            Log.e("AsyaWatch", "API yanıtı çözülemedi - " + endpoint + "; HTTP " + response.code + "; bodyLength=" + body.length + "; htmlOrChallenge=" + isHtml)
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
        }.onFailure {
            Log.e("AsyaWatch", "Keşfet bağlantı hatası: " + endpoint + " (" + it.javaClass.simpleName + ": " + it.message + ")")
        }.getOrNull() ?: return null

        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            Log.w("AsyaWatch", "Keşfet hız sınırı yanıtı: HTTP " + response.code + " - " + endpoint)
            noteRateLimit()
            return null
        }
        if (!response.isSuccessful) {
            val isHtml = body.trimStart().startsWith("<") ||
                body.contains("<html", true) ||
                body.contains("Attention Required", true)
            Log.e("AsyaWatch", "Keşfet HTTP " + response.code + " - " + endpoint +
                "; bodyLength=" + body.length + "; htmlOrChallenge=" + isHtml)
            return null
        }

        val root = parseObject(body)
        if (root == null) {
            Log.e("AsyaWatch", "Keşfet JSON yanıtı çözülemedi - " + endpoint +
                "; bodyLength=" + body.length)
            return null
        }
        if (!root.optBoolean("ok", true)) {
            Log.e("AsyaWatch", "Keşfet API ok=false döndürdü - " + endpoint)
            return null
        }

        val result = root.optJSONObject("result")
        val items = result?.optJSONArray("items")
        if (items == null) {
            Log.e("AsyaWatch", "Keşfet yanıtında result.items yok - " + endpoint)
        } else {
            Log.d("AsyaWatch", "Keşfet yanıtı alındı - " + endpoint +
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

        Log.d("AsyaWatch", "Ana sayfa: " + request.name + "; tür=" + kind +
            "; kategori=" + request.data + "; bulunan=" + completedResults.size +
            "; sonraki=" + hasNext)
        return newHomePageResponse(request, completedResults, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = postJson("$mainUrl/api/bg/searchcontent?searchterm=$encoded")
            ?: return emptyList()
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
        val heading = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("Error 1015", true) && !it.contains("rate limited", true) }
        if (heading != null) return heading

        val openGraph = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("Error 1015", true) && !it.contains("rate limited", true) }
        if (openGraph != null) return openGraph

        val pageTitle = document.title().trim()
        if (pageTitle.isNotBlank() && !pageTitle.contains("Error 1015", true) &&
            !pageTitle.contains("rate limited", true) && !pageTitle.contains("Just a moment", true)
        ) return pageTitle
        return titleFromUrl(pageUrl)
    }

    private fun normalizedImageTitle(value: String): String =
        value.lowercase()
            .replace("&amp;", "and")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun detailPoster(content: JSONObject?, document: Document): String? {
        // Structured API data first: it usually points to the exact poster used in cards.
        imageFromJson(content)?.let { return it }

        val metadataSelectors = listOf(
            "meta[property=og:image]",
            "meta[property=og:image:url]",
            "meta[property=og:image:secure_url]",
            "meta[name=twitter:image]",
            "meta[name=twitter:image:src]",
            "meta[itemprop=image]",
            "link[rel=image_src]"
        )
        for (selector in metadataSelectors) {
            val element = document.selectFirst(selector) ?: continue
            imageFromElement(element, mainUrl)?.let { return it }
        }

        // AsyaWatch poster cards often use generic classes such as "lazyload w-full";
        // the reliable identifiers are the image alt text and the Macellan URL itself.
        // Match the page's actual title first so a logo, avatar, or unrelated image is
        // not accidentally returned as this film's poster.
        val expectedTitles = listOfNotNull(
            field(content, "original_title", "originalTitle", "title", "object_name", "name", "t"),
            field(content, "culture_title", "cultureTitle"),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        ).map(::normalizedImageTitle).filter { it.length >= 4 }.distinct()

        val pageImages = document.select("img, picture source, [style*=background]")
        val titleMatchedImages = pageImages.filter { element ->
            val labels = listOf(
                element.attr("alt"),
                element.attr("title"),
                element.attr("aria-label"),
                element.attr("data-title"),
                element.parent()?.attr("title").orEmpty(),
                element.parent()?.attr("aria-label").orEmpty()
            ).map(::normalizedImageTitle).filter { it.length >= 4 }
            labels.any { label ->
                expectedTitles.any { title ->
                    label == title || label.contains(title) || title.contains(label)
                }
            }
        }
        for (element in titleMatchedImages) {
            imageFromElement(element, mainUrl)?.let { return it }
        }

        // If titles are missing from the markup, prefer Macellan's known poster filename
        // pattern (for example .../tt39328047-poster-1791557492.jpg?v=4) to generic images.
        for (element in pageImages) {
            val candidate = imageFromElement(element, mainUrl) ?: continue
            val path = runCatching { URI(candidate).path.orEmpty() }
                .getOrDefault(candidate.substringBefore('?'))
            if (Regex("""(?i)/(?:images/\d+/\d+/\d+/{1,2})?tt\d+-poster-\d+\.(?:jpe?g|png|webp)$""")
                    .containsMatchIn(path) ||
                path.contains("/poster/", true) ||
                path.contains("-poster.", true)
            ) return candidate
        }

        // Prefer image elements that are explicitly labelled as poster/cover artwork.
        val posterSelectors = listOf(
            ".poster img", ".poster-image", ".movie-poster img", ".drama-poster img",
            ".cover img", ".thumbnail img", "img[class*=poster]", "img[id*=poster]",
            "img[data-poster]", "img[data-cover]", "img[data-thumbnail]"
        )
        for (selector in posterSelectors) {
            for (element in document.select(selector)) {
                imageFromElement(element, mainUrl)?.let { return it }
            }
        }

        // The original site lazy-loads many images through data-* attributes.
        for (element in document.select("img, picture source, [style*=background]")) {
            imageFromElement(element, mainUrl)?.let { return it }
        }

        // JSON-LD often contains an image even when the card/API poster field is empty.
        for (script in document.select("script[type=application/ld+json]")) {
            val json = parseObject(script.data().ifBlank { script.html() })
            imageFromJson(json)?.let { return it }
        }
        return null
    }

    /**
     * A small number of the site's API entries omit the poster even though the
     * content page contains it. Repair only those results and do not replace
     * a poster already supplied by the API.
     */
    private suspend fun fillMissingPosters(items: List<SearchResponse>): List<SearchResponse> {
        if (items.none { it.posterUrl.isNullOrBlank() }) return items

        // Apply every cached result first. The old implementation returned as soon as
        // it encountered the first cached item, so later missing posters in the same
        // catalogue were never considered.
        var attemptedPageRequest = false
        for (item in items) {
            if (!item.posterUrl.isNullOrBlank()) continue

            val cached = posterFallbackCache[item.url]
            if (cached != null) {
                if (cached != noPosterSentinel) item.posterUrl = cached
                continue
            }

            // Keep network fallback conservative: extra page requests in a catalogue
            // can trigger Cloudflare Error 1015. Try at most one uncached detail page
            // per call, while still applying cached posters to all other entries.
            if (attemptedPageRequest || isCoolingDown()) continue
            val now = System.currentTimeMillis()
            if (now - lastPosterFallbackAt < 12 * 1000L) continue

            attemptedPageRequest = true
            lastPosterFallbackAt = now
            val page = runCatching { getPageData(item.url) }.getOrNull() ?: continue
            if (page.isRateLimited) continue

            val poster = runCatching {
                detailPoster(objectField(page.secureData, "contentItem", "content_item"), page.document)
            }.getOrNull()
            posterFallbackCache[item.url] = poster ?: noPosterSentinel
            if (!poster.isNullOrBlank()) item.posterUrl = poster
        }
        return items
    }

    private fun detailYear(content: JSONObject?, document: Document): Int? {
        field(content, "release_year", "releaseYear", "year", "y")?.toIntOrNull()?.let { return it }
        return Regex("""\b(?:19|20)\d{2}\b""")
            .find(document.selectFirst("body")?.text().orEmpty())?.value?.toIntOrNull()
    }

    private fun detailTags(content: JSONObject?): List<String>? {
        val genreArray = arrayField(content, "g", "genres", "categories")
        if (genreArray != null) {
            val values = (0 until genreArray.length())
                .mapNotNull { genreArray.optString(it).trim().takeIf(String::isNotBlank) }
                .distinct()
            if (values.isNotEmpty()) return values
        }

        val raw = field(content, "categories", "tags", "genres", "g") ?: return null
        val tags = raw.removePrefix("[").removeSuffix("]")
            .split(',', '|')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() && !it.equals("null", true) }
            .distinct()
        return tags.takeIf { it.isNotEmpty() }
    }

    private fun detailActors(related: JSONObject?): List<Actor> {
        // The current detail payload's cast array is authoritative.
        // Older getMovieCastsById data remains a compatibility fallback.
        val cast = objectField(related, "getMovieCastsById", "getContentCastsById")
        val rows = arrayField(related, "cast", "casts")
            ?: arrayField(cast, "result")
            ?: return emptyList()
        val actors = ArrayList<Actor>()
        val seen = HashSet<String>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val actorName = field(item, "name", "actor_name", "title") ?: continue
            if (seen.add(actorName)) {
                actors.add(
                    Actor(
                        actorName,
                        imageUrl(field(item, "cast_image", "castImage", "image", "poster", "ph"))
                    )
                )
            }
        }
        return actors
    }

    /**
     * New AsyaWatch detail pages render the full cast as .cast > .cc cards.
     * Read names and roles from the visible markup, and ignore SVG placeholder portraits.
     */
    private fun detailActorsFromDocument(document: Document): List<Actor> {
        val actors = ArrayList<Actor>()
        val seen = HashSet<String>()
        for (card in document.select(".cast .cc")) {
            val name = card.selectFirst("a.mj-cl")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(name)) continue
            val role = card.selectFirst("div > span")?.text()?.trim()
            val rawImage = card.selectFirst("img")?.let { image ->
                image.attr("src").ifBlank {
                    image.attr("data-src").ifBlank { image.attr("data-original") }
                }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:image/", true) }
            actors.add(Actor(name, imageUrl(rawImage)))
        }
        return actors
    }

    private fun detailTrailer(related: JSONObject?): String? {
        val trailers = objectField(related, "getContentTrailers", "getTrailers") ?: return null
        val rows = arrayField(trailers, "result") ?: return null
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val raw = field(item, "raw_url", "rawUrl", "url", "raw") ?: continue
            absoluteUrl(raw)?.let { return it }
        }
        return null
    }

    private fun parseEpisodes(related: JSONObject?, poster: String?): List<Episode> {
        // The current AsyaWatch detail payload stores published episodes directly in sch.
        // Do not walk the whole object: unrelated nested data can contain other n/url fields.
        val schedule = arrayField(related, "sch") ?: return emptyList()
        val result = LinkedHashMap<String, Episode>()

        for (index in 0 until schedule.length()) {
            val item = schedule.optJSONObject(index) ?: continue
            val rawUrl = field(item, "url", "used_slug", "slug", "episode_url", "href")
                ?: continue
            val url = absoluteUrl(rawUrl) ?: continue

            // Future schedule rows are not playable; keep only published entries.
            val isOn = field(item, "on")
            if (isOn != null && (isOn.equals("false", true) || isOn == "0")) continue

            // The canonical episode number and season are encoded in the actual URL.
            val episodeNumber = Regex("""(?i)/bolum-(\d+)""")
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: field(item, "n", "episode_no", "episodeNo", "episode")?.toIntOrNull()
                ?: continue
            val seasonNumber = Regex("""(?i)/sezon-(\d+)""")
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: field(item, "season_no", "seasonNo", "season")?.toIntOrNull()
                ?: 1
            val episodeName = field(item, "episode_text", "epText", "title", "name")
                ?: "Bölüm $episodeNumber"

            result[url] = newEpisode(url) {
                name = episodeName
                season = seasonNumber
                episode = episodeNumber
                posterUrl = poster
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    /**
     * The server-rendered detail HTML already contains links for published episodes.
     * Use these links as a fallback when a client receives a partial Flight payload.
     */
    private fun parseEpisodesFromDocument(
        document: Document,
        poster: String?,
        pageUrl: String
    ): List<Episode> {
        val result = LinkedHashMap<String, Episode>()
        val pagePath = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrDefault(pageUrl.substringBefore('?'))
            .trimEnd('/')
        val seriesPath = pagePath.replace(
            Regex("""(?i)/sezon-\d+(?:/bolum-\d+)?$"""),
            ""
        ).trimEnd('/')
        val pageSeason = Regex("""(?i)/sezon-(\d+)""")
            .find(pagePath)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        fun addEpisode(rawUrl: String, number: Int, season: Int, label: String?) {
            val absolute = absoluteUrl(rawUrl) ?: return
            if (result.containsKey(absolute)) return
            result[absolute] = newEpisode(absolute) {
                name = label?.takeIf { it.isNotBlank() } ?: "Bölüm $number"
                this.season = season
                episode = number
                posterUrl = poster
            }
        }

        // Original AsyaWatch episode board: .eps links, title inside .tx b.
        val epsLinks = document.select(".eps a[href*='/bolum-']")
        for (element in epsLinks) {
            if (element.attr("aria-disabled").equals("true", true) ||
                element.hasClass("fut")
            ) continue
            val rawUrl = element.attr("href").trim()
            val number = Regex("""(?i)/bolum-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val season = Regex("""(?i)/sezon-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
            val label = element.selectFirst(".tx b")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.selectFirst(".tx")?.text()?.trim()
            addEpisode(rawUrl, number, season, label)
        }

        // Newer pages may also render the same board as .elist.
        val elistLinks = document.select(".elist a.er[href*='/bolum-']")
        for (element in elistLinks) {
            if (element.attr("aria-disabled").equals("true", true) ||
                element.hasClass("fut")
            ) continue
            val rawUrl = element.attr("href").trim()
            val number = Regex("""(?i)/bolum-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val season = Regex("""(?i)/sezon-(\d+)""")
                .find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
            val label = element.selectFirst(".t1")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.attr("aria-label")
            addEpisode(rawUrl, number, season, label)
        }

        // A currently playing .elist episode has no href; reconstruct it from its label.
        val current = document.selectFirst(".elist a.er.cur[aria-current=true]")
        if (current != null && seriesPath.startsWith("/dizi/", true)) {
            val label = current.selectFirst(".t1")?.text()
                ?.ifBlank { current.attr("aria-label") }
                .orEmpty()
            val number = Regex("""(?i)(\d+)\s*\.?\s*bölüm""")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (number != null) {
                addEpisode(
                    "$seriesPath/sezon-$pageSeason/bolum-$number",
                    number,
                    pageSeason,
                    current.selectFirst(".t1")?.text() ?: "Bölüm $number"
                )
            }
        }

        Log.d(
            "AsyaWatch",
            "HTML bölüm panosu: eps=${epsLinks.size}; elist=${elistLinks.size}; çıkan=${result.size}"
        )
        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val page = getPageData(url)
        val root = page.secureData
        val flightDrama = nextFlightObject(page.document, "drama")
        val flightFilm = nextFlightObject(page.document, "film")
        val catalogEntry = detailCatalogEntry(page.document, url)
        val legacyContent = objectField(root, "contentItem", "content_item")
        val content = mergeDetailObjects(catalogEntry, flightDrama, flightFilm, legacyContent)
        val related = objectField(root, "RelatedResults", "relatedResults") ?: content
        val title = detailTitle(content, page.document, url)
        val poster = detailPoster(content, page.document)
        val description = field(flightDrama, "syn")
            ?: field(content, "syn", "description", "plot", "overview", "d", "desc")
            ?: page.document.selectFirst("meta[name=description]")?.attr("content")
            ?: page.document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = detailYear(content, page.document)
        val score = scoreFrom(field(content, "imdb_point", "imdbPoint", "score", "rating", "sc"))
        val tags = detailTags(content) ?: page.document
            .select(".tags a.tag")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
        val actors = detailActors(content)
            .ifEmpty { detailActors(related) }
            .ifEmpty { detailActorsFromDocument(page.document) }
        val trailer = detailTrailer(related)
        val duration = field(content, "total_minutes", "totalMinutes", "duration", "min")?.toIntOrNull()
        // Prefer the new sch array; if it cannot yield episodes, use the original .eps HTML board.
        val episodes = parseEpisodes(content, poster)
            .ifEmpty { parseEpisodesFromDocument(page.document, poster, url) }
            .ifEmpty { parseEpisodes(related, poster) }
        val isSeries = url.contains("/dizi/", true) ||
            field(content, "used_slug", "usedSlug", "url")?.contains("/dizi/", true) == true ||
            objectField(related, "getSerieSeasonAndEpisodes", "getSeriesSeasons") != null

        val scheduleCount = arrayField(content, "sch")?.length() ?: 0
        Log.d("AsyaWatch", "Detay: $title; katalog=${catalogEntry != null}; sch=$scheduleCount; tür=${tags?.size ?: 0}; kadro=${actors.size}; bölüm=${episodes.size}; yıl=$year")

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
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun sourceItems(root: JSONObject?, isSeries: Boolean): List<SourceItem> {
        if (root == null) return emptyList()
        val related = objectField(root, "RelatedResults", "relatedResults") ?: return emptyList()
        val result = ArrayList<SourceItem>()

        fun collect(holder: JSONObject?) {
            val rows = arrayField(holder, "result", "sources") ?: return
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val raw = field(row, "source_content", "sourceContent", "url", "link") ?: continue
                val quality = field(row, "quality_name", "qualityName", "quality").orEmpty()
                result.add(SourceItem(raw, quality))
            }
        }

        if (isSeries) {
            collect(objectField(related, "getEpisodeSources", "getEpisodeSource"))
        } else {
            val parts = arrayField(objectField(related, "getMoviePartsById", "getMovieParts"), "result", "parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val id = field(part, "id", "part_id", "partId") ?: continue
                    val key = "getMoviePartSourcesById_$id"
                    collect(objectField(related, key) ?: objectField(root, key))
                }
            }
            collect(objectField(related, "getMoviePartSourcesById", "getMovieSources"))
        }
        return result.distinctBy { it.raw to it.quality }
    }

    private fun looksLikePlayer(url: String): Boolean {
        val value = url.lowercase()
        return Regex("""\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(value) ||
            listOf(
                "embed", "player", "video", "stream", "watch", "iframe", "hls",
                "filemoon", "vidmoly", "youtube", "youtu.be", "vimeo", "dood",
                "streamwish", "voe.sx", "ok.ru", "sibnet", "mixdrop", "uqload",
                "mp4upload", "asianload", "googlevideo", "/e/"
            ).any { value.contains(it) }
    }

    private fun extractCandidates(raw: String, base: String): List<String> {
        val result = LinkedHashSet<String>()
        val normalized = raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\u003d", "=")
        val document = runCatching { Jsoup.parse(normalized, base) }.getOrNull()
        val attributes = listOf(
            "src", "data-src", "data-lazy-src", "data-url", "data-embed",
            "data-player", "data-video", "data-file", "data-iframe", "href"
        )

        document?.select("iframe, video, source, embed")?.forEach { element ->
            for (attribute in attributes) {
                val candidate = absoluteUrl(element.attr(attribute), base) ?: continue
                result.add(candidate)
            }
        }
        document?.select("[data-src], [data-lazy-src], [data-url], [data-embed], [data-player], [data-video], [data-file], [data-iframe]")?.forEach { element ->
            for (attribute in attributes) {
                val candidate = absoluteUrl(element.attr(attribute), base) ?: continue
                if (looksLikePlayer(candidate)) result.add(candidate)
            }
        }

        val trimmed = normalized.trim().trim('"', '\'')
        absoluteUrl(trimmed, base)?.let { candidate ->
            if (looksLikePlayer(candidate) || trimmed.startsWith("http", true) || trimmed.startsWith("//")) {
                result.add(candidate)
            }
        }
        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""").findAll(normalized).forEach { match ->
            val candidate = absoluteUrl(match.value.trimEnd('\\', ')', ']', ',', ';'), base) ?: return@forEach
            if (looksLikePlayer(candidate)) result.add(candidate)
        }
        Regex("""(?i)(?:src|url|file|source)\s*[:=]\s*["']([^"']+)["']""").findAll(normalized).forEach { match ->
            val candidate = absoluteUrl(match.groupValues[1], base) ?: return@forEach
            if (looksLikePlayer(candidate)) result.add(candidate)
        }
        return result.toList()
    }

    private fun looksLikeSubtitle(url: String): Boolean {
        val value = url.lowercase()
        return Regex("""(?i)\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt)(?:[?#].*)?$""").containsMatchIn(value) ||
            // Several players expose translated subtitles as a dedicated HLS playlist,
            // for example /sub_turkish/subtitles.m3u8. This is not a video master playlist.
            Regex("""(?i)/(?:sub_[a-z0-9_-]+/)?(?:subtitles?|captions?)(?:\.m3u8?)?(?:[?#].*)?$""").containsMatchIn(value) ||
            value.contains("/subtitle/") || value.contains("/subtitles/") ||
            value.contains("/caption/") || value.contains("/captions/") ||
            value.contains("/subtitle?") || value.contains("/subtitles?") ||
            value.contains("/caption?") || value.contains("/captions?") ||
            value.endsWith("/subtitle") || value.endsWith("/subtitles") ||
            value.endsWith("/caption") || value.endsWith("/captions")
    }

    private fun normalizeSubtitleText(text: String): String {
        var value = text
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\x3a", ":", ignoreCase = true)
            .replace("\\x2f", "/", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&#x2f;", "/", ignoreCase = true)

        value = Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        value = Regex("""\\x([0-9a-fA-F]{2})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

        // Yalnızca URL'nin başındaki yüzde-kaçışlı şemayı aç; imzalı query değerlerini bozma.
        value = value.replace(Regex("""(?i)^https%3a%2f%2f"""), "https://")
            .replace(Regex("""(?i)^http%3a%2f%2f"""), "http://")
        return value
    }

    private fun explicitSubtitleLanguage(value: String): String? {
        val normalized = value.lowercase()
            .replace("_", "-")
            .trim()
        return when {
            normalized.contains("turk") || normalized.contains("türk") ||
                Regex("""(?:^|[^a-z])(?:tr|tur|turkish|turkce)(?:$|[^a-z])""").containsMatchIn(normalized) -> "Türkçe"
            normalized.contains("english") || normalized.contains("ingiliz") ||
                Regex("""(?:^|[^a-z])(?:en|eng)(?:$|[^a-z])""").containsMatchIn(normalized) -> "English"
            normalized.contains("korean") || normalized.contains("korece") ||
                Regex("""(?:^|[^a-z])ko(?:$|[^a-z])""").containsMatchIn(normalized) -> "Korean"
            normalized.contains("japanese") || normalized.contains("japonca") ||
                Regex("""(?:^|[^a-z])ja(?:$|[^a-z])""").containsMatchIn(normalized) -> "Japanese"
            normalized.contains("spanish") || normalized.contains("español") ||
                Regex("""(?:^|[^a-z])(?:es|spa)(?:$|[^a-z])""").containsMatchIn(normalized) -> "Spanish"
            normalized.contains("french") || normalized.contains("français") ||
                Regex("""(?:^|[^a-z])(?:fr|fra)(?:$|[^a-z])""").containsMatchIn(normalized) -> "French"
            normalized.contains("german") || normalized.contains("deutsch") ||
                Regex("""(?:^|[^a-z])(?:de|deu)(?:$|[^a-z])""").containsMatchIn(normalized) -> "German"
            else -> null
        }
    }

    private fun subtitleUrlQueryLanguage(url: String): String? {
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        if (query.isBlank()) return null

        // Only inspect parameters whose names explicitly indicate a subtitle language;
        // unrelated signed token/query values must never determine the language.
        val languageKeys = setOf(
            "lang", "language", "locale", "srclang",
            "sub_lang", "sub_language", "subtitle_lang", "subtitle_language",
            "caption_lang", "caption_language"
        )
        for (part in query.split('&')) {
            val rawKey = part.substringBefore('=', "")
            if (rawKey.isBlank()) continue
            val key = runCatching {
                URLDecoder.decode(rawKey.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(rawKey).lowercase()
            if (key !in languageKeys) continue

            val rawValue = part.substringAfter('=', "")
            val value = runCatching {
                URLDecoder.decode(rawValue.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(rawValue)
            explicitSubtitleLanguage(value)?.let { return it }
        }
        return null
    }

    private fun subtitleUrlLanguage(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?'))
        val segments = path.split('/').filter { it.isNotBlank() }

        // Trust explicit language folders and file names first. Some providers instead
        // put a language code in a query parameter such as ?lang=tr or ?language=turkish.
        for (directory in segments.dropLast(1).asReversed()) {
            val language = when {
                directory.startsWith("sub_", true) -> directory.substring(4)
                directory.startsWith("sub-", true) -> directory.substring(4)
                else -> continue
            }
            explicitSubtitleLanguage(language)?.let { return it }
        }
        explicitSubtitleLanguage(segments.lastOrNull().orEmpty())?.let { return it }
        return subtitleUrlQueryLanguage(url)
    }

    private fun subtitleLanguage(label: String, url: String = ""): String {
        // Prefer a clear language-specific URL over a mislabeled PlayerJS track, then
        // use the player's language label when the URL itself is generic.
        subtitleUrlLanguage(url)?.let { return it }
        explicitSubtitleLanguage(label)?.let { return it }

        val cleanLabel = label.trim()
        val genericLabels = setOf(
            "", "file", "src", "url", "track", "subtitle", "subtitles",
            "caption", "captions", "default", "und", "unknown"
        )
        return if (cleanLabel.lowercase() !in genericLabels && cleanLabel.length < 40) {
            cleanLabel
        } else {
            "Altyazı"
        }
    }

    private fun resolveSubtitleUrl(raw: String?, baseUrl: String): String? {
        var value = normalizeSubtitleText(raw?.trim().orEmpty())
        if (value.isBlank()) return null
        value = value.trim().trim('"', '\'')

        if (value.startsWith("data:", true) || value.startsWith("javascript:", true)) return null
        return runCatching {
            val uri = URI(value)
            if (uri.isAbsolute) uri.toString()
            else URI(baseUrl).resolve(value).toString()
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun subtitleUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val body = normalizeSubtitleText(text)
        val result = LinkedHashMap<String, String>()

        val explicitlyLabeledUrls = HashSet<String>()

        fun add(raw: String?, label: String = "", forceSubtitle: Boolean = false) {
            if (raw.isNullOrBlank()) return
            val cleaned = raw.trim().trim('"', '\'', '<', '>', ',', ';', '\\').trimEnd(')', ']', '}')
            val url = resolveSubtitleUrl(cleaned, baseUrl) ?: return
            if (looksLikeSubtitle(url) || forceSubtitle) {
                val language = subtitleLanguage(label, url)
                val pathLanguage = subtitleUrlLanguage(url)
                val explicitLanguage = pathLanguage ?: explicitSubtitleLanguage(label)
                val previous = result[url]

                // Keep the URL's own explicit language when the player label has been
                // attached to another track by a broad match.
                if (explicitLanguage != null) {
                    result[url] = language
                    explicitlyLabeledUrls.add(url)
                } else if (!explicitlyLabeledUrls.contains(url) &&
                    (previous == null || previous == "Altyazı")
                ) {
                    result[url] = language
                }
            }
        }

        // PlayerJS stores tracks with commas or packed together as
        // [English].../en.vtt[Turkish].../tr.vtt. Stop each URL before the next '['.
        val taggedPlayerTrack = Regex(
            """(?i)\[\s*([^\]]{1,40})\s*\]\s*((?:https?:)?//[^,\s\[\]"']+|/[^,\s\[\]"']+|\.\.?/[^,\s\[\]"']+|[^,\s\[\]"']+\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt|m3u8?)(?:\?[^,\s\[\]"']*)?)"""
        )

        val playerSubtitleField = Regex(
            """(?is)\b(?:subtitle|subtitles|captions?)\b\s*[:=]\s*["']([^"']{1,8000})["']"""
        )
        playerSubtitleField.findAll(body).forEach { match ->
            val config = match.groupValues[1]
            val entries = taggedPlayerTrack.findAll(config).toList()
            if (entries.isNotEmpty()) {
                entries.forEach { add(it.groupValues[2], it.groupValues[1], forceSubtitle = true) }
            } else {
                config.split(',').map { it.trim() }.filter { candidate ->
                    candidate.startsWith("http://", true) ||
                        candidate.startsWith("https://", true) ||
                        candidate.startsWith("//") ||
                        candidate.startsWith("/")
                }.forEach { add(it, "Altyazı", forceSubtitle = true) }
            }
        }

        // Inline PlayerJS variant: file:"...m3u8(subtitle:[English]... [Turkish]...)"
        Regex("""(?is)\(\s*subtitle(?:s)?\s*:\s*([^)]+)\)""")
            .findAll(body)
            .forEach { match ->
                taggedPlayerTrack.findAll(match.groupValues[1]).forEach { entry ->
                    add(entry.groupValues[2], entry.groupValues[1], forceSubtitle = true)
                }
            }

        // Pair each label and URL only inside the same object to avoid cross-track pairing.
        val objectLabel = Regex(
            """(?is)["']?(?:label|srclang|language|lang|name)["']?\s*[:=]\s*["']([^"']{1,40})["']"""
        )
        val objectUrl = Regex(
            """(?is)["']?(?:file|src|url|subtitle)["']?\s*[:=]\s*["']([^"']{1,1200})["']"""
        )
        Regex("""(?is)\{[^{}]{2,2200}\}""").findAll(body).forEach { objectMatch ->
            val trackObject = objectMatch.value
            val label = objectLabel.find(trackObject)?.groupValues?.getOrNull(1) ?: return@forEach
            val url = objectUrl.find(trackObject)?.groupValues?.getOrNull(1) ?: return@forEach
            add(url, label, forceSubtitle = true)
        }

        Regex(
            """(?i)(?:https?:)?//[^"'<>\s]+?\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt|m3u8?)(?:\?[^"'<>\s]*)?"""
        ).findAll(body).forEach { add(it.value) }

        Regex(
            """(?i)["']((?:(?:https?:)?//|/|\.\.?/)?[^"'<>\s]{1,900}\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt|m3u8?)(?:\?[^"']*)?)["']"""
        ).findAll(body).forEach { add(it.groupValues[1]) }

        Regex(
            """(?i)(subtitle|subtitles|caption|captions|subtitleUrl|subtitle_url|captionUrl|caption_url|track|file|src)\s*["']?\s*[:=]\s*["']([^"']+)["']"""
        ).findAll(body).forEach { match ->
            val value = match.groupValues[2]
            val tagged = Regex("""(?i)\[\s*([^\]]{1,40})\s*\]\s*([^,\s]+)""").findAll(value).toList()
            if (tagged.isNotEmpty()) {
                tagged.forEach { add(it.groupValues[2], it.groupValues[1]) }
            } else {
                add(value, match.groupValues[1])
            }
        }

        body.lineSequence()
            .filter { it.contains("TYPE=SUBTITLES", true) }
            .forEach { line ->
                val uri = Regex("""(?i)\bURI\s*=\s*["']([^"']+)["']""")
                    .find(line)?.groupValues?.getOrNull(1) ?: return@forEach
                val label = Regex("""(?i)\b(?:NAME|LANGUAGE)\s*=\s*["']([^"']+)["']""")
                    .find(line)?.groupValues?.getOrNull(1).orEmpty()
                add(uri, label, forceSubtitle = true)
            }

        return result.map { it.key to it.value }
    }

    private fun publishSubtitlesFromText(
        text: String,
        baseUrl: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit,
        referer: String = baseUrl
    ) {
        fun emit(url: String, label: String) {
            val subtitle = SubtitleFile(
                lang = subtitleLanguage(label, url),
                url = url
            )
            // Altyazı sunucuları çoğu zaman oynatıcıdaki UA/Referer olmadan isteği reddeder.
            // SubtitleFile başlıklarını doldurmak, doğrudan VTT/SRT ve HLS altyazı URL'lerinde
            // tarayıcı ile CloudStream arasındaki bu farkı azaltır.
            val requestHeaders = siteHeaders.toMutableMap()
            requestHeaders["Referer"] = referer
            requestHeaders["Accept"] = "text/vtt, text/plain, */*"
            val origin = runCatching {
                val uri = URI(referer)
                if (uri.scheme != null && uri.authority != null) {
                    "${uri.scheme}://${uri.authority}"
                } else null
            }.getOrNull()
            if (!origin.isNullOrBlank()) requestHeaders["Origin"] = origin
            subtitle.headers = requestHeaders
            callback(subtitle)
        }

        val variants = LinkedHashSet<String>()
        val normalized = normalizeSubtitleText(text)
        variants.add(normalized)

        // Bazı API'ler source_content alanını tek parça Base64 olarak döndürüyor.
        val compact = normalized.filterNot { it.isWhitespace() }.trim('"', '\'')
        if (compact.length in 80..200000 &&
            Regex("""^[A-Za-z0-9+/]+={0,2}$""").matches(compact)
        ) {
            val decoded = decodeBase64Text(compact)
            if (!decoded.isNullOrBlank() &&
                Regex("""(?is)(subtitle|caption|playerjs|\.vtt|\.srt|\.m3u8|https?://)""").containsMatchIn(decoded)
            ) {
                variants.add(normalizeSubtitleText(decoded))
            }
        }

        for (payload in variants) {
            subtitleUrls(payload, baseUrl).forEach { (url, label) ->
                // A URL may first appear without a language label and later reappear
                // correctly tagged as Turkish/English. Do not let the early generic
                // discovery suppress the later, useful language metadata.
                if (seen.add(url) ||
                    subtitleUrlLanguage(url) != null ||
                    explicitSubtitleLanguage(label) != null
                ) {
                    emit(url, label)
                }
            }

            val document = runCatching { Jsoup.parse(payload, baseUrl) }.getOrNull() ?: continue
            document.select("track[src], track[data-src], track[data-url]").forEach { track ->
                val raw = track.attr("src")
                    .ifBlank { track.attr("data-src") }
                    .ifBlank { track.attr("data-url") }
                val url = resolveSubtitleUrl(raw, baseUrl) ?: return@forEach
                val label = track.attr("label")
                    .ifBlank { track.attr("srclang") }
                    .ifBlank { track.attr("data-lang") }
                    .ifBlank { "Altyazı" }
                val explicitTrack = label != "Altyazı" ||
                    track.attr("kind").equals("subtitles", true) ||
                    track.attr("kind").equals("captions", true)
                if (!looksLikeSubtitle(url) && !explicitTrack) return@forEach

                // As with parsed PlayerJS entries, allow a later explicit language
                // label to refresh an URL that was first discovered generically.
                val firstDiscovery = seen.add(url)
                if (!firstDiscovery &&
                    subtitleUrlLanguage(url) == null &&
                    explicitSubtitleLanguage(label) == null
                ) return@forEach
                emit(url, label)
            }
        }
    }

    private suspend fun extractSubtitlesFromManifest(
        manifestUrl: String,
        referer: String,
        seen: MutableSet<String>,
        seenManifests: MutableSet<String>,
        callback: (SubtitleFile) -> Unit,
        depth: Int = 0
    ) {
        if (depth > 1 || !manifestUrl.contains(".m3u8", true) || !seenManifests.add(manifestUrl)) return
        val response = runCatching {
            app.get(
                manifestUrl,
                headers = siteHeaders,
                referer = referer,
                allowRedirects = true
            )
        }.getOrNull() ?: return
        if (!response.isSuccessful) return

        publishSubtitlesFromText(response.text, manifestUrl, seen, callback, referer)
        if (depth >= 1) return

        val nested = response.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains(".m3u8", true) }
            .mapNotNull { resolveSubtitleUrl(it, manifestUrl) }
            .distinct()
            .take(2)
            .toList()
        for (url in nested) {
            extractSubtitlesFromManifest(
                url, referer, seen, seenManifests, callback, depth + 1
            )
        }
    }

    private suspend fun extractSubtitlesFromPlayerPage(
        pageUrl: String,
        referer: String,
        seen: MutableSet<String>,
        seenPages: MutableSet<String>,
        seenManifests: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        if (!seenPages.add(pageUrl) || isDirectMedia(pageUrl)) return
        val isFourPichive = runCatching {
            URI(pageUrl).host.equals("four.pichive.online", true)
        }.getOrDefault(false)
        val response = runCatching {
            app.get(
                pageUrl,
                headers = siteHeaders,
                referer = referer,
                allowRedirects = true
            )
        }.onFailure {
            if (isFourPichive) {
                Log.e(
                    "AsyaWatch",
                    "FourPichive isteği başarısız: ${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }.getOrNull() ?: return

        val html = response.text
        if (isFourPichive) {
            val document = Jsoup.parse(html, pageUrl)
            val inlineScripts = document.select("script:not([src])")
                .joinToString("\n") { it.data() }
            val inspected = html + "\n" + inlineScripts
            val markers = listOf(
                "openPlayer", "NP_CONFIG", "source2.php", "m3u8", "mp4",
                "jwplayer", "videojs", "playlist", "token", "fetch(", "XMLHttpRequest"
            ).joinToString("; ") { marker ->
                "$marker=${inspected.contains(marker, true)}"
            }
            val scripts = document.select("script[src]")
                .map { it.absUrl("src").ifBlank { it.attr("src") } }
                .filter { it.isNotBlank() }
                .distinct()
                .take(10)
                .joinToString(" | ") { it.substringBefore("?") }

            // NP_CONFIG alan adlarını bul; geçici oynatma tokenlarını/değerlerini loglama.
            val configIndex = inspected.indexOf("NP_CONFIG", ignoreCase = true)
            val configText = if (configIndex >= 0) {
                inspected.substring(configIndex, minOf(inspected.length, configIndex + 7000))
            } else ""
            val configKeys = Regex("""["']?([A-Za-z_$][\\w$-]*)["']?\\s*:""")
                .findAll(configText)
                .map { it.groupValues[1] }
                .distinct()
                .take(50)
                .joinToString(",")
            val configShape = if (configKeys.isBlank()) "alan adı çıkarılamadı" else configKeys

            // Parse NP_CONFIG as JSON and report source shape without query tokens.
            val cfgStart = configText.indexOf('{')
            var cfgEnd = -1
            if (cfgStart >= 0) {
                var depth = 0
                var quoted = false
                var escaped = false
                for (index in cfgStart until configText.length) {
                    val ch = configText[index]
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (ch == '\\\\') escaped = true
                        else if (ch == '"') quoted = false
                    } else {
                        when (ch) {
                            '"' -> quoted = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    cfgEnd = index + 1
                                    break
                                }
                            }
                        }
                    }
                }
            }
            val configObject = if (cfgEnd > cfgStart && cfgStart >= 0) {
                runCatching { JSONObject(configText.substring(cfgStart, cfgEnd)) }.getOrNull()
            } else null

            fun safeUrlShape(raw: String): String {
                val uri = runCatching { URI(raw) }.getOrNull()
                return if (uri?.host != null) {
                    "${uri.scheme}://${uri.host}${uri.path.orEmpty()}".take(180)
                } else {
                    "metin(${raw.length})"
                }
            }

            fun sourceObjectShape(item: JSONObject): String {
                val keys = item.keys().asSequence().sorted().joinToString(",")
                val urls = listOf("file", "url", "src", "source", "master", "hls", "m3u8")
                    .mapNotNull { key ->
                        val value = item.opt(key) as? String
                        if (value.isNullOrBlank()) null else "$key=${safeUrlShape(value)}"
                    }
                return "alanlar={$keys}" +
                    if (urls.isEmpty()) "" else "; adresler={${urls.joinToString("|")}}"
            }

            fun configValueShape(value: Any?): String = when (value) {
                is JSONObject -> sourceObjectShape(value)
                is JSONArray -> {
                    (0 until minOf(value.length(), 5)).mapNotNull { index ->
                        when (val item = value.opt(index)) {
                            is JSONObject -> "[$index]${sourceObjectShape(item)}"
                            is String -> "[$index]=${safeUrlShape(item)}"
                            else -> null
                        }
                    }.joinToString("; ").ifBlank { "array(${value.length()})" }
                }
                is String -> safeUrlShape(value)
                null, JSONObject.NULL -> "null"
                else -> value.javaClass.simpleName
            }

            val configValues = if (configObject == null) {
                "NP_CONFIG JSON ayrıştırılamadı"
            } else {
                listOf("sources", "master", "url", "tok", "gate", "embed", "srv", "ident")
                    .mapNotNull { key ->
                        if (!configObject.has(key)) null
                        else "$key=${configValueShape(configObject.opt(key))}"
                    }
                    .joinToString("; ")
            }

            // np.js içindeki istek kodlarının çevresini göster; query/token değerlerini maskele.
            val npScript = scripts.split(" | ").firstOrNull { it.contains("/np.js", true) }
            var npScriptInfo = "np.js bulunamadı"
            if (!npScript.isNullOrBlank()) {
                val scriptResponse = runCatching {
                    app.get(npScript, headers = siteHeaders, referer = pageUrl, allowRedirects = true)
                }.getOrNull()
                if (scriptResponse != null) {
                    val scriptBody = scriptResponse.text
                    val scriptMarkers = listOf(
                        "NP_CONFIG", "Hls", "loadSource", "attachMedia", "fetch(",
                        "XMLHttpRequest", "playlist", "source", "token", "decrypt"
                    ).joinToString("; ") { marker ->
                        "$marker=${scriptBody.contains(marker, true)}"
                    }
                    val contexts = listOf("fetch(", "XMLHttpRequest", "loadSource(", "NP_CONFIG.sources")
                        .mapNotNull { marker ->
                            val index = scriptBody.indexOf(marker, ignoreCase = true)
                            if (index < 0) null else {
                                val from = maxOf(0, index - 180)
                                val to = minOf(scriptBody.length, index + 360)
                                scriptBody.substring(from, to)
                                    .replace(Regex("""(?i)(token|tok|key|sig|auth)(\\s*[:=]\\s*["']?)[^"'&\\s,)}]+"""), "$1$2[redacted]")
                                    .replace(Regex("""([?&][^=&#\\s]+)=([^&#"'\\s]+)"""), "$1=[redacted]")
                                    .replace(Regex("""\\s+"""), " ")
                            }
                        }
                        .distinct()
                        .take(4)
                        .joinToString(" || ")
                    npScriptInfo = "HTTP=${scriptResponse.code}; chars=${scriptBody.length}; " +
                        "$scriptMarkers; kod parçaları=$contexts"
                } else {
                    npScriptInfo = "np.js isteği başarısız"
                }
            }

            Log.w(
                "AsyaWatch",
                "FourPichive yanıtı: HTTP ${response.code}; başarılı=${response.isSuccessful}; " +
                    "karakter=${html.length}; başlık=${document.title()}; " +
                    "video=${document.select("video").size}; source=${document.select("video source, source").size}; " +
                    "iframe=${document.select("iframe").size}; track=${document.select("track").size}"
            )
            Log.w("AsyaWatch", "FourPichive işaretleri: $markers")
            Log.w("AsyaWatch", "FourPichive NP_CONFIG yapısı: $configShape")
            Log.w("AsyaWatch", "FourPichive NP_CONFIG kaynakları: $configValues")
            Log.w("AsyaWatch", "FourPichive np.js analizi: $npScriptInfo")
            Log.d("AsyaWatch", "FourPichive scriptleri: $scripts")
        }
        if (!response.isSuccessful) return

        publishSubtitlesFromText(html, pageUrl, seen, callback, pageUrl)

        val manifests = LinkedHashSet<String>()
        Regex("""(?i)(?:https?:)?//[^"'<>\s]+\.m3u8(?:\?[^"'<>\s]*)?""")
            .findAll(html)
            .mapNotNull { resolveSubtitleUrl(it.value, pageUrl) }
            .forEach { manifests.add(it) }
        Regex("""(?i)["']((?:(?:https?:)?//|/|\.\.?/)?[^"'<>\s]{1,1000}\.m3u8(?:\?[^"']*)?)["']""")
            .findAll(html)
            .mapNotNull { resolveSubtitleUrl(it.groupValues[1], pageUrl) }
            .forEach { manifests.add(it) }

        manifests.take(3).forEach { manifest ->
                extractSubtitlesFromManifest(
                    manifest, pageUrl, seen, seenManifests, callback
                )
            }
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(url)

    private fun qualityValue(label: String, url: String): Int {
        val quality = Regex("""(?i)(2160|1080|720|480|360|240|4k)""")
            .find("$label $url")?.value?.lowercase() ?: return Qualities.Unknown.value
        return when (quality) {
            "2160", "4k" -> Qualities.P2160.value
            "1080" -> Qualities.P1080.value
            "720" -> Qualities.P720.value
            "480" -> Qualities.P480.value
            "360" -> Qualities.P360.value
            "240" -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
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
        val seenSubtitlePages = LinkedHashSet<String>()
        val seenSubtitleManifests = LinkedHashSet<String>()
        val chosenSubtitles = LinkedHashMap<String, SubtitleFile>()

        fun subtitleTrackPriority(subtitle: SubtitleFile): Int {
            val path = runCatching { URI(subtitle.url).path.orEmpty() }
                .getOrDefault(subtitle.url.substringBefore('?'))
            val segments = path.split('/').filter { it.isNotBlank() }
            val detectedLanguage = subtitleUrlLanguage(subtitle.url)
            val queryLanguage = subtitleUrlQueryLanguage(subtitle.url)
            val languageDirectory = segments.dropLast(1).any { directory ->
                val value = when {
                    directory.startsWith("sub_", true) -> directory.substring(4)
                    directory.startsWith("sub-", true) -> directory.substring(4)
                    else -> ""
                }
                value.isNotBlank() &&
                    explicitSubtitleLanguage(value) == detectedLanguage
            }
            val fileNameLanguage =
                explicitSubtitleLanguage(segments.lastOrNull().orEmpty()) == detectedLanguage
            val isHlsSubtitlePlaylist = Regex("""(?i)\.m3u8$""").containsMatchIn(path)
            val standaloneTextTrack = Regex(
                """(?i)\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt)$"""
            ).containsMatchIn(path)

            // Prefer the provider's language-specific folder/filename, then explicit
            // language query metadata. Generic labels alone should be the weakest signal.
            return when {
                detectedLanguage == null && explicitSubtitleLanguage(subtitle.lang) == null -> 0
                languageDirectory && isHlsSubtitlePlaylist -> 12
                languageDirectory -> 10
                fileNameLanguage -> 9
                queryLanguage != null && queryLanguage == detectedLanguage -> 8
                detectedLanguage != null -> 6
                standaloneTextTrack -> 4
                isHlsSubtitlePlaylist -> 2
                else -> 1
            }
        }

        // Only expose one correctly identified track per supported language.
        // URL language metadata takes precedence over a possibly mismatched label.
        // If the same language is found more than once, prefer the URL that explicitly
        // identifies its language, then a standalone VTT/SRT file.
        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val language = subtitleUrlLanguage(subtitle.url)
                ?: explicitSubtitleLanguage(subtitle.lang)
            if (language == "Türkçe" || language == "English") {
                val existing = chosenSubtitles[language]
                // Do not let later tracks of equal priority replace the first candidate.
                // Replace only when the new URL carries stronger language/path evidence.
                if (existing == null ||
                    subtitleTrackPriority(subtitle) > subtitleTrackPriority(existing)
                ) {
                    subtitle.lang = language
                    chosenSubtitles[language] = subtitle
                }
            }
        }

        suspend fun processCandidate(raw: String, referer: String, quality: String) {
            val candidate = absoluteUrl(raw, referer) ?: return
            if (!seenCandidates.add(candidate)) return
            if (isDirectMedia(candidate)) {
                if (candidate.contains(".m3u8", true)) {
                    extractSubtitlesFromManifest(
                        candidate, referer, seenSubtitleUrls, seenSubtitleManifests, emitSubtitle
                    )
                }
                if (emittedUrls.add(candidate)) {
                    val type = if (candidate.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    val label = name + " " + quality.ifBlank { "Kaynak" }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = candidate,
                            type = type
                        ) {
                            this.referer = referer
                            this.quality = qualityValue(quality, candidate)
                        }
                    )
                    found = true
                }
                return
            }

            // Player pages commonly contain their subtitle tracks outside secureData.
            extractSubtitlesFromPlayerPage(
                candidate, referer, seenSubtitleUrls, seenSubtitlePages,
                seenSubtitleManifests, emitSubtitle
            )

            try {
                loadExtractor(candidate, referer, emitSubtitle) { link ->
                    if (emittedUrls.add(link.url)) callback(link)
                    found = true
                }
            } catch (_: Exception) {
                // Bir oynatıcı başarısız olduğunda kalan kaynaklar denenmeye devam eder.
            }
        }

        if (isDirectMedia(data)) processCandidate(data, "$mainUrl/", "")

        val page = runCatching { getPageData(data) }.getOrNull()
        val root = page?.secureData
        val related = objectField(root, "RelatedResults", "relatedResults")
        val isSeries = data.contains("/dizi/", true) ||
            objectField(related, "getSerieSeasonAndEpisodes", "getSeriesSeasons") != null

        if (page != null) {
            // Bazı sürümlerde altyazı adresleri source_content JSON alanının içinde tutuluyor.
            publishSubtitlesFromText(root?.toString().orEmpty(), data, seenSubtitleUrls, emitSubtitle)
            publishSubtitlesFromText(page.document.html(), data, seenSubtitleUrls, emitSubtitle)

            for (source in sourceItems(root, isSeries)) {
                publishSubtitlesFromText(source.raw, data, seenSubtitleUrls, emitSubtitle)
                for (candidate in extractCandidates(source.raw, data)) {
                    processCandidate(candidate, data, source.quality)
                }
            }
            for (candidate in extractCandidates(page.document.html(), data)) {
                processCandidate(candidate, data, "")
            }
            if (looksLikePlayer(data)) processCandidate(data, "$mainUrl/", "")
        }

        // Keep the list limited to one Turkish and one English option.
        chosenSubtitles.values.forEach { subtitleCallback(it) }
        return found
    }
}
