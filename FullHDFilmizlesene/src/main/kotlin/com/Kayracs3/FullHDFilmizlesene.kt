package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FullDFilmizlesene : MainAPI() {
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
            title = title,
            url = href,
            type = TvType.Movie
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
        val encoded = query.urlEncoded()

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

    private fun findMasterUrl(text: String): String? {
        val patterns = listOf(
            Regex(
                """(?i)["']?selectedMasterUrl["']?\s*[:=]\s*["'](https?://[^"'\\\s]+)["']"""
            ),
            Regex(
                """(?i)["']?(?:masterUrl|master_url)["']?\s*[:=]\s*["'](https?://[^"'\\\s]+)["']"""
            ),
            Regex(
                """(?i)(https?://[^"'<>\\\s]+(?:master\.txt|master\.m3u8)(?:\?[^"'<>\\\s]*)?)"""
            ),
            Regex(
                """(?i)(https?://[^"'<>\\\s]+\.m3u8(?:\?[^"'<>\\\s]*)?)"""
            )
        )

        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                return match
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("\\u003A", ":")
            }
        }

        return null
    }

    private suspend fun extractPlayerSource(
        playerUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                playerUrl,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val html = response.text
            Log.d(name, "Player HTML uzunluğu: ${html.length}")

            val masterUrl = findMasterUrl(html)

            if (masterUrl.isNullOrBlank()) {
                Log.d(name, "selectedMasterUrl/master URL HTML içinde bulunamadı")
                Log.d(
                    name,
                    "Player URL: $playerUrl | master.txt=${html.contains("master.txt", true)} | m3u8=${html.contains(".m3u8", true)}"
                )
                return false
            }

            Log.d(name, "Bulunan master URL: $masterUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "FullDFilmizlesene",
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = playerUrl
                    quality = Qualities.P1080.value
                }
            )

            true
        } catch (e: Exception) {
            Log.e(name, "Player source çıkarılırken hata: ${e.message}", e)
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TEST: DevTools'ta bu film için görülen selectedMasterUrl.
        // Bu URL film/oturum bazında değişebileceği için kalıcı çözüm değildir.
        val testUrl = "https://s32.cdnimages6326.shop/mf/ITIlozI0Yx5cozcuYwZhZwNlAF5KEHVgERjhZGN4ZUNhESIOGP5VYwV2AP1VER0d0zxL2EhnJ1uM2ImAwZlAv5mnT9js0xi32avr1"

        Log.d(name, "TEST selectedMasterUrl bulundu: $testUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "FullDFilmizlesene TEST",
                url = testUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://rapidvid.org/"
                quality = Qualities.P1080.value
            }
        )

        return true
    }

}
