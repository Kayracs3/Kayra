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
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * AsyaLuna sağlayıcısı. Dizi detayları /dizi/, bölüm grupları /bolum/ altında.
 * Her bölüm grubu kendi sayfasını kullanır; ikinci ve sonraki bölümler /2/, /3/
 * gibi alt adreslerden açılır.
 */
class AsyaLuna : MainAPI() {
    override var mainUrl = "https://asyaluna.com"
    override var name = "AsyaLuna"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi/" to "Son Eklenen Diziler",
        "$mainUrl/diziler/aksiyon/" to "Aksiyon",
        "$mainUrl/diziler/dogaustu/" to "Doğaüstü",
        "$mainUrl/diziler/fantastik/" to "Fantastik",
        "$mainUrl/diziler/gerilim/" to "Gerilim",
        "$mainUrl/diziler/gizem/" to "Gizem",
        "$mainUrl/diziler/korku/" to "Korku",
        "$mainUrl/diziler/komedi/" to "Komedi",
        "$mainUrl/diziler/romantik/" to "Romantik"
    )

    private data class CatalogItem(
        val title: String,
        val url: String,
        val poster: String?,
        val score: Score?,
        val year: Int?
    )

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#'))

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        var value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value = value.replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("&amp;", "&")
            .trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(if (base.endsWith("/")) base else "$base/").resolve(value).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun normalize(value: String): String {
        val lower = value.lowercase(Locale.ROOT).replace('ı', 'i')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim().replace(Regex("\\s+"), " ")
    }

    private fun cleanTitle(value: String?): String =
        value.orEmpty()
            .replace("&nbsp;", " ", true)
            .replace("&#8211;", "–")
            .replace("&#8217;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .removeSuffix(" | AsyaLuna Asya Dizileri")
            .trim()

    private fun titleFromUrl(url: String): String {
        val slug = pathOf(url).trimEnd('/').substringAfterLast('/')
            .replace(
                Regex("""(?i)-(?:\d{1,3}-\d{1,3}-bolum(?:-final)?|\d{1,3}-bolum(?:-final)?)$"""),
                ""
            )
        return slug.replace(Regex("[-_]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
    }

    private fun isSeriesUrl(url: String) = pathOf(url).lowercase(Locale.ROOT).startsWith("/dizi/")
    private fun isEpisodeUrl(url: String) = pathOf(url).lowercase(Locale.ROOT).startsWith("/bolum/")

    private fun isUsablePoster(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val file = pathOf(url).substringAfterLast('/').lowercase(Locale.ROOT)
        if (file.endsWith(".svg")) return false
        if (Regex("(logo|avatar|placeholder|no[-_]?image|transparent|blank)").containsMatchIn(file)) return false
        return file.contains('.') || url.contains("image")
    }

    private fun posterFrom(element: Element?): String? {
        if (element == null) return null
        val nodes = listOf(element) + element.select("img, [data-poster], [data-background], [data-bg], [style*=background]")
        for (node in nodes.distinct()) {
            for (attr in listOf("data-poster", "data-original", "data-lazy-src", "data-src", "data-srcset", "srcset", "src", "content")) {
                val raw = node.attr(attr).trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) continue
                val candidate = if (attr.contains("srcset")) raw.substringBefore(',').trim().substringBefore(' ') else raw
                val image = absoluteUrl(candidate, node.baseUri().ifBlank { mainUrl })
                if (image != null && isUsablePoster(image)) return image
            }
            val background = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
                .find(node.attr("style"))?.groupValues?.getOrNull(1)
            val image = absoluteUrl(background, node.baseUri().ifBlank { mainUrl })
            if (image != null && isUsablePoster(image)) return image
        }
        return null
    }

    private fun scoreFrom(text: String?): Score? {
        val value = Regex("""(?i)(?:IMDb|İMDb|IMDB)\s*(?:Puanı)?\s*[:：]?\s*(\d{1,2}(?:[.,]\d+)?)""")
            .find(text.orEmpty())?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            ?: return null
        return Score.from10(value.toString())
    }

    private fun yearFrom(text: String?): Int? =
        text?.let { Regex("""\b(?:19|20|21)\d{2}\b""").find(it)?.value?.toIntOrNull() }

    private fun seriesUrlFromEpisode(rawUrl: String): String? {
        val path = pathOf(rawUrl)
        if (!path.startsWith("/bolum/", true)) return null
        val slug = path.removePrefix("/bolum/").trim('/').substringBefore('/')
        if (slug.isBlank()) return null
        val seriesSlug = slug.replace(
            Regex("""(?i)-(?:\d{1,3}-\d{1,3}-bolum(?:-final)?|\d{1,3}-bolum(?:-final)?)$"""),
            ""
        )
        if (seriesSlug.isBlank() || seriesSlug == slug) return null
        return "$mainUrl/dizi/$seriesSlug/"
    }

    private fun cardTitle(link: Element, card: Element, url: String): String {
        val image = link.selectFirst("img")?.let { it.attr("alt").ifBlank { it.attr("title") } }.orEmpty()
        return listOf(
            link.attr("title"), image,
            card.selectFirst("h1,h2,h3,h4,.title,.name")?.text().orEmpty(),
            link.text()
        ).asSequence().map(::cleanTitle)
            .firstOrNull { it.isNotBlank() && normalize(it) !in setOf("incele", "devami", "devamini oku") }
            ?: titleFromUrl(url)
    }

    private fun parseCards(document: Document, includeEpisodeCards: Boolean): List<CatalogItem> {
        val results = LinkedHashMap<String, CatalogItem>()
        for (link in document.select("a[href]")) {
            val linkedUrl = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: continue
            val series = isSeriesUrl(linkedUrl)
            val episode = isEpisodeUrl(linkedUrl)
            if (!series && !(includeEpisodeCards && episode)) continue

            val resultUrl = if (series) {
                linkedUrl.substringBefore('#').substringBefore('?')
            } else {
                seriesUrlFromEpisode(linkedUrl) ?: continue
            }
            val card = link.closest("article, .post, .item, .movie, .dizi, .film, li") ?: link
            val title = cleanTitle(if (series) cardTitle(link, card, resultUrl) else titleFromUrl(resultUrl))
            if (title.isBlank() || normalize(title).length < 2) continue

            results.putIfAbsent(
                resultUrl,
                CatalogItem(title, resultUrl, posterFrom(link) ?: posterFrom(card), scoreFrom(card.text()),
                    yearFrom(title) ?: yearFrom(card.text()))
            )
        }
        return results.values.toList()
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(title, url) {
            posterUrl = this@toSearchResponse.poster
            score = this@toSearchResponse.score
            year = this@toSearchResponse.year
        }

    private fun paginationPageNumber(rawUrl: String): Int? {
        val url = absoluteUrl(rawUrl, mainUrl) ?: return null
        val pathNumber = Regex("""(?i)/page/(\\d+)/?$""")
            .find(pathOf(url))?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (pathNumber != null) return pathNumber

        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        return Regex("""(?i)(?:^|&)(?:paged|page)=(\\d+)(?:&|$)""")
            .find(query)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextPage = page + 1
        val links = document.select("a[href]")

        // Prefer an explicit link to the exact next page. This works for both
        // /dizi/page/2/ and /diziler/aksiyon/page/2/ archive layouts.
        if (links.any { link ->
                val href = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: return@any false
                paginationPageNumber(href) == nextPage ||
                    link.attr("rel").equals("next", true) ||
                    link.classNames().any { it.equals("next", true) } ||
                    link.attr("aria-label").contains("next", true) &&
                        !link.attr("aria-label").contains("previous", true)
            }) return true

        // Some WordPress themes expose only a numbered range and a final "Son"
        // link. Read its real target instead of treating "Son" as infinite pages.
        val paginationLinks = document.select(
            "a.page-numbers[href], .pagination a[href], .nav-links a[href], .page-nav a[href]"
        )
        val lastLink = paginationLinks.firstOrNull {
            val label = normalize(it.text() + " " + it.attr("aria-label") + " " + it.attr("title"))
            label == "son" || label.contains("son sayfa") || label.contains("last page")
        }
        val lastPage = lastLink?.let { paginationPageNumber(it.attr("href")) }
        if (lastPage != null) return page < lastPage

        val numberedPages = paginationLinks.mapNotNull {
            paginationPageNumber(it.attr("href"))
        }.distinct()
        return numberedPages.any { it > page }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val pageUrl = when {
            page <= 1 -> baseUrl
            baseUrl.contains("?") -> "$baseUrl&paged=$page"
            else -> baseUrl.trimEnd('/') + "/page/" + page + "/"
        }
        val document = runCatching {
            app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)

        val includeEpisodes = pathOf(baseUrl).lowercase(Locale.ROOT).startsWith("/bolum")
        val results = parseCards(document, includeEpisodes).map { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = hasNextPage(document, page))
    }

    private fun matchesSearch(query: String, title: String): Boolean {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        if (t.contains(q)) return true
        val ignored = setOf("dizi", "dizisi", "izle", "turkce", "altyazili", "film")
        val terms = q.split(' ').filter { it !in ignored && it.length > 1 }
        return terms.isNotEmpty() && terms.all { term ->
            t.split(' ').any { word ->
                word == term || (term.length >= 4 && word.length >= 4 &&
                    (word.startsWith(term) || term.startsWith(word)))
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val pages = listOf("$mainUrl/?s=$encoded", "$mainUrl/tum-diziler/?s=$encoded")
        val found = LinkedHashMap<String, SearchResponse>()

        for (pageUrl in pages) {
            val document = runCatching {
                app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            for (item in parseCards(document, includeEpisodeCards = true)) {
                if (matchesSearch(cleanQuery, item.title)) found.putIfAbsent(item.url, item.toSearchResponse())
            }
            if (found.isNotEmpty()) break
        }
        return found.values.toList()
    }

    private fun parseGenres(document: Document, pageText: String): List<String> {
        val fromText = Regex(
            """(?i)\bTür\s*:\s*(.*?)(?=\s+(?:Ülke|Bölüm\s*Sayısı|Süre|Oyuncular|Durum)\s*:|$)"""
        ).find(pageText)?.groupValues?.getOrNull(1)
            ?.split(',', '/', '،')?.map { cleanTitle(it).trim() }?.filter { it.isNotBlank() }.orEmpty()
        val fromLinks = document.select("a[rel=tag], .genre a, .genres a, .post-categories a")
            .map { cleanTitle(it.text()) }
            .filter { it.isNotBlank() && normalize(it) !in setOf("anasayfa", "dizi", "bolum", "bolumler") }
        return (fromText + fromLinks).distinctBy { normalize(it) }.take(15)
    }

    private fun parseActors(pageText: String): List<String> {
        val raw = Regex(
            """(?i)Oyuncular\s*:\s*(.*?)(?=\s+(?:Ülke|Bölüm\s*Sayısı|Tür|Süre|Durum)\s*:|$)"""
        ).find(pageText)?.groupValues?.getOrNull(1).orEmpty()
        return raw.split(',', '،').map { cleanTitle(it).trim().trim('.', ';', ':') }
            .filter { it.isNotBlank() && it.length < 70 }.distinct().take(40)
    }

    private fun parsePlot(document: Document): String? {
        document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
            ?.trim()?.takeIf { it.length > 25 }?.let { return it }
        return document.select("p, .description, .plot, .summary, .entry-content").asSequence()
            .map { cleanTitle(it.text()) }
            .firstOrNull {
                it.length > 60 && !it.contains("Oyuncular:", true) &&
                    !it.contains("Bölüm Sayısı:", true) && !it.contains("Sitemizde bulunan", true)
            }
    }

    private fun parseTrailer(document: Document): String? =
        document.select("a[href]").asSequence()
            .filter {
                val label = (it.text() + " " + it.attr("title")).lowercase(Locale.ROOT)
                label.contains("fragman") || label.contains("trailer")
            }
            .mapNotNull { absoluteUrl(it.attr("href"), it.baseUri().ifBlank { mainUrl }) }
            .firstOrNull { it.contains("youtube.com", true) || it.contains("youtu.be", true) }

    private fun parseEpisodes(document: Document, seriesUrl: String, poster: String?): List<Episode> {
        val seriesSlug = pathOf(seriesUrl).removePrefix("/dizi/").trim('/').substringBefore('/')
        if (seriesSlug.isBlank()) return emptyList()

        val groups = LinkedHashMap<String, String>()
        for (link in document.select("a[href*='/bolum/']")) {
            val raw = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: continue
            val path = pathOf(raw)
            if (!path.startsWith("/bolum/", true)) continue
            val slug = path.removePrefix("/bolum/").trim('/').substringBefore('/')
            if (!slug.startsWith(seriesSlug, true)) continue
            val groupUrl = "$mainUrl/bolum/$slug/"
            groups.putIfAbsent(groupUrl, cleanTitle(link.text() + " " + link.attr("title")))
        }

        val episodes = ArrayList<Episode>()
        for ((groupUrl, linkText) in groups) {
            val slug = pathOf(groupUrl).removePrefix("/bolum/").trim('/')
            val range = Regex("""(?i)(\d{1,3})\s*[-–]\s*(\d{1,3})""").find("$linkText $slug")
            if (range != null) {
                val first = range.groupValues[1].toIntOrNull() ?: continue
                val last = range.groupValues[2].toIntOrNull() ?: continue
                if (first <= 0 || last < first) continue
                for (number in first..last) {
                    val offset = number - first + 1
                    val episodeUrl = if (offset == 1) groupUrl else groupUrl.trimEnd('/') + "/" + offset + "/"
                    episodes += newEpisode(episodeUrl) {
                        name = "$number. Bölüm"
                        season = 1
                        episode = number
                        this.posterUrl = poster
                    }
                }
            } else {
                val number = Regex("""(?i)(?:bolum|bölüm|episode)[-_.\s]*(\d{1,3})""")
                    .find("$linkText $slug")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                episodes += newEpisode(groupUrl) {
                    name = if (linkText.isNotBlank()) linkText else "$number. Bölüm"
                    season = 1
                    episode = number
                    this.posterUrl = poster
                }
            }
        }
        return episodes.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })
    }

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = if (isSeriesUrl(url)) url else seriesUrlFromEpisode(url) ?: return null
        val response = runCatching {
            app.get(targetUrl, headers = siteHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null

        val document = response.document
        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: titleFromUrl(targetUrl)
        ).ifBlank { titleFromUrl(targetUrl) }

        val pageText = document.text()
        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?.let { absoluteUrl(it, targetUrl) }?.takeIf { isUsablePoster(it) }
            ?: posterFrom(document.selectFirst("main img, article img, .poster img, .post-thumbnail img, .entry-content img"))
            ?: posterFrom(document.selectFirst("img[alt*=poster], img[alt*=Poster]"))

        val genres = parseGenres(document, pageText)
        val actors = parseActors(pageText)
        val trailer = parseTrailer(document)
        val episodes = parseEpisodes(document, targetUrl, poster)

        return newTvSeriesLoadResponse(
            name = title,
            url = targetUrl,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            year = yearFrom(title) ?: yearFrom(pageText)
            plot = parsePlot(document)
            score = scoreFrom(pageText)
            tags = genres
            if (trailer != null) addTrailer(trailer)
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    private fun normalizeRaw(text: String): String =
        text.replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&#038;", "&")

    private fun qualityFrom(url: String): Int = when {
        Regex("""(?i)(2160|4k)""").containsMatchIn(url) -> Qualities.P2160.value
        Regex("""(?i)1080""").containsMatchIn(url) -> Qualities.P1080.value
        Regex("""(?i)720""").containsMatchIn(url) -> Qualities.P720.value
        Regex("""(?i)480""").containsMatchIn(url) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun mediaType(url: String): ExtractorLinkType =
        if (url.contains(".m3u8", true) || url.contains(".m3u", true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO

    private fun isSubtitleUrl(url: String): Boolean =
        Regex("""(?i)\.(?:vtt|srt|ass|ssa|ttml|dfxp)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("subtitle", true) || url.contains("subtitles", true) || url.contains("caption", true)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = siteHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull() ?: return false
        if (!response.isSuccessful) return false

        val document = response.document
        val body = normalizeRaw(response.text)
        val players = LinkedHashSet<String>()
        var found = false

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], iframe[data-embed]").forEach { frame ->
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
                .ifBlank { frame.attr("data-lazy-src") }.ifBlank { frame.attr("data-url") }
                .ifBlank { frame.attr("data-embed") }
            absoluteUrl(raw, data)?.let(players::add)
        }

        document.select("[data-player], [data-video], [data-embed], [data-url]").forEach { element ->
            listOf(element.attr("data-player"), element.attr("data-video"), element.attr("data-embed"), element.attr("data-url"))
                .forEach { raw -> absoluteUrl(raw, data)?.let(players::add) }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+(?:ok\.ru/videoembed/|odnoklassniki\.ru/videoembed/)[^"'<>\s]*""")
            .findAll(body).forEach { match -> absoluteUrl(match.value, data)?.let(players::add) }

        val directMedia = LinkedHashSet<String>()
        Regex("""(?i)(?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mp4)(?:\?[^"'<>\s]*)?""")
            .findAll(body).forEach { match -> absoluteUrl(match.value, data)?.let(directMedia::add) }

        document.select("video[src], source[src]").forEach { element ->
            absoluteUrl(element.attr("src"), data)?.let { candidate ->
                if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true)) directMedia.add(candidate)
            }
        }

        for (mediaUrl in directMedia) {
            if (isSubtitleUrl(mediaUrl)) continue
            runCatching {
                callback(newExtractorLink(
                    source = "AsyaLuna",
                    name = if (mediaUrl.contains(".m3u8", true)) "AsyaLuna HLS" else "AsyaLuna MP4",
                    url = mediaUrl,
                    type = mediaType(mediaUrl)
                ) {
                    quality = qualityFrom(mediaUrl)
                    headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)
                })
                found = true
            }
        }

        document.select("track[src]").forEach { track ->
            val subtitleUrl = absoluteUrl(track.attr("src"), data) ?: return@forEach
            if (!isSubtitleUrl(subtitleUrl)) return@forEach
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Türkçe" }
            runCatching { subtitleCallback(SubtitleFile(label, subtitleUrl)) }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'<>\s]*)?""")
            .findAll(body).forEach { match ->
                val subtitleUrl = absoluteUrl(match.value, data) ?: return@forEach
                val label = if (subtitleUrl.contains("/en/", true) || subtitleUrl.contains("english", true)) "English" else "Türkçe"
                runCatching { subtitleCallback(SubtitleFile(label, subtitleUrl)) }
            }

        for (playerUrl in players.distinct()) {
            runCatching {
                if (loadExtractor(playerUrl, data, subtitleCallback, callback)) found = true
            }
        }
        return found
    }
}
