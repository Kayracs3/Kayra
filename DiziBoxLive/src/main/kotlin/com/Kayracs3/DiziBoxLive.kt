package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * DiziBOX Live provider rewritten around card-aware listing extraction.
 * The title link and the poster are not always in the same HTML element.
 */
class DiziBoxLive : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBOX Live"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/efsane-diziler/" to "Efsane Diziler",
        "$mainUrl/arsiv/" to "Arşiv",
    )

    private data class ListingItem(
        var title: String,
        val url: String,
        var poster: String? = null,
        var seriesUrl: String? = null,
    )

    private data class Meta(
        val year: Int? = null,
        val score: Double? = null,
        val genres: List<String> = emptyList(),
        val actors: List<String> = emptyList(),
        val trailer: String? = null,
    )

    // Main page and search -------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = requestDocument(url)
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val results = listingResponses(document)
        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/search/$encoded/",
        ).distinct()

        for (url in urls) {
            val document = requestDocument(url) ?: continue
            val results = listingResponses(document)
            if (results.isEmpty()) continue
            val filtered = results.filter { titleMatches(q, it.name) }
            if (filtered.isNotEmpty()) return filtered
            if (document.selectFirst("body")?.text()?.contains(q, true) == true) {
                return results
            }
        }
        return emptyList()
    }

    private fun titleMatches(query: String, title: String): Boolean {
        fun norm(s: String): String {
            val lower = s.lowercase(Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
        val q = norm(query)
        val t = norm(title)
        if (q.isBlank() || t.isBlank()) return false
        return t.contains(q) || q.split(' ').filter { it.isNotBlank() }.all { word ->
            t.split(' ').any { it == word || (word.length >= 4 && it.startsWith(word)) }
        }
    }

    private suspend fun listingResponses(document: Document): List<SearchResponse> {
        val items = parseListingItems(document)
        if (items.isEmpty()) return emptyList()

        // Fetch a real detail-page image only if the listing card had no poster.
        // This fallback is capped to keep home-page requests responsive.
        var probes = 0
        for (item in items) {
            if (!item.poster.isNullOrBlank() || probes >= 30) continue
            probes++
            val detailUrl = item.seriesUrl ?: item.url
            val detail = requestDocument(detailUrl)
            val poster = detail?.let { posterFromDocument(it) }
            if (!poster.isNullOrBlank()) item.poster = poster
        }

        return items.map { item ->
            newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                posterUrl = item.poster
            }
        }
    }

    private fun parseListingItems(document: Document): List<ListingItem> {
        val items = LinkedHashMap<String, ListingItem>()
        document.select("a[href]").forEach { anchor ->
            val url = absoluteUrl(anchor.attr("href"), document.location()) ?: return@forEach
            if (!isSameSite(url) || !isListingLink(url, anchor.text())) return@forEach
            val title = listingTitle(anchor)
            if (!validTitle(title)) return@forEach

            val seriesUrl = findSeriesUrl(anchor, url)
            val poster = posterFromCard(anchor, title)
            val old = items[url]
            if (old == null) {
                items[url] = ListingItem(title, url, poster, seriesUrl)
            } else {
                if (old.poster.isNullOrBlank() && !poster.isNullOrBlank()) old.poster = poster
                if (old.seriesUrl.isNullOrBlank() && !seriesUrl.isNullOrBlank()) {
                    old.seriesUrl = seriesUrl
                }
                if (old.title.length < title.length && title.length <= 100) old.title = title
            }
        }

        return items.values
            .sortedBy { if (isSeriesUrl(it.url)) 0 else 1 }
            .take(60)
    }

    private fun listingTitle(anchor: Element): String {
        val children = listOf(
            ".title", ".name", ".episode-title", ".post-title", "h2", "h3", "h4"
        )
        for (selector in children) {
            val text = anchor.selectFirst(selector)?.text().cleanText()
            if (validTitle(text)) return text
        }
        anchor.attr("title").cleanText().takeIf { validTitle(it) }?.let { return it }
        anchor.attr("aria-label").cleanText().takeIf { validTitle(it) }?.let { return it }
        anchor.text().cleanText().takeIf { validTitle(it) }?.let { return it }
        anchor.selectFirst("img[alt]")?.attr("alt").cleanText()
            .takeIf { validTitle(it) }?.let { return it }
        return anchor.parent()?.text().cleanText().takeIf { validTitle(it) }.orEmpty()
    }

    private fun String?.cleanText(): String = this.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun validTitle(title: String): Boolean {
        val value = title.cleanText()
        if (value.length !in 2..150) return false
        if (value.equals("input", true) || value.equals("fragmanı izle", true)) return false
        if (value.startsWith("javascript:", true)) return false
        return !Regex("(?i)^(giriş|kayıt ol|üye giriş|sonraki|önceki|tümünü seç)$")
            .matches(value)
    }

    private fun isListingLink(url: String, anchorText: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = decodePath(uri.path.orEmpty()).lowercase(Locale.ROOT)
        if (path.isBlank() || path == "/" || path.contains("/wp-") ||
            path.contains("/etiket/") || path.contains("/tag/") ||
            path.contains("/kategori/") || path.contains("/category/") ||
            path.contains("/ulke/") || path.contains("/tur/") ||
            path.contains("/yorum") || path.contains("/comment-page") ||
            path.contains("/giris") || path.contains("/uye") ||
            (path.contains("/page/") && !path.contains("sezon"))
        ) return false
        return isSeriesUrl(url) || isEpisodeLink(path, anchorText)
    }

    private fun isSeriesUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return path.contains("/diziler/") || path.contains("/dizi/")
    }

    private fun isEpisodeLink(pathOrUrl: String, title: String): Boolean {
        val value = decodePath("$pathOrUrl $title").lowercase(Locale.ROOT).replace('ı', 'i')
        return Regex("""\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bolum""").containsMatchIn(value) ||
            Regex("""\d+[-_/]?sezon[-_/]?\d+[-_/]?bolum""").containsMatchIn(value) ||
            Regex("""s\d{1,2}e\d{1,3}""").containsMatchIn(value) ||
            Regex("""episode[-_ ]?\d+""").containsMatchIn(value) ||
            (value.contains("bolum") && value.contains("izle"))
    }

    private fun findSeriesUrl(anchor: Element, currentUrl: String): String? {
        if (isSeriesUrl(currentUrl)) return currentUrl
        var parent: Element? = anchor.parent()
        repeat(6) {
            val node = parent ?: return@repeat
            node.select("a[href]").forEach { candidate ->
                val href = absoluteUrl(candidate.attr("href"), currentUrl) ?: return@forEach
                if (isSameSite(href) && isSeriesUrl(href)) return href
            }
            parent = node.parent()
        }
        return null
    }

    // Poster extraction ----------------------------------------------------

    private val posterSelector =
        "img, picture source, source, [data-poster], [data-thumbnail], [data-thumb], " +
        "[data-image-url], [data-image], [data-img], [data-lazy-src], [data-original-src], " +
        "[data-original], [data-src], [data-lazyload], [data-lazy], [data-cfsrc], " +
        "[data-background], [data-bg], [data-srcset], [srcset], [style*=background]"

    private fun posterFromCard(anchor: Element, title: String): String? {
        posterFromElement(anchor)?.let { return it }
        var parent: Element? = anchor.parent()
        repeat(8) {
            val node = parent ?: return@repeat
            val classNames = (node.className() + " " + node.id()).lowercase(Locale.ROOT)
            val isCard = node.tagName() == "article" ||
                Regex("""(card|item|episode|series|dizi|show|poster|thumb|entry|post|box|owl-item|swiper-slide|archive)""")
                    .containsMatchIn(classNames)
            if (isCard || it < 2) {
                node.select(posterSelector).forEach { image ->
                    posterFromElement(image)?.let { poster ->
                        if (looksLikePoster(poster, title)) return poster
                    }
                }
                posterFromElement(node)?.let { poster ->
                    if (looksLikePoster(poster, title)) return poster
                }
            }
            parent = node.parent()
        }
        return null
    }

    private fun posterFromElement(element: Element): String? {
        val attributes = listOf(
            "data-poster", "data-thumbnail", "data-thumb", "data-image-url", "data-image",
            "data-img", "data-lazy-src", "data-original-src", "data-original", "data-src",
            "data-lazyload", "data-lazy", "data-cfsrc", "data-background", "data-bg",
            "data-srcset", "srcset", "src"
        )
        for (attribute in attributes) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) continue
            val value = if (attribute.contains("srcset")) {
                raw.split(",").firstOrNull().orEmpty().trim().substringBefore(" ")
            } else raw
            normalizeImage(value)?.let { if (looksLikePoster(it, "")) return it }
        }

        val background = Regex(
            """(?i)background(?:-image)?\s*:[^;]*?url\(\s*['"]?([^)'""]+)"""
        ).find(element.attr("style"))?.groupValues?.getOrNull(1)
        normalizeImage(background)?.let { if (looksLikePoster(it, "")) return it }
        return null
    }

    private fun looksLikePoster(url: String, title: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        if (!value.startsWith("http") || value.contains("favicon") ||
            value.contains("logo") || value.contains("avatar") ||
            value.contains("placeholder") || value.contains("blank.gif") ||
            value.contains("spacer.gif") || value.contains("gravatar") ||
            value.endsWith(".svg")
        ) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val hasImagePath = Regex("""(?i)\.(?:jpe?g|png|webp|gif|avif)$""").containsMatchIn(path) ||
            value.contains("image") || value.contains("poster") || value.contains("afis")
        return hasImagePath || title.isNotBlank()
    }

    private fun normalizeImage(raw: String?): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.replace("\\/", "/").orEmpty()
        if (value.isBlank() || value.startsWith("data:", true) ||
            value.startsWith("javascript:", true) || value.startsWith("about:", true)
        ) return null
        return absoluteUrl(value, mainUrl)?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
    }

    private fun posterFromDocument(document: Document): String? {
        val metaSelectors = listOf(
            "meta[property=og:image]",
            "meta[property=og:image:secure_url]",
            "meta[name=twitter:image]",
            "meta[name=twitter:image:src]",
        )
        for (selector in metaSelectors) {
            normalizeImage(document.selectFirst(selector)?.attr("content"))
                ?.let { if (looksLikePoster(it, "")) return it }
        }

        val jsonImage = jsonLd(document).asSequence()
            .mapNotNull { it.opt("image") }
            .mapNotNull { value ->
                when (value) {
                    is String -> value
                    is JSONObject -> value.optString("url").takeIf { it.isNotBlank() }
                    is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { index ->
                        value.opt(index) as? String
                    }
                    else -> null
                }
            }
            .mapNotNull { normalizeImage(it) }
            .firstOrNull { looksLikePoster(it, "") }
        if (!jsonImage.isNullOrBlank()) return jsonImage

        val selectors = listOf(
            "article img", ".post-thumbnail img", ".poster img", ".entry-thumb img",
            ".featured-image img", ".wp-post-image", ".entry-content img", "main img",
            "img[data-src]", "img[data-lazy-src]", "img[data-original]"
        )
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                posterFromElement(element)?.let { return it }
            }
        }

        // DiziBOX episode pages often put the show's poster in a separate
        // linked image next to the text "Dizi sayfasına gidin", outside article,
        // main and conventional WordPress image classes. Scan every image-like
        // element as the final fallback rather than silently returning null.
        document.select(posterSelector).forEach { element ->
            val candidate = posterFromElement(element) ?: return@forEach
            val label = listOf(
                element.attr("alt"),
                element.attr("title"),
                element.attr("aria-label"),
                element.parent()?.attr("href").orEmpty(),
                element.parent()?.text().orEmpty(),
            ).joinToString(" ").lowercase(Locale.ROOT)
            if (label.contains("dizi sayfasına gidin") ||
                label.contains("dizi sayfasina gidin") ||
                label.contains("dizi izle") ||
                label.contains("series")
            ) return candidate
        }

        // If the image has no useful label, still return the first genuine
        // image after logo/avatar/placeholder filtering in posterFromElement.
        document.select(posterSelector).forEach { element ->
            posterFromElement(element)?.let { return it }
        }
        return null
    }

    private fun jsonLd(document: Document): List<JSONObject> {
        val output = ArrayList<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    output += value
                    walk(value.opt("@graph"))
                    walk(value.opt("mainEntity"))
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
            }
        }
        document.select("script[type=application/ld+json]").forEach { script ->
            runCatching { walk(JSONTokener(script.data().trim()).nextValue()) }
        }
        return output
    }

    // Series and episode pages --------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val normalized = absoluteUrl(url, mainUrl) ?: return null
        if (!isSameSite(normalized)) return null
        val document = requestDocument(normalized) ?: return null

        val heading = document.selectFirst("h1")?.text().cleanText()
        val metaTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            .cleanText().substringBefore("|").trim()
        val title = heading.takeIf { validTitle(it) } ?: metaTitle.takeIf { validTitle(it) } ?: return null

        val poster = posterFromDocument(document)
        val parsedEpisodes = parseEpisodes(document, poster)
        val episodeList = if (parsedEpisodes.isNotEmpty()) parsedEpisodes else {
            listOf(newEpisode(normalized) {
                name = title
                season = seasonNumber(normalized, title)
                episode = episodeNumber(normalized, title)
                posterUrl = poster
            })
        }
        val meta = parseMeta(document)

        return newTvSeriesLoadResponse(title, normalized, TvType.TvSeries, episodeList) {
            posterUrl = poster
            plot = document.selectFirst("meta[name=description]")?.attr("content").cleanText()
                .takeIf { it.isNotBlank() }
                ?: document.selectFirst(".description, .summary, .entry-content p")?.text().cleanText()
            year = meta.year
            meta.score?.let { score = Score.from10(it) }
            if (meta.genres.isNotEmpty()) tags = meta.genres
            if (meta.actors.isNotEmpty()) actors = meta.actors.map { ActorData(Actor(it)) }
            addTrailer(meta.trailer)
        }
    }

    private fun parseEpisodes(document: Document, showPoster: String?): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val url = absoluteUrl(anchor.attr("href"), document.location()) ?: return@forEach
            if (!isSameSite(url)) return@forEach
            val text = anchor.text().cleanText()
            val numbers = episodeNumbers(text + " " + url) ?: return@forEach
            if (text.isBlank() || text.length > 180) return@forEach
            if (episodes.containsKey(url)) return@forEach

            episodes[url] = newEpisode(url) {
                name = text
                season = numbers.first
                episode = numbers.second
                posterUrl = showPoster ?: posterFromCard(anchor, text)
            }
        }
        return episodes.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    private fun episodeNumbers(raw: String): Pair<Int, Int>? {
        val value = decodePath(raw).lowercase(Locale.ROOT).replace('ı', 'i')
        val patterns = listOf(
            Regex("""(\d{1,2})\s*\.?\s*sezon\s*(\d{1,3})\s*\.?\s*bolum"""),
            Regex("""(\d{1,2})[-_/]?sezon[-_/]?(\d{1,3})[-_/]?bolum"""),
            Regex("""s(\d{1,2})[-_ ]?e(\d{1,3})"""),
            Regex("""season[-_ ]?(\d{1,2}).*?episode[-_ ]?(\d{1,3})"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(value) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            return season to episode
        }
        return null
    }

    private fun seasonNumber(url: String, title: String): Int? =
        episodeNumbers("$url $title")?.first

    private fun episodeNumber(url: String, title: String): Int? =
        episodeNumbers("$url $title")?.second

    private fun parseMeta(document: Document): Meta {
        val nodes = jsonLd(document)
        val primary = nodes.firstOrNull { node ->
            val type = node.opt("@type").toString()
            type.contains("TVSeries", true) || type.contains("Series", true) ||
                type.contains("CreativeWork", true)
        } ?: nodes.firstOrNull()

        val year = listOf("datePublished", "dateCreated", "startDate")
            .asSequence()
            .mapNotNull { key ->
                Regex("""(?:19|20)\d{2}""").find(primary?.optString(key).orEmpty())
                    ?.value?.toIntOrNull()
            }.firstOrNull()
            ?: Regex("""(?:19|20)\d{2}""").find(document.selectFirst("h1")?.text().orEmpty())
                ?.value?.toIntOrNull()

        val score = Regex("""(?i)imdb\s*[:：]?\s*(\d(?:[.,]\d)?)""")
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: primary?.optJSONObject("aggregateRating")?.optString("ratingValue")?.toDoubleOrNull()

        val genres = LinkedHashSet<String>()
        primary?.opt("genre")?.let { raw ->
            when (raw) {
                is String -> raw.split(",").forEach { if (it.isNotBlank()) genres += it.trim() }
                is JSONArray -> for (index in 0 until raw.length()) {
                    raw.optString(index).trim().takeIf { it.isNotBlank() }?.let(genres::add)
                }
            }
        }
        document.select("a[href*=/tur/], a[href*=/turler/], a[rel~=tag]").forEach { element ->
            element.text().cleanText().takeIf { it.length in 2..40 }?.let(genres::add)
        }

        val actors = LinkedHashSet<String>()
        primary?.opt("actor")?.let { raw ->
            when (raw) {
                is String -> raw.split(",").forEach { if (it.isNotBlank()) actors += it.trim() }
                is JSONObject -> raw.optString("name").takeIf { it.isNotBlank() }?.let(actors::add)
                is JSONArray -> for (index in 0 until raw.length()) {
                    val entry = raw.opt(index)
                    val actor = when (entry) {
                        is JSONObject -> entry.optString("name")
                        is String -> entry
                        else -> ""
                    }.trim()
                    if (actor.isNotBlank()) actors += actor
                }
            }
        }
        document.select("a[href*=/oyuncu/], a[href*=/oyuncular/], a[href*=/actor/], a[href*=/cast/]")
            .forEach { it.text().cleanText().takeIf { actor -> actor.length in 2..60 }?.let(actors::add) }

        val trailer = document.selectFirst(
            "iframe[src*=youtube], iframe[data-src*=youtube], a[href*=youtu.be], a[href*=youtube.com/watch]"
        )?.let { element ->
            youtubeUrl(element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") })
        } ?: nodes.asSequence().mapNotNull { it.optJSONObject("trailer") }
            .mapNotNull { trailerNode ->
                youtubeUrl(trailerNode.optString("embedUrl").ifBlank { trailerNode.optString("url") })
            }.firstOrNull()

        return Meta(
            year,
            score?.takeIf { it in 0.0..10.0 },
            genres.take(15),
            actors.take(25),
            trailer,
        )
    }

    private fun youtubeUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val id = Regex("""(?:v=|/embed/|youtu\.be/)([A-Za-z0-9_-]{11})""")
            .find(value)?.groupValues?.getOrNull(1)
        return id?.let { "https://www.youtube.com/watch?v=$it" }
            ?: value.takeIf { it.contains("youtube", true) }
    }

    // Video, iframe and subtitle resolution --------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = absoluteUrl(data, mainUrl) ?: return false
        val document = requestDocument(episodeUrl) ?: return false
        val emitted = HashSet<String>()
        val visited = HashSet<String>()
        var count = 0

        fun emit(link: ExtractorLink) {
            if (emitted.add(link.url)) {
                count++
                callback(link)
            }
        }

        suspend fun scanPage(doc: Document, pageUrl: String, depth: Int) {
            if (!visited.add(pageUrl)) return
            collectSubtitles(doc, pageUrl, subtitleCallback)

            val texts = ArrayList<String>()
            texts.add(doc.html())
            doc.select("script").forEach { script ->
                val scriptText = script.data().ifBlank { script.html() }
                texts.add(scriptText)
                if (scriptText.contains("eval(function(p,a,c,k")) {
                    runCatching { getAndUnpack(scriptText) }.getOrNull()?.let(texts::add)
                }
            }
            for (media in texts.flatMap { scanMediaUrls(it, pageUrl) }.distinct()) {
                emitDirect(media, pageUrl, ::emit)
            }

            for (candidate in playerCandidates(doc, pageUrl)) {
                if (candidate in visited) continue
                if (isMediaUrl(candidate)) {
                    emitDirect(candidate, pageUrl, ::emit)
                    continue
                }

                val oldCount = count
                runCatching { loadExtractor(candidate, pageUrl, subtitleCallback, ::emit) }
                if (count > oldCount || depth >= 2) continue

                val response = runCatching {
                    app.get(
                        candidate,
                        headers = headers + mapOf("Referer" to pageUrl),
                        referer = pageUrl,
                    )
                }.getOrNull() ?: continue
                if (response.isSuccessful) scanPage(response.document, candidate, depth + 1)
            }
        }

        scanPage(document, episodeUrl, 0)
        return count > 0
    }

    private fun playerCandidates(document: Document, pageUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val selectors = listOf(
            "iframe[src]", "iframe[data-src]", "iframe[data-lazy-src]",
            "video[src]", "video source[src]", "source[src]", "track[src]", "track[data-src]",
            "[data-embed]", "[data-player]", "[data-url]", "[data-href]", "[data-src]"
        )
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                val raw = listOf(
                    "src", "data-src", "data-lazy-src", "data-embed", "data-player",
                    "data-url", "data-href"
                ).firstNotNullOfOrNull { key -> element.attr(key).takeIf { it.isNotBlank() } }
                    ?: return@forEach
                val url = absoluteUrl(raw, pageUrl) ?: return@forEach
                if (isMediaUrl(url) || isPlayerUrl(url)) candidates += url
            }
        }
        document.select("a[href]").forEach { anchor ->
            val url = absoluteUrl(anchor.attr("href"), pageUrl) ?: return@forEach
            if (isMediaUrl(url) || isPlayerUrl(url)) candidates += url
        }
        return candidates.toList()
    }

    private fun scanMediaUrls(raw: String, baseUrl: String): List<String> {
        val text = raw.replace("\\/", "/").replace("\\u002F", "/", true).replace("&amp;", "&")
        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""//[^\s"'<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""/[^\s"'<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.findAll(text).forEach { match ->
                val candidate = match.value.trimEnd(')', ']', '}', ';', ',')
                absoluteUrl(candidate, baseUrl)?.let { if (isMediaUrl(it)) found += it }
            }
        }
        return found.toList()
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val value = url.lowercase(Locale.ROOT)
        val type = when {
            value.contains(".m3u8") || value.contains("/hls/") -> ExtractorLinkType.M3U8
            value.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )
        originOf(referer)?.let { mediaHeaders["Origin"] = it }
        callback(newExtractorLink(source = name, name = hostOf(url), url = url, type = type) {
            this.referer = referer
            this.headers = mediaHeaders
            quality = qualityFromUrl(url)
        })
    }

    private suspend fun collectSubtitles(
        document: Document,
        pageUrl: String,
        callback: (SubtitleFile) -> Unit,
    ) {
        val seen = HashSet<String>()
        for (element in document.select("track[src], track[data-src], track[data-url], [data-subtitle]")) {
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-url") }.ifBlank { element.attr("data-subtitle") }
            val url = absoluteUrl(raw, pageUrl) ?: continue
            if (!isSubtitleUrl(url) || !seen.add(url)) continue
            val label = element.attr("label").ifBlank {
                element.attr("srclang").ifBlank { "Türkçe" }
            }
            callback(newSubtitleFile(label, url))
        }
        val pattern = Regex(
            """https?://[^\s"'<>]+?\.(?:vtt|srt)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        for (script in document.select("script")) {
            val raw = script.data().ifBlank { script.html() }.replace("\\/", "/")
            for (match in pattern.findAll(raw)) {
                val url = absoluteUrl(match.value.trimEnd(')', ']', '}', ';', ','), pageUrl)
                    ?: continue
                if (seen.add(url)) callback(newSubtitleFile("Türkçe", url))
            }
        }
    }
    private fun isSubtitleUrl(url: String): Boolean =
        Regex("""(?i)\.(?:vtt|srt|ass|ssa)(?:$|[?#])""").containsMatchIn(url)

    private fun isMediaUrl(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mpd|mp4|webm)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("/hls/", true)

    private fun isPlayerUrl(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return value.contains("/embed") || value.contains("/player") ||
            value.contains("vidmoly") || value.contains("streamtape") ||
            value.contains("filemoon") || value.contains("dood") ||
            value.contains("ok.ru") || value.contains("odnoklassniki") ||
            value.contains("uqload") || value.contains("voe")
    }

    // HTTP and helpers ------------------------------------------------------

    private suspend fun requestDocument(url: String): Document? = runCatching {
        app.get(url, headers = headers, referer = "$mainUrl/").document
            .also { if (it.location().isBlank()) it.setBaseUri(url) }
    }.getOrNull()

    private fun absoluteUrl(raw: String?, base: String): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.replace("\\/", "/")
            ?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("javascript:", true) &&
                    !it.startsWith("data:", true) &&
                    !it.startsWith("mailto:", true) &&
                    !it.startsWith("#")
            } ?: return null
        return runCatching {
            val resolved = URI(base).resolve(value)
            if (resolved.scheme != "http" && resolved.scheme != "https") null
            else resolved.normalize().toString().substringBefore("#")
        }.getOrNull()
    }

    private fun isSameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        val site = runCatching { URI(mainUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return host.equals(site, ignoreCase = true)
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.trimEnd('/') == mainUrl.trimEnd('/')) {
            "$mainUrl/page/$page/"
        } else {
            base.trimEnd('/') + "/page/" + page + "/"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val next = page + 1
        return document.select("a[href]").any { anchor ->
            val url = absoluteUrl(anchor.attr("href"), document.location()).orEmpty()
            val text = anchor.text().lowercase(Locale.ROOT)
            text.contains("sonraki") || text.contains("next") || url.contains("/page/$next/")
        }
    }

    private fun decodePath(raw: String): String = runCatching {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(raw).replace('ı', 'i')

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
        else uri.scheme + "://" + uri.host
    }.getOrNull()

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: name

    private fun qualityFromUrl(url: String): Int {
        val value = url.lowercase(Locale.ROOT)
        return when {
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1440") -> Qualities.P1440.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
