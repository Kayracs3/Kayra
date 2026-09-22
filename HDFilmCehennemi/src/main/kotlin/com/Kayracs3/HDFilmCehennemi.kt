package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

data class Results(
    @JsonProperty("results")
    val results: List<String>
)

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 " +
            "(Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 " +
            "(KHTML, like Gecko) " +
            "Chrome/120.0.0.0"

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-5" to "Yeni Eklenen Diziler",
        "$mainUrl/category/tavsiye-filmler-izle3" to "Tavsiye Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmle-2r" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle-4" to "En Çok Beğenilenler",
        "$mainUrl/tur/aile-filmleri-izleyin-7" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-8" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmlerini-izleyin-5" to "Animasyon",
        "$mainUrl/tur/belgesel-filmlerini-izle-2" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-5" to "Bilim Kurgu",
        "$mainUrl/tur/komedi-filmlerini-izleyin-2" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmlerini-izle-9/" to "Korku Filmleri",
        "$mainUrl/tur/romantik-filmleri-izle-3" to "Romantik Filmleri"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val home = document
            .select("div.section-content a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this
            .selectFirst("strong.poster-title")?.text()
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?q=$query",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "User-Agent" to userAgent
            )
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val titleText = document.selectFirst("h4.title")?.text()
                ?: return@mapNotNull null

            val hrefText = fixUrlNull(
                document.selectFirst("a")?.attr("href")
            ) ?: return@mapNotNull null

            val imgEl = document.selectFirst("img")
            val srcText = fixUrlNull(imgEl?.attr("src"))
                ?: fixUrlNull(imgEl?.attr("data-src"))

            newMovieSearchResponse(titleText, hrefText, TvType.Movie) {
                this.posterUrl = srcText?.replace("/thumb/", "/list/")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val isTvSeries = !document.select("div.seasons").isEmpty()

        return if (isTvSeries) {
            newTvSeriesLoadResponse(
                name = document.selectFirst("h1.section-title")?.text()
                    ?.substringBefore(" izle") ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = document.select("div.seasons-tab-content a")
                    .mapNotNull {
                        val epName = it.selectFirst("h4")?.text()?.trim()
                            ?: return@mapNotNull null

                        newEpisode(fixUrlNull(it.attr("href")) ?: "") {
                            this.name = epName

                            this.season = Regex("""(\d+)\. ?Sezon""")
                                .find(epName)?.groupValues?.get(1)
                                ?.toIntOrNull() ?: 1

                            this.episode = Regex("""(\d+)\. ?Bölüm""")
                                .find(epName)?.groupValues?.get(1)
                                ?.toIntOrNull()
                        }
                    }
            ) {
                this.posterUrl = fixUrlNull(
                    document
                        .select("aside.post-info-poster img.lazyload")
                        .lastOrNull()
                        ?.attr("data-src")
                )

                this.year = document.selectFirst(
                    "div.post-info-year-country a"
                )?.text()?.trim()?.toIntOrNull()

                this.plot = document.selectFirst(
                    "article.post-info-content > p"
                )?.text()?.trim()

                this.tags = document
                    .select("div.post-info-genres a")
                    .map { it.text() }

                this.actors = document
                    .select("div.post-info-cast a")
                    .mapNotNull {
                        ActorData(
                            Actor(
                                it.selectFirst("strong")?.text()
                                    ?: return@mapNotNull null,
                                it.selectFirst("img")?.attr("data-src")
                            ),
                            null,
                            null
                        )
                    }

                this.recommendations = document
                    .select("div.section-slider-container div.slider-slide")
                    .mapNotNull {
                        newTvSeriesSearchResponse(
                            it.selectFirst("a")?.attr("title")
                                ?: return@mapNotNull null,
                            fixUrlNull(
                                it.selectFirst("a")?.attr("href")
                            ) ?: return@mapNotNull null,
                            TvType.TvSeries
                        ) {
                            this.posterUrl =
                                fixUrlNull(
                                    it.selectFirst("img")?.attr("data-src")
                                ) ?: fixUrlNull(
                                    it.selectFirst("img")?.attr("src")
                                )
                        }
                    }
            }
        } else {
            newMovieLoadResponse(
                name = document.selectFirst("h1.section-title")?.text()
                    ?.substringBefore(" izle") ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = fixUrlNull(
                    document
                        .select("aside.post-info-poster img.lazyload")
                        .lastOrNull()
                        ?.attr("data-src")
                )

                this.year = document.selectFirst(
                    "div.post-info-year-country a"
                )?.text()?.trim()?.toIntOrNull()

                this.plot = document.selectFirst(
                    "article.post-info-content > p"
                )?.text()?.trim()

                this.tags = document
                    .select("div.post-info-genres a")
                    .map { it.text() }

                this.actors = document
                    .select("div.post-info-cast a")
                    .mapNotNull {
                        ActorData(
                            Actor(
                                it.selectFirst("strong")?.text()
                                    ?: return@mapNotNull null,
                                it.selectFirst("img")?.attr("data-src")
                            ),
                            null,
                            null
                        )
                    }

                this.recommendations = document
                    .select("div.section-slider-container div.slider-slide")
                    .mapNotNull {
                        newTvSeriesSearchResponse(
                            it.selectFirst("a")?.attr("title")
                                ?: return@mapNotNull null,
                            fixUrlNull(
                                it.selectFirst("a")?.attr("href")
                            ) ?: return@mapNotNull null,
                            TvType.TvSeries
                        ) {
                            this.posterUrl =
                                fixUrlNull(
                                    it.selectFirst("img")?.attr("data-src")
                                ) ?: fixUrlNull(
                                    it.selectFirst("img")?.attr("src")
                                )
                        }
                    }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        var linkFound = false

        val elements = document.select("iframe")

        Log.d(
            "HDFilmCehennemi",
            "Bulunan iframe sayisi: ${elements.size}"
        )

        for (element in elements) {
            val rawSrc = sequenceOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-frame")
            ).firstOrNull { it.isNotBlank() }

            if (rawSrc.isNullOrBlank()) {
                Log.d("HDFilmCehennemi", "Iframe src bulunamadi")
                continue
            }

            val fixedUrl = fixUrl(rawSrc)

            Log.d(
                "HDFilmCehennemi",
                "Player URL: $fixedUrl"
            )

            try {
                val handled = if (
                    fixedUrl.contains("hdfilmcehennemi.mobi/video/embed", ignoreCase = true) ||
                    fixedUrl.contains("rapidrame_id=", ignoreCase = true)
                ) {
                    extractRapidrame(
                        playerUrl = fixedUrl,
                        siteReferer = data,
                        callback = callback
                    )
                } else {
                    loadExtractor(
                        fixedUrl,
                        data,
                        subtitleCallback,
                        callback
                    )
                }

                if (handled) {
                    linkFound = true
                    Log.d(
                        "HDFilmCehennemi",
                        "Kaynak bulundu: $fixedUrl"
                    )
                } else {
                    Log.d(
                        "HDFilmCehennemi",
                        "Kaynak bulunamadi: $fixedUrl"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HDFilmCehennemi",
                    "Link hatasi ($fixedUrl): ${e.message}",
                    e
                )
            }
        }

        Log.d(
            "HDFilmCehennemi",
            "loadLinks tamamlandi. linkFound=$linkFound"
        )

        return linkFound
    }

    private suspend fun extractRapidrame(
        playerUrl: String,
        siteReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val rawHtml = app.get(
                playerUrl,
                referer = siteReferer,
                headers = mapOf("User-Agent" to userAgent)
            ).text

            val normalized = rawHtml
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")

            val unpacked = try {
                getAndUnpack(normalized)
            } catch (_: Exception) {
                normalized
            }

            val candidates = listOf(normalized, unpacked)

            val absoluteRegex = Regex(
                """(?:(?:https?:)?//)[^\"'\s<>]+(?:master\.m3u8|index\.m3u8|\.m3u8)(?:\?[^\"'\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

            val relativeRegex = Regex(
                """(?:^|[\"'])((?:/|\./|\.\./)?[^\"'\s<>]*(?:master\.m3u8|index\.m3u8|\.m3u8)(?:\?[^\"'\s<>]*)?)""",
                RegexOption.IGNORE_CASE
            )

            var streamUrl: String? = null

            for (text in candidates) {
                streamUrl = absoluteRegex.find(text)?.value
                    ?: relativeRegex.find(text)?.groupValues?.getOrNull(1)

                if (!streamUrl.isNullOrBlank()) break
            }

            if (streamUrl.isNullOrBlank()) {
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame sayfasinda m3u8 bulunamadi"
                )
                return false
            }

            val finalUrl = when {
                streamUrl!!.startsWith("//") -> "https:$streamUrl"
                streamUrl.startsWith("http://") || streamUrl.startsWith("https://") -> streamUrl
                else -> URI(playerUrl).resolve(streamUrl).toString()
            }

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame M3U8 bulundu: $finalUrl"
            )

            callback.invoke(
                newExtractorLink(
                    source = "HDFilmCehennemi",
                    name = "Rapidrame",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = siteReferer
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to userAgent)
                }
            )

            true
        } catch (e: Exception) {
            Log.e(
                "HDFilmCehennemi",
                "Rapidrame extractor hatasi: ${e.message}",
                e
            )
            false
        }
    }
}
