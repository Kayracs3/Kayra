package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val JET_TAG = "JetFilmizle"
private const val JET_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val pageHeaders = mapOf(
        "User-Agent" to JET_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler/sayfa-" to "En Yeni Filmler",
        "$mainUrl/diziler/sayfa-" to "En Yeni Diziler",
        "$mainUrl/filmler/en-cok-izlenenler/sayfa-" to "En Çok İzlenen Filmler",
        "$mainUrl/diziler/siralama-en-cok-izlenen/sayfa-" to "En Çok İzlenen Diziler",
        "$mainUrl/saglayici/netflix?sayfa=" to "Netflix",
        "$mainUrl/yerli-filmler/sayfa-" to "Yerli Filmler",
        "$mainUrl/tur/aile/film/sayfa-" to "Aile",
        "$mainUrl/tur/aksiyon/film/sayfa-" to "Aksiyon",
        "$mainUrl/tur/animasyon/film/sayfa-" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu/film/sayfa-" to "Bilim Kurgu",
        "$mainUrl/tur/dram/film/sayfa-" to "Dram",
        "$mainUrl/tur/fantastik/film/sayfa-" to "Fantastik",
        "$mainUrl/tur/gerilim/film/sayfa-" to "Gerilim",
        "$mainUrl/tur/gizem/film/sayfa-" to "Gizem",
        "$mainUrl/tur/komedi/film/sayfa-" to "Komedi",
        "$mainUrl/tur/korku/film/sayfa-" to "Korku",
        "$mainUrl/tur/macera/film/sayfa-" to "Macera",
        "$mainUrl/tur/romantik/film/sayfa-" to "Romantik",
        "$mainUrl/tur/suc/film/sayfa-" to "Suç",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = "${request.data}$page"

        Log.d(JET_TAG, "getMainPage: $url")

        val document = runCatching {
            app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false,
        )

        val items = document.select(
            ".row-cols-2 .col .film-card, .film-card"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (base.endsWith("/")) {
            return base.trimEnd('/') + "/page/$page/"
        }
        return "$base/page/$page/"
    }

    private fun detectNextPage(document: org.jsoup.nodes.Document, page: Int): Boolean {
        if (document.select("a.next, a[rel=next], .pagination a").any {
                it.text().contains("Son", true) || it.text().contains("»")
            }) {
            return true
        }
        return page < 50 && document.select("article.movie").isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(
            ".film-title, .card-title, h3 a, h3, h2 a, h2"
        )?.text()?.trim()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val href = fixUrlNull(
            selectFirst(".card-body a[href], a[href]")?.attr("href")
        ) ?: return null

        val poster = sequenceOf(
            selectFirst(".film-poster img")?.attr("data-src"),
            selectFirst(".film-poster img")?.attr("data-lazy-src"),
            selectFirst(".film-poster img")?.attr("src"),
            selectFirst("img")?.attr("data-src"),
            selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val score = selectFirst(
            ".rating-year-imdb .text-warning, .rating, .imdb"
        )?.text()?.trim()

        return newMovieSearchResponse(
            title,
            href,
            if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie,
        ) {
            posterUrl = poster
            score?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        // Önce sitenin JSON arama ucunu kullan; boş veya farklı bir cevap dönerse
        // geleneksel HTML arama sayfalarını da dene.
        val apiUrl = "$mainUrl/arama-json?q=$encoded"
        val apiText = runCatching {
            app.get(apiUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).text
        }.onFailure {
            Log.w(JET_TAG, "JSON search request failed", it)
        }.getOrNull()

        if (!apiText.isNullOrBlank()) {
            val body = Regex("""(?is)<body[^>]*>(.*?)</body>""")
                .find(apiText)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { apiText.trim() }
            val apiResults = runCatching {
                val json = org.json.JSONObject(body)
                val results = json.optJSONArray("results") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val rawUrl = item.optString("url").trim()
                        if (title.isBlank() || rawUrl.isBlank()) continue
                        val fullUrl = fixUrlNull(
                            if (rawUrl.startsWith("http", true)) rawUrl else "$mainUrl/${rawUrl.trimStart('/')}"
                        ) ?: continue
                        val poster = fixUrlNull(item.optString("poster").trim())
                        val year = item.optString("year").toIntOrNull()
                        val rating = item.optString("rating")
                        val type = if (item.optString("type").equals("dizi", true)) TvType.TvSeries else TvType.Movie
                        add(newMovieSearchResponse(title, fullUrl, type) {
                            posterUrl = poster
                            this.year = year
                            this.score = Score.from10(rating)
                        })
                    }
                }.distinctBy { it.url }
            }.getOrElse {
                Log.d(JET_TAG, "JSON search parse failed: ${it.message}")
                emptyList()
            }
            if (apiResults.isNotEmpty()) return apiResults
        }

        val fallbackUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/arama/$encoded",
            "$mainUrl/search?q=$encoded",
        ).distinct()
        for (url in fallbackUrls) {
            val document = runCatching {
                app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.onFailure {
                Log.w(JET_TAG, "HTML search request failed: $url", it)
            }.getOrNull() ?: continue

            val results = document.select(
                "article.movie, .movie-card, .movie-item, .film-card, .film-item, .card.movie, a[href*='/film/'], a[href*='/dizi/']"
            ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            Log.d(JET_TAG, "HTML search $url -> ${results.size} results")
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: return null

        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = mainUrl + "/", allowRedirects = true).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst(".col-12 .film-title")?.text(),
            document.selectFirst(".film-title")?.text(),
            document.selectFirst("h1")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.substringBefore(" izle")?.trim() ?: return null

        val poster = sequenceOf(
            document.selectFirst(".film-bilgileri-section img")?.attr("data-src"),
            document.selectFirst(".film-bilgileri-section img")?.attr("src"),
            document.selectFirst(".film-poster img")?.attr("data-src"),
            document.selectFirst(".film-poster img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val infoText = document.selectFirst(".film-bilgileri-section, .film-info, .detail-item")?.text().orEmpty()
        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""").find(infoText)?.value?.toIntOrNull()
        val rating = document.selectFirst(".film-ratings-container b, .rating-year-imdb .text-warning, .rating, .imdb")?.text()?.let { value ->
            Regex("""\d+(?:[\.,]\d+)?""").find(value)?.value?.replace(",", ".")
        }
        val description = sequenceOf(
            document.selectFirst(".description-text p:nth-child(2)")?.text(),
            document.selectFirst(".description-text p:nth-child(1)")?.text(),
            document.selectFirst(".description, .plot")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select(".categories-container-details a, .categories-container a, .catss a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select(".oyuncular-section .actors-grid .col, .oyuncu").mapNotNull { actorElement ->
            val actorName = sequenceOf(
                actorElement.selectFirst(".text-decoration-none")?.text(),
                actorElement.selectFirst(".name")?.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return@mapNotNull null
            val actorImage = sequenceOf(
                actorElement.selectFirst("img")?.attr("data-src"),
                actorElement.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)
            Actor(actorName, actorImage)
        }.distinctBy { it.name }

        if (pageUrl.contains("/dizi/", true)) {
            val episodes = document.select("button.episode-btn[data-player-type], button.episode-btn")
                .mapNotNull { button ->
                    val season = button.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                    val episode = button.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
                    val sourceIndex = button.attr("data-source-index")
                    val playerType = button.attr("data-player-type")
                    if (sourceIndex.isBlank() || playerType.isBlank()) return@mapNotNull null

                    val episodeUrl = pageUrl + "?sezon=" + season + "&bolum=" + episode + "&index=" + sourceIndex + "&type=" + playerType

                    newEpisode(episodeUrl) {
                        name = season.toString() + ".Sezon " + episode + ".Bölüm"
                        this.season = season
                        this.episode = episode
                        posterUrl = poster
                    }
                }
                .distinctBy { it.season.toString() + "-" + it.episode.toString() + "-" + it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            Log.d(JET_TAG, "Dizi bölümleri: " + episodes.size)

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(JET_TAG, "loadLinks: " + data)

        val episodeMode = data.contains("sezon=") &&
            data.contains("bolum=") &&
            data.contains("index=") &&
            data.contains("type=")

        var document = runCatching {
            app.get(
                data,
                headers = pageHeaders + mapOf("Referer" to "$mainUrl/"),
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return false

        var filmId = document.selectFirst(
            "input[name=film_id], input[name='film_id'], input[name=filmId]"
        )?.attr("value")

        // Bölüm URL'si seçili bölüme ait farklı bir HTML döndürüyorsa,
        // film_id'yi ana dizi sayfasından tekrar al.
        if (filmId.isNullOrBlank() && episodeMode) {
            val baseUrl = data.substringBefore("?")

            document = runCatching {
                app.get(
                    baseUrl,
                    headers = pageHeaders + mapOf("Referer" to "$mainUrl/"),
                    allowRedirects = true,
                ).document
            }.getOrNull() ?: return false

            filmId = document.selectFirst(
                "input[name=film_id], input[name='film_id'], input[name=filmId]"
            )?.attr("value")
        }

        if (filmId.isNullOrBlank()) {
            Log.e(JET_TAG, "film_id bulunamadı: " + data)
            return false
        }

        Log.d(JET_TAG, "film_id: " + filmId)

        suspend fun requestPlayer(
            sourceIndex: Int,
            playerType: String,
            label: String,
        ): Boolean {
            if (sourceIndex < 0 || playerType.isBlank()) return false

            return try {
                Log.d(
                    JET_TAG,
                    "jetplayer POST: film_id=" + filmId +
                        " source=" + sourceIndex +
                        " type=" + playerType,
                )

                val responseText = app.post(
                    "$mainUrl/jetplayer",
                    headers = mapOf(
                        "User-Agent" to JET_UA,
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    ),
                    data = mapOf(
                        "film_id" to filmId,
                        "source_index" to sourceIndex.toString(),
                        "player_type" to playerType,
                    ),
                    allowRedirects = true,
                ).text

                if (responseText.isBlank()) {
                    Log.d(JET_TAG, "boş jetplayer cevabı: " + label)
                    return false
                }

                val responseDoc = org.jsoup.Jsoup.parse(responseText)

                val iframeSrc = sequenceOf(
                    responseDoc.selectFirst("iframe")?.attr("src"),
                    responseDoc.selectFirst("iframe")?.attr("data-src"),
                    responseDoc.selectFirst("video")?.attr("src"),
                    responseDoc.selectFirst("video source")?.attr("src"),
                    responseDoc.selectFirst("source")?.attr("src"),
                ).firstOrNull { !it.isNullOrBlank() }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "about:blank" }

                if (iframeSrc.isNullOrBlank()) {
                    Log.d(
                        JET_TAG,
                        "iframe bulunamadı: " + label +
                            " -> " + responseText.take(180).replace("\n", " "),
                    )
                    return false
                }

                val playerUrl = when {
                    iframeSrc.startsWith("//") -> "https:" + iframeSrc
                    iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                    else -> fixUrlNull(iframeSrc) ?: iframeSrc
                }

                Log.d(JET_TAG, "player bulundu [" + label + "]: " + playerUrl)

                var emitted = false

                if (
                    playerUrl.contains("pixeldrain.com", true) ||
                    playerUrl.contains("pixeldrain.net", true)
                ) {
                    val pixelId = playerUrl.substringAfterLast("/")
                        .substringBefore("?")
                        .trim()

                    if (pixelId.isNotBlank()) {
                        val directUrl =
                            "https://pixeldrain.com/api/file/" + pixelId + "?download"

                        callback(
                            newExtractorLink(
                                source = "PixelDrain",
                                name = label + " - PixelDrain",
                                url = directUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                referer = playerUrl
                                quality = Qualities.Unknown.value
                            }
                        )

                        emitted = true
                    }
                } else {
                    loadExtractor(
                        playerUrl,
                        data,
                        subtitleCallback,
                        { link ->
                            emitted = true
                            callback(link)
                        },
                    )
                }

                Log.d(
                    JET_TAG,
                    "kaynak sonucu [" + label + "]: emitted=" + emitted,
                )

                emitted
            } catch (e: Exception) {
                Log.e(
                    JET_TAG,
                    "jetplayer hata [" + label + "]: " + e.message,
                )
                false
            }
        }

        if (episodeMode) {
            val season = data.substringAfter("sezon=")
                .substringBefore("&")
                .toIntOrNull()

            val episode = data.substringAfter("bolum=")
                .substringBefore("&")
                .toIntOrNull()

            val selectedIndex = data.substringAfter("index=")
                .substringBefore("&")
                .toIntOrNull()

            val selectedType = data.substringAfter("type=")
                .substringBefore("&")
                .trim()
                .ifBlank { "dublaj" }

            if (season == null || episode == null) return false

            // Önce CloudStream'in seçtiği kaynak.
            if (selectedIndex != null) {
                if (requestPlayer(
                        selectedIndex,
                        selectedType,
                        "S" + season + "E" + episode,
                    )
                ) return true
            }

            // Aynı bölüm için sitedeki tüm kaynak kombinasyonlarını dene.
            val tried = mutableSetOf<String>()

            val matchingButtons = document.select(
                "button.episode-btn[data-season][data-episode][data-source-index][data-player-type]"
            ).filter { button ->
                button.attr("data-season").toIntOrNull() == season &&
                    button.attr("data-episode").toIntOrNull() == episode
            }

            for (button in matchingButtons) {
                val index = button.attr("data-source-index").toIntOrNull() ?: continue
                val type = button.attr("data-player-type").trim()
                if (type.isBlank()) continue

                val key = "$index|$type"
                if (!tried.add(key)) continue

                if (requestPlayer(
                        index,
                        type,
                        "S" + season + "E" + episode + " fallback",
                    )
                ) return true
            }

            // Sayfadaki butonlar yoksa index üzerinde tipleri sırayla dene.
            if (tried.isEmpty() && selectedIndex != null) {
                for (type in listOf(selectedType, "dublaj", "altyazili").distinct()) {
                    if (requestPlayer(
                            selectedIndex,
                            type,
                            "S" + season + "E" + episode + " type-fallback",
                        )
                    ) return true
                }
            }

            return false
        }

        // Film: doğrudan sayfadaki kaynakları önce dene.
        val sourceButtons = document.select(
            "button.player-source-btn[data-source-index]"
        )

        if (sourceButtons.isNotEmpty()) {
            val tried = mutableSetOf<String>()

            for (button in sourceButtons) {
                val index = button.attr("data-source-index").toIntOrNull() ?: continue
                val type = button.attr("data-player-type").trim()
                    .ifBlank { "dublaj" }
                val label = button.text().trim().ifBlank { "Kaynak" }

                val key = "$index|$type"
                if (!tried.add(key)) continue

                if (requestPlayer(index, type, label)) return true
            }
        }

        // Eski/alternatif HTML yapısı.
        document.select(
            "#movie iframe[src], #movie iframe[data-src], iframe[src], iframe[data-src]"
        ).forEach { iframe ->
            val raw = iframe.attr("data-src")
                .ifBlank { iframe.attr("src") }

            val playerUrl = fixUrlNull(raw) ?: return@forEach

            runCatching {
                var emitted = false
                loadExtractor(
                    playerUrl,
                    data,
                    subtitleCallback,
                    { link ->
                        emitted = true
                        callback(link)
                    },
                )
                if (emitted) {
                    return true
                }
            }.onFailure {
                Log.e(JET_TAG, "fallback extractor hata: " + it.message)
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src")
                .ifBlank { track.attr("data-src") }

            val subUrl = fixUrlNull(raw) ?: return@forEach

            runCatching {
                subtitleCallback(
                    SubtitleFile("Türkçe", subUrl)
                )
            }
        }

        return false
    }

}