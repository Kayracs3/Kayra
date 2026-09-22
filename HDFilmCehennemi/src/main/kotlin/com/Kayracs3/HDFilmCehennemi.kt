package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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
        "$mainUrl/tur/belgesel-filmlerini-izle-2" to "Belgesel",
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
        
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document
                .selectFirst("h4.title")?.text() 
                ?: return@forEach
            val href = fixUrlNull(
                document.selectFirst("a")?.attr("href")
            ) ?: return@forEach
            
            val imgEl = document.selectFirst("img")
            val posterUrl = fixUrlNull(imgEl?.attr("src")) 
                ?: fixUrlNull(imgEl?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { 
                    this.posterUrl = posterUrl
                        ?.replace("/thumb/", "/list/") 
                }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url, 
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val title = document
            .selectFirst("h1.section-title")?.text()
            ?.substringBefore(" izle") ?: return null
            
        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload")
                .lastOrNull()?.attr("data-src")
        )
        val tags = document
            .select("div.post-info-genres a")
            .map { it.text() }
        val year = document
            .selectFirst("div.post-info-year-country a")
            ?.text()?.trim()?.toIntOrNull()
            
        val tvType = if (document.select("div.seasons").isEmpty()) 
            TvType.Movie else TvType.TvSeries
            
        val description = document
            .selectFirst("article.post-info-content > p")
            ?.text()?.trim()
        
        val actors = document
            .select("div.post-info-cast a")
            .mapNotNull {
                val actorName = it.selectFirst("strong")?.text() 
                    ?: return@mapNotNull null
                val actorImg = it.selectFirst("img")?.attr("data-src")
                Actor(actorName, actorImg)
            }

        val recommendations = document
            .select("div.section-slider-container div.slider-slide")
            .mapNotNull {
                val recName = it.selectFirst("a")?.attr("title") 
                    ?: return@mapNotNull null
                val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) 
                    ?: return@mapNotNull null
                val img = it.selectFirst("img")
                val recPosterUrl = fixUrlNull(img?.attr("data-src")) 
                    ?: fixUrlNull(img?.attr("src"))

                newTvSeriesSearchResponse(
                    recName, 
                    recHref, 
                    TvType.TvSeries
                ) {
                    this.posterUrl = recPosterUrl
                }
            }

        val trailerBtn = document
            .selectFirst("div.post-info-trailer button")
        val trailerUrl = trailerBtn?.attr("data-modal")
            ?.substringAfter("trailer/")
            ?.let { "https://youtube.com" }

        return if (tvType == TvType.TvSeries) {
            val episodes = document
                .select("div.seasons-tab-content a")
                .mapNotNull {
                    val epName = it.selectFirst("h4")?.text()?.trim() 
                        ?: return@mapNotNull null
                    val epHref = fixUrlNull(it.attr("href")) 
                        ?: return@mapNotNull null
                    val epEpisode = Regex("""(\d+)\. ?Bölüm""")
                        .find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    val epSeason = Regex("""(\d+)\. ?Sezon""")
                        .find(epName)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: 1

                    newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                }

            newTvSeriesLoadResponse(
                title, 
                url, 
                TvType.TvSeries, 
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
                this.trailer = trailerUrl
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
                this.trailer = trailerUrl
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
        
        val selectors = "iframe, div[data-frame], [data-embed], " +
                "nav.player-tabs a, div.player-tab-sources button"
                
        val playerElements = document.select(selectors)
        
        playerElements.forEach { element ->
            var targetUrl = element.attr("src").ifEmpty { 
                element.attr("data-src").ifEmpty { 
                    element.attr("data-frame").ifEmpty { 
                        element.attr("data-embed").ifEmpty { 
                            element.attr("href") ?: "" 
                        } 
                    } 
                } 
            }
            
            if (targetUrl.isNotEmpty()) {
                targetUrl = fixUrl(targetUrl)
                
                if (targetUrl.contains("hdfilmcehennemi") || 
                    targetUrl.contains("moly") || 
                    targetUrl.contains("cdnimages")
                ) {
                    fetchLocalStream(targetUrl, callback)
                } else {
                    loadExtractor(
                        targetUrl, 
                        data, 
                        subtitleCallback, 
                        callback
                    )
                }
            }
        }
        return true
    }

    private suspend fun fetchLocalStream(
        playerUrl: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
val headersMap = mapOf("User-Agent" to userAgent)val response = app.get(playerUrl,referer = "$mainUrl/",headers = headersMap).textval pattern = """["']?(https?://[^"']+""" +"""(?:cdnimages|shop)[^"']+""" +"""(?:master.txt|master.m3u8)""" +"""[^"']*)["']"""val m3u8Regex = Regex(pattern)val match = m3u8Regex.find(response)if (match != null) {val finalVideoUrl = match.groupValues[1]callback.invoke(newExtractorLink(source = "HDFilmCehennemi (CDN)",name = "HQ Kalite (Yerel)",url = finalVideoUrl) {this.referer = playerUrlthis.quality = Qualities.P1080.valuethis.isM3u8 = true})}} catch (e: Exception) {Log.e("HDFilmCehennemi", "Hata: ${e.message}")}}}
