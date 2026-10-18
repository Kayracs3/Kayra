package com.Kayracs3

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
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

class DiziAsya : MainAPI() {
    override var mainUrl = "https://diziasya.com"
    override var name = "DiziAsya"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie, TvType.AsianDrama)

    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val requestHeaders = mapOf(
        "User-Agent" to browserUa,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private data class CatalogItem(
        val title: String,
        val url: String,
        val poster: String?,
        val year: Int?,
        val score: Score?,
        val type: TvType
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/programlar" to "Programlar"
    )

    private fun normalize(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim().replace(Regex("\\s+"), " ")
    }

    private fun absoluteUrl(rawValue: String?, base: String = mainUrl): String? {
        var raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) return null
        raw = raw.replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .trim().trim('"', '\'')
        if (raw.isBlank() || raw.startsWith("javascript:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("mailto:", true) ||
            raw.startsWith("about:", true) || raw.startsWith("#")
        ) return null
        return runCatching {
            val resolved = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                else -> URI(if (base.endsWith("/")) base else "$base/").resolve(raw).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#'))

    private fun pathParts(url: String): List<String> =
        pathOf(url).trim('/').split('/').filter { it.isNotBlank() }

    private fun isEpisodeUrl(url: String): Boolean =
        Regex("""(?i)/(?:dizi|anime|program)/[^/]+/sezon-\d+-bolum-\d+/?$""")
            .containsMatchIn(pathOf(url))

    private fun categoryFor(url: String): String? {
        val parts = pathParts(url)
        if (parts.isEmpty()) return null
        val root = parts[0].lowercase(Locale.ROOT)
        if (root !in setOf("dizi", "anime", "film", "program")) return null
        if (parts.size == 2) return root
        if (parts.size == 3 && isEpisodeUrl(url)) return root
        return null
    }

    private fun canonicalUrl(url: String): String {
        val parts = pathParts(url)
        if (parts.size >= 3 && isEpisodeUrl(url)) {
            return mainUrl + "/" + parts[0] + "/" + parts[1]
        }
        return absoluteUrl(url.substringBefore('#').substringBefore('?')) ?: url
    }

    private fun titleFromUrl(url: String): String {
        val slug = pathParts(canonicalUrl(url)).lastOrNull().orEmpty()
            .replace(Regex("""(?i)-sezon-\d+-bolum-\d+.*$"""), "")
            .replace('-', ' ').replace('_', ' ').trim()
        return slug.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("""(?i)\s*\|\s*Diziasya.*$"""), "")
        .replace(Regex("""(?i)\s*[-–]\s*DiziAsya.*$"""), "")
        .replace(Regex("""(?i)\s+(?:dizi|anime|program|film)\s*[·•].*$"""), "")
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*sezon\s+\d+\s*\.?\s*bölüm.*$"""), "")
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*bölüm\s*(?:final)?.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':', '–', '·').trim()

    private fun isGenericTitle(value: String): Boolean {
        val n = normalize(value)
        return n.isBlank() || n in setOf(
            "diziler", "filmler", "animeler", "programlar", "oyuncular", "ana sayfa",
            "tumunu gor", "tumu", "izle", "bolumler", "onceki", "sonraki", "ilk", "son",
            "daha fazla", "giris", "kayit ol", "diziasya"
        ) || n.matches(Regex("\\d{1,4}"))
    }

    private fun isUsablePoster(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val file = pathOf(url).substringAfterLast('/').lowercase(Locale.ROOT)
        return !file.endsWith(".svg") &&
            !Regex("""(?i)(logo|avatar|placeholder|no[-_]?image|transparent|blank|favicon|pixel)""")
                .containsMatchIn(file) &&
            (file.contains('.') || url.contains("/images/", true))
    }

    private fun posterFrom(element: Element?): String? {
        if (element == null) return null
        val nodes = listOf(element) + element.select(
            "img, [data-poster], [data-background], [data-bg], [style*=background]"
        )
        for (node in nodes.distinct()) {
            for (attr in listOf(
                "data-poster", "data-original", "data-lazy-src", "data-src",
                "data-srcset", "srcset", "data-image", "src", "content"
            )) {
                val raw = node.attr(attr).trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) continue
                val candidate = if (attr.contains("srcset")) {
                    raw.substringBefore(',').trim().substringBefore(' ')
                } else raw
                val url = absoluteUrl(candidate, node.baseUri().ifBlank { mainUrl }) ?: continue
                if (isUsablePoster(url)) return url
            }
            val bg = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
                .find(node.attr("style"))?.groupValues?.getOrNull(1)
            val bgUrl = absoluteUrl(bg, node.baseUri().ifBlank { mainUrl })
            if (bgUrl != null && isUsablePoster(bgUrl)) return bgUrl
        }
        return null
    }

    private fun yearFrom(text: String): Int? =
        Regex("""\b(?:19|20|21)\d{2}\b""").find(text)?.value?.toIntOrNull()

    private fun scoreFrom(text: String): Score? {
        val value = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,]\d+)?)(?:\s*\(\d+\))?""")
            .findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 } ?: return null
        return Score.from10(value.toString())
    }

    private fun cardTitle(link: Element, card: Element, url: String): String {
        if (isEpisodeUrl(url)) return titleFromUrl(url)
        val imageTitle = card.selectFirst("img[alt], img[title]")
            ?.let { it.attr("alt").ifBlank { it.attr("title") } }.orEmpty()
        return listOf(
            link.attr("title"),
            card.selectFirst("h1,h2,h3,h4,h5,[class*=title],[class*=name]")?.text().orEmpty(),
            imageTitle, link.ownText(), link.text()
        ).asSequence().map { cleanTitle(it) }
            .firstOrNull { it.isNotBlank() && !isGenericTitle(it) }
            ?: titleFromUrl(url)
    }

    private fun parseCards(document: Document): List<CatalogItem> {
        val results = LinkedHashMap<String, CatalogItem>()
        for (link in document.select("a[href]")) {
            val linkedUrl = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: continue
            val rootKind = categoryFor(linkedUrl) ?: continue
            val resultUrl = canonicalUrl(linkedUrl)
            val type = when (rootKind) {
                "film" -> TvType.Movie
                "anime" -> TvType.Anime
                else -> TvType.TvSeries
            }
            val card = link.closest(
                "article, li, [data-testid*=card], [class*=poster-card], [class*=media-card], [class*=item-card]"
            ) ?: link.parent() ?: link
            val title = cardTitle(link, card, resultUrl)
            if (title.isBlank() || isGenericTitle(title)) continue
            if (normalize(title) in setOf("ilk", "son", "onceki", "sonraki", "sayfa")) continue
            val poster = posterFrom(link) ?: posterFrom(card)
            val cardText = card.text()
            results.putIfAbsent(
                resultUrl,
                CatalogItem(
                    title, resultUrl, poster, yearFrom(title) ?: yearFrom(cardText),
                    scoreFrom(cardText), type
                )
            )
        }
        return results.values.toList()
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse =
        if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = this@toSearchResponse.poster
                year = this@toSearchResponse.year
                score = this@toSearchResponse.score
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                posterUrl = this@toSearchResponse.poster
                year = this@toSearchResponse.year
                score = this@toSearchResponse.score
            }
        }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val clean = base.substringBefore('#')
        val path = clean.substringBefore('?')
        val query = clean.substringAfter('?', "")
        val params = query.split('&').filter { it.isNotBlank() && !it.startsWith("page=") }
        return path + "?" + (params + "page=" + page).joinToString("&")
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val next = page + 1
        for (link in document.select("a[href]")) {
            val raw = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: continue
            val label = normalize(link.text() + " " + link.attr("aria-label") + " " + link.attr("rel"))
            if (label.contains("sonraki") || label == "next" || link.attr("rel").equals("next", true)) return true
            val queryPage = Regex("""(?:[?&])page=(\d+)\b""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val pathPage = Regex("""/(?:page|sayfa)/(\d+)/?$""").find(pathOf(raw))?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (queryPage == next || pathPage == next) return true
        }
        return false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = runCatching {
            app.get(pageUrl(request.data, page), headers = requestHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull()
        val document = response?.takeIf { it.isSuccessful }?.document
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        val items = parseCards(document).map { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page))
    }

    private fun titleMatches(query: String, title: String): Boolean {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        if (t.contains(q)) return true
        val words = q.split(' ').filter { it.length > 1 && it !in setOf("dizi", "izle", "altyazili") }
        return words.isNotEmpty() && words.all { word -> t.split(' ').any { it == word } }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/diziler?search=$encoded",
            "$mainUrl/animeler?search=$encoded"
        )
        val all = LinkedHashMap<String, SearchResponse>()
        for (searchUrl in searchUrls) {
            val document = runCatching {
                app.get(searchUrl, headers = requestHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            for (item in parseCards(document)) {
                if (titleMatches(clean, item.title)) all.putIfAbsent(item.url, item.toSearchResponse())
            }
            if (all.isNotEmpty()) break
        }
        return all.values.toList()
    }

    private fun parsePlot(document: Document): String? {
        val headings = document.select("h1,h2,h3,h4,h5,h6,strong,b")
            .filter { normalize(it.text()).contains("konusu") || normalize(it.text()).contains("ozet") }
        for (heading in headings) {
            var branch: Node? = heading
            var depth = 0
            while (branch != null && depth < 5) {
                var sibling: Node? = branch.nextSibling()
                var scanned = 0
                val found = ArrayList<String>()
                while (sibling != null && scanned < 20) {
                    if (sibling is Element) {
                        val element = sibling
                        val text = element.text().trim()
                        val n = normalize(text)
                        if (element.tagName().matches(Regex("(?i)h[1-6]"))) break
                        if (n.startsWith("oyuncular") || n.startsWith("bolumler") ||
                            n.startsWith("benzer diziler") || n.startsWith("yorumlar")
                        ) break
                        if (text.length >= 35 && !n.contains("reklam engelleyici")) found.add(text)
                    } else if (sibling is TextNode) {
                        val text = sibling.text().trim()
                        if (text.length >= 35 && !normalize(text).contains("reklam engelleyici")) found.add(text)
                    }
                    sibling = sibling.nextSibling()
                    scanned++
                }
                if (found.isNotEmpty()) return found.distinct().joinToString("\n\n").take(6000)
                branch = branch.parent()
                depth++
            }
        }
        val paragraph = document.select(
            "main p, article p, [class*=description], [class*=synopsis], [class*=overview], .prose p"
        ).asSequence().map { it.text().trim() }
            .firstOrNull { it.length >= 45 && !normalize(it).contains("reklam engelleyici") }
        if (!paragraph.isNullOrBlank()) return paragraph.take(6000)
        val meta = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()
        return meta?.takeIf { it.length >= 35 }?.take(6000)
    }

    private fun parseGenres(document: Document): List<String> {
        val result = LinkedHashMap<String, String>()
        val selectors = listOf(
            "a[href*='/tur/']", "a[href*='/genre/']", "a[href*='/kategori/']",
            "a[href*='genre=']", "a[href*='tur=']", ".genres a", ".genre a", "[class*=tag] a"
        )
        for (selector in selectors) {
            for (link in document.select(selector)) {
                val text = cleanTitle(link.text())
                if (text.isNotBlank() && text.length <= 40 &&
                    normalize(text) !in setOf("diziler", "filmler", "animeler", "programlar", "tumu")
                ) result.putIfAbsent(normalize(text), text)
            }
        }
        if (result.isEmpty()) {
            val match = Regex("""(?is)(?:Türler|Tür|Genres?)\s*:?\s*(.{1,180}?)(?=Oyuncular|Bölümler|Yapım|Ülke|$)""")
                .find(document.text())?.groupValues?.getOrNull(1)
            match?.split(Regex("[,|•]"))?.map { cleanTitle(it) }
                ?.filter { it.isNotBlank() && it.length <= 40 }
                ?.forEach { result.putIfAbsent(normalize(it), it) }
        }
        return result.values.take(20)
    }

    private fun parseActors(document: Document): List<String> {
        val links = document.select(
            "a[href*='/oyuncu/'], a[href*='/oyuncular/'], a[href*='/cast/'], a[href*='/person/']"
        ).map { cleanTitle(it.text()) }
            .filter { it.isNotBlank() && it.length < 70 && !isGenericTitle(it) }
            .distinct()
        if (links.isNotEmpty()) return links.take(50)
        val raw = Regex("""(?is)Oyuncular\s*\(\d+\)\s*(.*?)(?=Benzer Diziler|Sık Sorulan Sorular|Yorum|$)""")
            .find(document.text())?.groupValues?.getOrNull(1).orEmpty()
        return raw.split(Regex("\\s{2,}"))
            .map { cleanTitle(it) }.filter { it.length in 2..70 && !isGenericTitle(it) }
            .distinct().take(50)
    }

    private fun parseTrailer(document: Document, pageUrl: String): String? {
        for (link in document.select("a[href], iframe[src], iframe[data-src]")) {
            val raw = link.attr("href").ifBlank { link.attr("src") }.ifBlank { link.attr("data-src") }
            val url = absoluteUrl(raw, pageUrl) ?: continue
            val label = normalize(link.text() + " " + link.attr("title") + " " + url)
            if ((label.contains("fragman") || label.contains("trailer")) &&
                (url.contains("youtube.com", true) || url.contains("youtu.be", true))
            ) return url
        }
        return null
    }

    private fun parseEpisodes(document: Document, seriesUrl: String, poster: String?): List<Episode> {
        val base = canonicalUrl(seriesUrl).trimEnd('/')
        val parts = pathParts(base)
        if (parts.size < 2) return emptyList()
        val root = parts[0]
        val slug = parts[1]
        val pattern = Regex("""(?i)/sezon-(\d+)-bolum-(\d+)$""")
        val episodes = LinkedHashMap<String, Episode>()
        for (element in document.select("a[href], [data-href], [data-url], [data-episode-url]")) {
            val raw = element.attr("href")
                .ifBlank { element.attr("data-episode-url") }
                .ifBlank { element.attr("data-href") }
                .ifBlank { element.attr("data-url") }
            val episodeUrl = absoluteUrl(raw, element.baseUri().ifBlank { base }) ?: continue
            val epParts = pathParts(episodeUrl)
            if (epParts.size < 3 || epParts[0] != root || epParts[1] != slug) continue
            val match = pattern.find(pathOf(episodeUrl).trimEnd('/')) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            if (season !in 1..99 || number !in 1..2000) continue
            val explicit = cleanTitle(element.text() + " " + element.attr("title") + " " + element.attr("aria-label"))
            val episodeName = if (normalize(explicit).contains("bolum") || normalize(explicit).contains("final")) {
                explicit
            } else "$season. Sezon $number. Bölüm"
            val key = "$season-$number"
            if (!episodes.containsKey(key)) {
                episodes[key] = newEpisode(episodeUrl.substringBefore('#')) {
                    name = episodeName
                    this.season = season
                    episode = number
                    this.posterUrl = poster
                }
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })
    }

    override suspend fun load(url: String): LoadResponse? {
        val requested = absoluteUrl(url) ?: return null
        val kind = categoryFor(requested) ?: return null
        val targetUrl = canonicalUrl(requested)
        val response = runCatching {
            app.get(targetUrl, headers = requestHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).ifBlank { titleFromUrl(targetUrl) }
        val pageText = document.text()
        val poster = document.selectFirst(
            "meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]"
        )?.attr("content")?.let { absoluteUrl(it, targetUrl) }?.takeIf { isUsablePoster(it) }
            ?: posterFrom(document.selectFirst("main img, article img, [class*=poster] img, img[alt*=cover]"))
            ?: posterFrom(document.selectFirst("img[alt*=cover], img[alt*=poster]"))
        val plot = parsePlot(document)
        val actors = parseActors(document)
        val genres = parseGenres(document)
        val year = yearFrom(title) ?: yearFrom(pageText)
        val score = scoreFrom(document.selectFirst("main")?.text().orEmpty() + " " + document.title())
        val trailer = parseTrailer(document, targetUrl)

        if (kind == "film") {
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score
                tags = genres
                if (trailer != null) addTrailer(trailer)
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        val type = if (kind == "anime") TvType.Anime else TvType.TvSeries
        val episodes = parseEpisodes(document, targetUrl, poster)
        return newTvSeriesLoadResponse(title, targetUrl, type, episodes) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            tags = genres
            if (trailer != null) addTrailer(trailer)
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    private fun normalizeEmbeddedText(value: String): String =
        value.replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)

    private fun isSubtitleUrl(url: String): Boolean =
        Regex("""(?i)\.(?:vtt|srt|ass|ssa|ttml|dfxp)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("subtitle", true) || url.contains("subtitles", true) ||
            url.contains("caption", true)

    private fun qualityFrom(url: String): Int = when {
        Regex("""(?i)(2160|4k)""").containsMatchIn(url) -> Qualities.P2160.value
        Regex("""(?i)1080""").containsMatchIn(url) -> Qualities.P1080.value
        Regex("""(?i)720""").containsMatchIn(url) -> Qualities.P720.value
        Regex("""(?i)480""").containsMatchIn(url) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4|mpd|webm|m4v)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("/master.m3u8", true)

    private fun isKnownPlayer(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val lower = url.lowercase(Locale.ROOT)
        if (host.isBlank()) return false
        if (host == "diziasya.com" || host.endsWith(".diziasya.com")) {
            return lower.contains("/embed/") || lower.contains("/player/") || lower.contains("/video/")
        }
        return listOf(
            "ok.ru", "odnoklassniki.ru", "vidmoly", "4me", "abyss", "abstr", "p2p", "lulu",
            "dracarys", "dood", "filemoon", "streamwish", "mixdrop", "uqload", "voe",
            "dailymotion", "youtube.com", "youtu.be", "drive.google", "vidoza", "vidsrc", "streamtape"
        ).any { host.contains(it) } ||
            lower.contains("/embed/") || lower.contains("/player/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data) ?: return false
        val response = runCatching {
            app.get(
                pageUrl,
                headers = requestHeaders + ("Referer" to pageUrl),
                referer = "$mainUrl/",
                allowRedirects = true
            )
        }.getOrNull()
        val document = response?.takeIf { it.isSuccessful }?.document
        val html = response?.takeIf { it.isSuccessful }?.text.orEmpty()
        val decoded = normalizeEmbeddedText(html)
        val players = LinkedHashSet<String>()
        val directMedia = LinkedHashSet<String>()
        val subtitles = LinkedHashMap<String, String>()
        val emitted = LinkedHashSet<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            if (emitted.add(link.url)) {
                callback(link)
                found = true
            }
        }

        fun addCandidate(raw: String?, base: String, forcePlayer: Boolean = false) {
            val url = absoluteUrl(raw, base) ?: return
            if (isSubtitleUrl(url)) {
                val label = when {
                    url.contains("/en/", true) || url.contains("english", true) -> "English"
                    url.contains("/zh/", true) || url.contains("chinese", true) -> "Chinese"
                    else -> "Türkçe"
                }
                subtitles.putIfAbsent(url, label)
            } else if (isDirectMedia(url)) {
                directMedia.add(url)
            } else if (forcePlayer || isKnownPlayer(url)) {
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                if (host.isNotBlank() &&
                    (!host.equals("diziasya.com", true) && !host.endsWith(".diziasya.com", true) ||
                        isKnownPlayer(url))
                ) players.add(url)
            }
        }

        if (document != null) {
            document.select(
                "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], iframe[data-embed], " +
                    "video[src], source[src], source[data-src], [data-player], [data-video], [data-embed], [data-url]"
            ).forEach { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-lazy-src") }
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("data-embed") }
                    .ifBlank { element.attr("data-player") }
                    .ifBlank { element.attr("data-video") }
                addCandidate(raw, pageUrl, forcePlayer = element.tagName().equals("iframe", true))
            }

            document.select("track[src], track[data-src]").forEach { track ->
                val raw = track.attr("src").ifBlank { track.attr("data-src") }
                val sub = absoluteUrl(raw, pageUrl) ?: return@forEach
                if (isSubtitleUrl(sub)) {
                    subtitles.putIfAbsent(
                        sub, track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Türkçe" } }
                    )
                }
            }

            document.select("a[href], source[src], video[src]").forEach { element ->
                val raw = if (element.hasAttr("href")) element.attr("href") else element.attr("src")
                addCandidate(raw, pageUrl)
            }
        }

        // Source URLs can be embedded in the server-rendered Next.js data payload.
        Regex("""(?i)https?://[^"'<>\\\s]+""").findAll(decoded).forEach { match ->
            val url = match.value.trimEnd(',', ';', ')', ']', '}')
            addCandidate(url, pageUrl)
        }

        for (url in directMedia) {
            if (isSubtitleUrl(url)) continue
            val hls = url.contains(".m3u8", true)
            runCatching {
                emit(
                    newExtractorLink(
                        source = "DiziAsya",
                        name = if (hls) "DiziAsya HLS" else "DiziAsya Direct",
                        url = url,
                        type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        quality = qualityFrom(url)
                        headers = mapOf("Referer" to pageUrl, "User-Agent" to browserUa)
                    }
                )
            }
        }

        for ((url, label) in subtitles) {
            runCatching { subtitleCallback(SubtitleFile(label, url)) }
        }
        for (player in players.distinct()) {
            runCatching { loadExtractor(player, pageUrl, subtitleCallback, ::emit) }
        }
        return found
    }
}
