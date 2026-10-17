package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.ArrayDeque

class DiziLife : MainAPI() {
    override var mainUrl = "https://dizi79.life"
    override var name = "DiziLife"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val tag = "DiziLife"
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    private val posterHeaders = headers + ("Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Öne Çıkanlar",
        "$mainUrl/son-eklenenler" to "Son Eklenenler",
        "$mainUrl/diziler" to "Tüm Diziler",
        "$mainUrl/filmler" to "Tüm Filmler"
    )

    private val episodeRegex = Regex("""(?i)/dizi/([^/?#]+)/sezon/(\d+)/bolum/(\d+)/?""")
    private val seasonRegex = Regex("""(?i)/dizi/([^/?#]+)/sezon/(\d+)/?""")
    private val mediaRegex = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|m4v|webm)(?:\?[^\s"'<>\\]*)?""")
    private val subtitleRegex = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:vtt|srt|ass|ssa)(?:\?[^\s"'<>\\]*)?""")

    private fun abs(rawValue: String?, base: String = mainUrl): String? {
        var raw = rawValue?.trim().orEmpty()
        if (raw.isBlank() || raw.startsWith("javascript:", true) || raw.startsWith("data:", true) ||
            raw.startsWith("blob:", true) || raw.startsWith("mailto:", true) || raw.startsWith("about:", true)
        ) return null
        raw = raw.replace("&amp;", "&").replace("\\/", "/")
            .replace("\\u002F", "/").replace("\\u002f", "/").replace("\\u0026", "&")
            .trim('"', '\'')
        return runCatching {
            val uri = if (raw.startsWith("//")) URI("https:$raw") else URI(base).resolve(raw)
            uri.normalize().toString().substringBefore('#')
                .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun path(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault(url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT))
    private fun host(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
    private fun onSite(url: String) = host(url).isNotBlank() && host(url) == host(mainUrl)
    private fun isSeries(url: String) = onSite(url) && Regex("""(?i)^/dizi/[^/]+/?$""").matches(path(url))
    private fun isMovie(url: String) = onSite(url) && Regex("""(?i)^/film/[^/]+/?$""").matches(path(url))
    private fun isEpisode(url: String) = episodeRegex.containsMatchIn(path(url))
    private fun isSeason(url: String) = seasonRegex.containsMatchIn(path(url)) && !isEpisode(url)
    private fun isItem(url: String) = isSeries(url) || isMovie(url)

    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("""[^a-z0-9]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("""(?i)\s*[-|:]\s*Dizi\.?life.*$"""), "")
        .replace(Regex("""(?i)^\s*(?:YENİ\s+)?(?:DİZİ|FİLM)\s*[:\-]?\s*"""), "")
        .replace(Regex("""(?i)\s+(?:fragmanı\s+)?izle\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:star|IMDb|IMDB)\s*[:★]?\s*\d+(?:[.,]\d+)?(?:\s+IMDb)?\s*.*$"""), "")
        .replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '•')

    private fun validTitle(title: String): Boolean {
        val value = norm(title)
        return value.length >= 2 && value !in setOf(
            "ana sayfa", "tum diziler", "tum filmler", "diziler", "filmler",
            "son eklenenler", "tumunu gor", "dizi life", "dizilife",
            "film izle", "dizi izle", "fragmani izle", "oynat", "indir", "poster"
        )
    }

    private fun slugTitle(url: String): String =
        path(url).trimEnd('/').substringAfterLast('/').replace('-', ' ').replace('_', ' ')
            .split(Regex("""\s+""")).joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

    private fun scoreFrom(text: String): Float? =
        Regex("""(?i)(?:IMDb|IMDB|star|★)\s*[:：]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""")
            .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0f..10f }

    private fun pageFor(base: String, page: Int): String =
        if (page <= 1) base else if (base.contains('?')) "$base&page=$page" else "$base?page=$page"

    private fun cardFor(anchor: Element): Element {
        var node: Element? = anchor
        repeat(6) {
            val element = node ?: return anchor
            val hasImage = element.selectFirst("img, picture img, [data-poster], [style*=background-image]") != null
            val contentCount = element.select("a[href]").count { link ->
                val candidate = abs(link.attr("href"), link.baseUri().ifBlank { mainUrl })
                candidate != null && isItem(candidate)
            }
            if (hasImage && contentCount in 1..2) return element
            node = element.parent()
        }
        return anchor
    }

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        for (attr in listOf("data-poster", "data-image", "data-src", "data-lazy-src", "data-original", "data-thumbnail", "data-thumb", "data-bg", "src")) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val url = abs(raw, base)
            if (url != null && !Regex("""(?i)(logo|favicon|avatar|placeholder|blank|spacer|pixel|\.svg$)""").containsMatchIn(url)) return url
        }
        val bg = Regex("""(?i)url\(\s*['"]?([^)'"]+)""").find(element.attr("style"))?.groupValues?.getOrNull(1)
        return abs(bg, base)
    }

    private fun posterOf(anchor: Element, card: Element, base: String): String? =
        imageUrl(anchor.selectFirst("img, picture img, source"), base)
            ?: imageUrl(card.selectFirst("img, picture img, source, [data-poster], [style*=background-image]"), base)
            ?: imageUrl(card, base)

    private fun titleOf(anchor: Element, card: Element, poster: String?): String {
        val image = anchor.selectFirst("img") ?: card.selectFirst("img")
        val candidates = listOf(
            anchor.selectFirst("h1,h2,h3,h4,[class*=title],[class*=name]")?.text(),
            card.selectFirst("h2,h3,h4,[class*=title],[class*=name]")?.text(),
            image?.attr("alt"), image?.attr("title"), anchor.attr("title"), anchor.attr("aria-label"),
            card.attr("data-title"), anchor.ownText(), anchor.text(), card.text()
        )
        candidates.forEach { raw ->
            val title = cleanTitle(raw)
            if (validTitle(title)) return title
        }
        return cleanTitle(poster?.let { path(it).substringAfterLast('/').substringBeforeLast('.').replace('-', ' ') })
            .takeIf { validTitle(it) }.orEmpty()
    }

    private fun parseItems(document: Document): List<SearchResponse> {
        val output = LinkedHashMap<String, SearchResponse>()
        document.select("a[href]").forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { mainUrl }) ?: return@forEach
            if (!isItem(url)) return@forEach
            val card = cardFor(anchor)
            val poster = posterOf(anchor, card, url)
            val title = titleOf(anchor, card, poster)
            if (!validTitle(title)) return@forEach
            val score = scoreFrom(card.text() + " " + anchor.attr("aria-label"))
            val item = if (isSeries(url)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = this@DiziLife.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = this@DiziLife.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            }
            val old = output[url]
            if (old == null || (old.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) output[url] = item
        }
        return output.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageFor(request.data, page)
        val document = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").document }
            .onFailure { Log.w(tag, "Ana sayfa alınamadı: $url", it) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseItems(document)
        Log.d(tag, "Ana sayfa " + request.name + ", sayfa " + page + ": " + items.size + " içerik")
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 100)
    }

    private fun matchesQuery(query: String, item: SearchResponse): Boolean {
        val q = norm(query)
        val title = norm(item.name)
        val slug = norm(path(item.url).substringAfterLast('/').replace('-', ' '))
        if (q.isBlank() || title.isBlank()) return false
        if (title == q || title.contains(q) || slug.contains(q)) return true
        val stop = setOf("the", "a", "an", "of", "to", "and", "ve", "bir", "ile", "in", "on")
        val parts = q.split(' ').filter { it.length >= 2 && it !in stop }
        val tokens = (title + " " + slug).split(' ').toSet()
        return parts.isNotEmpty() && parts.all { term ->
            tokens.any { it == term || (term.length >= 4 && it.length >= 4 && (it.startsWith(term) || term.startsWith(it))) }
        }
    }

    private fun relevance(query: String, item: SearchResponse): Int {
        val q = norm(query)
        val title = norm(item.name)
        val slug = norm(path(item.url).substringAfterLast('/').replace('-', ' '))
        return when {
            title == q -> 1000
            title.startsWith(q) -> 850
            title.contains(q) -> 750
            slug == q -> 700
            slug.contains(q) -> 600
            else -> q.split(' ').count { title.contains(it) || slug.contains(it) } * 10
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/api/search?query=$encoded",
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/api/search?term=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/?q=$encoded"
        )
        for (url in searchUrls) {
            val document = runCatching { app.get(url, headers = headers, referer = "$mainUrl/", allowRedirects = true).document }
                .onFailure { Log.d(tag, "Arama isteği başarısız: $url") }.getOrNull() ?: continue
            val results = parseItems(document).filter { matchesQuery(q, it) }
            Log.d(tag, "Arama " + url.substringAfter(mainUrl) + ": " + results.size + " eşleşme")
            if (results.isNotEmpty()) return results.sortedByDescending { relevance(q, it) }.take(100)
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun extractActors(document: Document, text: String): List<Actor>? {
        val linked = document.select("a[href*='/oyuncu/'],a[href*='/actor/'],a[href*='/person/'],[class*=cast] a,[class*=actors] a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val names = if (linked.isNotEmpty()) linked else {
            val raw = Regex("""(?i)Oyuncular\s*:\s*(.*?)(?=\s+(?:Yönetmen|Yayında|Türler|Sezonlar|Fragman)\s*:?\s|$)""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            raw.split(',').map { it.trim().trim('.', '•', '|') }.filter { it.isNotBlank() && it.length < 70 }.distinct()
        }
        return names.take(40).map { Actor(it) }.takeIf { it.isNotEmpty() }
    }

    private fun extractGenres(document: Document, text: String): List<String> {
        val found = LinkedHashSet<String>()
        document.select("[class*=genre] a,[class*=genres] a,[class*=tag] a,a[href*='/tur/'],a[href*='/turler/'],a[href*='/genre/']")
            .forEach { el -> cleanTitle(el.text()).takeIf { validTitle(it) }?.let(found::add) }
        if (found.isEmpty()) {
            val raw = Regex("""(?i)Türler?\s*:\s*(.*?)(?=\s+(?:Yayında|Oyuncular|Yönetmen|Sezonlar|Bölümler)\s*:?\s|$)""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            raw.split(',', '•', '|').map { it.trim() }.filter { it.isNotBlank() && it.length < 40 }.forEach(found::add)
        }
        return found.toList()
    }

    private fun seasonUrls(document: Document, pageUrl: String): Set<String> {
        val out = LinkedHashSet<String>()
        document.select("a[href]").forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { pageUrl }) ?: return@forEach
            if (onSite(url) && isSeason(url)) out.add(url)
        }
        return out
    }

    private fun posterFromPage(document: Document, base: String): String? {
        val meta = firstNonBlank(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content")
        )
        if (meta != null) abs(meta, base)?.let { return it }
        return document.selectFirst("img[fetchpriority=high],main img,article img,img")?.let { imageUrl(it, base) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = abs(url, mainUrl) ?: return null
        val response = runCatching { app.get(pageUrl, headers = headers, referer = "$mainUrl/") }
            .onFailure { Log.w(tag, "İçerik sayfası alınamadı: $pageUrl", it) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        val text = document.text()
        val title = cleanTitle(firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        )).ifBlank { slugTitle(pageUrl) }
        if (!validTitle(title)) return null
        val poster = posterFromPage(document, pageUrl)
        val description = firstNonBlank(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("[class*=overview],[class*=description],[class*=synopsis]")?.text()
        )
        val year = Regex("""(?<!\d)(?:18|19|20|21)\d{2}(?!\d)""").find(text)?.value?.toIntOrNull()
        val score = scoreFrom(text)
        val genres = extractGenres(document, text)
        val actors = extractActors(document, text)

        if (isMovie(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = genres
                actors?.let { addActors(it) }
                score?.let { this.score = Score.from10(it) }
            }
        }

        val episodes = extractEpisodes(document).ifEmpty {
            val seasonSet = seasonUrls(document, pageUrl).toMutableSet()
            val seasonNumbers = Regex("""(?i)(\d+)\s*\.?\s*Sezon""").findAll(text)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.filter { it in 1..100 }.toSet()
            val seriesPath = path(pageUrl).trimEnd('/')
            seasonNumbers.forEach { seasonSet.add(mainUrl + seriesPath + "/sezon/" + it) }
            val recovered = LinkedHashMap<String, Episode>()
            seasonSet.take(60).forEach { seasonUrl ->
                val seasonDoc = runCatching { app.get(seasonUrl, headers = headers, referer = pageUrl).document }.getOrNull()
                    ?: return@forEach
                extractEpisodes(seasonDoc).forEach { recovered.putIfAbsent(it.data, it) }
            }
            recovered.values.toList()
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = genres
            actors?.let { addActors(it) }
            score?.let { this.score = Score.from10(it) }
        }
    }

    private fun extractEpisodes(document: Document): List<Episode> {
        val out = LinkedHashMap<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { mainUrl }) ?: return@forEach
            if (!onSite(url)) return@forEach
            val match = episodeRegex.find(path(url)) ?: return@forEach
            val season = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val number = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@forEach
            if (season !in 1..100 || number !in 1..10000) return@forEach
            val card = cardFor(anchor)
            var label = firstNonBlank(
                anchor.selectFirst("h2,h3,h4,[class*=title],[class*=name]")?.text(),
                anchor.attr("title"), anchor.attr("aria-label"), anchor.ownText(), anchor.text()
            ).orEmpty()
            label = label.replace(Regex("""(?i)^\s*\d+\s*\.?\s*Sezon\s*"""), "")
                .replace(Regex("""(?i)^\s*\d+\s*\.?\s*Bölüm\s*[-:•]?\s*"""), "")
                .replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '•')
            if (label.isBlank() || norm(label) == norm("$season Sezon $number Bölüm") || norm(label) == norm("$number Bölüm")) {
                label = "$number. Bölüm"
            }
            val poster = posterOf(anchor, card, url)
            val description = card.text().takeIf { it.length in 40..1200 }
            out[url] = newEpisode(url) {
                name = label
                this.season = season
                this.episode = number
                posterUrl = poster
                this.description = description
            }
        }
        return out.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    // ---------------------------------------------------------------------
    // Playback: iframe chains, embedded media, JS strings and subtitles
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = abs(data, mainUrl) ?: return false
        val first = runCatching { app.get(episodeUrl, headers = headers, referer = "$mainUrl/") }
            .onFailure { Log.w(tag, "Bölüm/video sayfası açılamadı: $episodeUrl", it) }.getOrNull() ?: return false
        if (!first.isSuccessful) {
            Log.w(tag, "Bölüm/video HTTP " + first.code + ": " + episodeUrl)
            return false
        }
        val seenPages = LinkedHashSet<String>()
        val seenCandidates = LinkedHashSet<String>()
        val seenScripts = LinkedHashSet<String>()
        val seenSubtitleUrls = LinkedHashSet<String>()
        val pages = ArrayDeque<Pair<String, Document>>()
        pages.add(episodeUrl to first.document)
        var found = false

        while (pages.isNotEmpty() && seenPages.size < 10) {
            val (pageUrl, document) = pages.removeFirst()
            if (!seenPages.add(pageUrl)) continue
            collectSubtitles(document, pageUrl, subtitleCallback)
            val rawText = decodeEmbedded(document.html())
            scanMedia(rawText, pageUrl).forEach { media ->
                if (seenCandidates.add(media) && emitMedia(media, pageUrl, callback)) found = true
            }

            // Player sayfasının akış adresini çalıştırılan JavaScript/API içinden alması yaygın.
            // Harici JS dosyalarını da kontrollü biçimde indirip içlerindeki kaynak/API adreslerini tara.
            val scriptCandidates = LinkedHashSet<String>()
            scriptUrls(document, pageUrl).take(8).forEach scriptLoop@ { scriptUrl ->
                if (seenScripts.size >= 16 || !seenScripts.add(scriptUrl)) return@scriptLoop
                val scriptResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = headers + ("Referer" to pageUrl),
                        referer = pageUrl,
                        allowRedirects = true
                    )
                }.getOrNull() ?: return@scriptLoop
                if (!scriptResponse.isSuccessful || scriptResponse.text.isBlank()) return@scriptLoop

                val scriptText = decodeEmbedded(scriptResponse.text)
                scanMedia(scriptText, scriptUrl).forEach { media ->
                    if (seenCandidates.add(media) && emitMedia(media, pageUrl, callback)) found = true
                }
                textPlayerCandidates(scriptText, scriptUrl).forEach(scriptCandidates::add)
            }

            val candidates = (playerCandidates(document, rawText, pageUrl) + scriptCandidates).distinct()
            candidates.forEach candidateLoop@ { candidate ->
                if (!seenCandidates.add(candidate)) return@candidateLoop

                // Bu site oynatıcı iframe'inde /player/{id} kullanıyor; medya ise
                // ayrı CDN'de /{id}/master.m3u8 olarak sunulabiliyor.
                val playerId = extractPlayerId(candidate)
                if (playerId != null) {
                    val directStream = "$knownStreamOrigin/$playerId/master.m3u8"
                    if (seenCandidates.add(directStream) && emitMedia(directStream, candidate, callback)) {
                        found = true
                        Log.d(tag, "Oynatıcı kimliğinden HLS adresi üretildi: $playerId")
                    }
                    val turkishSubtitle = "$knownStreamOrigin/$playerId/sub_turkish/subtitles.m3u8"
                    if (seenSubtitleUrls.add(turkishSubtitle)) {
                        subtitleCallback(SubtitleFile(lang = "Turkish", url = turkishSubtitle))
                        Log.d(tag, "Türkçe HLS altyazı adresi eklendi: $playerId")
                    }
                }

                if (isMedia(candidate)) {
                    if (emitMedia(candidate, pageUrl, callback)) found = true
                    return@candidateLoop
                }
                if (onSite(candidate) && (isItem(candidate) || isEpisode(candidate) || isSeason(candidate))) return@candidateLoop

                var emitted = false
                runCatching {
                    val proxy: (ExtractorLink) -> Unit = { link ->
                        callback(link)
                        emitted = true
                    }
                    loadExtractor(candidate, pageUrl, subtitleCallback, proxy)
                }.onFailure {
                    Log.d(tag, "Extractor başarısız: " + host(candidate) + " / " + it.javaClass.simpleName)
                }
                if (emitted) {
                    found = true
                    return@candidateLoop
                }
                if (seenPages.size < 6) {
                    val nested = runCatching {
                        app.get(candidate, headers = headers + ("Referer" to pageUrl), referer = pageUrl, allowRedirects = true)
                    }.getOrNull()
                    if (nested != null && nested.isSuccessful && nested.text.isNotBlank()) {
                        pages.add(candidate to nested.document)
                    }
                }
            }
            Log.d(tag, "Oynatıcı taraması " + pageUrl.substringAfter(mainUrl) +
                ": aday=" + candidates.size + ", bulunan=" + found)
        }
        return found
    }

    private val knownStreamOrigin = "https://one.makesomthingup.click"

    private fun extractPlayerId(url: String): String? {
        val decoded = url.replace("&amp;", "&").substringBefore('#')
        return Regex("""(?i)/player/([a-z0-9_-]{12,80})(?:[/?#]|$)""")
            .find(decoded)?.groupValues?.getOrNull(1)
    }

    private fun playerCandidates(document: Document, rawText: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val attrs = listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "data-iframe", "data-file", "data-player", "data-video", "data-hls", "data-stream")
        document.select("iframe,video,source,[data-src],[data-url],[data-embed],[data-iframe],[data-file],[data-player],[data-video],[data-hls],[data-stream],meta[property=og:video]")
            .forEach { element ->
                attrs.forEach attrLoop@ { attr ->
                    val url = abs(element.attr(attr), base) ?: return@attrLoop
                    if (playerCandidate(url, element.text() + " " + element.attr("class"))) out.add(url)
                }
                if (element.tagName() == "meta") abs(element.attr("content"), base)?.let {
                    if (playerCandidate(it, "video")) out.add(it)
                }
            }

        document.select("a[href]").filter {
            Regex("""(?i)(izle|oynat|player|video|kaynak|indir|stream)""")
                .containsMatchIn(it.text() + " " + it.attr("class") + " " + it.attr("aria-label"))
        }.forEach { anchor ->
            val url = abs(anchor.attr("href"), base) ?: return@forEach
            if (!onSite(url) && playerCandidate(url, anchor.text())) out.add(url)
        }

        scanMedia(rawText, base).forEach(out::add)
        Regex("""(?i)(?:src|file|source|url|embed|player|hls|playlist|videoUrl)["']?\s*[:=]\s*["']([^"']{6,1000})["']""")
            .findAll(rawText).forEach { match ->
                abs(match.groupValues.getOrNull(1), base)?.let { if (playerCandidate(it, "script")) out.add(it) }
            }
        return out.toList()
    }

    private fun scriptUrls(document: Document, base: String): List<String> {
        val out = LinkedHashSet<String>()
        document.select("script[src],link[rel=preload][as=script][href]").forEach { element ->
            val raw = if (element.tagName() == "script") element.attr("src") else element.attr("href")
            val url = abs(raw, base) ?: return@forEach
            val lower = url.lowercase(Locale.ROOT)
            if (isIgnoredResource(url)) return@forEach
            if (lower.contains(".js") || lower.contains(".mjs") || lower.contains("script")) out.add(url)
        }
        return out.toList()
    }

    private fun textPlayerCandidates(raw: String, base: String): List<String> {
        val text = decodeEmbedded(raw)
        val out = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""(?i)(?:src|file|source|url|embed|player|hls|playlist|videoUrl|streamUrl|manifest|mediaUrl|apiUrl|endpoint|requestUrl)["']?\s*[:=]\s*["']([^"'<>]{4,1800})["']"""),
            Regex("""(?i)(?:fetch|axios\.(?:get|post)|\.open|\.get|\.post)\s*\(\s*["']([^"'<>]{4,1800})["']"""),
            Regex("""(?i)["']((?:https?:)?//[^"'\\\s<>]{8,1800}|/(?:api|ajax|player|video|stream|source|playlist|embed|resolve|v[0-9]+)/[^"'\\\s<>]{1,1800})["']""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach matchLoop@ { match ->
                val rawUrl = match.groupValues.getOrNull(1).orEmpty()
                    .trimEnd(')', ']', '}', ';', ',')
                val url = abs(rawUrl, base) ?: return@matchLoop
                if (playerCandidate(url, match.value.take(160))) out.add(url)
            }
        }
        return out.toList()
    }

    private fun isIgnoredResource(url: String): Boolean {
        val lowerHost = host(url)
        val lower = url.lowercase(Locale.ROOT)
        return lowerHost in setOf(
            "static.cloudflareinsights.com",
            "google-analytics.com",
            "www.google-analytics.com",
            "googletagmanager.com",
            "connect.facebook.net",
            "doubleclick.net"
        ) || lower.contains("/beacon.min.js") || lower.contains("/analytics.")
    }

    private fun playerCandidate(url: String, context: String): Boolean {
        if (!url.startsWith("http", true) || isIgnoredResource(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        if (listOf(".css", ".js", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".woff", ".woff2").any { lower.endsWith(it) } ||
            listOf("favicon", "logo", "avatar", "placeholder").any { lower.contains(it) }
        ) return false
        if (isMedia(url)) return true
        if (onSite(url)) {
            val p = path(url)
            if (isItem(url) || p == "/" || p == "/diziler" || p == "/filmler" || p == "/son-eklenenler") return false
            return Regex("""(?i)(play|player|video|embed|watch|stream|source|izle|bolum|episode|media|api|resolve|manifest|playlist|hls)""").containsMatchIn(p) ||
                Regex("""(?i)(player|video|embed|stream|watch|source|api|playlist|hls)""").containsMatchIn(context)
        }
        return Regex("""(?i)(player|embed|video|stream|source|hls|m3u8|playlist|manifest|resolve|watch|api|media|file)""")
            .containsMatchIn(path(url)) ||
            Regex("""(?i)(player|video|embed|stream|source|playlist|manifest)""").containsMatchIn(context)
    }

    private fun isMedia(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4|mpd|m4v|webm)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("/hls/", true) || url.contains("master.m3u8", true)

    private fun scanMedia(raw: String, base: String): List<String> {
        val text = decodeEmbedded(raw)
        val found = LinkedHashSet<String>()
        mediaRegex.findAll(text).forEach { match ->
            abs(match.value.trimEnd(')', ']', '}', ';', ','), base)?.let(found::add)
        }
        Regex("""(?i)//[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|m4v|webm)(?:\?[^\s"'<>\\]*)?""")
            .findAll(text).forEach { match ->
                abs(match.value.trimEnd(')', ']', '}', ';', ','), base)?.let(found::add)
            }
        if (text.contains("%3A%2F%2F", true) || text.contains("%2F", true)) {
            val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault("")
            if (decoded.isNotBlank()) mediaRegex.findAll(decoded).forEach { match ->
                abs(match.value.trimEnd(')', ']', '}', ';', ','), base)?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun decodeEmbedded(raw: String): String {
        var text = raw.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\x2F", "/").replace("&amp;", "&")
        val regex = Regex("""(?i)atob\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""")
        val decoded = regex.findAll(text).mapNotNull { match ->
            runCatching { String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }.joinToString("\n")
        if (decoded.isNotBlank()) text += "\n" + decoded
        if (text.contains("%3A%2F%2F", true)) {
            val decodedUrl = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault("")
            if (decodedUrl.isNotBlank()) text += "\n" + decodedUrl
        }
        return text
    }

    private fun collectSubtitles(document: Document, base: String, callback: (SubtitleFile) -> Unit) {
        val sent = LinkedHashSet<String>()
        document.select("track[src],track[data-src],a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("href") } }
            val url = abs(raw, base) ?: return@forEach
            if (!Regex("""(?i)\.(?:vtt|srt|ass|ssa)(?:$|[?#])""").containsMatchIn(url) || !sent.add(url)) return@forEach
            val lang = firstNonBlank(
                element.attr("srclang"), element.attr("label"),
                Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""").find(url)?.groupValues?.getOrNull(1)
            ) ?: "Unknown"
            callback(SubtitleFile(lang = lang, url = url))
        }
        subtitleRegex.findAll(decodeEmbedded(document.html())).forEach { match ->
            val url = abs(match.value.trimEnd(')', ']', '}', ';', ','), base) ?: return@forEach
            if (!sent.add(url)) return@forEach
            val lang = Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""").find(url)?.groupValues?.getOrNull(1) ?: "Unknown"
            callback(SubtitleFile(lang = lang, url = url))
        }
    }

    private suspend fun emitMedia(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Accept" to "*/*")
        when {
            url.contains(".m3u8", true) || url.contains("/hls/", true) -> {
                val generated = runCatching { M3u8Helper.generateM3u8(name, url, referer, null, mediaHeaders) }.getOrDefault(emptyList())
                if (generated.isNotEmpty()) generated.forEach(callback)
                else callback(newExtractorLink(name, "DiziLife HLS", url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            url.contains(".mpd", true) -> {
                callback(newExtractorLink(name, "DiziLife DASH", url, ExtractorLinkType.DASH) {
                    this.referer = referer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            Regex("""(?i)\.(?:mp4|m4v|webm)(?:$|[?#])""").containsMatchIn(url) -> {
                callback(newExtractorLink(name, "DiziLife Video", url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        }
        return false
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
