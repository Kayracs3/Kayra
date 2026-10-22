package com.Kayracs3

import android.util.Log

import com.lagradost.cloudstream3.newEpisode

import com.lagradost.cloudstream3.newHomePageResponse

import com.lagradost.cloudstream3.utils.loadExtractor

import com.lagradost.cloudstream3.utils.ExtractorLink

import com.lagradost.cloudstream3.newMovieSearchResponse

import com.lagradost.cloudstream3.newTvSeriesLoadResponse

import com.lagradost.cloudstream3.newMovieLoadResponse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Flix : MainAPI() {
    override var mainUrl = "https://fixbettv84.com"
    override var name = "Flix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/" to "Ana Sayfa")

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'')
            ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("mailto:", true)
        ) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    private fun isContentUrl(url: String): Boolean {
        if (!sameSite(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") return false
        if (Regex("(?i)^/(?:wp-admin|wp-login|login|logout|register|privacy|privacy-policy|terms|contact|about|search|arama|category|kategori|tag|etiket|genre|tur)(?:/|$)").containsMatchIn(path)) return false
        return true
    }

    private fun posterFrom(element: Element, link: Element): String? {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        val raw = sequenceOf(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-original-src"),
            image?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) }
        return fixUrl(raw)
    }

    private fun titleFrom(element: Element, link: Element): String {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        return sequenceOf(
            element.selectFirst("h1, h2, h3, .title, .film-title, .movie-title")?.text(),
            link.attr("title"),
            image?.attr("alt"),
            link.text(),
            element.attr("title")
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length >= 2 }
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .substringBefore(" | ")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName().equals("a", true)) this else {
            select("a[href]").firstOrNull { anchor ->
                val resolved = fixUrl(anchor.attr("href"))
                resolved != null && isContentUrl(resolved)
            }
        } ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!isContentUrl(url)) return null

        val title = titleFrom(this, link)
        if (title.length < 2 || title.lowercase() in setOf(
                "ana sayfa", "home", "filmler", "diziler", "kategoriler", "giriş", "giris",
                "kayıt ol", "kayit ol", "iletişim", "iletisim", "hakkımızda", "hakkimizda"
            )
        ) return null

        val allText = "${title} ${url} ${text()}"
        val type = when {
            Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText) -> TvType.TvSeries
            Regex("(?i)(canlı|canli|live|yayın|yayin|channel\\.html|/kanal|/channel|/tv/)").containsMatchIn(allText) -> TvType.Live
            else -> TvType.Movie
        }
        val poster = posterFrom(this, link)
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()
        return newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun matchesQuery(query: String, title: String): Boolean {
        val normalizedTitle = title.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        val normalizedQuery = query.lowercase().trim()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        if (normalizedQuery.isBlank()) return true
        return normalizedTitle.contains(normalizedQuery) ||
            normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }.all { normalizedTitle.contains(it) }
    }

    private fun parseResults(document: Document, query: String = ""): List<SearchResponse> {
        val cards = document.select(
            "article, .movie-item, .film-item, .video-item, .series-item, .film-card, .movie-card, .post-item, .movie, .film, .series, .card, .thumbnail, .list-item"
        ).mapNotNull { it.toSearchResult() }
        val results = if (cards.isNotEmpty()) cards else {
            document.select("a[href]").mapNotNull { it.toSearchResult() }
        }
        return results.distinctBy { it.url }
            .filter { matchesQuery(query, it.name) }
            .take(100)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data.trimEnd('/') + "/page/$page/"
            else -> "$mainUrl/page/$page/"
        }
        val document = runCatching {
            app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseResults(document)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/arama/$encoded/",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?ara=$encoded"
        ).distinct()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            val results = parseResults(document, q)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeNumbers(value: String): Pair<Int, Int>? {
        val source = value.replace('_', ' ').replace('-', ' ')
        val first = Regex("(?i)(?:sezon|season|s)\\s*(\\d+)\\D{0,40}?(?:bölüm|bolum|episode|ep|e)\\s*(\\d+)")
            .find(source)
        if (first != null) {
            return first.groupValues[1].toIntOrNull()?.let { season ->
                first.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
            }
        }
        val compact = Regex("(?i)\\bs(\\d+)\\s*e(\\d+)\\b").find(value)
        return compact?.let { it.groupValues[1].toIntOrNull()?.let { s -> it.groupValues[2].toIntOrNull()?.let { e -> s to e } } }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url) ?: return null
        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title()
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length > 1 }
            ?.substringBefore(" | ")
            ?.trim()
            ?: return null

        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[class*=poster]")?.attr("data-src"),
            document.selectFirst("img[class*=poster]")?.attr("src"),
            document.selectFirst("img")?.attr("data-src"),
            document.selectFirst("img")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }?.let { fixUrl(it, pageUrl) }

        val description = sequenceOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".description, .plot, .synopsis, [class*=description]")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val pageText = document.text()
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(pageText)?.value?.toIntOrNull()
        val ratingText = document.selectFirst(".imdb, .rating, .score, [class*=rating], [class*=imdb]")?.text().orEmpty()
        val rating = Regex("(?<!\\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\\d)")
            .find(ratingText)?.groupValues?.getOrNull(1)?.replace(',', '.')
        val score = rating?.let { runCatching { Score.from10(it) }.getOrNull() }
        val tags = document.select("a[rel=tag], a[href*=genre], a[href*=tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)

        val episodes = document.select("a[href]").mapNotNull { anchor ->
            val episodeUrl = fixUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            if (!sameSite(episodeUrl) || episodeUrl.trimEnd('/') == pageUrl.trimEnd('/')) return@mapNotNull null
            val episodeName = sequenceOf(anchor.text().trim(), anchor.attr("title").trim())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val descriptor = "$episodeUrl $episodeName"
            if (!Regex("(?i)(bölüm|bolum|episode|/episodes?/|/bolum/|s\\d+\\s*e\\d+)").containsMatchIn(descriptor)) {
                return@mapNotNull null
            }
            val numbers = parseEpisodeNumbers(descriptor)
            newEpisode(episodeUrl) {
                name = episodeName.ifBlank { "Bölüm" }
                season = numbers?.first
                episode = numbers?.second
                posterUrl = poster
            }
        }.distinctBy { it.name + "|" + it.season + "|" + it.episode }

        // Canlı kanal tespiti: doğrudan channel.html iframe'i varsa ya da URL canlı anahtar kelimeler içeriyorsa
        val isLive = document.selectFirst("iframe[src*=channel\\.html]") != null ||
            Regex("(?i)(canlı|canli|/live|/channel|channel\\.html|/kanal/|yayin|yayın)").containsMatchIn(pageUrl)

        val isSeries = !isLive && (episodes.isNotEmpty() ||
            Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl))

        return when {
            isLive -> newMovieLoadResponse(title, pageUrl, TvType.Live, pageUrl) {
                posterUrl = poster
                this.plot = description
                this.tags = tags
            }
            isSeries -> newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
            else -> newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        }
    }

    private suspend fun findAlternativeHls(manifestUrl: String, referer: String): String? {
        val base = runCatching { URI(manifestUrl) }.getOrNull() ?: return null
        val alternatives = listOf(
            "master.m3u8",
            "index.m3u8",
            "playlist.m3u8",
            "live.m3u8",
            "stream.m3u8",
            "video.m3u8",
            "master_720.m3u8"
        )
        for (name in alternatives) {
            val candidate = runCatching { base.resolve(name).toString() }.getOrNull() ?: continue
            if (candidate == manifestUrl) continue
            if (isPlayableHls(candidate, referer)) return candidate
        }
        return null
    }

    private suspend fun isPlayableHls(url: String, referer: String): Boolean {
        val response = runCatching {
            app.get(
                url,
                headers = pageHeaders + ("Referer" to referer),
                allowRedirects = true
            )
        }.getOrNull() ?: return false

        val body = response.text.trim()
            .replace("""\/""", "/")
            .replace("""\:""", ":")
            .replace("""\u002F""", "/", true)
            .replace("""\u0026""", "&", true)

        if (!body.startsWith("#EXTM3U")) return false

        val hasVariantPlaylist = Regex(
            """(?im)^\s*#EXT-X-STREAM-INF\s*:"""
        ).containsMatchIn(body)

        val hasVideoMap = Regex(
            """(?i)#EXT-X-MAP:.*URI=["']?[^"']+\.(?:mp4|m4s)(?:[?#][^"']*)?"""
        ).containsMatchIn(body)

        // Canlı yayınlar çoğunlukla #EXTINF segment listesi içerir.
        val hasLiveSegments = Regex(
            """(?im)^\s*#EXTINF\s*:"""
        ).containsMatchIn(body)

        val uriLines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()

        if (uriLines.isEmpty()) return hasVariantPlaylist || hasVideoMap || hasLiveSegments

        val imageOnly = uriLines.all { line ->
            Regex("""(?i)\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$""")
                .containsMatchIn(line)
        }

        // Sadece görsel playlist'i ise oynatma; aksi halde (canlı segmentler dahil) kabul et.
        return !imageOnly || hasVariantPlaylist || hasVideoMap || hasLiveSegments
    }

    /**
     * channel.html sayfalarındaki Clappr player CONFIG nesnesini ayrıştırır.
     * Sırasıyla:
     *  1. CONFIG içindeki bilinen anahtarlarda doğrudan .m3u8/.mp4/.txt URL'si arar.
     *  2. Gövdede geçen herhangi bir m3u8/mp4 URL'sini yakalar.
     *  3. Son çare olarak `baseUrl` + yaygın manifest yollarını deneyip
     *     `isPlayableHls` ile doğrular.
     */
    private suspend fun extractStreamFromChannelPage(body: String, pageUrl: String): String? {
        val cleaned = body
            .replace("""\/""", "/")
            .replace("""\u002F""", "/", true)
            .replace("""\u0026""", "&", true)
            .replace("&quot;", "\"", true)
            .replace("&#039;", "'", true)
            .replace("&amp;", "&", true)

        // 1. Adım: Clappr CONFIG içindeki yaygın anahtarlar
        val configKeys = listOf(
            "baseUrl", "source", "file", "url", "streamUrl", "hlsUrl",
            "src", "m3u8", "m3u8Url", "stream", "videoUrl", "playlist",
            "manifest", "liveUrl", "hls", "link", "video", "playlistUrl"
        )
        for (key in configKeys) {
            val regex = Regex(
                """(?i)["']?$key["']?\s*[:=]\s*["']([^"'\s]+\.(?:m3u8|mp4|txt)[^"'\s]*)["']"""
            )
            val match = regex.find(cleaned)
            if (match != null) {
                val url = fixUrl(match.groupValues[1], pageUrl)
                if (url != null) return url
            }
        }

        // 2. Adım: Gövdede geçen herhangi bir m3u8/mp4 URL'si
        val anyUrlRegex = Regex(
            """(?i)(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?"""
        )
        val directUrl = anyUrlRegex.find(cleaned)?.value?.let { fixUrl(it, pageUrl) }
        if (directUrl != null) return directUrl

        // 3. Adım: baseUrl varsa yaygın manifest yollarını dene
        val baseUrlMatch = Regex("""(?i)["']?baseUrl["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(cleaned)?.groupValues?.getOrNull(1)
            ?.let { fixUrl(it, pageUrl) }
        if (baseUrlMatch != null && !Regex("""(?i)\.(?:m3u8|mp4|txt)(?:\?.*)?$""").containsMatchIn(baseUrlMatch)) {
            val base = baseUrlMatch.trimEnd('/')
            for (path in listOf(
                "index.m3u8", "playlist.m3u8", "master.m3u8",
                "live.m3u8", "stream.m3u8", "video.m3u8"
            )) {
                val candidate = "$base/$path"
                if (isPlayableHls(candidate, pageUrl)) return candidate
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visitedPages = linkedSetOf<String>()
        val emittedLinks = linkedSetOf<String>()
        var found = false

        suspend fun scanPage(pageRaw: String, referer: String, depth: Int) {
            if (depth > 2 || visitedPages.size >= 8) return
            val pageUrl = fixUrl(pageRaw) ?: return
            if (!visitedPages.add(pageUrl)) return

            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = pageHeaders + ("Referer" to referer),
                    allowRedirects = true
                )
            }.getOrNull() ?: return
            val responseBody = response.text
            val document = response.document
            Log.d("Flix", "Tarama başladı: ${pageUrl.substringBefore('?')} (derinlik=$depth)")

            // channel.html sayfaları Clappr CONFIG içinde akış URL'sini saklar.
            if (pageUrl.contains("channel.html", ignoreCase = true) ||
                Regex("(?i)/channel\\b").containsMatchIn(pageUrl)
            ) {
                val streamUrl = extractStreamFromChannelPage(responseBody, pageUrl)
                if (streamUrl != null && emittedLinks.add(streamUrl)) {
                    val path = runCatching { URI(streamUrl).path.orEmpty().lowercase() }
                        .getOrDefault("")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = if (path.endsWith(".mp4")) {
                                ExtractorLinkType.VIDEO
                            } else {
                                ExtractorLinkType.M3U8
                            }
                        ) {
                            this.referer = pageUrl
                            quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                    Log.d("Flix", "channel.html CONFIG'ten akış bulundu: ${streamUrl.substringBefore('?')}")
                }
            }

            for (track in document.select("track[src], track[data-src], track[data-url]")) {
                val raw = sequenceOf(
                    track.attr("src"),
                    track.attr("data-src"),
                    track.attr("data-url")
                ).firstOrNull { it.isNotBlank() } ?: continue
                val subtitleUrl = fixUrl(raw, pageUrl) ?: continue
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                    val language = track.attr("srclang")
                        .ifBlank { track.attr("label") }
                        .ifBlank { track.attr("lang") }
                        .ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(language, subtitleUrl))
                }
            }

            val candidates = linkedSetOf<String>()
            val selector = listOf(
                "iframe",
                "video",
                "video source",
                "source",
                "[data-embed]",
                "[data-embed-url]",
                "[data-video]",
                "[data-player]",
                "[data-player-url]",
                "[data-stream]",
                "a[href*='.m3u8']",
                "a[href*='.mp4']",
                "a[href*='channel.html']"
            ).joinToString(", ")

            for (element in document.select(selector)) {
                val raw = sequenceOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-lazy-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-embed-url"),
                    element.attr("data-video"),
                    element.attr("data-player"),
                    element.attr("data-player-url"),
                    element.attr("data-stream"),
                    element.attr("href")
                ).firstOrNull { it.isNotBlank() } ?: continue

                val candidate = fixUrl(raw, pageUrl) ?: continue
                if (candidate != pageUrl) candidates.add(candidate)
            }

            document.select("script[src]").mapNotNull { script ->
                val scriptUrl = fixUrl(script.attr("src"), pageUrl) ?: return@mapNotNull null
                val scriptName = runCatching { URI(scriptUrl).path.orEmpty().substringAfterLast('/') }
                    .getOrDefault("")
                if (Regex("""(?i)(player|video|stream|embed|hls|jwplayer|dash|source|main|app|request|xhr|api|network|index|clappr).{0,30}\.js$""").containsMatchIn(scriptName)) {
                    scriptUrl
                } else {
                    null
                }
            }.take(8).forEach { candidates.add(it) }

            val html = responseBody
                .replace("""\/""", "/")
                .replace("""\u002F""", "/", true)
                .replace("""\u0026""", "&", true)
                .replace("&quot;", "\"", true)
            val mediaPattern = Regex(
                """(?i)(?:https?:)?//[^\s"'<>]+\.(?:m3u8|mp4|txt)(?:\?[^\s"'<>]*)?"""
            )
            mediaPattern.findAll(html).forEach { match ->
                val candidate = fixUrl(match.value, pageUrl)
                if (candidate != null) candidates.add(candidate)
            }

            for (candidate in candidates.take(40)) {
                val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
                val isTextManifest = path.endsWith("/master.txt") ||
                    path.endsWith("/index.txt") ||
                    path.endsWith("/playlist.txt")
                val isHlsCandidate = path.endsWith(".m3u8") || isTextManifest
                val isDirectMedia = isHlsCandidate || path.endsWith(".mp4")

                if (isDirectMedia) {
                    var playableUrl = candidate
                    if (isHlsCandidate && !isPlayableHls(candidate, pageUrl)) {
                        Log.w("Flix", "Oynatılabilir olmayan HLS adayı elendi: ${candidate.substringBefore('?')}")
                        playableUrl = findAlternativeHls(candidate, pageUrl) ?: continue
                    }
                    val playablePath = runCatching { URI(playableUrl).path.orEmpty().lowercase() }
                        .getOrDefault("")
                    if (emittedLinks.add(playableUrl)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = playableUrl,
                                type = if (playablePath.endsWith(".mp4")) {
                                    ExtractorLinkType.VIDEO
                                } else {
                                    ExtractorLinkType.M3U8
                                }
                            ) {
                                this.referer = pageUrl
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        Log.d("Flix", "Oynatılabilir bağlantı bulundu: ${playableUrl.substringBefore('?')}")
                    }
                    continue
                }

                runCatching {
                    loadExtractor(candidate, pageUrl, subtitleCallback) { link ->
                        if (emittedLinks.add(link.url)) {
                            callback(link)
                            found = true
                        }
                    }
                }

                // channel.html adaylarını önce iç CONFIG ayrıştırması için recurse et
                if (depth < 2 && visitedPages.size < 8) {
                    scanPage(candidate, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$mainUrl/", 0)

        if (!found) {
            Log.w("Flix", "Bağlantı bulunamadı; taranan sayfa sayısı=${visitedPages.size}")
        }
        return found
    }
}
