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

    override val supportedTypes = setOf(
        TvType.Movie
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/130.0.0.0 Safari/537.36"

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

        val document = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )
        ).document

        val results = document
            .select("a[href*='/film/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.endsWith("/")) {
            "${base}sayfa/$page/"
        } else {
            "$base/sayfa/$page/"
        }
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null

        if (!href.contains("/film/")) return null

        val title = (
            selectFirst("h2")?.text()
                ?: selectFirst("h3")?.text()
                ?: selectFirst("[title]")?.attr("title")
                ?: attr("title")
                ?: text()
        )
            .trim()
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("data-lazy-src")
                ?: selectFirst("img")?.attr("src")
        )

        val quality = when {
            text().contains("4K", ignoreCase = true) -> SearchQuality.HD
            text().contains("1080", ignoreCase = true) -> SearchQuality.HD
            text().contains("720", ignoreCase = true) -> SearchQuality.HD
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

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
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
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$mainUrl/"
                    )
                ).document

                val results = document
                    .select("a[href*='/film/']")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }

                if (results.isNotEmpty()) {
                    return results
                }
            } catch (e: Exception) {
                Log.d(name, "Arama denemesi başarısız: $searchUrl -> ${e.message}")
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).document

            val title = (
                document.selectFirst("h1")?.text()
                    ?: document.selectFirst("meta[property='og:title']")
                        ?.attr("content")
                    ?: "FullDFilmizlesene"
            )
                .trim()
                .removeSuffix(" izle")
                .removeSuffix(" İzle")
                .trim()

            val posterUrl = fixUrlNull(
                document.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
                    ?: document.selectFirst("img")?.attr("src")
            )

            val plot = document.selectFirst("meta[name='description']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("article p")
                    ?.text()
                    ?.trim()

            val pageText = document.text()

            val year = Regex("""(?:Yapım|Yıl)\s*(?:[:\-])?\s*(\d{4})""")
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""\b(19\d{2}|20\d{2})\b""")
                    .find(pageText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val tags = document
                .select("a[href*='/tur/'], a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

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
        /*
         * Video bağlantısı henüz bilinçli olarak burada bırakıldı.
         *
         * Sen Network/DevTools üzerinden gerçek video bağlantısının
         * nereden geldiğini söylediğinde sadece bu bölümü dolduracağız.
         *
         * Şu anda plugin:
         * - ana sayfa/listeleri okur
         * - arama yapar
         * - film detayını açar
         * - metadata/poster/özet/yıl/tür bilgilerini alır
         */

        Log.d(name, "loadLinks beklemede -> $data")
        return false
    }
}
