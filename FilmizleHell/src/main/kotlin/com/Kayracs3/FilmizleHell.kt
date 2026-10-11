package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class FilmizleHell : MainAPI() {
    override var mainUrl = "https://filmizlehell.net"
    override var name = "FilmizleHell"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/tur/1-aksiyon-14k" to "Aksiyon",
        "$mainUrl/tur/7-macera-1jy" to "Macera",
        "$mainUrl/tur/8-bilim-kurgu-pt1" to "Bilim-Kurgu",
        "$mainUrl/tur/9-savas-14f" to "Savaş",
        "$mainUrl/tur/10-dram-12w" to "Dram",
        "$mainUrl/tur/11-yerli-film-dv3" to "Yerli Filmler",
        "$mainUrl/tur/12-gerilim-1xr" to "Gerilim",
        "$mainUrl/tur/13-komedi-1wl" to "Komedi",
        "$mainUrl/tur/15-belgesel-170" to "Belgesel",
        "$mainUrl/tur/16-aile-1kf" to "Aile",
        "$mainUrl/tur/17-fantastik-zt1" to "Fantastik"
    )

    private val yearRegex = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
    private val mediaRegex = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'<>\\\s]*)?""")
    private val subtitleRegex = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?""")

    private fun decode(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\u003F", "?", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value == "#" ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("about:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:" + value
                else -> URI(base).resolve(value).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    private fun isFilmUrl(url: String): Boolean =
        Regex("""(?i)^/film/\d+-[^/]+/?$""").matches(pathOf(url))

    private fun sameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host == "filmizlehell.net" || host == "www.filmizlehell.net"
    }

    private fun cleanText(value: String?): String =
        value.orEmpty().replace(Regex("""\s+"""), " ").trim()

    private fun cleanTitle(raw: String?): String = cleanText(raw)
        .replace(Regex("""(?i)\s*[-|–]\s*Film izle Hell.*$"""), "")
        .replace(Regex("""(?i)\s*\|\s*FilmizleHell.*$"""), "")
        .replace(Regex("""(?i)\s+Türkçe\s+(?:Dublaj|Altyazılı).*$"""), "")
        .replace(Regex("""(?i)\s+(?:Dublajlı|Altyazılı).*$"""), "")
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .replace(Regex("""(?i)\s+IMDb\s*[:⭐★]?\s*\d+(?:[.,]\d+)?\s*$"""), "")
        .trim(' ', '-', '|', '–', ':', '★', '⭐')

    private suspend fun fetchDocument(url: String, referer: String = mainUrl): Document? =
        runCatching {
            app.get(
                url,
                headers = requestHeaders + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 18000
            )
        }.onFailure { Log.w(name, "Sayfa alınamadı: " + url, it) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-image",
            "data-poster", "data-poster-url", "data-lazy", "srcset", "data-srcset", "src"
        )
        for (attr in attrs) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (attr.contains("srcset", true)) {
                raw.split(",").maxByOrNull { part ->
                    Regex("""(\d+)(?:w|x)""").find(part)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }?.trim()?.substringBefore(" ").orEmpty()
            } else raw
            val absolute = fixUrl(candidate, base) ?: continue
            val path = pathOf(absolute).lowercase(Locale.ROOT)
            if (listOf("/icons/", "/avatars/", "/favicon", "/logo", "/placeholder").any { path.contains(it) }) continue
            if (Regex("""(?i)\.(?:jpe?g|png|webp)(?:[?#].*)?$""").containsMatchIn(absolute)) return absolute
            if (path.contains("/storage/posters/")) return absolute
        }
        val style = element.attr("style")
        val rawBackground = Regex("""(?i)url\(['"]?([^)'"]+)""").find(style)?.groupValues?.getOrNull(1)
        return fixUrl(rawBackground, base)
    }

    private fun imageFor(link: Element, base: String): String? {
        val candidates = listOf(
            link.selectFirst("img"),
            link.parent()?.selectFirst("img"),
            link.parent()?.parent()?.selectFirst("img")
        )
        for (image in candidates) {
            val result = imageUrl(image, base)
            if (result != null) return result
        }
        return null
    }

    private fun extractScore(text: String?): Score? {
        val value = text.orEmpty()
        val imdb = Regex("""(?is)\bIMDb\b[^\d]{0,50}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
            .find(value)?.groupValues?.getOrNull(1)
        val number = imdb ?: return null
        val score = number.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 } ?: return null
        return runCatching { Score.from10(score) }.getOrNull()
    }

    private fun scoreFromCard(element: Element): Score? {
        val candidates = listOf(
            element.selectFirst("[data-imdb]")?.attr("data-imdb"),
            element.selectFirst("[data-rating]")?.attr("data-rating"),
            element.selectFirst(".imdb, .imdb-rating, .rating, .score, .film-puan")?.text(),
            element.attr("aria-label"),
            element.attr("title")
        )
        for (raw in candidates) {
            val number = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(raw.orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (number in 0.0..10.0) return runCatching { Score.from10(number) }.getOrNull()
        }
        return extractScore(element.text())
    }

    private fun parseCards(document: Document, base: String, query: String? = null): List<SearchResponse> {
        val output = LinkedHashMap<String, SearchResponse>()
        for (anchor in document.select("a[href]")) {
            val url = fixUrl(anchor.attr("href"), base) ?: continue
            if (!sameSite(url) || !isFilmUrl(url)) continue

            val image = anchor.selectFirst("img")
                ?: anchor.parent()?.selectFirst("img")
                ?: anchor.parent()?.parent()?.selectFirst("img")
            val titleText = anchor.attr("title").takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("h2,h3,h4,.title,.film-title,.movie-title")?.text()
                ?: anchor.text()
                .ifBlank { anchor.parent()?.text().orEmpty() }
            val title = cleanTitle(titleText)
            if (title.length < 2 || title.equals("Film izle Hell", true)) continue
            if (query != null && !matchesQuery(title, query)) continue

            val poster = imageFor(anchor, base)
            val cardContext = anchor.parent()?.parent() ?: anchor.parent() ?: anchor
            val score = scoreFromCard(cardContext)
            output.putIfAbsent(
                url.trimEnd('/'),
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    this.score = score
                }
            )
        }
        return output.values.toList()
    }

    private fun matchesQuery(title: String, query: String): Boolean {
        fun normalize(input: String): String = input.lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val t = normalize(title)
        val q = normalize(query)
        if (q.isBlank()) return false
        if (t.contains(q)) return true
        val parts = q.split(' ').filter { it.length > 1 }
        return parts.isNotEmpty() && parts.all { t.contains(it) }
    }

    private fun pageCandidates(base: String, page: Int): List<String> {
        if (page <= 1) return listOf(base)
        val separator = if (base.contains('?')) "&" else "?"
        return listOf(
            base.trimEnd('/') + "/sayfa/" + page,
            base + separator + "page=" + page,
            base + separator + "p=" + page,
            base + separator + "sayfa=" + page
        ).distinct()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        for (url in pageCandidates(request.data, page)) {
            val doc = fetchDocument(url) ?: continue
            val items = parseCards(doc, url)
            if (items.isNotEmpty() || page == 1) {
                return newHomePageResponse(request.name, items, hasNext = page < 30 && items.isNotEmpty())
            }
        }
        return null
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/arama?q=" + encoded,
            "$mainUrl/search?q=" + encoded,
            "$mainUrl/ara?q=" + encoded,
            "$mainUrl/?q=" + encoded,
            "$mainUrl/?s=" + encoded,
            "$mainUrl/ara/" + encoded
        ).distinct()

        for (url in urls) {
            val doc = fetchDocument(url) ?: continue
            val results = parseCards(doc, url, q)
            if (results.isNotEmpty()) return newSearchResponseList(results, false)
        }

        // Site şablonundaki arama formunun gerçek action/name alanlarını dene.
        val home = fetchDocument(mainUrl) ?: return newSearchResponseList(emptyList(), false)
        for (form in home.select("form")) {
            val input = form.select("input[name]").firstOrNull {
                val nameAttr = it.attr("name").lowercase(Locale.ROOT)
                val hint = (nameAttr + " " + it.attr("placeholder") + " " + it.attr("type")).lowercase(Locale.ROOT)
                hint.contains("search") || hint.contains("query") || hint.contains("ara") || hint.contains("keyword") || hint == "q text"
            } ?: continue
            val action = fixUrl(form.attr("action").ifBlank { mainUrl }, mainUrl) ?: continue
            val field = input.attr("name").ifBlank { "q" }
            val formUrl = action + (if (action.contains('?')) "&" else "?") +
                URLEncoder.encode(field, "UTF-8") + "=" + encoded
            val doc = fetchDocument(formUrl) ?: continue
            val results = parseCards(doc, formUrl, q)
            if (results.isNotEmpty()) return newSearchResponseList(results, false)
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    private fun detailContext(document: Document): Element {
        val heading = document.selectFirst("main h1, article h1, h1") ?: return document.body()
        var node: Element? = heading
        var fallback: Element = heading.parent() ?: document.body()
        for (i in 0..7) {
            val current = node ?: break
            val text = current.text()
            if (text.length in 50..7000) {
                fallback = current
                val hasMovieMetadata = Regex("""(?i)IMDb|oyuncu kadrosu|fragman|\b\d{4}\b""").containsMatchIn(text)
                if (hasMovieMetadata) return current
            }
            node = current.parent()
        }
        return fallback
    }

    private fun parseYear(document: Document, context: Element): Int? {
        val sources = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title(),
            document.selectFirst("time[datetime], [itemprop=datePublished]")?.let {
                it.attr("datetime").ifBlank { it.attr("content") }.ifBlank { it.text() }
            },
            context.text()
        )
        for (source in sources) {
            val year = yearRegex.find(source.orEmpty())?.value?.toIntOrNull()
            if (year != null && year in 1870..2100) return year
        }
        return null
    }

    private fun parseRuntime(document: Document, context: Element): Int? {
        val text = context.text() + " " + document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val minutes = Regex("""(?i)(\d{1,3})\s*dk\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(\d{1,3})\s* dakika""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return minutes?.takeIf { it in 1..600 }
    }

    private fun parseScore(document: Document): Score? {
        val explicit = document.selectFirst(
            "#imdb-rating, .imdb-rating, .imdb-score, [data-imdb], [itemprop=ratingValue]"
        )?.let { element ->
            element.attr("data-imdb").ifBlank { element.attr("content") }
                .ifBlank { element.attr("title") }.ifBlank { element.text() }
        }
        if (!explicit.isNullOrBlank()) {
            val match = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(explicit)?.value?.replace(',', '.')?.toDoubleOrNull()
            if (match != null && match in 0.0..10.0) return runCatching { Score.from10(match) }.getOrNull()
        }
        val labelled = Regex("""(?is)\bIMDb\b[^\d]{0,60}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        if (labelled != null && labelled in 0.0..10.0) return runCatching { Score.from10(labelled) }.getOrNull()
        return null
    }

    private fun parsePoster(document: Document, context: Element, base: String): String? {
        val poster = context.selectFirst(
            ".poster img, .film-poster img, .movie-poster img, img[alt*=Poster], img[alt*=Afiş], img[itemprop=image]"
        ) ?: document.selectFirst(
            ".poster img, .film-poster img, .movie-poster img, img[alt*=Poster], img[alt*=Afiş], img[itemprop=image], img[src*=storage/posters], img[data-src*=storage/posters]"
        )
        imageUrl(poster, base)?.let { return it }
        val og = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
        imageUrlFromRaw(og, base)?.let { return it }
        return context.select("img").firstNotNullOfOrNull { imageUrl(it, base) }
    }

    private fun imageUrlFromRaw(raw: String?, base: String): String? {
        val fixed = fixUrl(raw, base) ?: return null
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        if (listOf("/icons/", "/avatars/", "/favicon", "/logo").any { path.contains(it) }) return null
        return fixed
    }

    private fun parsePlot(document: Document, context: Element): String? {
        val candidate = context.selectFirst(
            ".film-description, .movie-description, .description, .overview, .plot, .summary, " +
                "[itemprop=description], #description, .film-plot"
        )?.text()?.trim()?.takeIf { it.length > 30 }
        if (candidate != null) return candidate
        return document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.takeIf { it.length > 20 }
    }

    private fun parseGenres(document: Document, context: Element): List<String> {
        val result = LinkedHashSet<String>()
        val anchors = context.select("a[href*=/tur/], a[href*=/kategori/], [itemprop=genre]") +
            document.select("main .genres a, article .genres a, .film-genres a, .movie-genres a")
        for (anchor in anchors) {
            val text = cleanText(anchor.attr("content").ifBlank { anchor.text() })
                .replace(Regex("""(?i)\s+filmleri?$"""), "")
                .replace(Regex("""(?i)\s+filmi$"""), "")
                .trim()
            if (text.length !in 2..35 || text.equals("Filmler", true)) continue
            if (anchor.parents().any {
                    it.tagName() in setOf("nav", "header", "footer") ||
                        it.className().contains("site-menu", true) ||
                        it.className().contains("footer-menu", true)
                }) continue
            result += text
        }
        return result.take(15)
    }

    private fun parseActors(document: Document, context: Element): List<ActorData> {
        val names = LinkedHashSet<String>()
        val anchors = context.select("a[href*=/oyuncu/], [itemprop=actor] a, .cast a, .actors a, .film-cast a") +
            document.select("main a[href*=/oyuncu/], article a[href*=/oyuncu/]")
        for (anchor in anchors) {
            val wholeText = cleanText(anchor.text())
            if (wholeText.isBlank() || wholeText.contains("yönetmen", true) || wholeText.contains("director", true)) continue
            val name = cleanText(
                anchor.selectFirst(".actor-name, .person-name, .name")?.text()
                    .takeUnless { it.isNullOrBlank() }
                    ?: anchor.ownText().takeUnless { it.isBlank() }
                    ?: anchor.attr("title").takeUnless { it.isBlank() }
                    ?: wholeText
            ).replace(Regex("""\s{2,}"""), " ")
            if (name.length in 2..70 && !name.contains("Film izle Hell", true)) names += name
        }
        if (names.isEmpty()) {
            val label = document.getAllElements().firstOrNull {
                it.ownText().trim().trimEnd(':').equals("Oyuncu Kadrosu", true) ||
                    it.ownText().trim().trimEnd(':').equals("Oyuncular", true)
            }
            var node: Element? = label
            repeat(3) {
                node = node?.parent() ?: return@repeat
                node?.select("a[href]").orEmpty().forEach { anchor ->
                    val name = cleanText(anchor.ownText().ifBlank { anchor.attr("title") }.ifBlank { anchor.text() })
                    if (name.length in 2..70 && !name.contains("yönetmen", true)) names += name
                }
            }
        }
        return names.take(35).map { ActorData(Actor(it)) }
    }

    private fun parseTrailer(document: Document, base: String): String? {
        val element = document.select("iframe[src], iframe[data-src], a[href], video[src]").firstOrNull { item ->
            val url = item.attr("src").ifBlank { item.attr("data-src") }.ifBlank { item.attr("href") }
            val label = (item.text() + " " + item.attr("title") + " " + item.className()).lowercase(Locale.ROOT)
            val isTrailer = label.contains("fragman") || label.contains("trailer") ||
                url.contains("youtube.com/embed", true) || url.contains("youtu.be/", true)
            isTrailer && (url.contains("youtube", true) || url.contains("youtu.be", true) || url.contains("vimeo", true))
        } ?: return null
        return fixUrl(element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }, base)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Film detay sayfası açılamadı: " + url, it) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        val context = detailContext(document)
        val title = cleanTitle(
            document.selectFirst("main h1, article h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        )
        if (title.isBlank()) return null

        val poster = parsePoster(document, context, response.url)
        val plot = parsePlot(document, context)
        val year = parseYear(document, context)
        val runtime = parseRuntime(document, context)
        val score = parseScore(document)
        val genres = parseGenres(document, context)
        val actors = parseActors(document, context)
        val trailer = parseTrailer(document, response.url)

        Log.d(
            name,
            "Film bilgisi: title=" + title + ", year=" + year + ", genres=" + genres.size +
                ", actors=" + actors.size + ", score=" + (score != null) + ", poster=" + (poster != null)
        )

        return newMovieLoadResponse(title, response.url, TvType.Movie, response.url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.duration = runtime
            this.score = score
            this.tags = genres
            this.actors = actors
            addTrailer(trailer)
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = pathOf(url).lowercase(Locale.ROOT)
        if (Regex("""\.(?:m3u8|mp4|mpd|webm)$""").containsMatchIn(path)) return true
        return false
    }

    private fun isStaticAsset(url: String): Boolean {
        val path = pathOf(url).lowercase(Locale.ROOT)
        return listOf(
            "/storage/posters/", "/storage/backgrounds/", "/assets/", "/images/",
            "/icons/", "/avatars/", "/css/", "/js/", "/fonts/", "/favicon"
        ).any { path.contains(it) } ||
            Regex("""\.(?:jpe?g|png|gif|webp|svg|ico|css|js|woff2?|ttf)(?:$|\?)""").containsMatchIn(url.lowercase(Locale.ROOT))
    }

    private fun addPlayerCandidate(raw: String?, base: String, direct: MutableSet<String>, embeds: MutableSet<String>) {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'').trimEnd(';', ',')
        val fixed = fixUrl(value, base) ?: return
        if (isStaticAsset(fixed)) return
        if (isDirectMedia(fixed)) {
            if (!fixed.contains("site.webm", true) && !fixed.contains("logo.webm", true)) direct += fixed
            return
        }
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        val host = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank() || host.contains("doubleclick") || host.contains("googlesyndication") ||
            host.contains("google-analytics") || host.contains("facebook.com") || host.contains("t.me")
        ) return
        if (isFilmUrl(fixed)) return
        if (path.contains("embed") || path.contains("player") || path.contains("video") ||
            path.contains("stream") || path.endsWith(".php") || path.endsWith(".html") ||
            path.endsWith(".aspx") || path.endsWith(".asp")
        ) embeds += fixed
        else if (host != "filmizlehell.net" && host != "www.filmizlehell.net") embeds += fixed
    }

    private fun collectPlayerCandidates(document: Document, html: String, base: String): Pair<Set<String>, Set<String>> {
        val direct = LinkedHashSet<String>()
        val embeds = LinkedHashSet<String>()

        val nodes = document.select(
            "iframe[src], iframe[data-src], iframe[data-url], video[src], video[data-src], " +
                "source[src], source[data-src], embed[src], [data-embed], [data-player], [data-video], [data-iframe]"
        )
        val attrs = listOf(
            "src", "data-src", "data-url", "data-embed", "data-player", "data-video",
            "data-iframe", "data-iframe-src", "data-href", "data-link", "data-file", "data-hls"
        )
        for (element in nodes) {
            for (attr in attrs) {
                if (element.hasAttr(attr)) addPlayerCandidate(element.attr(attr), base, direct, embeds)
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            // Track URLs are handled as subtitles in loadLinks; never treat them as video embeds.
            if (track.hasAttr("src") || track.hasAttr("data-src")) Unit
        }

        val decoded = decode(html)
        mediaRegex.findAll(decoded).forEach { addPlayerCandidate(it.value, base, direct, embeds) }
        Regex("""(?is)(?:src|file|url|hls|dash|embed|iframe|player|video|source|stream)\s*["']?\s*[:=]\s*["']([^"'<>]{5,1200})["']""")
            .findAll(decoded).forEach { match ->
                addPlayerCandidate(match.groupValues[1], base, direct, embeds)
            }

        document.select("a[href]").forEach { anchor ->
            val label = (anchor.text() + " " + anchor.attr("title") + " " + anchor.className()).lowercase(Locale.ROOT)
            if (label.contains("kaynak") || label.contains("izle") || label.contains("source") ||
                label.contains("server") || label.contains("oynat") || label.contains("başlat")
            ) {
                addPlayerCandidate(anchor.attr("href"), base, direct, embeds)
                attrs.forEach { attr ->
                    if (anchor.hasAttr(attr)) addPlayerCandidate(anchor.attr(attr), base, direct, embeds)
                }
            }
        }
        return direct to embeds
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
                    .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = requestHeaders + ("Referer" to referer)
            }
        )
    }

    private suspend fun publishSubtitles(document: Document, html: String, base: String, callback: (SubtitleFile) -> Unit) {
        val seen = HashSet<String>()
        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val url = fixUrl(raw, base) ?: continue
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) continue
            if (!seen.add(url)) continue
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            callback(newSubtitleFile(label, url))
        }
        subtitleRegex.findAll(decode(html)).forEach { match ->
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
        val page = runCatching {
            app.get(data, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Oynatıcı sayfası alınamadı: " + data, it) }.getOrNull() ?: return false
        if (!page.isSuccessful) return false

        publishSubtitles(page.document, page.text, page.url, subtitleCallback)
        val (direct, embeds) = collectPlayerCandidates(page.document, page.text, page.url)
        var found = false
        val visited = HashSet<String>()
        val allCandidates = LinkedHashSet<String>().apply {
            addAll(direct)
            addAll(embeds)
        }

        suspend fun resolve(candidate: String, referer: String, depth: Int) {
            if (depth > 2 || !visited.add(candidate)) return
            if (isDirectMedia(candidate)) {
                publishDirect(candidate, referer, callback)
                found = true
                return
            }

            runCatching {
                loadExtractor(candidate, referer, subtitleCallback) {
                    found = true
                    callback(it)
                }
            }.onFailure { Log.w(name, "Extractor başarısız: " + candidate, it) }

            if (depth >= 2) return
            val nested = runCatching {
                app.get(candidate, headers = requestHeaders + ("Referer" to referer), referer = referer, allowRedirects = true, timeout = 12000)
            }.onFailure { Log.d(name, "Embed HTML alınamadı: " + candidate) }.getOrNull() ?: return
            if (!nested.isSuccessful || nested.text.isBlank() || nested.text.length > 4_000_000) return
            publishSubtitles(nested.document, nested.text, nested.url, subtitleCallback)
            val (nextDirect, nextEmbeds) = collectPlayerCandidates(nested.document, nested.text, nested.url)
            nextDirect.forEach { url ->
                if (visited.add(url)) {
                    publishDirect(url, nested.url, callback)
                    found = true
                }
            }
            for (next in nextEmbeds.take(10)) {
                if (sameSite(next) || depth == 0) resolve(next, nested.url, depth + 1)
                else runCatching {
                    loadExtractor(next, nested.url, subtitleCallback) {
                        found = true
                        callback(it)
                    }
                }.onFailure { Log.d(name, "Nested extractor başarısız: " + next) }
            }
        }

        Log.d(name, "loadLinks: direct=" + direct.size + ", embeds=" + embeds.size)
        for (candidate in allCandidates.take(20)) resolve(candidate, page.url, 0)
        Log.d(name, "loadLinks: found=" + found + ", candidates=" + allCandidates.size)
        return found
    }
}
