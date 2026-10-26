package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale

class DDizi : MainAPI() {
    override var mainUrl = "https://www.ddizi.tel"
    override var name = "DDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "yeni" to "Yeni Eklenen Bölümler",
        "yerli" to "Yerli Diziler",
        "yabanci" to "Yabancı Diziler",
        "eski" to "Eski Diziler"
    )

    // Ana sayfa kenar menüsünde "Yabancı Diziler" altında listelenen dizi id'leri.
    // Menüde yerli ve yabancı diziler aynı sayfada olduğu için ayırmak için kullanılır.
    private val foreignIds = setOf(
        1957, 2030, 1916, 1815, 1807, 1808, 1813, 1816, 1812,
        1791, 1811, 1817, 1884, 1809, 1814, 1810, 1917, 1972
    )

    private val seriesHref = Regex("/diziler/(\\d+)")
    private var indexCache: List<SearchResponse>? = null

    // ------------------------------------------------------------------ yardımcılar

    private fun norm(s: String): String = s.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanSeriesTitle(raw: String): String = raw
        .replace(Regex("\\s*\\|\\s*Ddizi.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(son bölüm|full|izle)\\b.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun Element.imgUrl(): String? {
        val img = if (tagName() == "img") this else (selectFirst("img") ?: return null)
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "data-img", "src")) {
            val v = img.attr(attr).trim()
            if (v.isNotEmpty() && !v.startsWith("data:")) return fixUrlNull(v)
        }
        return null
    }

    private fun Element.cardTitle(): String {
        val alt = selectFirst("img")?.attr("alt")?.trim().orEmpty()
        val title = attr("title").trim()
        return when {
            alt.isNotEmpty() -> alt
            text().isNotBlank() -> text().trim()
            else -> title
        }
    }

    private fun seriesResponse(title: String, url: String, poster: String?): SearchResponse =
        // Afişte rozet/buton gösterilmemesi için kalite, dublaj, bölüm sayısı vb. eklenmiyor.
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }

    /** Görselli dizi kartları (eski diziler / yabancı diziler listeleri). */
    private fun Document.cardsWithImg(): List<SearchResponse> =
        select("a[href*=/diziler/]:has(img)").mapNotNull { a ->
            val href = a.attr("href")
            if (!seriesHref.containsMatchIn(href)) return@mapNotNull null
            val title = cleanSeriesTitle(a.cardTitle())
            if (title.isBlank()) return@mapNotNull null
            seriesResponse(title, fixUrl(href), a.imgUrl())
        }.distinctBy { it.url }

    /** Kenar menüsündeki (görselsiz) dizi bağlantıları. */
    private fun Document.sidebarSeries(): List<SearchResponse> =
        select("a[href*=/diziler/]:not(:has(img))").mapNotNull { a ->
            val href = a.attr("href")
            if (!seriesHref.containsMatchIn(href)) return@mapNotNull null
            val title = cleanSeriesTitle(a.attr("title").ifBlank { a.text() })
            if (title.isBlank()) return@mapNotNull null
            seriesResponse(title, fixUrl(href), null)
        }.distinctBy { it.url }

    private fun idOf(url: String): Int? =
        seriesHref.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Ana sayfadaki bölüm kartları: (bölüm başlığı, bölüm url, afiş). */
    private fun Document.episodeCards(): List<Triple<String, String, String?>> =
        select("a[href*=/izle/]:has(img)").mapNotNull { a ->
            val title = a.cardTitle()
            if (title.isBlank()) return@mapNotNull null
            Triple(title, fixUrl(a.attr("href")), a.imgUrl())
        }.distinctBy { it.second }

    private fun stripEpisode(title: String): String =
        title.replace(Regex("\\s*\\d+\\s*\\.\\s*Bölüm.*$", RegexOption.IGNORE_CASE), "").trim()

    private suspend fun pagedCards(base: String, maxPages: Int): List<SearchResponse> {
        val first = app.get(base).document
        val path = base.substringAfter(mainUrl)
        val last = first.select("a[href*='$path/']")
            .mapNotNull { it.attr("href").substringAfterLast('/').toIntOrNull() }
            .maxOrNull()?.coerceAtMost(maxPages) ?: 1
        val rest = if (last >= 2) {
            (2..last).toList().amap { p ->
                runCatching { app.get("$base/$p").document.cardsWithImg() }.getOrDefault(emptyList())
            }.flatten()
        } else emptyList()
        return first.cardsWithImg() + rest
    }

    // ------------------------------------------------------------------ ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        when (request.data) {
            "yeni" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val doc = app.get(mainUrl).document
                val items = doc.episodeCards().map { (title, url, poster) ->
                    seriesResponse(title, url, poster)
                }
                return newHomePageResponse(request.name, items, false)
            }

            "yerli" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val doc = app.get(mainUrl).document
                // Afişleri, ana sayfadaki bölüm kartlarından eşleştir.
                val posters = doc.episodeCards().mapNotNull { (title, _, poster) ->
                    if (poster == null) null else norm(stripEpisode(title)) to poster
                }
                val items = doc.sidebarSeries()
                    .filter { (idOf(it.url) ?: 0) !in foreignIds }
                    .map { s ->
                        val n = norm(s.name)
                        val poster = posters.firstOrNull { (k, _) ->
                            k.isNotEmpty() && (n.startsWith(k) || k.startsWith(n))
                        }?.second
                        seriesResponse(s.name, s.url, poster)
                    }
                return newHomePageResponse(request.name, items, false)
            }

            "yabanci" -> {
                val url = if (page <= 1) "$mainUrl/yabanci-dizi-izle" else "$mainUrl/yabanci-dizi-izle/$page"
                val doc = app.get(url).document
                var items = doc.cardsWithImg()
                if (items.isEmpty() && page <= 1) {
                    items = app.get(mainUrl).document.sidebarSeries()
                        .filter { (idOf(it.url) ?: 0) in foreignIds }
                }
                val hasNext = doc.select("a:contains(Sonraki)").isNotEmpty()
                return newHomePageResponse(request.name, items, hasNext)
            }

            "eski" -> {
                val url = if (page <= 1) "$mainUrl/eski.diziler" else "$mainUrl/eski.diziler/$page"
                val doc = app.get(url).document
                val hasNext = doc.select("a:contains(Sonraki)").isNotEmpty()
                return newHomePageResponse(request.name, doc.cardsWithImg(), hasNext)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ arama

    /** Sitede arama adresi doğrulanamadığı için tüm dizi listesi bir kez çekilip yerelde filtrelenir. */
    private suspend fun buildIndex(): List<SearchResponse> {
        indexCache?.let { return it }
        val all = LinkedHashMap<String, SearchResponse>()
        runCatching { app.get(mainUrl).document.sidebarSeries() }
            .getOrDefault(emptyList()).forEach { all[it.url] = it }
        runCatching { pagedCards("$mainUrl/yabanci-dizi-izle", 30) }
            .getOrDefault(emptyList()).forEach { all[it.url] = it }
        runCatching { pagedCards("$mainUrl/eski.diziler", 60) }
            .getOrDefault(emptyList()).forEach { all[it.url] = it }
        return all.values.toList().also { if (it.isNotEmpty()) indexCache = it }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return buildIndex().filter { norm(it.name).contains(q) }
    }

    // ------------------------------------------------------------------ dizi sayfası + bölümler

    private val months = mapOf(
        "ocak" to 0, "şubat" to 1, "mart" to 2, "nisan" to 3, "mayıs" to 4, "haziran" to 5,
        "temmuz" to 6, "ağustos" to 7, "eylül" to 8, "ekim" to 9, "kasım" to 10, "aralık" to 11
    )
    private val dateRegex = Regex(
        "(\\d{1,2})\\s+(Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık)\\s+(\\d{4})",
        RegexOption.IGNORE_CASE
    )

    private fun parseDate(a: Element): Long? {
        val parent = a.parent() ?: return null
        // Üst öğe birden fazla bölüm içeriyorsa tarih karışabilir; o zaman atla.
        if (parent.select("a[href*=/izle/]").size > 2) return null
        val m = dateRegex.find(parent.text()) ?: return null
        val month = months[m.groupValues[2].lowercase(Locale("tr"))] ?: return null
        return runCatching {
            Calendar.getInstance().apply {
                clear()
                set(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
            }.timeInMillis
        }.getOrNull()
    }

    private data class RawEp(
        val url: String, val name: String, val season: Int?, val episode: Int?,
        val poster: String?, val date: Long?
    )

    private fun parseEpisodes(doc: Document): List<RawEp> =
        doc.select("a[href*=/izle/]:has(img)").mapNotNull { a ->
            val full = a.cardTitle()
            if (full.isBlank()) return@mapNotNull null
            val ep = Regex("(\\d+)\\s*\\.\\s*Bölüm", RegexOption.IGNORE_CASE)
                .find(full)?.groupValues?.get(1)?.toIntOrNull()
            val se = Regex("(\\d+)\\s*\\.\\s*Sezon", RegexOption.IGNORE_CASE)
                .find(full)?.groupValues?.get(1)?.toIntOrNull()
            RawEp(fixUrl(a.attr("href")), full, se, ep, a.imgUrl(), parseDate(a))
        }

    override suspend fun load(url: String): LoadResponse? {
        var seriesUrl = fixUrl(url)

        // Bölüm adresi verildiyse dizinin kendi sayfasına geç.
        if (seriesUrl.contains("/izle/")) {
            val epDoc = app.get(seriesUrl).document
            val link = epDoc.selectFirst("a:contains(sayfasina git)")?.attr("href")
                ?: epDoc.selectFirst("a:contains(sayfasına git)")?.attr("href")
            if (link.isNullOrBlank()) {
                val t = cleanSeriesTitle(epDoc.title())
                return newTvSeriesLoadResponse(t, seriesUrl, TvType.TvSeries, listOf(
                    newEpisode(seriesUrl) {
                        this.name = epDoc.title().substringBefore("|").trim()
                        this.season = 1
                        this.episode = 1
                    }
                )) {
                    this.posterUrl = epDoc.selectFirst("meta[property=og:image]")?.attr("content")
                }
            }
            seriesUrl = fixUrl(link)
        }

        val first = app.get(seriesUrl).document
        val title = cleanSeriesTitle(
            first.selectFirst("meta[property=og:title]")?.attr("content") ?: first.title()
        )

        // Tüm sayfalardaki bölümleri topla (site: /sayfa-0, /sayfa-1, ... "Sonraki »").
        val raw = ArrayList<RawEp>()
        val seenEp = HashSet<String>()
        val seenPages = HashSet<String>()
        var doc = first
        seenPages.add(seriesUrl)
        var guard = 0
        while (guard++ < 120) {
            parseEpisodes(doc).forEach { if (seenEp.add(it.url)) raw.add(it) }
            val nextHref = doc.select("a:contains(Sonraki)").firstOrNull()?.attr("href")
            if (nextHref.isNullOrBlank() || nextHref.startsWith("javascript")) break
            val nextUrl = fixUrl(nextHref)
            if (!seenPages.add(nextUrl)) break
            doc = app.get(nextUrl).document
        }

        // Site en yeniyi başa koyuyor; eskiden yeniye çevir.
        val ordered = raw.reversed()
        var counter = 0
        val episodes = ordered.map { r ->
            counter++
            newEpisode(r.url) {
                this.name = r.name.removePrefix(title).trim().ifBlank { r.name }
                this.season = r.season ?: 1
                this.episode = r.episode ?: counter
                this.posterUrl = r.poster
                this.date = r.date
            }
        }

        // Afiş: sayfada yoksa en yeni bölüm sayfasındaki og:image kullanılır.
        var poster = first.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            val latest = ordered.lastOrNull()?.url
            if (latest != null) {
                poster = runCatching {
                    app.get(latest).document.selectFirst("meta[property=og:image]")?.attr("content")
                }.getOrNull()
            }
        }
        val plot = first.selectFirst("meta[name~=(?i)^description$]")?.attr("content")

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    // ------------------------------------------------------------------ video bağlantıları

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\)]+?(?:\.m3u8|/master\.txt)[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE)
    private val mp4Regex = Regex("""https?://[^\s"'<>\\)]+?\.mp4[^\s"'<>\\)]*""")
    private val embedRegex = Regex(
        """https?://(?:[\w-]+\.)*(?:dailymotion\.com|dai\.ly|ok\.ru|vk\.com|vk\.ru|mail\.ru|sibnet\.ru|vidmoly\.\w+|streamtape\.\w+|doodstream\.\w+|dood\.\w+|mixdrop\.\w+|voe\.sx|filemoon\.\w+|vimeo\.com|uqload\.\w+|upstream\.to|supervideo\.\w+|youtube\.com/embed|youtube-nocookie\.com/embed|drive\.google\.com)/[^\s"'<>\\]+"""
    )
    private val playerRegex = Regex("""/player/oynat/[A-Za-z0-9_-]+""")

    // Çerezler her loadLinks çağrısında yerel tutulur; paralel bölüm yüklemeleri birbirini ezmez.
    private val playerHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Tarayıcı, farklı bir siteden gelen medya isteğinde sadece "https://www.ddizi.tel/" gönderir.
    private val mediaReferer get() = "$mainUrl/"
    private val mediaHeaders get() = mapOf("Origin" to mainUrl)

    private fun hostOf(u: String): String =
        runCatching { java.net.URI(u).host }.getOrNull() ?: "link"

    // Diagnostik loglarda olası geçici tokenları içeren query parametrelerini yazdırma.
    private fun debugUrl(u: String): String =
        runCatching {
            val uri = java.net.URI(u)
            "${uri.host ?: "?"}${uri.path ?: ""}"
        }.getOrDefault(u.take(180))

    private fun debug(message: String) {
        Log.d("DDiziDebug", message)
    }

    // Boş iframe src değerleri ve JavaScript placeholder'ları yüzünden gerçek
    // data-src/data-lazy-src adresinin atlanmasını önler.
    private fun absoluteHttpUrl(value: String, base: String): String? {
        val raw = value.trim()
        if (raw.isBlank() ||
            raw.startsWith("javascript:", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("blob:", ignoreCase = true) ||
            raw.startsWith("about:", ignoreCase = true) ||
            raw.startsWith("mailto:", ignoreCase = true) ||
            raw.startsWith("#") ||
            raw.contains("{") ||
            raw.contains(" ")
        ) return null

        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            else -> runCatching { java.net.URI(base).resolve(raw).toString() }
                .getOrElse { fixUrl(raw) }
        }
        return absolute.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun isDdiziHost(u: String): Boolean {
        val host = hostOf(u).lowercase(Locale.ROOT)
        return host == "ddizi.tel" || host.endsWith(".ddizi.tel")
    }

    // Videolar Twitter/X CDN'inden (video.twimg.com/amplify_video/.../pl/xxx.m3u8) geliyor.
    // Bu CDN için ddizi Referer/Origin göndermiyoruz; Twitter'ın kendi oynatıcısı gibi x.com kullanıyoruz.
    private fun isTwimg(u: String) = hostOf(u).endsWith("twimg.com")
    private fun refererFor(u: String, fallbackReferer: String? = null): String =
        if (isTwimg(u)) "https://x.com/" else
            fallbackReferer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: mediaReferer
    private fun headersFor(u: String): Map<String, String> =
        if (isTwimg(u)) {
            // Keep X CDN requests consistent for the master playlist, child playlists and segments.
            mapOf("Origin" to "https://x.com", "Accept" to "*/*")
        } else mediaHeaders

    // Cookies set by Streambox during manifest probing must also reach ExoPlayer.
    // The provider HTTP client and Cronet do not necessarily share the same cookie jar.
    private fun headersWithCookies(u: String, cookies: Map<String, String>): Map<String, String> {
        val headers = headersFor(u).toMutableMap()
        if (!isTwimg(u)) {
            // Mirror a normal browser request; Streambox responds to the plugin's
            // HTTP probe but may return a non-HLS response to the player's default UA.
            headers["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            headers["Accept"] = "*/*"
            if (cookies.isNotEmpty()) {
                headers["Cookie"] = cookies.entries.joinToString("; ") { (key, value) -> "${key}=${value}" }
            }
        }
        return headers
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fixUrl(data)
        debug("loadLinks start page=${debugUrl(epUrl)}")
        val res = try {
            app.get(epUrl, referer = "$mainUrl/")
        } catch (e: Exception) {
            Log.w("DDiziDebug", "episode request failed page=${debugUrl(epUrl)}", e)
            return false
        }
        val pageCookies = res.cookies
        val html = res.text
        val doc = res.document
        debug(
            "episode response code=${res.code} htmlLength=${html.length} " +
                "iframeCount=${doc.select("iframe").size} playerIds=${playerRegex.findAll(html).count()} " +
                "playlistCount=${m3u8Regex.findAll(html).count()} mp4Count=${mp4Regex.findAll(html).count()}"
        )

        val candidates = LinkedHashSet<String>()
        val sourceAttributes = listOf(
            "src", "data-src", "data-lazy-src", "data-video", "data-url",
            "data-embed", "data-iframe", "data-file", "data-player"
        )
        // Bazı bölümlerde oynatıcı adresi iframe yerine lazy-load/data-* alanlarında tutuluyor.
        doc.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], video[src], video[data-src], " +
                "source[src], source[data-src], [data-video], [data-url], [data-embed], " +
                "[data-iframe], [data-file], [data-player]"
        ).forEach { element ->
            // src "about:blank" olsa bile gerçek adres data-src alanında bulunabilir.
            // İlk dolu alanla durmak yerine bilinen tüm alanları dene.
            sourceAttributes.asSequence()
                .map { element.attr(it) }
                .mapNotNull { absoluteHttpUrl(it, epUrl) }
                .forEach(candidates::add)
        }
        playerRegex.findAll(html).forEach { candidates.add(fixUrl(it.value)) }
        doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(fixUrl(it)) }

        debug(
            "candidateCount=${candidates.size} candidates=" +
                candidates.take(12).joinToString(",") { debugUrl(it) } +
                if (candidates.size > 12) ",..." else ""
        )
        var found = false
        for (c in candidates) {
            val resolved = resolve(c, epUrl, 0, pageCookies, subtitleCallback, callback)
            debug("candidate result url=${debugUrl(c)} resolved=$resolved")
            if (resolved) found = true
        }
        // Oynatıcıdan sonuç çıkmadıysa bölüm sayfasının kaynağında doğrudan gömülü akış ara.
        if (!found) {
            val fallback = scanText(html, epUrl, epUrl, 0, pageCookies, subtitleCallback, callback)
            debug("page source fallback result=$fallback page=${debugUrl(epUrl)}")
            if (fallback) found = true
        }
        debug("loadLinks finish page=${debugUrl(epUrl)} found=$found")
        return found
    }

    private suspend fun resolve(
        url: String,
        referer: String,
        depth: Int,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 4) {
            debug("resolve depth limit url=${debugUrl(url)}")
            return false
        }
        debug("resolve start depth=$depth url=${debugUrl(url)} referer=${debugUrl(referer)}")

        // Bazı bölüm/player sayfaları doğrudan medya URL'si veriyor; extractor'a bırakmadan aç.
        val directHls = m3u8Regex.find(url)?.value
        if (directHls != null) {
            if (directHls.contains("/master.txt", ignoreCase = true)) {
                return resolveMasterTxt(directHls, referer, cookies, callback)
            }
            if (isTwimg(directHls)) {
                // Keep X's original playlist intact: splitting it with generateM3u8 can
                // detach an audio rendition from its video variant and produce silent playback.
                callback(newExtractorLink(name, "$name X HLS", directHls, ExtractorLinkType.M3U8) {
                    this.referer = refererFor(directHls, referer)
                    this.headers = headersFor(directHls)
                    this.quality = Qualities.Unknown.value
                })
                debug("X HLS passed through unchanged so ExoPlayer can select video and audio together")
                return true
            }
            val links = M3u8Helper.generateM3u8(
                "${name} ${hostOf(directHls)}", directHls,
                refererFor(directHls, referer), null, headersFor(directHls)
            )
            if (links.isNotEmpty()) {
                links.forEach(callback)
                return true
            }
            debug("HLS helper rejected direct URL=${debugUrl(directHls)} referer=${debugUrl(referer)}")
            callback(newExtractorLink(name, "${name} ${hostOf(directHls)}", directHls, ExtractorLinkType.M3U8) {
                this.referer = refererFor(directHls, referer)
                this.headers = headersFor(directHls)
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        val directMp4 = mp4Regex.find(url)?.value
        if (directMp4 != null) {
            callback(newExtractorLink(name, "${name} ${hostOf(directMp4)}", directMp4, ExtractorLinkType.VIDEO) {
                this.referer = refererFor(directMp4, referer)
                this.headers = headersFor(directMp4)
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        if (!isDdiziHost(url)) {
            // Bilinen hostları önce yerleşik extractor ile dene. Tanınmayan embedlerde HTML'i ayrıca tara.
            val extracted = runCatching {
                loadExtractor(url, referer, subtitleCallback, callback)
            }.onFailure {
                Log.w("DDiziDebug", "extractor error url=${debugUrl(url)}", it)
            }.getOrDefault(false)
            debug("external extractor url=${debugUrl(url)} extracted=$extracted")
            if (extracted) return true

            val res = try {
                app.get(url, referer = referer, headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ))
            } catch (e: Exception) {
                Log.w("DDiziDebug", "external request failed url=${debugUrl(url)}", e)
                return false
            }
            debug("external response url=${debugUrl(url)} code=${res.code} htmlLength=${res.text.length}")
            if (res.code !in 200..299) return false
            return scanText(
                res.text, url, url, depth + 1, res.cookies, subtitleCallback, callback
            )
        }

        val res = try {
            app.get(url, referer = referer, headers = playerHeaders, cookies = cookies)
        } catch (e: Exception) {
            Log.w("DDiziDebug", "DDizi player request failed url=${debugUrl(url)}", e)
            return false
        }
        debug("DDizi player response url=${debugUrl(url)} code=${res.code} htmlLength=${res.text.length}")
        if (res.code !in 200..299) return false
        val nextCookies = cookies + res.cookies
        val found = scanText(res.text, url, url, depth + 1, nextCookies, subtitleCallback, callback)
        debug("DDizi player scan url=${debugUrl(url)} found=$found")
        return found
    }


    /**
     * The Streambox URL is named master.txt, but some clients may receive an
     * error/interstitial page instead of an HLS manifest. Verify the response
     * before handing it to ExoPlayer; expand variant playlists when present.
     */
    private suspend fun resolveMasterTxt(
        url: String,
        referer: String,
        cookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            app.get(
                url,
                referer = referer,
                headers = headersFor(url) + mapOf("Accept" to "*/*"),
                cookies = cookies
            )
        } catch (e: Exception) {
            Log.w("DDiziDebug", "master.txt probe failed url=${debugUrl(url)}", e)
            return false
        }

        // Keep cookies newly issued by the master playlist; its child playlist may
        // depend on them even when the initial probe succeeds without an error.
        val masterCookies = cookies + res.cookies
        val body = res.text.removePrefix("\uFEFF").trimStart()
        val isManifest = body.startsWith("#EXTM3U")
        val preview = body.take(96)
            .map { ch -> if (ch.isLetterOrDigit() || ch in "#<>/:-_. ") ch else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .take(80)
        debug(
            "master.txt response url=${debugUrl(url)} code=${res.code} " +
                "length=${body.length} isManifest=$isManifest preview=$preview"
        )
        if (res.code !in 200..299 || !isManifest) return false

        val lines = body.lines().map { it.trim() }
        val variants = ArrayList<String>()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val next = lines.drop(index + 1).firstOrNull {
                    it.isNotBlank() && !it.startsWith("#")
                }
                next?.let { absoluteHttpUrl(it, url) }?.let(variants::add)
            }
        }

        if (variants.isNotEmpty()) {
            // Do not prefetch or parse the child playlist here.
            // The child endpoint may return a different response on subsequent
            // requests; let ExoPlayer perform the first request and parse the
            // verified master playlist's child URL itself.
            variants.distinct().forEach { variant ->
                callback(
                    newExtractorLink(name, "$name ${hostOf(variant)}", variant, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.headers = headersWithCookies(variant, masterCookies)
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            debug("master.txt variants=${variants.size} sent=${variants.isNotEmpty()}")
            return variants.isNotEmpty()
        }

        // A valid media playlist can be passed directly to ExoPlayer.
        callback(newExtractorLink(name, "$name ${hostOf(url)}", url, ExtractorLinkType.M3U8) {
            this.referer = refererFor(url, referer)
            this.headers = headersWithCookies(url, masterCookies)
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private suspend fun scanText(
        raw: String,
        base: String,
        referer: String,
        depth: Int,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val text = raw
            .replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\x2F", "/").replace("&amp;", "&")

        // atob("...") ile gizlenmiş içerikleri de çöz.
        val decoded = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""")
            .findAll(text).mapNotNull {
                runCatching { String(Base64.decode(it.groupValues[1], Base64.DEFAULT)) }.getOrNull()
            }.joinToString("\n")
        val joined = if (decoded.isBlank()) text else text + "\n" + decoded
        // URL-kodlu (https%3A%2F%2F...) adresleri de yakala.
        val urlDecoded = if (joined.contains("%3A%2F%2F", ignoreCase = true)) {
            runCatching { java.net.URLDecoder.decode(joined, "UTF-8") }.getOrDefault("")
        } else ""
        val all = if (urlDecoded.isBlank()) joined else joined + "\n" + urlDecoded

        // Doğrudan HLS
        for (m in m3u8Regex.findAll(all).map { it.value }.distinct()) {
            if (m.contains("/master.txt", ignoreCase = true)) {
                if (resolveMasterTxt(m, referer, cookies, callback)) found = true
                continue
            }
            if (isTwimg(m)) {
                // Do not split an X HLS manifest into child links; retain any paired audio rendition.
                callback(newExtractorLink(name, "$name X HLS", m, ExtractorLinkType.M3U8) {
                    this.referer = refererFor(m, referer)
                    this.headers = headersFor(m)
                    this.quality = Qualities.Unknown.value
                })
                found = true
                debug("X HLS passed through unchanged from embedded page source")
                continue
            }
            val links = M3u8Helper.generateM3u8(
                "$name ${hostOf(m)}", m, refererFor(m, referer), null, headersFor(m)
            )
            if (links.isNotEmpty()) {
                links.forEach(callback)
                found = true
            } else {
                debug("HLS helper rejected URL=${debugUrl(m)} referer=${debugUrl(referer)}")
            }
        }
        // Doğrudan MP4
        for (m in mp4Regex.findAll(all).map { it.value }.distinct()) {
            callback(
                newExtractorLink(name, "$name ${hostOf(m)}", m, ExtractorLinkType.VIDEO) {
                    this.referer = refererFor(m, referer)
                    this.headers = headersFor(m)
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        // Bilinen gömme siteleri
        for (m in embedRegex.findAll(all).map { it.value }.distinct()) {
            if (runCatching { loadExtractor(m, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            }
        }
        // İç içe iframe / video etiketleri
        val inner = Jsoup.parse(all, base)
        val frameAttributes = listOf(
            "src", "data-src", "data-lazy-src", "data-video", "data-url",
            "data-embed", "data-iframe", "data-file", "data-player"
        )
        val frames = inner.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], video[src], video[data-src], " +
                "source[src], source[data-src], [data-video], [data-url], [data-embed], " +
                "[data-iframe], [data-file], [data-player]"
        ).flatMap { element ->
            // Aynı elementte src ve data-src birlikte bulunabilir; ikisini de koru.
            frameAttributes.asSequence()
                .map { element.attr(it) }
                .mapNotNull { absoluteHttpUrl(it, base) }
                .toList()
        }.distinct()
        for (f in frames) {
            if (f == base) continue
            if (resolve(f, referer, depth + 1, cookies, subtitleCallback, callback)) found = true
        }
        inner.select("video[src], video source[src]").forEach {
            val s = it.attr("src")
            if (s.isNotBlank() && !s.endsWith(".m3u8")) {
                callback(
                    newExtractorLink(name, "$name ${hostOf(s)}", fixUrl(s), ExtractorLinkType.VIDEO) {
                        this.referer = refererFor(s, referer)
                        this.headers = headersFor(s)
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
