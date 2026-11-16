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

    private val fourPichiveResolver = WebViewResolver(Regex("https://(four\\.)?pichive\\.online/.*"))
    private val powSalt = "l_GnlwTfd3"

    private data class PageData(val document: Document, val secureData: JSONObject?)
    @Volatile private var rateLimitedUntil = 0L
    private val powCache = ConcurrentHashMap<String, Long>()

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

    private fun solvePow(input: String): Long? {
        powCache[input]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        val suffix = "|$powSalt|$input"
        for (i in 0L until 5_000_000L) {
            val hash = digest.digest("$i$suffix".toByteArray(Charsets.UTF_8))
            val w7 = ((hash[28].toInt() and 0xFF) shl 24) or ((hash[29].toInt() and 0xFF) shl 16) or
                     ((hash[30].toInt() and 0xFF) shl 8) or (hash[31].toInt() and 0xFF)
            if ((w7 ushr 20) == 0) { powCache[input] = i; return i }
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
        val re = Regex("""window\.NP_CONFIG\s*=\s*(\{.+?\});\s*(?:window\.|</script>|$)""", RegexOption.DOT_MATCHES_ALL)
        val m = re.find(html) ?: return null
        return parseObject(m.groupValues[1].trim())
    }

    // ⭐⭐⭐ HER ÇAĞRIDA ÇALIŞAN TEŞHİS — FLAG YOK ⭐⭐⭐
    private suspend fun diagnoseNpJs(html: String, iframeUrl: String, nmUrl: String, npId: String?) {
        val base = "https://four.pichive.online"
        Log.d("AsyaWatch", "═══════════════════════════════════")
        Log.d("AsyaWatch", "🔬 NP.JS TEŞHİS (her çağrıda)")
        Log.d("AsyaWatch", "═══════════════════════════════════")

        // np.js URL'i HTML'den bul
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
        val scriptUrls = doc?.select("script[src]")?.mapNotNull { absoluteUrl(it.attr("src"), iframeUrl) } ?: emptyList()
        Log.d("AsyaWatch", "📜 Script URL'leri: $scriptUrls")

        for (jsUrl in scriptUrls) {
            if (!jsUrl.contains("np", true) && !jsUrl.contains("player", true)) continue
            val js = runCatching { app.get(jsUrl, headers = siteHeaders, referer = base).text }.getOrNull() ?: continue
            Log.d("AsyaWatch", "📜 np.js: $jsUrl (${js.length}b)")

            // NP_GATE çağrısını bul (tanım değil)
            var from = 0
            var count = 0
            while (count < 5) {
                val idx = js.indexOf("NP_GATE", from)
                if (idx < 0) break
                val isDef = idx + 7 < js.length && js[idx + 7] == '='
                if (!isDef) {
                    val s = maxOf(0, idx - 200)
                    val e = minOf(js.length, idx + 500)
                    Log.d("AsyaWatch", "🎯 NP_GATE çağrı @$idx: ...${js.substring(s, e).replace(Regex("\\s+"), " ")}...")
                    count++
                }
                from = idx + 7
            }

            // p= veya p= POW param eklemesi için context
            for (kw in listOf("&p=", "p=", "searchParams", "URLSearchParams", "location.search")) {
                val i = js.indexOf(kw)
                if (i >= 0) {
                    val s = maxOf(0, i - 150)
                    val e = minOf(js.length, i + 250)
                    Log.d("AsyaWatch", "🔍 '$kw' @$i: ...${js.substring(s, e).replace(Regex("\\s+"), " ")}...")
                }
            }
        }

        // İnline NP_GATE tanımını da logla (bu zaten html'de)
        val inlineIdx = html.indexOf("NP_GATE=function")
        if (inlineIdx >= 0) {
            val s = maxOf(0, inlineIdx - 50)
            val e = minOf(html.length, inlineIdx + 400)
            Log.d("AsyaWatch", "🎯 Inline NP_GATE: ...${html.substring(s, e).replace(Regex("\\s+"), " ")}...")
        }

        Log.d("AsyaWatch", "═══════════════════════════════════")
    }

    private suspend fun tryAllPowVariants(nmUrl: String, iframeUrl: String, npId: String?, html: String): String? {
        val vMatch = Regex("""[?&]v=([^&]+)""").find(nmUrl)
        val vToken = vMatch?.groupValues?.getOrNull(1) ?: return null
        val baseUrl = nmUrl.substringBefore('?')
        val headers = browserHeaders(iframeUrl)

        // HTML hash'ini input olarak dene
        val htmlHash = runCatching {
            MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrNull()

        val candidates = mutableListOf<Pair<String, String>>()
        if (!npId.isNullOrBlank()) {
            candidates.add("id" to npId)
            candidates.add("idUPPER" to npId.uppercase())
        }
        candidates.add("vToken" to vToken)
        candidates.add("fullMasterUrl" to nmUrl)
        if (!npId.isNullOrBlank()) {
            candidates.add("id|vToken" to "$npId|$vToken")
            candidates.add("vToken|id" to "$vToken|$npId")
        }
        if (htmlHash != null) candidates.add("htmlHash" to htmlHash)

        Log.d("AsyaWatch", "🎫 ${candidates.size} input denenecek")

        // PoW'suz
        Log.d("AsyaWatch", "🧪 PoW'suz nm.php")
        val np = runCatching { app.get(nmUrl, headers = headers, referer = iframeUrl) }.getOrNull()
        if (np != null) {
            Log.d("AsyaWatch", "  → HTTP ${np.code}, bodyLen=${np.text.length}")
            if (np.code == 200 && np.text.trim().startsWith("#EXTM3U")) return nmUrl
        }

        // p=0/1
        for (testP in listOf("0", "1")) {
            val testUrl = "$baseUrl?v=$vToken&p=$testP"
            val r = runCatching { app.get(testUrl, headers = headers, referer = iframeUrl) }.getOrNull()
            Log.d("AsyaWatch", "🧪 p=$testP → HTTP ${r?.code}, bodyLen=${r?.text?.length}")
            if (r != null && r.code == 200 && r.text.trim().startsWith("#EXTM3U")) return testUrl
        }

        // Her aday için PoW + 2 URL formatı (p önce/sonra)
        for ((label, input) in candidates) {
            val pow = solvePow(input) ?: continue
            Log.d("AsyaWatch", "🎫 $label (${input.length}ch) → n=$pow")

            val urlFormats = listOf(
                "$baseUrl?v=$vToken&p=$pow",
                "$baseUrl?p=$pow&v=$vToken",
                "$baseUrl?v=$vToken&pow=$pow"
            )

            for (testUrl in urlFormats) {
                val format = testUrl.substringAfterLast('?').take(20)
                val r = runCatching { app.get(testUrl, headers = headers, referer = iframeUrl) }.getOrNull() ?: continue
                Log.d("AsyaWatch", "  $label [$format]: HTTP ${r.code}, bodyLen=${r.text.length}")

                if (r.code == 200 && r.text.trim().startsWith("#EXTM3U")) {
                    Log.d("AsyaWatch", "🎉🎉🎉 BAŞARILI! $label, $format, bodyLen=${r.text.length}")
                    return testUrl
                }
                // 404 olsa bile body varsa logla
                if (r.text.isNotBlank() && r.text.length < 500) {
                    Log.d("AsyaWatch", "  body: ${r.text.take(300)}")
                }
            }

            // WebView de bir kere
            val wvUrl = "$baseUrl?v=$vToken&p=$pow"
            val wv = runCatching { app.get(wvUrl, headers = headers, referer = iframeUrl, interceptor = fourPichiveResolver) }.getOrNull()
            if (wv != null) {
                Log.d("AsyaWatch", "  $label [WebView]: HTTP ${wv.code}, bodyLen=${wv.text.length}")
                if (wv.code == 200 && wv.text.trim().startsWith("#EXTM3U")) return wvUrl
            }
        }
        return null
    }

    private suspend fun extractFourPichive(iframeUrl: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (isRootOrigin(iframeUrl)) return false
        Log.d("AsyaWatch", "🔥 FourPichive: ${iframeUrl.take(80)}")

        val resp = runCatching { app.get(iframeUrl, headers = browserHeaders(iframeUrl), referer = referer, allowRedirects = true) }.getOrNull() ?: return false
        if (!resp.isSuccessful) { Log.e("AsyaWatch", "iframe HTTP ${resp.code}"); return false }
        val html = resp.text
        if (html.isBlank()) return false
        Log.d("AsyaWatch", "iframe HTML: ${html.length}b")

        val npConfig = parseNpConfig(html) ?: return false
        val npId = npConfig.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
        Log.d("AsyaWatch", "NP_CONFIG id=$npId")

        val masterUrl = npConfig.optJSONArray("sources")?.optJSONObject(0)?.optString("master")?.takeIf { it.isNotBlank() && it != "null" } ?: return false
        Log.d("AsyaWatch", "master: ${masterUrl.take(80)}")

        // ⭐ HER ÇAĞRIDA TEŞHİS ÇALIŞSIN
        diagnoseNpJs(html, iframeUrl, masterUrl, npId)

        // Altyazılar
        val seen = HashSet<String>()
        npConfig.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                val label = sub.optString("label", "Altyazı")
                val u = sub.optString("url")
                if (u.isNotBlank()) {
                    val s = SubtitleFile(lang = label, url = u)
                    s.headers = siteHeaders + mapOf("Referer" to iframeUrl, "Origin" to "https://four.pichive.online", "Accept" to "text/vtt, text/plain, */*")
                    if (seen.add(u)) subtitleCallback(s)
                }
            }
        }

        val finalUrl = if (html.contains("NP_GATE")) tryAllPowVariants(masterUrl, iframeUrl, npId, html) else masterUrl
        if (finalUrl == null) { Log.e("AsyaWatch", "✘ Tüm PoW varyantları başarısız"); return false }

        val type = if (finalUrl.contains(".m3u8", true) || isNmPhpUrl(finalUrl)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink(source = name, name = "$name FourPichive", url = finalUrl, type = type) {
            this.referer = iframeUrl
            this.headers = browserHeaders(iframeUrl)
            this.quality = Qualities.Unknown.value
        })
        return true
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
