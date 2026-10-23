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
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Beyazelma : MainAPI() {
    override var mainUrl = "https://beyazelma78.com"
    override var name = "BeyazElma"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/" to "Canlı Kanallar")

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    // m3u8 isteği için ZORUNLU başlıklar (tarayıcıdan alındı)
    private val streamHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://beyazelma78.com",
        "Referer" to "https://beyazelma78.com/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
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
            Regex("(?i)(canlı|canli|live|yayın|yayin|channel\\.html|/kanal|/channel|/tv/)").containsMatchIn(allText) -> TvType.Live
            Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText) -> TvType.TvSeries
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

    private fun parseChannelsFromJson(json: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name").trim()
                val slug = obj.optString("slug").trim()
                val logoPath = obj.optString("logo").trim()
                if (name.isBlank() || slug.isBlank()) continue

                val url = "$mainUrl/kanal/$slug"
                val poster = if (logoPath.isNotBlank()) fixUrl(logoPath, mainUrl) else null
                results.add(
                    newMovieSearchResponse(name, url, TvType.Live) {
                        posterUrl = poster
                    }
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "$mainUrl/api/channels"
        val json = runCatching {
            app.get(apiUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).text
        }.getOrNull()

        if (!json.isNullOrBlank()) {
            val liveItems = parseChannelsFromJson(json)
            if (liveItems.isNotEmpty()) {
                return newHomePageResponse(request.name, liveItems)
            }
        }

        val document = runCatching {
            app.get(request.data, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val items = parseResults(document)
        return newHomePageResponse(request.name, items)
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

        // Canlı yayın tespiti
        if (pageUrl.contains("/kanal/") || pageUrl.contains("/canli/") || pageUrl.contains("/live/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Live, pageUrl) {
                posterUrl = poster
                plot = "Canlı yayın — BeyazElma"
            }
        }

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

        val isSeries = episodes.isNotEmpty() ||
            Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl)
        return if (isSeries) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        }
    }

    // ---------------------------------------------------------------------
    //  m3u8 testi — ZORUNLU başlıklarla
    // ---------------------------------------------------------------------
    private suspend fun isPlayableHls(url: String, referer: String, origin: String): Boolean {
        val response = runCatching {
            app.get(
                url,
                headers = streamHeaders + mapOf("Referer" to referer, "Origin" to origin),
                allowRedirects = true
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..299) {
            Log.w("Beyazelma", "HLS HTTP ${response.code}: $url")
            return false
        }
        val body = response.text.trim()
        if (!body.startsWith("#EXTM3U")) {
            Log.w("Beyazelma", "HLS EXTM3U değil (kod=${response.code}): ${body.take(80)}")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------
    //  Aday URL toplama (HTML + inline script)
    // ---------------------------------------------------------------------
    private fun collectCandidateUrls(body: String, document: Document, baseUrl: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        val cleaned = body
            .replace("\\/", "/")
            .replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        // 1. Doğrudan m3u8/mp4
        Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
            .findAll(cleaned).forEach { m ->
                fixUrl(m.value, baseUrl)?.let { candidates.add(it) }
            }

        // 2. iframe / video / source etiketleri
        document.select(
            "iframe[src], iframe[data-src], video[src], video[data-src], source[src], source[data-src], [data-embed], [data-video], [data-player], [data-stream]"
        ).forEach { el ->
            sequenceOf(
                el.attr("src"), el.attr("data-src"), el.attr("data-url"),
                el.attr("data-embed"), el.attr("data-video"),
                el.attr("data-player"), el.attr("data-stream")
            ).firstOrNull { it.isNotBlank() }?.let { raw ->
                fixUrl(raw, baseUrl)?.takeIf { it != baseUrl }?.let { candidates.add(it) }
            }
        }

        // 3. Inline script içindeki anahtarlar
        for (script in document.select("script:not([src])")) {
            val data = script.data()
            Regex("""["']?(?:url|src|file|stream|streamUrl|embedUrl|playerUrl|iframe|m3u8)["']?\s*[:=]\s*["']([^"']+)["']""")
                .findAll(data).forEach { m ->
                    val v = m.groupValues[1].replace("\\/", "/")
                    fixUrl(v, baseUrl)?.let { candidates.add(it) }
                }
        }

        return candidates
    }

    // ---------------------------------------------------------------------
    //  LİNK ÇIKARMA — api/embed odaklı
    // ---------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun scanPage(pageRaw: String, referer: String, depth: Int) {
            if (depth > 3 || visited.size >= 15) return
            val pageUrl = fixUrl(pageRaw) ?: return
            if (!visited.add(pageUrl)) return

            val response = runCatching {
                app.get(pageUrl, headers = pageHeaders + ("Referer" to referer), allowRedirects = true)
            }.getOrNull() ?: return
            val body = response.text
            val document = response.document
            Log.d("Beyazelma", "Tarama: ${pageUrl.substringBefore('?')} (d=$depth) bodyLen=${body.length}")

            // Altyazılar
            document.select("track[src], track[data-src]").forEach { track ->
                val s = fixUrl(track.attr("src").ifBlank { track.attr("data-src") }, pageUrl) ?: return@forEach
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(s)) {
                    val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(lang, s))
                }
            }

            val candidates = collectCandidateUrls(body, document, pageUrl)
            Log.d("Beyazelma", "Aday URL sayısı: ${candidates.size}")

            // API endpoint denemeleri (slug üzerinden)
            val slug = runCatching { URI(pageUrl).path.substringAfterLast("/") }.getOrNull()
            if (!slug.isNullOrBlank() && depth <= 1) {
                val apiCandidates = listOf(
                    "$mainUrl/api/embed?channel=$slug",
                    "$mainUrl/api/embed/$slug",
                    "$mainUrl/api/stream/$slug",
                    "$mainUrl/api/player/$slug",
                    "$mainUrl/api/live/$slug",
                    "$mainUrl/api/channels/$slug/stream",
                    "$mainUrl/api/channels/$slug/embed"
                )
                for (api in apiCandidates) {
                    val r = runCatching {
                        app.get(api, headers = pageHeaders + ("Referer" to "$mainUrl/"), allowRedirects = true)
                    }.getOrNull() ?: continue
                    if (r.code !in 200..299) continue
                    val rBody = r.text
                    Log.d("Beyazelma", "API denemesi: $api → kod=${r.code} len=${rBody.length}")
                    Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
                        .findAll(rBody.replace("\\/", "/")).forEach { m ->
                            fixUrl(m.value, pageUrl)?.let { candidates.add(it) }
                        }
                    Regex("""["']?(?:url|streamUrl|embedUrl|playerUrl|m3u8|src)["']?\s*[:=]\s*["']([^"']+)["']""")
                        .findAll(rBody).forEach { m ->
                            fixUrl(m.groupValues[1].replace("\\/", "/"), pageUrl)?.let { candidates.add(it) }
                        }
                }
            }

            // Adayları işle
            for (cand in candidates.take(50)) {
                val path = runCatching { URI(cand).path.orEmpty().lowercase() }.getOrDefault("")
                val isM3u8 = path.endsWith(".m3u8")
                val isMp4 = path.endsWith(".mp4")
                val isDirect = isM3u8 || isMp4

                if (isDirect) {
                    if (isM3u8) {
                        val ok = isPlayableHls(cand, "$mainUrl/", mainUrl)
                        Log.d("Beyazelma", "HLS testi: $ok → $cand")
                        if (!ok) continue
                    }
                    if (emitted.add(cand)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = cand,
                                type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://beyazelma78.com/"
                                this.headers = mapOf(
                                    "Origin" to "https://beyazelma78.com",
                                    "Referer" to "https://beyazelma78.com/",
                                    "User-Agent" to browserUserAgent
                                )
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        Log.d("Beyazelma", "AKIŞ EKLENDİ: ${cand.substringBefore('?')}")
                    }
                } else {
                    // Embed/player sayfası → içini tara
                    runCatching {
                        loadExtractor(cand, "$mainUrl/", subtitleCallback) { link ->
                            if (emitted.add(link.url)) { callback(link); found = true }
                        }
                    }
                    if (depth < 3) scanPage(cand, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$mainUrl/", 0)
        if (!found) Log.w("Beyazelma", "Bağlantı bulunamadı. Denenen sayfa=${visited.size}")
        return found
    }
}
