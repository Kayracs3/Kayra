package com.Kayracs3

import android.util.Log
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
    override val mainPage = mainPageOf("$mainUrl/" to "Canlı Kanal")

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    // =================================================================
    // KRİTİK: Tarayıcıyla BİREBİR eşleşen stream başlıkları
    // =================================================================
    private fun buildStreamHeaders(
        streamUrl: String,
        pageUrl: String,
        cookies: String?
    ): Map<String, String> {
        return buildMap {
            // Tarayıcıdaki user-agent (log ile birebir)
            put(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
            )
            put("Accept", "*/*")
            put("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Origin", "https://fixbettv84.com")
            // Tarayıcı ana sayfayı referer gönderiyor (channel.html DEĞİL)
            put("Referer", "https://fixbettv84.com/")
            // Cloudflare/TLS parmak izi farkını kapatan kritik başlıklar
            put(
                "sec-ch-ua",
                "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\""
            )
            put("sec-ch-ua-mobile", "?0")
            put("sec-ch-ua-platform", "\"Windows\"")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "cross-site")
            if (!cookies.isNullOrBlank()) put("Cookie", cookies)
        }
    }

    private val channelLogos: Map<String, String> = mapOf(
        "trt 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png",
        "trt1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png",
        "trt 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-2-tr.png",
        "trt 3" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-3-tr.png",
        "trt haber" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-haber-tr.png",
        "trt spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png",
        "trt belgesel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-belgesel-tr.png",
        "trt muzik" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-muzik-tr.png",
        "trt cocuk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-cocuk-tr.png",
        "trt world" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-world-tr.png",
        "atv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/atv-tr.png",
        "a haber" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-haber-tr.png",
        "a spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-spor-tr.png",
        "show tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/show-tv-tr.png",
        "showtv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/show-tv-tr.png",
        "star tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/star-tv-tr.png",
        "startv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/star-tv-tr.png",
        "now tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/now-tv-tr.png",
        "now" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/now-tv-tr.png",
        "fox" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/fox-tv-tr.png",
        "tv8" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-tr.png",
        "kanal d" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kanal-d-tr.png",
        "kanald" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kanal-d-tr.png",
        "cnn turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cnn-turk-tr.png",
        "ntv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ntv-tr.png",
        "ntv spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ntv-spor-tr.png",
        "haberturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/haberturk-tr.png",
        "beyaz tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/beyaz-tv-tr.png",
        "tv 100" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv-100-tr.png",
        "tv100" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv-100-tr.png",
        "teve2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/teve2-tr.png",
        "360" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/360-tr.png",
        "ulke tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ulke-tv-tr.png",
        "tvnet" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tvnet-tr.png",
        "haber global" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/haber-global-tr.png",
        "halk tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/halk-tv-tr.png",
        "tele 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tele1-tr.png",
        "sozcu tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/sozcu-tv-tr.png",
        "krt" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/krt-tr.png",
        "s sport" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png",
        "ssport" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png",
        "s sport 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png",
        "tivibu spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-tr.png",
        "tivibuspor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-tr.png",
        "bein sports 1" to "https://images.seeklogo.com/logo-png/48/1/bein-sports-1-logo-png_seeklogo-481583.png",
        "beinsports1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-1-tr.png",
        "bein sports 2" to "https://images.seeklogo.com/logo-png/48/1/bein-sports-2-logo-png_seeklogo-481584.png",
        "beinsports2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-2-tr.png",
        "bein sports 3" to "https://images.seeklogo.com/logo-png/48/1/bein-sports-3-logo-png_seeklogo-481585.png",
        "bein sports 4" to "https://static.cdnlogo.com/logos/b/213/bein-sports-4-logo.png",
        "bein sports max 1" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRLbroEld1w5Nr2og_90zc2yHN6a4foW4SBklnMI7Ft2g&s=10",
        "bein sports max 2" to "https://static.cdnlogo.com/logos/b/744/bein-sports-max-2-logo.png",
        "eurosport 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/eurosport-1-tr.png",
        "eurosport 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/eurosport-2-tr.png",
        "minika cocuk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-cocuk-tr.png",
        "minika go" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-go-tr.png",
        "disney channel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/disney-channel-tr.png",
        "cartoon network" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cartoon-network-tr.png",
        "nickelodeon" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nickelodeon-tr.png",
        "nat geo" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-tr.png",
        "national geographic" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-tr.png",
        "nat geo wild" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-wild-tr.png",
        "discovery channel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/discovery-channel-tr.png",
        "history" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/history-tr.png",
        "animal planet" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/animal-planet-tr.png",
        "tlc" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tlc-tr.png",
        "dmax" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/dmax-tr.png",
        "bloomberg ht" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bloomberg-ht-tr.png",
        "cnbc-e" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cnbc-e-tr.png",
        "ekoturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ekoturk-tr.png",
        "powerturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/powerturk-tr.png",
        "power turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/powerturk-tr.png",
        "number one tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/number-one-tv-tr.png",
        "dream turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/dream-turk-tr.png",
        "kral tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kral-tv-tr.png",
        "kral pop" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kral-pop-tv-tr.png",
        "tv 24" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png",
        "24 tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png",
        "24" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png"
    )

    private fun normalizeChannelName(raw: String): String = raw.lowercase()
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace('İ', 'i').replace('Ğ', 'g').replace('Ü', 'u')
        .replace('Ş', 's').replace('Ö', 'o').replace('Ç', 'c')
        .replace(Regex("[^a-z0-9]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun liveLogoFor(title: String): String {
        val norm = normalizeChannelName(title)
        if (norm.isNotEmpty()) {
            channelLogos[norm]?.let { return it }
            for ((key, url) in channelLogos) {
                if (norm == key || norm.startsWith("$key ") || norm.endsWith(" $key") ||
                    norm.contains(" $key ") || norm.contains(key)
                ) return url
            }
        }
        val encoded = runCatching { URLEncoder.encode(title, "UTF-8") }.getOrDefault(title)
        return "https://ui-avatars.com/api/?name=$encoded&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
    }

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
            Regex("(?i)(canlı|canli|live|yayın|yayin|channel\\.html|/kanal|/channel|/tv/)").containsMatchIn(allText) -> TvType.Live
            Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText) -> TvType.TvSeries
            else -> TvType.Movie
        }
        val sitePoster = posterFrom(this, link)
        val poster = if (type == TvType.Live && sitePoster.isNullOrBlank()) {
            liveLogoFor(title)
        } else sitePoster
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()
        return newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun matchesQuery(query: String, title: String): Boolean {
        val normalizedTitle = normalizeChannelName(title)
        val normalizedQuery = normalizeChannelName(query)
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

        val sitePoster = sequenceOf(
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
                posterUrl = sitePoster
            }
        }.distinctBy { it.name + "|" + it.season + "|" + it.episode }

        val isLive = document.selectFirst("iframe[src*=channel\\.html]") != null ||
            Regex("(?i)(channel\\.html|/live|/channel|/kanal|canlı|canli)").containsMatchIn(pageUrl)

        val isSeries = !isLive && (episodes.isNotEmpty() ||
            Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl))

        val livePoster = liveLogoFor(title)

        return when {
            isLive -> {
                val liveDataUrl = if (pageUrl.contains("channel.html", true)) {
                    pageUrl
                } else {
                    document.selectFirst("iframe[src*=channel\\.html]")?.let { iframe ->
                        fixUrl(iframe.attr("src"), pageUrl) ?: pageUrl
                    } ?: pageUrl
                }
                newMovieLoadResponse(title, pageUrl, TvType.Live, liveDataUrl) {
                    posterUrl = livePoster
                    this.plot = description
                    this.tags = tags
                }
            }
            isSeries -> newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = sitePoster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
            else -> newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = sitePoster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        }
    }

    // =================================================================
    // STREAM URL ÇIKARIMI — sadece baseUrl + id'yi bulur, path denemesi loadLinks'te
    // =================================================================
    private suspend fun extractBaseAndId(body: String, pageUrl: String): Pair<String, String>? {
        val cleaned = body
            .replace("\\/", "/")
            .replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
            .replace("&quot;", "\"", true)
            .replace("\\u003D", "=", true)

        var baseUrl: String? = null
        var domainUrl: String? = null

        val varPatterns = listOf(
            Regex("""domainUrl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""baseUrl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""baseurl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""streamUrl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""hlsUrl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""playlistUrl\s*[:=]\s*['"]([^'"]+)['"]""")
        )
        for (pattern in varPatterns) {
            pattern.find(cleaned)?.groupValues?.getOrNull(1)?.let { value ->
                val normalized = value.replace("\\/", "/").trimEnd('/')
                when {
                    normalized.contains("domain", true) && domainUrl == null -> domainUrl = normalized
                    normalized.startsWith("http", true) && baseUrl == null -> baseUrl = normalized
                }
            }
        }

        if (domainUrl != null) {
            runCatching {
                val r = app.get(domainUrl!!, headers = pageHeaders, allowRedirects = true).text
                Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
                    .find(r)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.let {
                        if (baseUrl == null) baseUrl = it
                    }
            }
        }

        val id = runCatching {
            URI(pageUrl).query?.split("&")
                ?.find { it.startsWith("id=") }
                ?.substringAfter("id=")
        }.getOrNull() ?: return null

        val finalBase = baseUrl?.trimEnd('/') ?: return null
        return finalBase to id
    }

    // =================================================================
    // ADAY M3U8'leri test et: JPG-only yerine TS/fMP4 içereni tercih et
    // =================================================================
    private suspend fun resolveStreamUrl(
        baseUrl: String,
        id: String,
        pageUrl: String,
        cookies: String?
    ): String? {
        val pathVariants = listOf(
            "mono.m3u8",
            "index.m3u8",
            "master.m3u8",
            "playlist.m3u8",
            "stream.m3u8",
            "video.m3u8",
            "hd.m3u8",
            "sd.m3u8"
        )

        var jpgOnlyFallback: String? = null

        for (path in pathVariants) {
            val candidate = "$baseUrl/$id/$path"
            val test = runCatching {
                app.get(
                    candidate,
                    headers = buildStreamHeaders(candidate, pageUrl, cookies),
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            if (test.code !in 200..299) {
                Log.d("Flix", "  $path → HTTP ${test.code}")
                continue
            }

            val content = test.text.trim()
            if (!content.startsWith("#EXTM3U")) {
                Log.d("Flix", "  $path → #EXTM3U değil")
                continue
            }

            // TS / MP4 / fMP4 segment içeriyor mu?
            val hasTs = Regex("""(?im)^\s*[^\s#][^\n]*\.ts(?:\?|$)""").containsMatchIn(content)
            val hasMp4 = Regex("""(?im)^\s*[^\s#][^\n]*\.(?:mp4|m4s)(?:\?|$)""").containsMatchIn(content)
            val hasMap = content.contains("#EXT-X-MAP")
            val hasVariant = content.contains("#EXT-X-STREAM-INF")
            val hasJpg = content.contains(".jpg", ignoreCase = true)

            Log.d(
                "Flix",
                "  $path → HLS ✓ (ts=$hasTs, mp4=$hasMp4, map=$hasMap, variant=$hasVariant, jpg=$hasJpg)"
            )

            if (hasTs || hasMp4 || hasMap || hasVariant) {
                Log.d("Flix", "✔ Gerçek video stream: $path")
                return candidate
            }

            if (hasJpg && jpgOnlyFallback == null) {
                jpgOnlyFallback = candidate
            }
        }

        if (jpgOnlyFallback != null) {
            Log.w("Flix", "⚠ Sadece JPG-only stream bulundu, yine de dönüyorum: $jpgOnlyFallback")
        } else {
            Log.w("Flix", "✘ Hiçbir geçerli stream bulunamadı")
        }
        return jpgOnlyFallback
    }

    // =================================================================
    // ANA LİNK ÇIKARMA
    // =================================================================
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
                app.get(pageUrl, headers = pageHeaders + ("Referer" to referer), allowRedirects = true)
            }.getOrNull() ?: return
            val responseBody = response.text
            val document = response.document
            Log.d("Flix", "Tarama: ${pageUrl.substringBefore('?')} (depth=$depth)")

            val pageCookies = runCatching {
                response.headers.values("Set-Cookie")
                    .joinToString("; ") { it.substringBefore(";").trim() }
                    .takeIf { it.isNotBlank() }
            }.getOrNull()

            // === CHANNEL.HTML → STREAM ===
            if (pageUrl.contains("channel.html", ignoreCase = true)) {
                val baseId = extractBaseAndId(responseBody, pageUrl)
                if (baseId != null) {
                    val (baseUrl, id) = baseId
                    Log.d("Flix", "baseUrl=$baseUrl id=$id")
                    val streamUrl = resolveStreamUrl(baseUrl, id, pageUrl, pageCookies)
                    if (streamUrl != null && emittedLinks.add(streamUrl)) {
                        // Referer OLARAK ANA SAYFA (log ile birebir)
                        val headers = buildStreamHeaders(streamUrl, pageUrl, pageCookies)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"   // ANA SAYFA
                                this.headers = headers
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        Log.d("Flix", "✔ Extractor sunuldu: ${streamUrl.substringBefore('?')}")
                    }
                } else {
                    Log.w("Flix", "baseUrl/id çıkarılamadı")
                }
            }

            // === ALTYAZILAR ===
            for (track in document.select("track[src], track[data-src], track[data-url]")) {
                val raw = sequenceOf(track.attr("src"), track.attr("data-src"), track.attr("data-url"))
                    .firstOrNull { it.isNotBlank() } ?: continue
                val subtitleUrl = fixUrl(raw, pageUrl) ?: continue
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                    val language = track.attr("srclang")
                        .ifBlank { track.attr("label") }
                        .ifBlank { track.attr("lang") }
                        .ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(language, subtitleUrl))
                }
            }

            // === DİĞER ADAYLAR ===
            val candidates = linkedSetOf<String>()
            val selector = listOf(
                "iframe", "video", "video source", "source",
                "[data-embed]", "[data-embed-url]", "[data-video]", "[data-player]",
                "[data-player-url]", "[data-stream]",
                "a[href*='.m3u8']", "a[href*='.mp4']", "a[href*='channel.html']"
            ).joinToString(", ")

            for (element in document.select(selector)) {
                val raw = sequenceOf(
                    element.attr("src"), element.attr("data-src"), element.attr("data-lazy-src"),
                    element.attr("data-url"), element.attr("data-embed"), element.attr("data-embed-url"),
                    element.attr("data-video"), element.attr("data-player"), element.attr("data-player-url"),
                    element.attr("data-stream"), element.attr("href")
                ).firstOrNull { it.isNotBlank() } ?: continue
                val candidate = fixUrl(raw, pageUrl) ?: continue
                if (candidate != pageUrl) candidates.add(candidate)
            }

            val cleanedBody = responseBody
                .replace("\\/", "/")
                .replace("\\u002F", "/", true)
                .replace("\\u0026", "&", true)
                .replace("&quot;", "\"", true)

            Regex("""(?i)(?:https?:)?//[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
                .findAll(cleanedBody).forEach { match ->
                    fixUrl(match.value, pageUrl)?.let { candidates.add(it) }
                }

            for (candidate in candidates.take(40)) {
                val clean = candidate.replace("\\/", "/").trim()
                val path = runCatching { URI(clean).path.orEmpty().lowercase() }.getOrDefault("")
                val isHls = path.endsWith(".m3u8")
                val isVideo = path.endsWith(".mp4")
                val isDirect = isHls || isVideo

                if (isDirect) {
                    if (emittedLinks.add(clean)) {
                        val headers = buildStreamHeaders(clean, pageUrl, pageCookies)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = clean,
                                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = headers
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                    continue
                }

                runCatching {
                    loadExtractor(clean, pageUrl, subtitleCallback) { link ->
                        if (emittedLinks.add(link.url)) {
                            callback(link)
                            found = true
                        }
                    }
                }

                if (depth < 2 && visitedPages.size < 8 && !clean.contains("channel.html", true)) {
                    scanPage(clean, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$mainUrl/", 0)

        if (!found) Log.w("Flix", "Bağlantı bulunamadı (sayfa=${visitedPages.size})")
        return found
    }
}
