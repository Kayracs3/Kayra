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
    private val fourPichiveUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

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
        interceptUrl = Regex("""(?i)^https://four\.pichive\\.online/nl\\.php(?:\\?.*)?$"""),
        additionalUrls = listOf(
            Regex("""(?i)^https://four\.pichive\\.online/nsub\\.php(?:\\?.*)?$""")
        ),
        userAgent = fourPichiveUserAgent,
        useOkhttp = false,
        timeout = 45_000L
    )

    private data class PageData(val document: Document, val secureData: JSONObject?)
    @Volatile private var rateLimitedUntil = 0L

    private fun isRateLimitResponse(code: Int, body: String): Boolean {
        val l = body.lowercase()
        return code == 1015 || code == 429 || l.contains("error 1015") || l.contains("you are being rate limited")
    }
    private fun noteRateLimit() { rateLimitedUntil = maxOf(rateLimitedUntil, System.currentTimeMillis() + 5*60*1000L) }
    private fun isCoolingDown(): Boolean = System.currentTimeMillis() < rateLimitedUntil

    private fun field(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (k in keys) {
            val raw = obj.opt(k) ?: continue
            if (raw == JSONObject.NULL) continue
            val v = raw.toString().trim()
            if (v.isNotEmpty() && !v.equals("null", true)) return v
        }
        return null
    }
    private fun objectField(obj: JSONObject?, vararg keys: String): JSONObject? {
        if (obj == null) return null
        for (k in keys) obj.optJSONObject(k)?.let { return it }
        return null
    }
    private fun arrayField(obj: JSONObject?, vararg keys: String): JSONArray? {
        if (obj == null) return null
        for (k in keys) obj.optJSONArray(k)?.let { return it }
        return null
    }
    private fun parseObject(raw: String?): JSONObject? = if (raw.isNullOrBlank()) null else runCatching { JSONObject(raw) }.getOrNull()

    private fun decodeBase64Text(raw: String): String? {
        val v = raw.trim().removeSurrounding("\"")
        if (v.isBlank()) return null
        return runCatching { String(base64Decode(v).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.getOrNull()
    }
    private fun decodeApiResponse(raw: String): JSONObject? {
        val env = parseObject(raw) ?: return null
        val encoded = field(env, "response") ?: return env
        return parseObject(decodeBase64Text(encoded) ?: "") ?: env
    }

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        var v = raw?.trim().orEmpty()
        if (v.isBlank()) return null
        v = v.replace("\\/", "/").replace("&amp;", "&").trim()
        if (v.startsWith("javascript:", true) || v.startsWith("data:", true) || v.startsWith("about:", true)) return null
        val abs = when {
            v.startsWith("https://", true) || v.startsWith("http://", true) -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> {
                val origin = runCatching { val u = URI(base); "${u.scheme}://${u.authority}" }.getOrDefault(mainUrl)
                origin + v
            }
            else -> base.trimEnd('/') + "/" + v.trimStart('/')
        }
        return abs.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    }

    private fun imageUrl(raw: String?): String? {
        var v = raw?.trim().orEmpty(); if (v.isBlank()) return null
        v = v.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&").replace("\\u003d", "=").trim().trim('"','\'')
        if (v.startsWith("https%3A%2F%2F", true)) v = v.replaceFirst("https%3A%2F%2F", "https://", true)
        else if (v.startsWith("http%3A%2F%2F", true)) v = v.replaceFirst("http%3A%2F%2F", "http://", true)
        return absoluteUrl(v)
    }

    private fun imageFromJson(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 5) return null
        val keys = listOf("poster_url","posterUrl","poster","img","p","sq","wide","image_url","imageUrl","thumbnail","cover_url","cover","image","thumb","photo","src")
        if (value is JSONObject) {
            for (k in keys) {
                val c = value.opt(k)
                if (c is String) imageUrl(c)?.let { return it }
                else if (c is JSONObject || c is JSONArray) imageFromJson(c, depth+1)?.let { return it }
            }
        } else if (value is JSONArray) {
            for (i in 0 until value.length()) imageFromJson(value.opt(i), depth+1)?.let { return it }
        } else if (value is String) return imageUrl(value)
        return null
    }
    private fun imageFromElement(el: org.jsoup.nodes.Element, base: String): String? {
        for (a in listOf("data-poster","data-cover","data-original","data-src","src")) {
            val raw = el.attr(a).trim()
            if (raw.isNotBlank()) imageUrl(raw.split(',').firstOrNull()?.trim()?.substringBefore(' ') ?: raw)?.let { return it }
        }
        return null
    }

    private fun pageSecureData(doc: Document): JSONObject? {
        val script = doc.selectFirst("script#__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("script#__NEXT_DATA__")?.html()?.takeIf { it.isNotBlank() } ?: return null
        val pp = objectField(parseObject(script)?.optJSONObject("props"), "pageProps") ?: return null
        val sec = pp.opt("secureData")
        if (sec is JSONObject) return sec
        val raw = sec?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return parseObject(decodeBase64Text(raw) ?: "") ?: parseObject(raw)
    }

    private suspend fun getPageData(url: String): PageData {
        if (isCoolingDown()) return PageData(Jsoup.parse("", url), null)
        val r = app.get(url, headers = siteHeaders, referer = "$mainUrl/", allowRedirects = true)
        if (isRateLimitResponse(r.code, r.text)) { noteRateLimit(); return PageData(Jsoup.parse(r.text, url), null) }
        return PageData(r.document, pageSecureData(r.document))
    }

    private suspend fun postJson(url: String): JSONObject? {
        if (isCoolingDown()) return null
        val r = runCatching { app.post(url, headers = apiHeaders, referer = "$mainUrl/") }.getOrNull() ?: return null
        if (isRateLimitResponse(r.code, r.text)) { noteRateLimit(); return null }
        if (!r.isSuccessful) return null
        return decodeApiResponse(r.text)
    }

    private fun searchResponseFrom(item: JSONObject, forceSeries: Boolean = false): SearchResponse? {
        val rawUrl = field(item, "used_slug","slug","url","u","href","path") ?: return null
        val url = absoluteUrl(rawUrl) ?: return null
        val orig = field(item, "original_title","object_name","title","name","t") ?: return null
        val cul = field(item, "culture_title","cultureTitle")
        val title = if (!cul.isNullOrBlank() && !cul.equals(orig, true)) "$orig - $cul" else orig
        val poster = imageFromJson(item)
        val type = field(item, "used_type","type","content_type","kind").orEmpty()
        val isSeries = when {
            type.contains("movie", true) || type.contains("film", true) -> false
            type.contains("series", true) || type.contains("dizi", true) -> true
            else -> forceSeries || url.contains("/dizi/", true)
        }
        return if (isSeries) newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }

    private suspend fun getExploreJson(url: String): JSONObject? {
        if (isCoolingDown()) return null
        val r = runCatching { app.get(url, headers = apiHeaders, referer = "$mainUrl/", allowRedirects = true) }.getOrNull() ?: return null
        if (isRateLimitResponse(r.code, r.text)) { noteRateLimit(); return null }
        if (!r.isSuccessful) return null
        return parseObject(r.text)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val isSeries = request.name.contains("Dizi", true)
        val kind = if (isSeries) "dizi" else "film"
        val cat = URLEncoder.encode(request.data.trim(), "UTF-8")
        val root = getExploreJson("$mainUrl/api/asya/kesfet?kind=$kind&ulke=&tur=$cat&sirala=yeni&page=$page")
            ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val rn = root.optJSONObject("result")
        val items = rn?.optJSONArray("items")
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        if (items != null) for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            searchResponseFrom(it, isSeries)?.let { r -> if (seen.add(r.url)) results.add(r) }
        }
        val tp = rn?.optInt("pages", -1) ?: -1
        val hasNext = if (tp >= 0) page < tp else results.size >= 42
        return newHomePageResponse(request, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        val root = postJson("$mainUrl/api/bg/searchcontent?searchterm=$enc") ?: return emptyList()
        val items = arrayField(root, "result") ?: return emptyList()
        val results = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            searchResponseFrom(it)?.let { results.add(it) }
        }
        return results
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodesFromDocument(doc: Document, poster: String?, pageUrl: String): List<Episode> {
        val res = LinkedHashMap<String, Episode>()
        val pp = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault(pageUrl.substringBefore('?')).trimEnd('/')
        val pageSeason = Regex("""(?i)/sezon-(\d+)""").find(pp)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        for (el in doc.select(".eps a[href*='/bolum-'], .elist a.er[href*='/bolum-']")) {
            if (el.attr("aria-disabled").equals("true", true) || el.hasClass("fut")) continue
            val raw = el.attr("href").trim()
            val num = Regex("""(?i)/bolum-(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val season = Regex("""(?i)/sezon-(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
            val label = el.selectFirst(".tx b, .t1")?.text()?.trim()
            val abs = absoluteUrl(raw, pageUrl) ?: continue
            if (res.containsKey(abs)) continue
            res[abs] = newEpisode(abs) {
                name = label?.takeIf { it.isNotBlank() } ?: "Bölüm $num"
                this.season = season
                episode = num
                posterUrl = poster
            }
        }
        return res.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    override suspend fun load(url: String): LoadResponse {
        val page = getPageData(url)
        val content = objectField(page.secureData, "contentItem") ?: JSONObject()
        val title = field(content, "original_title","title","name")
            ?: page.document.selectFirst("h1")?.text()?.trim()
            ?: url.substringAfterLast('/')
        val poster = imageFromJson(content) ?: page.document.selectFirst("meta[property=og:image]")?.let { imageFromElement(it, mainUrl) }
        val desc = field(content, "syn","description","plot","overview")
        val year = field(content, "release_year","year")?.toIntOrNull()
        val eps = parseEpisodesFromDocument(page.document, poster, url)
        val isSeries = url.contains("/dizi/", true) || eps.isNotEmpty()
        return if (isSeries) newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            posterUrl = poster; plot = desc; this.year = year
        } else newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = desc; this.year = year
        }
    }

    private fun looksLikePlayer(url: String): Boolean {
        val v = url.lowercase()
        return Regex("""\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(v) ||
            listOf("iframe","player","four.pichive.online","pichive.online","nm.php","iframe.php").any { v.contains(it) }
    }

    private fun extractCandidates(raw: String, base: String): List<String> {
        val res = LinkedHashSet<String>()
        val norm = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val doc = runCatching { Jsoup.parse(norm, base) }.getOrNull()
        doc?.select("iframe, video, source, embed")?.forEach { el ->
            for (a in listOf("src","data-src","data-url","data-embed","href")) absoluteUrl(el.attr(a), base)?.let { res.add(it) }
        }
        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""").findAll(norm).forEach { m ->
            absoluteUrl(m.value.trimEnd('\\', ')', ']', ',', ';', '"'), base)?.let { if (looksLikePlayer(it)) res.add(it) }
        }
        return res.toList()
    }

    private fun isDirectMedia(url: String): Boolean = Regex("""(?i)\.(m3u8|mp4|m4v|mpd)(?:[?#].*)?$""").containsMatchIn(url)
    private fun isNmPhpUrl(url: String): Boolean = url.contains("/nm.php", true)
    private fun isRootOrigin(url: String): Boolean {
        val p = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return p.isBlank() || p == "/"
    }

    // Mirrors FourPichive's NP_GATE proof:
    // SHA-256(challenge + salt + base36(nonce) + salt)[3] >>> (32 - bits) == 0.
    private fun fourPichiveGateProof(config: JSONObject?): String? {
        val gate = config?.optJSONObject("gate") ?: return null
        val challenge = field(gate, "n")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bits = gate.optInt("b", 12).takeIf { it in 1..32 } ?: 12
        val digest = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull() ?: return null
        val salt = "nEhC31vsdq"

        for (nonce in 0L until 100_000_000L) {
            val nonceText = nonce.toString(36)
            val input = challenge + salt + nonceText + salt
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            val fourthWord =
                ((hash[12].toInt() and 0xff) shl 24) or
                    ((hash[13].toInt() and 0xff) shl 16) or
                    ((hash[14].toInt() and 0xff) shl 8) or
                    (hash[15].toInt() and 0xff)
            if ((fourthWord ushr (32 - bits)) == 0) return nonceText
        }
        return null
    }

    private fun appendQueryParameter(url: String, name: String, value: String): String {
        if (Regex("""(?i)(?:[?&])${Regex.escape(name)}=""").containsMatchIn(url)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$name=$value"
    }

    private fun browserHeaders(iframeUrl: String): Map<String, String> {
        val origin = runCatching {
            URI(iframeUrl).let { "${it.scheme}://${it.authority}" }
        }.getOrDefault("https://four.pichive.online")
        return siteHeaders + mapOf(
            "User-Agent" to fourPichiveUserAgent,
            "Accept" to "*/*",
            "Referer" to iframeUrl,
            "Origin" to origin
        )
    }

    private fun parseNpConfig(html: String): JSONObject? {
        val re = Regex("""window\.NP_CONFIG\s*=\s*(\{.+?\});\s*(?:window\.|</script>|$)""", RegexOption.DOT_MATCHES_ALL)
        val m = re.find(html) ?: return null
        return parseObject(m.groupValues[1].trim())
    }

    // =====================================================================
    // ⭐⭐⭐ ANA DÜZELTME: NP_CONFIG'den "gate" alanını oku = PoW input
    // =====================================================================
    private suspend fun extractFourPichive(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isRootOrigin(iframeUrl)) return false
        Log.d("AsyaWatch", "🔥 FourPichive: ${iframeUrl.take(80)}")

        // Use the browser identity already proven by the older Dizilla/Pichive path.
        val playerHeaders = browserHeaders(iframeUrl)
        val resp = runCatching {
            app.get(iframeUrl, headers = playerHeaders, referer = referer, allowRedirects = true)
        }.getOrNull() ?: return false
        if (!resp.isSuccessful) {
            Log.e("AsyaWatch", "iframe HTTP ${resp.code}")
            return false
        }

        val html = resp.text
        if (html.isBlank()) return false
        Log.d("AsyaWatch", "iframe HTML: ${html.length}b")

        val npConfig = parseNpConfig(html) ?: run {
            Log.e("AsyaWatch", "NP_CONFIG yok")
            return false
        }

        val gate = npConfig.optJSONObject("gate")
        val gateProof = if (gate != null) fourPichiveGateProof(npConfig) else null
        if (gate != null && gateProof == null) {
            Log.e("AsyaWatch", "FourPichive gate proof hesaplanamadı")
            return false
        }

        val origin = runCatching {
            URI(iframeUrl).let { "${it.scheme}://${it.authority}" }
        }.getOrDefault("https://four.pichive.online")

        // Cronet/ExoPlayer does not share NiceHttp's cookie jar. Forward iframe cookies.
        val iframeCookies = resp.headers.values("Set-Cookie")
            .mapNotNull { cookie ->
                cookie.substringBefore(';').trim().takeIf {
                    it.contains('=') && it.substringBefore('=').isNotBlank()
                }
            }
            .distinct()
            .joinToString("; ")

        val mediaHeaders = playerHeaders + mapOf(
            "Referer" to iframeUrl,
            "Origin" to origin,
            "Accept" to "*/*"
        ) + if (iframeCookies.isBlank()) {
            emptyMap()
        } else {
            mapOf("Cookie" to iframeCookies)
        }

        // Run the iframe in a short-lived WebView so its JavaScript creates the
        // same playlist/subtitle requests seen in Chrome (nl.php and nsub.php).
        // This also keeps the WebView-generated cookies and per-session tokens.
        val webViewCapture = runCatching {
            fourPichiveResolver.resolveUsingWebView(
                url = iframeUrl,
                referer = referer,
                headers = playerHeaders
            )
        }.onFailure { error ->
            Log.w("AsyaWatch", "FourPichive WebView yakalama başarısız: ${error.message}")
        }

        val capturedPlaylist = webViewCapture.getOrNull()?.first?.takeIf {
            it.url.encodedPath.equals("/nl.php", true)
        }
        val capturedSubtitleRequests = webViewCapture.getOrNull()?.second.orEmpty()
            .filter { it.url.encodedPath.equals("/nsub.php", true) }
            .distinctBy { it.url.toString() }

        Log.d(
            "AsyaWatch",
            "FourPichive gate hazır: koruma=${gate != null}, iframe çerezi=${iframeCookies.isNotBlank()}"
        )

        val subtitleHeaders = mediaHeaders + ("Accept" to "text/vtt, text/plain, */*")
        val seenSubs = HashSet<String>()
        val configuredSubs = npConfig.optJSONArray("subtitles")

        if (capturedSubtitleRequests.isNotEmpty()) {
            capturedSubtitleRequests.forEachIndexed { index, request ->
                val subtitleUrl = request.url.toString()
                if (!seenSubs.add(subtitleUrl)) return@forEachIndexed

                val token = request.url.queryParameter("v")
                val matchingIndex = if (token == null || configuredSubs == null) null else {
                    (0 until configuredSubs.length()).firstOrNull { subIndex ->
                        val raw = configuredSubs.optJSONObject(subIndex)?.optString("url").orEmpty()
                        runCatching {
                            URI(raw).rawQuery.orEmpty().split('&')
                                .firstOrNull { it.substringBefore('=') == "v" }
                                ?.substringAfter('=', "")
                        }.getOrNull() == token
                    }
                }
                val labelIndex = matchingIndex ?: index
                val label = configuredSubs?.optJSONObject(labelIndex)
                    ?.optString("label")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: "Altyazı ${index + 1}"

                val requestHeaders = LinkedHashMap(subtitleHeaders)
                request.headers.names().forEach { key ->
                    if (key.equals("Cookie", true) ||
                        key.equals("User-Agent", true) ||
                        key.equals("Referer", true) ||
                        key.equals("Origin", true) ||
                        key.equals("Accept-Language", true)
                    ) {
                        request.header(key)?.let { requestHeaders[key] = it }
                    }
                }
                requestHeaders["Accept"] = "text/vtt, text/plain, */*"

                val subtitle = SubtitleFile(lang = label, url = subtitleUrl)
                subtitle.headers = requestHeaders
                subtitleCallback(subtitle)
            }
        } else {
            configuredSubs?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sub = arr.optJSONObject(i) ?: continue
                    val rawUrl = sub.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val subtitleUrl = if (gateProof != null) {
                        appendQueryParameter(rawUrl, "p", gateProof)
                    } else {
                        rawUrl
                    }
                    if (seenSubs.add(subtitleUrl)) {
                        val subtitle = SubtitleFile(
                            lang = sub.optString("label", "Altyazı"),
                            url = subtitleUrl
                        )
                        subtitle.headers = subtitleHeaders
                        subtitleCallback(subtitle)
                    }
                }
            }
        }

        var emitted = false

        if (capturedPlaylist != null) {
            val finalUrl = capturedPlaylist.url.toString()
            val capturedHeaders = LinkedHashMap(mediaHeaders)
            capturedPlaylist.headers.names().forEach { key ->
                if (key.equals("Cookie", true) ||
                    key.equals("User-Agent", true) ||
                    key.equals("Referer", true) ||
                    key.equals("Origin", true) ||
                    key.equals("Accept-Language", true)
                ) {
                    capturedPlaylist.header(key)?.let { capturedHeaders[key] = it }
                }
            }

            callback(newExtractorLink(
                source = name,
                name = "$name Orijinal",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl
                this.headers = capturedHeaders
                this.quality = Qualities.Unknown.value
            })
            emitted = true
            Log.d("AsyaWatch", "FourPichive tarayıcı playlist'i yakalandı: nl.php; altyazı isteği=${capturedSubtitleRequests.size}")
        } else {
            Log.w("AsyaWatch", "FourPichive nl.php isteği yakalanamadı; NP_CONFIG kaynakları deneniyor")
            val sourceRows = npConfig.optJSONArray("sources") ?: run {
                Log.e("AsyaWatch", "sources yok ve nl.php yakalanamadı")
                return false
            }

            for (i in 0 until sourceRows.length()) {
                val source = sourceRows.optJSONObject(i) ?: continue
                val rawMaster = field(source, "master", "url", "file", "src") ?: continue
                val masterUrl = absoluteUrl(rawMaster, iframeUrl) ?: continue
                val label = field(source, "title", "name", "kind")?.takeIf { it.isNotBlank() } ?: "HLS"

                callback(newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                emitted = true
                Log.w("AsyaWatch", "FourPichive yedek kaynak verildi: nm.php; WebView nl.php isteğini yakalayamadı")
            }
        }

        if (!emitted) Log.e("AsyaWatch", "FourPichive kullanılabilir master bulamadı")
        return emitted
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val emitted = HashSet<String>()
        val seenC = HashSet<String>()
        val subs = LinkedHashMap<String, SubtitleFile>()

        val emitSub: (SubtitleFile) -> Unit = { s ->
            val l = s.lang
            if (l == "Türkçe" || l == "English" || l == "İngilizce Forced") subs.putIfAbsent(l, s)
        }

        suspend fun process(raw: String, ref: String) {
            val c = absoluteUrl(raw, ref) ?: return
            if (!seenC.add(c)) return
            if (isRootOrigin(c)) return
            if (isDirectMedia(c) && !isNmPhpUrl(c)) {
                if (emitted.add(c)) {
                    val t = if (c.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(source = name, name = name, url = c, type = t) {
                        referer = ref; quality = Qualities.Unknown.value
                    }); found = true
                }
                return
            }
            val host = runCatching { URI(c).host?.lowercase() }.getOrNull()
            if (host == "four.pichive.online" || host?.endsWith(".four.pichive.online") == true) {
                runCatching { extractFourPichive(c, ref, emitSub) { link -> if (emitted.add(link.url)) { callback(link); found = true } } }
                return
            }
            runCatching { loadExtractor(c, ref, emitSub) { link -> if (emitted.add(link.url)) callback(link); found = true } }
        }

        val page = runCatching { getPageData(data) }.getOrNull()
        if (page != null) {
            val cands = extractCandidates(page.document.html(), data)
            Log.d("AsyaWatch", "Sayfa adayları: ${cands.size}")
            for (c in cands) process(c, data)
        }
        subs.values.forEach { subtitleCallback(it) }
        return found
    }
}
