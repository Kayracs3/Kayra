package com.Kayracs3

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
import java.util.Calendar
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
        "" to "Yeni Eklenen Filmler",
        "49" to "Aile Film",
        "44" to "Animasyon Film",
        "59" to "Aksiyon Film",
        "66" to "Bilim Kurgu Film",
        "48" to "Dram Film",
        "61" to "Fantastik Film",
        "68" to "Gerilim Film",
        "51" to "Gizem Film",
        "63" to "Korku Film",
        "45" to "Komedi Film",
        "65" to "Romantik Film",
        "46" to "Suç Film",
        "69" to "Savaş Film",
        "78" to "Western Film",
        "" to "Yeni Eklenen Diziler",
        "15" to "Aile Dizi",
        "17" to "Animasyon Dizi",
        "9" to "Aksiyon Dizi",
        "5" to "Bilim Kurgu Dizi",
        "2" to "Dram Dizi",
        "12" to "Fantastik Dizi",
        "18" to "Gerilim Dizi",
        "3" to "Gizem Dizi",
        "8" to "Korku Dizi",
        "4" to "Komedi Dizi",
        "7" to "Romantik Dizi",
        "1" to "Suç Dizi",
        "26" to "Savaş Dizi",
        "11" to "Western Dizi"
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

        // Some cards return an AMP image proxy rather than the original image URL.
        val marker = "images-macellan-online.cdn.ampproject.org/i/s/"
        val markerIndex = value.indexOf(marker, ignoreCase = true)
        if (markerIndex >= 0) {
            val tail = value.substring(markerIndex + marker.length).trimStart('/')
            value = when {
                tail.startsWith("http://", true) || tail.startsWith("https://", true) -> tail
                tail.startsWith("http%3A%2F%2F", true) -> tail.replaceFirst("http%3A%2F%2F", "http://", true)
                tail.startsWith("https%3A%2F%2F", true) -> tail.replaceFirst("https%3A%2F%2F", "https://", true)
                else -> "https://$tail"
            }
        }

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
            "poster", "poster_path", "posterPath", "image_url", "imageUrl",
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
        if (isCoolingDown()) return null
        val response = runCatching {
            app.post(url, headers = apiHeaders, referer = "$mainUrl/")
        }.getOrNull() ?: return null
        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            noteRateLimit()
            return null
        }
        if (!response.isSuccessful) return null
        return decodeApiResponse(body)
    }

    private fun searchResponseFrom(item: JSONObject, forceSeries: Boolean = false): SearchResponse? {
        val rawUrl = field(item, "used_slug", "slug", "url") ?: return null
        val url = absoluteUrl(rawUrl) ?: return null
        val originalTitle = field(item, "original_title", "object_name", "title", "name") ?: return null
        val cultureTitle = field(item, "culture_title", "cultureTitle")
        val title = if (!cultureTitle.isNullOrBlank() && !cultureTitle.equals(originalTitle, true)) {
            "$originalTitle - $cultureTitle"
        } else originalTitle
        val poster = imageFromJson(item)
            ?: imageUrl(field(
                item,
                "poster_url", "posterUrl", "object_poster_url", "objectPosterUrl",
                "poster", "image_url", "imageUrl", "thumbnail", "thumbnail_url", "cover"
            ))
        val score = scoreFrom(field(item, "imdb_point", "imdbPoint", "score", "rating"))
        val type = field(item, "used_type", "type", "content_type").orEmpty()
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val isSeries = request.name.contains("Dizi", true)
        val endpoint = if (isSeries) "findSeries" else "findMovies"
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val category = URLEncoder.encode(request.data, "UTF-8")
        val endpointUrl = "$mainUrl/api/bg/$endpoint" +
            "?releaseYearStart=1900&releaseYearEnd=$year" +
            "&imdbPointMin=1&imdbPointMax=10" +
            "&categoryIdsComma=$category&countryIdsComma=" +
            "&orderType=date_desc&languageId=-1" +
            "&currentPage=$page&currentPageCount=12" +
            "&queryStr=&categorySlugsComma=&countryCodesComma="

        val root = postJson(endpointUrl)
            ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val items = arrayField(root, "result")
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
        return newHomePageResponse(request, completedResults, hasNext = completedResults.isNotEmpty())
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
        val original = field(content, "original_title", "originalTitle", "title")
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
        field(content, "release_year", "releaseYear", "year")?.toIntOrNull()?.let { return it }
        return Regex("""\b(?:19|20)\d{2}\b""")
            .find(document.selectFirst("body")?.text().orEmpty())?.value?.toIntOrNull()
    }

    private fun detailTags(content: JSONObject?): List<String>? {
        val raw = field(content, "categories", "tags", "genres") ?: return null
        val tags = raw.removePrefix("[").removeSuffix("]")
            .split(',', '|')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() && !it.equals("null", true) }
            .distinct()
        return tags.takeIf { it.isNotEmpty() }
    }

    private fun detailActors(related: JSONObject?): List<Actor> {
        val cast = objectField(related, "getMovieCastsById", "getContentCastsById") ?: return emptyList()
        val rows = arrayField(cast, "result") ?: return emptyList()
        val actors = ArrayList<Actor>()
        val seen = HashSet<String>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val actorName = field(item, "name", "actor_name", "title") ?: continue
            if (seen.add(actorName)) {
                actors.add(Actor(actorName, imageUrl(field(item, "cast_image", "castImage", "image", "poster"))))
            }
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
        val seasonData = objectField(related, "getSerieSeasonAndEpisodes", "getSeriesSeasons")
            ?: return emptyList()
        val seasons = arrayField(seasonData, "result", "seasons") ?: return emptyList()
        val result = ArrayList<Episode>()
        val seen = HashSet<String>()

        for (seasonIndex in 0 until seasons.length()) {
            val seasonObject = seasons.optJSONObject(seasonIndex) ?: continue
            val seasonNumber = field(seasonObject, "season_no", "seasonNo", "season")?.toIntOrNull()
                ?: (seasonIndex + 1)
            val episodes = arrayField(seasonObject, "episodes", "episodeList") ?: continue
            for (episodeIndex in 0 until episodes.length()) {
                val episodeObject = episodes.optJSONObject(episodeIndex) ?: continue
                val rawUrl = field(episodeObject, "used_slug", "slug", "url", "episode_url") ?: continue
                val url = absoluteUrl(rawUrl) ?: continue
                if (!seen.add(url)) continue
                val episodeText = field(episodeObject, "episode_text", "epText", "title", "name").orEmpty()
                val episodeNumber = field(episodeObject, "episode_no", "episodeNo", "episode")?.toIntOrNull()
                    ?: Regex("""\d+""").find(episodeText)?.value?.toIntOrNull()
                    ?: (episodeIndex + 1)
                result.add(newEpisode(url) {
                    name = episodeText.ifBlank { "Bölüm $episodeNumber" }
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = poster
                })
            }
        }
        return result.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    override suspend fun load(url: String): LoadResponse {
        val page = getPageData(url)
        val root = page.secureData
        val content = objectField(root, "contentItem", "content_item") ?: JSONObject()
        val related = objectField(root, "RelatedResults", "relatedResults")
        val title = detailTitle(content, page.document, url)
        val poster = detailPoster(content, page.document)
        val description = field(content, "description", "plot", "overview")
            ?: page.document.selectFirst("meta[name=description]")?.attr("content")
            ?: page.document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = detailYear(content, page.document)
        val score = scoreFrom(field(content, "imdb_point", "imdbPoint", "score", "rating"))
        val tags = detailTags(content)
        val actors = detailActors(related)
        val trailer = detailTrailer(related)
        val duration = field(content, "total_minutes", "totalMinutes", "duration")?.toIntOrNull()
        val episodes = parseEpisodes(related, poster)
        val isSeries = url.contains("/dizi/", true) ||
            field(content, "used_slug", "usedSlug")?.contains("/dizi/", true) == true ||
            objectField(related, "getSerieSeasonAndEpisodes", "getSeriesSeasons") != null

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
        val response = runCatching {
            app.get(
                pageUrl,
                headers = siteHeaders,
                referer = referer,
                allowRedirects = true
            )
        }.getOrNull() ?: return
        if (!response.isSuccessful) return

        val html = response.text
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
