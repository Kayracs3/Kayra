package com.Kayracs3

import com.lagradost.cloudstream3.newEpisode

import com.lagradost.cloudstream3.newHomePageResponse

import com.lagradost.cloudstream3.newMovieSearchResponse

import com.lagradost.cloudstream3.newTvSeriesLoadResponse

import com.lagradost.cloudstream3.newMovieLoadResponse

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
import java.net.URLDecoder
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

    private fun normalizeImageUrl(raw: String?, base: String = mainUrl): String? {
        val resolved = absoluteUrl(raw, base) ?: return null
        val uri = runCatching { URI(resolved) }.getOrNull() ?: return resolved
        if (!uri.path.orEmpty().contains("/_next/image")) return resolved

        val encodedImage = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("url=", true) }
            ?.substringAfter('=') ?: return resolved
        val decodedImage = runCatching { URLDecoder.decode(encodedImage, "UTF-8") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return resolved
        return absoluteUrl(decodedImage, base) ?: absoluteUrl(decodedImage, resolved) ?: resolved
    }

    private fun isUsablePoster(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase(Locale.ROOT)
        val file = path.substringAfterLast('/')
        val isNextImage = path.endsWith("/_next/image") &&
            uri?.rawQuery.orEmpty().contains("url=", true)
        if (file.endsWith(".svg")) return false
        if (Regex("""(?i)(logo|placeholder|no[-_]?image|transparent|blank|favicon|pixel)""")
                .containsMatchIn(file)
        ) return false
        return isNextImage || file.contains('.') ||
            url.contains("/images/", true) || url.contains("/uploads/", true)
    }

    private fun posterFrom(element: Element?): String? {
        if (element == null) return null
        val nodes = listOf(element) + element.select(
            "img, picture img, [data-poster], [data-background], [data-bg], [style*=background]"
        )
        for (node in nodes.distinct()) {
            for (attr in listOf(
                "data-poster", "data-original", "data-lazy-src", "data-src",
                "data-srcset", "srcset", "data-image", "data-nimg", "src", "content"
            )) {
                val raw = node.attr(attr).trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) continue
                val candidate = if (attr.contains("srcset")) {
                    raw.substringBefore(',').trim().substringBefore(' ')
                } else raw
                val url = normalizeImageUrl(candidate, node.baseUri().ifBlank { mainUrl }) ?: continue
                if (isUsablePoster(url)) return url
            }
            val bg = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
                .find(node.attr("style"))?.groupValues?.getOrNull(1)
            val bgUrl = normalizeImageUrl(bg, node.baseUri().ifBlank { mainUrl })
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
        val generic = setOf(
            "dizi", "diziler", "film", "filmler", "anime", "animeler", "program", "programlar",
            "kore", "cin", "japon", "tayland", "turkiye", "turk", "mdL".lowercase(Locale.ROOT),
            "izle", "tumu", "tumunu gor", "oyuncular", "oyuncu", "yonetmen"
        )
        fun addGenre(raw: String) {
            val text = cleanTitle(raw)
            val normalized = normalize(text)
            if (text.isBlank() || text.length > 40 || normalized in generic) return
            if (normalized.matches(Regex("""\d{1,4}(?:\.\d+)?"""))) return
            if (Regex("""(?i)^(?:19|20|21)\d{2}$""").matches(text)) return
            if (Regex("""(?i)^\d+\s*(?:dk|dakika|saat|h|m|min)$""").matches(text)) return
            result.putIfAbsent(normalized, text)
        }

        // DiziAsya detail metadata row shown under:
        // <div class="-ml-3 flex flex-wrap items-center text-xs">...</div>
        val metadataRows = document.select(
            "div[class*='-ml-3'][class*='flex-wrap'][class*='text-xs'], " +
                "div[class*='flex-wrap'][class*='text-xs']"
        )
        for (row in metadataRows.take(8)) {
            for (link in row.select("a[href]")) {
                val href = link.attr("href").lowercase(Locale.ROOT)
                val text = link.text().trim()
                val genreHref = listOf("/genre/", "/genres/", "/tur/", "/turler/", "/kategori/", "/tag/")
                    .any { href.contains(it) } || listOf("genre=", "tur=", "category=", "tag=")
                    .any { href.contains(it) }
                if (genreHref) addGenre(text)
            }
        }

        // Fallback for theme changes where genre tags do not use predictable href paths.
        if (result.isEmpty()) {
            val text = document.select(
                "div[class*='-ml-3'][class*='flex-wrap'][class*='text-xs'] a, " +
                    ".genres a, .genre a, [class*=genre] a, [class*=genres] a, [class*=tag] a"
            )
            for (link in text) {
                val label = link.text().trim()
                val href = link.attr("href").lowercase(Locale.ROOT)
                if (href.contains("/dizi/") || href.contains("/anime/") ||
                    href.contains("/film/") || href.contains("/program/")
                ) continue
                addGenre(label)
            }
        }

        if (result.isEmpty()) {
            val pageText = document.text()
            val match = Regex(
                """(?is)(?:Türler|Tür|Genres?)\s*:?\s*(.{1,180}?)(?=Oyuncular|Bölümler|Yapım|Ülke|$)"""
            ).find(pageText)?.groupValues?.getOrNull(1)
            match?.split(Regex("[,|•·]"))?.map { cleanTitle(it) }?.forEach(::addGenre)
        }

        return result.values.take(20)
    }

    private fun parseActors(document: Document): List<ActorData> {
        val actors = LinkedHashMap<String, ActorData>()

        fun imageForActor(img: Element?, base: String): String? {
            if (img == null) return null
            for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "srcset", "src")) {
                val raw = img.attr(attr).trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) continue
                val candidate = if (attr.contains("srcset")) raw.substringBefore(',').trim().substringBefore(' ') else raw
                val url = normalizeImageUrl(candidate, base) ?: continue
                val path = pathOf(url).lowercase(Locale.ROOT)
                if (path.endsWith("/_next/image") || Regex("""(?i)\.(?:jpe?g|png|webp|gif|avif)(?:$|[?#])""")
                        .containsMatchIn(url) || url.contains("/images/", true) || url.contains("/uploads/", true)
                ) return url
            }
            return null
        }

        fun addActorCard(card: Element, base: String) {
            val img = card.selectFirst("img")
            val childName = card.selectFirst(
                "h3, h4, [class*=font-semibold], [class*=font-medium], [class*=actor-name], [class*=person-name]"
            )?.text().orEmpty()
            val imgName = img?.attr("alt").orEmpty().ifBlank { img?.attr("title").orEmpty() }
            val linkName = card.attr("title").ifBlank { card.attr("aria-label") }
            val rawName = listOf(imgName, childName, linkName, card.ownText(), card.text())
                .map { cleanTitle(it) }
                .firstOrNull {
                    it.isNotBlank() && it.length <= 70 && !isGenericTitle(it) &&
                        normalize(it) != "oyuncular" && !normalize(it).contains("tumunu gor")
                } ?: return

            // Profile cards may include the role after the actor name; prefer the image alt/title
            // or a dedicated heading so that "Bae Min Tae" is not appended to the actor's name.
            val name = cleanTitle(
                imgName.takeIf { it.isNotBlank() }
                    ?: childName.takeIf { it.isNotBlank() }
                    ?: linkName.takeIf { it.isNotBlank() }
                    ?: rawName
            )
            if (name.isBlank() || isGenericTitle(name)) return
            val photo = imageForActor(img, img?.baseUri()?.ifBlank { base } ?: base)
            val key = normalize(name)
            actors.putIfAbsent(key, ActorData(Actor(name, photo)))
        }

        val heading = document.select("h2").firstOrNull {
            normalize(it.text()) == "oyuncular"
        } ?: document.select("h2").firstOrNull {
            normalize(it.text()).startsWith("oyuncular")
        }

        if (heading != null) {
            var section: Element? = heading.parent()
            repeat(5) {
                val current = section ?: return@repeat
                val base = document.location().ifBlank { mainUrl }
                val cards = current.select("a[href], [role=link]")
                    .filter { it.selectFirst("img") != null }
                cards.forEach { addActorCard(it, base) }

                // Some profile cards are non-clickable divs. Resolve one portrait per card
                // rather than parsing the entire actor section as a single name.
                for (img in current.select("img")) {
                    val linkedCard = img.closest("a[href], [role=link]")
                    if (linkedCard != null) continue
                    var parent: Element? = img.parent()
                    var card: Element? = null
                    repeat(5) {
                        val candidate = parent ?: return@repeat
                        if (candidate.select("img").size == 1 &&
                            candidate.text().trim().length in 2..140
                        ) {
                            card = candidate
                            return@repeat
                        }
                        parent = candidate.parent()
                    }
                    card?.let { addActorCard(it, base) }
                }
                if (actors.size >= 3) return actors.values.take(50)
                section = current.parent()
            }
            if (actors.isNotEmpty()) return actors.values.take(50)
        }

        // Fallback: actor-profile routes with portrait images may exist without the heading block.
        for (link in document.select(
            "a[href*='/oyuncu/'], a[href*='/oyuncular/'], a[href*='/actor/'], " +
                "a[href*='/actors/'], a[href*='/person/'], a[href*='/cast/']"
        )) {
            if (link.selectFirst("img") != null) {
                addActorCard(link, document.location().ifBlank { mainUrl })
            } else {
                val name = cleanTitle(link.text())
                if (name.isNotBlank() && name.length <= 70 && !isGenericTitle(name)) {
                    actors.putIfAbsent(normalize(name), ActorData(Actor(name)))
                }
            }
        }

        // JSON-LD is a final fallback; never replace portrait data already found in the page.
        if (actors.isEmpty()) {
            val raw = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }
            val actorObjects = Regex(
                """"@type"\s*:\s*"Person".{0,500}?"name"\s*:\s*"([^"]+)" """
                    .trim(),
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            actorObjects.findAll(raw).forEach { match ->
                val name = cleanTitle(match.groupValues.getOrNull(1))
                if (name.isNotBlank() && !isGenericTitle(name)) {
                    actors.putIfAbsent(normalize(name), ActorData(Actor(name)))
                }
            }
        }
        return actors.values.take(50)
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
            app.get(pageUrl, headers = requestHeaders + ("Referer" to pageUrl), referer = "$mainUrl/", allowRedirects = true)
        }.getOrNull()
        val document = response?.takeIf { it.isSuccessful }?.document
        val html = response?.takeIf { it.isSuccessful }?.text.orEmpty()
        if (html.isBlank()) return false
        val decoded = normalizeEmbeddedText(html)
        val players = LinkedHashSet<String>()
        val directMedia = LinkedHashSet<String>()
        val apiCandidates = LinkedHashSet<String>()
        val subtitles = LinkedHashMap<String, String>()
        val emitted = LinkedHashSet<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            if (emitted.add(link.url)) {
                callback(link)
                found = true
            }
        }

        fun addCandidate(raw: String?, base: String, forcePlayer: Boolean = false, apiHint: Boolean = false) {
            val url = absoluteUrl(raw, base) ?: return
            if (isSubtitleUrl(url)) {
                val label = when {
                    url.contains("/en/", true) || url.contains("english", true) -> "English"
                    url.contains("/zh/", true) || url.contains("chinese", true) -> "Chinese"
                    else -> "Türkçe"
                }
                subtitles.putIfAbsent(url, label)
                return
            }
            if (isDirectMedia(url)) {
                directMedia.add(url)
                return
            }

            val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            val sameSite = host == "diziasya.com" || host.endsWith(".diziasya.com")
            val lower = url.lowercase(Locale.ROOT)
            if (listOf(
                    "google-analytics", "googletagmanager", "doubleclick", "adservice",
                    "facebook.com", "twitter.com", "whatsapp", "hotjar", "clarity.ms"
                ).any { lower.contains(it) }
            ) return
            if (Regex("""(?i)\.(?:css|js|mjs|png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf)(?:$|[?#])""")
                    .containsMatchIn(url)
            ) return

            val path = pathOf(url).lowercase(Locale.ROOT)
            val isApi = sameSite && (
                path.contains("/api/") || path.contains("/source") ||
                    path.contains("/server") || path.contains("/watch/") ||
                    path.contains("/embed/") || path.contains("/player/")
                )
            if (isApi && apiHint) apiCandidates.add(url)

            // Do not turn arbitrary nearby page assets into video choices. Forced URLs
            // must resemble a player path unless the hostname identifies a known video host.
            val knownHost = listOf(
                "ok.ru", "odnoklassniki.ru", "vidmoly", "abyss", "4me", "p2p", "abstr",
                "lulu", "streamwish", "filemoon", "dood", "mixdrop", "uqload", "voe",
                "dailymotion", "youtube.com", "youtu.be", "streamtape", "vidoza"
            ).any { host.contains(it) }
            val playerPath = listOf(
                "/embed/", "/player/", "/video/", "/watch/", "/stream/", "/source/",
                "/server/", "/iframe/", "videoid=", "video_id=", "file=", "url="
            ).any { lower.contains(it) }
            if (forcePlayer || isKnownPlayer(url)) {
                // A server button/iframe is explicit evidence that this URL is a player.
                // Keep unknown player hosts too; they need not contain "embed" in their path.
                if (!sameSite && (forcePlayer || knownHost || playerPath)) players.add(url)
                else if (sameSite && isApi && apiHint) apiCandidates.add(url)
                else if (sameSite && isKnownPlayer(url)) players.add(url)
            }
        }

        val candidateAttrs = listOf(
            "src", "href", "data-src", "data-lazy-src", "data-url", "data-href",
            "data-embed", "data-embed-url", "data-iframe", "data-iframe-src",
            "data-player", "data-player-url", "data-video", "data-video-url",
            "data-server", "data-server-url", "data-source", "data-source-url",
            "data-stream", "data-file", "data-link", "data-id"
        )

        if (document != null) {
            document.select(
                "iframe, video, source, embed, [data-src], [data-url], [data-embed], [data-iframe], " +
                    "[data-player], [data-video], [data-server], [data-source], [data-provider], button, [role=button]"
            ).forEach { element ->
                val label = normalize(
                    element.text() + " " + element.attr("aria-label") + " " +
                        element.attr("title") + " " + element.attr("data-provider") + " " +
                        element.attr("data-server") + " " + element.attr("data-source")
                )
                val sourceButton = listOf(
                    "diziasya2", "okru", "vidmoly", "abyss", "4me", "ev", "p2p", "abstr", "lulu"
                ).any { label.contains(it) }
                for (attr in candidateAttrs) {
                    if (!element.hasAttr(attr)) continue
                    val raw = element.attr(attr).trim()
                    // Source ids such as "okru" are not URLs; only follow URL-shaped values.
                    if (raw.isBlank() || !(raw.startsWith("http", true) || raw.startsWith("//") ||
                            raw.startsWith("/") || raw.contains("://"))
                    ) continue
                    addCandidate(
                        raw,
                        element.baseUri().ifBlank { pageUrl },
                        forcePlayer = element.tagName().equals("iframe", true) ||
                            element.tagName().equals("source", true) ||
                            sourceButton,
                        apiHint = sourceButton || element.hasAttr("data-server") ||
                            element.hasAttr("data-provider") || element.hasAttr("data-source")
                    )
                }

                // A URL can be embedded in onclick or another inline attribute even when
                // the visible text is only "OKRU", "Vidmoly", "Abyss", etc.
                for (attr in element.attributes().asList()) {
                    val value = attr.value
                    if (!value.contains("http", true) && !value.contains("/api/", true) &&
                        !value.contains("/embed/", true) && !value.contains("/player/", true)
                    ) continue
                    Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+|/(?:api|embed|player|watch|source|server)/[^"'<>\\\s]+""")
                        .findAll(normalizeEmbeddedText(value))
                        .forEach { addCandidate(it.value, pageUrl, forcePlayer = sourceButton, apiHint = sourceButton) }
                }
            }

            document.select("track[src], track[data-src]").forEach { track ->
                val raw = track.attr("src").ifBlank { track.attr("data-src") }
                val sub = absoluteUrl(raw, pageUrl) ?: return@forEach
                if (isSubtitleUrl(sub)) {
                    subtitles.putIfAbsent(sub, track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Türkçe" } })
                }
            }

            document.select("a[href], source[src], video[src], iframe[src]").forEach { element ->
                val raw = element.attr("href").ifBlank { element.attr("src") }
                addCandidate(raw, pageUrl, forcePlayer = element.tagName().equals("iframe", true))
            }
        }

        // Extract URLs from serialized React/Next.js state and JS object fields. Search source
        // labels too, because the site renders provider buttons but may keep their URLs in data.
        val sources = buildList {
            add(decoded)
            if (document != null) {
                document.select("script").forEach { script ->
                    add(normalizeEmbeddedText(script.data() + "\n" + script.html()))
                }
            }
        }
        val playerValueRegex = Regex(
            """(?is)["']?(?:src|file|url|href|iframe|embed|player|stream|video|source|server|media)(?:Url|URL|_url)?["']?\s*[:=]\s*["']([^"'<>]{5,1800})["']"""
        )
        val urlRegex = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]{6,1200}""")
        val relativeMediaRegex = Regex(
            """(?i)["']((?:/|\.{1,2}/)[^"'<> ]{3,1000})["']"""
        )
        val providerLabels = listOf("Diziasya2", "OKRU", "Vidmoly", "Abyss", "4Me", "EV", "P2P", "ABStr", "LULU")

        for (source in sources) {
            urlRegex.findAll(source).forEach { match ->
                val value = match.value.trimEnd(',', ';', ')', ']', '}', '\\')
                addCandidate(value, pageUrl)
            }
            playerValueRegex.findAll(source).forEach { match ->
                val value = match.groupValues.getOrNull(1).orEmpty().trim()
                val prefix = source.substring((match.range.first - 240).coerceAtLeast(0), match.range.first)
                val sourceHint = providerLabels.any { prefix.contains(it, true) } ||
                    providerLabels.any { source.substring(match.range.last.coerceAtMost(source.length),
                        (match.range.last + 240).coerceAtMost(source.length)).contains(it, true) }
                addCandidate(value, pageUrl, forcePlayer = sourceHint, apiHint = sourceHint)
            }
        }

        // Buttons sometimes carry only a provider name in server-rendered data.
        // Inspect the text window around each label to associate it with serialized URLs.
        for (source in sources) {
            for (label in providerLabels) {
                var offset = 0
                var count = 0
                while (count < 8) {
                    val at = source.indexOf(label, offset, ignoreCase = true)
                    if (at < 0) break
                    val start = (at - 500).coerceAtLeast(0)
                    val end = (at + label.length + 900).coerceAtMost(source.length)
                    val window = source.substring(start, end)
                    urlRegex.findAll(window).forEach { match ->
                        val value = match.value.trimEnd(',', ';', ')', ']', '}', '\\')
                        addCandidate(value, pageUrl, forcePlayer = true, apiHint = true)
                    }
                    relativeMediaRegex.findAll(window).forEach { match ->
                        val value = match.groupValues.getOrNull(1).orEmpty()
                        if (value.contains("/api/", true) || value.contains("/embed/", true) ||
                            value.contains("/player/", true) || value.contains("/watch/", true) ||
                            value.contains("/source", true) || value.contains("/server", true)
                        ) addCandidate(value, pageUrl, forcePlayer = true, apiHint = true)
                    }
                    offset = at + label.length
                    count++
                }
            }
        }

        // Only inspect a small number of page-specific Next.js chunks. Some current pages
        // keep server selection data in the route chunk rather than the initial HTML.
        val scriptUrls = document?.select("script[src]")?.mapNotNull {
            absoluteUrl(it.attr("src"), pageUrl)
        }?.distinct()?.filter {
            it.contains("/_next/static/", true) && it.endsWith(".js", true) &&
                (it.contains("dizi", true) || it.contains("video", true) ||
                    it.contains("watch", true) || it.contains("page-", true))
        }?.take(8).orEmpty()
        for (scriptUrl in scriptUrls) {
            val script = runCatching {
                app.get(scriptUrl, headers = requestHeaders + ("Referer" to pageUrl), referer = pageUrl)
            }.getOrNull()?.takeIf { it.isSuccessful }?.text ?: continue
            if (script.length > 2_000_000) continue
            urlRegex.findAll(normalizeEmbeddedText(script)).forEach { match ->
                val value = match.value.trimEnd(',', ';', ')', ']', '}', '\\')
                addCandidate(value, scriptUrl)
            }
            playerValueRegex.findAll(normalizeEmbeddedText(script)).forEach { match ->
                addCandidate(match.groupValues.getOrNull(1), scriptUrl, forcePlayer = false)
            }
            for (label in providerLabels) {
                val at = script.indexOf(label, ignoreCase = true)
                if (at < 0) continue
                val window = script.substring((at - 300).coerceAtLeast(0), (at + 1100).coerceAtMost(script.length))
                urlRegex.findAll(window).forEach { match ->
                    addCandidate(match.value.trimEnd(',', ';', ')', ']', '}', '\\'), scriptUrl,
                        forcePlayer = true, apiHint = true)
                }
            }
        }

        // If source buttons point to a same-site API route, request that route and parse its
        // response for iframe, embed, HLS, MP4, subtitle and JSON URL fields.
        val visitedApi = HashSet<String>()
        for (endpoint in apiCandidates.take(12)) {
            if (!visitedApi.add(endpoint)) continue
            val apiResponse = runCatching {
                app.get(endpoint, headers = requestHeaders + ("Referer" to pageUrl), referer = pageUrl, allowRedirects = true)
            }.getOrNull()?.takeIf { it.isSuccessful } ?: continue
            val body = normalizeEmbeddedText(apiResponse.text)
            urlRegex.findAll(body).forEach { match ->
                addCandidate(match.value.trimEnd(',', ';', ')', ']', '}', '\\'), endpoint, forcePlayer = true)
            }
            playerValueRegex.findAll(body).forEach { match ->
                addCandidate(match.groupValues.getOrNull(1), endpoint, forcePlayer = true)
            }
            val apiDoc = apiResponse.document
            apiDoc.select("iframe, video, source, embed, [data-src], [data-url], [data-embed]").forEach { element ->
                for (attr in candidateAttrs) {
                    if (element.hasAttr(attr)) addCandidate(element.attr(attr), endpoint, forcePlayer = true)
                }
            }
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

        for ((url, label) in subtitles) runCatching { subtitleCallback(SubtitleFile(label, url)) }
        for (player in players.distinct()) runCatching { loadExtractor(player, pageUrl, subtitleCallback, ::emit) }

        // Resolve same-site source/player routes discovered in button state through extractors
        // only after trying their JSON/HTML response above.
        for (endpoint in apiCandidates.distinct().take(12)) {
            if (endpoint in players) continue
            runCatching { loadExtractor(endpoint, pageUrl, subtitleCallback, ::emit) }
        }
        return found
    }
}
