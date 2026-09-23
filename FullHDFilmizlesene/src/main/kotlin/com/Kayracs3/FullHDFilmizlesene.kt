package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullDFilmizlesene"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"

    override val supportedTypes = setOf(TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"
        ),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa",
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/yil/2026-filmleri-izle" to "2026 Filmleri",
        "$mainUrl/filmizle/hd-720p-filmler-izle" to "1080p / 720p",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1" to "Türkçe Altyazılı",
        "$mainUrl/filmizle/yerli-filmler" to "Yerli Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else pageUrl(request.data, page)

        return try {
            val document = app.get(targetUrl, headers = browserHeaders).document

            // Afişin bulunduğu kartın tamamını seçiyoruz.
            // Önce sitenin klasik li.film yapısı, sonra olası alternatif kart sınıfları.
            val cards = document
                .select("li.film, article.film, .film-card, .film-item")
                .filter { it.selectFirst("a[href*='/film/']") != null }
                .distinctBy {
                    fixUrlNull(it.selectFirst("a[href*='/film/']")?.attr("href")) ?: it.html()
                }

            val results = cards.mapNotNull { it.toSearchResult() }

            Log.d(name, "getMainPage url=$targetUrl cards=${cards.size} results=${results.size}")

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage hata: $targetUrl -> ${e.message}", e)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.endsWith("/")) {
            "${base}sayfa/$page/"
        } else {
            "$base/sayfa/$page/"
        }
    }

    private fun isFilmUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("fullhdfilmizlesene.now") && lower.contains("/film/")
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotEmpty() }

    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null

        val direct = firstNonBlank(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-url"),
            img.attr("src")
        )

        if (!direct.isNullOrBlank() && !direct.startsWith("data:image", ignoreCase = true)) {
            return fixUrlNull(direct)
        }

        val srcSet = firstNonBlank(img.attr("data-srcset"), img.attr("srcset"))
        if (!srcSet.isNullOrBlank()) {
            val first = srcSet.split(",")
                .map { it.trim().substringBefore(" ") }
                .firstOrNull { it.isNotBlank() }
            if (!first.isNullOrBlank()) return fixUrlNull(first)
        }

        val style = firstNonBlank(img.attr("style"), element.attr("style"))
        if (!style.isNullOrBlank()) {
            val match = Regex("""url\(['\"]?([^)'\"]+)['\"]?\)""")
                .find(style)
                ?.groupValues
                ?.getOrNull(1)

            if (!match.isNullOrBlank()) return fixUrlNull(match)
        }

        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(
            selectFirst("a[href*='/film/']")?.attr("href")
                ?: selectFirst("a[href]")?.attr("href")
        ) ?: return null

        if (!isFilmUrl(href)) return null

        val title = firstNonBlank(
            selectFirst("span.film-title")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            selectFirst("a[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            selectFirst("a")?.attr("title"),
            selectFirst("a")?.text()
        )
            ?.replace(Regex("\\s+"), " ")
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?.trim()
            ?: return null

        val posterUrl = extractPoster(this)
        val cardText = text()

        val quality = when {
            cardText.contains("4K", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("1080", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("720", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("HD", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/arama/$encoded",
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for (searchUrl in searchUrls) {
            try {
                val document = app.get(searchUrl, headers = browserHeaders).document
                val results = document
                    .select("li.film, article.film, .film-card, .film-item")
                    .filter { it.selectFirst("a[href*='/film/']") != null }
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }

                Log.d(name, "search url=$searchUrl results=${results.size}")
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                Log.d(name, "Arama başarısız: $searchUrl -> ${e.message}")
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url, headers = browserHeaders).document

            val title = firstNonBlank(
                document.selectFirst("div.izle-titles")?.text(),
                document.selectFirst("h1")?.text(),
                document.selectFirst("meta[property='og:title']")?.attr("content")
            )
                ?.removeSuffix(" izle")
                ?.removeSuffix(" İzle")
                ?.trim()
                ?: return null

            val posterUrl = firstNonBlank(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("meta[name='twitter:image']")?.attr("content"),
                extractPoster(document)
            )?.let { fixUrlNull(it) }

            val plot = firstNonBlank(
                document.selectFirst("div.ozet-ic > p")?.text(),
                document.selectFirst("meta[name='description']")?.attr("content"),
                document.selectFirst("article p")?.text()
            )

            val year = firstNonBlank(
                document.selectFirst("div.dd a.category")?.text()?.split(" ")?.firstOrNull(),
                Regex("""(?:Yapım|Yıl)\s*[:\-]?\s*(\d{4})""")
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1),
                Regex("""\b(19\d{2}|20\d{2})\b""")
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1)
            )?.toIntOrNull()

            val tags = document
                .select("a[rel='category tag'], a[href*='/tur/'], a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            Log.d(name, "load title=$title poster=$posterUrl")

            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e(name, "Film sayfası yüklenemedi: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d(name, "loadLinks -> $data")

            val document = app.get(data, headers = browserHeaders).document
            val pageHtml = document.html()
            var emitted = false

            val iframeUrls = document
                .select("iframe[src], iframe[data-src]")
                .mapNotNull { frame ->
                    fixUrlNull(
                        firstNonBlank(
                            frame.attr("data-src"),
                            frame.attr("src")
                        )
                    )
                }
                .filter { it.isNotBlank() }
                .distinct()

            Log.d(name, "iframe count=${iframeUrls.size}")

            for (iframeUrl in iframeUrls) {
                // Önce CloudStream extractor sistemini dene. Gerçekten link üretildiyse
                // emitted=true oluyor; sadece çağrılmış olması yeterli değil.
                try {
                    var extractorEmitted = false
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                    ) { link ->
                        extractorEmitted = true
                        callback(link)
                    }
                    if (extractorEmitted) emitted = true
                    Log.d(name, "loadExtractor sonuc=$extractorEmitted -> $iframeUrl")
                } catch (e: Exception) {
                    Log.d(name, "loadExtractor başarısız -> $iframeUrl : ${e.message}")
                }

                // Extractor bulunamazsa iframe HTML'sinden doğrudan m3u8/master.txt ara.
                try {
                    val iframeHtml = app.get(
                        iframeUrl,
                        headers = browserHeaders + ("Referer" to data)
                    ).text

                    val candidates = extractMediaCandidates(iframeHtml)
                    Log.d(name, "iframe candidates=${candidates.size} -> $iframeUrl")

                    for (candidate in candidates) {
                        if (emitM3u8(candidate, iframeUrl, callback)) emitted = true
                    }
                } catch (e: Exception) {
                    Log.d(name, "iframe okunamadı -> $iframeUrl : ${e.message}")
                }
            }

            // Bazı sürümlerde link iframe yerine doğrudan film sayfasındaki script'tedir.
            val pageCandidates = extractMediaCandidates(pageHtml)
            Log.d(name, "page candidates=${pageCandidates.size}")

            for (candidate in pageCandidates) {
                if (emitM3u8(candidate, data, callback)) emitted = true
            }

            emitted
        } catch (e: Exception) {
            Log.e(name, "loadLinks hata: ${e.message}", e)
            false
        }
    }

    private fun extractMediaCandidates(html: String): List<String> {
        val candidates = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            var value = raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .trim(' ', '\'', '"', '`')

            if (value.startsWith("//")) value = "https:$value"

            if (value.startsWith("http://") || value.startsWith("https://")) {
                candidates += value
            }
        }

        Regex(
            """(?i)(?:selectedMasterUrl|masterUrl)\s*[:=]\s*[\"']([^\"']+)[\"']"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        Regex(
            """(?i)[\"'](https?://[^\"']*(?:master\.txt|master\.m3u8|\.m3u8(?:\?[^\"']*)?|\.mp4(?:\?[^\"']*)?))[\"']"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        Regex(
            """(?i)(https?://[^\s\"'<>]+(?:master\.txt|master\.m3u8|\.m3u8(?:\?[^\s\"'<>]*)?|\.mp4(?:\?[^\s\"'<>]*)?))"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        return candidates.toList()
    }

    private suspend fun emitM3u8(
        candidate: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = candidate

        if (url.contains("master.txt", ignoreCase = true)) {
            try {
                val text = app.get(
                    url,
                    headers = browserHeaders + ("Referer" to referer)
                ).text

                val m3u8 = Regex(
                    """(?i)(https?://[^\s\"'<>]+(?:master\.m3u8|\.m3u8)(?:\?[^\s\"'<>]*)?)"""
                ).find(text)?.groupValues?.getOrNull(1)

                if (!m3u8.isNullOrBlank()) url = m3u8
            } catch (e: Exception) {
                Log.d(name, "master.txt okunamadı -> $url : ${e.message}")
            }
        }

        if (!url.contains(".m3u8", ignoreCase = true)) return false

        callback(
            newExtractorLink(
                source = name,
                name = "FullDFilmizlesene",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = referer
                quality = Qualities.P1080.value
            }
        )

        Log.d(name, "M3U8 emit -> $url")
        return true
    }
}
