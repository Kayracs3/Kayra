package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziSol : MainAPI() {
    override var mainUrl = "https://dizisol.com"
    override var name = "DiziSol"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/netflix-dizileri" to "Netflix"
    )

    private val seasonEpisodeRegex = Regex("""(?i)(\d+)-sezon-(\d+)-bolum""")
    private val yearRegex = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")

    // ---------------------------------------------------------------------
    // URL / metin yardımcıları
    // ---------------------------------------------------------------------

    private fun decode(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    private fun isFilmUrl(url: String): Boolean =
        Regex("""(?i)^/film/[^/]+/?$""").matches(pathOf(url))

    private fun isSeriesUrl(url: String): Boolean =
        pathOf(url).startsWith("/dizi/", true)

    private fun isEpisodeUrl(url: String): Boolean =
        seasonEpisodeRegex.containsMatchIn(pathOf(url))

    private fun seriesUrlOf(rawUrl: String): String {
        val fixed = fixUrl(rawUrl) ?: return rawUrl
        if (!isSeriesUrl(fixed)) return fixed
        val parts = pathOf(fixed).trim('/').split('/')
        if (parts.size >= 3 && seasonEpisodeRegex.containsMatchIn(parts.last())) {
            return mainUrl.trimEnd('/') + "/" + parts.take(2).joinToString("/")
        }
        return mainUrl.trimEnd('/') + "/" + pathOf(fixed).trim('/')
    }

    private fun seasonEpisode(url: String): Pair<Int, Int>? {
        val match = seasonEpisodeRegex.find(pathOf(url)) ?: return null
        val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (season < 1 || episode < 1) return null
        return season to episode
    }

    private fun cleanTitle(raw: String?): String =
        raw.orEmpty()
            .let(::decode)
            .replace(Regex("""(?i)\s*[\-|–]\s*DİZİSOL.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*DIZISOL.*$"""), "")
            .replace(Regex("""(?i)\s+izle\b.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\.?\s*sezon\s+\d+\.?\s*bölüm.*$"""), "")
            .replace(Regex("""(?i)\s+\(\s*(?:film|dizi)\s*\)\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '–', ':')

    private fun titleFromSlug(url: String): String {
        val parts = pathOf(url).trim('/').split('/')
        val slug = if (parts.size >= 2) parts[1] else parts.lastOrNull().orEmpty()
        return cleanTitle(
            slug.replace(Regex("""-[a-z0-9]{3,5}$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""[-_]+"""), " ")
                .replace(Regex("""\b[a-z]""")) { it.value.uppercase() }
        )
    }

    private suspend fun document(url: String): Document? {
        return runCatching {
            app.get(
                url,
                headers = requestHeaders + mapOf("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                allowRedirects = true,
                timeout = 18000
            )
        }.onFailure { Log.w(name, "Sayfa isteği başarısız: $url") }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document
    }

    private fun imageUrl(image: Element?, baseUrl: String = mainUrl): String? {
        if (image == null) return null
        val keys = listOf(
            "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-src-original", "data-lazy", "data-image", "data-poster",
            "data-thumb", "data-thumbnail", "data-url", "data-echo",
            "data-srcset", "data-lazy-srcset", "srcset", "src", "poster", "content"
        )
        for (key in keys) {
            val raw = image.attr(key).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (key.contains("srcset", true)) {
                raw.split(",").maxByOrNull { part ->
                    Regex("""(\d+)(?:w|x)""").find(part)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }?.trim()?.substringBefore(" ").orEmpty()
            } else raw
            val resolved = fixUrl(candidate, baseUrl) ?: continue
            if (isRejectedPoster(resolved)) continue
            return resolved
        }
        return backgroundImageUrl(image, baseUrl)
    }

    private fun isRejectedPoster(url: String): Boolean =
        Regex("""(?i)\.(?:svg|gif|ico)(?:[?#]|$)""").containsMatchIn(url) ||
            listOf("logo", "avatar", "placeholder", "blank.", "no-image", "no_image").any {
                url.contains(it, true)
            }

    private fun backgroundImageUrl(element: Element?, baseUrl: String = mainUrl): String? {
        if (element == null) return null
        val dataKeys = listOf(
            "data-bg", "data-background", "data-background-image", "data-bg-image",
            "data-lazy-bg", "data-thumb", "data-thumbnail", "data-poster"
        )
        for (key in dataKeys) {
            val raw = element.attr(key).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val resolved = fixUrl(raw, baseUrl) ?: continue
            if (!isRejectedPoster(resolved)) return resolved
        }

        val rawStyleUrl = Regex("""(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)?.trim()
            ?: return null
        val resolved = fixUrl(rawStyleUrl, baseUrl) ?: return null
        return resolved.takeUnless(::isRejectedPoster)
    }

    /**
     * Afişi sadece eşleşen içerik kartından oku. Lazy-load ve CSS arka planlı görseller
     * da desteklenir; sayfanın rastgele ilk görseli hiçbir zaman karta atanmaz.
     */
    private fun posterFromCard(link: Element, baseUrl: String = mainUrl): String? {
        val imageSelector =
            "img, source[srcset], [style*=background], [data-bg], [data-background], " +
                "[data-background-image], [data-src], [data-lazy-src], [data-original], " +
                "[data-image], [data-url], [data-echo], [data-poster], [data-thumb], [data-thumbnail]"

        imageUrl(link.selectFirst(imageSelector), baseUrl)?.let { return it }
        backgroundImageUrl(link, baseUrl)?.let { return it }

        val card = link.closest(
            "article, .movie-card, .film-card, .series-card, .content-card, " +
                ".poster-card, .media-card, .item-card, .movie-item, .film-item, .dizi-item, " +
                ".episode-item, .post-item, .swiper-slide, .grid-item, .film-box, .dizi-box, " +
                ".thumb, .thumbnail, .post, .item, .card, li"
        )
        val cardImage = card?.selectFirst(imageSelector)
        imageUrl(cardImage, baseUrl)?.let { return it }
        backgroundImageUrl(cardImage, baseUrl)?.let { return it }
        return backgroundImageUrl(card, baseUrl)
    }

    private fun pagePoster(doc: Document): String? {
        val og = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.trim()
        val image = fixUrl(og, doc.location())
        if (image != null && !image.contains("logo", true) && !image.contains("placeholder", true)) {
            return image
        }

        // Detay gövdesi ile sınırlı kal; yan taraftaki öneri kartlarının afişini kullanma.
        val mainImage = doc.selectFirst(
            "main article img, main .detail img, main .movie-detail img, main .series-detail img, " +
                "article .poster img, article img[itemprop=image], .detail-poster img"
        )
        return imageUrl(mainImage, doc.location()) ?: backgroundImageUrl(mainImage, doc.location())
    }

    private fun cardTitle(link: Element, url: String): String {
        val card = link.closest(
            "article, .movie-card, .film-card, .series-card, .content-card, " +
                ".poster-card, .media-card, .item-card, .movie-item, .film-item, .dizi-item, " +
                ".film-box, .dizi-box, .card, li"
        )
        val candidate = link.attr("title").ifBlank {
            card?.selectFirst("h1, h2, h3, h4, .title, .name, [itemprop=name]")?.text().orEmpty()
        }.ifBlank {
            link.selectFirst("img")?.let { it.attr("alt").ifBlank { it.attr("title") } }.orEmpty()
        }.ifBlank { link.text() }
        return cleanTitle(candidate).ifBlank { titleFromSlug(url) }
    }

    private fun canonicalResultUrl(url: String): String =
        if (isSeriesUrl(url)) seriesUrlOf(url) else url

    private fun parseCards(doc: Document, pageUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in doc.select("a[href]")) {
            val href = fixUrl(link.attr("href"), pageUrl) ?: continue
            if (!isFilmUrl(href) && !isSeriesUrl(href)) continue
            val canonical = canonicalResultUrl(href)
            val title = cardTitle(link, href)
            if (title.isBlank() || title.length > 150 || title.equals("izle", true)) continue
            if (title.lowercase() in setOf("film izle", "dizi izle", "detaylar", "hemen izle")) continue

            val poster = posterFromCard(link, pageUrl)
            val nearbyText = link.parent()?.text().orEmpty()
            val cardYear = yearRegex.find(nearbyText)?.value?.toIntOrNull()
            val response: SearchResponse = if (isSeriesUrl(href)) {
                newTvSeriesSearchResponse(title, canonical, TvType.TvSeries) {
                    posterUrl = poster
                    year = cardYear
                }
            } else {
                newMovieSearchResponse(title, canonical, TvType.Movie) {
                    posterUrl = poster
                    year = cardYear
                }
            }
            found.putIfAbsent(canonical, response)
        }
        return found.values.toList()
    }

    // ---------------------------------------------------------------------
    // Ana sayfa ve arama
    // ---------------------------------------------------------------------

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page"
        else base.trimEnd('/') + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        var doc = document(url)
        if ((doc == null || parseCards(doc, url).isEmpty()) && page > 1) {
            val alternate = if (url.contains("?")) url else request.data.trimEnd('/') + "?page=" + page
            doc = document(alternate) ?: doc
        }
        if (doc == null) return newHomePageResponse(request.name, emptyList(), false)
        val items = parseCards(doc, url)
        val more = doc.select("a[href]").any { link ->
            val label = link.text().trim().lowercase()
            label.contains("sonraki") || label == "next" ||
                (page + 1).toString() == link.text().trim() ||
                link.attr("href").contains("/page/" + (page + 1) + "/")
        }
        Log.d(name, "Ana sayfa bölümüne ait içerikler ayrıştırıldı.")
        return newHomePageResponse(request.name, items, items.isNotEmpty() && more)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val candidates = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/arama/?s=$encoded",
            "$mainUrl/arama/$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/ara?q=$encoded"
        ).distinct()

        for (url in candidates) {
            val doc = document(url) ?: continue
            val results = parseCards(doc, url)
            Log.d(name, "Arama isteği denendi: $url")
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    // ---------------------------------------------------------------------
    // Detay ve metadata
    // ---------------------------------------------------------------------

    private data class Meta(
        val year: Int?,
        val score: Double?,
        val genres: List<String>,
        val actors: List<String>,
        val trailer: String?,
        val plot: String?
    )

    private fun jsonLdObjects(doc: Document): List<JSONObject> {
        val objects = ArrayList<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    objects += value
                    walk(value.opt("@graph"))
                    walk(value.opt("itemListElement"))
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching { walk(JSONTokener(script.data().trim()).nextValue()) }
        }
        return objects
    }

    private fun jsonNames(value: Any?): List<String> = when (value) {
        is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
        is JSONObject -> listOfNotNull(value.optString("name").trim().takeIf { it.isNotBlank() })
        is JSONArray -> (0 until value.length()).flatMap { jsonNames(value.opt(it)) }
        else -> emptyList()
    }

    private fun meta(doc: Document, pageUrl: String): Meta {
        val nodes = jsonLdObjects(doc)
        val relevant = nodes.firstOrNull { node ->
            val type = node.opt("@type")?.toString().orEmpty()
            type.contains("Movie", true) || type.contains("TVSeries", true) ||
                type.contains("CreativeWork", true) || type.contains("Episode", true)
        }
        val date = relevant?.optString("datePublished").orEmpty()
            .ifBlank { relevant?.optString("dateCreated").orEmpty() }
        val year = yearRegex.find(date)?.value?.toIntOrNull()
            ?: yearRegex.find(doc.selectFirst("h1")?.text().orEmpty())?.value?.toIntOrNull()
            ?: yearRegex.find(doc.selectFirst("meta[name=description]")?.attr("content").orEmpty())?.value?.toIntOrNull()

        val ratingObj = relevant?.optJSONObject("aggregateRating")
            ?: nodes.firstNotNullOfOrNull { it.optJSONObject("aggregateRating") }
        val ratingRaw = ratingObj?.opt("ratingValue")?.toString().orEmpty()
        val score = ratingRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            ?: doc.selectFirst("[itemprop=ratingValue], meta[itemprop=ratingValue], .imdb-rating, .imdb-puan")
                ?.let { el ->
                    el.attr("content").ifBlank { el.attr("data-rating") }
                        .ifBlank { el.attr("title") }.ifBlank { el.text() }
                }?.let { raw ->
                    Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                        .find(raw)?.value?.replace(',', '.')?.toDoubleOrNull()
                }?.takeIf { it in 0.0..10.0 }

        val genres = LinkedHashSet<String>()
        nodes.forEach { node -> genres.addAll(jsonNames(node.opt("genre"))) }
        doc.select(
            "main .genres a, main .genre a, main [class*=category] a, " +
                "article .genres a, article .genre a, .movie-detail .tag, .series-detail .tag, " +
                "[itemprop=genre]"
        ).forEach { el ->
            val text = el.attr("content").ifBlank { el.text() }.trim()
            if (text.length in 2..40 && !text.contains("yetişkin", true) &&
                !text.equals("film", true) && !text.equals("dizi", true)
            ) genres += text
        }

        val actors = LinkedHashSet<String>()
        nodes.forEach { node ->
            actors.addAll(jsonNames(node.opt("actor")))
            actors.addAll(jsonNames(node.opt("actors")))
        }
        doc.select(
            "main [itemprop=actor] [itemprop=name], main .cast a, main .actors a, " +
                "article .cast a, article .actors a, .movie-detail .cast a, .series-detail .cast a"
        ).forEach { el ->
            val value = el.attr("content").ifBlank { el.attr("title") }.ifBlank { el.text() }.trim()
            if (value.length in 2..70 && value.split(' ').size <= 6) actors += value
        }

        val trailer = doc.select("iframe[src], meta[property=og:video], a[href]").firstOrNull { el ->
            val value = el.attr("src").ifBlank { el.attr("content") }.ifBlank { el.attr("href") }
            value.contains("youtube.com/embed", true) || value.contains("youtu.be/", true) ||
                value.contains("youtube.com/watch", true)
        }?.let { el ->
            val raw = el.attr("src").ifBlank { el.attr("content") }.ifBlank { el.attr("href") }
            fixUrl(raw, pageUrl)
        }

        val plot = doc.selectFirst(
            "meta[property=og:description], meta[name=description], main [itemprop=description], " +
                "main .overview, main .plot, main .description, article .description"
        )?.let { el ->
            el.attr("content").ifBlank { el.text() }.trim()
        }?.takeIf { it.length > 20 }

        return Meta(year, score, genres.toList(), actors.toList(), trailer, plot)
    }

    private fun pageTitle(doc: Document, url: String): String {
        val raw = doc.selectFirst("h1")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { doc.title() }
        val clean = cleanTitle(raw)
        return clean.ifBlank { titleFromSlug(url) }
    }

    private fun parseEpisodes(doc: Document, seriesUrl: String, fallbackPoster: String?): List<Episode> {
        val canonical = seriesUrlOf(seriesUrl).trimEnd('/')
        val found = LinkedHashMap<String, Episode>()
        for (link in doc.select("a[href]")) {
            val href = fixUrl(link.attr("href"), doc.location()) ?: continue
            if (!isSeriesUrl(href)) continue
            if (seriesUrlOf(href).trimEnd('/') != canonical) continue
            val coordinates = seasonEpisode(href) ?: continue
            val (season, episodeNumber) = coordinates
            val text = link.text().trim()
            val name = if (Regex("""(?i)sezon.*bölüm""").containsMatchIn(text)) {
                cleanTitle(text).ifBlank { "$season. Sezon $episodeNumber. Bölüm" }
            } else {
                "$season. Sezon $episodeNumber. Bölüm"
            }
            found.putIfAbsent(
                href,
                newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episodeNumber
                    // Bölüm afişi yalnızca aynı bölüm kartında açıkça bulunursa kullanılır.
                    posterUrl = posterFromCard(link, doc.location()) ?: fallbackPoster
                }
            )
        }
        val current = fixUrl(seriesUrl) ?: seriesUrl
        val coords = seasonEpisode(current)
        if (found.isEmpty() && coords != null) {
            val (season, episodeNumber) = coords
            found[current] = newEpisode(current) {
                name = "$season. Sezon $episodeNumber. Bölüm"
                this.season = season
                this.episode = episodeNumber
                posterUrl = fallbackPoster
            }
        }
        return found.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = fixUrl(url) ?: return null
        val doc = document(normalized) ?: return null
        if (!isFilmUrl(normalized) && !isSeriesUrl(normalized)) {
            Log.w(name, "Desteklenmeyen detay yolu: $normalized")
            return null
        }

        val title = pageTitle(doc, normalized)
        val poster = pagePoster(doc)
        val meta = meta(doc, normalized)

        return if (isFilmUrl(normalized)) {
            newMovieLoadResponse(
                name = title,
                url = normalized,
                type = TvType.Movie,
                dataUrl = normalized
            ) {
                posterUrl = poster
                plot = meta.plot
                year = meta.year
                meta.score?.let { score = Score.from10(it) }
                tags = meta.genres
                actors = meta.actors.map { ActorData(Actor(it)) }
                addTrailer(meta.trailer)
            }
        } else {
            val canonicalSeries = seriesUrlOf(normalized)
            val episodes = parseEpisodes(doc, canonicalSeries, poster)
            newTvSeriesLoadResponse(
                title,
                canonicalSeries,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = meta.plot
                year = meta.year
                meta.score?.let { score = Score.from10(it) }
                tags = meta.genres
                actors = meta.actors.map { ActorData(Actor(it)) }
                addTrailer(meta.trailer)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Video / altyazı çözümleme
    // ---------------------------------------------------------------------

    private fun directMediaUrls(raw: String): List<String> {
        val text = decode(raw)
            .replace("\\u002e", ".", ignoreCase = true)
            .replace("\\x2e", ".", ignoreCase = true)
        val found = LinkedHashSet<String>()
        Regex(
            """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { found += it.value.trimEnd(')', ']', '}', ';', ',') }
        Regex(
            """(?<![:\w])//[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { found += "https:" + it.value.trimEnd(')', ']', '}', ';', ',') }
        return found.filterNot {
            it.contains("thumbnail", true) || it.contains("preview", true) ||
                it.contains("/sample", true)
        }
    }

    private fun playerCandidates(doc: Document, pageUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val selectors = listOf(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url]",
            "video[src], video source[src], source[src]",
            "[data-embed], [data-player], [data-video], [data-url], [data-src], [data-href]"
        )
        doc.select(selectors.joinToString(",")).forEach { el ->
            val value = listOf("src", "data-src", "data-lazy-src", "data-url",
                "data-embed", "data-player", "data-video", "data-href")
                .asSequence().map { el.attr(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            val candidate = fixUrl(decode(value), pageUrl)
            if (candidate != null && candidate != pageUrl && !isStaticAsset(candidate)) found += candidate
        }

        // Oynatıcı sekmeleri bazen iframe yerine sunucu düğmesi kullanıyor.
        doc.select("a[href]").forEach { link ->
            val href = fixUrl(link.attr("href"), pageUrl) ?: return@forEach
            val context = (link.text() + " " + link.className() + " " +
                link.id() + " " + link.parent()?.className().orEmpty()).lowercase()
            if (isMediaUrl(href) || ((context.contains("server") || context.contains("player") ||
                context.contains("kaynak") || context.contains("izle") || context.contains("embed")) &&
                (!isSameSite(href) || pathOf(href).contains("/embed", true) ||
                    pathOf(href).contains("/player", true)))
            ) {
                if (href != pageUrl && !isStaticAsset(href)) found += href
            }
        }
        return found.toList()
    }

    private fun isSameSite(url: String): Boolean = runCatching {
        URI(url).host.equals(URI(mainUrl).host, ignoreCase = true)
    }.getOrDefault(false)

    private fun isStaticAsset(url: String): Boolean =
        Regex("""(?i)\.(?:jpe?g|png|gif|webp|svg|ico|css|js|woff2?|ttf)(?:$|[?#])""")
            .containsMatchIn(url)

    private fun isMediaUrl(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4|mpd|webm)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("/hls/", true)

    private fun quality(url: String): Int = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1440", true) -> Qualities.P1440.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun host(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "DiziSol"

    private suspend fun emitMedia(
        url: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) || url.contains("/hls/", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to sourcePage,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        callback(
            newExtractorLink(
                source = name,
                name = host(url),
                url = url,
                type = type
            ) {
                referer = sourcePage
                headers = mediaHeaders
                quality = quality(url)
            }
        )
    }

    private suspend fun collectSubtitles(doc: Document, pageUrl: String, callback: (SubtitleFile) -> Unit) {
        val seen = HashSet<String>()
        doc.select("track[src], track[data-src], track[data-url], a[href$='.vtt'], a[href$='.srt']")
            .forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("data-url") }.ifBlank { el.attr("href") }
                val subtitleUrl = fixUrl(raw, pageUrl) ?: return@forEach
                if (!seen.add(subtitleUrl)) return@forEach
                val label = el.attr("label").ifBlank { el.attr("srclang") }
                    .ifBlank { el.attr("data-lang") }.ifBlank { el.text() }
                    .ifBlank { "Altyazı" }
                callback(newSubtitleFile(label, subtitleUrl))
            }

        Regex(
            """(?i)["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["']"""
        ).findAll(doc.html()).forEach { match ->
            val subtitleUrl = fixUrl(decode(match.groupValues[1]), pageUrl) ?: return@forEach
            if (seen.add(subtitleUrl)) callback(newSubtitleFile("Altyazı", subtitleUrl))
        }
    }

    private suspend fun resolveWebView(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pattern = Regex("""(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)""")
        val resolver = runCatching {
            WebViewResolver(
                interceptUrl = pattern,
                additionalUrls = listOf(pattern),
                useOkhttp = false,
                timeout = 22000L
            )
        }.getOrNull() ?: return false
        val response = runCatching {
            app.get(
                playerUrl,
                headers = requestHeaders + mapOf("Referer" to referer),
                referer = referer,
                interceptor = resolver,
                timeout = 25000
            )
        }.getOrNull() ?: return false
        val mediaUrl = response.url.orEmpty()
        if (!isMediaUrl(mediaUrl)) return false
        emitMedia(mediaUrl, playerUrl, callback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data) ?: return false
        val first = document(pageUrl) ?: return false
        val visitedPages = HashSet<String>()
        val seenMedia = HashSet<String>()
        var linkCount = 0
        var webViewCount = 0
        val countedCallback: (ExtractorLink) -> Unit = {
            callback(it)
            linkCount++
        }

        suspend fun inspect(currentDoc: Document, currentUrl: String, depth: Int) {
            if (!visitedPages.add(currentUrl)) return
            collectSubtitles(currentDoc, currentUrl, subtitleCallback)

            val scriptsAndHtml = buildString {
                append(currentDoc.html()).append('\n')
                currentDoc.select("script").forEach { append(it.data()).append('\n') }
            }
            for (media in directMediaUrls(scriptsAndHtml)) {
                if (seenMedia.add(media)) emitMedia(media, currentUrl, countedCallback)
            }

            for (candidate in playerCandidates(currentDoc, currentUrl).take(12)) {
                if (candidate in visitedPages) continue
                if (isMediaUrl(candidate)) {
                    if (seenMedia.add(candidate)) emitMedia(candidate, currentUrl, countedCallback)
                    continue
                }

                val before = linkCount
                runCatching { loadExtractor(candidate, currentUrl, subtitleCallback, countedCallback) }
                    .onFailure { Log.d(name, "Extractor çözemedi: $candidate") }

                // Embed sayfalarının içine yalnızca sınırlı derinlikte gir.
                if (depth < 2) {
                    val child = document(candidate)
                    if (child != null) inspect(child, candidate, depth + 1)
                }
                if (linkCount == before && webViewCount < 2 &&
                    (candidate.contains("player", true) || candidate.contains("embed", true) ||
                        candidate != pageUrl)
                ) {
                    webViewCount++
                    resolveWebView(candidate, currentUrl, countedCallback)
                }
            }
        }

        inspect(first, pageUrl, 0)
        Log.d(name, "Video çözümleme tamamlandı: $pageUrl")
        return linkCount > 0
    }
}

@CloudstreamPlugin
class DiziSolPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziSol())
    }
}
