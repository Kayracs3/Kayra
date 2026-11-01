package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOrjinal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal18.com"
    override var name = "DiziPalOrjinal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private const val AES_KEY = "T8r9Wq3mZ1kL5vYb"
        private const val AES_IV = "N2pR7sX4cV6bM1qF"
    }

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
        "$mainUrl/populer" to "Popüler",
        "$mainUrl/imdb-en-iyiler" to "IMDb En İyiler",
        "$mainUrl/diziler?sort=newest" to "Yeni Diziler",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/aksiyon-macera" to "Aksiyon & Macera",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/romantik" to "Romantik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        val apiUrl = "$mainUrl/api/v1/search?q=$encoded&per_page=20"

        val response = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/",
                ),
                referer = "$mainUrl/",
                timeout = 15000,
                allowRedirects = true,
            )
        }.getOrNull()

        if (response == null || !response.isSuccessful) {
            Log.e(name, "Arama API başarısız: ${response?.code}")
            return emptyList()
        }

        val json = runCatching { org.json.JSONObject(response.text) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
            val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val poster = item.optString("poster_url").replace("\\/", "/").takeIf { it.isNotBlank() }
            val type = item.optString("type", "series")

            val url = if (type == "movie") "$mainUrl/filmler/$slug" else "$mainUrl/diziler/$slug"

            val searchResponse = if (type == "movie") {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            }
            results.add(searchResponse)
        }

        Log.d(name, "Arama API: '${value}' için ${results.size} sonuç")
        return results
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

        if (isMovie(address) || document.selectFirst("meta[property=og:type][content*=movie]") != null) {
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
        Log.d(name, "Dizi detayı: " + title + ", oyuncu=" + actors.size + ", sezon=" + episodes.mapNotNull { it.season }.distinct().size + ", bölüm=" + episodes.size + ", yıl=" + year + ", puan=" + rating)
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

    private suspend fun parseAllSeasonEpisodes(detail: Document, seriesUrl: String, fallbackPoster: String?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        parseEpisodeLinks(detail, seriesUrl, fallbackPoster, null).forEach { byUrl[canonical(it.data)] = it }
        val seasons = parseSeasonLinks(detail, seriesUrl)
        var loaded = 0
        for ((index, season) in seasons.withIndex()) {
            if (loaded >= 40) break
            val seasonUrl = season.first
            val seasonNumber = season.second ?: index + 1
            val seasonDocument = getDocument(seasonUrl, seriesUrl) ?: continue
            parseEpisodeLinks(seasonDocument, seasonUrl, fallbackPoster, seasonNumber).forEach { episode ->
                val key = canonical(episode.data)
                val old = byUrl[key]
                if (old == null || (old.season == null && episode.season != null) || (old.episode == null && episode.episode != null) || (old.posterUrl.isNullOrBlank() && !episode.posterUrl.isNullOrBlank())) {
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
        Log.d(name, "Sezon taraması: bulunan=" + seasons.size + ", okunan=" + loaded + ", bölüm=" + result.size)
        return result
    }

    private fun parseSeasonLinks(document: Document, baseUrl: String): List<Pair<String, Int?>> {
        val found = LinkedHashMap<String, Int?>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue
            val url = absolute(href, baseUrl).substringBefore("#")
            val path = pathOf(url).lowercase()
            val label = link.text() + " " + link.attr("title")
            val isSeasonPath = path.contains("/sezon/") || Regex("""(?i)/(?:season|sezon)[-_]?\d+(?:/|$)""").containsMatchIn(path)
            if (!isSeasonPath || canonical(url) == canonical(baseUrl)) continue
            if (!url.startsWith(mainUrl, true)) continue
            found.putIfAbsent(url, seasonNumber(label + " " + url))
        }
        return found.map { it.key to it.value }
    }

    private fun parseEpisodeLinks(document: Document, baseUrl: String, fallbackPoster: String?, defaultSeason: Int?): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue
            val url = absolute(href, baseUrl).substringBefore("#")
            if (!url.startsWith(mainUrl, true)) continue
            val label = listOf(link.text(), link.attr("title"), link.attr("aria-label"), link.selectFirst("img")?.attr("alt").orEmpty())
                .filter { it.isNotBlank() }.joinToString(" ")
            val context = episodeContext(link)
            val numbers = readEpisodeNumbers(label) ?: readEpisodeNumbers(context) ?: readEpisodeNumbers(url)
            val path = pathOf(url).lowercase()
            val routeLooksLikeEpisode = path.startsWith("/bolum/") || path.startsWith("/episode/") || path.startsWith("/episodes/") || Regex("""(?i)(?:^|[-/])\d+-sezon-\d+-bolum(?:[-/]|$)""").containsMatchIn(path) || Regex("""(?i)(?:^|[-/])s\d{1,2}e\d{1,3}(?:[-/]|$)""").containsMatchIn(path)
            val labelLooksLikeEpisode = Regex("""(?i)\d+\s*\.?\s*sezon.{0,80}\d+\s*\.?\s*bölüm""").containsMatchIn(label) || Regex("""(?i)\d+\s*\.?\s*sezon.{0,80}\d+\s*\.?\s*bölüm""").containsMatchIn(context)
            if (!routeLooksLikeEpisode && !labelLooksLikeEpisode) continue
            if (canonical(url) == canonical(baseUrl)) continue
            val season = numbers?.first ?: defaultSeason
            val episodeNumber = numbers?.second ?: readEpisodeNumber(label + " " + context + " " + url)
            val rawName = listOf(link.attr("title"), link.attr("aria-label"), link.text(), link.selectFirst("img")?.attr("alt").orEmpty())
                .firstOrNull { it.isNotBlank() } ?: pathOf(url).substringAfterLast("/")
            val episodeName = cleanEpisodeName(rawName).ifBlank { if (episodeNumber != null) "Bölüm " + episodeNumber else "Bölüm" }
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
            if (text.contains("Bölüm", true) || text.contains("Episode", true)) return text.take(350)
            current = item.parent()
        }
        return link.parent()?.text()?.take(350).orEmpty()
    }

    private fun readEpisodeNumbers(value: String): Pair<Int, Int>? {
        val text = value.replace("_", " ").replace("-", " ").replace(Regex("""\s+"""), " ")
        val seasonEpisode = Regex("""(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\s*(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode)\b""").find(text)
        if (seasonEpisode != null) {
            val s = seasonEpisode.groupValues[1].toIntOrNull()
            val e = seasonEpisode.groupValues[2].toIntOrNull()
            if (s != null && e != null && s in 1..100 && e in 1..999) return s to e
        }
        val sxe = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""").find(text)
        if (sxe != null) {
            val s = sxe.groupValues[1].toIntOrNull()
            val e = sxe.groupValues[2].toIntOrNull()
            if (s != null && e != null) return s to e
        }
        val x = Regex("""\b(\d{1,2})\s*x\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
        if (x != null) {
            val s = x.groupValues[1].toIntOrNull()
            val e = x.groupValues[2].toIntOrNull()
            if (s != null && e != null) return s to e
        }
        return null
    }

    private fun readEpisodeNumber(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\b(?:bölüm|bolum|episode|ep)\s*[#.:_-]*\s*(\d{1,3})\b"""),
            Regex("""(?i)\bS\d{1,2}\s*E(\d{1,3})\b"""),
            Regex("""(?i)\b\d{1,2}\s*x\s*(\d{1,3})\b"""),
        )
        return patterns.asSequence().mapNotNull { it.find(value)?.groupValues?.lastOrNull()?.toIntOrNull() }.firstOrNull { it in 1..999 }
    }

    private fun seasonNumber(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\b"""),
            Regex("""(?i)/(?:season|sezon)[-_]?(\d{1,2})(?:/|$)"""),
        )
        return patterns.asSequence().mapNotNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }.firstOrNull { it in 1..100 }
    }

    private fun cleanEpisodeName(value: String): String = value
        .replace(Regex("""(?i)^\s*\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bölüm\s*"""), "")
        .replace(Regex("""(?i)^\s*S\d{1,2}\s*E\d{1,3}\s*[-:–—]*\s*"""), "")
        .replace(Regex("""(?i)\s+(?:dublaj|altyazılı|altyazili|türkçe dublaj|türkçe altyazılı).*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '–', '—', ':', '|')

    private fun parseCatalog(document: Document, baseUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in document.select("a[href]")) {
            val url = absolute(link.attr("href"), baseUrl).substringBefore("#")
            if (!isDetailUrl(url)) continue
            val card = findCard(link)
            val poster = imageFrom(link, baseUrl) ?: imageFrom(card, baseUrl)
            val title = listOf(
                link.attr("title"), link.attr("aria-label"),
                link.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
                link.selectFirst("img")?.attr("alt").orEmpty(),
                link.text(),
                card.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
            ).firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: continue
            if (title.length < 2 || isGenericTitle(title)) continue
            if (isAdultContent(title + " " + card.text())) continue
            val key = canonical(url)
            val item = if (isMovie(url)) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
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
            document.selectFirst(".poster img, .detail-poster img, .film-poster img, .series-poster img")?.let(::imageAttribute),
            document.selectFirst("main img, article img")?.let(::imageAttribute),
        )
        return images.asSequence().filterNotNull().map { absolute(it, baseUrl) }.firstOrNull { isImageUrl(it) }
    }

    private fun imageFrom(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val image = if (element.tagName().equals("img", true)) element else element.selectFirst("img") ?: return null
        return absolute(imageAttribute(image), baseUrl).takeIf { isImageUrl(it) }
    }

    private fun imageAttribute(image: Element): String {
        for (attribute in listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-lazy", "src", "srcset")) {
            val value = image.attr(attribute).trim()
            if (value.isBlank() || value.startsWith("data:", true)) continue
            return if (attribute == "srcset") value.substringBefore(",").trim().substringBefore(" ") else value
        }
        return ""
    }

    private fun isImageUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:", true)) return false
        val path = pathOf(url).lowercase()
        return Regex("""\.(?:jpe?g|png|webp|gif|avif)(?:$|[?#])""").containsMatchIn(url) || path.contains("/image") || path.contains("/upload") || path.contains("/poster")
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

    private fun pagePlot(document: Document): String? = listOf(
        document.selectFirst("meta[property=og:description]")?.attr("content"),
        document.selectFirst("meta[name=description]")?.attr("content"),
        document.selectFirst(".description")?.text(),
        document.selectFirst(".plot")?.text(),
        document.selectFirst(".summary")?.text(),
        document.selectFirst("main p")?.text(),
    ).firstOrNull { !it.isNullOrBlank() }?.replace(Regex("""\s+"""), " ")?.trim()

    // ★★★ YENİ: Yapım yılı — JSON-LD → meta → görünür etiket → başlık ★★★
    private fun pageYear(document: Document, title: String): Int? {
        // 1) JSON-LD datePublished
        for (script in document.select("script[type=application/ld+json]")) {
            val text = script.data()
            if (text.isBlank()) continue
            val m = Regex(""""datePublished"\s*:\s*"(\d{4})""", RegexOption.IGNORE_CASE).find(text)
            if (m != null) {
                val y = m.groupValues[1].toIntOrNull()
                if (y != null && y in 1900..2100) {
                    Log.d(name, "Yıl (JSON-LD): $y")
                    return y
                }
            }
        }
        // 2) Meta etiketleri
        for (key in listOf("og:release_date", "release_date", "og:year", "year", "date")) {
            val content = document.selectFirst("meta[property=$key], meta[name=$key]")?.attr("content")
            if (content.isNullOrBlank()) continue
            val y = Regex("""(19|20)\d{2}""").find(content)?.value?.toIntOrNull()
            if (y != null) {
                Log.d(name, "Yıl (meta): $y")
                return y
            }
        }
        // 3) Görünür "Yapım Yılı: 2024" / "Yıl: 2024"
        for (el in document.select("span, div, p, li, strong, b")) {
            val t = el.text().replace(Regex("""\s+"""), " ").trim()
            if (t.length > 80) continue
            if (!Regex("""(?i)(yapım\s*yılı|yapim\s*yili|çıkış\s*yılı|cikis\s*yili|yıl|year)""").containsMatchIn(t)) continue
            val y = Regex("""(19|20)\d{2}""").find(t)?.value?.toIntOrNull()
            if (y != null) {
                Log.d(name, "Yıl (etiket): $y")
                return y
            }
        }
        // 4) Başlıktan "(2024)" veya "2024"
        val y = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        if (y != null) Log.d(name, "Yıl (başlık): $y")
        return y
    }

    // ★★★ YENİ: IMDb puanı — JSON-LD → meta → itemprop → görünür etiket ★★★
    private fun pageRating(document: Document): Double? {
        // 1) JSON-LD aggregateRating.ratingValue
        for (script in document.select("script[type=application/ld+json]")) {
            val text = script.data()
            if (text.isBlank()) continue
            val m = Regex(""""ratingValue"\s*:\s*"?(\d+(?:[.,]\d+)?)"?""", RegexOption.IGNORE_CASE).find(text)
            if (m != null) {
                val r = m.groupValues[1].replace(",", ".").toDoubleOrNull()
                if (r != null && r in 0.0..10.0) {
                    Log.d(name, "Puan (JSON-LD): $r")
                    return r
                }
            }
        }
        // 2) Meta etiketleri
        for (key in listOf("og:rating", "rating", "imdb:rating", "twitter:data1")) {
            val content = document.selectFirst("meta[property=$key], meta[name=$key]")?.attr("content")
            if (content.isNullOrBlank()) continue
            val r = Regex("""\d+(?:[.,]\d+)?""").find(content)?.value?.replace(",", ".")?.toDoubleOrNull()
            if (r != null && r in 0.0..10.0) {
                Log.d(name, "Puan (meta): $r")
                return r
            }
        }
        // 3) itemprop=ratingValue
        document.selectFirst("[itemprop=ratingValue]")?.text()?.let { text ->
            val r = Regex("""\d+(?:[.,]\d+)?""").find(text)?.value?.replace(",", ".")?.toDoubleOrNull()
            if (r != null && r in 0.0..10.0) {
                Log.d(name, "Puan (itemprop): $r")
                return r
            }
        }
        // 4) Görünür "IMDb: 8.7" / "8.7/10"
        for (el in document.select("span, div, p, strong, b, li")) {
            val t = el.text().replace(Regex("""\s+"""), " ").trim()
            if (t.length > 60) continue
            if (!Regex("""(?i)imdb|puan|rating""").containsMatchIn(t) && !Regex("""\d([.,]\d+)?\s*/\s*10""").containsMatchIn(t)) continue
            val r = Regex("""\d+(?:[.,]\d+)?""").find(t)?.value?.replace(",", ".")?.toDoubleOrNull()
            if (r != null && r in 0.0..10.0) {
                Log.d(name, "Puan (etiket): $r")
                return r
            }
        }
        return null
    }

    private fun pageGenres(document: Document): List<String> {
        val result = LinkedHashSet<String>()
        document.select("a[href*='/tur/'], .genres a, .genre a, [itemprop=genre]").forEach { element ->
            val genre = cleanTitle(element.text())
            if (genre.isNotBlank() && genre.length < 40) result += genre
        }
        return result.take(20)
    }

    private fun pageActors(document: Document, baseUrl: String): List<Actor> {
        val result = LinkedHashMap<String, Actor>()
        val heading = document.select("h1,h2,h3,h4").firstOrNull {
            normalize(it.text()).startsWith("oyuncular") || normalize(it.text()).startsWith("cast")
        }
        if (heading != null) {
            var section: Element? = heading.parent()
            repeat(5) {
                val current = section ?: return@repeat
                current.select("a[href], .actor, .cast-item, .person").forEach { item ->
                    val actorName = cleanTitle(item.text().ifBlank { item.attr("title").ifBlank { item.selectFirst("img")?.attr("alt").orEmpty() } })
                    if (actorName.isBlank() || actorName.length > 70) return@forEach
                    result.putIfAbsent(normalize(actorName), Actor(actorName, imageFrom(item, baseUrl)))
                }
                if (result.size >= 40) return@repeat
                section = current.parent()
            }
        }
        if (result.isEmpty()) {
            document.select("a[href*='/oyuncu/'], a[href*='/actor/']").forEach { item ->
                val actorName = cleanTitle(item.text().ifBlank { item.attr("title") })
                if (actorName.isNotBlank() && actorName.length <= 70) result.putIfAbsent(normalize(actorName), Actor(actorName, imageFrom(item, baseUrl)))
            }
        }
        return result.values.take(40)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""(?i)\s*[|–-]\s*(?:dizipal|yabancı dizi izle|film izle).*$"""), "")
        .trim()

    private fun isGenericTitle(value: String): Boolean = normalize(value) in setOf(
        "izle", "detaylar", "detay", "tumunu gor", "tum bolumleri", "fragman", "oynat", "dizipal", "ana sayfa",
    )

    private fun isAdultContent(value: String): Boolean {
        val terms = normalize(value).split(" ").toSet()
        val blocked = setOf("porn", "porno", "hentai", "erotik", "erotic", "xxx", "adult", "yetiskin", "seks", "sex")
        return terms.any { it in blocked } || Regex("""(?i)18\s*\+|18\s*yaş|18\s*yas""").containsMatchIn(value)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim().replace(Regex("""\s+"""), " ")

    private fun isDetailUrl(url: String): Boolean {
        val parts = pathOf(url).trimEnd('/').lowercase().split("/").filter { it.isNotBlank() }
        return parts.size >= 2 && parts.first() in setOf("diziler", "filmler", "dizi", "film", "movies", "series")
    }

    private fun isMovie(url: String): Boolean {
        val path = pathOf(url).lowercase()
        return path.startsWith("/filmler/") || path.startsWith("/film/") || path.startsWith("/movies/")
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (page >= 30) return false
        val hasNext = document.select("a[rel=next], .pagination a.next, a[aria-label*=Sonraki i], a:contains(Sonraki)")
            .any { it.attr("href").isNotBlank() }
        if (hasNext) return true
        return document.select(".pagination a, nav[aria-label*=pagination i] a").any { it.text().trim() == (page + 1).toString() }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) base + "&page=" + page else base.trimEnd('/') + "?page=" + page
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            val response = app.get(url, headers = headers + ("Referer" to referer), referer = referer, timeout = 15000, allowRedirects = true)
            if (response.isSuccessful) response.document else null
        } catch (e: Exception) {
            Log.d(name, "Sayfa isteği başarısız: " + url + "; " + e.message)
            null
        }
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/").trim('"', '\'')
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:" + value
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun canonical(url: String): String = absolute(url, mainUrl).substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun pathOf(url: String): String = runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }.getOrDefault("")

    private fun extractEncryptedConfig(text: String): String? {
        val patterns = listOf(
            Regex("""(?:const|let|var)\s+_?config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:const|let|var)\s+_?enc\w*\s*=\s*['"]([^'"]{20,})['"]""", RegexOption.IGNORE_CASE),
            Regex(""""config"\s*:\s*"([^"]{20,})"""", RegexOption.IGNORE_CASE),
            Regex("""_config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val value = m.groupValues[1]
            if (value.startsWith("enc:") || value.length > 40) return value
        }
        return null
    }

    private fun decryptDizipalConfig(encrypted: String): String {
        val clean = encrypted.removePrefix("enc:")
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
            String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)))
        }.getOrElse { runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }.getOrDefault("") }
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    } catch (_: Exception) { null }

    private fun mapLang(code: String): String {
        val c = code.lowercase().take(2)
        return when (c) {
            "tr" -> "Türkçe"
            "en" -> "English"
            "de" -> "Deutsch"
            "fr" -> "Français"
            "es" -> "Español"
            "it" -> "Italiano"
            "ru" -> "Русский"
            "ar" -> "العربية"
            else -> code
        }
    }

    private suspend fun extractVideoplaysSubtitles(
        tokenBody: String,
        finalUrl: String,
        iframeUrl: String,
        origin: String,
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        var count = 0
        Log.d(name, "videoplays.cfd: altyazı aramaya başlıyorum (videoId=$videoId)")

        val storageBaseUrl = Regex(""""storage_base_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(tokenBody)?.groupValues?.getOrNull(1)?.trimEnd('/') ?: ""
        val videoToken = Regex("""[?&]token=([^&]+)""").find(finalUrl)?.groupValues?.getOrNull(1)

        val subHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to iframeUrl,
            "Origin" to origin,
            "Accept" to "text/vtt, application/x-subrip, text/plain, */*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        suspend fun addSub(rawUrl: String?, label: String?) {
            if (rawUrl.isNullOrBlank()) return
            val clean = rawUrl.replace("\\/", "/").trim()
            if (clean.isBlank() || clean.startsWith("data:")) return

            val baseResolved = when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("/") && storageBaseUrl.isNotBlank() -> "$storageBaseUrl$clean"
                storageBaseUrl.isNotBlank() -> "$storageBaseUrl/$clean"
                else -> absolute(clean, iframeUrl)
            }

            val candidates = mutableListOf(baseResolved)
            if (!videoToken.isNullOrBlank() && !baseResolved.contains("token=", true)) {
                val sep = if (baseResolved.contains("?")) "&" else "?"
                candidates.add("$baseResolved${sep}token=" + URLEncoder.encode(videoToken, "UTF-8"))
            }

            for (candidate in candidates) {
                Log.d(name, "videoplays.cfd: altyazı denemesi → $candidate")
                val resp = runCatching {
                    app.get(candidate, headers = subHeaders, referer = iframeUrl, timeout = 10000, allowRedirects = true)
                }.getOrNull()

                if (resp == null) { Log.d(name, "videoplays.cfd: bağlantı hatası"); continue }
                if (!resp.isSuccessful) { Log.d(name, "videoplays.cfd: HTTP ${resp.code}"); continue }

                val body = resp.text
                Log.d(name, "videoplays.cfd: içerik boyut=${body.length}")

                if (body.isBlank() || body.length < 10) { continue }

                if (body.contains("#EXTM3U", true)) {
                    Regex("""[^\r\n#]+\.(?:vtt|srt|webvtt)[^\r\n]*""", RegexOption.IGNORE_CASE)
                        .findAll(body).forEach { m -> addSub(absolute(m.value.trim(), candidate), label) }
                    return
                }

                val hasCues = body.contains("-->") || body.contains("Dialogue:", true)
                if (!hasCues) { Log.d(name, "videoplays.cfd: ✗ boş altyazı"); continue }

                val lang = label?.let { mapLang(it) } ?: "Türkçe"
                subtitleCallback.invoke(newSubtitleFile(lang = lang, url = candidate))
                Log.d(name, "videoplays.cfd: ★ Altyazı eklendi: $candidate ($lang, ${body.length} char)")
                count++
                return
            }
        }

        val apiUrl = "$origin/api/videos/$videoId"
        val apiResp = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to iframeUrl,
                    "Origin" to origin,
                    "Accept" to "application/json, text/plain, */*",
                ),
                referer = iframeUrl, timeout = 10000, allowRedirects = true,
            )
        }.getOrNull()
        if (apiResp != null && apiResp.isSuccessful) {
            val apiBody = apiResp.text
            val subtitlesArray = Regex(""""subtitles"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(apiBody)?.groupValues?.getOrNull(1)
            if (!subtitlesArray.isNullOrBlank()) {
                Regex("""\{[^{}]*\}""").findAll(subtitlesArray).forEach { objMatch ->
                    val obj = objMatch.value
                    val subUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                        ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                        ?: Regex(""""src"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                    val label = Regex(""""lang"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                        ?: Regex(""""language"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                        ?: Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                    addSub(subUrl, label)
                }
            }
        }

        for (key in listOf("subtitles", "subs", "tracks", "captions")) {
            val arrStart = Regex(""""$key"\s*:\s*\[""", RegexOption.IGNORE_CASE).find(tokenBody) ?: continue
            var i = arrStart.range.last + 1
            var depth = 1
            while (i < tokenBody.length && depth > 0) {
                when (tokenBody[i]) { '[' -> depth++; ']' -> depth-- }
                i++
            }
            if (depth != 0) continue
            val arrBody = tokenBody.substring(arrStart.range.last + 1, i - 1)
            Regex("""\{[^{}]*\}""").findAll(arrBody).forEach { objMatch ->
                val obj = objMatch.value
                val subUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                val label = Regex(""""lang"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                    ?: Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                addSub(subUrl, label)
            }
        }

        Log.d(name, "videoplays.cfd: toplam $count altyazı eklendi")
    }

    private suspend fun resolveVideoplaysCfd(
        iframeUrl: String,
        outerReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("""[?&]video=([^&\s"'<>]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
        if (videoId.isNullOrBlank()) {
            Log.e(name, "videoplays.cfd: video ID bulunamadı: $iframeUrl")
            return false
        }
        val origin = originOf(iframeUrl) ?: "https://videoplays.cfd"

        val tokenUrl = "$origin/api/videos/$videoId/token?embed_referrer=" + URLEncoder.encode(outerReferer, "UTF-8")
        val tokenResp = runCatching {
            app.get(tokenUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to outerReferer, "Origin" to origin, "Accept" to "application/json, text/plain, */*"), referer = outerReferer, timeout = 15000, allowRedirects = true)
        }.getOrNull()

        if (tokenResp == null || !tokenResp.isSuccessful) {
            Log.e(name, "videoplays.cfd: token alınamadı (${tokenResp?.code}) - $tokenUrl")
            return false
        }

        val tokenBody = tokenResp.text

        val playlistUrlFromBody = Regex(""""playlist_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(tokenBody)?.groupValues?.getOrNull(1)
        val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenBody)?.groupValues?.getOrNull(1)

        val finalUrl = when {
            !playlistUrlFromBody.isNullOrBlank() -> absolute(playlistUrlFromBody.replace("\\/", "/"), origin)
            !token.isNullOrBlank() -> "$origin/api/videos/$videoId/master.m3u8?token=" + URLEncoder.encode(token, "UTF-8")
            else -> return false
        }

        Log.d(name, "videoplays.cfd: ★ m3u8 linki: $finalUrl")

        callback.invoke(newExtractorLink(source = name, name = name, url = finalUrl, type = ExtractorLinkType.M3U8) {
            this.referer = iframeUrl
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to iframeUrl, "Origin" to origin, "Accept" to "*/*")
        })

        extractVideoplaysSubtitles(tokenBody, finalUrl, iframeUrl, origin, videoId, subtitleCallback)

        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = absolute(data, mainUrl)
        if (sourceUrl.isBlank()) return false
        val visited = mutableSetOf<String>()
        var found = false

        suspend fun emitMedia(raw: String?, referer: String) {
            val media = raw?.trim() ?: return
            val mediaUrl = absolute(media.replace("\\/", "/"), referer)
            if (mediaUrl.isBlank() || !mediaUrl.startsWith("http", true)) return
            if (!visited.add(mediaUrl)) return
            val lower = mediaUrl.lowercase()
            if (!(lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") || lower.contains(".m3u") || lower.contains(".webm"))) return
            val linkType = when {
                lower.contains(".m3u8") || lower.contains(".m3u") -> ExtractorLinkType.M3U8
                lower.contains(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback.invoke(newExtractorLink(source = name, name = name, url = mediaUrl, type = linkType) {
                this.referer = referer
                this.quality = when {
                    lower.contains("2160") -> Qualities.P2160.value
                    lower.contains("1080") -> Qualities.P1080.value
                    lower.contains("720") -> Qualities.P720.value
                    lower.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Accept" to "*/*")
            })
            found = true
        }

        suspend fun scanPage(url: String, referer: String, depth: Int) {
            if (depth > 3 || !visited.add("page:$url")) return
            if (url.contains("videoplays.cfd", true) || url.contains("player.html?video=", true)) {
                if (resolveVideoplaysCfd(url, referer, subtitleCallback, callback)) { found = true; return }
            }
            val resp = runCatching {
                app.get(url, headers = headers + mapOf("Referer" to referer, "User-Agent" to USER_AGENT), referer = referer, timeout = 15000, allowRedirects = true)
            }.getOrNull()
            if (resp == null || !resp.isSuccessful) return
            val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|m3u|webm)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).findAll(text).forEach { emitMedia(it.value, url) }
            listOf(
                """"file"\s*:\s*"([^"]+)"""", """"url"\s*:\s*"([^"]+)"""", """"src"\s*:\s*"([^"]+)"""",
                """"source"\s*:\s*"([^"]+)"""", """"stream"\s*:\s*"([^"]+)"""", """"playlist"\s*:\s*"([^"]+)"""",
            ).forEach { p -> Regex(p, RegexOption.IGNORE_CASE).findAll(text).forEach { emitMedia(it.groupValues[1], url) } }
            Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                val ifUrl = absolute(m.groupValues[1], url)
                if (ifUrl.isNotBlank()) scanPage(ifUrl, url, depth + 1)
            }
        }

        scanPage(sourceUrl, "$mainUrl/", 0)
        return found
    }
}
