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
    private val quotedSubtitleRegex = Regex("""(?i)["']((?:(?:https?:)?//|/|\.\.?/)?[^"'<>\\\s]{1,260}\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?)["']""")

    private fun decode(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\u003F", "?", ignoreCase = true)
        .replace("\\u0023", "#", ignoreCase = true)
        .replace("\\x23", "#", ignoreCase = true)
        .replace("%23", "#")
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

    private suspend fun fetchImdbScore(url: String, referer: String): Score? =
        runCatching {
            app.get(
                url,
                headers = requestHeaders + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 4500
            )
        }.onFailure {
            Log.d(name, "IMDb puanı alınamadı: " + safeLogUrl(url))
        }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document
            ?.let { parseScore(it) }


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
        imageUrl(link.selectFirst("img"), base)?.let { return it }

        // A parent image is safe only when that small wrapper points to one
        // content page. The old broad parent/parent lookup could borrow a poster
        // from a neighbouring section or from the "Son Yorumlar" widget.
        var parent: Element? = link.parent()
        repeat(3) {
            val container = parent ?: return null
            val filmTargets = container.select("a[href]").mapNotNull { anchor ->
                val url = fixUrl(anchor.attr("href"), base) ?: return@mapNotNull null
                url.takeIf { sameSite(it) && isFilmUrl(it) && !isCommentOrChrome(anchor) }
            }.map { it.trimEnd('/') }.distinct()

            if (filmTargets.size == 1) {
                val image = container.select("img").firstOrNull()
                imageUrl(image, base)?.let { return it }
            }
            parent = container.parent()
        }
        return null
    }

    private fun normalizeSectionText(value: String?): String =
        cleanText(value).lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isCommentOrChrome(anchor: Element): Boolean {
        val blockedMarker = Regex(
            """(?i)(comment|comments|yorum|yorumlar|review|reviews|feedback|disqus|recent-comments|latest-comments)"""
        )
        var current: Element? = anchor
        repeat(9) {
            val node = current ?: return false
            val marker = node.id() + " " + node.className()
            if (blockedMarker.containsMatchIn(marker)) return true
            if (node.tagName().lowercase(Locale.ROOT) in setOf("footer", "aside", "nav")) return true
            current = node.parent()
        }
        return false
    }

    private fun isCommentSectionHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        return normalized.contains("son yorum") ||
            normalized == "yorumlar" ||
            normalized == "comments" ||
            normalized == "recent comments" ||
            normalized == "latest comments"
    }

    private fun isNewContentHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        return normalized.contains("yeni eklenen bolum") ||
            normalized.contains("son eklenen bolum") ||
            normalized.contains("yeni bolumler") ||
            normalized.contains("yeni eklenen film") ||
            normalized == "yeni eklenenler"
    }

    private fun isSectionBoundaryHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        if (isNewContentHeading(normalized)) return true
        return listOf(
            "son yorum", "yorumlar", "comments", "recent comments", "latest comments",
            "en cok izlenen", "trend filmler", "trend diziler", "yakinda gelecek",
            "2026 yili", "2025 yili", "kesfet", "film robotu", "kategoriler",
            "film turleri", "tum filmler", "seriler", "platformlar", "haftanin secme"
        ).any { normalized.contains(it) }
    }

    private fun newContentHeading(document: Document): Element? {
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        // Episode-specific heading wins when the site exposes one; otherwise use
        // the site's new-film/new-content section.
        return headings.firstOrNull {
            val title = normalizeSectionText(it.text())
            title.contains("yeni eklenen bolum") ||
                title.contains("son eklenen bolum") ||
                title.contains("yeni bolumler")
        } ?: headings.firstOrNull { isNewContentHeading(it.text()) }
    }

    private fun linksAfterHeading(
        document: Document,
        heading: Element,
        base: String
    ): List<Element> {
        val elements = document.getAllElements()
        val headingIndex = elements.indexOfFirst { it === heading }
        if (headingIndex < 0) return emptyList()

        val links = ArrayList<Element>()
        for (index in (headingIndex + 1) until elements.size) {
            val element = elements[index]
            val tag = element.tagName().lowercase(Locale.ROOT)
            if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val headingText = normalizeSectionText(element.ownText().ifBlank { element.text() })
                if (isSectionBoundaryHeading(headingText)) break
            }
            if (tag != "a" || !element.hasAttr("href") || isCommentOrChrome(element)) continue
            val absolute = fixUrl(element.attr("href"), base) ?: continue
            if (!sameSite(absolute)) continue
            links.add(element)
        }
        return links
    }

    private fun findNewContentMoreUrl(document: Document, base: String): String? {
        val heading = newContentHeading(document)
        if (heading != null) {
            val scopedLinks = linksAfterHeading(document, heading, base)
            val more = scopedLinks.firstOrNull { anchor ->
                val label = normalizeSectionText(
                    anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
                )
                label.contains("tumunu gor") ||
                    label.contains("tum bolumleri gor") ||
                    label.contains("hepsini gor") ||
                    label.contains("tum bolumler")
            }
            if (more != null) {
                val url = fixUrl(more.attr("href"), base) ?: return null
                if (sameSite(url) && url.trimEnd('/') != base.trimEnd('/')) return url
            }

            // On templates where the "Tümünü Gör" control sits just outside the
            // heading's sibling range, inspect ancestors containing this section.
            var parent: Element? = heading.parent()
            repeat(6) {
                val container = parent ?: return@repeat
                val hasContentCards = container.select("a[href]").any { anchor ->
                    val url = fixUrl(anchor.attr("href"), base) ?: return@any false
                    sameSite(url) && isFilmUrl(url) && !isCommentOrChrome(anchor)
                }
                if (hasContentCards) {
                    val button = container.select("a[href]").firstOrNull { anchor ->
                        if (isCommentOrChrome(anchor)) return@firstOrNull false
                        val label = normalizeSectionText(
                            anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
                        )
                        label.contains("tumunu gor") ||
                            label.contains("tum bolumleri gor") ||
                            label.contains("hepsini gor") ||
                            label.contains("tum bolumler")
                    }
                    val url = button?.let { fixUrl(it.attr("href"), base) }
                    if (url != null && sameSite(url) && url.trimEnd('/') != base.trimEnd('/')) return url
                }
                parent = container.parent()
            }
        }

        // Fallback: only accept a "Tümünü Gör" control near actual content cards.
        for (anchor in document.select("a[href]")) {
            if (isCommentOrChrome(anchor)) continue
            val label = normalizeSectionText(
                anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
            )
            if (!(label.contains("tumunu gor") || label.contains("tum bolumleri gor") ||
                    label.contains("hepsini gor") || label.contains("tum bolumler"))
            ) continue
            val url = fixUrl(anchor.attr("href"), base) ?: continue
            if (!sameSite(url) || url.trimEnd('/') == base.trimEnd('/')) continue
            val nearContent = anchor.parents().take(6).any { ancestor ->
                ancestor.select("a[href]").count { sibling ->
                    val siblingUrl = fixUrl(sibling.attr("href"), base) ?: return@count false
                    isFilmUrl(siblingUrl) && !isCommentOrChrome(sibling)
                } >= 3
            }
            if (nearContent) return url
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
        var current: Element? = element
        repeat(4) {
            val node = current ?: return@repeat
            val explicit = node.selectFirst(
                "[data-imdb], [itemprop=ratingValue], .imdb-rating, .imdb-score, [aria-label*=IMDb], [title*=IMDb]"
            )?.let { selected ->
                selected.attr("data-imdb")
                    .ifBlank { selected.attr("content") }
                    .ifBlank { selected.attr("aria-label") }
                    .ifBlank { selected.attr("title") }
                    .ifBlank { selected.text() }
            }
            if (!explicit.isNullOrBlank()) {
                val imdb = Regex("""(?is)\bIMDb\b[^\d]{0,50}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
                    .find(explicit)?.groupValues?.getOrNull(1)
                val numeric = (imdb ?: explicit).let {
                    Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                        .find(it)?.value
                }?.replace(',', '.')?.toDoubleOrNull()
                if (numeric != null && numeric in 0.0..10.0) {
                    return runCatching { Score.from10(numeric) }.getOrNull()
                }
            }
            extractScore(node.text())?.let { return it }
            current = node.parent()
        }

        // Fallback to the site's visible rating if the card has no explicit IMDb value.
        val generic = listOf(
            element.selectFirst("[data-rating]")?.attr("data-rating"),
            element.selectFirst(".rating, .score, .film-puan")?.text(),
            element.attr("aria-label"),
            element.attr("title")
        )
        for (raw in generic) {
            val number = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(raw.orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (number in 0.0..10.0) return runCatching { Score.from10(number) }.getOrNull()
        }
        return null
    }
    private suspend fun parseCards(
        document: Document,
        base: String,
        query: String? = null,
        candidateAnchors: List<Element>? = null
    ): List<SearchResponse> {
        val output = LinkedHashMap<String, SearchResponse>()
        val detailLookupLimit = if (query != null) 12 else 8
        var detailLookups = 0

        // Even if the website's theme gives its comments widget no useful CSS
        // class, anything after the "Son Yorumlar" heading is not a content card.
        val allElements = document.getAllElements()
        val commentHeadingIndex = allElements.indexOfFirst { element ->
            element.tagName().lowercase(Locale.ROOT) in setOf("h1", "h2", "h3", "h4", "h5", "h6") &&
                isCommentSectionHeading(element.ownText().ifBlank { element.text() })
        }
        val afterCommentHeading = if (commentHeadingIndex >= 0) {
            allElements.drop(commentHeadingIndex + 1).toHashSet()
        } else {
            emptySet()
        }

        for (anchor in candidateAnchors ?: document.select("a[href]")) {
            if (isCommentOrChrome(anchor)) continue
            if (candidateAnchors == null && anchor in afterCommentHeading) continue
            val url = fixUrl(anchor.attr("href"), base) ?: continue
            if (!sameSite(url) || !isFilmUrl(url)) continue
            val key = url.trimEnd('/')
            if (output.containsKey(key)) continue

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
            val cardScore = scoreFromCard(cardContext)
            val detailScore = if (detailLookups < detailLookupLimit) {
                detailLookups++
                fetchImdbScore(url, base)
            } else null
            val score = detailScore ?: cardScore
            Log.d(
                name,
                "Kart puanı: title=" + title.take(60) +
                    ", imdb=" + (detailScore != null) +
                    ", score=" + (score != null)
            )
            output[key] = newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }
        }
        return output.values.toList()
    }

    private fun mergeCardResults(
        preferred: List<SearchResponse>,
        additional: List<SearchResponse>
    ): List<SearchResponse> {
        val merged = LinkedHashMap<String, SearchResponse>()
        for (item in preferred + additional) {
            val key = item.url.trimEnd('/')
            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                if (existing.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank()) {
                    existing.posterUrl = item.posterUrl
                }
                if (existing.posterHeaders == null && item.posterHeaders != null) {
                    existing.posterHeaders = item.posterHeaders
                }
                if (existing.score == null && item.score != null) {
                    existing.score = item.score
                }
            }
        }
        return merged.values.toList()
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
        val homeRequest = pathOf(request.data).trimEnd('/').isBlank() ||
            request.data.trimEnd('/') == mainUrl.trimEnd('/')
        var contentListingUrl: String? = null

        // The "Tümünü Gör" target is the canonical source for older entries.
        // Rediscover it for page 2+ because HomePageRequest keeps the original homepage URL.
        if (homeRequest && page > 1) {
            val homeDocument = fetchDocument(mainUrl) ?: fetchDocument(mainUrl + "/")
            if (homeDocument != null) {
                contentListingUrl = findNewContentMoreUrl(homeDocument, mainUrl + "/")
            }
        }

        val pageBase = if (homeRequest && page > 1 && !contentListingUrl.isNullOrBlank()) {
            contentListingUrl!!
        } else {
            request.data
        }
        val urls = pageCandidates(pageBase, page)

        for (url in urls) {
            val doc = fetchDocument(url) ?: continue
            val baseSection = newContentHeading(doc)
            val scopedAnchors = if (homeRequest && page == 1 && baseSection != null) {
                linksAfterHeading(doc, baseSection, url).filter { anchor ->
                    val target = fixUrl(anchor.attr("href"), url)
                    target != null && sameSite(target) && isFilmUrl(target) && !isCommentOrChrome(anchor)
                }
            } else {
                null
            }

            var items = parseCards(
                doc,
                url,
                candidateAnchors = scopedAnchors?.takeIf { it.isNotEmpty() }
            )

            if (homeRequest && page == 1) {
                val moreUrl = findNewContentMoreUrl(doc, url)
                if (!moreUrl.isNullOrBlank() && moreUrl.trimEnd('/') != url.trimEnd('/')) {
                    val moreDocument = fetchDocument(moreUrl, url)
                    if (moreDocument != null) {
                        val moreHeading = newContentHeading(moreDocument)
                        val moreAnchors = if (moreHeading != null) {
                            linksAfterHeading(moreDocument, moreHeading, moreUrl).filter { anchor ->
                                val target = fixUrl(anchor.attr("href"), moreUrl)
                                target != null && sameSite(target) && isFilmUrl(target) &&
                                    !isCommentOrChrome(anchor)
                            }
                        } else {
                            null
                        }
                        val moreItems = parseCards(
                            moreDocument,
                            moreUrl,
                            candidateAnchors = moreAnchors?.takeIf { it.isNotEmpty() }
                        )
                        if (moreItems.isNotEmpty()) {
                            Log.d(
                                name,
                                "Tümünü Gör listesi: url=" + safeLogUrl(moreUrl) +
                                    ", section=" + (moreHeading?.text().orEmpty()) +
                                    ", count=" + moreItems.size
                            )
                            items = mergeCardResults(items, moreItems)
                        } else {
                            Log.d(name, "Tümünü Gör sayfasında içerik bulunamadı: " + safeLogUrl(moreUrl))
                        }
                    }
                }
            }

            Log.d(
                name,
                "Ana sayfa bölümü: request=" + request.name +
                    ", page=" + page +
                    ", section=" + baseSection?.text().orEmpty() +
                    ", items=" + items.size
            )
            if (items.isNotEmpty() || page == 1) {
                val hasMore = page < 30 && (items.isNotEmpty() || contentListingUrl != null)
                return newHomePageResponse(request.name, items, hasNext = hasMore)
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
        val genreSelectors =
            "a[href*=/tur/], a[href*=/kategori/], a[href*=/genre/], a[href*=/tag/], [itemprop=genre]"
        val detailSelectors =
            ".genres a, .genre a, .film-genres a, .movie-genres a, .film-meta a, " +
                ".movie-meta a, .detail-meta a, .movie-details a, .film-details a, " +
                ".film-info a, .movie-info a, .meta-info a, .tags a"
        val anchors = context.select(genreSelectors + ", " + detailSelectors) +
            document.select("main " + detailSelectors + ", article " + detailSelectors)
        fun addGenre(raw: String?) {
            val text = cleanText(raw)
                .replace(Regex("""(?i)\s+filmleri?$"""), "")
                .replace(Regex("""(?i)\s+filmi$"""), "")
                .trim(' ', ',', '|', '-', ':')
            if (text.length !in 2..35 || text.equals("Filmler", true) ||
                text.contains("tüm tür", true) || text.contains("kategori", true)
            ) return
            result += text
        }
        for (anchor in anchors) {
            val text = anchor.attr("content").ifBlank { anchor.attr("title") }.ifBlank { anchor.text() }
            if (anchor.parents().any {
                    it.tagName() in setOf("nav", "header", "footer") ||
                        it.className().contains("site-menu", true) ||
                        it.className().contains("footer-menu", true) ||
                        it.className().contains("main-menu", true)
                }) continue
            addGenre(text)
        }

        // Bazı tema sürümleri türleri görünür bağlantı yerine JSON-LD içinde tutuyor.
        for (script in document.select("script[type=application/ld+json]")) {
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            when (val genre = json.opt("genre")) {
                is String -> genre.split(",", "|", "•").forEach(::addGenre)
                is org.json.JSONArray -> for (i in 0 until genre.length()) {
                    addGenre(genre.optString(i))
                }
            }
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
            Regex("""\.(?:jpe?g|png|gif|webp|svg|ico|css|js|json|map|xml|txt|woff2?|ttf)(?:$|\?)""").containsMatchIn(url.lowercase(Locale.ROOT))
    }

    private fun isRejectedPlayerCandidate(raw: String, fixed: String): Boolean {
        val original = decode(raw).trim().lowercase(Locale.ROOT)
        if (original.isBlank() || original.startsWith("javascript:") ||
            original.contains("getembedurl") || original.contains("activesource") ||
            original.contains("[object") || original.contains("undefined") ||
            original.contains("null") || original.contains("\${") ||
            original.contains("function(") || original.contains("function (")
        ) return true

        val host = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        // Trailer embeds must never become the movie playback source.
        // This site places its trailer YouTube iframe alongside the player source buttons.
        val blockedHosts = listOf(
            "googletagmanager.com", "google-analytics.com", "accounts.google.com",
            "static.cloudflareinsights.com", "github.com", "githubusercontent.com",
            "fonts.googleapis.com", "fonts.gstatic.com", "doubleclick.net",
            "googlesyndication.com", "googleadservices.com", "facebook.com",
            "connect.facebook.net", "t.me", "telegram.org", "twitter.com",
            "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com",
            "youtube.com", "youtube-nocookie.com", "youtu.be", "youtube.googleapis.com",
            "googlevideo.com", "ytimg.com", "gstatic.com"
        )
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (host == "filmizlehell.net" || host == "www.filmizlehell.net") {
            if (path.contains("/film/getembedurl") || path.contains("/film/get-embed-url")) return true
        }
        if (path == "/gtag/js" || path == "/gsi/client" ||
            path.endsWith("/beacon.min.js") || path.endsWith(".js") ||
            path.endsWith(".css") || path.endsWith(".map")
        ) return true
        return false
    }

    private fun addPlayerCandidate(raw: String?, base: String, direct: MutableSet<String>, embeds: MutableSet<String>) {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'').trimEnd(';', ',')
        if (value.isBlank() || value.length > 1800) return
        val fixed = fixUrl(value, base) ?: return
        if (isRejectedPlayerCandidate(value, fixed) || isStaticAsset(fixed)) return
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
                "source[src], source[data-src], embed[src], [data-embed], [data-player], [data-video], " +
                "[data-iframe], [data-source], [data-source-url], [data-source-id], [data-server], " +
                "[data-server-id], [data-stream], [data-stream-id], [data-video-id], [data-file], " +
                "button[onclick], [onclick]"
        )
        val attrs = listOf(
            "src", "data-src", "data-url", "data-embed", "data-player", "data-video",
            "data-iframe", "data-iframe-src", "data-href", "data-link", "data-file", "data-hls",
            "data-source", "data-source-url", "data-source-id", "data-server", "data-server-id",
            "data-stream", "data-stream-id", "data-video-id", "data-media-id", "data-video"
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
        val baseHost = runCatching { URI(base).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (baseHost == "p.playturka.space" || decoded.contains("p.playturka.space", true)) {
            Regex("""(?i)(/videos/[A-Za-z0-9_-]{1,64}/(?:master|index)\.m3u8(?:\?[^"'<>\\\s]*)?)""")
                .findAll(decoded)
                .forEach { addPlayerCandidate(it.groupValues[1], base, direct, embeds) }
        }
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

    private fun safeLogUrl(raw: String): String =
        runCatching {
            val uri = URI(raw)
            val keys = uri.rawQuery.orEmpty().split('&')
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=').take(32) }
                .distinct()
                .take(8)
            val queryHint = if (keys.isEmpty()) "" else keys.joinToString(
                prefix = "?",
                separator = "&"
            ) { "$it=*" }
            (uri.host.orEmpty() + uri.path.orEmpty() + queryHint).take(260)
        }.getOrDefault(raw.substringBefore('?').take(240))

    private fun extractPlayturkaIds(embedUrl: String, document: Document, html: String): Set<String> {
        val host = runCatching { URI(embedUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host != "p.playturka.space" && !html.contains("p.playturka.space", true)) return emptySet()

        val ids = LinkedHashSet<String>()
        val acceptedId = Regex("""^[A-Za-z0-9_-]{1,64}$""")
        val rawFragment = runCatching { URI(embedUrl).rawFragment.orEmpty() }.getOrDefault("").trim()
        val decodedFragment = runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }
            .getOrDefault(rawFragment).trim().removePrefix("#")
        if (acceptedId.matches(decodedFragment)) ids.add(decodedFragment)
        Regex("""(?i)(?:video[_-]?id|source[_-]?id|stream[_-]?id)=([A-Za-z0-9_-]{5,64})""")
            .find(decodedFragment)?.groupValues?.getOrNull(1)?.let { if (acceptedId.matches(it)) ids.add(it) }
        val longId = Regex("""^[A-Za-z0-9_-]{5,24}$""")
        val queryNames = setOf(
            "id", "v", "vid", "video", "video_id", "videoid", "source", "source_id",
            "sourceid", "stream", "stream_id", "streamid", "media", "media_id", "key", "token"
        )
        runCatching { URI(embedUrl).rawQuery.orEmpty() }.getOrDefault("")
            .split('&')
            .forEach { pair ->
                val key = pair.substringBefore('=')
                if (key.lowercase(Locale.ROOT) in queryNames) {
                    val rawValue = pair.substringAfter('=', "")
                    val value = runCatching { java.net.URLDecoder.decode(rawValue, "UTF-8") }
                        .getOrDefault(rawValue).trim()
                    if (acceptedId.matches(value)) ids += value
                }
            }

        val idAttributes = listOf(
            "data-video-id", "data-source-id", "data-stream-id", "data-media-id",
            "data-file-id", "data-id", "value",
            "data-video", "data-source", "data-stream", "data-file"
        )
        val sourceIdAttributes = setOf(
            "data-video-id", "data-source-id", "data-stream-id", "data-media-id", "data-file-id",
            "data-video", "data-source", "data-stream", "data-file"
        )
        for (element in document.select(
            "[data-video-id], [data-source-id], [data-stream-id], [data-media-id], [data-file-id], " +
                "[data-id], [data-video], [data-source], [data-stream], [data-file], input[name*=video], input[name*=source]"
        )) {
            for (attr in idAttributes) {
                val raw = element.attr(attr).trim()
                if (acceptedId.matches(raw) && (attr in sourceIdAttributes || longId.matches(raw))) {
                    ids += raw
                }
            }
        }

        val patterns = listOf(
            Regex("""(?i)(?:https?:)?//p\.playturka\.space/[^\s#]*#([A-Za-z0-9_-]{5,64})"""),
            Regex("""(?i)p\.playturka\.space[^\s]{0,180}?#([A-Za-z0-9_-]{5,64})"""),
            Regex("""(?i)(?:video[_-]?id|source[_-]?id|stream[_-]?id|media[_-]?id|file[_-]?id|activeSource)\s*["']?\s*[:=]\s*["']([A-Za-z0-9_-]{1,64})["']"""),
            Regex("""(?i)/videos/([A-Za-z0-9_-]{1,64})/(?:master\.m3u8|index\.m3u8)""")
        )
        for (pattern in patterns) {
            pattern.findAll(decode(html)).forEach { match ->
                match.groupValues.getOrNull(1)?.takeIf { acceptedId.matches(it) }?.let(ids::add)
            }
        }
        return ids
    }

    private suspend fun publishPlayturkaManifestCandidates(
        embedUrl: String,
        document: Document,
        html: String,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val host = runCatching { URI(embedUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host != "p.playturka.space" && !html.contains("p.playturka.space", true)) return 0

        val ids = LinkedHashSet(extractPlayturkaIds(embedUrl, document, html))
        if (ids.isEmpty()) {
            val scriptUrls = document.select("script[src]")
                .mapNotNull { fixUrl(it.attr("src"), embedUrl) }
                .distinct()
                .filter { pathOf(it).substringBefore('?').endsWith(".js", true) }
                .filterNot {
                    listOf("google-analytics", "googletagmanager", "doubleclick", "adsystem",
                        "cloudflareinsights", "fonts.", "cdnjs", "jsdelivr", "unpkg")
                        .any { term -> it.contains(term, true) }
                }
                .sortedByDescending {
                    listOf("player", "video", "source", "main", "app").any { term ->
                        pathOf(it).contains(term, true)
                    }
                }
                .take(8)

            for (scriptUrl in scriptUrls) {
                val scriptResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = requestHeaders + ("Referer" to embedUrl),
                        referer = embedUrl,
                        allowRedirects = true,
                        timeout = 8000
                    )
                }.getOrNull() ?: continue
                if (!scriptResponse.isSuccessful || scriptResponse.text.isBlank() ||
                    scriptResponse.text.length > 2_000_000
                ) continue
                val scriptText = decode(scriptResponse.text)
                val scriptHints = Regex(
                    """(?is).{0,100}(?:p\.playturka\.space|playturka|666film\.net|videoId|video_id|sourceId|source_id|embed.?url|playerUrl|sourceUrl|iframe|master\.m3u8|/videos/).{0,140}"""
                ).findAll(scriptText)
                    .take(7)
                    .map {
                        it.value.replace(Regex("""\s+"""), " ").trim()
                            .replace(Regex("""([?&][\w-]{1,40}=)[^&\s"'<>]+""")) { match ->
                                match.groupValues[1] + "*"
                            }
                    }
                    .joinToString(" | ")
                    .take(1500)
                if (scriptHints.isNotBlank()) {
                    Log.d(name, "Playturka kaynak JS: " + safeLogUrl(scriptUrl) + "; hints=" + scriptHints)
                }
                ids.addAll(
                    extractPlayturkaIds(
                        embedUrl,
                        Jsoup.parse("", scriptResponse.url),
                        scriptResponse.text
                    )
                )
                if (ids.isNotEmpty()) {
                    Log.d(name, "Playturka ID JS dosyasından bulundu: " + safeLogUrl(scriptUrl))
                    break
                }
            }
        }
        if (ids.isEmpty()) {
            val decodedHints = decode(html)
            val apiHints = Regex(
                """(?is).{0,70}(?:fetch\s*\(|XMLHttpRequest|axios|/api/|api/|master\.m3u8|/videos/|video[_-]?id|source[_-]?id|playlist|p\.playturka\.space|playturka|embed.?url|playerUrl|sourceUrl).{0,100}"""
            ).findAll(decodedHints)
                .take(8)
                .map {
                    it.value.replace(Regex("""\s+"""), " ").trim()
                        .replace(Regex("""([?&][\w-]{1,40}=)[^&\s"'<>]+""")) { match ->
                            match.groupValues[1] + "*"
                        }
                }
                .joinToString(" | ")
                .take(1200)
            val scriptHint = document.select("script[src]")
                .take(12)
                .mapNotNull { fixUrl(it.attr("src"), embedUrl) }
                .distinct()
                .joinToString(" | ") { safeLogUrl(it) }
            Log.d(name, "Playturka JS/API izleri: scripts=" + scriptHint + "; hints=" + apiHints)
            val sourceHints = document.select(
                "button, [onclick], [data-source], [data-source-id], [data-video-id], [data-embed], iframe[src], a[href]"
            ).filter { node ->
                val sample = (node.text() + " " + node.outerHtml()).lowercase(Locale.ROOT)
                listOf("playturka", "kaynak", "dublaj", "altyaz", "filmi başlat", "video", "source", "player", "server", "embed")
                    .any { sample.contains(it) }
            }.take(20).mapNotNull { node ->
                val label = cleanText(node.text()).take(36)
                val attrs = listOf(
                    "data-source-id", "data-video-id", "data-stream-id", "data-media-id",
                    "data-file-id", "data-source", "data-source-url", "data-embed", "data-player",
                    "data-id", "data-video", "data-src", "data-url", "src", "href", "onclick"
                ).mapNotNull { key ->
                    val value = node.attr(key).trim()
                    if (value.isBlank()) null
                    else {
                        val safeValue = if (key in setOf("data-src", "data-url", "src", "href", "data-source-url", "data-embed", "data-player")) {
                            safeLogUrl(fixUrl(value, embedUrl) ?: value)
                        } else value.take(70)
                        key + "=" + safeValue
                    }
                }.joinToString(",")
                if (label.isBlank() && attrs.isBlank()) null
                else listOf(label, attrs).filter { it.isNotBlank() }.joinToString(" [", postfix = if (attrs.isBlank()) "" else "]")
            }.joinToString(" | ")
            Log.d(name, "Playturka: ID bulunamadı; sayfa=" + safeLogUrl(embedUrl) +
                "; sourceHints=" + sourceHints.take(700))
            return 0
        }

        var found = 0
        val playerReferer = if (host == "p.playturka.space") embedUrl else "https://p.playturka.space/"
        for (id in ids.take(5)) {
            for (fileName in listOf("master.m3u8", "index.m3u8")) {
                val manifest = "https://p.playturka.space/videos/" + id + "/" + fileName
                val response = runCatching {
                    app.get(
                        manifest,
                        headers = requestHeaders + mapOf("Accept" to "*/*", "Referer" to playerReferer),
                        referer = playerReferer,
                        allowRedirects = true,
                        timeout = 10000
                    )
                }.onFailure {
                    Log.d(name, "Playturka manifest isteği başarısız: " + safeLogUrl(manifest))
                }.getOrNull() ?: continue
                val body = response.text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                val contentType = response.headers["content-type"].orEmpty()
                if (response.isSuccessful &&
                    (body.startsWith("#EXTM3U") ||
                        contentType.contains("mpegurl", true) ||
                        contentType.contains("m3u8", true))
                ) {
                    if (body.startsWith("#EXTM3U")) {
                        publishHlsSubtitleTracks(manifest, body, seenSubtitles, subtitleCallback)
                    }
                    val resolved = response.url.takeIf { isDirectMedia(it) } ?: manifest
                    publishDirect(resolved, playerReferer, callback)
                    Log.d(name, "Playturka manifest doğrulandı: id=" + id + ", url=" + safeLogUrl(resolved))
                    found++
                    break
                } else {
                    Log.d(name, "Playturka manifest doğrulanmadı: " + safeLogUrl(manifest) +
                        ", HTTP=" + response.code + ", type=" + contentType.take(80))
                }
            }
        }
        return found
    }

    private suspend fun collectExternalScriptCandidates(
        document: Document,
        base: String,
        referer: String,
        visitedScripts: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Pair<Set<String>, Set<String>> {
        val direct = LinkedHashSet<String>()
        val embeds = LinkedHashSet<String>()
        val blocked = listOf(
            "google-analytics", "googletagmanager", "doubleclick", "adsystem",
            "advert", "hotjar", "clarity", "facebook", "cookie", "share",
            "cloudflareinsights", "github.com", "githubusercontent", "google.com",
            "gstatic.com", "fonts.", "cdnjs", "jsdelivr", "unpkg"
        )
        val scriptUrls = document.select("script[src]")
            .mapNotNull { fixUrl(it.attr("src"), base) }
            .distinct()
            .filter { pathOf(it).substringBefore('?').endsWith(".js", true) }
            .filterNot { blocked.any { term -> it.contains(term, true) } }
            .sortedByDescending {
                listOf("player", "video", "source", "film", "main", "app").any { hint ->
                    pathOf(it).contains(hint, true)
                }
            }
            .take(8)

        var fetched = 0
        for (scriptUrl in scriptUrls) {
            if (!visitedScripts.add(scriptUrl)) continue
            val response = runCatching {
                app.get(
                    scriptUrl,
                    headers = requestHeaders + ("Referer" to referer),
                    referer = referer,
                    allowRedirects = true,
                    timeout = 10000
                )
            }.onFailure {
                Log.d(name, "JS isteği başarısız: " + safeLogUrl(scriptUrl) + " (" + it.javaClass.simpleName + ")")
            }.getOrNull() ?: continue
            if (!response.isSuccessful || response.text.isBlank() || response.text.length > 2_000_000) {
                Log.d(name, "JS atlandı: " + safeLogUrl(scriptUrl) + " HTTP=" + response.code)
                continue
            }
            fetched++
            publishSubtitles(
                Jsoup.parse("", response.url),
                response.text,
                response.url,
                seenSubtitles,
                subtitleCallback
            )
            val (scriptDirect, scriptEmbeds) =
                collectPlayerCandidates(Jsoup.parse("", response.url), response.text, response.url)
            direct.addAll(scriptDirect)
            embeds.addAll(scriptEmbeds)
        }
        Log.d(name, "JS taraması: fetched=" + fetched + ", direct=" + direct.size +
            ", embeds=" + embeds.size + ", page=" + safeLogUrl(base))
        return direct to embeds
    }

    private suspend fun publishDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank() || !isDirectMedia(url) || isRejectedPlayerCandidate(url, url) || isStaticAsset(url)) {
            Log.d(name, "Geçersiz/fragman medya bağlantısı atlandı: " + safeLogUrl(url))
            return
        }
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

    private suspend fun publishSubtitles(
        document: Document,
        html: String,
        base: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val url = fixUrl(raw, base) ?: continue
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) continue
            if (!seen.add(url)) continue
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            callback(newSubtitleFile(label, url))
        }

        val decoded = decode(html)
        subtitleRegex.findAll(decoded).forEach { match ->
            val url = fixUrl(match.value, base) ?: return@forEach
            if (seen.add(url)) callback(newSubtitleFile("Altyazı", url))
        }
        quotedSubtitleRegex.findAll(decoded).forEach { match ->
            val url = fixUrl(match.groupValues[1], base) ?: return@forEach
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) return@forEach
            if (seen.add(url)) callback(newSubtitleFile("Altyazı", url))
        }
    }

    private fun publishHlsSubtitleTracks(
        playlistUrl: String,
        playlist: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val trackLines = Regex("""(?im)^#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*$""")
            .findAll(playlist)
        for (match in trackLines) {
            val line = match.value
            val uri = Regex("""(?i)\bURI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
            val url = fixUrl(uri, playlistUrl) ?: continue
            // CloudStream's external SubtitleFile accepts standalone text-subtitle files,
            // not an HLS rendition playlist itself.
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) continue
            val label = Regex("""(?i)\bNAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)\bLANGUAGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: "Altyazı"
            if (seen.add(url)) callback(newSubtitleFile(label, url))
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

        val seenSubtitles = HashSet<String>()
        publishSubtitles(page.document, page.text, page.url, seenSubtitles, subtitleCallback)
        val visited = HashSet<String>()
        val visitedScripts = HashSet<String>()
        val (pageDirect, pageEmbeds) = collectPlayerCandidates(page.document, page.text, page.url)
        val (scriptDirect, scriptEmbeds) =
                collectExternalScriptCandidates(
                page.document, page.url, page.url, visitedScripts, seenSubtitles, subtitleCallback
                )
        val direct = LinkedHashSet<String>().apply {
            addAll(pageDirect)
            addAll(scriptDirect)
        }
        val embeds = LinkedHashSet<String>().apply {
            addAll(pageEmbeds)
            addAll(scriptEmbeds)
        }
        var found = false
        // FilmizleHell sources are selected client-side; inspect the source metadata on the movie page too.
        if (publishPlayturkaManifestCandidates(page.url, page.document, page.text, seenSubtitles, subtitleCallback, callback) > 0) {
            found = true
        }
        val allCandidates = LinkedHashSet<String>().apply {
            addAll(direct)
            addAll(embeds)
        }

        Log.d(name, "loadLinks: page=" + safeLogUrl(page.url) + ", direct=" + direct.size +
            ", embeds=" + embeds.size + ", candidates=" +
            allCandidates.take(20).joinToString(" | ") { safeLogUrl(it) })

        suspend fun resolve(candidate: String, referer: String, depth: Int) {
            if (depth > 2 || !visited.add(candidate)) return
            Log.d(name, "resolve[$depth]: " + safeLogUrl(candidate))
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
            }.onFailure {
                Log.d(name, "Extractor başarısız: " + safeLogUrl(candidate) + " (" + it.javaClass.simpleName + ")")
            }

            if (depth >= 2) return
            val nested = runCatching {
                app.get(
                    candidate,
                    headers = requestHeaders + ("Referer" to referer),
                    referer = referer,
                    allowRedirects = true,
                    timeout = 12000
                )
            }.onFailure {
                Log.d(name, "Embed isteği başarısız: " + safeLogUrl(candidate) + " (" + it.javaClass.simpleName + ")")
            }.getOrNull() ?: return
            Log.d(name, "embed response: " + safeLogUrl(nested.url) + " HTTP=" + nested.code +
                ", length=" + nested.text.length + ", type=" + nested.headers["content-type"].orEmpty())
            if (!nested.isSuccessful || nested.text.isBlank() || nested.text.length > 4_000_000) return
            publishSubtitles(nested.document, nested.text, nested.url, seenSubtitles, subtitleCallback)
            if (runCatching { URI(nested.url).host.orEmpty().equals("p.playturka.space", true) }
                    .getOrDefault(false)
            ) {
                val playturkaFound = publishPlayturkaManifestCandidates(
                    nested.url, nested.document, nested.text, seenSubtitles, subtitleCallback, callback
                )
                if (playturkaFound > 0) found = true
            }
            val (nextDirect, nextEmbeds) = collectPlayerCandidates(nested.document, nested.text, nested.url)
            val (jsDirect, jsEmbeds) =
                collectExternalScriptCandidates(
                    nested.document, nested.url, nested.url, visitedScripts, seenSubtitles, subtitleCallback
                )
            val nestedDirect = LinkedHashSet<String>().apply {
                addAll(nextDirect)
                addAll(jsDirect)
            }
            val nestedEmbeds = LinkedHashSet<String>().apply {
                addAll(nextEmbeds)
                addAll(jsEmbeds)
            }
            Log.d(name, "nested candidates: direct=" + nestedDirect.size + ", embeds=" +
                nestedEmbeds.size + ", page=" + safeLogUrl(nested.url))
            nestedDirect.forEach { url ->
                if (!visited.contains(url)) {
                    publishDirect(url, nested.url, callback)
                    found = true
                }
            }
            for (next in nestedEmbeds.take(12)) {
                resolve(next, nested.url, depth + 1)
            }
        }

        for (candidate in allCandidates.take(20)) resolve(candidate, page.url, 0)
        Log.d(name, "loadLinks: found=" + found + ", visited=" + visited.size + ", candidates=" + allCandidates.size)
        return found
    }
}
