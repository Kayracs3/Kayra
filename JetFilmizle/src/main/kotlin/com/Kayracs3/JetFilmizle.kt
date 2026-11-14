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
private const val JET_REQUEST_TIMEOUT_MS = 15_000L
private const val JET_PLAYER_TIMEOUT_MS = 12_000L
private const val JET_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class JetFilmizle : NeonMainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
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
                timeout = JET_REQUEST_TIMEOUT_MS,
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
        val response = runCatching {
            app.get(
                "$mainUrl/arama-json?q=$encoded",
                headers = pageHeaders,
                referer = "$mainUrl/",
                timeout = JET_REQUEST_TIMEOUT_MS,
            ).text
        }.getOrNull() ?: return emptyList()

        return try {
            val body = response.substringAfter("<body>", response).substringBefore("</body>", response)
            val json = org.json.JSONObject(body)
            val results = json.optJSONArray("results") ?: return emptyList()

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val rawUrl = item.optString("url").trim()
                    if (title.isBlank() || rawUrl.isBlank()) continue

                    val fullUrl = fixUrlNull(
                        if (rawUrl.startsWith("http")) rawUrl else "$mainUrl/$rawUrl"
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
                .let { neonRelevantSearchResults(it, q) }
        } catch (e: Exception) {
            Log.e(JET_TAG, "search hata: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: return null

        val document = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders,
                referer = mainUrl + "/",
                timeout = JET_REQUEST_TIMEOUT_MS,
                allowRedirects = true,
            ).document
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
            // Her bölüm tek kart olsun. Kaynaklar aynı bölümün oynatma
            // penceresinde ayrı ayrı gösterilsin; kaynak başına bölüm çoğaltma.
            val episodes = document.select("button.episode-btn[data-season][data-episode]")
                .mapNotNull { button ->
                    val season = button.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                    val episode = button.attr("data-episode").toIntOrNull() ?: return@mapNotNull null

                    val episodeUrl = pageUrl + "?sezon=" + season + "&bolum=" + episode

                    newEpisode(episodeUrl) {
                        name = season.toString() + ".Sezon " + episode + ".Bölüm"
                        this.season = season
                        this.episode = episode
                        posterUrl = poster
                    }
                }
                .distinctBy { it.season.toString() + "-" + it.episode.toString() }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            Log.d(JET_TAG, "Dizi bölümleri: " + episodes.size)

            return neonEnrichResponse(
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {

                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            
            },
            document = document,
            baseUrl = pageUrl,
        )
        }

        return neonEnrichResponse(
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {

            posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
        
            },
            document = document,
            baseUrl = pageUrl,        )
    }    override suspend fun loadLinks(        data: String,        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(JET_TAG, "loadLinks: " + data)

        // Farklı player'ların aynı dil altyazısını tekrar tekrar eklemesini önle.
        // Bir içerik için dil başına tek bir temiz altyazı seçeneği göster.
        fun normalizeSubtitleLabel(raw: String): String {
            val value = raw.trim()
            val key = value.lowercase().replace('_', '-').substringBefore(' ')
            return when {
                key in setOf("tr", "tr-tr", "tur", "turkish", "türkçe", "turkce") ||
                    value.contains("türk", true) || value.contains("turkish", true) -> "Türkçe"
                key in setOf("en", "en-us", "en-gb", "eng", "english") ||
                    value.contains("english", true) -> "English"
                key in setOf("de", "de-de", "ger", "deu", "german") -> "Deutsch"
                key in setOf("fr", "fr-fr", "fre", "fra", "french") -> "Français"
                key in setOf("es", "es-es", "spa", "spanish") -> "Español"
                key in setOf("pt", "pt-br", "por", "portuguese") -> "Português"
                key in setOf("ar", "ara", "arabic") -> "العربية"
                key in setOf("ru", "rus", "russian") -> "Русский"
                else -> value
                    .replace(Regex("""\s*[\(\[].*?[\)\]]\s*"""), "")
                    .trim()
                    .ifBlank { "Altyazı" }
            }
        }

        // Bir içerikte aynı dili ve aynı altyazı URL'sini birden fazla kez gösterme.
        val sentSubtitleLanguages = mutableSetOf<String>()
        val sentSubtitleUrls = mutableSetOf<String>()
        val uniqueSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            val subtitleLabel = normalizeSubtitleLabel(subtitle.lang)
            val languageKey = subtitleLabel.lowercase()
            val urlKey = subtitle.url.trim()
            if (
                languageKey.isNotBlank() &&
                urlKey.isNotBlank() &&
                languageKey !in sentSubtitleLanguages &&
                sentSubtitleUrls.add(urlKey)
            ) {
                sentSubtitleLanguages.add(languageKey)
                subtitleCallback(SubtitleFile(subtitleLabel, urlKey))
            }
        }

        // Aynı medya URL'sini farklı source düğmeleri döndürürse listede tek kez göster.
        val sentMediaUrls = mutableSetOf<String>()
        val uniqueLinkCallback: (ExtractorLink) -> Unit = { link ->
            val mediaKey = link.url.trim()
            if (mediaKey.isNotBlank() && sentMediaUrls.add(mediaKey)) {
                callback.invoke(link)
            }
        }

        // Tek bölüm URL'si artık kaynak indeksini taşımaz; bütün
        // oynatma kaynakları aşağıda aynı bölüm için toplanır.
        val episodeMode = data.contains("sezon=") && data.contains("bolum=")
        val attemptedPlayerUrls = mutableSetOf<String>()

        var pageResponse = runCatching {
            app.get(
                data,
                headers = pageHeaders + mapOf("Referer" to "$mainUrl/"),
                timeout = JET_REQUEST_TIMEOUT_MS,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false
        var document = pageResponse.document
        // Keep the session cookies from the film page: /jetplayer is an AJAX
        // endpoint and may return an empty player fragment without this session.
        var pageCookies = pageResponse.cookies

        var filmId = document.selectFirst(
            "input[name=film_id], input[name='film_id'], input[name=filmId]"
        )?.attr("value")

        // Bölüm URL'si seçili bölüme ait farklı bir HTML döndürüyorsa,
        // film_id'yi ana dizi sayfasından tekrar al.
        if (filmId.isNullOrBlank() && episodeMode) {
            val baseUrl = data.substringBefore("?")

            pageResponse = runCatching {
                app.get(
                    baseUrl,
                    headers = pageHeaders + mapOf("Referer" to "$mainUrl/"),
                    timeout = JET_REQUEST_TIMEOUT_MS,
                    allowRedirects = true,
                )
            }.getOrNull() ?: return false
            document = pageResponse.document
            pageCookies = pageResponse.cookies

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

                val playerHeaders = mutableMapOf(
                    "User-Agent" to JET_UA,
                    "Referer" to data,
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                )
                if (pageCookies.isNotEmpty()) {
                    playerHeaders["Cookie"] = pageCookies.entries.joinToString("; ") {
                        it.key + "=" + it.value
                    }
                }

                val responseText = app.post(
                    "$mainUrl/jetplayer",
                    headers = playerHeaders,
                    cookies = pageCookies,
                    data = mapOf(
                        "film_id" to filmId,
                        "source_index" to sourceIndex.toString(),
                        "player_type" to playerType,
                    ),
                    timeout = JET_REQUEST_TIMEOUT_MS,
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

                val hasVideoParkInitial = Regex(
                    """(?is)(?:const|let|var)\s+initial\s*="""
                ).containsMatchIn(responseText) &&
                    (responseText.contains("has_dub") ||
                        responseText.contains("vsrc.top", true) ||
                        responseText.contains("videopark.top", true))

                // Sadece player değişkenleri gerçekten HTML/JS içinde tanımlıysa
                // yanıtı inline say. Iframe src içindeki host adı tek başına yeterli değil.
                val hasInlineWorkerPlayer = Regex(
                    """(?is)(?:const|let|var)\s+(?:PUB_ID|WORKER_URL|WORKER_BASE)\s*="""
                ).containsMatchIn(responseText)
                val inlinePlayerHtml = responseText.takeIf {
                    iframeSrc.isNullOrBlank() &&
                        (hasInlineWorkerPlayer || hasVideoParkInitial)
                }

                if (iframeSrc.isNullOrBlank() && inlinePlayerHtml.isNullOrBlank()) {
                    Log.d(
                        JET_TAG,
                        "iframe/player HTML bulunamadı: " + label +
                            " -> " + responseText.take(180).replace("\n", " "),
                    )
                    return false
                }

                val playerUrl = if (!inlinePlayerHtml.isNullOrBlank() || iframeSrc.isNullOrBlank()) {
                    val embeddedBase = Regex("""(?is)<base\b[^>]*\bhref\s*=\s*["']([^"']+)["']""")
                        .find(responseText)?.groupValues?.get(1)
                    embeddedBase?.let { fixUrlNull(it) } ?: data.substringBefore("?")
                } else {
                    when {
                        iframeSrc.startsWith("//") -> "https:" + iframeSrc
                        iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                        else -> fixUrlNull(iframeSrc) ?: iframeSrc
                    }
                }

                Log.d(JET_TAG, "player bulundu [" + label + "]: " + playerUrl)
                if (!attemptedPlayerUrls.add(playerUrl)) {
                    Log.d(JET_TAG, "Aynı player URL daha önce denendi; yinelenen istek atlandı")
                    return false
                }

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

                        uniqueLinkCallback(
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
                    // Yeni JetFilmizle JWPlayer sayfası, medya URL'lerini
                    // dplayer Worker API'sinden JSON olarak alıyor.
                    val isVideoParkGoPlayer =
                        playerUrl.contains("videopark.top", true) &&
                            playerUrl.contains("/go/", true)

                    // VideoPark /go/ sayfası doğrudan açıldığında ana sayfaya yönlenebiliyor.
                    // Doğrulanmış Worker API'ye doğrudan gitmek fazladan HTML isteğini önler.
                    val playerPageText = inlinePlayerHtml ?: if (isVideoParkGoPlayer) {
                        ""
                    } else {
                        runCatching {
                            app.get(
                                playerUrl,
                                headers = mapOf(
                                    "User-Agent" to JET_UA,
                                    "Referer" to data,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                ),
                                timeout = JET_PLAYER_TIMEOUT_MS,
                                allowRedirects = true,
                            ).text
                        }.getOrDefault("")
                    }

                    if (playerPageText.isNotBlank()) {
                        org.jsoup.Jsoup.parse(playerPageText).select(
                            "track[src], track[data-src], track[data-subtitle]"
                        ).forEach { track ->
                            val trackUrl = sequenceOf(
                                track.attr("src"),
                                track.attr("data-src"),
                                track.attr("data-subtitle"),
                            ).firstOrNull { it.isNotBlank() }?.let(::fixUrlNull) ?: return@forEach
                            val trackLabel = sequenceOf(
                                track.attr("srclang"),
                                track.attr("lang"),
                                track.attr("label"),
                            ).firstOrNull { it.isNotBlank() } ?: "Türkçe"
                            uniqueSubtitleCallback(
                                SubtitleFile(normalizeSubtitleLabel(trackLabel), trackUrl)
                            )
                        }
                    }

                    val isGoDrivePlayer =
                        playerPageText.contains("WORKER_BASE") ||
                            playerPageText.contains("godrive.erikkalinina1994.workers.dev")

                    // VideoPark alan adı artık iki farklı oynatıcı için kullanılıyor:
                    // eski VideoPark initial/api.php ve yeni DPlayer Worker API.
                    val hasLegacyVideoParkConfig =
                        Regex("""(?is)(?:const|let|var)\s+initial\s*=""")
                            .containsMatchIn(playerPageText) &&
                            (playerPageText.contains("has_dub") ||
                                playerPageText.contains("vsrc.top", true) ||
                                playerPageText.contains("api.php?action=stream", true))

                    val isVideoParkHost = playerUrl.contains("videopark.top", true)
                    val isDPlayerWorkerPlayer =
                        isVideoParkGoPlayer ||
                            playerPageText.contains("dplayer.erikkalinina1994.workers.dev", true) ||
                            playerPageText.contains("api/stream?pubId", true) ||
                            Regex(
                                """(?is)(?:const|let|var)\s+(?:PUB_ID|pubId|PUBLISHER_ID|publisherId)\s*="""
                            ).containsMatchIn(playerPageText) ||
                            // Tarayıcıda doğrulanan /go/?id= isteği DPlayer Worker'a gidiyor.
                            // Harici player scripti HTML'de görünmese veya eski değişkenler
                            // yanında bulunsa bile bu URL'yi DPlayer API'sine yönlendir.
                            (isVideoParkHost &&
                                (playerUrl.contains("/go/", true) ||
                                    !hasLegacyVideoParkConfig))

                    val isVideoParkPlayer =
                        !isDPlayerWorkerPlayer &&
                            (playerPageText.contains("vsrc.top/you/p/", true) ||
                                hasLegacyVideoParkConfig ||
                                isVideoParkHost)

                    if (
                        isVideoParkPlayer ||
                        isDPlayerWorkerPlayer ||
                        playerPageText.contains("PUB_ID") ||
                        playerPageText.contains("WORKER_URL") ||
                        isGoDrivePlayer
                    ) {
                        try {
                            if (isVideoParkPlayer) {
                                fun extractInitialPayload(html: String): org.json.JSONObject? {
                                    val marker = Regex("""(?:const|let|var)\s+initial\s*=""")
                                        .find(html) ?: return null
                                    val start = html.indexOf('{', marker.range.last + 1)
                                    if (start < 0) return null

                                    var depth = 0
                                    var quoted = false
                                    var escaped = false
                                    var quote = '\u0000'

                                    for (index in start until html.length) {
                                        val char = html[index]
                                        if (quoted) {
                                            if (escaped) {
                                                escaped = false
                                            } else if (char == '\\') {
                                                escaped = true
                                            } else if (char == quote) {
                                                quoted = false
                                            }
                                        } else {
                                            when (char) {
                                                '"', '\'' -> {
                                                    quoted = true
                                                    quote = char
                                                }
                                                '{' -> depth++
                                                '}' -> {
                                                    depth--
                                                    if (depth == 0) {
                                                        return runCatching {
                                                            org.json.JSONObject(html.substring(start, index + 1))
                                                        }.getOrNull()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    return null
                                }
                                val initial = extractInitialPayload(playerPageText)
                                if (initial == null) {
                                    Log.w(JET_TAG, "VideoPark initial JSON bulunamadı: " + playerUrl)
                                } else {
                                    val baseHref = Regex("""(?is)<base\b[^>]*\bhref\s*=\s*["']([^"']+)["']""")
                                        .find(playerPageText)?.groupValues?.get(1)
                                    val baseUrl = baseHref?.takeIf { it.startsWith("http", true) }
                                        ?: playerUrl.substringBeforeLast('/') + "/"

                                    fun resolveVideoParkUrl(raw: String): String? {
                                        val value = raw.trim()
                                        if (value.isBlank()) return null
                                        return runCatching {                                            java.net.URI(baseUrl).resolve(value).toString()
                                        }.getOrNull()?.takeIf { it.startsWith("http", true) }
                                    }
                                    val initialLanguage = initial.optString("lang").trim().lowercase()
                                    val initialMode = if (initialLanguage == "dub") "dub" else "sub"
                                    val hasDub = initial.optBoolean("has_dub", initialMode == "dub")
                                    val streamPayloads = mutableListOf<Pair<String, org.json.JSONObject>>()

                                    val videoCode = initial.optString("code").trim()
                                    if (videoCode.isNotBlank()) {
                                        // initial.sources içindeki vsrc.top URL'leri imzalı ve
                                        // süreli olabilir. Her oynatmada API'den yenisini al.
                                        val modesToFetch = if (hasDub) {
                                            listOf("dub", "sub")
                                        } else {
                                            listOf("sub")
                                        }

                                        for (mode in modesToFetch) {
                                            val apiUrl = runCatching {
                                                java.net.URI(baseUrl).resolve(
                                                    "api.php?action=stream&v=" +
                                                        URLEncoder.encode(videoCode, "UTF-8") +
                                                        "&lang=" + mode
                                                ).toString()
                                            }.getOrDefault("")

                                            if (apiUrl.isBlank()) continue

                                            val freshPayload = runCatching {
                                                val apiText = app.get(
                                                    apiUrl,
                                                    headers = mapOf(
                                                        "User-Agent" to JET_UA,
                                                        "Referer" to playerUrl,
                                                        "Accept" to "application/json,text/plain,*/*",
                                                    ),
                                                    timeout = JET_PLAYER_TIMEOUT_MS,
                                                    allowRedirects = true,
                                                ).text
                                                org.json.JSONObject(apiText)
                                            }.getOrNull()

                                            if (freshPayload?.optJSONArray("sources")?.length()?.let { it > 0 } == true) {
                                                streamPayloads.add(mode to freshPayload)
                                                Log.d(JET_TAG, "VideoPark taze akış alındı: " + mode)
                                            } else {
                                                Log.w(JET_TAG, "VideoPark API akışı alınamadı: " + mode)
                                            }
                                        }
                                    }

                                    // API erişilemezse aynı player yanıtındaki URL son çare
                                    // olarak kullanılır; normal durumda imzalı eski adresler seçilmez.
                                    if (streamPayloads.isEmpty()) {
                                        val initialSources = initial.optJSONArray("sources")
                                        if (initialSources != null && initialSources.length() > 0) {
                                            streamPayloads.add(initialMode to initial)
                                            Log.w(JET_TAG, "VideoPark API başarısız; ilk HTML akışı yedek olarak kullanılıyor")
                                        }
                                    }

                                    val seenMediaUrls = mutableSetOf<String>()
                                    val seenSubtitleLanguages = mutableSetOf<String>()

                                    // Kaynak isimlerini kısa tut: dublaj ve altyazılı
                                    // akışlar ayrı, anlaşılır seçenekler olarak görünür.
                                    for ((streamMode, payload) in streamPayloads) {
                                        val modeLabel = if (streamMode == "dub") {
                                            "Dublaj"
                                        } else {
                                            "Altyazılı"
                                        }

                                        val sources = payload.optJSONArray("sources")
                                        if (sources != null) {
                                            for (index in 0 until sources.length()) {
                                                val sourceObject = sources.optJSONObject(index) ?: continue
                                                val rawFile = sourceObject.optString("file").trim()
                                                val mediaUrl = resolveVideoParkUrl(rawFile) ?: continue
                                                if (!seenMediaUrls.add(mediaUrl)) continue

                                                val format = sourceObject.optString("type").trim().lowercase()
                                                val isHls = format.contains("hls") ||
                                                    mediaUrl.substringBefore('?').endsWith(".m3u8", true)
                                                val linkType = if (isHls) {
                                                    ExtractorLinkType.M3U8
                                                } else {
                                                    ExtractorLinkType.VIDEO
                                                }
                                                val rawQuality = sourceObject.optString("label").trim()
                                                val qualityLabel = rawQuality
                                                    .takeIf { it.isNotBlank() }
                                                    ?: if (isHls) "Otomatik" else "Video"

                                                uniqueLinkCallback(
                                                    newExtractorLink(
                                                        source = name,
                                                        name = "VideoPark • " + modeLabel + " • " + qualityLabel,
                                                        url = mediaUrl,
                                                        type = linkType,
                                                    ) {
                                                        referer = playerUrl
                                                        quality = getQualityFromName(qualityLabel)
                                                    }
                                                )
                                                emitted = true
                                            }
                                        }
                                    }

                                    // Altyazı listesine yalnızca altyazılı akışın
                                    // parçalarını ekle. Dublaj JSON'undaki kopyalar
                                    // CloudStream menüsünü gereksiz yere çoğaltmasın.
                                    val subtitlePayload = streamPayloads.firstOrNull { (mode, payload) ->
                                        mode == "sub" && (payload.optJSONArray("tracks")?.length() ?: 0) > 0
                                    }?.second ?: streamPayloads.firstOrNull { (_, payload) ->
                                        (payload.optJSONArray("tracks")?.length() ?: 0) > 0
                                    }?.second

                                    val tracks = subtitlePayload?.optJSONArray("tracks")
                                    if (tracks != null) {
                                        for (index in 0 until tracks.length()) {
                                            val track = tracks.optJSONObject(index) ?: continue
                                            val rawTrack = track.optString("file").trim()
                                            val trackUrl = resolveVideoParkUrl(rawTrack) ?: continue

                                            val rawLang = track.optString("lang").trim().lowercase()
                                            val rawLabel = track.optString("label").trim()
                                            val trackLabel = normalizeSubtitleLabel(
                                                rawLang.ifBlank { rawLabel.ifBlank { "Altyazı" } }
                                            )
                                            val subtitleKey = trackLabel.lowercase()
                                            if (!seenSubtitleLanguages.add(subtitleKey)) continue

                                            uniqueSubtitleCallback(SubtitleFile(trackLabel, trackUrl))
                                        }
                                    }

                                    Log.d(
                                        JET_TAG,
                                        "VideoPark kaynakları: akış=" + streamPayloads.size +
                                            " medya=" + seenMediaUrls.size +
                                            " altyazı=" + seenSubtitleLanguages.size,
                                    )
                                }
                            } else {
                            fun jsConstant(vararg constantNames: String): String? {
                                // Player sürümleri aynı alanları PUB_ID/pubId veya
                                // PUBLISHER_ID/publisherId gibi farklı adlarla sunabiliyor.
                                for (constantName in constantNames) {
                                    val escapedName = Regex.escape(constantName)
                                    val declarationPattern = Regex(
                                        """(?is)(?:const|let|var)\s+""" +
                                            escapedName +
                                            """\s*=\s*(?:["']([^"']*)["']|([0-9]+))"""
                                    )
                                    val declaration = declarationPattern.find(playerPageText)
                                    val declaredValue = declaration?.let {
                                        it.groupValues.getOrNull(1)
                                            ?.takeIf(String::isNotBlank)
                                            ?: it.groupValues.getOrNull(2)
                                    }?.trim()
                                    if (!declaredValue.isNullOrBlank()) return declaredValue

                                    // Bazı player sürümleri değerleri config nesnesinde tutuyor.
                                    val objectPattern = Regex(
                                        """(?is)["']?""" + escapedName +
                                            """["']?\s*:\s*["']([^"']+)["']"""
                                    )
                                    val objectValue = objectPattern.find(playerPageText)
                                        ?.groupValues?.getOrNull(1)?.trim()
                                    if (!objectValue.isNullOrBlank()) return objectValue
                                }
                                return null
                            }

                            fun queryValue(parameter: String): String? {
                                val pattern = Regex(
                                    """[?&]""" + Regex.escape(parameter) + """=([^&#]+)"""
                                )
                                return pattern.find(playerUrl)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.takeIf { it.isNotBlank() }
                            }

                            val videoId = jsConstant("VIDEO_ID") ?: queryValue("id")

                            if (isGoDrivePlayer) {
                                // İkinci JetFilmizle player: kalite listesi /v/{id}/info
                                // üzerinden gelir; gerçek video /v/{id}?q={quality} yoludur.
                                val workerBase = (
                                    jsConstant("WORKER_BASE")
                                        ?: "https://godrive.erikkalinina1994.workers.dev"
                                    ).replace("\\/", "/").trimEnd('/')
                                val goDriveId = videoId

                                if (!goDriveId.isNullOrBlank()) {
                                    val infoUrl = workerBase + "/v/" +
                                        URLEncoder.encode(goDriveId, "UTF-8") + "/info"
                                    val info = runCatching {
                                        val infoText = app.get(
                                            infoUrl,
                                            headers = mapOf(
                                                "User-Agent" to JET_UA,
                                                "Referer" to playerUrl,
                                                "Accept" to "application/json,text/plain,*/*",
                                            ),
                                            timeout = JET_PLAYER_TIMEOUT_MS,
                                            allowRedirects = true,
                                        ).text
                                        org.json.JSONObject(infoText)
                                    }.getOrNull()

                                    val qualities = info?.optJSONArray("qualities")
                                    if (qualities != null && qualities.length() > 0) {
                                        for (i in 0 until qualities.length()) {
                                            val quality = qualities.optString(i).trim()
                                            if (quality.isBlank() || quality == "null") continue

                                            val qualityLabel = if (quality.endsWith("p", true)) {
                                                quality
                                            } else {
                                                quality + "p"
                                            }
                                            val fileUrl = workerBase + "/v/" +
                                                URLEncoder.encode(goDriveId, "UTF-8") +
                                                "?q=" + URLEncoder.encode(quality, "UTF-8")

                                            uniqueLinkCallback(
                                                newExtractorLink(
                                                    source = name,
                                                    name = label + " - " + qualityLabel,
                                                    url = fileUrl,
                                                    type = ExtractorLinkType.VIDEO,
                                                ) {
                                                    referer = playerUrl
                                                    this.quality = getQualityFromName(qualityLabel)
                                                }
                                            )
                                            emitted = true
                                        }
                                    }

                                    if (!emitted) {
                                        val directUrl = workerBase + "/v/" +
                                            URLEncoder.encode(goDriveId, "UTF-8")
                                        uniqueLinkCallback(
                                            newExtractorLink(
                                                source = name,
                                                name = label + " - MP4",
                                                url = directUrl,
                                                type = ExtractorLinkType.VIDEO,
                                            ) {
                                                referer = playerUrl
                                                quality = Qualities.Unknown.value
                                            }
                                        )
                                        emitted = true
                                    }

                                    Log.d(
                                        JET_TAG,
                                        "GoDrive Worker kaynak sonucu: id=" + goDriveId +
                                            " qualities=" + (qualities?.length() ?: 0),
                                    )
                                } else {
                                    Log.w(JET_TAG, "GoDrive player içinde VIDEO_ID bulunamadı")
                                }
                            } else {
                            val isVideoParkHost = playerUrl.contains("videopark.top", true)
                            // Bu iki kimlik, tarayıcıdan alınan başarılı /api/stream
                            // isteğinde doğrulandı. HTML/JS değişirse yalnızca VideoPark
                            // için yedek olarak kullan; diğer DPlayer sağlayıcılarını etkileme.
                            val pubId = jsConstant("PUB_ID", "pubId", "publicationId")
                                ?: queryValue("pubId")
                                ?: if (isVideoParkHost) "689017f7e019e64194db2cf6" else null
                            val publisherId = jsConstant("PUBLISHER_ID", "publisherId")
                                ?: queryValue("publisherId")
                                ?: if (isVideoParkHost) "688dd580384ba8406f5a4c3a" else null
                            val videoTitle = jsConstant("VIDEO_TITLE", "videoTitle", "title")
                                ?: videoId
                                ?: label
                            val workerUrl = jsConstant("WORKER_URL")
                                ?: "https://dplayer.erikkalinina1994.workers.dev"

                            val apiUrl = when {
                                !pubId.isNullOrBlank() -> {
                                    workerUrl.trimEnd('/') +
                                        "/api/stream?pubId=" +
                                        URLEncoder.encode(pubId, "UTF-8") +
                                        "&title=" +
                                        URLEncoder.encode(videoTitle, "UTF-8") +
                                        if (!publisherId.isNullOrBlank()) {
                                            "&publisherId=" +
                                                URLEncoder.encode(publisherId, "UTF-8")
                                        } else {
                                            ""
                                        }
                                }

                                !videoId.isNullOrBlank() -> {
                                    workerUrl.trimEnd('/') +
                                        "/api/video?id=" +
                                        URLEncoder.encode(videoId, "UTF-8")
                                }
                                else -> ""
                            }

                            if (apiUrl.isNotBlank()) {
                                Log.d(
                                    JET_TAG,
                                    "JWPlayer Worker API deneniyor: pubId=" +
                                        (pubId ?: "yok") + " videoId=" + (videoId ?: "yok"),
                                )
                                val apiText = app.get(
                                    apiUrl,
                                    headers = mapOf(
                                        "User-Agent" to JET_UA,
                                        // VideoPark sayfası Worker API'ye kök sayfa
                                        // referer'ı ve kendi origin'i ile istek gönderiyor.
                                        "Referer" to "https://videopark.top/",
                                        "Origin" to "https://videopark.top",
                                        "Accept" to "application/json,text/plain,*/*",
                                    ),
                                    timeout = JET_PLAYER_TIMEOUT_MS,
                                    allowRedirects = true,
                                ).text

                                val payload = org.json.JSONObject(apiText)
                                val apiError = payload.optString("error").trim()
                                if (apiError.isNotBlank() && apiError != "null") {
                                    Log.w(JET_TAG, "JWPlayer Worker API hatası: " + apiError)
                                } else {
                                    val hlsFile = payload.optJSONObject("hlsSource")
                                        ?.optString("file")
                                        ?.trim()
                                        ?.takeIf { it.startsWith("http", true) }
                                        ?: payload.optString("hlsSource")
                                            .trim()
                                            .takeIf { it.startsWith("http", true) }

                                    if (!hlsFile.isNullOrBlank()) {
                                        uniqueLinkCallback(
                                            newExtractorLink(
                                                source = name,
                                                name = label + " - HLS",
                                                url = hlsFile,
                                                type = ExtractorLinkType.M3U8,
                                            ) {
                                                referer = "https://videopark.top/"
                                                quality = Qualities.Unknown.value
                                            }
                                        )
                                        emitted = true
                                    }

                                    // Worker API sürümleri hlsSource yerine yalnızca
                                    // sources dizisini döndürürse HLS kaynağını yine yakala.
                                    val genericSources = payload.optJSONArray("sources")
                                    if (genericSources != null) {
                                        for (i in 0 until genericSources.length()) {
                                            val sourceObject = genericSources.optJSONObject(i) ?: continue
                                            val fileUrl = sourceObject.optString("file").trim()
                                                .takeIf { it.startsWith("http", true) } ?: continue
                                            val format = sourceObject.optString("type").lowercase()
                                            val isHls = format.contains("hls") ||
                                                fileUrl.substringBefore('?').endsWith(".m3u8", true)
                                            uniqueLinkCallback(
                                                newExtractorLink(
                                                    source = name,
                                                    name = label + " - " +
                                                        sourceObject.optString("label").ifBlank {
                                                            if (isHls) "HLS" else "Video"
                                                        },
                                                    url = fileUrl,
                                                    type = if (isHls) ExtractorLinkType.M3U8
                                                        else ExtractorLinkType.VIDEO,
                                                ) {
                                                    referer = "https://videopark.top/"
                                                    quality = getQualityFromName(sourceObject.optString("label"))
                                                }
                                            )
                                            emitted = true
                                        }
                                    }

                                    val workerTracks = payload.optJSONArray("tracks")
                                    if (workerTracks != null) {
                                        for (i in 0 until workerTracks.length()) {
                                            val track = workerTracks.optJSONObject(i) ?: continue
                                            val trackUrl = sequenceOf(
                                                track.optString("file"),
                                                track.optString("src"),
                                                track.optString("url"),
                                            ).firstOrNull { it.startsWith("http", true) } ?: continue
                                            val kind = track.optString("kind").lowercase()
                                            if (kind.isNotBlank() &&
                                                !kind.contains("subtitle") &&
                                                !kind.contains("caption")
                                            ) continue
                                            val language = sequenceOf(
                                                track.optString("lang"),
                                                track.optString("srclang"),
                                                track.optString("language"),
                                                track.optString("label"),
                                            ).firstOrNull { it.isNotBlank() && it != "null" }
                                                ?: "Türkçe"
                                            uniqueSubtitleCallback(
                                                SubtitleFile(normalizeSubtitleLabel(language), trackUrl)
                                            )
                                        }
                                    }

                                    val mp4Sources = payload.optJSONArray("mp4Sources")
                                    if (mp4Sources != null) {
                                        for (i in 0 until mp4Sources.length()) {
                                            val sourceObject = mp4Sources.optJSONObject(i) ?: continue
                                            val fileUrl = sourceObject.optString("file")
                                                .trim()
                                                .takeIf { it.startsWith("http", true) }
                                                ?: continue
                                            val qualityLabel = sourceObject.optString("label")
                                                .trim()
                                                .ifBlank { "MP4" }

                                            uniqueLinkCallback(
                                                newExtractorLink(
                                                    source = name,
                                                    name = label + " - " + qualityLabel,
                                                    url = fileUrl,
                                                    type = ExtractorLinkType.VIDEO,
                                                ) {
                                                    referer = "https://videopark.top/"
                                                    quality = getQualityFromName(qualityLabel)
                                                }
                                            )
                                            emitted = true
                                        }
                                    }
                                }
                            } else {
                                Log.d(JET_TAG, "JWPlayer HTML içinde pubId/videoId bulunamadı")
                            }
                            }
                            }
                        } catch (e: Exception) {
                            Log.e(JET_TAG, "JWPlayer/VideoPark çözümleme hatası: " + e.message)
                        }
                    }

                    if (emitted) {
                        Log.d(JET_TAG, "Player kaynakları başarıyla alındı: " + label)
                    } else if (
                        !isGoDrivePlayer && !isVideoParkPlayer && !isDPlayerWorkerPlayer
                    ) {
                        // Eski iframe sağlayıcıları için extractor yedeği.
                        // GoDrive bilinen bir player ise sayfayı genel extractor'a
                        // vermeyip fragman veya HTML içeriğinin ana video sayılmasını önle.
                        loadExtractor(
                            playerUrl,
                            data,
                            uniqueSubtitleCallback,
                            { link ->
                                emitted = true
                                uniqueLinkCallback(link)
                            },
                        )
                    } else {
                        Log.w(
                            JET_TAG,
                            "Bilinen player kaynağı alınamadı; yanlış fragman yedeği açılmayacak: " +
                                when {
                                    isVideoParkPlayer -> "VideoPark"
                                    isDPlayerWorkerPlayer -> "DPlayer Worker"
                                    else -> "GoDrive"
                                },
                        )
                    }
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

            // Eski sürümden gelen bölüm bağlantıları için geriye dönük uyumluluk.
            val selectedIndex = data.substringAfter("index=")
                .substringBefore("&")
                .toIntOrNull()

            val selectedType = data.substringAfter("type=")
                .substringBefore("&")
                .trim()
                .ifBlank { "dublaj" }

            if (season == null || episode == null) return false

            val tried = mutableSetOf<String>()
            var foundAny = false

            val matchingButtons = document.select(
                "button.episode-btn[data-season][data-episode][data-source-index][data-player-type]"
            ).filter { button ->
                button.attr("data-season").toIntOrNull() == season &&
                    button.attr("data-episode").toIntOrNull() == episode
            }

            // Eski link seçili bir indeks taşıyorsa önce onu dene, sonra diğer
            // kaynaklara geç. Yeni bölüm kartları tüm kaynakları doğrudan tarar.
            if (selectedIndex != null) {
                val selectedButton = matchingButtons.firstOrNull { button ->
                    button.attr("data-source-index").toIntOrNull() == selectedIndex &&
                        button.attr("data-player-type").trim() == selectedType
                }
                val selectedLabel = selectedButton?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "S" + season + "E" + episode + " • " + selectedType

                val key = "$selectedIndex|$selectedType"
                if (tried.add(key) &&
                    requestPlayer(selectedIndex, selectedType, selectedLabel)
                ) {
                    foundAny = true
                }
            }

            // Tek tek bütün kaynakları çöz. Bir tanesinin başarılı olması
            // döngüyü durdurmaz; Vidmoly ve diğer player'lar da listelenir.
            for (button in matchingButtons) {
                val index = button.attr("data-source-index").toIntOrNull() ?: continue
                val type = button.attr("data-player-type").trim()
                if (type.isBlank()) continue

                val key = "$index|$type"
                if (!tried.add(key)) continue

                val label = button.text().trim()
                    .ifBlank { "S" + season + "E" + episode + " • Kaynak " + index }

                if (requestPlayer(index, type, label)) {
                    foundAny = true
                }
            }

            // Sayfada kaynak düğmeleri bulunmayan eski HTML için yedek deneme.
            if (matchingButtons.isEmpty() && selectedIndex != null) {
                for (type in listOf(selectedType, "dublaj", "altyazili").distinct()) {
                    if (type == selectedType) continue
                    if (requestPlayer(
                            selectedIndex,
                            type,
                            "S" + season + "E" + episode + " • " + type,
                        )
                    ) {
                        foundAny = true
                    }
                }
            }

            Log.d(
                JET_TAG,
                "Bölüm kaynakları: S" + season + "E" + episode +
                    " denenen=" + tried.size + " bulunan=" + foundAny,
            )
            if (foundAny) return true
            Log.w(JET_TAG, "Bölüm kaynakları başarısız; sayfa iframe ve NeonCore yedekleri deneniyor")
        }

        // Film: doğrudan sayfadaki kaynakları önce dene.
        val sourceButtons = document.select(
            "button.player-source-btn[data-source-index]"
        )

        if (sourceButtons.isNotEmpty()) {
            val tried = mutableSetOf<String>()
            var foundAny = false

            for (button in sourceButtons) {
                val index = button.attr("data-source-index").toIntOrNull() ?: continue
                val type = button.attr("data-player-type").trim()
                    .ifBlank { "dublaj" }
                val label = button.text().trim().ifBlank { "Kaynak " + index }

                val key = "$index|$type"
                if (!tried.add(key)) continue

                // Bir kaynak açıldı diye diğer kaynakları bırakma.
                if (requestPlayer(index, type, label)) {
                    foundAny = true
                }
            }

            Log.d(
                JET_TAG,
                "Film kaynakları: denenen=" + tried.size + " bulunan=" + foundAny,
            )

            // Kaynak düğmeleri olan film sayfasında fragman iframe'ine düşme.
            if (foundAny) return true
            Log.w(JET_TAG, "Film kaynakları başarısız; sayfa iframe ve NeonCore yedekleri deneniyor")
        }
        // Eski/alternatif HTML yapısı.
        var legacySourcesFound = false
        document.select(
            "#movie iframe[src], #movie iframe[data-src], .player iframe[src], " +
                ".video-player iframe[src], .video-container iframe[src], iframe[src], iframe[data-src]"
        ).forEach { iframe ->
            val raw = iframe.attr("data-src")
                .ifBlank { iframe.attr("src") }
            if (raw.isBlank()) return@forEach

            val context = buildString {
                var node: Element? = iframe
                repeat(5) {
                    val current = node ?: return@repeat
                    append(' ')
                    append(current.id())
                    append(' ')
                    append(current.className())
                    append(' ')
                    append(current.attr("data-role"))
                    append(' ')
                    node = current.parent()
                }
            }.lowercase()

            val candidateMarker = (raw + " " + context).lowercase()
            val isTrailer = listOf(
                "trailer", "fragman", "preview", "teaser", "youtube.com",
                "youtu.be", "youtube-nocookie.com"
            ).any { candidateMarker.contains(it) }
            if (isTrailer) {
                Log.d(JET_TAG, "Fragman iframe'i oynatma kaynağı olarak atlandı: " + raw)
                return@forEach
            }

            val playerUrl = fixUrlNull(raw) ?: return@forEach
            runCatching {
                var emitted = false
                loadExtractor(
                    playerUrl,
                    data,
                    uniqueSubtitleCallback,
                    { link ->
                        emitted = true
                        uniqueLinkCallback(link)
                    },
                )
                if (emitted) legacySourcesFound = true
            }.onFailure {
                Log.e(JET_TAG, "fallback extractor hata: " + it.message)
            }
        }
        if (legacySourcesFound) return true

        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src")
                .ifBlank { track.attr("data-src") }

            val subUrl = fixUrlNull(raw) ?: return@forEach

            val trackLabel = sequenceOf(
                track.attr("srclang"),
                track.attr("lang"),
                track.attr("label"),
            ).firstOrNull { it.isNotBlank() } ?: "Türkçe"
            runCatching {
                uniqueSubtitleCallback(
                    SubtitleFile(normalizeSubtitleLabel(trackLabel), subUrl)
                )
            }
        }

        val hasTrailerMarkup = document.select(
            "[id*=trailer i], [class*=trailer i], [id*=fragman i], [class*=fragman i], " +
                "[data-trailer], .preview, [class*=preview i]"
        ).isNotEmpty()

        if (!hasTrailerMarkup) {
            val neonFound = neonResolveLinks(
                data = data,
                sourceName = "$name - NeonCore",
                subtitleCallback = uniqueSubtitleCallback,
                callback = callback,
            )
            if (neonFound) return true
        } else {
            Log.d(JET_TAG, "Sayfada ayrı fragman alanı var; ana oynatma için genel sayfa taraması atlandı")
        }

        return false
    }

}
