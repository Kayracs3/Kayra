package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1594.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    // Yalnızca /platform-bolumler sayfasındaki "Tümü" bağlantılarının hedefleri.
    override val mainPage = mainPageOf(
        "$mainUrl/kategori/dram" to "Dram",
        "$mainUrl/kategori/suc" to "Suç",
        "$mainUrl/kategori/komedi" to "Komedi",
        "$mainUrl/kategori/gizem" to "Gizem",
        "$mainUrl/kategori/aksiyon-macera" to "Aksiyon ve Macera",
        "$mainUrl/kategori/bilim-kurgu-fantazi" to "Bilim Kurgu ve Fantastik",
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private const val AES_KEY = "T8r9Wq3mZ1kL5vYb"
        private const val AES_IV = "N2pR7sX4cV6bM1qF"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    // ================= KATEGORİ / KART AYRIŞTIRMA =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) request.data else "$baseUrl?page=$page"
        val response = runCatching {
            app.get(pageUrl, headers = headers, timeout = 20000, allowRedirects = true)
        }.onFailure {
            Log.e("DiziPal", "Kategori isteği başarısız: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        if (!response.isSuccessful) {
            Log.e("DiziPal", "Kategori HTTP hatası: ${response.code} - $pageUrl")
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val results = parseCatalogItems(response.document)
        Log.d("DiziPal", "${request.name}: ${results.size} gerçek içerik kartı bulundu (sayfa $page)")
        return newHomePageResponse(request.name, results, results.isNotEmpty() && page < 50)
    }

    private fun isDiziPalContentUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/series/") || path.startsWith("/film/") ||
            path.startsWith("/movie/") || path.startsWith("/movies/")
    }

    private fun isDiziPalSeriesUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/series/") || path.startsWith("/dizi/")
    }

    private fun isDiziPalSiteUrl(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty().lowercase().removePrefix("www.")
        val mainHost = java.net.URI(mainUrl).host.orEmpty().lowercase().removePrefix("www.")
        host == mainHost || host.endsWith(".$mainHost")
    }.getOrDefault(false)

    private fun cardFor(anchor: Element): Element {
        var current: Element? = anchor
        repeat(6) {
            val node = current ?: return anchor
            if (node.selectFirst("img, picture img, [data-poster], [style*=background-image]") != null) return node
            current = node.parent()
        }
        return anchor
    }

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        val attributes = listOf(
            "data-poster", "data-image", "data-src", "data-lazy-src", "data-original",
            "data-original-src", "data-thumbnail", "data-thumb", "data-bg", "src", "content"
        )
        for (attribute in attributes) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            if (attribute == "content" && element.tagName() != "meta") continue
            val url = normalizeUrl(raw, base)
            if (url.isBlank()) continue
            if (Regex("""(?i)(logo|favicon|avatar|placeholder|no[-_]?image|blank|spacer|pixel|\.svg(?:$|\?))""")
                    .containsMatchIn(url)) continue
            return url
        }

        val srcSet = element.attr("data-srcset").ifBlank { element.attr("srcset") }
        if (srcSet.isNotBlank()) {
            val candidate = srcSet.substringBefore(',').trim().substringBefore(' ').trim()
            if (candidate.isNotBlank()) {
                val url = normalizeUrl(candidate, base)
                if (!Regex("""(?i)(logo|favicon|placeholder|\.svg(?:$|\?))""").containsMatchIn(url)) return url
            }
        }

        val background = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)
        if (!background.isNullOrBlank()) {
            val url = normalizeUrl(background, base)
            if (!Regex("""(?i)(logo|favicon|placeholder|\.svg(?:$|\?))""").containsMatchIn(url)) return url
        }
        return null
    }

    private fun cleanCardTitle(raw: String?): String {
        var value = raw.orEmpty()
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (value.isBlank()) return ""

        value = value
            .replace(Regex("""(?i)^\s*dizipal\s*[:\-|]?\s*"""), "")
            .replace(Regex("""(?i)\s*(?:\||-)\s*dizipal.*$"""), "")
            .replace(Regex("""(?i)\s*(?:·|•)\s*(?:dram|suç|komedi|gizem|aksiyon|macera|animasyon|bilim kurgu|fantastik|gerilim|korku|romantik|belgesel).*$"""), "")
            .replace(Regex("""(?i)\s+(?:18|19|20|21)\d{2}\b.*$"""), "")
            .replace(Regex("""(?i)\b(?:IMDb|IMDB)\s*[0-9]+(?:[.,][0-9]+)?(?:\s*/\s*10)?"""), " ")
            .replace(Regex("""(?<!\d)(?:10|[0-9])[.,][0-9](?!\d)"""), " ")
            .replace(Regex("""(?i)\s+\d{1,2}\s*\.?\s*(?:sezon|season)\b.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '·', '•', ':', '–')

        val badges = setOf(
            "dram", "drama", "suç", "crime", "komedi", "gizem", "aksiyon", "macera",
            "animasyon", "bilim", "kurgu", "fantastik", "gerilim", "korku", "romantik",
            "belgesel", "aile", "western", "savaş", "politik", "spor", "müzik",
            "yeni", "dizi", "film", "netflix", "prime", "video", "hbo", "max",
            "apple", "tv+", "disney+", "paramount+", "blutv", "tabii", "exxen",
            "gain", "epix", "amc", "cbs", "fx", "bbc", "syndication"
        )
        val words = value.split(Regex("""\s+""")).toMutableList()
        while (words.isNotEmpty()) {
            val first = words.first().lowercase()
            if (first in badges || first.matches(Regex("""\d+(?:[.,]\d+)?"""))) {
                words.removeAt(0)
            } else {
                break
            }
        }
        return words.joinToString(" ").trim()
    }

    private fun slugTitle(url: String): String {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val slug = path.trimEnd('/').substringAfterLast('/')
            .replace(Regex("""(?i)-\d{1,2}x\d{1,3}$"""), "")
            .replace(Regex("""(?i)-sezon-\d+$"""), "")
            .replace('-', ' ')
            .replace('_', ' ')
        return slug.split(Regex("""\s+""")).filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .trim()
    }

    private fun scoreFrom(text: String): Float? {
        val imdb = Regex("""(?i)(?:IMDb|IMDB)\s*(?:puanı|rating)?\s*[:：]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""")
            .find(text)?.groupValues?.getOrNull(1)
        val value = imdb ?: Regex("""(?<![\d.])(?:10|[0-9])[.,][0-9](?!\d)""")
            .find(text)?.value
        return value?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0f..10f }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", ignoreCase = true)) this
            else selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return null

        val url = normalizeUrl(href, mainUrl)
        if (!isDiziPalSiteUrl(url) || !isDiziPalContentUrl(url)) return null

        val card = cardFor(anchor)
        val image = anchor.selectFirst("img, picture img") ?: card.selectFirst("img, picture img")
        val poster = imageUrl(image, url)
            ?: imageUrl(card.selectFirst("[data-poster], [style*=background-image]"), url)
            ?: imageUrl(card, url)

        val titleCandidates = listOf(
            anchor.selectFirst("h1,h2,h3,h4,[class*=card-title],[class*=title],[class*=name]")?.text(),
            card.selectFirst("h2,h3,h4,[class*=card-title],[class*=title],[class*=name]")?.text(),
            image?.attr("alt")?.replace(Regex("""(?i)\s*(?:afişi|poster)$"""), ""),
            image?.attr("title"),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            card.attr("data-title"),
            slugTitle(url),
            cleanCardTitle(anchor.ownText()),
            cleanCardTitle(anchor.text()),
            cleanCardTitle(card.text())
        )
        val title = titleCandidates.asSequence()
            .map { cleanCardTitle(it) }
            .firstOrNull { it.length >= 2 && !it.equals("dizipal", true) }
            ?: return null

        val score = scoreFrom(card.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title"))
        return if (isDiziPalSeriesUrl(url)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseCatalogItems(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        document.select("a[href]").forEach { anchor ->
            val item = anchor.toSearchResult() ?: return@forEach
            val old = results[item.url]
            if (old == null || (old.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) {
                results[item.url] = item
            }
        }
        return results.values.take(80)
    }

    // ================= ARAMA =================
    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()
        val url = "$mainUrl/?s=" + URLEncoder.encode(value, "UTF-8")
        Log.d("DiziPal", "Arama isteği: $url")
        val response = runCatching {
            app.get(url, headers = headers, timeout = 20000, allowRedirects = true)
        }.onFailure {
            Log.e("DiziPal", "Arama isteği başarısız: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return emptyList()
        if (!response.isSuccessful) {
            Log.e("DiziPal", "Arama HTTP hatası: ${response.code}")
            return emptyList()
        }
        return parseCatalogItems(response.document)
    }

    private fun normalizeMetadataLabel(value: String?): String =
        value.orEmpty().lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
            .replace(Regex("""\s+"""), " ").trim().trim(':', '：')

    private fun linksNearHeading(
        document: org.jsoup.nodes.Document,
        labels: Set<String>,
        accept: (Element) -> Boolean
    ): List<Element> {
        val possibleHeadings = document.select("h1,h2,h3,h4,h5,strong,b,dt,label,p,div,span")
        for (heading in possibleHeadings) {
            val own = normalizeMetadataLabel(heading.ownText())
            val full = normalizeMetadataLabel(heading.text())
            if (own !in labels && full !in labels) continue

            var parent: Element? = heading.parent()
            repeat(5) {
                val current = parent ?: return@repeat
                val matches = current.select("a[href]").filter(accept)
                if (matches.isNotEmpty() && matches.size <= 40) return matches
                parent = current.parent()
            }
        }
        return emptyList()
    }

    private fun extractGenres(
        document: org.jsoup.nodes.Document,
        pageText: String
    ): List<String> {
        val genres = LinkedHashMap<String, String>()
        fun add(raw: String?) {
            val value = raw.orEmpty().replace('\u00a0', ' ')
                .replace(Regex("""\s+"""), " ").trim()
            val key = normalizeMetadataLabel(value)
            if (key.length < 2 || key in setOf("tumu", "turler", "tur")) return
            if (value.length <= 40) genres.putIfAbsent(key, value)
        }

        val genreLinks = linksNearHeading(
            document,
            setOf("turler", "tur", "genres", "genre")
        ) { link ->
            val href = link.attr("href").lowercase()
            href.contains("/kategori/") || href.contains("/tur/") ||
                href.contains("/turler/") || href.contains("/genre/")
        }

        genreLinks.forEach { add(it.text()) }
        if (genres.isEmpty()) {
            document.select(
                "a[href*='/kategori/'],a[href*='/tur/'],a[href*='/turler/'],a[href*='/genre/']"
            ).forEach { link ->
                val href = link.attr("href").lowercase()
                if (href.contains("benzer") || href.contains("filtre")) return@forEach
                add(link.text())
            }
        }

        if (genres.isEmpty()) {
            val raw = Regex(
                """(?is)(?:Türler|Tür|Genres?)\s*:?\s*(.{1,180}?)(?=\s+(?:Platform|Oyuncular|Sezon|Dil|Tarih|Ülke|Ekip|Bölümler)\b|$)"""
            ).find(pageText)?.groupValues?.getOrNull(1)
            raw?.split(',', '•', '|')?.forEach(::add)
        }
        return genres.values.take(20)
    }

    private fun actorPhoto(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        for (attribute in listOf(
            "data-profile-image", "data-profile", "data-avatar", "data-portrait",
            "data-photo", "data-image", "data-original", "data-lazy-src",
            "data-src", "src", "srcset"
        )) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (attribute == "srcset") {
                raw.substringBefore(',').trim().substringBefore(' ').trim()
            } else raw
            val url = normalizeUrl(candidate, baseUrl)
            if (url.isBlank()) continue
            if (Regex("""(?i)(logo|favicon|placeholder|no[-_]?image|blank|spacer|pixel|\.svg(?:$|\?))""")
                    .containsMatchIn(url)) continue
            return url
        }

        val background = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)
        return background?.let { normalizeUrl(it, baseUrl) }
    }

    private fun extractActors(document: org.jsoup.nodes.Document, baseUrl: String): List<ActorData> {
        val actors = LinkedHashMap<String, ActorData>()
        val actorLinks = document.select(
            "a[href*='/oyuncu/'],a[href*='/oyuncular/'],a[href*='/actor/'],a[href*='/actors/'],a[href*='/person/']"
        )

        actorLinks.forEach { link ->
            val image = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.parent()?.parent()?.selectFirst("img")
            val name = sequenceOf(
                link.selectFirst("[class*=name],h3,h4")?.text(),
                link.attr("title"),
                link.attr("aria-label"),
                link.text(),
                image?.attr("alt"),
                image?.attr("title")
            ).mapNotNull { it?.trim() }
                .map(::cleanCardTitle)
                .firstOrNull { it.length in 2..80 && !it.equals("dizipal", true) }
                ?: return@forEach

            val photo = actorPhoto(image, image?.baseUri()?.ifBlank { baseUrl } ?: baseUrl)
                ?: image?.let { actorPhoto(it, baseUrl) }
            val key = normalizeMetadataLabel(name)
            actors.putIfAbsent(key, ActorData(Actor(name, photo)))
        }

        // Fallback for cast cards whose profile anchor is not /oyuncu/.
        if (actors.isEmpty()) {
            val castHeadingLinks = linksNearHeading(
                document,
                setOf("oyuncular", "oyuncu kadrosu", "cast")
            ) { it.selectFirst("img") != null }
            castHeadingLinks.forEach { card ->
                val image = card.selectFirst("img")
                val name = sequenceOf(
                    card.selectFirst("h3,h4,[class*=name]")?.text(),
                    image?.attr("alt"),
                    card.attr("title"),
                    card.text()
                ).mapNotNull { it?.trim() }
                    .map(::cleanCardTitle)
                    .firstOrNull { it.length in 2..80 && !it.equals("dizipal", true) }
                    ?: return@forEach
                val photo = actorPhoto(image, image?.baseUri()?.ifBlank { mainUrl } ?: mainUrl)
                actors.putIfAbsent(normalizeMetadataLabel(name), ActorData(Actor(name, photo)))
            }
        }

        return actors.values.take(50)
    }

    private fun extractSimilarItems(
        document: org.jsoup.nodes.Document,
        currentUrl: String
    ): List<SearchResponse> {
        val current = normalizeUrl(currentUrl, mainUrl).trimEnd('/')
        val containers = LinkedHashSet<Element>()

        document.select(
            "section[class*=similar],div[class*=similar],section[id*=similar],div[id*=similar]," +
                "section[class*=related],div[class*=related],section[id*=related],div[id*=related]," +
                "section[class*=recommend],div[class*=recommend],section[id*=recommend],div[id*=recommend]"
        ).forEach { containers.add(it) }

        val similarHeadings = document.select("h2,h3,h4,h5,strong,b")
            .filter {
                val label = normalizeMetadataLabel(it.text())
                label.contains("benzer") || label.contains("similar") ||
                    label.contains("ilgili") || label.contains("onerilen")
            }
        similarHeadings.forEach headingLoop@ { heading ->
            var parent: Element? = heading.parent()
            repeat(5) {
                val currentParent = parent ?: return@repeat
                val candidates = currentParent.select("a[href]").filter { anchor ->
                    val url = normalizeUrl(anchor.attr("href"), currentUrl)
                    isDiziPalSiteUrl(url) && isDiziPalContentUrl(url)
                }
                if (candidates.isNotEmpty() && candidates.size <= 40) {
                    containers.add(currentParent)
                    return@headingLoop
                }
                parent = currentParent.parent()
            }
        }

        val results = LinkedHashMap<String, SearchResponse>()
        containers.forEach containerLoop@ { container ->
            container.select("a[href]").forEach anchorLoop@ { anchor ->
                val item = anchor.toSearchResult() ?: return@anchorLoop
                if (item.url.trimEnd('/').equals(current, true)) return@anchorLoop
                results.putIfAbsent(item.url.trimEnd('/'), item)
            }
        }

        return results.values.take(30)
    }

    // ================= DETAY / SEZON / BÖLÜM =================
    private fun seasonNumberFromUrl(url: String): Int? {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url)
        return Regex("""(?i)sezon-(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)/season/(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodeNumbersFromUrl(url: String): Pair<Int, Int>? {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url)
        val match = Regex("""(?i)-(\d{1,2})x(\d{1,3})(?:[-/]|$)""").find(path) ?: return null
        val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (season !in 1..100 || episode !in 1..10000) return null
        return season to episode
    }

    private fun seasonUrlFromEpisode(url: String): String? {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val match = Regex("""(?i)^/bolum/(.+)-(\d{1,2})x(\d{1,3})/?$""").find(path) ?: return null
        val slug = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val season = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return "$mainUrl/sezon/$slug/sezon-$season"
    }

    private fun collectSeasonUrls(document: org.jsoup.nodes.Document, baseUrl: String): Set<String> {
        val urls = LinkedHashSet<String>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach
            val url = normalizeUrl(href, baseUrl)
            if (!isDiziPalSiteUrl(url)) return@forEach
            val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            if (path.startsWith("/sezon/")) urls.add(url)
        }
        return urls
    }

    private fun episodeLabel(anchor: Element, episodeNumber: Int): String {
        var label = anchor.selectFirst("h2,h3,h4,[class*=episode-title],[class*=title],[class*=name]")?.text()
            .orEmpty().ifBlank { anchor.attr("title") }
            .ifBlank { anchor.attr("aria-label") }
            .ifBlank { anchor.ownText() }
            .ifBlank { anchor.text() }
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        label = label
            .replace(Regex("""(?i)^\s*\d+\s*\.?\s*"""), "")
            .replace(Regex("""(?i)^\s*\d+\s*\.?\s*(?:bölüm|episode)\s*[-:•]?\s*"""), "")
            .replace(Regex("""(?i)^\s*\d+\s*\.?\s*"""), "")
            .replace(Regex("""(?i)^\s*(?:bölüm|episode)\s*[-:•]?\s*"""), "")
            .replace(Regex("""(?i)\s+\d{1,2}\s+[\p{L}]+\s+20\d{2}\b.*$"""), "")
            .replace(Regex("""(?i)\s+(?:sıradaki|izlendi|izle|oynat).*$"""), "")
            .replace(Regex("""(?i)\s*[-|·]\s*(?:dizipal|türkçe dublaj|altyazılı).*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':', '•', '·')

        return label.takeIf { it.length >= 2 && !it.equals("dizipal", true) }
            ?: "$episodeNumber. Bölüm"
    }

    private fun parseEpisodeLinks(
        document: org.jsoup.nodes.Document,
        baseUrl: String,
        defaultSeason: Int? = null
    ): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach
            val episodeUrl = normalizeUrl(href, baseUrl)
            if (!isDiziPalSiteUrl(episodeUrl)) return@forEach

            val path = runCatching { java.net.URI(episodeUrl).path.orEmpty().lowercase() }.getOrDefault("")
            if (!path.startsWith("/bolum/")) return@forEach
            val numbers = episodeNumbersFromUrl(episodeUrl) ?: return@forEach
            val season = numbers.first.takeIf { it > 0 } ?: defaultSeason ?: return@forEach
            val episodeNumber = numbers.second
            val card = cardFor(anchor)
            val poster = imageUrl(anchor.selectFirst("img"), episodeUrl)
                ?: imageUrl(card.selectFirst("img, [data-poster], [style*=background-image]"), episodeUrl)
                ?: imageUrl(card, episodeUrl)
            val label = episodeLabel(anchor, episodeNumber)
            episodes[episodeUrl] = newEpisode(episodeUrl) {
                name = label
                this.season = season
                this.episode = episodeNumber
                posterUrl = poster
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    private suspend fun getDocument(url: String, referer: String): org.jsoup.nodes.Document? {
        val response = runCatching {
            app.get(url, headers = headers, referer = referer, timeout = 20000, allowRedirects = true)
        }.onFailure {
            Log.e("DiziPal", "Sayfa isteği başarısız: $url / ${it.javaClass.simpleName}")
        }.getOrNull() ?: return null
        if (!response.isSuccessful) {
            Log.e("DiziPal", "Sayfa HTTP ${response.code}: $url")
            return null
        }
        return response.document
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url, mainUrl)
        if (!isDiziPalSiteUrl(pageUrl) || !isDiziPalContentUrl(pageUrl)) {
            throw ErrorLoadingException("DiziPal içerik bağlantısı tanınmadı")
        }

        Log.d("DiziPal", "Detay isteği: $pageUrl")
        val document = getDocument(pageUrl, "$mainUrl/")
            ?: throw ErrorLoadingException("DiziPal detay sayfası açılamadı")

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.length > 1 && !it.equals("Dizipal", true) }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("""(?i)^\s*dizipal\s*"""), "")
                ?.replace(Regex("""(?i)\s+(?:1080p|720p|4k).*$"""), "")
                ?.trim()
            ?: slugTitle(pageUrl)

        val poster = imageUrl(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image]"),
            pageUrl
        ) ?: imageUrl(
            document.selectFirst("main img, article img, .poster img, img[alt*=afiş]"),
            pageUrl
        ) ?: imageUrl(document.selectFirst("img"), pageUrl)

        val pageText = document.text()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[class*=description], [class*=overview], [class*=synopsis], [itemprop=description]")?.text()
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("main p, article p")?.text()?.takeIf { it.length > 40 }
        val year = Regex("""(?<!\d)(?:18|19|20|21)\d{2}(?!\d)""")
            .find(pageText)?.value?.toIntOrNull()
        val rating = scoreFrom(pageText)
        val genres = extractGenres(document, pageText)
        val actors = extractActors(document, pageUrl)
        val recommendations = extractSimilarItems(document, pageUrl)

        Log.d(
            "DiziPal",
            "Detay metadata: tür=${genres.size}, oyuncu=${actors.size}, benzer=${recommendations.size}, IMDb=${rating ?: "yok"}"
        )

        if (!isDiziPalSeriesUrl(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
                rating?.let { this.score = Score.from10(it) }
            }
        }

        val seasonUrls = LinkedHashSet<String>()
        seasonUrls.addAll(collectSeasonUrls(document, pageUrl))

        val firstEpisode = parseEpisodeLinks(document, pageUrl).firstOrNull()
        if (seasonUrls.isEmpty() && firstEpisode != null) {
            val episodeDoc = getDocument(firstEpisode.data, pageUrl)
            if (episodeDoc != null) {
                seasonUrls.addAll(collectSeasonUrls(episodeDoc, firstEpisode.data))
            }
            if (seasonUrls.isEmpty()) {
                seasonUrlFromEpisode(firstEpisode.data)?.let { seasonUrls.add(it) }
            }
        }

        val allEpisodes = LinkedHashMap<String, Episode>()
        val visitedSeasons = LinkedHashSet<String>()
        var index = 0
        while (index < seasonUrls.size && visitedSeasons.size < 60) {
            val seasonUrl = seasonUrls.elementAt(index++)
            if (!visitedSeasons.add(seasonUrl)) continue
            val seasonDoc = getDocument(seasonUrl, pageUrl) ?: continue

            collectSeasonUrls(seasonDoc, seasonUrl).forEach { if (it !in visitedSeasons) seasonUrls.add(it) }

            val defaultSeason = seasonNumberFromUrl(seasonUrl)
            parseEpisodeLinks(seasonDoc, seasonUrl, defaultSeason).forEach { episode ->
                allEpisodes.putIfAbsent(episode.data, episode)
            }
        }

        if (allEpisodes.isEmpty()) {
            parseEpisodeLinks(document, pageUrl).forEach { allEpisodes.putIfAbsent(it.data, it) }
        }

        val episodes = allEpisodes.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
        Log.d("DiziPal", "Dizi detay: $title, sezon sayfası=${visitedSeasons.size}, bölüm=${episodes.size}")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = genres
            this.actors = actors
            this.recommendations = recommendations
            rating?.let { this.score = Score.from10(it) }
        }
    }

    // ================= OYNATICI (KRİTİK DÜZELTME) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = normalizeUrl(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        Log.d("DiziPal", "loadLinks başladı: $sourceUrl")
        val response = runCatching {
            app.get(sourceUrl, headers = headers, referer = "$mainUrl/", timeout = 15000, allowRedirects = true)
        }.onFailure {
            Log.e("DiziPal", "Video sayfası isteği başarısız: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return false
        Log.d("DiziPal", "Video sayfası HTTP: ${response.code}, içerik uzunluğu: ${response.text.length}")
        if (!response.isSuccessful) {
            Log.e("DiziPal", "Video sayfası HTTP hatası: ${response.code}")
            return false
        }

        val document = response.document
        val raw = buildString {
            append(response.text); append("\n"); append(document.html())
            document.select("script, noscript, template").forEach {
                append("\n"); append(it.data()); append("\n"); append(it.html())
            }
        }.decodeEscapes()

        var found = false

        // 1) Kaynak sayfada doğrudan m3u8
        for (media in findMediaUrls(raw)) {
            if (emitMediaLink(media, sourceUrl, callback)) found = true
        }

        // 2) Şifreli config -> player URL
        val encrypted = extractEncryptedConfig(raw)
        if (encrypted != null) {
            val player = decryptDizipalConfig(encrypted)
            if (player.isNotBlank()) {
                Log.d("DiziPal", "Player URL: " + player.take(200))

                val playerWithScheme = when {
                    player.startsWith("//") -> "https:$player"
                    !player.startsWith("http") -> "https://$player"
                    else -> player
                }
                val iframeUrl = normalizeUrl(playerWithScheme, sourceUrl)
                Log.d("DiziPal", "Iframe URL: $iframeUrl")

                // ★★★ KRİTİK: İframe URL'sindeki ?v= parametresini çıkar ★★★
                // Örnek: https://four.dplayer82.site/iframe.php?v=0ec9a3... -> "0ec9a3..."
                val vParam = Regex("""[?&]v=([^&]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
                Log.d("DiziPal", "v param: $vParam")

                val iframeHeaders = headers + mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to sourceUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                )

                val iframeResponse = runCatching {
                    app.get(iframeUrl, headers = iframeHeaders, referer = sourceUrl, timeout = 15000, allowRedirects = true)
                }.getOrNull()

                if (iframeResponse != null && iframeResponse.isSuccessful) {
                    Log.d("DiziPal", "Iframe başarılı: ${iframeResponse.code}")
                    val iframeHtml = iframeResponse.text.decodeEscapes()

                    // Altyazılar
                    if (extractSubtitles(iframeHtml, iframeUrl, subtitleCallback)) found = true

                    // ★★★ ASIL ÇÖZÜM: v param ile doğrudan source2.php çağır ★★★
                    if (!vParam.isNullOrBlank()) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val source2Url = origin.trimEnd('/') + "/source2.php?v=" + URLEncoder.encode(vParam, "UTF-8")
                        Log.d("DiziPal", "Source2 URL (v param): $source2Url")

                        val source2Response = runCatching {
                            app.get(
                                source2Url,
                                headers = iframeHeaders + mapOf(
                                    "Accept" to "application/json,text/plain,*/*",
                                    "X-Requested-With" to "XMLHttpRequest",
                                ),
                                referer = iframeUrl,
                                timeout = 15000,
                                allowRedirects = true,
                            )
                        }.getOrNull()

                        if (source2Response != null && source2Response.isSuccessful) {
                            val source2Text = source2Response.text.decodeEscapes()
                            Log.d("DiziPal", "Source2 yanıtı: ${source2Text.take(500)}")

                            // m3u8 / mp4
                            for (media in extractFileUrls(source2Text)) {
                                if (emitMediaLink(media, iframeUrl, callback)) found = true
                            }
                            // Altyazı
                            if (extractSubtitles(source2Text, iframeUrl, subtitleCallback)) found = true
                        } else {
                            Log.e("DiziPal", "Source2 başarısız: ${source2Response?.code}")
                        }
                    }

                    // Alternatif: window.openPlayer (eski desen) - v param yoksa veya farklıysa
                    val playlistId = Regex("""window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                        .find(iframeHtml)?.groupValues?.getOrNull(1)
                    if (!playlistId.isNullOrBlank() && playlistId != vParam) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val s2Url = origin.trimEnd('/') + "/source2.php?v=" + URLEncoder.encode(playlistId, "UTF-8")
                        Log.d("DiziPal", "Source2 URL (openPlayer): $s2Url")
                        val s2 = runCatching {
                            app.get(s2Url, headers = iframeHeaders, referer = iframeUrl, timeout = 15000, allowRedirects = true)
                        }.getOrNull()
                        if (s2 != null && s2.isSuccessful) {
                            for (media in extractFileUrls(s2.text.decodeEscapes())) {
                                if (emitMediaLink(media, iframeUrl, callback)) found = true
                            }
                        }
                    }

                    // İframe HTML'inde doğrudan m3u8
                    for (media in findMediaUrls(iframeHtml)) {
                        if (emitMediaLink(media, iframeUrl, callback)) found = true
                    }

                    // JSON'daki "file":"...", "url":"...", "src":"..." desenleri
                    val patterns = listOf(
                        Regex(""""file"\s*:\s*"([^"]+)""""),
                        Regex(""""url"\s*:\s*"([^"]+)""""),
                        Regex(""""source"\s*:\s*"([^"]+)""""),
                        Regex(""""src"\s*:\s*"([^"]+)""""),
                        Regex("""file\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""url\s*:\s*['"]([^'"]+)['"]"""),
                    )
                    for (p in patterns) {
                        p.findAll(iframeHtml).forEach { m ->
                            val candidate = m.groupValues[1].decodeEscapes()
                            if (candidate.contains(".m3u8") || candidate.contains(".mp4")) {
                                if (emitMediaLink(candidate, iframeUrl, callback)) found = true
                            }
                        }
                    }
                } else {
                    Log.e("DiziPal", "Iframe başarısız: ${iframeResponse?.code}")
                }
            } else {
                Log.e("DiziPal", "Şifre çözme başarısız, player URL boş.")
            }
        } else {
            Log.e("DiziPal", "Şifreli config bulunamadı.")
        }

        // 3) Kaynak sayfada iframe/video etiketleri
        document.select("iframe[src], iframe[data-src], video[src], video source[src], source[src], [data-video-url], [data-stream]").forEach { el ->
            val rawUrl = el.attr("src").ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-video-url") }.ifBlank { el.attr("data-stream") }
            if (rawUrl.isBlank()) return@forEach
            val target = normalizeUrl(rawUrl, sourceUrl)
            if (isMediaUrl(target) && emitMediaLink(target, sourceUrl, callback)) found = true
        }

        // 4) Track tag'leri
        document.select("track[src], track[data-src]").forEach { track ->
            val rawUrl = track.attr("src").ifBlank { track.attr("data-src") }
            if (rawUrl.isBlank()) return@forEach
            runCatching {
                subtitleCallback(newSubtitleFile(
                    lang = track.attr("label").ifBlank { "Türkçe" },
                    url = normalizeUrl(rawUrl, sourceUrl)
                ))
            }
        }

        if (!found) Log.e("DiziPal", "Hiçbir oynatma linki bulunamadı.")
        return found
    }

    // ================= YARDIMCI FONKSİYONLAR =================
    private fun normalizeUrl(url: String, base: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { java.net.URI(base).resolve(url).toString() }
        catch (_: Exception) { base.trimEnd('/') + "/" + url.trimStart('/') }
    }

    private fun String.decodeEscapes(): String = this
        .replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")

    private fun originOf(url: String): String? = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    } catch (_: Exception) { null }

    private fun isMediaUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".m3u8") || u.endsWith(".mp4") || u.endsWith(".mpd") ||
                u.contains(".m3u8?") || u.contains(".mp4?") || u.contains(".mpd?") ||
                u.contains("/master.m3u8") || u.contains("/playlist.m3u8")
    }

    private fun findMediaUrls(text: String): List<String> {
        val regex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.distinct().toList()
    }

    private fun extractFileUrls(text: String): List<String> {
        val results = mutableListOf<String>()
        results.addAll(findMediaUrls(text))
        Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes(); if (raw.isNotBlank()) results.add(raw)
        }
        Regex("""src\s*=\s*["']([^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            results.add(it.groupValues[1].decodeEscapes())
        }
        return results.distinct()
    }

    private fun extractEncryptedConfig(text: String): String? {
        val patterns = listOf(
            Regex("""(?:const|let|var)\s+_?config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:const|let|var)\s+_?enc\w*\s*=\s*['"]([^'"]{20,})['"]""", RegexOption.IGNORE_CASE),
            Regex(""""config"\s*:\s*"([^"]{20,})"""", RegexOption.IGNORE_CASE)
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
        }.getOrElse { runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }.getOrDefault(clean) }
    }

    private suspend fun emitMediaLink(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val finalUrl = normalizeUrl(url, referer)
        if (!isMediaUrl(finalUrl)) return false
        val type = when {
            finalUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            finalUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url = finalUrl,
            type = type
        ) {
            this.referer = referer
            this.headers = mediaHeaders(referer)
            this.quality = Qualities.P1080.value
        })
        Log.d("DiziPal", "Link eklendi: $finalUrl")
        return true
    }

    private fun mediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to referer,
        "Origin" to (originOf(referer) ?: referer),
        "Accept" to "*/*"
    )

    private suspend fun extractSubtitles(text: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit): Boolean {
        var any = false
        Regex(""""subtitle"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes()
            if (raw.isBlank()) return@forEach
            runCatching { subtitleCallback(newSubtitleFile(lang = "Türkçe", url = normalizeUrl(raw, baseUrl))); any = true }
        }
        Regex("""<track[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes()
            if (raw.isBlank()) return@forEach
            runCatching { subtitleCallback(newSubtitleFile(lang = "Türkçe", url = normalizeUrl(raw, baseUrl))); any = true }
        }
        return any
    }
}
