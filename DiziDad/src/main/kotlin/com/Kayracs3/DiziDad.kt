package com.Kayracs3

// Ported from https://github.com/neoncs3/neoncs33/tree/master/DiziDad; package adapted for Kayra.
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Bağımsız DiziDAD katalog sağlayıcısı.
 * Afişler karttan ayrıştırılır; data-src, src'den önce okunur ve genel alt metin
 * içerik başlığı sayılmaz. Video çıkarımı eski, çalışan sağlayıcıdan taşınmıştır.
 */
class DiziDad : MainAPI() {
    override var mainUrl = "https://www.dizidad.com"
    override var name = "DiziDad"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    private val posterHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to browserHeaders.getValue("User-Agent")
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/dizi-arsivi/" to "Dizi Arşivi",
        "$mainUrl/yerli-dizi-izle/" to "Yerli Diziler",
        "$mainUrl/yabanci-dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/netflix-dizileri-izle/" to "Netflix Dizileri",
        "$mainUrl/turkce-dublaj-diziler-hd/" to "Dublajlı Diziler",        
        "$mainUrl/kore-dizileri-izle-hd/" to "Kore Dizileri",        
        "$mainUrl/tv-programlari-izle/" to "TV Programları",
        "$mainUrl/tum-bolumler/" to "Son Bölümler",
        "$mainUrl/yerli-dizi-bolumleri/" to "Yerli Dizi Bölümleri",
        "$mainUrl/turkce-dublaj-bolumler-hd/" to "Dublajlı Bölümler",
        "$mainUrl/yabanci-dizilerin-son-bolumleri/" to "Yabancı Dizi Bölümleri",
        "$mainUrl/kore-dizilerinden-son-bolumler/" to "Kore Dizi Bölümleri",
        "$mainUrl/tv-program-bolumleri/" to "TV Program Bölümleri",       
        "$mainUrl/tum-filmler/" to "Filmler",
        "$mainUrl/yabanci-filmler-izle/" to "Yabancı Filmler",
        "$mainUrl/yerli-filmler/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublajlı Filmler",
        "$mainUrl/turkce-altyazili-filmler/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/film-tur/netflix-filmleri/" to "Netflix Filmleri",
        "$mainUrl/film-tur/2026-filmleri-izle/" to "2026 Filmleri",
        "$mainUrl/film-tur/2025-filmleri/" to "2025 Filmleri"
    )

    private data class EpNo(val season: Int, val episode: Int)
    private val episodeUrlRegex = Regex("""-(\d+)-bolum(?:-|/|$)""", RegexOption.IGNORE_CASE)
    private val seasonUrlRegex = Regex("""-(\d+)-sezon-""", RegexOption.IGNORE_CASE)
    private val episodeTextRegex = Regex("""(\d+)\s*\.?\s*Bölüm""", RegexOption.IGNORE_CASE)
    private val seasonTextRegex = Regex("""(\d+)\s*\.?\s*Sezon""", RegexOption.IGNORE_CASE)
    private val genericLabels = setOf(
        "yerli dizi", "yabanci dizi", "yerli film", "yabanci film",
        "turkce dublaj", "turkce altyazi", "dizi", "film", "poster", "afis",
        "dizi afisi", "film afisi", "dizi posteri", "film posteri",
        "dizidad", "dizidad avatar"
    )
    private val listingPaths = setOf(
        "/", "/dizi-arsivi/", "/yerli-dizi-izle/", "/yabanci-dizi-izle/",
        "/netflix-dizileri-izle/", "/turkce-dublaj-diziler-hd/", "/anime-izle/",
        "/kore-dizileri-izle-hd/", "/full-hd-hint-dizileri-izle/",
        "/pakistan-dizileri-izle/", "/tv-programlari-izle/", "/tum-bolumler/",
        "/yerli-dizi-bolumleri/", "/turkce-dublaj-bolumler-hd/",
        "/yabanci-dizilerin-son-bolumleri/", "/kore-dizilerinden-son-bolumler/",
        "/hint-dizilerinden-son-bolumler/", "/pakistan-dizilerinden-son-bolumler/",
        "/tv-program-bolumleri/", "/anime-dizilerin-bolumleri/",
        "/tum-filmler/", "/yabanci-filmler-izle/", "/yerli-filmler/",
        "/turkce-dublaj-filmler/", "/turkce-altyazili-filmler/",
        "/netflix-filmleri-izle/", "/film-tur/netflix-filmleri/",
        "/film-tur/2026-filmleri-izle/", "/film-tur/2025-filmleri/",
        "/dizi-izle/", "/film-izle/", "/dizi-takvimi/", "/yardim/",
        "/iletisim/", "/mobil-apk/", "/uye-ol/", "/uye-girisi/",
        "/dizi-turu/", "/film-kategorileri/"
    )

    private fun norm(value: String): String = value.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("""(?i)\s*\|\s*DiziDAD.*$"""), "")
        .replace(Regex("""(?i)\s*[-|:]\s*DiziDAD\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:izle\s*)+$"""), "")
        .replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '–').trim()

    private fun isGeneric(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val value = norm(cleanTitle(raw))
        return value.isBlank() || value in genericLabels ||
            Regex("""^\d+(?:[.,]\d+)?$""").matches(value) ||
            Regex("""(?i)^(?:null|undefined|none|nan)$""").matches(value) ||
            Regex("""(?i)^.*\.(?:jpe?g|png|webp|gif|avif|svg)$""").matches(value)
    }

    private fun absoluteUrl(rawValue: String?, base: String = mainUrl): String? {
        val raw = rawValue?.trim()?.replace("&amp;", "&") ?: return null
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("mailto:", true)
        ) return null
        return runCatching {
            URI(if (base.endsWith("/")) base else "$base/").resolve(raw).normalize()
                .toString().substringBefore("#")
        }.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // Orijinal oynatıcı kodunun kullandığı CloudStream tarzı URL çözümlemesi.
    private fun absUrl(value: String): String = absoluteUrl(value, mainUrl) ?: value

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault(url.substringBefore("?").substringBefore("#").lowercase(Locale.ROOT))

    private fun urlKey(url: String): String = pathOf(url).trimEnd('/').ifBlank { "/" }
    private fun isEpisodeUrl(url: String) = episodeUrlRegex.containsMatchIn(pathOf(url))
    private fun isSeriesUrl(url: String) = pathOf(url).contains("/diziler/")

    private fun isListingUrl(url: String): Boolean {
        var path = pathOf(url).replace(Regex("""/page/\d+/?$"""), "/")
        if (path.isBlank()) path = "/"
        if (path in listingPaths) return true
        return path.startsWith("/category/") || path.startsWith("/tag/") ||
            path.startsWith("/author/") || path.startsWith("/wp-") ||
            path.startsWith("/user/") || path.startsWith("/profile/") ||
            path.startsWith("/uye/") || path.startsWith("/yorum") ||
            path.startsWith("/comments") || path.startsWith("/search/") ||
            path.contains("takvim") || path.contains("tartisma")
    }

    private fun isContentUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl, true) || isListingUrl(url)) return false
        val path = pathOf(url)
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isBlank() || last == "page") return false
        if (path.contains("/wp-content/") || path.contains("/wp-admin/") ||
            path.contains("/wp-json/") || path.contains("/feed/")
        ) return false
        if (isEpisodeUrl(url) || isSeriesUrl(url)) return true
        return last.endsWith("-izle") || last.endsWith("-izle-hd") ||
            (path.contains("/filmler/") && last != "filmler")
    }

    private fun titleFromUrl(url: String): String {
        var slug = pathOf(url).trimEnd('/').substringAfterLast('/')
        slug = slug
            .replace(Regex("""(?i)-\d+-sezon-.*$"""), "")
            .replace(Regex("""(?i)-\d+-bolum.*$"""), "")
            .replace(Regex("""(?i)-(?:turkce-)?(?:dublaj|altyazili)-izle.*$"""), "")
            .replace(Regex("""(?i)-izle(?:-hd)?$"""), "")
            .replace(Regex("""(?i)^(?:dizi|film)-"""), "")
            .replace('-', ' ').replace('_', ' ').trim()
        if (slug.isBlank()) return ""
        return slug.split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    private fun isUsablePoster(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = pathOf(url)
        val file = path.substringAfterLast('/')
        if (file.isBlank() || file.endsWith(".svg")) return false
        if (Regex("""(?i)(placeholder|no[-_]?image|transparent|spacer|default[-_]?avatar|blank|pixel|logo|gravatar|avatar)""")
                .containsMatchIn(file)
        ) return false
        return file.contains('.') || path.contains("/wp-content/uploads/")
    }

    private fun imageUrl(node: Element): String? {
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-image", "data-poster",
            "data-litespeed-src", "data-lazy-srcset", "data-srcset", "srcset",
            "data-bg", "data-background-image", "data-background", "src"
        )
        for (attr in attrs) {
            val raw = node.attr(attr).trim()
            if (raw.isBlank()) continue
            val first = if (attr.contains("srcset")) raw.substringBefore(",").trim().substringBefore(" ") else raw
            if (first.isBlank() || first.startsWith("data:", true)) continue
            val result = absoluteUrl(first, node.baseUri().ifBlank { mainUrl }) ?: continue
            if (isUsablePoster(result)) return result
        }
        val style = node.attr("style")
        val bg = Regex("""(?i)url\(\s*['"]?([^)'"]+)""").find(style)?.groupValues?.getOrNull(1)
        return absoluteUrl(bg, node.baseUri().ifBlank { mainUrl })?.takeIf { isUsablePoster(it) }
    }

    private fun scorePoster(node: Element, poster: String, targetUrl: String, title: String): Int {
        val alt = norm(node.attr("alt") + " " + node.attr("title"))
        val file = norm(pathOf(poster).substringAfterLast('/').substringBeforeLast('.').replace('-', ' '))
        val target = norm(titleFromUrl(targetUrl) + " " + title)
        var score = 0
        for (token in target.split(" ").filter { it.length >= 3 }.distinct()) {
            if (alt.split(" ").contains(token)) score += 8 else if (alt.contains(token)) score += 3
            if (file.split(" ").contains(token)) score += 10 else if (file.contains(token)) score += 4
        }
        if (pathOf(poster).contains("/wp-content/uploads/")) score++
        return score
    }

    private fun candidates(container: Element): List<Pair<Element, String>> =
        container.select("img, [data-bg], [data-background-image], [style*=background]")
            .mapNotNull { node -> imageUrl(node)?.let { node to it } }
            .distinctBy { it.second }

    private fun posterNearLink(link: Element, url: String, title: String): String? {
        imageUrl(link.selectFirst("img, [data-bg], [data-background-image], [style*=background]") ?: link)
            ?.let { return it }

        var current: Element? = link
        var depth = 0
        while (current != null && depth < 6) {
            val marker = (current.id() + " " + current.classNames().joinToString(" ")).lowercase(Locale.ROOT)
            if (Regex("""\b(site[-_]?header|site[-_]?footer|navbar|navigation|main[-_]?menu|mobile[-_]?menu|sidebar|widget|login|register|comment|avatar|alphabet|social|user[-_]?menu)\b""")
                    .containsMatchIn(marker)
            ) return null

            val localLinks = (listOf(current) + current.select("a[href]"))
                .filter { it.tagName().equals("a", true) }
                .mapNotNull { absoluteUrl(it.attr("href"), it.baseUri()) }
                .filter { isContentUrl(it) }
                .distinctBy { urlKey(it) }

            if (localLinks.any { urlKey(it) == urlKey(url) }) {
                val images = candidates(current)
                if (images.isNotEmpty() && localLinks.size == 1) {
                    val best = images.maxByOrNull { scorePoster(it.first, it.second, url, title) }
                    if (best != null && (scorePoster(best.first, best.second, url, title) > 1 || images.size == 1)) {
                        return best.second
                    }
                }
                if (localLinks.size > 1) return null
            }
            if (current.tagName().lowercase(Locale.ROOT) in setOf("body", "html")) break
            current = current.parent()
            depth++
        }
        return null
    }

    private fun matchingPosterFromDocument(doc: Document, url: String, title: String): String? {
        val images = candidates(doc)
        return images.maxByOrNull { scorePoster(it.first, it.second, url, title) }
            ?.takeIf { scorePoster(it.first, it.second, url, title) >= 4 }
            ?.second
    }

    private fun posterForDocument(doc: Document, url: String, title: String): String? {
        matchingPosterFromDocument(doc, url, title)?.let { return it }

        val og = doc.selectFirst(
            "meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]"
        )?.attr("content")
        absoluteUrl(og, url)?.takeIf { isUsablePoster(it) }?.let { return it }

        imageUrl(doc.selectFirst(
            ".category_image img, .cat-img img, .post-thumbnail img, .movie-image img, .movie-poster img, .single-item img, article img"
        ) ?: doc.selectFirst("img") ?: return null)?.let { return it }
        return candidates(doc).firstOrNull()?.second
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (link in doc.select("a[href]")) {
            val url = absoluteUrl(link.attr("href"), link.baseUri()) ?: continue
            if (isContentUrl(url)) groups.getOrPut(urlKey(url)) { mutableListOf() }.add(link)
        }

        val output = LinkedHashMap<String, SearchResponse>()
        for ((key, links) in groups) {
            val url = absoluteUrl(links.first().attr("href"), links.first().baseUri()) ?: continue
            val titleCandidates = mutableListOf<String>()
            for (link in links) {
                titleCandidates += link.ownText()
                titleCandidates += link.attr("title")
                titleCandidates += link.selectFirst("[title]")?.attr("title").orEmpty()
                titleCandidates += link.select("img[alt], img[title]")
                    .map { it.attr("alt").ifBlank { it.attr("title") } }
            }
            val rawTitle = titleCandidates.asSequence().map { cleanTitle(it) }
                .firstOrNull { !isGeneric(it) }.orEmpty().ifBlank { titleFromUrl(url) }
            if (rawTitle.isBlank() || isGeneric(rawTitle)) continue

            // Son bölümler listesinde her bölüm ayrı linktir. Kartı ana dizi başlığıyla
            // tekilleştir; load() bölüm sayfasından gerçek dizi sayfasına yönlendirecek.
            val episodeUrl = isEpisodeUrl(url)
            val displayTitle = if (episodeUrl) seriesTitleFromEpisode(rawTitle, url) else rawTitle
            if (displayTitle.isBlank() || isGeneric(displayTitle)) continue
            val itemKey = if (episodeUrl || isSeriesUrl(url)) "series:${norm(displayTitle)}" else key
            if (output.containsKey(itemKey)) continue

            var poster: String? = null
            for (link in links) {
                poster = posterNearLink(link, url, displayTitle)
                if (!poster.isNullOrBlank()) break
            }
            if (poster.isNullOrBlank()) poster = matchingPosterFromDocument(doc, url, displayTitle)

            output[itemKey] = if (isSeriesUrl(url) || episodeUrl) {
                newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders
                }
            } else {
                newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders
                }
            }
        }
        return output.values.toList()
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val clean = base.substringBefore("?").trimEnd('/')
        val query = base.substringAfter("?", "").takeIf { it.isNotBlank() }
        val paged = "$clean/page/$page/"
        return if (query == null) paged else "$paged?$query"
    }

    private fun hasNext(doc: Document, page: Int): Boolean = page < 250 &&
        doc.select("a[rel=next], .nav-links a.next, a.next, a[href*='/page/" + (page + 1) + "/']").isNotEmpty()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = runCatching { app.get(pagedUrl(request.data, page), headers = browserHeaders).document }
            .getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseItems(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && hasNext(doc, page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/?search=$encoded", "$mainUrl/ara/$encoded/")
        for (url in urls) {
            val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull() ?: continue
            val results = parseItems(doc).filter { norm(it.name).contains(norm(q)) }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun pageTitle(doc: Document, url: String): String {
        val raw = doc.selectFirst("h1.entry-title, h1.post-title, main h1, article h1, h1")
            ?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().ifBlank { doc.title() } }
        return cleanTitle(raw).ifBlank { titleFromUrl(url) }
    }

    private fun pagePlot(doc: Document): String? {
        val meta = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content").orEmpty()
        val body = if (meta.isNotBlank()) meta else doc.selectFirst(
            ".entry-content, .movie-description, .description, .post-content, article"
        )?.text().orEmpty()
        if (body.isBlank()) return null
        return body.replace(Regex("""(?is)^.*?sizlerle,\s*"""), "")
            .replace(Regex("""(?is)^.*?keyifle izleyin,\s*"""), "").trim().ifBlank { null }
    }

    private fun pageYear(doc: Document, title: String): Int? =
        Regex("""(?:Yapım Yılı|Çıkış Yılı)\s*:?\s*(\d{4})""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun pageActors(doc: Document): List<String> {
        val match = Regex(
            """Oyuncular\s*:?\s*(.+?)(?:IMDb|IMDB|Tür\s*:|Film Süre|IMDb Puanı|$)""",
            RegexOption.IGNORE_CASE
        ).find(doc.text()) ?: return emptyList()
        return match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotBlank() && it.length < 80 }
    }

    private fun pageRating(doc: Document): Double? {
        val text = doc.text()
        val raw = Regex("""(?:IMDb Puanı|IMDB)\s*:?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        return raw?.takeIf { it in 0.0..10.0 }
    }

    private fun pageGenres(doc: Document): List<String> {
        val fromLinks = doc.select("a[href*=tur=], a[href*=genre], a[href*=kategori]")
            .map { cleanTitle(it.text()) }
            .filter { it.isNotBlank() && !isGeneric(it) && it.length < 32 }
            .distinct()
        if (fromLinks.isNotEmpty()) return fromLinks
        return Regex("""Tür\s*:\s*([^\n]+?)(?:\s+Oyuncular|\s+Yapım Yılı|\s+IMDB|$)""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.getOrNull(1)?.split(",")
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
    }

    private fun pageTrailer(doc: Document): String? {
        for (iframe in doc.select("iframe[src], iframe[data-src]")) {
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (raw.contains("youtube.com/embed/") || raw.contains("youtu.be/")) {
                return absoluteUrl(raw, doc.location())
            }
        }
        return Regex("""https?://(?:www\.)?youtube\.com/embed/[A-Za-z0-9_-]+""").find(doc.html())?.value
    }

    private fun epNo(url: String, text: String) = EpNo(
        seasonTextRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: seasonUrlRegex.find(pathOf(url))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        episodeTextRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: episodeUrlRegex.find(pathOf(url))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    )

    private fun seriesTitleFromEpisode(title: String, url: String): String {
        val clean = cleanTitle(title)
            .replace(Regex("""(?i)\s+\d+\s*\.?\s*Sezon.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\s*\.?\s*Bölüm.*$"""), "").trim()
        return clean.takeIf { it.isNotBlank() && !isGeneric(it) } ?: titleFromUrl(url)
    }

    private fun collectEpisodes(doc: Document, seriesUrl: String, poster: String?): List<Episode> {
        val episodeLinks = LinkedHashMap<String, Element>()
        for (link in doc.select("a[href]")) {
            val url = absoluteUrl(link.attr("href"), link.baseUri()) ?: continue
            if (!url.startsWith(mainUrl, true) || !isEpisodeUrl(url) || isSeriesUrl(url) || isListingUrl(url)) continue
            if (urlKey(url) != urlKey(seriesUrl)) episodeLinks.putIfAbsent(urlKey(url), link)
        }

        return episodeLinks.values.mapNotNull { link ->
            val url = absoluteUrl(link.attr("href"), link.baseUri()) ?: return@mapNotNull null
            val title = cleanTitle(link.text().ifBlank { link.attr("title") }).ifBlank { titleFromUrl(url) }
            val number = epNo(url, title)
            newEpisode(url) {
                name = title
                season = number.season
                episode = number.episode
                posterUrl = posterNearLink(link, url, seriesTitleFromEpisode(title, url)) ?: poster
            }
        }.sortedWith(compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: 1 }, { it.name }))
    }

    private suspend fun documentFor(url: String): Document? = runCatching {
        app.get(url, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).document
    }.getOrNull()

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = absoluteUrl(url) ?: return null
        var doc = documentFor(pageUrl) ?: return null

        // Son bölümler kartı tek bölüm URL'si taşıyabilir. Bölüm sayfasındaki
        // /diziler/... bağlantısını bulup gerçek dizi detayını yükle.
        if (isEpisodeUrl(pageUrl)) {
            val episodeTitle = pageTitle(doc, pageUrl)
            val expectedWords = norm(seriesTitleFromEpisode(episodeTitle, pageUrl))
                .split(" ").filter { it.length >= 3 }.distinct()
            val parentSeriesUrl = doc.select("a[href*='/diziler/']")
                .mapNotNull { link ->
                    val target = absoluteUrl(link.attr("href"), link.baseUri()) ?: return@mapNotNull null
                    if (isSeriesUrl(target) && !isEpisodeUrl(target) && isContentUrl(target)) link to target else null
                }
                .distinctBy { urlKey(it.second) }
                .map { (link, target) ->
                    val linkTitle = norm(cleanTitle(link.text().ifBlank { link.attr("title") }))
                    val slugTitle = norm(titleFromUrl(target))
                    val score = expectedWords.count { word ->
                        linkTitle.split(" ").contains(word) || slugTitle.split(" ").contains(word)
                    } * 10 + if (norm(link.text()).contains("dizinin sayfasina git")) 100 else 0
                    score to target
                }
                .filter { it.first > 0 }
                .maxByOrNull { it.first }?.second

            if (!parentSeriesUrl.isNullOrBlank()) {
                val seriesDoc = documentFor(parentSeriesUrl)
                if (seriesDoc != null) {
                    pageUrl = parentSeriesUrl
                    doc = seriesDoc
                }
            }
        }
        val title = pageTitle(doc, pageUrl)
        val poster = posterForDocument(doc, pageUrl, title)
        val plot = pagePlot(doc)
        val year = pageYear(doc, title)
        val actors = pageActors(doc)
        val tags = pageGenres(doc)
        val trailer = pageTrailer(doc)
        val rating = pageRating(doc)

        if (isEpisodeUrl(pageUrl)) {
            val number = epNo(pageUrl, title)
            val seriesTitle = seriesTitleFromEpisode(title, pageUrl)
            val episode = newEpisode(pageUrl) {
                name = title.ifBlank { "Sezon " + number.season + " Bölüm " + number.episode }
                season = number.season
                episode = number.episode
                posterUrl = poster
            }
            return newTvSeriesLoadResponse(seriesTitle, pageUrl, TvType.TvSeries, listOf(episode)) {
                posterUrl = poster
                posterHeaders = this@DiziDad.posterHeaders
                this.plot = plot
                this.year = year
                rating?.let { score = Score.from10(it) }
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }

        if (isSeriesUrl(pageUrl)) {
            val episodes = collectEpisodes(doc, pageUrl, poster)
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                posterHeaders = this@DiziDad.posterHeaders
                this.plot = plot
                this.year = year
                rating?.let { score = Score.from10(it) }
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            posterHeaders = this@DiziDad.posterHeaders
            this.plot = plot
            this.year = year
            rating?.let { score = Score.from10(it) }
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // Sadece video bağlantısı çıkarımı eski DiziDad sağlayıcısından taşınır.
private val m3u8Regex = Regex("""https?://[^\s"'<>\\)]+?\.m3u8[^\s"'<>\\)]*""")
    private val mp4Regex = Regex("""https?://[^\s"'<>\\)]+?\.mp4[^\s"'<>\\)]*""")
    private val hdPlayerBase = "https://hdplayersystem.com"

    /**
     * X/Twitter HLS playlistleri seekbar önizleyicisinin beklediği TS yerine
     * CMAF/fMP4 parçaları kullanabilir. Syndication JSON'unda MP4 varsa onu tercih et;
     * API başarısız olursa aşağıdaki kod orijinal HLS bağlantısını korur.
     */
    private suspend fun twitterMp4Variants(m3u8Url: String): List<Pair<Int, String>> {
        val mediaId = Regex("""/amplify_video/(\d+)/""")
            .find(m3u8Url)?.groupValues?.getOrNull(1) ?: return emptyList()
        val apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$mediaId&token=0&lang=en"
        val jsonText = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to browserHeaders.getValue("User-Agent"),
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "https://x.com/"
                )
            ).text
        }.getOrNull()?.takeIf { it.trimStart().startsWith("{") } ?: return emptyList()

        val found = mutableListOf<Pair<Int, String>>()
        fun scan(value: Any?) {
            when (value) {
                is org.json.JSONObject -> {
                    val url = value.optString("url").trim()
                    val mime = value.optString("content_type")
                        .ifBlank { value.optString("contentType") }
                    if (
                        url.startsWith("https://video.twimg.com/") &&
                        url.substringBefore("?").endsWith(".mp4", true) &&
                        (mime.isBlank() || mime.contains("video/mp4", true))
                    ) {
                        val bitrate = value.optInt("bitrate", 0)
                        val height = Regex("""\d{3,4}x(\d{3,4})""")
                            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        found += (height.takeIf { it > 0 } ?: (bitrate / 1000)) to url
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is org.json.JSONObject || child is org.json.JSONArray) scan(child)
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.opt(i)
                        if (child is org.json.JSONObject || child is org.json.JSONArray) scan(child)
                    }
                }
            }
        }
        runCatching { scan(org.json.JSONObject(jsonText)) }
        return found.distinctBy { it.second }.sortedByDescending { it.first }.take(4)
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data)
        val res = app.get(pageUrl, referer = "$mainUrl/")
        val html = res.text.replace("\\/", "/")
        val doc = res.document

        val candidates = LinkedHashSet<String>()
        for (f in doc.select("iframe")) {
            for (attr in listOf("src", "data-src", "data-lazy-src", "data-litespeed-src", "data-lazy")) {
                val v = f.attr(attr).trim()
                if (v.isNotEmpty() && !v.startsWith("about:") && !v.startsWith("data:")) {
                    val candidate = absUrl(v)
                    val host = runCatching { URI(candidate).host.orEmpty().lowercase(Locale.ROOT) }
                        .getOrDefault("")
                    // YouTube iframe sayfa fragmanıdır; kısa fragmanı bölüm videosu gibi sunma.
                    val isYoutubeTrailer = host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host.endsWith(".youtu.be") ||
                        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
                    if (!isYoutubeTrailer) candidates.add(candidate)
                }
            }
        }
        Regex("""https?://(?:www\.)?hdplayersystem\.com/[^\s"'<>\\]+""")
            .findAll(html).forEach { candidates.add(it.value) }

        var found = false
        for (c in candidates) {
            val ok = if (c.contains("hdplayersystem.com")) {
                hdPlayer(c, pageUrl, subtitleCallback, callback)
            } else {
                // Çıkarılan bağlantıları yakala; X HLS için doğrudan MP4 varsa onu ver.
                val emitted = mutableListOf<ExtractorLink>()
                val extractorOk = runCatching {
                    loadExtractor(c, pageUrl, subtitleCallback) { emitted.add(it) }
                }.getOrDefault(false)

                for (link in emitted) {
                    val isTwitterHls = link.url.contains("video.twimg.com/amplify_video/") &&
                        Regex("""\.m3u8(?:\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(link.url)
                    if (isTwitterHls) {
                        // Asıl HLS bağlantısını hemen sun ve ilk sırada koru;
                        // MP4 alternatifinin aranması mevcut oynatımı geciktirmesin.
                        callback(link)
                        val mp4Variants = runCatching { twitterMp4Variants(link.url) }
                            .getOrDefault(emptyList())
                        for ((quality, mp4Url) in mp4Variants) {
                            callback(
                                newExtractorLink(name, "${link.name} MP4 alternatif", mp4Url, ExtractorLinkType.VIDEO) {
                                    this.referer = link.referer.ifBlank { pageUrl }
                                    this.quality = quality.takeIf { it > 0 } ?: Qualities.Unknown.value
                                }
                            )
                        }
                    } else {
                        callback(link)
                    }
                }
                extractorOk || emitted.isNotEmpty()
            }
            if (ok) found = true
        }
        return found
    }

    /**
     * hdplayersystem.com/video/<id>: sayfa kendisi videoyu bir POST isteğiyle alır
     * (player/index.php?data=<id>&do=getVideo -> {"securedLink": "...m3u8"}).
     */
    private suspend fun hdPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = url.substringAfter("/video/", "")
            .ifBlank { url.substringAfter("data=", "") }
            .substringBefore("?").substringBefore("&").substringBefore("#").trim('/')
        if (id.isBlank()) return false
        var found = false

        val embedText = runCatching {
            app.get(url, referer = referer).text.replace("\\/", "/")
        }.getOrDefault("")

        Regex("""playerjsSubtitle\s*=\s*["']([^"']+)["']""").find(embedText)?.groupValues?.get(1)
            ?.split(",")?.forEach { part ->
                val lang = part.substringAfter("[", "").substringBefore("]", "").ifBlank { "Türkçe" }
                val link = part.substringAfter("]", part).trim()
                if (link.startsWith("http")) subtitleCallback(SubtitleFile(lang, link))
            }

        val body = runCatching {
            app.post(
                "$hdPlayerBase/player/index.php?data=$id&do=getVideo",
                data = mapOf("hash" to id, "r" to referer),
                referer = url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to hdPlayerBase
                )
            ).text.replace("\\/", "/")
        }.getOrDefault("")

        val links = LinkedHashSet<String>()
        for (key in listOf("securedLink", "videoSource")) {
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }?.let { links.add(it) }
        }
        m3u8Regex.findAll(body).forEach { links.add(it.value) }
        if (links.isEmpty()) m3u8Regex.findAll(embedText).forEach { links.add(it.value) }

        for (l in links) {
            M3u8Helper.generateM3u8(name, l, url, null, mapOf("Origin" to hdPlayerBase))
                .forEach(callback)
            found = true
        }

        if (links.isEmpty()) {
            for (m in mp4Regex.findAll(body + "\n" + embedText).map { it.value }.distinct()) {
                callback(
                    newExtractorLink(name, name, m, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}

