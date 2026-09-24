package com.Kayracs3

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Dizilla : MainAPI() {

    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    // CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/arsiv" to "Yeni Eklenen Bölümler",
        "${mainUrl}/yabanci-dizi-izle" to "Öne Çıkan Diziler",
        "${mainUrl}/anime-izle" to "Asya Dizileri",
        "${mainUrl}/kdrama-izle" to "Anime Dizileri",
        
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data
        ).document

        val home =
            if (request.data.contains("dizi-turu")) {

                document
                    .select("div.grid-cols-3 a")
                    .mapNotNull {
                        it.diziler()
                    }

            } else {

                document
                    .select("div.grid a")
                    .mapNotNull {
                        it.sonBolumler()
                    }
            }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    // =========================================================
    // DİZİLER
    // =========================================================

    private fun Element.diziler(): SearchResponse? {

        val title =
            this
                .selectFirst("h2")
                ?.text()
                ?: return null

        val href =
            fixUrlNull(
                this.attr("href")
            )
                ?: return null

        val posterUrl =
            fixUrlNull(
                this
                    .selectFirst("img")
                    ?.attr("data-src")
            )
                ?: fixUrlNull(
                    this
                        .selectFirst("img")
                        ?.attr("src")
                )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    // =========================================================
    // SON BÖLÜMLER
    // =========================================================

    private suspend fun Element.sonBolumler(): SearchResponse? {

        val name =
            this
                .selectFirst("h2")
                ?.text()
                ?: return null

        val epName =
            this
                .selectFirst("div.opacity-80")
                ?.text()
                ?.replace(
                    ". Sezon ",
                    "x"
                )
                ?.replace(
                    ". Bölüm",
                    ""
                )
                ?: return null

        val title =
            "$name - $epName"

        val epDoc =
            app.get(
                this.attr("href")
            ).document

        val href =
            fixUrlNull(
                epDoc
                    .selectFirst(
                        "a.relative"
                    )
                    ?.attr("href")
            )
                ?: return null

        val posterUrl =
            fixUrlNull(
                epDoc
                    .selectFirst(
                        "img.imgt"
                    )
                    ?.attr("onerror")
                    ?.substringAfter(
                        "= '"
                    )
                    ?.substringBefore(
                        "';"
                    )
            )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    // =========================================================
    // SEARCH ITEM
    // =========================================================

    private fun SearchItem.toSearchResponse():
        SearchResponse? {

        return newTvSeriesSearchResponse(
            title
                ?: return null,
            "${mainUrl}/${slug}",
            TvType.TvSeries,
        ) {

            this.posterUrl =
                poster
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val mainReq =
            app.get(
                mainUrl
            )

        val mainPage =
            mainReq.document

        val cKey =
            mainPage
                .selectFirst(
                    "input[name='cKey']"
                )
                ?.attr("value")
                ?: return emptyList()

        val cValue =
            mainPage
                .selectFirst(
                    "input[name='cValue']"
                )
                ?.attr("value")
                ?: return emptyList()

        val veriler =
            mutableListOf<SearchResponse>()

        val searchReq =
            app.post(
                "${mainUrl}/bg/searchcontent",

                data = mapOf(
                    "cKey" to cKey,
                    "cValue" to cValue,
                    "searchterm" to query
                ),

                headers = mapOf(
                    "Accept" to
                        "application/json, text/javascript, */*; q=0.01",

                    "X-Requested-With" to
                        "XMLHttpRequest"
                ),

                referer =
                    "${mainUrl}/",

                cookies = mapOf(
                    "showAllDaFull" to
                        "true",

                    "PHPSESSID" to
                        mainReq
                            .cookies["PHPSESSID"]
                            .toString(),
                )
            )
                .parsedSafe<SearchResult>()

        if (
            searchReq?.data?.state != true
        ) {

            throw ErrorLoadingException(
                "Invalid Json response"
            )
        }

        searchReq
            .data
            .result
            ?.forEach { searchItem ->

                veriler.add(
                    searchItem
                        .toSearchResponse()
                        ?: return@forEach
                )
            }

        return veriler
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(url).document

        // -----------------------------------------------------
        // BAŞLIK
        // -----------------------------------------------------

        val title =
            document
                .selectFirst(
                    "div.page-top h1"
                )
                ?.text()
                ?: return null

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        val poster =
            fixUrlNull(
                document
                    .selectFirst(
                        "div.page-top img"
                    )
                    ?.attr("src")
            )
                ?: fixUrlNull(
                    document
                        .selectFirst(
                            "div.page-top img"
                        )
                        ?.attr("data-src")
                )

        // -----------------------------------------------------
        // YIL
        // -----------------------------------------------------

        val year =
            document
                .selectXpath(
                    "//span[text()='Yayın tarihi']//following-sibling::span"
                )
                .text()
                .trim()
                .split(" ")
                .lastOrNull()
                ?.toIntOrNull()

        // -----------------------------------------------------
        // AÇIKLAMA
        // -----------------------------------------------------

        val description =
            document
                .selectFirst(
                    "div.mv-det-p"
                )
                ?.text()
                ?.trim()
                ?: document
                    .selectFirst(
                        "div.w-full div.text-base"
                    )
                    ?.text()
                    ?.trim()

        // -----------------------------------------------------
        // ETİKETLER
        // -----------------------------------------------------

        val tags =
            document
                .select(
                    "[href*='dizi-turu']"
                )
                .map {
                    it.text()
                }

        // -----------------------------------------------------
        // IMDb PUANI
        // -----------------------------------------------------
        //
        // Eski:
        //
        // .toRatingInt()
        //
        // yerine güncel Score API:
        //
        // Score.from10()
        //
        // IMDb değerleri zaten 10 üzerinden geldiğinden
        // doğrudan from10 kullanıyoruz.
        // -----------------------------------------------------

        val rating =
            document
                .selectFirst(
                    "a[href*='imdb.com'] span"
                )
                ?.text()
                ?.trim()
                ?.replace(
                    ",",
                    "."
                )
                ?.toDoubleOrNull()

        // -----------------------------------------------------
        // SÜRE
        // -----------------------------------------------------

        val duration =
            document
                .select(
                    "div.gap-3 span.text-sm"
                )
                .getOrNull(1)
                ?.text()
                ?.let {
                    Regex(
                        "(\\d+)"
                    )
                        .find(it)
                        ?.value
                        ?.toIntOrNull()
                }

        // -----------------------------------------------------
        // OYUNCULAR
        // -----------------------------------------------------

        val actors =
            document
                .select(
                    "[href*='oyuncu']"
                )
                .map {
                    Actor(
                        it.text()
                    )
                }

        // -----------------------------------------------------
        // BÖLÜMLER
        // -----------------------------------------------------

        val episodeList =
            mutableListOf<Episode>()

        document
            .selectXpath(
                "//div[contains(@class, 'gap-2')]/a[contains(@href, '-sezon')]"
            )
            .forEach { seasonElement ->

                val seasonHref =
                    fixUrlNull(
                        seasonElement.attr(
                            "href"
                        )
                    )
                        ?: return@forEach

                val epDoc =
                    app.get(
                        seasonHref
                    ).document

                // =================================================
                // ALTYAZILI
                // =================================================

                epDoc
                    .select(
                        "div.episodes div.cursor-pointer"
                    )
                    .forEach ep@ { episodeElement ->

                        val epName =
                            episodeElement
                                .select("a")
                                .lastOrNull()
                                ?.text()
                                ?.trim()
                                ?: return@ep

                        val epHref =
                            fixUrlNull(
                                episodeElement
                                    .selectFirst(
                                        "a.opacity-60"
                                    )
                                    ?.attr("href")
                            )
                                ?: return@ep

                        val epDescription =
                            episodeElement
                                .selectFirst(
                                    "span.t-content"
                                )
                                ?.text()
                                ?.trim()

                        val epPoster =
                            fixUrlNull(
                                epDoc
                                    .selectFirst(
                                        "img.object-cover"
                                    )
                                    ?.attr("src")
                            )

                        val epEpisode =
                            episodeElement
                                .selectFirst(
                                    "a.opacity-60"
                                )
                                ?.text()
                                ?.toIntOrNull()

                        val parentDiv =
                            episodeElement.parent()

                        val seasonClass =
                            parentDiv
                                ?.className()
                                ?.split(" ")
                                ?.find {
                                    it.startsWith(
                                        "szn"
                                    )
                                }

                        val epSeason =
                            seasonClass
                                ?.substringAfter(
                                    "szn"
                                )
                                ?.toIntOrNull()

                        episodeList.add(
                            newEpisode(
                                epHref
                            ) {

                                this.name =
                                    epName

                                this.season =
                                    epSeason

                                this.episode =
                                    epEpisode

                                this.description =
                                    epDescription

                                this.posterUrl =
                                    epPoster
                            }
                        )
                    }

                // =================================================
                // DUBLAJ
                // =================================================

                epDoc
                    .select(
                        "div.dub-episodes div.cursor-pointer"
                    )
                    .forEach epDub@ { dubEpisodeElement ->

                        val epName =
                            dubEpisodeElement
                                .select("a")
                                .lastOrNull()
                                ?.text()
                                ?.trim()
                                ?: return@epDub

                        val epHref =
                            fixUrlNull(
                                dubEpisodeElement
                                    .selectFirst(
                                        "a.opacity-60"
                                    )
                                    ?.attr("href")
                            )
                                ?: return@epDub

                        val epDescription =
                            dubEpisodeElement
                                .selectFirst(
                                    "span.t-content"
                                )
                                ?.text()
                                ?.trim()

                        val epPoster =
                            fixUrlNull(
                                epDoc
                                    .selectFirst(
                                        "img.object-cover"
                                    )
                                    ?.attr("src")
                            )

                        val epEpisode =
                            dubEpisodeElement
                                .selectFirst(
                                    "a.opacity-60"
                                )
                                ?.text()
                                ?.toIntOrNull()

                        val parentDiv =
                            dubEpisodeElement.parent()

                        val seasonClass =
                            parentDiv
                                ?.className()
                                ?.split(" ")
                                ?.find {
                                    it.startsWith(
                                        "szn"
                                    )
                                }

                        val epSeason =
                            seasonClass
                                ?.substringAfter(
                                    "szn"
                                )
                                ?.toIntOrNull()

                        episodeList.add(
                            newEpisode(
                                epHref
                            ) {

                                this.name =
                                    "$epName Dublaj"

                                this.season =
                                    epSeason

                                this.episode =
                                    epEpisode

                                this.description =
                                    epDescription

                                this.posterUrl =
                                    epPoster
                            }
                        )
                    }
            }

        // =====================================================
        // RESPONSE
        // =====================================================

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {

            // -------------------------------------------------
            // DİZİ POSTERİ
            // -------------------------------------------------

            this.posterUrl =
                poster

            // -------------------------------------------------
            // YIL
            // -------------------------------------------------

            this.year =
                year

            // -------------------------------------------------
            // AÇIKLAMA
            // -------------------------------------------------

            this.plot =
                description

            // -------------------------------------------------
            // ETİKETLER
            // -------------------------------------------------

            this.tags =
                tags

            // -------------------------------------------------
            // PUAN
            // -------------------------------------------------

            rating?.let {
                this.score =
                    Score.from10(it)
            }

            // -------------------------------------------------
            // SÜRE
            // -------------------------------------------------

            this.duration =
                duration

            // -------------------------------------------------
            // OYUNCULAR
            // -------------------------------------------------

            addActors(
                actors
            )
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit,
    ): Boolean {

        Log.d(
            "DZL",
            "data » $data"
        )

        val document =
            app.get(
                data
            ).document

        val iframes =
            mutableSetOf<String>()

        // -----------------------------------------------------
        // ALTERNATİF PLAYERLAR
        // -----------------------------------------------------

        val alternatifler =
            document.select(
                "a[href*='player']"
            )

        // -----------------------------------------------------
        // ALTERNATİF YOK
        // -----------------------------------------------------

        if (
            alternatifler.isEmpty()
        ) {

            val iframe =
                fixUrlNull(
                    document
                        .selectFirst(
                            "div#playerLsDizilla iframe"
                        )
                        ?.attr("src")
                )
                    ?: return false

            Log.d(
                "DZL",
                "iframe » $iframe"
            )

            loadExtractor(
                iframe,
                "${mainUrl}/",
                subtitleCallback,
                callback
            )

        } else {

            // -------------------------------------------------
            // ALTERNATİFLER
            // -------------------------------------------------

            alternatifler.forEach { alternative ->

                val playerUrl =
                    fixUrlNull(
                        alternative.attr(
                            "href"
                        )
                    )
                        ?: return@forEach

                val playerDoc =
                    app.get(
                        playerUrl
                    ).document

                val iframe =
                    fixUrlNull(
                        playerDoc
                            .selectFirst(
                                "div#playerLsDizilla iframe"
                            )
                            ?.attr("src")
                    )
                        ?: return@forEach

                // ---------------------------------------------
                // Duplicate iframe engelle
                // ---------------------------------------------

                if (
                    iframe in iframes
                ) {
                    return@forEach
                }

                iframes.add(
                    iframe
                )

                Log.d(
                    "DZL",
                    "iframe » $iframe"
                )

                loadExtractor(
                    iframe,
                    "${mainUrl}/",
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}
