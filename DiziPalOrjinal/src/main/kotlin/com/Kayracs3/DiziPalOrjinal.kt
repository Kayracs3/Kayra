package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziPalOrjinal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal18.com"
    override var name = "DiziPalOrjinal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/bolumler" to "Son Bölümler",
        "$mainUrl/populer" to "Popüler",
        "$mainUrl/imdb-en-iyiler" to "IMDb En İyiler",
        "$mainUrl/diziler?sort=popular" to "Popüler Diziler",
        "$mainUrl/filmler?sort=popular" to "Popüler Filmler",
        "$mainUrl/tur/aile" to "Aile",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/aksiyon-macera" to "Aksiyon & Macera",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/belgesel" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gerceklik" to "Gerçeklik",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/muzik" to "Müzik",
        "$mainUrl/tur/pembe-dizi" to "Pembe Dizi",
        "$mainUrl/tur/romantik" to "Romantik",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val address = pageUrl(request.data, page)
        val document = getDocument(address)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = parseCatalog(document, address)
        val next = hasNextPage(document, page)

        Log.d(name, "Katalog " + request.name + ": " + results.size + " kayıt")
        return newHomePageResponse(request.name, results, hasNext = next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()

        val encoded = URLEncoder.encode(value, "UTF-8")
        val addresses = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/ara?search=$encoded",
        )

        for (address in addresses) {
            val document = getDocument(address) ?: continue
            val results = parseCatalog(document, address)
                .filter {
                    it.name.contains(value, true) ||
                        normalize(it.name).contains(normalize(value))
                }
                .distinctBy { canonical(it.url) }

            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val address = absolute(url, mainUrl)
        if (!isDetailUrl(address)) return null

        val document = getDocument(address) ?: return null
        val title = pageTitle(document, address) ?: return null
        val poster = pagePoster(document, address)
        val plot = pagePlot(document)
        val year = pageYear(document, title)
        val rating = pageRating(document)
        val genres = pageGenres(document)
        if (isAdultContent(title + " " + genres.joinToString(" "))) return null
        val actors = pageActors(document, address)

        if (isMovie(address) ||
            document.selectFirst("meta[property=og:type][content*=movie]") != null
        ) {
            return newMovieLoadResponse(title, address, TvType.Movie, address) {
                posterUrl = poster
                posterHeaders = headers
                this.plot = plot
                this.year = year
                this.tags = genres
                if (actors.isNotEmpty()) addActors(actors)
                rating?.let { score = Score.from10(it) }
            }
        }

        val episodes = parseAllSeasonEpisodes(document, address, poster)
        Log.d(
            name,
            "Dizi detayı: " + title +
                ", oyuncu=" + actors.size +
                ", sezon=" + episodes.mapNotNull { it.season }.distinct().size +
                ", bölüm=" + episodes.size,
        )

        return newTvSeriesLoadResponse(title, address, TvType.TvSeries, episodes) {
            posterUrl = poster
            posterHeaders = headers
            this.plot = plot
            this.year = year
            this.tags = genres
            if (actors.isNotEmpty()) addActors(actors)
            rating?.let { score = Score.from10(it) }
        }
    }

    // This module currently exposes catalog metadata only. It does not resolve
    // third-party playback sources whose distribution rights have not been verified.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name, "Oynatma kaynağı eklenmedi; katalog modu.")
        return false
    }

    private suspend fun parseAllSeasonEpisodes(
        detail: Document,
        seriesUrl: String,
        fallbackPoster: String?,
    ): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()

        parseEpisodeLinks(detail, seriesUrl, fallbackPoster, null)
            .forEach { byUrl[canonical(it.data)] = it }

        val seasons = parseSeasonLinks(detail, seriesUrl)
        var loaded = 0

        for ((index, season) in seasons.withIndex()) {
            if (loaded >= 40) break

            val seasonUrl = season.first
            val seasonNumber = season.second ?: index + 1
            val seasonDocument = getDocument(seasonUrl, seriesUrl) ?: continue

            parseEpisodeLinks(
                seasonDocument,
                seasonUrl,
                fallbackPoster,
                seasonNumber,
            ).forEach { episode ->
                val key = canonical(episode.data)
                val old = byUrl[key]

                if (old == null ||
                    (old.season == null && episode.season != null) ||
                    (old.episode == null && episode.episode != null) ||
                    (old.posterUrl.isNullOrBlank() && !episode.posterUrl.isNullOrBlank())
                ) {
                    byUrl[key] = episode
                }
            }

            loaded++
        }

        val result = byUrl.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name.orEmpty() },
        )

        Log.d(
            name,
            "Sezon taraması: bulunan=" + seasons.size +
                ", okunan=" + loaded + ", bölüm=" + result.size,
        )
        return result
    }

    private fun parseSeasonLinks(
        document: Document,
        baseUrl: String,
    ): List<Pair<String, Int?>> {
        val found = LinkedHashMap<String, Int?>()

        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue

            val url = absolute(href, baseUrl).substringBefore("#")
            val path = pathOf(url).lowercase()
            val label = link.text() + " " + link.attr("title")
            val isSeasonPath = path.contains("/sezon/") ||
                Regex("""(?i)/(?:season|sezon)[-_]?\d+(?:/|$)""").containsMatchIn(path)

            if (!isSeasonPath || canonical(url) == canonical(baseUrl)) continue
            if (!url.startsWith(mainUrl, true)) continue

            found.putIfAbsent(url, seasonNumber(label + " " + url))
        }

        return found.map { it.key to it.value }
    }

    private fun parseEpisodeLinks(
        document: Document,
        baseUrl: String,
        fallbackPoster: String?,
        defaultSeason: Int?,
    ): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue

            val url = absolute(href, baseUrl).substringBefore("#")
            if (!url.startsWith(mainUrl, true)) continue

            val label = listOf(
                link.text(),
                link.attr("title"),
                link.attr("aria-label"),
                link.selectFirst("img")?.attr("alt").orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" ")

            val context = episodeContext(link)
            val numbers = readEpisodeNumbers(label)
                ?: readEpisodeNumbers(context)
                ?: readEpisodeNumbers(url)

            val path = pathOf(url).lowercase()
            val routeLooksLikeEpisode =
                path.startsWith("/bolum/") ||
                    path.startsWith("/episode/") ||
                    path.startsWith("/episodes/") ||
                    Regex("""(?i)(?:^|[-/])\d+-sezon-\d+-bolum(?:[-/]|$)""").containsMatchIn(path) ||
                    Regex("""(?i)(?:^|[-/])s\d{1,2}e\d{1,3}(?:[-/]|$)""").containsMatchIn(path)

            val labelLooksLikeEpisode =
                Regex("""(?i)\d+\s*\.?\s*sezon.{0,80}\d+\s*\.?\s*bölüm""").containsMatchIn(label) ||
                    Regex("""(?i)\d+\s*\.?\s*sezon.{0,80}\d+\s*\.?\s*bölüm""").containsMatchIn(context)

            if (!routeLooksLikeEpisode && !labelLooksLikeEpisode) continue
            if (canonical(url) == canonical(baseUrl)) continue

            val season = numbers?.first ?: defaultSeason
            val episodeNumber = numbers?.second ?: readEpisodeNumber(label + " " + context + " " + url)

            val rawName = listOf(
                link.attr("title"),
                link.attr("aria-label"),
                link.text(),
                link.selectFirst("img")?.attr("alt").orEmpty(),
            ).firstOrNull { it.isNotBlank() } ?: pathOf(url).substringAfterLast("/")

            val episodeName = cleanEpisodeName(rawName).ifBlank {
                if (episodeNumber != null) "Bölüm " + episodeNumber else "Bölüm"
            }
            if (isAdultContent(episodeName)) continue

            val episodePoster = imageFrom(link, baseUrl) ?: fallbackPoster
            val episode = newEpisode(url) {
                name = episodeName
                this.season = season
                this.episode = episodeNumber
                posterUrl = episodePoster
            }

            found.putIfAbsent(canonical(url), episode)
        }

        return found.values.toList()
    }

    private fun episodeContext(link: Element): String {
        var current: Element? = link
        repeat(5) {
            val item = current ?: return ""
            val text = item.text().replace(Regex("""\s+"""), " ").trim()
            if (text.contains("Bölüm", true) || text.contains("Episode", true)) {
                return text.take(350)
            }
            current = item.parent()
        }
        return link.parent()?.text()?.take(350).orEmpty()
    }

    private fun readEpisodeNumbers(value: String): Pair<Int, Int>? {
        val text = value
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")

        val seasonEpisode = Regex(
            """(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\s*(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode)\b""",
        ).find(text)

        if (seasonEpisode != null) {
            val season = seasonEpisode.groupValues[1].toIntOrNull()
            val episode = seasonEpisode.groupValues[2].toIntOrNull()
            if (season != null && episode != null &&
                season in 1..100 && episode in 1..999
            ) return season to episode
        }

        val sxe = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""").find(text)
        if (sxe != null) {
            val season = sxe.groupValues[1].toIntOrNull()
            val episode = sxe.groupValues[2].toIntOrNull()
            if (season != null && episode != null) return season to episode
        }

        val x = Regex("""\b(\d{1,2})\s*x\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
        if (x != null) {
            val season = x.groupValues[1].toIntOrNull()
            val episode = x.groupValues[2].toIntOrNull()
            if (season != null && episode != null) return season to episode
        }

        return null
    }

    private fun readEpisodeNumber(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\b(?:bölüm|bolum|episode|ep)\s*[#.:_-]*\s*(\d{1,3})\b"""),
            Regex("""(?i)\bS\d{1,2}\s*E(\d{1,3})\b"""),
            Regex("""(?i)\b\d{1,2}\s*x\s*(\d{1,3})\b"""),
        )

        return patterns.asSequence()
            .mapNotNull { it.find(value)?.groupValues?.lastOrNull()?.toIntOrNull() }
            .firstOrNull { it in 1..999 }
    }

    private fun seasonNumber(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\b"""),
            Regex("""(?i)/(?:season|sezon)[-_]?(\d{1,2})(?:/|$)"""),
        )

        return patterns.asSequence()
            .mapNotNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in 1..100 }
    }

    private fun cleanEpisodeName(value: String): String {
        return value
            .replace(Regex("""(?i)^\s*\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bölüm\s*"""), "")
            .replace(Regex("""(?i)^\s*S\d{1,2}\s*E\d{1,3}\s*[-:–—]*\s*"""), "")
            .replace(Regex("""(?i)\s+(?:dublaj|altyazılı|altyazili|türkçe dublaj|türkçe altyazılı).*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—', ':', '|')
    }

    private fun parseCatalog(
        document: Document,
        baseUrl: String,
    ): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()

        for (link in document.select("a[href]")) {
            val url = absolute(link.attr("href"), baseUrl).substringBefore("#")
            if (!isDetailUrl(url)) continue

            val card = findCard(link)
            val poster = imageFrom(link, baseUrl) ?: imageFrom(card, baseUrl)

            val title = listOf(
                link.attr("title"),
                link.attr("aria-label"),
                link.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
                link.selectFirst("img")?.attr("alt").orEmpty(),
                link.text(),
                card.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
            ).firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: continue

            if (title.length < 2 || isGenericTitle(title)) continue
            if (isAdultContent(title + " " + card.text())) continue

            val key = canonical(url)
            val item = if (isMovie(url)) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }

            found.putIfAbsent(key, item)
        }

        return found.values.toList()
    }

    private fun findCard(link: Element): Element {
        var best = link
        var current: Element? = link

        repeat(4) {
            val item = current ?: return@repeat
            if (item.selectFirst("img") != null) best = item
            val parent = item.parent() ?: return@repeat
            if (parent.select("a[href]").size > 12) return@repeat
            current = parent
            best = parent
        }

        return best
    }

    private fun pagePoster(document: Document, baseUrl: String): String? {
        val images = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst(".poster img, .detail-poster img, .film-poster img, .series-poster img")
                ?.let(::imageAttribute),
            document.selectFirst("main img, article img")?.let(::imageAttribute),
        )

        return images.asSequence()
            .filterNotNull()
            .map { absolute(it, baseUrl) }
            .firstOrNull { isImageUrl(it) }
    }

    private fun imageFrom(element: Element?, baseUrl: String): String? {
        if (element == null) return null

        val image = if (element.tagName().equals("img", true)) {
            element
        } else {
            element.selectFirst("img")
        } ?: return null

        return absolute(imageAttribute(image), baseUrl).takeIf { isImageUrl(it) }
    }

    private fun imageAttribute(image: Element): String {
        for (attribute in listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "data-lazy",
            "src",
            "srcset",
        )) {
            val value = image.attr(attribute).trim()
            if (value.isBlank() || value.startsWith("data:", true)) continue
            return if (attribute == "srcset") {
                value.substringBefore(",").trim().substringBefore(" ")
            } else {
                value
            }
        }
        return ""
    }

    private fun isImageUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:", true)) return false
        val path = pathOf(url).lowercase()
        return Regex("""\.(?:jpe?g|png|webp|gif|avif)(?:$|[?#])""").containsMatchIn(url) ||
            path.contains("/image") ||
            path.contains("/upload") ||
            path.contains("/poster")
    }

    private fun pageTitle(document: Document, url: String): String? {
        val value = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.title(),
        ).firstOrNull { !it.isNullOrBlank() } ?: pathOf(url).substringAfterLast("/")

        return cleanTitle(value).takeIf { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
            document.selectFirst(".summary")?.text(),
            document.selectFirst("main p")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
    }

    private fun pageYear(document: Document, title: String): Int? {
        val text = title + " " +
            document.selectFirst("main")?.text().orEmpty() + " " +
            document.selectFirst("meta[name=description]")?.attr("content").orEmpty()

        return Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(text)
            ?.value
            ?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? {
        val text = document.selectFirst("main")?.text().orEmpty() + " " +
            document.selectFirst("meta[itemprop=ratingValue]")?.attr("content").orEmpty()

        val patterns = listOf(
            Regex("""(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""),
            Regex("""(?i)\b(?:puan|rating)\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""),
        )

        return patterns.asSequence()
            .mapNotNull {
                it.find(text)?.groupValues?.getOrNull(1)
                    ?.replace(',', '.')?.toDoubleOrNull()
            }
            .firstOrNull { it in 0.0..10.0 }
    }

    private fun pageGenres(document: Document): List<String> {
        val result = LinkedHashSet<String>()
        document.select("a[href*='/tur/'], .genres a, .genre a, [itemprop=genre]")
            .forEach { element ->
                val genre = cleanTitle(element.text())
                if (genre.isNotBlank() && genre.length < 40) result += genre
            }
        return result.take(20)
    }

    private fun pageActors(document: Document, baseUrl: String): List<Actor> {
        val result = LinkedHashMap<String, Actor>()
        val heading = document.select("h1,h2,h3,h4").firstOrNull {
            normalize(it.text()).startsWith("oyuncular") ||
                normalize(it.text()).startsWith("cast")
        }

        if (heading != null) {
            var section: Element? = heading.parent()
            repeat(5) {
                val current = section ?: return@repeat
                current.select("a[href], .actor, .cast-item, .person").forEach { item ->
                    val actorName = cleanTitle(
                        item.text().ifBlank {
                            item.attr("title").ifBlank {
                                item.selectFirst("img")?.attr("alt").orEmpty()
                            }
                        },
                    )
                    if (actorName.isBlank() || actorName.length > 70) return@forEach

                    result.putIfAbsent(
                        normalize(actorName),
                        Actor(actorName, imageFrom(item, baseUrl)),
                    )
                }
                if (result.size >= 40) return@repeat
                section = current.parent()
            }
        }

        if (result.isEmpty()) {
            document.select("a[href*='/oyuncu/'], a[href*='/actor/']").forEach { item ->
                val actorName = cleanTitle(item.text().ifBlank { item.attr("title") })
                if (actorName.isNotBlank() && actorName.length <= 70) {
                    result.putIfAbsent(
                        normalize(actorName),
                        Actor(actorName, imageFrom(item, baseUrl)),
                    )
                }
            }
        }

        return result.values.take(40)
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("""\s+"""), " ")
            .replace(
                Regex("""(?i)\s*[|–-]\s*(?:dizipal|yabancı dizi izle|film izle).*$"""),
                "",
            )
            .trim()
    }

    private fun isGenericTitle(value: String): Boolean {
        return normalize(value) in setOf(
            "izle", "detaylar", "detay", "tumunu gor",
            "tum bolumleri", "fragman", "oynat", "dizipal", "ana sayfa",
        )
    }

    private fun isAdultContent(value: String): Boolean {
        val terms = normalize(value).split(" ").toSet()
        val blocked = setOf(
            "porn", "porno", "hentai", "erotik", "erotic",
            "xxx", "adult", "yetiskin", "seks", "sex",
        )
        return terms.any { it in blocked } ||
            Regex("""(?i)18\s*\+|18\s*yaş|18\s*yas""").containsMatchIn(value)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace('ı', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun isDetailUrl(url: String): Boolean {
        val parts = pathOf(url).trimEnd('/').lowercase()
            .split("/")
            .filter { it.isNotBlank() }

        return parts.size >= 2 && parts.first() in setOf(
            "diziler", "filmler", "dizi", "film", "movies", "series",
        )
    }

    private fun isMovie(url: String): Boolean {
        val path = pathOf(url).lowercase()
        return path.startsWith("/filmler/") ||
            path.startsWith("/film/") ||
            path.startsWith("/movies/")
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (page >= 30) return false

        val hasNext = document.select(
            "a[rel=next], .pagination a.next, a[aria-label*=Sonraki i], a:contains(Sonraki)",
        ).any { it.attr("href").isNotBlank() }

        if (hasNext) return true

        return document.select(".pagination a, nav[aria-label*=pagination i] a")
            .any { it.text().trim() == (page + 1).toString() }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) {
            base + "&page=" + page
        } else {
            base.trimEnd('/') + "?page=" + page
        }
    }

    private suspend fun getDocument(
        url: String,
        referer: String = "$mainUrl/",
    ): Document? {
        return try {
            val response = app.get(
                url,
                headers = headers + ("Referer" to referer),
                referer = referer,
                timeout = 15000,
                allowRedirects = true,
            )
            if (response.isSuccessful) response.document else null
        } catch (e: Exception) {
            Log.d(name, "Sayfa isteği başarısız: " + url + "; " + e.message)
            null
        }
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim('"', '\'')

        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }
        if (value.startsWith("//")) return "https:" + value

        return runCatching { URI(base).resolve(value).toString() }
            .getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun canonical(url: String): String {
        return absolute(url, mainUrl)
            .substringBefore("#")
            .substringBefore("?")
            .trimEnd('/')
            .lowercase()
    }

    private fun pathOf(url: String): String {
        return runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }
            .getOrDefault("")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }
}
