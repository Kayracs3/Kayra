package com.Kayracs3

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class SinezyTo : MainAPI() {
    override var mainUrl = "https://sinezy.si"
    override var name = "Sinezy"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/izle/en-yeni-filmler/" to "Yeni Filmler",
        "$mainUrl/izle/yabanci-dizi/" to "Yabancı Diziler",
        "$mainUrl/izle/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/film-arsivi/" to "Film Arşivi"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun decode(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\x26", "&")
        .replace("\\u003d", "=")
        .replace("\\x3d", "=")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")

    /**
     * Sinezy'nin bazı oynatıcı blokları Base64 içindeki iframe HTML'si olarak gelir.
     * Hatalı/uygun olmayan değerleri sessizce yok sayar; yalnızca çözülebilir metin döndürür.
     */
    private fun decodeBase64Payload(raw: String): String? {
        val compact = raw.trim().replace(Regex("\\s+"), "")
        if (compact.length < 12) return null

        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = runCatching {
            Base64.decode(padded, Base64.DEFAULT)
        }.getOrNull() ?: return null

        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return null
        return decoded.takeIf {
            it.contains("iframe", true) || it.contains("src=", true) ||
                it.contains("m3u8", true) || it.contains("http", true)
        }
    }

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("data:", true) ||
            value.startsWith("javascript:", true) || value == "#"
        ) return null

        return try {
            val result = when {
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
            result.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("(?i)^poster\\s*"), "")
        .replace(Regex("(?i)\\s+izle\\s*$"), "")
        .replace(Regex("^\\s*\\d+(?:[.,]\\d+)?\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun imageFrom(img: Element?): String? {
        if (img == null) return null
        for (attr in listOf(
            "data-src", "data-lazy-src", "data-original", "data-lazy",
            "data-img", "data-image", "data-srcset", "srcset", "src"
        )) {
            val raw = img.attr(attr).trim()
            if (raw.isBlank()) continue
            val candidate = if (attr.contains("srcset")) {
                raw.substringBefore(",").substringBefore(" ").trim()
            } else raw
            if (candidate.startsWith("data:", true)) continue
            fixUrl(candidate)?.let { return it }
        }
        return null
    }

    private fun posterOf(link: Element): String? {
        val img = link.selectFirst("img")
            ?: link.parent()?.selectFirst("img")
            ?: link.parent()?.parent()?.selectFirst("img")
        return imageFrom(img)
    }

    // Yetişkin içerikli sayfalar kataloğa ve oynatıcıya alınmaz.
    private fun isAdultSlug(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.lowercase())
        val terms = listOf("erotik", "erotic", "yetiskin", "yetişkin", "adult", "18-plus", "18plus")
        return path.split("/", "-", "_").any { segment -> terms.contains(segment) } ||
            path.contains("18-plus") || path.contains("18plus")
    }

    private fun isAdultCard(link: Element, url: String): Boolean {
        if (isAdultSlug(url)) return true
        val card = link.closest("article, .item, .movie, .film, .post, li") ?: link.parent()
        val categoryText = card?.select(
            ".genres a, .genre a, .categories a, .category a, .cat-links a, .post-categories a, .film-kategorileri a"
        )?.text()?.lowercase().orEmpty()
        return listOf("erotik", "erotic", "yetişkin", "yetiskin", "adult", "+18")
            .any { categoryText.contains(it) }
    }

    private fun isAdultContent(document: Document, url: String): Boolean {
        if (isAdultSlug(url)) return true
        // parseGenres normal katalog etiketlerinden yetişkin kategorisini ayırır;
        // bu kontrol ise ham kategori alanını ayrıca tarayarak bu içeriğin yüklenmesini engeller.
        val rawCategories = document.select(
            "div.detail span a, .genres a, .genre a, .categories a, .category a, " +
                ".cat-links a, .post-categories a, .film-kategorileri a"
        ).joinToString(" ") { it.text() }.lowercase()
        return listOf("erotik", "erotic", "yetişkin", "yetiskin", "adult", "+18", "18-plus")
            .any { rawCategories.contains(it) }
    }

    private fun hasSeriesCategory(document: Document): Boolean {
        return document.select("a[href]").any { link ->
            val label = link.text().trim()
            if (!label.equals("Yabancı Dizi", true) && !label.equals("Yabanci Dizi", true)) {
                return@any false
            }
            val context = (link.parent()?.text().orEmpty() + " " +
                link.parent()?.parent()?.text().orEmpty()).take(700)
            context.contains("Kategori", true) && !context.contains("Film Türleri", true)
        }
    }

    private fun isListingUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.lowercase())
        return path == "/" ||
            path.contains("/izle/") ||
            path.contains("/film-arsivi") ||
            path.contains("/kategori/") ||
            path.contains("/tag/") ||
            path.contains("/page/") ||
            path.contains("/imdb-sirali-liste") ||
            path.contains("/populer-filmler")
    }

    private fun looksLikeSeries(url: String, title: String, genres: List<String> = emptyList()): Boolean {
        val value = "$url $title".lowercase()
        return value.contains("yabanci-dizi") ||
            value.contains("yabancı dizi") ||
            Regex("""\b\d+\s*\.?\s*sezon\b|-\d+-sezon(?:-|/|$)""").containsMatchIn(value) ||
            genres.any { it.equals("Yabancı Dizi", true) || it.equals("Dizi", true) }
    }

    private fun parseCards(document: Document, seriesSection: Boolean = false): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()

        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            val url = fixUrl(href) ?: continue
            if (isListingUrl(url) || isAdultCard(link, url)) continue

            val img = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.parent()?.parent()?.selectFirst("img")
            val rawTitle = link.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("h2,h3,h4,.title,.name")?.text()
                ?: link.text()
            val title = cleanTitle(rawTitle)
            if (title.isBlank() || title.length < 2) continue

            val looksLikeCard = img != null ||
                link.attr("title").contains("izle", true) ||
                link.classNames().any { it.contains("movie", true) || it.contains("film", true) || it.contains("post", true) }
            if (!looksLikeCard) continue

            val poster = imageFrom(img)
            val isSeries = seriesSection || looksLikeSeries(url, title)

            val cardScore = parseCardScore(link)
            val response = if (isSeries) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    score = cardScore
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    score = cardScore
                }
            }

            found.putIfAbsent(url.trimEnd('/'), response)
        }

        return found.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) request.data else "$base/page/$page/"
        val response = app.get(pageUrl, headers = requestHeaders, referer = mainUrl)
        if (!response.isSuccessful) return null

        val seriesSection = request.name.contains("Dizi", true)
        val items = parseCards(response.document, seriesSection)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/arama/?s=$encoded"
        val response = app.get(url, headers = requestHeaders, referer = mainUrl)
        if (!response.isSuccessful) return newSearchResponseList(emptyList(), false)

        val items = parseCards(response.document)
        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    private fun cleanGenre(label: String): String {
        return label.trim()
            .replace(Regex("(?i)\\s+filmleri?$"), "")
            .replace(Regex("(?i)\\s+dizileri?$"), "")
            .replace(Regex("(?i)\\s+filmi$"), "")
            .trim()
    }

    private fun genreLinksIn(element: Element?): List<String> {
        if (element == null) return emptyList()
        return element.select("a[href]").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .filter {
                val low = it.lowercase()
                low.endsWith("filmleri") || low.endsWith("filmi") ||
                    low.endsWith("dizileri") || low == "yabancı dizi" || low == "yabanci dizi"
            }
            .map(::cleanGenre)
            .filterNot {
                it.isBlank() || it.equals("En İyi", true) ||
                    it.equals("Gelecek", true) || it.equals("Yetişkin", true)
            }
            .distinct()
    }

    private fun parseGenres(document: Document): List<String> {
        // Sinezy etiketleri ayrıntı kutusunda doğrudan div.detail span a altında veriyor.
        val siteGenres = document.select("div.detail span a")
            .map { cleanGenre(it.text()) }
            .filter {
                it.endsWith("filmleri", true) || it.endsWith("filmi", true) ||
                    it.endsWith("dizileri", true) || it.equals("Yabancı Dizi", true) ||
                    it.equals("Yabanci Dizi", true) || it.equals("Dizi", true)
            }
            .filter { it.isNotBlank() && !it.contains("yetişkin", true) }
            .distinct()
        if (siteGenres.isNotEmpty()) return siteGenres

        val all = document.getAllElements()
        val label = all.firstOrNull {
            it.ownText().trim().trimEnd(':').equals("Kategori", true) ||
                it.ownText().trim().trimEnd(':').equals("Tür", true) ||
                it.ownText().trim().trimEnd(':').equals("Türler", true)
        }

        var node: Element? = label
        repeat(4) {
            node = node?.parent()
            val candidate = node
            if (candidate != null && candidate.text().length in 1..600) {
                val genres = genreLinksIn(candidate)
                if (genres.isNotEmpty()) return genres
            }
        }

        val fallback = document.select(
            ".genres a, .genre a, .categories a, .category a, .film-kategorileri a, .movie-genres a"
        ).map { cleanGenre(it.text()) }.filter { it.isNotBlank() }.distinct()
        return fallback
    }

    private fun parseCardScore(link: Element): Score? {
        val card = link.closest("div.movie_box, article, .item, .movie, .film, .post, li")
            ?: link.parent()?.parent()
            ?: link.parent()
        val scoreElement = card?.selectFirst(
            "span.coz, span.imdb, .imdb-rating, .imdb_puan, .imdb-puan, " +
                "[itemprop=ratingValue], [data-rating]"
        )
        val elementText = scoreElement?.let {
            it.attr("content").ifBlank { it.attr("data-rating") }
                .ifBlank { it.attr("title") }.ifBlank { it.text() }
        }.orEmpty()
        val titleText = link.attr("title").ifBlank { link.text() }
        val raw = if (elementText.isNotBlank()) elementText else {
            Regex("""^\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)\s+""")
                .find(titleText)?.groupValues?.getOrNull(1).orEmpty()
        }
        val value = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\d)""")
            .find(raw)?.value?.replace(",", ".")?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 } ?: return null
        return runCatching { Score.from10(value) }.getOrNull()
    }

    private fun parseScore(document: Document): Score? {
        fun scoreFrom(raw: String?): Double? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return null
            val candidate = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\d)""")
                .find(value)?.value?.replace(",", ".")?.toDoubleOrNull()
            return candidate?.takeIf { it in 0.0..10.0 }
        }

        val selectors = listOf(
            "meta[itemprop=ratingValue]",
            "[itemprop=ratingValue]",
            "[data-rating]",
            ".detail span.imdb", "div.detail span.imdb", ".info span.imdb", "span.coz",
            ".imdb-rating", ".imdb_puan", ".imdb-puan", ".imdb", ".puan-imdb",
            "[class*=imdb]", "[class*=rating]"
        )
        for (selector in selectors) {
            for (element in document.select(selector)) {
                val raw = element.attr("content")
                    .ifBlank { element.attr("data-rating") }
                    .ifBlank { element.attr("title") }
                    .ifBlank { element.text() }
                val value = scoreFrom(raw) ?: continue
                val classes = element.className().lowercase()
                if (classes.contains("user") || classes.contains("comment") || classes.contains("vote")) continue
                return runCatching { Score.from10(value) }.getOrNull()
            }
        }

        val html = document.outerHtml()
        val jsonScore = Regex(
            """"(?:ratingValue|imdbRating|imdb_rating)"\s*:\s*"?(\d+(?:[.,]\d+)?)"?""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        scoreFrom(jsonScore)?.let { return runCatching { Score.from10(it) }.getOrNull() }

        val poster = document.select("img").firstOrNull {
            val src = it.attr("src") + it.attr("data-src")
            src.contains("/uploads/filmler/", true) || it.attr("alt").contains("Dublaj", true)
        }
        val posterContext = poster?.parent()?.parent()?.text()
        scoreFrom(posterContext)?.let { return runCatching { Score.from10(it) }.getOrNull() }

        val heading = document.selectFirst("h1")
        var context: Element? = heading
        repeat(3) {
            context = context?.parent()
            scoreFrom(context?.text())?.let { return runCatching { Score.from10(it) }.getOrNull() }
        }

        return null
    }

    private fun parseImdbId(document: Document): String? {
        val html = document.outerHtml()
        val patterns = listOf(
            Regex("""https?://(?:www\.)?imdb\.com/title/(tt\d{7,10})""", RegexOption.IGNORE_CASE),
            Regex("""(?:data-imdb-id|data-imdb|imdb_id|imdbId|imdbid)\s*["']?\s*[:=]\s*["']?(tt\d{7,10})""", RegexOption.IGNORE_CASE),
            Regex("""["']imdb["']\s*:\s*["'](tt\d{7,10})["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun parseActors(document: Document): List<ActorData>? {
        // Sinezy'nin mevcut temasında oyuncular çoğu kez span.oyn p içinde virgülle ayrılır.
        val siteActors = document.select("span.oyn p")
            .flatMap { element ->
                element.text()
                    .replace(Regex("(?i)^.*?oyuncular?\\s*:?\\s*"), "")
                    .split(",", "•", "|")
                    .map { it.trim() }
            }
            .filter { it.isNotBlank() && it.length < 60 }
            .distinct()
            .take(30)
            .map { ActorData(Actor(it)) }
        if (siteActors.isNotEmpty()) return siteActors

        val label = document.getAllElements().firstOrNull {
            it.ownText().trim().trimEnd(':').equals("Oyuncular", true) ||
                it.ownText().trim().trimEnd(':').equals("Oyuncu Kadrosu", true)
        }
        var node: Element? = label
        repeat(3) {
            node = node?.parent()
            val current = node ?: return@repeat
            if (current.text().length > 0 && current.text().length < 350) {
                val names = current.select("a[href]").map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty {
                        current.text()
                            .replace(Regex("(?i)^.*?oyuncular?\\s*:?\\s*"), "")
                            .split(",", "•", "|")
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it.length < 60 }
                    }
                val actors = names.distinct().take(30).map { ActorData(Actor(it)) }
                if (actors.isNotEmpty()) return actors
            }
        }
        return null
    }

    private fun parseYear(document: Document): Int? {
        val metadata = document.selectFirst("[itemprop=datePublished], meta[property=article:published_time], .movie-year, .film-yili, .year")
        val raw = metadata?.let { it.attr("content").ifBlank { it.attr("datetime") }.ifBlank { it.text() } }
        Regex("""\b(?:19|20)\d{2}\b""").find(raw.orEmpty())?.value?.toIntOrNull()?.let { return it }

        val poster = document.select("img").firstOrNull {
            (it.attr("src") + it.attr("data-src")).contains("/uploads/filmler/", true)
        }
        var node: Element? = poster
        repeat(3) {
            node = node?.parent()
            Regex("""\b(?:19|20)\d{2}\b""").find(node?.text().orEmpty())?.value?.toIntOrNull()?.let { return it }
        }

        val titleBlock = document.selectFirst("h1")?.parent()?.parent()?.parent()?.text().orEmpty()
        return Regex("""\b(?:19|20)\d{2}\b""").find(titleBlock)?.value?.toIntOrNull()
    }

    private fun parseEpisodes(document: Document, currentUrl: String, title: String): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()
        val patterns = listOf(
            Regex("""(?i)-(\d+)-sezon-(\d+)-bolum(?:-|/|$)"""),
            Regex("""(?i)(?:season|sezon)[-_/ ]?(\d{1,2})[-_/ .]*(?:episode|episod|ep|bolum|bölüm)[-_/ .]*(\d{1,3})"""),
            Regex("""(?i)(?:^|[/_. -])s(\d{1,2})e(\d{1,3})(?:[/_. -]|$)"""),
            Regex("""(?i)(\d{1,2})\s*\.?\s*sezon\s*(\d{1,3})\s*\.?\s*bölüm""")
        )
        val episodeOnly = Regex(
            """(?i)(?:(\d{1,2})\s*\.?\s*sezon\s*)?(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode|ep)\b"""
        )
        val pageSeason = Regex("""(?i)(?:^|[-/ ])(\d{1,2})\s*\.?\s*(?:sezon|season)(?:[-/ ]|$)""")
            .find("$currentUrl $title")?.groupValues?.getOrNull(1)?.toIntOrNull()

        for (link in document.select("a[href], [data-episode-url], [data-episode-number]")) {
            val rawHref = link.attr("href")
                .ifBlank { link.attr("data-episode-url") }
                .ifBlank { link.attr("data-url") }
            val href = fixUrl(rawHref, currentUrl) ?: continue
            if (href.trimEnd('/') == currentUrl.trimEnd('/')) continue

            val text = link.attr("aria-label")
                .ifBlank { link.attr("title") }
                .ifBlank { link.text() }
                .trim()
            val combined = "$href $text"
            var season: Int? = null
            var episodeNumber: Int? = null

            for (pattern in patterns) {
                val match = pattern.find(combined) ?: continue
                season = match.groupValues.getOrNull(1)?.toIntOrNull()
                episodeNumber = match.groupValues.getOrNull(2)?.toIntOrNull()
                if (season != null && episodeNumber != null) break
            }

            if (episodeNumber == null) {
                val dataSeason = link.attr("data-season").toIntOrNull()
                val dataEpisode = link.attr("data-episode-number")
                    .ifBlank { link.attr("data-episode") }
                    .toIntOrNull()
                if (dataEpisode != null) {
                    season = dataSeason ?: pageSeason
                    episodeNumber = dataEpisode
                }
            }

            if (episodeNumber == null) {
                val label = episodeOnly.find(text)
                val parsedEpisode = label?.groupValues?.getOrNull(2)?.toIntOrNull()
                if (parsedEpisode != null) {
                    episodeNumber = parsedEpisode
                    season = label.groupValues.getOrNull(1)?.toIntOrNull() ?: pageSeason
                }
            }

            if (season == null || episodeNumber == null || season < 1 || episodeNumber < 1) continue
            if (text.equals("DUAL", true) || text.equals("FID", true) ||
                text.contains("fragman", true) || text.contains("trailer", true)
            ) continue

            val episodeName = cleanTitle(text).ifBlank { "$episodeNumber. Bölüm" }
            episodes.putIfAbsent(href, newEpisode(href) {
                this.name = episodeName
                this.season = season
                this.episode = episodeNumber
            })
        }

        // Gerçek bölüm bağlantısı yoksa sahte bir "1. Bölüm" oluşturma.
        return episodes.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
    }

    private fun applyMetadata(response: LoadResponse, document: Document) {
        parseGenres(document).takeIf { it.isNotEmpty() }?.let { response.tags = it }
        parseScore(document)?.let { response.score = it }
        parseActors(document)?.let { response.actors = it }
        parseImdbId(document)?.let { imdbId ->
            response.syncData["imdb_id"] = imdbId
            response.syncData["imdb"] = imdbId
        }

        val trailer = document.select("a[href], iframe[src]").firstOrNull {
            val url = it.attr("href").ifBlank { it.attr("src") }
            val text = it.text()
            (text.contains("fragman", true) || text.contains("trailer", true)) &&
                (url.contains("youtube", true) || url.contains("youtu.be", true) ||
                    url.contains("vimeo", true) || url.contains("trailer", true))
        }?.let { fixUrl(it.attr("href").ifBlank { it.attr("src") }) }

        if (!trailer.isNullOrBlank()) {
            response.trailers.clear()
            response.trailers += TrailerData(extractorUrl = trailer, referer = null, raw = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = requestHeaders, referer = mainUrl)
        if (!response.isSuccessful) return null

        val document = response.document
        if (isAdultContent(document, url)) return null
        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrl(it, url) }
            ?: imageFrom(document.selectFirst("img"))
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .plot, .movie-description, .entry-content")?.text()?.trim()
        val year = parseYear(document)
        val genres = parseGenres(document)
        val episodes = parseEpisodes(document, url, title)
        val isSeries = hasSeriesCategory(document) ||
            looksLikeSeries(url, title, genres) ||
            document.select("a[href], [data-episode-url], [data-episode-number]").any { link ->
                Regex("""(?i)\d+[\s.-]*sezon[\s-]*\d+[\s.-]*bölüm""")
                    .containsMatchIn(link.attr("href") + " " + link.text()) ||
                    Regex("""(?i)(?:^|[/_. -])s\d{1,2}e\d{1,3}(?:[/_. -]|$)""")
                        .containsMatchIn(link.attr("href"))
            }

        if (isSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                applyMetadata(this, document)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            applyMetadata(this, document)
        }
    }

    private fun sameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "sinezy.si" || host.endsWith(".sinezy.si") ||
            host == "sinezy.to" || host.endsWith(".sinezy.to")
    }

    private fun isDirectMedia(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4|webm|mpd)(?:[?#].*)?$""").containsMatchIn(url)
    }

    private fun looksLikePlayerUrl(url: String): Boolean {
        val low = url.lowercase()
        return isDirectMedia(url) ||
            listOf("embed", "player", "stream", "video", "watch", "iframe", "vr_set", "fid")
                .any { low.contains(it) }
    }

    private fun addCandidate(output: MutableSet<String>, raw: String?, base: String, force: Boolean = false) {
        val candidate = fixUrl(raw, base) ?: return
        val low = candidate.lowercase()
        if (low.contains("google-analytics") || low.contains("doubleclick") ||
            low.contains("facebook.com") || low.contains("twitter.com") ||
            Regex("""\.(css|js|png|jpe?g|gif|webp|svg|woff2?|ttf|ico)(?:[?#].*)?$""")
                .containsMatchIn(low)
        ) return
        if (candidate.trimEnd('/') == base.trimEnd('/') && !candidate.contains("vr_set=", true)) return
        if (force || looksLikePlayerUrl(candidate)) output.add(candidate)
    }

    private fun collectCandidates(document: Document, html: String, base: String): LinkedHashSet<String> {
        val output = LinkedHashSet<String>()
        val attrs = listOf(
            "src", "data-src", "data-lazy-src", "data-original", "data-url",
            "data-embed", "data-iframe", "data-iframe-src", "data-player",
            "data-video", "data-link", "data-href"
        )

        for (element in document.select(
            "iframe, video, source, embed, [data-src], [data-url], [data-embed], [data-iframe], [data-player], [data-video]"
        )) {
            for (attr in attrs) {
                if (element.hasAttr(attr)) addCandidate(output, element.attr(attr), base, force = true)
            }
        }

        for (link in document.select("a[href]")) {
            val text = link.text().trim()
            val hints = listOf("dual", "fid", "player", "server", "kaynak", "video", "izle")
            if (hints.any { text.contains(it, true) } ||
                link.className().contains("player", true) ||
                link.className().contains("server", true) ||
                link.attr("href").contains("vr_set", true) ||
                looksLikePlayerUrl(link.attr("href"))
            ) {
                addCandidate(output, link.attr("href"), base, force = true)
            }
        }

        val decodedHtml = decode(html)
        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^"'<>\\\s]*)?""")
            .findAll(decodedHtml).forEach { addCandidate(output, it.value, base, force = true) }

        val scripts = document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val decodedScripts = decode(scripts)

        // Sinezy player HTML'sini Base64 ile "ilkpartkod" değişkenine gömebiliyor.
        // Önce bu blokları çöz, ardından iframe/video kaynaklarını normal aday listesine ekle.
        val encodedBlocks = Regex(
            """(?is)\b(?:ilkpartkod|ikinciPartKod|ikinci_part_kod|ikinciPartkod)\s*=\s*(['"])([A-Za-z0-9+/_=-]{16,})\1"""
        ).findAll("$html\n$decodedHtml\n$decodedScripts")
        val embeddedAttrs = listOf(
            "src", "data-src", "data-url", "data-embed", "data-iframe",
            "data-iframe-src", "data-player", "data-video", "data-link", "data-href"
        )
        for (block in encodedBlocks) {
            val payload = decodeBase64Payload(block.groupValues[2]) ?: continue
            val payloadDocument = Jsoup.parse(payload, base)
            for (element in payloadDocument.select(
                "iframe[src], iframe[data-src], video[src], source[src], embed[src], " +
                    "[data-src], [data-url], [data-embed], [data-player], [data-video]"
            )) {
                for (attr in embeddedAttrs) {
                    if (element.hasAttr(attr)) {
                        addCandidate(output, element.attr(attr), base, force = true)
                    }
                }
            }

            Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^"'<>\\\s]*)?""")
                .findAll(decode(payload)).forEach {
                    addCandidate(output, it.value, base, force = true)
                }
            Regex("""(?i)(?:src|file|url|iframe|embed|player|stream|video)\s*["']?\s*[:=]\s*["']([^"']{5,800})["']""")
                .findAll(decode(payload)).forEach {
                    addCandidate(output, it.groupValues[1], base, force = true)
                }
        }

        Regex("""(?i)(?:src|file|url|iframe|embed|player|stream|video)\s*["']?\s*[:=]\s*["']([^"']{5,800})["']""")
            .findAll(decodedScripts).forEach { addCandidate(output, it.groupValues[1], base) }
        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]{6,500}""")
            .findAll(decodedScripts).forEach { match ->
                val candidate = match.value.trimEnd(')', ';', ',', '\\')
                if (looksLikePlayerUrl(candidate)) addCandidate(output, candidate, base)
            }

        return output
    }

    private suspend fun publishDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mp4", true) || url.contains(".webm", true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
        callback(
            newExtractorLink(source = name, name = name, url = url, type = type) {
                this.referer = referer
                this.quality = Regex("""(?i)(2160|1440|1080|720|480|360)""")
                    .find(url)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                this.headers = requestHeaders + ("Referer" to referer)
            }
        )
    }

    private suspend fun publishSubtitles(document: Document, html: String, base: String, callback: (SubtitleFile) -> Unit) {
        val seen = HashSet<String>()
        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val url = fixUrl(raw, base) ?: continue
            if (!url.contains(".vtt", true) && !url.contains(".srt", true) && !url.contains(".ass", true)) continue
            if (!seen.add(url)) continue
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            callback(newSubtitleFile(label, url))
        }

        val decoded = decode(html)
        Regex("""(?i)(https?:)?//[^"'<>\\\s]+?\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?""")
            .findAll(decoded).forEach { match ->
                val url = fixUrl(match.value, base) ?: return@forEach
                if (seen.add(url)) callback(newSubtitleFile("Altyazı", url))
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = runCatching {
            app.get(data, headers = requestHeaders + ("Referer" to mainUrl), referer = mainUrl)
        }.getOrNull() ?: return false
        if (!pageResponse.isSuccessful) return false
        // Adult-only pages are deliberately not resolved by this provider.
        if (isAdultContent(pageResponse.document, data)) return false

        val visited = HashSet<String>()
        var found = false
        val countingCallback: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        suspend fun resolveCandidate(candidate: String, referer: String, depth: Int) {
            if (!visited.add(candidate) || depth > 2) return

            if (isDirectMedia(candidate)) {
                publishDirect(candidate, referer, countingCallback)
                return
            }

            if (sameSite(candidate)) {
                if (depth >= 2) return
                val nested = runCatching {
                    app.get(candidate, headers = requestHeaders + ("Referer" to referer), referer = referer)
                }.getOrNull() ?: return
                if (!nested.isSuccessful) return
                publishSubtitles(nested.document, nested.text, candidate, subtitleCallback)
                val nestedCandidates = collectCandidates(nested.document, nested.text, candidate)
                for (next in nestedCandidates) resolveCandidate(next, candidate, depth + 1)
            } else {
                runCatching {
                    loadExtractor(candidate, referer, subtitleCallback, countingCallback)
                }
            }
        }

        publishSubtitles(pageResponse.document, pageResponse.text, data, subtitleCallback)
        val candidates = collectCandidates(pageResponse.document, pageResponse.text, data)
        for (candidate in candidates) resolveCandidate(candidate, data, 0)

        return found
    }
}
