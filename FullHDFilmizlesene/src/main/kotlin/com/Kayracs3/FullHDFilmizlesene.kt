package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            pageUrl(request.data, page)
        }

        return try {
            val response = app.get(
                targetUrl,
                headers = browserHeaders
            )
            val document = response.document

            val filmLinks = document
                .select("a[href]")
                .mapNotNull { element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                    if (!isFilmUrl(href)) return@mapNotNull null
                    href
                }
                .distinct()

            Log.d(
                name,
                "getMainPage url=$targetUrl title=${document.title()} html=${document.html().length} filmLinks=${filmLinks.size}"
            )

            val results = document
                .select("a[href]")
                .filter { element ->
                    val href = fixUrlNull(element.attr("href"))
                    href != null && isFilmUrl(href)
                }
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            Log.d(name, "getMainPage parsedResults=${results.size}")

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
        return try {
            val lower = url.lowercase()
            val hostOk = lower.contains("fullhdfilmizlesene.now")
            hostOk && lower.contains("/film/")
        } catch (_: Exception) {
            false
        }
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!isFilmUrl(href)) return null

        val title = (
            selectFirst("h2")?.text()
                ?: selectFirst("h3")?.text()
                ?: selectFirst("h4")?.text()
                ?: attr("title").takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: text()
        )
            .trim()
            .replace(Regex("\\s+"), " ")
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-original").takeIf { it.isNotBlank() }
            }
        )

        val cardText = text()
        val quality = when {
            cardText.contains("4K", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("1080", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("720", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("HD", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        val searchUrls = listOf(
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for (searchUrl in searchUrls) {
            try {
                val document = app.get(
                    searchUrl,
                    headers = browserHeaders
                ).document

                val results = document
                    .select("a[href]")
                    .filter { element ->
                        val href = fixUrlNull(element.attr("href"))
                        href != null && isFilmUrl(href)
                    }
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
            val document = app.get(
                url,
                headers = browserHeaders
            ).document

            val title = (
                document.selectFirst("h1")?.text()
                    ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                    ?: "FullDFilmizlesene"
            )
                .trim()
                .removeSuffix(" izle")
                .removeSuffix(" İzle")
                .trim()

            val posterUrl = fixUrlNull(
                document.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
                    ?: document.selectFirst("img")?.let { img ->
                        img.attr("data-src").takeIf { it.isNotBlank() }
                            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                            ?: img.attr("src").takeIf { it.isNotBlank() }
                    }
            )

            val plot = document.selectFirst("meta[name='description']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("article p")?.text()?.trim()

            val pageText = document.text()

            val year = Regex("""(?:Yapım|Yıl)\\s*(?:[:\\-])?\\s*(\\d{4})""")
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""\\b(19\\d{2}|20\\d{2})\\b""")
                    .find(pageText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val tags = document
                .select("a[href*='/tur/'], a[href*='/genre/'], a[href*='/filmizle/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            Log.d(name, "load url=$url title=$title")

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
        Log.d(name, "loadLinks henüz eklenmedi -> $data")
        return false
    }
}
