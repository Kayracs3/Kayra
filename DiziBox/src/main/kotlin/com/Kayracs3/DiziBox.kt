package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DiziBox : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBox"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/page/1/?tip=populer" to "Popüler Dizilerden Son Bölümler",
        "$mainUrl/tum-bolumler/page/1/" to "Yeni Eklenen Bölümler",
        "$mainUrl/dizi-arsivi/page/1/" to "Dizi Arşivi",
        "$mainUrl/dizi-arsivi/page/1/?ulke[]=turkiye&yil=&imdb" to "Yerli Diziler",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=aile&yil&imdb" to "Aile",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=aksiyon&yil&imdb" to "Aksiyon",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=animasyon&yil&imdb" to "Animasyon",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=belgesel&yil&imdb" to "Belgesel",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=bilimkurgu&yil&imdb" to "Bilim Kurgu",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=biyografi&yil&imdb" to "Biyografi",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=dram&yil&imdb" to "Dram",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=fantastik&yil&imdb" to "Fantastik",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=gerilim&yil&imdb" to "Gerilim",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=gizem&yil&imdb" to "Gizem",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=komedi&yil&imdb" to "Komedi",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=korku&yil&imdb" to "Korku",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=macera&yil&imdb" to "Macera",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=reality-tv&yil&imdb" to "Reality TV",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=romantik&yil&imdb" to "Romantik",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=savas&yil&imdb" to "Savaş",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=suc&yil&imdb" to "Suç",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=tarih&yil&imdb" to "Tarih",
        "$mainUrl/dizi-arsivi/page/1/?tur[0]=western&yil&imdb" to "Western"
    )

    private fun normalizeUrl(raw: String?, base: String): String? {
        val value = raw.orEmpty().trim()
        if (value.isBlank() || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value == "#"
        ) return null
        return runCatching { URI(base).resolve(value).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isSiteUrl(url: String): Boolean = runCatching {
        URI(url).host.orEmpty().lowercase().removePrefix("www.") == "dizibox.live"
    }.getOrDefault(false)

    private fun cleanText(value: String?): String = value.orEmpty()
        .replace('\u00a0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun slugTitle(url: String): String {
        val slug = runCatching { URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/') }
            .getOrDefault("")
        return slug.replace('-', ' ').replace('_', ' ')
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        val candidates = listOf(
            element.attr("data-original"),
            element.attr("data-lazy-src"),
            element.attr("data-src"),
            element.attr("data-image"),
            element.attr("src"),
            element.attr("content"),
            element.attr("data-srcset").substringBefore(',').substringBefore(' '),
            element.attr("srcset").substringBefore(',').substringBefore(' ')
        )
        for (candidate in candidates) {
            val url = normalizeUrl(candidate, base) ?: continue
            if (Regex("""(?i)(logo|favicon|placeholder|no[-_]?image|blank|spacer|pixel|\.svg(?:$|\?))""")
                    .containsMatchIn(url)) continue
            return url
        }
        val background = Regex("""(?i)url\(\s*['"]?([^)'"]+)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)
        return normalizeUrl(background, base)
    }

    private fun cardFor(element: Element): Element {
        var current: Element? = element
        repeat(5) {
            val node = current ?: return element
            if (node.selectFirst("img, picture img") != null) return node
            current = node.parent()
        }
        return element
    }

    private fun scoreFrom(text: String): String? {
        val value = Regex("""(?i)(?:IMDb|IMDB)\s*[:.]?\s*([0-9](?:[.,][0-9])?|10(?:[.,]0)?)""")
            .find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""(?<![\d.])(?:10|[0-9])[.,][0-9](?!\d)""").find(text)?.value
        return value?.replace(',', '.')?.toFloatOrNull()
            ?.takeIf { it in 0f..10f }?.toString()
    }

    private fun isListingOrUtilityPath(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        val blocked = listOf(
            "/dizi-arsivi/page/", "/tum-bolumler/", "/tur/", "/yil/",
            "/oyuncu/", "/oyuncular/", "/page/", "/wp-content/", "/wp-admin/"
        )
        return blocked.any { path.contains(it) }
    }

    private fun Element.toSeriesResult(base: String = mainUrl): SearchResponse? {
        val card = if (tagName().equals("article", true)) this else cardFor(this)
        val anchor = card.selectFirst("h3 a[href], h2 a[href], a.archive-title[href], a[href]")
            ?: return null
        val url = normalizeUrl(anchor.attr("href"), base) ?: return null
        if (!isSiteUrl(url) || isListingOrUtilityPath(url)) return null

        val image = card.selectFirst("img") ?: anchor.selectFirst("img")
        val title = cleanText(
            card.selectFirst("h3 a, h2 a, .post-title a, .entry-title a")?.text()
                ?.ifBlank { null }
                ?: anchor.attr("title").ifBlank { null }
                ?: image?.attr("alt")?.ifBlank { null }
                ?: anchor.text()
        ).replace(Regex("""(?i)\s*[-|]\s*dizibox.*$"""), "").trim()
            .ifBlank { slugTitle(url) }
        if (title.length < 2 || title.equals("DiziBOX", true)) return null

        val poster = imageUrl(image, url)
        val rating = scoreFrom(
            card.selectFirst("span.label-imdb b")?.text().orEmpty().ifBlank { card.text() }
        )
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            posterUrl = poster
            rating?.let { score = Score.from10(it) }
        }
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        val response = runCatching {
            app.get(
                url,
                headers = requestHeaders,
                referer = referer,
                timeout = 20000,
                allowRedirects = true
            )
        }.onFailure {
            Log.e("DiziBox", "İstek hatası: " + url + " | " + it.javaClass.simpleName + ": " + it.message)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) {
            Log.e("DiziBox", "HTTP " + response.code + ": " + url)
            return null
        }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = request.data.replace(
            Regex("""/page/\d+/"""),
            "/page/" + page.coerceAtLeast(1) + "/"
        )
        val document = getDocument(pageUrl)
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = if (request.name.contains("Bölüm", true) ||
            request.name.contains("Popüler", true)
        ) {
            parseRecentEpisodes(document, pageUrl)
        } else {
            document.select("article.detailed-article").mapNotNull { it.toSeriesResult(pageUrl) }
                .distinctBy { it.url }
        }

        Log.d("DiziBox", request.name + ": " + results.size + " kayıt, sayfa=" + page)
        return newHomePageResponse(request.name, results, results.isNotEmpty() && page < 100)
    }

    private suspend fun parseRecentEpisodes(document: Document, baseUrl: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        document.select("article.article-episode-card").take(50).forEach { card ->
            val episodeAnchor = card.selectFirst("a[href]") ?: return@forEach
            val episodeUrl = normalizeUrl(episodeAnchor.attr("href"), baseUrl) ?: return@forEach
            if (!isSiteUrl(episodeUrl)) return@forEach

            val episodeDoc = getDocument(episodeUrl, baseUrl) ?: return@forEach
            val seriesAnchor = episodeDoc.selectFirst(
                "a.archive-title[href], .tv-overview h1 a[href], a[href*='/dizi-arsivi/']"
            ) ?: return@forEach
            val seriesUrl = normalizeUrl(seriesAnchor.attr("href"), episodeUrl) ?: return@forEach
            if (!isSiteUrl(seriesUrl) || isListingOrUtilityPath(seriesUrl)) return@forEach

            val title = cleanText(card.selectFirst("b.series-name")?.text())
                .ifBlank { cleanText(seriesAnchor.text()) }
                .ifBlank { slugTitle(seriesUrl) }
            val poster = imageUrl(
                episodeDoc.selectFirst("img.small-thumbnail") ?: episodeDoc.selectFirst(".tv-overview img"),
                episodeUrl
            )
            results.putIfAbsent(
                seriesUrl,
                newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    posterUrl = poster
                }
            )
        }
        return results.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        val url = "$mainUrl/?s=" + URLEncoder.encode(term, "UTF-8")
        val document = getDocument(url) ?: return emptyList()
        return document.select("article.detailed-article").mapNotNull { it.toSeriesResult(url) }
            .distinctBy { it.url }
            .take(60)
    }

    private fun extractActors(document: Document, baseUrl: String): List<ActorData> {
        val actors = LinkedHashMap<String, ActorData>()
        document.select("a[href*='/oyuncu/'], a[href*='/oyuncular/']").forEach { link ->
            val image = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.parent()?.parent()?.selectFirst("img")
            val actorName = cleanText(
                link.attr("title").ifBlank { link.text() }
                    .ifBlank { image?.attr("alt").orEmpty() }
            )
            if (actorName.length !in 2..80) return@forEach
            val photo = imageUrl(image, baseUrl)
            actors.putIfAbsent(actorName.lowercase(), ActorData(Actor(actorName, photo)))
        }
        if (actors.isEmpty()) {
            val heading = document.select("h2,h3,h4").firstOrNull {
                cleanText(it.text()).contains("oyuncu", true) ||
                    cleanText(it.text()).contains("cast", true)
            }
            var parent = heading?.parent()
            repeat(4) {
                val current = parent ?: return@repeat
                current.select("a[href] img, img").forEach { image ->
                    val actorName = cleanText(image.attr("alt").ifBlank { image.attr("title") })
                    if (actorName.length in 2..80) {
                        val photo = imageUrl(image, baseUrl)
                        actors.putIfAbsent(actorName.lowercase(), ActorData(Actor(actorName, photo)))
                    }
                }
                if (actors.isNotEmpty()) return@repeat
                parent = current.parent()
            }
        }
        return actors.values.take(60)
    }

    private fun extractGenres(document: Document): List<String> =
        document.select("a[href*='/tur/']").map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinctBy { it.lowercase() }
            .take(30)

    private fun extractRecommendations(document: Document, currentUrl: String): List<SearchResponse> {
        val current = currentUrl.trimEnd('/')
        val containers = LinkedHashSet<Element>()
        document.select(
            "section[class*=similar], div[class*=similar], section[class*=related], div[class*=related], section[class*=recommend], div[class*=recommend]"
        ).forEach { containers.add(it) }

        document.select("h2,h3,h4").filter {
            val title = cleanText(it.text())
            title.contains("benzer", true) || title.contains("önerilen", true) ||
                title.contains("ilgili", true) || title.contains("similar", true)
        }.forEach { heading ->
            var parent = heading.parent()
            repeat(4) {
                val item = parent ?: return@repeat
                val anchors = item.select("a[href]")
                if (anchors.isNotEmpty() && anchors.size <= 40) {
                    containers.add(item)
                    return@repeat
                }
                parent = item.parent()
            }
        }

        val results = LinkedHashMap<String, SearchResponse>()
        containers.forEach { container ->
            container.select("article.detailed-article, a[href]").forEach { item ->
                val result = item.toSeriesResult(currentUrl) ?: return@forEach
                if (result.url.trimEnd('/') != current) results.putIfAbsent(result.url, result)
            }
        }
        return results.values.take(30)
    }

    private fun getSeasonNumber(text: String, url: String, fallback: Int? = null): Int {
        val fromText = Regex("""(?i)(\d+)\s*\.?\s*sezon""").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fromUrl = Regex("""(?i)(?:sezon-|season[-/])(\d+)""").find(url)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return fromText ?: fromUrl ?: fallback ?: 1
    }

    private fun parseEpisodeNumber(text: String, url: String): Int? {
        return Regex("""(?i)(\d+)\s*\.?\s*bölüm""").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)s\d{1,2}\s*e(\d{1,3})""").find(text)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(?:-|/)(\d{1,3})(?:[-/]|$)""").find(url)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseEpisodes(
        document: Document,
        baseUrl: String,
        defaultSeason: Int?
    ): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()
        document.select("article.grid-box").forEach { card ->
            val anchor = card.selectFirst("div.post-title a[href], .post-title a[href], a[href]")
                ?: return@forEach
            val episodeUrl = normalizeUrl(anchor.attr("href"), baseUrl) ?: return@forEach
            if (!isSiteUrl(episodeUrl) || episodeUrl.trimEnd('/') == baseUrl.trimEnd('/')) return@forEach
            val label = cleanText(anchor.text().ifBlank { anchor.attr("title") })
            val season = getSeasonNumber(label, episodeUrl, defaultSeason)
            val episodeNumber = parseEpisodeNumber(label, episodeUrl) ?: return@forEach
            val episodeName = label
                .replace(Regex("""(?i)\d+\s*\.?\s*sezon"""), "")
                .replace(Regex("""(?i)\d+\s*\.?\s*bölüm"""), "")
                .replace(Regex("""(?i)s\d{1,2}\s*e\d{1,3}"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', ':', '|', '•')
                .ifBlank { "$episodeNumber. Bölüm" }

            val poster = imageUrl(card.selectFirst("img"), episodeUrl)
            episodes.putIfAbsent(episodeUrl, newEpisode(episodeUrl) {
                name = episodeName
                this.season = season
                this.episode = episodeNumber
                posterUrl = poster
            })
        }
        return episodes.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = normalizeUrl(url, mainUrl) ?: return null
        if (!isSiteUrl(pageUrl)) return null
        val document = getDocument(pageUrl) ?: return null

        val title = cleanText(
            document.selectFirst("div.tv-overview h1 a, div.tv-overview h1, h1.entry-title, h1")?.text()
                .ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
        ).replace(Regex("""(?i)\s*[-|]\s*dizibox.*$"""), "")
            .ifBlank { slugTitle(pageUrl) }

        val poster = imageUrl(
            document.selectFirst("div.tv-overview figure img, div.tv-overview img, meta[property=og:image], meta[name=twitter:image]"),
            pageUrl
        )
        val description = cleanText(
            document.selectFirst("div.tv-story p, .tv-story, .tv-description, .entry-content p")?.text()
                .ifBlank { document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content").orEmpty() }
        ).ifBlank { null }

        val pageText = document.text()
        val year = document.selectFirst("a[href*='/yil/']")?.text()?.let {
            Regex("""\b(?:18|19|20|21)\d{2}\b""").find(it)?.value?.toIntOrNull()
        } ?: Regex("""\b(?:18|19|20|21)\d{2}\b""").find(pageText)?.value?.toIntOrNull()

        val ratingText = cleanText(document.selectFirst("span.label-imdb b")?.text())
            .ifBlank { cleanText(document.selectFirst("[class*=imdb]")?.text()) }
        val rating = scoreFrom(ratingText)
        val genres = extractGenres(document)
        val actors = extractActors(document, pageUrl)
        val recommendations = extractRecommendations(document, pageUrl)
        val seasonLinks = LinkedHashSet<String>()

        document.select("#seasons-list a[href], div#seasons-list a[href], a[href*='/sezon/']")
            .forEach { anchor ->
                val href = normalizeUrl(anchor.attr("href"), pageUrl) ?: return@forEach
                if (isSiteUrl(href)) seasonLinks.add(href)
            }

        val allEpisodes = LinkedHashMap<String, Episode>()
        parseEpisodes(document, pageUrl, null).forEach { allEpisodes.putIfAbsent(it.data, it) }

        seasonLinks.take(40).forEach { seasonUrl ->
            val seasonDoc = getDocument(seasonUrl, pageUrl) ?: return@forEach
            val defaultSeason = getSeasonNumber(
                seasonDoc.selectFirst("h1,h2,.season-title")?.text().orEmpty(),
                seasonUrl
            )
            parseEpisodes(seasonDoc, seasonUrl, defaultSeason).forEach {
                allEpisodes.putIfAbsent(it.data, it)
            }
        }

        val episodes = allEpisodes.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )

        Log.d(
            "DiziBox",
            "Detay: " + title + " | tür=" + genres.size + " | oyuncu=" + actors.size +
                " | benzer=" + recommendations.size + " | sezon=" + seasonLinks.size +
                " | bölüm=" + episodes.size + " | IMDb=" + (rating ?: "yok")
        )

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = genres
            this.actors = actors
            this.recommendations = recommendations
            rating?.let { score = Score.from10(it) }
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mpd")
    }

    private suspend fun handleNestedPlayerDocument(
        document: Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        document.select("#video-area track[src], video track[src]").forEach { track ->
            val subtitleUrl = normalizeUrl(track.attr("src"), pageUrl) ?: return@forEach
            val language = cleanText(track.attr("label")).ifBlank {
                cleanText(track.attr("srclang")).ifBlank { "Türkçe" }
            }
            runCatching { subtitleCallback(newSubtitleFile(lang = language, url = subtitleUrl)) }
        }

        val targets = document.select(
            "#video-area iframe[src], #video-area iframe[data-src], #video-area video source[src], #video-area video[src]"
        ).mapNotNull { element ->
            normalizeUrl(element.attr("src").ifBlank { element.attr("data-src") }, pageUrl)
        }.distinct()

        for (target in targets) {
            if (isMediaUrl(target)) {
                val type = when {
                    target.contains(".mpd", true) -> ExtractorLinkType.DASH
                    target.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    else -> ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink(name, "DiziBox", target, type) {
                    referer = pageUrl
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                        "Referer" to pageUrl
                    )
                })
                found = true
            } else if (!isSiteUrl(target)) {
                runCatching {
                    loadExtractor(target, pageUrl, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }.onFailure {
                    Log.w("DiziBox", "Harici oynatıcı çözülemedi: " + it.message)
                }
            }
        }
        return found
    }

    private suspend fun handlePlayerDocument(
        document: Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        document.select("#video-area track[src], video track[src]").forEach { track ->
            val subtitleUrl = normalizeUrl(track.attr("src"), pageUrl) ?: return@forEach
            val language = cleanText(track.attr("label"))
                .ifBlank { cleanText(track.attr("srclang")) }
                .ifBlank { "Türkçe" }
            runCatching { subtitleCallback(newSubtitleFile(lang = language, url = subtitleUrl)) }
        }

        val sources = LinkedHashSet<String>()
        document.select("#video-area video[src], #video-area video source[src], #video-area source[src]")
            .forEach { sources.add(it.attr("src")) }
        document.select("#video-area iframe[src], #video-area iframe[data-src]")
            .forEach { sources.add(it.attr("src").ifBlank { it.attr("data-src") }) }

        for (raw in sources) {
            val target = normalizeUrl(raw, pageUrl) ?: continue
            if (isMediaUrl(target)) {
                val type = when {
                    target.contains(".mpd", true) -> ExtractorLinkType.DASH
                    target.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    else -> ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink(name, "DiziBox", target, type) {
                    referer = pageUrl
                    headers = mapOf(
                        "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                        "Referer" to pageUrl
                    )
                    quality = Qualities.Unknown.value
                })
                found = true
            } else if (!isSiteUrl(target)) {
                runCatching {
                    loadExtractor(target, pageUrl, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }.onFailure {
                    Log.w("DiziBox", "Harici oynatıcı çözülemedi: " + it.message)
                }
            } else {
                val nested = getDocument(target, pageUrl) ?: continue
                if (handleNestedPlayerDocument(nested, target, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl) ?: return false
        if (!isSiteUrl(pageUrl)) return false

        Log.d("DiziBox", "loadLinks: " + pageUrl)
        val document = getDocument(pageUrl, "$mainUrl/") ?: return false
        var found = handlePlayerDocument(document, pageUrl, subtitleCallback, callback)

        // Alternate servers are read only from the site's own video toolbar.
        val alternatives = document.select("div.video-toolbar option[value]")
            .mapNotNull { normalizeUrl(it.attr("value"), pageUrl) }
            .filter { isSiteUrl(it) }
            .distinct()
            .take(8)

        for (alternative in alternatives) {
            val altDocument = getDocument(alternative, pageUrl) ?: continue
            if (handlePlayerDocument(altDocument, alternative, subtitleCallback, callback)) {
                found = true
            }
        }

        if (!found) {
            Log.e("DiziBox", "Sayfada doğrudan veya standart oynatıcı bağlantısı bulunamadı.")
        }
        return found
    }
}
