package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
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
        val pathNumber = Regex("""(?i)/page/(\d+)/?$""")
            .find(pathOf(url))?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (pathNumber != null) return pathNumber

        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        return Regex("""(?i)(?:^|&)(?:paged|page)=(\d+)(?:&|$)""")
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

    private fun isMetadataText(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.startsWith("oyuncular") ||
            normalized.startsWith("ulke") ||
            normalized.startsWith("bolum sayisi") ||
            normalized.startsWith("sure") ||
            normalized.startsWith("durum") ||
            normalized == "tur" ||
            normalized.startsWith("tur ")
    }

    private fun parsePlot(document: Document): String? {
        // Prefer the real synopsis section under headings like "Dizi Konusu".
        val headings = document.select("h1, h2, h3, h4, h5, h6, strong, b")
            .filter { normalize(it.text()).contains("konusu") }

        for (heading in headings) {
            var current: Element? = heading
            var depth = 0
            while (current != null && depth < 4) {
                var sibling = current.nextElementSibling()
                var scanned = 0
                val paragraphs = ArrayList<String>()
                while (sibling != null && scanned < 10) {
                    val text = cleanTitle(sibling.text())
                    val normalized = normalize(text)
                    if (isMetadataText(text) ||
                        (sibling.tagName().matches(Regex("(?i)h[1-6]")) &&
                            (normalized.contains("bolum") || normalized.contains("oyuncu") ||
                                normalized.contains("benzer dizi")))
                    ) break
                    if (text.length >= 35 &&
                        !normalized.contains("konusu") &&
                        !text.contains("Sitemizde bulunan", true) &&
                        !text.contains("Reklam engelleyici", true)
                    ) paragraphs.add(text)
                    sibling = sibling.nextElementSibling()
                    scanned++
                }
                if (paragraphs.isNotEmpty()) return paragraphs.distinct().joinToString("\n\n").take(6000)
                current = current.parent()
                depth++
            }
        }

        val articleText = document.select(
            ".entry-content p, .post-content p, article p, main p, .description, .plot, .summary, p"
        ).asSequence()
            .map { cleanTitle(it.text()) }
            .filter { text ->
                val normalized = normalize(text)
                text.length in 60..6000 &&
                    !isMetadataText(text) &&
                    !normalized.contains("sitemizde bulunan") &&
                    !normalized.contains("reklam engelleyici") &&
                    !normalized.contains("yorum yap") &&
                    !normalized.contains("benzer diziler")
            }
            .distinct()
            .toList()
        articleText.firstOrNull()?.let { return it }

        return document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.let(::cleanTitle)
            ?.takeIf {
                it.length > 40 &&
                    !normalize(it).contains("asyaluna asya dizileri") &&
                    !normalize(it).contains("konusu")
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
        val seriesSlug = pathOf(seriesUrl).trim('/').substringAfter("dizi/", "").substringBefore('/')
        if (seriesSlug.isBlank()) return emptyList()

        val groups = LinkedHashMap<String, String>()
        for (link in document.select("a[href], [data-href], [data-url]")) {
            val rawHref = link.attr("href").ifBlank {
                link.attr("data-href").ifBlank { link.attr("data-url") }
            }
            val raw = absoluteUrl(rawHref, link.baseUri().ifBlank { mainUrl }) ?: continue
            val path = pathOf(raw).trimEnd('/')
            if (!path.startsWith("/bolum/", true)) continue
            val slug = path.substringAfter("/bolum/", "").substringBefore('/')
            if (slug.isBlank() || !slug.startsWith(seriesSlug, true)) continue
            val groupUrl = "$mainUrl/bolum/$slug/"
            val label = cleanTitle(link.text() + " " + link.attr("title") + " " + link.attr("aria-label"))
            groups.putIfAbsent(groupUrl, label)
        }

        val episodes = ArrayList<Episode>()
        val rangePattern = Regex("""(?i)(?<!\d)(\d{1,3})\s*[-–]\s*(\d{1,3})(?!\d)""")
        val seasonPattern = Regex("""(?i)\b(?:sezon|season)\s*[-_.:]?\s*(\d{1,2})\b""")
        for ((groupUrl, linkText) in groups) {
            val slug = pathOf(groupUrl).substringAfter("/bolum/", "").trim('/')
            val sourceText = "$linkText $slug"
            val range = rangePattern.find(linkText) ?: rangePattern.find(slug)
            val season = seasonPattern.find(sourceText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            if (range != null) {
                val first = range.groupValues[1].toIntOrNull() ?: continue
                val last = range.groupValues[2].toIntOrNull() ?: continue
                if (first <= 0 || last < first || last - first > 100) continue
                for (number in first..last) {
                    val offset = number - first + 1
                    val episodeUrl = if (offset == 1) groupUrl else groupUrl.trimEnd('/') + "/" + offset + "/"
                    episodes.add(newEpisode(episodeUrl) {
                        name = "$number. Bölüm"
                        this.season = season
                        episode = number
                        this.posterUrl = poster
                    })
                }
            } else {
                val number = Regex("""(?i)(?:bolum|bölüm|episode)[-_.\s]*(\d{1,3})""")
                    .find(sourceText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                episodes.add(newEpisode(groupUrl) {
                    name = if (linkText.isNotBlank()) linkText else "$number. Bölüm"
                    this.season = season
                    episode = number
                    this.posterUrl = poster
                })
            }
        }

        // Some site versions expose individual episode links directly as well.
        for (link in document.select("a[href*='/bolum/']")) {
            val raw = absoluteUrl(link.attr("href"), link.baseUri().ifBlank { mainUrl }) ?: continue
            val path = pathOf(raw).trimEnd('/')
            val parts = path.substringAfter("/bolum/", "").split('/')
            if (parts.size < 2 || !parts[0].startsWith(seriesSlug, true)) continue
            val number = Regex("""(?i)(?:^|[/.-])(\d{1,3})$""")
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (number !in 1..1000) continue
            val season = seasonPattern.find(link.text() + " " + path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episodeUrl = raw.trimEnd('/') + "/"
            episodes.add(newEpisode(episodeUrl) {
                name = cleanTitle(link.text()).ifBlank { "$number. Bölüm" }
                this.season = season
                episode = number
                this.posterUrl = poster
            })
        }

        return episodes.distinctBy {
            (it.season ?: 1).toString() + "-" + (it.episode ?: 0).toString()
        }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })
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

    private fun okRuVideoId(url: String): String? =
        Regex("""(?i)/(?:video|videoembed)/(\d+)""")
            .find(url)?.groupValues?.getOrNull(1)

    private suspend fun extractOkRuStreams(
        rawUrl: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = okRuVideoId(rawUrl) ?: return false
        val videoUrl = "https://ok.ru/video/$id"
        val embedUrl = "https://ok.ru/videoembed/$id"
        val okHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to "https://ok.ru",
            "Referer" to videoUrl
        )
        var metadata: JSONObject? = null

        fun parseJson(raw: String?): JSONObject? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().replace("&quot;", "\"").replace("&amp;", "&")
            return runCatching { JSONObject(cleaned) }.getOrNull()
        }

        for (candidate in listOf(embedUrl, videoUrl)) {
            val page = runCatching {
                app.get(candidate, headers = okHeaders, referer = pageReferer, allowRedirects = true)
            }.getOrNull() ?: continue
            if (!page.isSuccessful) continue
            val optionsText = page.document.selectFirst("[data-options]")?.attr("data-options")
            val options = parseJson(optionsText)
            val flashvars = options?.optJSONObject("flashvars")
            metadata = parseJson(flashvars?.opt("metadata")?.toString())

            if (metadata == null) {
                val metadataUrl = flashvars?.optString("metadataUrl").orEmpty()
                if (metadataUrl.isNotBlank() && metadataUrl != "null") {
                    val decoded = runCatching { URLDecoder.decode(metadataUrl, "UTF-8") }.getOrDefault(metadataUrl)
                    val resolved = absoluteUrl(decoded, candidate) ?: decoded
                    val metadataResponse = runCatching {
                        app.get(
                            resolved,
                            headers = okHeaders + ("Referer" to candidate),
                            referer = candidate,
                            allowRedirects = true
                        )
                    }.getOrNull()
                    metadata = metadataResponse?.takeIf { it.isSuccessful }?.text?.let(::parseJson)
                }
            }
            if (metadata != null) break
        }

        if (metadata == null) {
            val response = runCatching {
                app.post(
                    "https://www.ok.ru/dk?cmd=videoPlayerMetadata",
                    headers = okHeaders,
                    data = mapOf("mid" to id),
                    referer = videoUrl
                )
            }.getOrNull()
            metadata = response?.takeIf { it.isSuccessful }?.text?.let(::parseJson)
        }

        val root = metadata?.optJSONObject("data") ?: metadata ?: return false
        var emitted = false
        fun emit(url: String?, label: String, forceHls: Boolean = false) {
            val mediaUrl = url?.trim().orEmpty()
            if (!mediaUrl.startsWith("http://", true) && !mediaUrl.startsWith("https://", true)) return
            if (isSubtitleUrl(mediaUrl)) return
            val hls = forceHls || mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".m3u", true)
            runCatching {
                callback(
                    newExtractorLink(
                        source = "OK.ru",
                        name = if (label.isBlank()) "OK.ru" else "OK.ru $label",
                        url = mediaUrl,
                        type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        quality = qualityFrom("$label $mediaUrl")
                        headers = mapOf(
                            "User-Agent" to okHeaders.getValue("User-Agent"),
                            "Referer" to videoUrl,
                            "Origin" to "https://ok.ru"
                        )
                    }
                )
                emitted = true
            }
        }
        for (key in listOf("hlsMasterPlaylistUrl", "hlsManifestUrl", "hlsUrl", "hls_url")) {
            val url = root.optString(key).orEmpty()
            if (url.startsWith("http", true) && url != "null") emit(url, "HLS", forceHls = true)
        }
        val videos = root.optJSONArray("videos") ?: root.optJSONObject("movie")?.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val item = videos.optJSONObject(i) ?: continue
                val url = item.optString("url").orEmpty()
                val label = item.optString("name").orEmpty().ifBlank { item.optString("quality").orEmpty() }
                emit(url, label)
            }
        }
        return emitted
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
        val players = LinkedHashSet<String>()
        val directMedia = LinkedHashSet<String>()
        val subtitleUrls = LinkedHashMap<String, String>()
        val emittedUrls = LinkedHashSet<String>()
        var found = false
        val chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val requestHeaders = siteHeaders + ("User-Agent" to chromeUa)

        fun emitLink(link: ExtractorLink) {
            if (emittedUrls.add(link.url)) {
                callback(link)
                found = true
            }
        }

        fun addPlayer(raw: String?, base: String) {
            if (raw.isNullOrBlank()) return
            val url = absoluteUrl(raw, base) ?: return
            val id = okRuVideoId(url)
            if (id != null) {
                players.add("https://ok.ru/video/$id")
                players.add("https://ok.ru/videoembed/$id")
            } else {
                players.add(url)
            }
        }

        addPlayer(data, data)
        val response = runCatching {
            app.get(data, headers = requestHeaders, referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull()
        val document = response?.takeIf { it.isSuccessful }?.document
        val body = response?.takeIf { it.isSuccessful }?.text?.let(::normalizeRaw).orEmpty()

        if (document != null) {
            document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], iframe[data-embed]").forEach { frame ->
                val raw = frame.attr("src")
                    .ifBlank { frame.attr("data-src") }
                    .ifBlank { frame.attr("data-lazy-src") }
                    .ifBlank { frame.attr("data-url") }
                    .ifBlank { frame.attr("data-embed") }
                addPlayer(raw, data)
            }
            document.select("[data-player], [data-video], [data-embed], [data-url], [data-href]").forEach { element ->
                listOf(
                    element.attr("data-player"), element.attr("data-video"), element.attr("data-embed"),
                    element.attr("data-url"), element.attr("data-href")
                ).forEach { raw -> addPlayer(raw, data) }
            }

            document.select("a[href], source[src], video[src]").forEach { element ->
                val raw = if (element.hasAttr("href")) element.attr("href") else element.attr("src")
                val url = absoluteUrl(raw, data) ?: return@forEach
                if (okRuVideoId(url) != null) addPlayer(url, data)
                if (url.contains(".m3u8", true) || url.contains(".mp4", true)) directMedia.add(url)
                if (isSubtitleUrl(url)) {
                    val label = element.attr("label").ifBlank { element.attr("srclang") }
                        .ifBlank { if (url.contains("/en/", true) || url.contains("english", true)) "English" else "Türkçe" }
                    subtitleUrls.putIfAbsent(url, label)
                }
            }
            document.select("track[src]").forEach { track ->
                val url = absoluteUrl(track.attr("src"), data) ?: return@forEach
                if (isSubtitleUrl(url)) {
                    val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Türkçe" }
                    subtitleUrls.putIfAbsent(url, label)
                }
            }
        }

        Regex("""(?i)(?:https?:)?//(?:www\.)?(?:ok\.ru|odnoklassniki\.ru)/(?:video|videoembed)/\d+[^"'<>\s]*""")
            .findAll(body).forEach { addPlayer(it.value, data) }
        Regex("""(?i)(?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mp4)(?:\?[^"'<>\s]*)?""")
            .findAll(body).forEach { match -> absoluteUrl(match.value, data)?.let(directMedia::add) }

        for (mediaUrl in directMedia) {
            if (isSubtitleUrl(mediaUrl)) continue
            val hls = mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".m3u", true)
            runCatching {
                emitLink(
                    newExtractorLink(
                        source = "AsyaLuna",
                        name = if (hls) "AsyaLuna HLS" else "AsyaLuna MP4",
                        url = mediaUrl,
                        type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        quality = qualityFrom(mediaUrl)
                        headers = mapOf("Referer" to data, "User-Agent" to chromeUa)
                    }
                )
            }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'<>\s]*)?""")
            .findAll(body).forEach { match ->
                val url = absoluteUrl(match.value, data) ?: return@forEach
                val label = if (url.contains("/en/", true) || url.contains("english", true)) "English" else "Türkçe"
                subtitleUrls.putIfAbsent(url, label)
            }
        for ((url, label) in subtitleUrls) {
            runCatching { subtitleCallback(SubtitleFile(label, url)) }
        }

        val okIdsHandled = HashSet<String>()
        for (playerUrl in players.distinct()) {
            val id = okRuVideoId(playerUrl)
            if (id != null && okIdsHandled.add(id)) {
                runCatching { extractOkRuStreams(playerUrl, data, ::emitLink) }
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, ::emitLink) }
        }
        return found
    }

}
