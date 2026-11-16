package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
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

    private val fourPichiveResolver = WebViewResolver(
        Regex("https://(four\\.)?pichive\\.online/.*")
    )

    @Volatile private var powSalt = "Tu6UwcgeK8"

    private data class PageData(
        val document: Document,
        val secureData: JSONObject?
    )

    @Volatile private var rateLimitedUntil = 0L
    private val powCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun isRateLimitResponse(code: Int, body: String): Boolean {
        val lower = body.lowercase()
        return code == 1015 || code == 429 ||
            lower.contains("error 1015") ||
            lower.contains("you are being rate limited")
    }

    private fun noteRateLimit() {
        rateLimitedUntil = maxOf(rateLimitedUntil, System.currentTimeMillis() + 5 * 60 * 1000L)
    }

    private fun isCoolingDown(): Boolean = System.currentTimeMillis() < rateLimitedUntil

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
            .replace("\\/", "/").replace("&amp;", "&")
            .replace("\\u0026", "&").replace("\\u003d", "=")
            .trim().trim('"', '\'')

        if (value.startsWith("https%3A%2F%2F", true))
            value = value.replaceFirst("https%3A%2F%2F", "https://", true)
        else if (value.startsWith("http%3A%2F%2F", true))
            value = value.replaceFirst("http%3A%2F%2F", "http://", true)
        return absoluteUrl(value)
    }

    private fun imageFromJson(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 5) return null
        val imageKeys = listOf(
            "poster_url", "posterUrl", "object_poster_url", "objectPosterUrl",
            "poster", "img", "p", "sq", "wide", "image_url", "imageUrl",
            "thumbnail", "cover_url", "cover", "image", "thumb", "photo", "src"
        )
        if (value is JSONObject) {
            for (key in imageKeys) {
                val candidate = value.opt(key)
                if (candidate is String) imageUrl(candidate)?.let { return it }
                else if (candidate is JSONObject || candidate is JSONArray)
                    imageFromJson(candidate, depth + 1)?.let { return it }
            }
        } else if (value is JSONArray) {
            for (index in 0 until value.length())
                imageFromJson(value.opt(index), depth + 1)?.let { return it }
        } else if (value is String) return imageUrl(value)
        return null
    }

    private fun imageFromElement(element: org.jsoup.nodes.Element, base: String): String? {
        val attributes = listOf(
            "data-poster", "data-cover", "data-thumbnail", "data-original",
            "data-original-src", "data-lazy-src", "data-src", "data-srcset",
            "srcset", "content", "href", "src"
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
        if (isCoolingDown()) return PageData(Jsoup.parse("", url), null)

        val response = app.get(
            url,
            headers = siteHeaders,
            referer = "$mainUrl/",
            allowRedirects = true
        )
        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            noteRateLimit()
            return PageData(Jsoup.parse(body, url), null)
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
        val rawUrl = field(item, "used_slug", "slug", "url", "u", "href", "path") ?: return null
        val url = absoluteUrl(rawUrl) ?: return null
        val originalTitle = field(item, "original_title", "object_name", "title", "name", "t") ?: return null
        val cultureTitle = field(item, "culture_title", "cultureTitle")
        val title = if (!cultureTitle.isNullOrBlank() && !cultureTitle.equals(originalTitle, true))
            "$originalTitle - $cultureTitle" else originalTitle
        val poster = imageFromJson(item)
            ?: imageUrl(field(item, "poster_url", "poster", "img", "image_url", "thumbnail", "cover"))
        val type = field(item, "used_type", "type", "content_type", "kind").orEmpty()
        val isSeries = when {
            type.contains("movie", true) || type.contains("film", true) -> false
            type.contains("series", true) || type.contains("dizi", true) -> true
            else -> forceSeries || url.contains("/dizi/", true)
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private suspend fun getExploreJson(url: String): JSONObject? {
        if (isCoolingDown()) return null
        val response = runCatching {
            app.get(url, headers = apiHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull() ?: return null
        val body = response.text
        if (isRateLimitResponse(response.code, body)) {
            noteRateLimit()
            return null
        }
        if (!response.isSuccessful) return null
        return parseObject(body)
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

        val totalPages = resultNode?.optInt("pages", -1) ?: -1
        val hasNext = if (totalPages >= 0) page < totalPages else results.size >= 42

        Log.d("AsyaWatch", "Ana sayfa: ${request.name} - ${results.size} sonuç")
        return newHomePageResponse(request, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = postJson("$mainUrl/api/bg/searchcontent?searchterm=$encoded") ?: return emptyList()
        val items = arrayField(root, "result") ?: return emptyList()
        val results = ArrayList<SearchResponse>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            searchResponseFrom(item)?.let { results.add(it) }
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

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
        val content = objectField(root, "contentItem") ?: JSONObject()
        val title = field(content, "original_title", "title", "name")
            ?: page.document.selectFirst("h1")?.text()?.trim()
            ?: url.substringAfterLast('/')
        val poster = imageFromJson(content) ?: page.document.selectFirst("meta[property=og:image]")
            ?.let { imageFromElement(it, mainUrl) }
        val description = field(content, "syn", "description", "plot", "overview")
        val year = field(content, "release_year", "year")?.toIntOrNull()
        val episodes = parseEpisodesFromDocument(page.document, poster, url)
        val isSeries = url.contains("/dizi/", true) || episodes.isNotEmpty()

        Log.d("AsyaWatch", "Detay: $title; bölüm=${episodes.size}")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.year = year
            }
        }
    }

    private fun looksLikePlayer(url: String): Boolean {
        val value = url.lowercase()
        return Regex("""\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(value) ||
            listOf("iframe", "player", "four.pichive.online", "pichive.online", "nm.php", "iframe.php")
                .any { value.contains(it) }
    }

    private fun extractCandidates(raw: String, base: String): List<String> {
        val result = LinkedHashSet<String>()
        val normalized = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val document = runCatching { Jsoup.parse(normalized, base) }.getOrNull()

        document?.select("iframe, video, source, embed")?.forEach { element ->
            for (attr in listOf("src", "data-src", "data-url", "data-embed", "href")) {
                absoluteUrl(element.attr(attr), base)?.let { result.add(it) }
            }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""").findAll(normalized).forEach { match ->
            val candidate = absoluteUrl(match.value.trimEnd('\\', ')', ']', ',', ';', '"'), base)
                ?: return@forEach
            if (looksLikePlayer(candidate)) result.add(candidate)
        }

        Regex("""(?i)(?:src|url|file|source|stream|iframe|master)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(normalized).forEach { match ->
                absoluteUrl(match.groupValues[1], base)?.let {
                    if (looksLikePlayer(it)) result.add(it)
                }
            }

        return result.toList()
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(url)

    private fun isNmPhpUrl(url: String): Boolean = url.contains("/nm.php", true)

    private fun logNpConfig(npConfig: JSONObject?) {
        if (npConfig == null) {
            Log.d("AsyaWatch", "📋 NP_CONFIG null")
            return
        }
        val pretty = runCatching { npConfig.toString(2) }.getOrNull() ?: npConfig.toString()
        Log.d("AsyaWatch", "📋 NP_CONFIG tam: ${pretty.take(3000)}")
    }

    private fun logPowJs(html: String) {
        val patterns = listOf(
            "salt", "nonce", "difficulty", "0xFFF", "0xfff", "4095", "65535",
            "SHA-256", "SHA256", "sha256", "MD5", "md5",
            "& 0xFFF", "& 4095", "& 0xFF", "& 0xFFFF",
            "leading", "zero", "hash", "proof", "work"
        )
        for (pattern in patterns) {
            val idx = html.indexOf(pattern, ignoreCase = true)
            if (idx >= 0) {
                val start = maxOf(0, idx - 100)
                val end = minOf(html.length, idx + 200)
                val context = html.substring(start, end).replace("\n", " ").replace(Regex("\\s+"), " ")
                Log.d("AsyaWatch", "🔍 '$pattern' → ...$context...")
            }
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
                Log.d("AsyaWatch", "PoW bulundu: n=$i ($label) salt=$powSalt")
                return result
            }
        }
        return null
    }

    private fun browserHeaders(iframeUrl: String): Map<String, String> = mapOf(
        "User-Agent" to chromeUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to iframeUrl,
        "Origin" to "https://four.pichive.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Storage-Access" to "active",
        "sec-ch-ua" to chromeUaBrand,
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Priority" to "u=1, i"
    )

    private fun parseNpConfig(html: String): JSONObject? {
        val regex = Regex("""window\.NP_CONFIG\s*=\s*(\{.+?\});\s*(?:window\.|</script>|$)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        return parseObject(match.groupValues[1].trim())
    }

    private suspend fun tryPowOnUrl(
        nmUrl: String,
        iframeUrl: String,
        id: String
    ): String? {
        val variants = listOf(
            Triple("SHA-256", "wordBE", solvePow(id, "SHA-256", "wordBE")),
            Triple("SHA-256", "wordLE", solvePow(id, "SHA-256", "wordLE")),
            Triple("SHA-1", "wordBE", solvePow(id, "SHA-1", "wordBE")),
            Triple("SHA-1", "wordLE", solvePow(id, "SHA-1", "wordLE"))
        ).filter { it.third != null }

        if (variants.isEmpty()) return null

        val vMatch = Regex("""[?&]v=([^&]+)""").find(nmUrl)
        val vToken: String = vMatch?.groupValues?.getOrNull(1) ?: ""
        val baseUrl = nmUrl.substringBefore('?')

        for ((alg, variantName, solution) in variants) {
            val powValue: Long = solution?.first ?: continue
            val label = "$alg-$variantName"

            val correctUrl: String = if (vToken.isNotEmpty())
                "$baseUrl?v=$vToken&p=$powValue" else nmUrl

            val headers: Map<String, String> = browserHeaders(iframeUrl) + ("X-PoW" to powValue.toString())

            val directResp = runCatching {
                app.get(correctUrl, headers = headers, referer = iframeUrl)
            }.getOrNull()

            if (directResp != null) {
                Log.d("AsyaWatch", "PoW [$label] (direct): HTTP ${directResp.code}, bodyLen=${directResp.text.length}")
                if (directResp.code == 200 && directResp.text.startsWith("#EXTM3U")) {
                    Log.d("AsyaWatch", "✔ PoW BAŞARILI (direct) → M3U8")
                    return correctUrl
                }
            }

            val wvResp = runCatching {
                app.get(correctUrl, headers = headers, referer = iframeUrl, interceptor = fourPichiveResolver)
            }.getOrNull()

            if (wvResp != null) {
                Log.d("AsyaWatch", "PoW [$label] (WebView): HTTP ${wvResp.code}, bodyLen=${wvResp.text.length}")
                if (wvResp.code == 200 && wvResp.text.trim().startsWith("#EXTM3U")) {
                    Log.d("AsyaWatch", "✔ PoW BAŞARILI (WebView) → M3U8")
                    return correctUrl
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
        Log.d("AsyaWatch", "🔥 FourPichive: ${iframeUrl.take(100)}")

        val response = runCatching {
            app.get(iframeUrl, headers = browserHeaders(iframeUrl), referer = referer, allowRedirects = true)
        }.getOrNull() ?: return false

        if (!response.isSuccessful) {
            Log.e("AsyaWatch", "iframe HTTP ${response.code}")
            return false
        }

        val html = response.text
        if (html.isBlank()) return false

        Log.d("AsyaWatch", "iframe HTML: ${html.length} bytes, NP_CONFIG: ${html.contains("NP_CONFIG")}")

        val npConfig = parseNpConfig(html)
        logNpConfig(npConfig)
        logPowJs(html)

        val npId: String? = npConfig?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
        val npSalt: String? = npConfig?.optString("salt")?.takeIf { it.isNotBlank() && it != "null" }

        if (npSalt != null && npSalt != powSalt) {
            Log.d("AsyaWatch", "⚠ Salt güncellendi: $powSalt → $npSalt")
            powSalt = npSalt
            powCache.clear()
        }

        Log.d("AsyaWatch", "NP_CONFIG: id=$npId, salt=${npSalt ?: "(varsayılan $powSalt)"}")

        val localSeenSubs = HashSet<String>()
        npConfig?.optJSONArray("subtitles")?.let { subsArray ->
            for (i in 0 until subsArray.length()) {
                val sub = subsArray.optJSONObject(i) ?: continue
                val label = sub.optString("label", "Altyazı")
                val url = sub.optString("url")
                if (url.isNotBlank()) {
                    val subtitle = SubtitleFile(lang = label, url = url)
                    subtitle.headers = siteHeaders + mapOf(
                        "Referer" to iframeUrl,
                        "Origin" to "https://four.pichive.online",
                        "Accept" to "text/vtt, text/plain, */*"
                    )
                    if (localSeenSubs.add(url)) subtitleCallback(subtitle)
                }
            }
        }

        val masterUrl: String? = npConfig?.optJSONArray("sources")?.optJSONObject(0)?.optString("master")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: Regex("""(?i)"master"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() }

        if (masterUrl.isNullOrBlank()) {
            Log.e("AsyaWatch", "master URL yok")
            return false
        }

        Log.d("AsyaWatch", "master: ${masterUrl.take(100)}")

        var finalUrl: String = masterUrl
        if (html.contains("NP_GATE") && npId != null) {
            Log.d("AsyaWatch", "NP_GATE PoW deneniyor...")
            tryPowOnUrl(masterUrl, iframeUrl, npId)?.let { finalUrl = it }
        }

        val type = if (finalUrl.contains(".m3u8", true) || isNmPhpUrl(finalUrl))
            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(source = name, name = "$name FourPichive", url = finalUrl, type = type) {
                this.referer = iframeUrl
                this.headers = browserHeaders(iframeUrl)
                this.quality = Qualities.Unknown.value
            }
        )
        Log.d("AsyaWatch", "✔ URL verildi")
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
        val chosenSubtitles = LinkedHashMap<String, SubtitleFile>()

        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val lang: String = subtitle.lang
            if (lang == "Türkçe" || lang == "English")
                chosenSubtitles.putIfAbsent(lang, subtitle)
        }

        suspend fun processCandidate(raw: String, referer: String) {
            val candidate: String = absoluteUrl(raw, referer) ?: return
            if (!seenCandidates.add(candidate)) return

            if (isDirectMedia(candidate) && !isNmPhpUrl(candidate)) {
                if (emittedUrls.add(candidate)) {
                    val type = if (candidate.contains(".m3u8", true))
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(source = name, name = name, url = candidate, type = type) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
                return
            }

            val host: String? = runCatching { URI(candidate).host?.lowercase() }.getOrNull()
            if (host == "four.pichive.online" || host?.endsWith(".four.pichive.online") == true) {
                runCatching {
                    extractFourPichive(candidate, referer, emitSubtitle) { link ->
                        if (emittedUrls.add(link.url)) { callback(link); found = true }
                    }
                }
                return
            }

            runCatching {
                loadExtractor(candidate, referer, emitSubtitle) { link ->
                    if (emittedUrls.add(link.url)) callback(link)
                    found = true
                }
            }
        }

        val page = runCatching { getPageData(data) }.getOrNull()
        if (page != null) {
            val pageCandidates = extractCandidates(page.document.html(), data)
            Log.d("AsyaWatch", "Sayfa adayları: ${pageCandidates.size}")
            for (c in pageCandidates) processCandidate(c, data)
        }

        chosenSubtitles.values.forEach { subtitleCallback(it) }
        return found
    }
}
