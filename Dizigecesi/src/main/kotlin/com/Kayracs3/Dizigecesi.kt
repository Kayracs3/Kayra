package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

private const val TMDB_API_KEY = "500330721680edb6d5f7f12ba7cd9023"
private const val DG_TAG = "Dizigecesi"

data class TmdbSearchItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)

data class TmdbSearchPage(
    @JsonProperty("results") val results: List<TmdbSearchItem> = emptyList()
)

data class TmdbGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class TmdbVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("iso_639_1") val language: String? = null,
    @JsonProperty("official") val official: Boolean? = null
)

data class TmdbVideos(
    @JsonProperty("results") val results: List<TmdbVideo> = emptyList()
)

data class TmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("order") val order: Int? = null
)

data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCast> = emptyList()
)

data class TmdbDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre> = emptyList(),
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("videos") val videos: TmdbVideos? = null
)

class Dizigecesi : MainAPI() {
    override var mainUrl = "https://dizigecesi.com"
    override var name = "Dizigecesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl + "/"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Popüler Diziler",
        "${mainUrl}/filmler" to "Yeni Filmler",
        "${mainUrl}/populer" to "En Popüler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else "${request.data}?page=${page}"
        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null
        if (isEpisodeUrl(href)) return null

        val img = element.selectFirst("img.card-series__image-media, img")
        val title = cleanTitle(
            element.selectFirst(".card-series__content-title")?.text(),
            img?.attr("alt"),
            element.attr("title")
        )
        if (title.isBlank()) return null

        val poster = img?.let { imageUrl(it) }
        val year = Regex("""\b(?:19|20)\d{2}\b""")
            .find(element.text())
            ?.value
            ?.toIntOrNull()

        val isMovie = href.contains("/film/", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)

        val encoded = URLEncoder.encode(q, "UTF-8")
        val targetUrl = if (page <= 1) {
            mainUrl + "/?s=" + encoded
        } else {
            mainUrl + "/page/" + page + "/?s=" + encoded
        }

        val document = runCatching {
            app.get(
                targetUrl,
                headers = browserHeaders,
                referer = mainUrl + "/",
                allowRedirects = true
            ).document
        }.getOrNull() ?: return newSearchResponseList(emptyList(), false)

        val results = parseSearchResults(document)
        return newSearchResponseList(
            results,
            hasNext = page < 20 && hasNextPage(document, page)
        )
    }


    fun parseSearchResults(document: Document): List<SearchResponse> {
        val elements = document.select(
            "a.card-series[href*='/dizi/'], a.card-series[href*='/film/']"
        )

        return elements.mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val isMovie = url.contains("/film/", true)

        val title = cleanTitle(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.title().substringBefore(" - "),
            url.substringAfterLast("/")
        )

        val sitePoster = posterOf(document)
        val sitePlot = firstNonBlank(
            document.selectFirst(".detail-info-content__description-description")?.text(),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content")
        )

        val siteYear = Regex("""\b(?:19|20)\d{2}\b""")
            .find(document.selectFirst(".detail-info-knowledge__items")?.text().orEmpty())
            ?.value
            ?.toIntOrNull()

        val siteTags = document.select(
            ".detail-info-knowledge__categories a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val tmdb = fetchTmdbDetail(title, siteYear, isMovie)

        val poster = sitePoster ?: tmdb?.posterPath?.let {
            "https://image.tmdb.org/t/p/w500" + it
        }

        val backdrop = tmdb?.backdropPath?.let {
            "https://image.tmdb.org/t/p/w1280" + it
        }

        val plot = firstNonBlank(sitePlot, tmdb?.overview)

        val year = siteYear ?: (
            if (isMovie) tmdb?.releaseDate else tmdb?.firstAirDate
        )?.take(4)?.toIntOrNull()

        val tags = (
            siteTags + tmdb?.genres.orEmpty().mapNotNull { it.name }
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val score = tmdb?.voteAverage

        val actors = tmdb?.credits?.cast.orEmpty()
            .sortedBy { it.order ?: Int.MAX_VALUE }
            .take(15)
            .mapNotNull { cast ->
                val actorName = cast.name?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                Actor(
                    actorName,
                    cast.profilePath?.let {
                        "https://image.tmdb.org/t/p/w500" + it
                    }
                )
            }

        val trailer = tmdb?.videos?.results.orEmpty()
            .filter {
                it.site.equals("YouTube", true) &&
                    !it.key.isNullOrBlank() &&
                    (
                        it.type.equals("Trailer", true) ||
                            it.type.equals("Teaser", true)
                    )
            }
            .sortedWith(
                compareBy<TmdbVideo>(
                    { if (it.language.equals("tr", true)) 0 else 1 },
                    { if (it.type.equals("Trailer", true)) 0 else 1 },
                    { if (it.official == true) 0 else 1 }
                )
            )
            .mapNotNull {
                it.key?.let { key ->
                    "https://www.youtube.com/watch?v=" + key
                }
            }
            .distinct()
            .firstOrNull()

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                score?.let { this.score = Score.from10(it.toString()) }
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }

        val episodes = parseEpisodes(document, poster)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            score?.let { this.score = Score.from10(it.toString()) }
            addActors(actors)
            trailer?.let { addTrailer(it) }
        }
    }

    private suspend fun fetchTmdbDetail(
        title: String,
        year: Int?,
        isMovie: Boolean
    ): TmdbDetail? {
        if (title.isBlank()) return null

        val type = if (isMovie) "movie" else "tv"
        val encoded = URLEncoder.encode(title, "UTF-8")

        val search = runCatching {
            app.get(
                "https://api.themoviedb.org/3/search/" + type +
                    "?api_key=" + TMDB_API_KEY +
                    "&language=tr-TR&include_adult=false&page=1&query=" + encoded,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json"
                ),
                cacheTime = 3600
            ).parsedSafe<TmdbSearchPage>()
        }.getOrNull() ?: return null

        val wanted = normalizeForMatch(title)

        val candidate = search.results
            .filter { it.id != null }
            .minByOrNull { item ->
                val candidateTitle = normalizeForMatch(
                    if (isMovie) {
                        item.title ?: item.originalTitle
                    } else {
                        item.name ?: item.originalName
                    }
                )

                val candidateYear = (
                    if (isMovie) item.releaseDate else item.firstAirDate
                )?.take(4)?.toIntOrNull()

                val titlePenalty = when {
                    candidateTitle == wanted -> 0
                    candidateTitle.contains(wanted) -> 10
                    wanted.contains(candidateTitle) -> 20
                    else -> 100
                }

                val yearPenalty = when {
                    year == null || candidateYear == null -> 5
                    year == candidateYear -> 0
                    kotlin.math.abs(year - candidateYear) <= 1 -> 2
                    else -> 10
                }

                titlePenalty + yearPenalty
            } ?: return null

        val id = candidate.id ?: return null

        return runCatching {
            app.get(
                "https://api.themoviedb.org/3/" + type + "/" + id +
                    "?api_key=" + TMDB_API_KEY +
                    "&language=tr-TR" +
                    "&append_to_response=credits,videos" +
                    "&include_video_language=tr,en,null",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json"
                ),
                cacheTime = 3600
            ).parsedSafe<TmdbDetail>()
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(DG_TAG, "loadLinks: " + data)

        val document = runCatching {
            app.get(
                data,
                headers = browserHeaders,
                referer = mainUrl + "/",
                allowRedirects = true,
                cacheTime = 0
            ).document
        }.getOrNull() ?: return false

        var found = false

        suspend fun resolveCandidate(
            candidate: String,
            referer: String,
            sourceName: String
        ): Boolean {
            return resolvePlayer(
                candidate,
                referer,
                sourceName,
                subtitleCallback,
                callback,
                0
            )
        }

        // 1) Doğrudan iframe/video/source.
        val directCandidates = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], " +
                "video[src], video[data-src], source[src], source[data-src]"
        ).forEach { element ->
            val raw = firstNonBlank(
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("src")
            )

            fixUrlNull(raw)?.let { candidate ->
                if (!candidate.contains(mainUrl + "/app/", true)) {
                    directCandidates += candidate
                }
            }
        }

        for (candidate in directCandidates) {
            if (resolveCandidate(candidate, data, "Doğrudan Kaynak")) {
                found = true
            }
        }

        // 2) data-embed ve AJAX.
        val embedElements = document.select(
            "button[data-embed], a[data-embed], [data-embed]"
        ).filter {
            it.attr("data-embed").trim().isNotBlank()
        }.distinctBy {
            it.attr("data-embed").trim()
        }

        for (element in embedElements) {
            val id = element.attr("data-embed").trim()
            val sourceName = firstNonBlank(
                element.selectFirst(".name, .source-name, .title")?.text(),
                element.text()
            ) ?: "Alternatif"

            if (id.startsWith("http://") || id.startsWith("https://")) {
                if (resolveCandidate(id, data, sourceName)) found = true
                continue
            }

            val ajax = runCatching {
                app.post(
                    mainUrl + "/ajax/embed",
                    headers = browserHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*",
                        "Referer" to data
                    ),
                    referer = data,
                    data = mapOf("id" to id),
                    cacheTime = 0,
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            if (!ajax.isSuccessful) continue

            val ajaxText = ajax.text
                .replace("\\/", "/")
                .replace("&amp;", "&")

            val candidates = linkedSetOf<String>()

            Regex(
                """<(?:iframe|embed)[^>]+(?:src|data-src)=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(ajaxText)
                .mapNotNull { fixUrlNull(it.groupValues[1]) }
                .forEach { candidates += it }

            Regex(
                """(?:src|url|player|embed)\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(ajaxText)
                .mapNotNull { fixUrlNull(it.groupValues[1]) }
                .forEach { candidates += it }

            Regex(
                """(?:https?:)?//[^"'<>\s]*video\.php\?[^"'<>\s]+""",
                RegexOption.IGNORE_CASE
            ).findAll(ajaxText)
                .mapNotNull { fixUrlNull(it.value) }
                .forEach { candidates += it }

            Regex(
                """(?:https?:)?//[^"'<>\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(ajaxText)
                .mapNotNull { fixUrlNull(it.value) }
                .forEach { candidates += it }

            for (candidate in candidates) {
                if (resolveCandidate(candidate, data, sourceName)) found = true
            }
        }

        // 3) Moly/Abyss/UPN/Moon/P2P gibi sağlayıcı düğmeleri.
        val sourceButtons = document.select(
            "button[data-id], a[data-id], [data-source-id], [data-player-id]"
        ).filter { element ->
            val text = (
                element.text() + " " +
                    element.attr("class") + " " +
                    element.attr("data-source") + " " +
                    element.attr("data-provider")
            ).lowercase(Locale.ROOT)

            listOf(
                "moly",
                "abyss",
                "upn",
                "moon",
                "p2p",
                "vidmoly",
                "filemoon"
            ).any { text.contains(it) }
        }

        for (button in sourceButtons.distinctBy {
            listOf(
                it.attr("data-id"),
                it.attr("data-source-id"),
                it.attr("data-player-id")
            ).joinToString("|")
        }) {
            val id = firstNonBlank(
                button.attr("data-source-id"),
                button.attr("data-player-id"),
                button.attr("data-id")
            ) ?: continue

            val sourceName = firstNonBlank(
                button.selectFirst(".name, .source-name, .title")?.text(),
                button.text()
            ) ?: "Alternatif"

            val endpoints = listOf(
                mainUrl + "/ajax/embed",
                mainUrl + "/ajax/player",
                mainUrl + "/ajax/source"
            )

            for (endpoint in endpoints) {
                val response = runCatching {
                    app.post(
                        endpoint,
                        headers = browserHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*",
                            "Referer" to data
                        ),
                        referer = data,
                        data = mapOf("id" to id),
                        cacheTime = 0,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!response.isSuccessful) continue

                val html = response.text
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                val candidates = linkedSetOf<String>()

                Regex(
                    """<(?:iframe|embed)[^>]+(?:src|data-src)=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)
                    .mapNotNull { fixUrlNull(it.groupValues[1]) }
                    .forEach { candidates += it }

                Regex(
                    """(?:https?:)?//[^"'<>\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)
                    .mapNotNull { fixUrlNull(it.value) }
                    .forEach { candidates += it }

                for (candidate in candidates) {
                    if (resolveCandidate(candidate, data, sourceName)) found = true
                }

                if (found) break
            }
        }

        // 4) Player URL'lerini HTML'den son çare olarak bul.
        if (!found) {
            val html = runCatching {
                app.get(
                    data,
                    headers = browserHeaders,
                    referer = mainUrl + "/",
                    allowRedirects = true,
                    cacheTime = 0
                ).text
            }.getOrNull().orEmpty()

            val candidates = linkedSetOf<String>()

            Regex(
                """https?://[^"'<>\s]+""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
                .mapNotNull { match ->
                    val value = match.value
                    if (
                        value.contains("vidmoly", true) ||
                        value.contains("moly", true) ||
                        value.contains("abyss", true) ||
                        value.contains("upn", true) ||
                        value.contains("moon", true) ||
                        value.contains("p2p", true) ||
                        value.contains("filemoon", true) ||
                        value.contains("ok.ru", true) ||
                        value.contains("vk.com", true)
                    ) {
                        fixUrlNull(value)
                    } else {
                        null
                    }
                }
                .forEach { candidates += it }

            for (candidate in candidates) {
                if (resolveCandidate(candidate, data, "HTML Kaynağı")) found = true
            }
        }

        Log.d(DG_TAG, "loadLinks sonucu found=" + found)
        return found
    }

    private suspend fun resolvePlayer(
        candidate: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > 3) return false

        val normalized = fixUrlNull(
            candidate
                .trim()
                .replace("\\/", "/")
                .replace("&amp;", "&")
        ) ?: return false

        if (normalized.contains(mainUrl + "/app/", true)) return false

        var found = false

        if (isMediaUrl(normalized)) {
            emitDirectMedia(normalized, referer, sourceName, callback)
            return true
        }

        runCatching {
            loadExtractor(
                normalized,
                referer,
                subtitleCallback
            ) { link ->
                found = true
                callback(link)
            }
        }

        if (found) return true

        val response = runCatching {
            app.get(
                normalized,
                headers = browserHeaders + mapOf("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                cacheTime = 0
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        val html = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val directMedia = linkedSetOf<String>()

        Regex(
            """https?://[^"'<>\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { fixUrlNull(it.value) }
            .forEach { directMedia += it }

        Regex(
            """["'](?:file|src|url|source|videoSource|securedLink)["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { fixUrlNull(it.groupValues[1]) }
            .filter { isMediaUrl(it) }
            .forEach { directMedia += it }

        response.document.select(
            "video[src], video[data-src], source[src], source[data-src]"
        ).forEach { element ->
            fixUrlNull(
                firstNonBlank(
                    element.attr("data-src"),
                    element.attr("src")
                )
            )?.takeIf { isMediaUrl(it) }?.let { directMedia += it }
        }

        for (stream in directMedia) {
            emitDirectMedia(
                stream,
                normalized,
                sourceName,
                callback
            )
            extractSubtitlesFromHtml(html, subtitleCallback)
            found = true
        }

        if (found) return true

        extractSubtitlesFromHtml(html, subtitleCallback)

        val nested = linkedSetOf<String>()

        Regex(
            """<(?:iframe|embed)[^>]+(?:src|data-src)=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { fixUrlNull(it.groupValues[1]) }
            .forEach { nested += it }

        response.document.select(
            "iframe[src], iframe[data-src], iframe[data-url]"
        ).forEach { element ->
            fixUrlNull(
                firstNonBlank(
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("src")
                )
            )?.let { nested += it }
        }

        for (next in nested) {
            if (next.equals(normalized, true)) continue

            if (
                resolvePlayer(
                    next,
                    normalized,
                    sourceName,
                    subtitleCallback,
                    callback,
                    depth + 1
                )
            ) {
                return true
            }
        }

        return false
    }

    private suspend fun emitDirectMedia(
        url: String,
        referer: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = name + " - " + sourceName,
                url = url,
                type = type
            ) {
                quality = Qualities.Unknown.value
                this.referer = referer
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            }
        )
    }

    private fun extractSubtitlesFromHtml(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex(
            """https?://[^"'<>\s]+\.(?:vtt|srt)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .distinct()
            .forEach { subtitle ->
                runCatching {
                    subtitleCallback(
                        SubtitleFile("Türkçe", subtitle)
                    )
                }
            }
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains(".mpd")
    }

    private fun buildPageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl

        return when {
            baseUrl.contains("?") -> baseUrl + "&page=" + page
            baseUrl.endsWith("/") -> baseUrl + "page/" + page + "/"
            else -> baseUrl + "?page=" + page
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel='next'], " +
                "a:matchesOwn((?i)Sonraki), " +
                "a:matchesOwn((?i)İleri), " +
                "a[href*='page=" + (page + 1) + "']"
        ) != null
    }

    private fun posterOf(document: Document): String? {
        return firstNonBlank(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("meta[name='twitter:image']")?.attr("content"),
            document.selectFirst(
                ".detail-info-media__image, img.card-series__image-media, main img, article img"
            )?.let { imageUrl(it) }
        )?.let(::fixUrlNull)
    }

    private fun imageUrl(image: Element): String? {
        return firstNonBlank(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-wpfc-original-src"),
            image.attr("data-poster"),
            image.attr("src")
        )?.let(::fixUrlNull)
    }

    private fun normalizeForMatch(value: String?): String {
        return value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanTitle(vararg values: String?): String {
        return values.firstNotNullOfOrNull { value ->
            value
                ?.replace(Regex("""(?i)\s*[-|/]\s*Dizigecesi.*$"""), "")
                ?.replace(Regex("""(?i)\s+İzle.*$"""), "")
                ?.replace(Regex("""\s+\(\d{4}\)$"""), "")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } ?: ""
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url.trimEnd('/')
            url.startsWith("//") -> "https:" + url.trimEnd('/')
            url.startsWith("/") -> (mainUrl + url).trimEnd('/')
            else -> url.trimEnd('/')
        }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return Regex("""/\d+-sezon/\d+-bolum(?:/)?$""")
            .containsMatchIn(url.lowercase(Locale.ROOT))
    }

    private fun parseEpisodes(document: Document, poster: String?): List<Episode> {
        val regex = Regex("""(?:/dizi/[^/]+/)?(\d+)-sezon/(\d+)-bolum""")

        return document.select(
            "a[href*='-sezon/'][href*='-bolum']"
        ).mapNotNull { element ->
            val href = fixUrlNull(element.attr("href"))
                ?: return@mapNotNull null

            val match = regex.find(href)
                ?: return@mapNotNull null

            val season = match.groupValues[1].toIntOrNull()
                ?: return@mapNotNull null
            val episode = match.groupValues[2].toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(href) {
                this.name = "Bölüm " + episode
                this.season = season
                this.episode = episode
                this.posterUrl = poster
            }
        }.distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: 0 }
            )
    }

}