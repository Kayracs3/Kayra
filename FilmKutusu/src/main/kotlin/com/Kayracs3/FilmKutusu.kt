package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class FilmKutusu : MainAPI() {
    override var mainUrl = "https://filmkutusu.com"
    override var name = "FilmKutusu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/kategori/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/kategori/yabanci-filmler" to "Yabancı Filmler",
        "$mainUrl/kategori/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/kategori/komedi-filmleri" to "Komedi",
        "$mainUrl/kategori/korku-filmleri" to "Korku"
    )

    private val yearRegex = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val mediaRegex = Regex("""(?i)(https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^"'<>\\\s]*)?""")
    private val subtitleRegex = Regex("""(?i)(https?:)?//[^"'<>\\\s]+?\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?""")

    private fun cleanText(raw: String?): String = raw.orEmpty()
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanTitle(raw: String?): String = cleanText(raw)
        .replace(Regex("""(?i)^\s*[▶▷►]+\s*"""), "")
        .replace(Regex("""(?i)\s*\|\s*FilmKutusu.*$"""), "")
        .replace(Regex("""(?i)\s+TR\s*Türkçe.*$"""), "")
        .replace(Regex("""(?i)\s+Türkçe\s+(?:Dublaj|Altyazılı).*$"""), "")
        .replace(Regex("""(?i)\s+IMDb\s*[:⭐★]?\s*\d+(?:[.,]\d+)?\s*$"""), "")
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .replace(Regex("""(?i)\s+film\s+afişi\s*$"""), "")
        .trim(' ', '-', '|', '–', ':', '★', '⭐')

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = cleanText(raw).trim('"', '\'')
        if (value.isBlank() || value == "#" ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("about:", true)
        ) return null

        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun sameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host == "filmkutusu.com" || host == "www.filmkutusu.com"
    }

    private fun imageUrl(image: Element?, base: String): String? {
        if (image == null) return null
        val attributes = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src", "data-srcset", "srcset")
        for (attribute in attributes) {
            val raw = image.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (attribute.contains("srcset")) {
                raw.substringBefore(",").substringBefore(" ").trim()
            } else raw
            val result = fixUrl(candidate, base) ?: continue
            if (!result.contains("logo", true) && !result.contains("avatar", true)) return result
        }
        return null
    }

    private fun imageFor(link: Element, base: String): String? {
        val image = link.selectFirst("img")
            ?: link.parent()?.selectFirst("img")
            ?: link.parent()?.parent()?.selectFirst("img")
            ?: link.parent()?.parent()?.parent()?.selectFirst("img")
        return imageUrl(image, base)
    }

    private fun scoreFromText(raw: String?): Score? {
        val text = raw.orEmpty()
        val imdbValue = Regex("""(?i)IMDb\s*[:：]?\s*[★⭐]?\s*(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
            .find(text)?.groupValues?.getOrNull(1)
            ?: Regex(""""ratingValue"\s*:\s*"?(\d+(?:[.,]\d+)?)"?""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)
            ?: return null
        val value = imdbValue.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 } ?: return null
        return runCatching { Score.from10(value) }.getOrNull()
    }

    private fun matchesQuery(title: String, query: String): Boolean {
        val normalize: (String) -> String = { input ->
            input.lowercase(Locale("tr", "TR"))
                .replace('ı', 'i')
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
        val normalizedTitle = normalize(title)
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return false
        if (normalizedTitle.contains(normalizedQuery)) return true
        val parts = normalizedQuery.split(' ').filter { it.length > 1 }
        return parts.isNotEmpty() && parts.all { normalizedTitle.contains(it) }
    }

    private fun parseCards(document: Document, baseUrl: String, query: String? = null): List<SearchResponse> {
        val output = LinkedHashMap<String, SearchResponse>()
        for (link in document.select("a[href]")) {
            val url = fixUrl(link.attr("href"), baseUrl) ?: continue
            if (!sameSite(url)) continue
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            if (!path.endsWith(".html", true)) continue

            val image = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.parent()?.parent()?.selectFirst("img")
            val rawTitle = link.attr("title").takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("h2,h3,h4,.title,.name")?.text()
                ?: link.text()
            val title = cleanTitle(rawTitle)
            if (title.length < 2) continue
            if (query != null && !matchesQuery(title, query)) continue

            val context = cleanText(link.text() + " " + link.attr("title") + " " +
                link.parent()?.text().orEmpty().take(450))
            val poster = imageFor(link, baseUrl)
            val response = newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                score = scoreFromText(context)
            }
            output.putIfAbsent(url.trimEnd('/'), response)
        }
        return output.values.toList()
    }

    private fun pageUrl(requestUrl: String, page: Int): String {
        if (page <= 1) return requestUrl
        val base = requestUrl.trimEnd('/').replace(Regex("""/sayfa/\d+$"""), "")
        return if (base == mainUrl) "$mainUrl/sayfa/$page" else "$base/sayfa/$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = pageUrl(request.data, page)
        val response = runCatching {
            app.get(url, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Ana sayfa isteği başarısız: $url", it) }.getOrNull()
            ?: return null
        if (!response.isSuccessful) return null

        val items = parseCards(response.document, response.url)
        val hasNext = page < 19 && items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?query=$encoded",
            "$mainUrl/arama?s=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/?search=$encoded"
        ).distinct()

        for (url in urls) {
            val response = runCatching {
                app.get(url, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
            }.onFailure { Log.d(name, "Arama isteği başarısız: $url", it) }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val results = parseCards(response.document, response.url, q)
            if (results.isNotEmpty()) return newSearchResponseList(results, false)
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    private fun detailContext(document: Document): Element {
        val heading = document.selectFirst("main h1, article h1, h1") ?: return document.body()
        var node: Element? = heading
        var fallback: Element = heading.parent() ?: document.body()
        for (i in 0..6) {
            val current = node ?: break
            val text = current.text()
            if (text.length in 30..2200) {
                fallback = current
                val hasYear = yearRegex.containsMatchIn(text)
                val hasRating = Regex("""(?i)IMDb|[★⭐]""").containsMatchIn(text)
                val hasGenre = current.select("a[href*=/kategori/]").isNotEmpty()
                if (hasYear && (hasRating || hasGenre)) return current
            }
            node = current.parent()
        }
        return fallback
    }

    private fun parseYear(document: Document, context: Element): Int? {
        val structured = document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()?.let { json ->
                yearRegex.find(json.optString("datePublished") + " " + json.optString("dateCreated"))?.value?.toIntOrNull()
            }
        }
        if (structured != null) return structured
        val value = document.selectFirst("[itemprop=datePublished], time[datetime], .movie-year, .film-yili, .year")
            ?.let { it.attr("content").ifBlank { it.attr("datetime") }.ifBlank { it.text() } }
        return yearRegex.find(value.orEmpty())?.value?.toIntOrNull()
            ?: yearRegex.find(context.text())?.value?.toIntOrNull()
    }

    private fun parseScore(document: Document, context: Element): Score? {
        val structured = document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()?.let { json ->
                val value = json.optJSONObject("aggregateRating")?.optString("ratingValue").orEmpty()
                value.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            }
        }
        if (structured != null) return runCatching { Score.from10(structured) }.getOrNull()

        for (element in document.select("[itemprop=ratingValue], meta[itemprop=ratingValue], .imdb-rating, .imdb-puan, .imdb, [data-rating]")) {
            val raw = element.attr("content").ifBlank { element.attr("data-rating") }
                .ifBlank { element.attr("title") }.ifBlank { element.text() }
            val value = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(raw)?.value?.replace(',', '.')?.toDoubleOrNull()
                ?: continue
            if (value in 0.0..10.0) return runCatching { Score.from10(value) }.getOrNull()
        }
        return scoreFromText(context.text()) ?: scoreFromText(document.selectFirst("main")?.text())
    }

    private fun parseGenres(context: Element, document: Document): List<String> {
        val found = LinkedHashSet<String>()
        val candidates = context.select("a[href*=/kategori/], [itemprop=genre]") +
            document.select("main .genres a, main .genre a, article .genres a, article .genre a")
        for (element in candidates) {
            val text = cleanText(element.attr("content").ifBlank { element.text() })
                .replace(Regex("""(?i)\s+filmleri?$"""), "")
                .replace(Regex("""(?i)\s+filmi$"""), "")
                .trim()
            if (text.length !in 2..40) continue
            if (text.equals("film", true) || text.equals("dizi", true)) continue
            if (text.contains("tümünü gör", true) || text.contains("film arşivi", true)) continue
            // Yalnız gerçek site navigasyonlarını ele; "header" sözcüğü film detay
            // kartlarında da geçebildiği için tür bağlantılarını yanlışlıkla silme.
            val inNav = element.parents().any { ancestor ->
                val classes = ancestor.classNames().map { it.lowercase(Locale.ROOT) }
                ancestor.tagName() == "nav" || classes.any {
                    it in setOf(
                        "site-header", "site-footer", "main-menu", "primary-menu",
                        "header-menu", "footer-menu", "main-navigation",
                        "primary-navigation", "site-navigation", "nav-menu"
                    )
                }
            }
            if (inNav) continue
            found += text
        }
        return found.take(12)
    }

    private fun parseActors(document: Document): List<ActorData> {
        val names = LinkedHashSet<String>()
        for (script in document.select("script[type=application/ld+json]")) {
            val raw = script.data()
            val actorArray = Regex(""""actor[s]?"\s*:\s*\[(.*?)\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(raw)?.groupValues?.getOrNull(1).orEmpty()
            Regex(""""name"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(actorArray).forEach { match ->
                    match.groupValues.getOrNull(1)?.trim()?.takeIf { it.length in 2..70 }?.let(names::add)
                }
        }

        val label = document.getAllElements().firstOrNull {
            it.ownText().trim().trimEnd(':').equals("Oyuncular", true) ||
                it.ownText().trim().trimEnd(':').equals("Oyuncu Kadrosu", true)
        }
        var node: Element? = label
        for (i in 0..2) {
            node = node?.parent() ?: break
            val text = node.text().take(700)
            if (text.contains("Oyuncu", true) && text.length in 12..700) {
                val after = text.replace(Regex("""(?is)^.*?oyuncu(?:lar| kadrosu)?\s*:?"""), "")
                after.split(",", "•", "|", "\n")
                    .map { cleanText(it).trim(' ', '.', ':') }
                    .filter { it.length in 2..70 && it.split(' ').size <= 7 }
                    .forEach(names::add)
            }
        }
        return names.filterNot { it.equals("Oyuncular", true) || it.equals("Oyuncu Kadrosu", true) }
            .take(30).map { ActorData(Actor(it)) }
    }

    private fun parsePlot(document: Document): String? {
        val text = document.selectFirst(
            "main [itemprop=description], main .plot, main .overview, main .description, " +
                "article .description, article .entry-content, .movie-description, .film-description"
        )?.text()?.trim()?.takeIf { it.length > 30 }
        if (text != null) return text
        return document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.takeIf { it.length > 20 }
    }

    private fun parsePoster(document: Document, context: Element, base: String): String? {
        val meta = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
        fixUrl(meta, base)?.let { return it }
        context.selectFirst("img[alt*=afiş], img[alt*=poster], img[itemprop=image]")?.let {
            imageUrl(it, base)?.let { poster -> return poster }
        }
        context.select("img").firstOrNull()?.let { imageUrl(it, base)?.let { poster -> return poster } }
        document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()).optString("image").takeIf { it.isNotBlank() } }.getOrNull()
        }?.let { fixUrl(it, base)?.let { poster -> return poster } }
        return null
    }

    private fun parseTrailer(document: Document, base: String): String? {
        val element = document.select("iframe[src], meta[property=og:video], a[href]").firstOrNull {
            val url = it.attr("src").ifBlank { it.attr("content") }.ifBlank { it.attr("href") }
            val label = it.text() + " " + it.attr("title")
            (label.contains("fragman", true) || label.contains("trailer", true) ||
                url.contains("youtube.com/embed", true) || url.contains("youtu.be/", true)) &&
                (url.contains("youtube", true) || url.contains("youtu.be", true) || url.contains("vimeo", true))
        } ?: return null
        return fixUrl(element.attr("src").ifBlank { element.attr("content") }.ifBlank { element.attr("href") }, base)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Film sayfası açılamadı: $url", it) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null

        val document = response.document
        val context = detailContext(document)
        val heading = document.selectFirst("main h1, article h1, h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        val title = cleanTitle(heading)
        if (title.isBlank()) return null

        val plot = parsePlot(document)
        val poster = parsePoster(document, context, response.url)
        val year = parseYear(document, context)
        val genres = parseGenres(context, document)
        val score = parseScore(document, context)
        val actors = parseActors(document)
        val trailer = parseTrailer(document, response.url)
        val imdbId = Regex("""https?://(?:www\.)?imdb\.com/title/(tt\d{7,10})""", RegexOption.IGNORE_CASE)
            .find(document.outerHtml())?.groupValues?.getOrNull(1)

        Log.d(name, "Film bilgisi: title=" + title + ", year=" + year + ", genres=" + genres.size + ", actors=" + actors.size + ", score=" + (score != null) + ", poster=" + (poster != null))

        return newMovieLoadResponse(title, response.url, TvType.Movie, response.url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = genres
            this.actors = actors
            addTrailer(trailer)
            if (!imdbId.isNullOrBlank()) {
                syncData["imdb_id"] = imdbId
                syncData["imdb"] = imdbId
            }
        }
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(m3u8|mp4|webm|mpd)(?:[?#].*)?$""").containsMatchIn(url)

    // Static site assets can also end in .webm; they must never be offered as films.
    private fun isLikelyMediaUrl(url: String): Boolean {
        if (!isDirectMedia(url)) return false
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        val staticPathMarkers = listOf(
            "/public/assets/", "/assets/", "/icons/", "/images/",
            "/img/", "/static/", "/favicon"
        )
        if (staticPathMarkers.any { path.contains(it) }) return false
        val fileName = path.substringAfterLast('/')
        if (fileName in setOf("site.webm", "logo.webm", "favicon.webm")) return false
        return true
    }

    private suspend fun publishDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mp4", true) || url.contains(".webm", true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
        callback(newExtractorLink(source = name, name = name, url = url, type = type) {
            this.referer = referer
            this.quality = Regex("""(?i)(2160|1440|1080|720|480|360)""")
                .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            this.headers = requestHeaders + ("Referer" to referer)
        })
    }

    private fun collectMedia(document: Document, html: String, base: String): Pair<Set<String>, Set<String>> {
        val direct = LinkedHashSet<String>()
        val embeds = LinkedHashSet<String>()
        fun addDirect(raw: String?) {
            val fixed = fixUrl(raw, base) ?: return
            if (isLikelyMediaUrl(fixed)) direct += fixed
        }

        document.select("video[src], video source[src], video source[data-src], source[src], source[data-src], a[href]").forEach { element ->
            val raw = if (element.tagName() == "a") element.attr("href")
                else element.attr("src").ifBlank { element.attr("data-src") }
            addDirect(raw)
        }
        document.select("iframe[src], iframe[data-src]").forEach { element ->
            val candidate = fixUrl(element.attr("src").ifBlank { element.attr("data-src") }, base) ?: return@forEach
            if (!candidate.contains("youtube.com", true) && !candidate.contains("youtu.be", true) &&
                !candidate.contains("doubleclick", true) && !candidate.contains("googlesyndication", true)
            ) embeds += candidate
        }
        mediaRegex.findAll(html).forEach { match ->
            val fixed = fixUrl(match.value, base) ?: return@forEach
            if (isLikelyMediaUrl(fixed)) direct += fixed
        }
        return direct to embeds
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = requestHeaders + ("Referer" to mainUrl), referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Video kaynak sayfası açılamadı", it) }.getOrNull() ?: return false
        if (!response.isSuccessful) return false

        val seenSubs = HashSet<String>()
        response.document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitle = fixUrl(raw, response.url) ?: return@forEach
            if (Regex("""(?i)\.(vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(subtitle) && seenSubs.add(subtitle)) {
                subtitleCallback(newSubtitleFile(track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Altyazı" } }, subtitle))
            }
        }
        subtitleRegex.findAll(response.text).forEach { match ->
            val subtitle = fixUrl(match.value, response.url) ?: return@forEach
            if (seenSubs.add(subtitle)) subtitleCallback(newSubtitleFile("Altyazı", subtitle))
        }

        val pageText = response.document.text()
        if (Regex("""(?i)video\s+kalite\s+dosyaları\s+bulunamadı|video\s+dosyaları\s+bulunamadı""")
                .containsMatchIn(pageText)
        ) {
            Log.w(name, "Bu film sayfası video dosyalarının sunucuya yüklenmediğini bildiriyor; oynatılabilir kaynak yok.")
            return false
        }

        val (direct, embeds) = collectMedia(response.document, response.text, response.url)
        var found = false
        direct.forEach { stream ->
            publishDirect(stream, response.url, callback)
            found = true
        }

        for (embed in embeds.take(12)) {
            if (isLikelyMediaUrl(embed)) {
                publishDirect(embed, response.url, callback)
                found = true
                continue
            }
            if (sameSite(embed)) {
                val embeddedResponse = runCatching {
                    app.get(embed, headers = requestHeaders + ("Referer" to response.url), referer = response.url, allowRedirects = true, timeout = 12000)
                }.getOrNull() ?: continue
                if (!embeddedResponse.isSuccessful) continue
                val (nestedDirect, nestedEmbeds) = collectMedia(embeddedResponse.document, embeddedResponse.text, embeddedResponse.url)
                nestedDirect.forEach { stream ->
                    publishDirect(stream, embeddedResponse.url, callback)
                    found = true
                }
                for (nested in nestedEmbeds.take(4)) {
                    if (!sameSite(nested)) loadExtractor(nested, embeddedResponse.url, subtitleCallback, callback)
                }
            } else {
                loadExtractor(embed, response.url, subtitleCallback, callback)
                found = true
            }
        }
        Log.d(name, "loadLinks: direct=" + direct.size + ", embeds=" + embeds.size + ", playableCandidate=" + found)
        return found
    }
}
