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
import org.json.JSONObject
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

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

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
            value.startsWith("data:", true) || value.startsWith("mailto:", true)) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
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
            image?.attr("data-src"), image?.attr("data-lazy-src"),
            image?.attr("data-original"), image?.attr("data-original-src"), image?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) }
        return fixUrl(raw)
    }

    private fun titleFrom(element: Element, link: Element): String {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        return sequenceOf(
            element.selectFirst("h1, h2, h3, .title, .film-title, .movie-title")?.text(),
            link.attr("title"), image?.attr("alt"), link.text(), element.attr("title")
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
                fixUrl(anchor.attr("href"))?.let { isContentUrl(it) } ?: false
            }
        } ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!isContentUrl(url)) return null
        val title = titleFrom(this, link)
        if (title.length < 2 || title.lowercase() in setOf(
                "ana sayfa","home","filmler","diziler","kategoriler","giriş","giris",
                "kayıt ol","kayit ol","iletişim","iletisim","hakkımızda","hakkimizda")) return null
        val allText = "$title $url ${text()}"
        val type = when {
            Regex("(?i)(canlı|canli|live|yayın|yayin|channel\\.html|/kanal|/channel|/tv/)").containsMatchIn(allText) -> TvType.Live
            Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText) -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, url, type) {
            posterUrl = posterFrom(this@toSearchResult, link)
            this.year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()
        }
    }

    private fun matchesQuery(q: String, title: String): Boolean {
        val n = title.lowercase().replace('ı','i').replace('ğ','g').replace('ü','u').replace('ş','s').replace('ö','o').replace('ç','c')
        val nq = q.lowercase().trim().replace('ı','i').replace('ğ','g').replace('ü','u').replace('ş','s').replace('ö','o').replace('ç','c')
        if (nq.isBlank()) return true
        return n.contains(nq) || nq.split(Regex("\\s+")).filter { it.length > 1 }.all { n.contains(it) }
    }

    private fun parseResults(document: Document, query: String = ""): List<SearchResponse> {
        val cards = document.select(
            "article, .movie-item, .film-item, .video-item, .series-item, .film-card, .movie-card, .post-item, .movie, .film, .series, .card, .thumbnail, .list-item"
        ).mapNotNull { it.toSearchResult() }
        val results = if (cards.isNotEmpty()) cards else document.select("a[href]").mapNotNull { it.toSearchResult() }
        return results.distinctBy { it.url }.filter { matchesQuery(query, it.name) }.take(100)
    }

    private fun parseChannelsFromJson(json: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                val slug = o.optString("slug").trim()
                val logoPath = o.optString("logo").trim()
                if (name.isBlank() || slug.isBlank()) continue
                val poster = if (logoPath.isNotBlank()) fixUrl(logoPath, mainUrl) else null
                results.add(newMovieSearchResponse(name, "$mainUrl/kanal/$slug", TvType.Live) { posterUrl = poster })
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = runCatching {
            app.get("$mainUrl/api/channels", headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).text
        }.getOrNull()
        if (!json.isNullOrBlank()) {
            val items = parseChannelsFromJson(json)
            if (items.isNotEmpty()) return newHomePageResponse(request.name, items)
        }
        val doc = runCatching {
            app.get(request.data, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, parseResults(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim(); if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        for (url in listOf("$mainUrl/?s=$enc", "$mainUrl/search/$enc/", "$mainUrl/arama/$enc/", "$mainUrl/?ara=$enc").distinct()) {
            val doc = runCatching {
                app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            val r = parseResults(doc, q)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeNumbers(value: String): Pair<Int, Int>? {
        val s = value.replace('_',' ').replace('-',' ')
        val first = Regex("(?i)(?:sezon|season|s)\\s*(\\d+)\\D{0,40}?(?:bölüm|bolum|episode|ep|e)\\s*(\\d+)").find(s)
        if (first != null) {
            return first.groupValues[1].toIntOrNull()?.let { se -> first.groupValues[2].toIntOrNull()?.let { ep -> se to ep } }
        }
        val c = Regex("(?i)\\bs(\\d+)\\s*e(\\d+)\\b").find(value)
        return c?.let { it.groupValues[1].toIntOrNull()?.let { se -> it.groupValues[2].toIntOrNull()?.let { ep -> se to ep } } }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url) ?: return null
        val doc = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return null
        val title = sequenceOf(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("h1")?.text(), doc.title()
        ).mapNotNull { it?.trim() }.firstOrNull { it.length > 1 }?.substringBefore(" | ")?.trim() ?: return null
        val poster = sequenceOf(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("img[class*=poster]")?.attr("data-src"),
            doc.selectFirst("img[class*=poster]")?.attr("src"),
            doc.selectFirst("img")?.attr("data-src"),
            doc.selectFirst("img")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }?.let { fixUrl(it, pageUrl) }

        if (pageUrl.contains("/kanal/") || pageUrl.contains("/canli/") || pageUrl.contains("/live/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Live, pageUrl) {
                posterUrl = poster; plot = "Canlı yayın — BeyazElma"
            }
        }

        val desc = sequenceOf(
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst(".description, .plot, .synopsis, [class*=description]")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val pageText = doc.text()
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(pageText)?.value?.toIntOrNull()
        val score = doc.selectFirst(".imdb, .rating, .score, [class*=rating], [class*=imdb]")?.text()
            ?.let { Regex("(?<!\\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\\d)").find(it)?.groupValues?.getOrNull(1)?.replace(',', '.') }
            ?.let { runCatching { Score.from10(it) }.getOrNull() }
        val tags = doc.select("a[rel=tag], a[href*=genre], a[href*=tur/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(30)

        val episodes = doc.select("a[href]").mapNotNull { a ->
            val eUrl = fixUrl(a.attr("href"), pageUrl) ?: return@mapNotNull null
            if (!sameSite(eUrl) || eUrl.trimEnd('/') == pageUrl.trimEnd('/')) return@mapNotNull null
            val eName = sequenceOf(a.text().trim(), a.attr("title").trim()).firstOrNull { it.isNotBlank() }.orEmpty()
            val desc2 = "$eUrl $eName"
            if (!Regex("(?i)(bölüm|bolum|episode|/episodes?/|/bolum/|s\\d+\\s*e\\d+)").containsMatchIn(desc2)) return@mapNotNull null
            val nums = parseEpisodeNumbers(desc2)
            newEpisode(eUrl) { name = eName.ifBlank { "Bölüm" }; season = nums?.first; episode = nums?.second; posterUrl = poster }
        }.distinctBy { "${it.name}|${it.season}|${it.episode}" }

        val isSeries = episodes.isNotEmpty() || Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl)
        return if (isSeries) newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster; this.year = year; this.plot = desc; this.tags = tags; this.score = score
        } else newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster; this.year = year; this.plot = desc; this.tags = tags; this.score = score
        }
    }

    private suspend fun isPlayableHls(url: String, referer: String, origin: String): Boolean {
        val r = runCatching {
            app.get(url, headers = streamHeaders + mapOf("Referer" to referer, "Origin" to origin), allowRedirects = true)
        }.getOrNull() ?: return false
        if (r.code !in 200..299) { Log.w("Beyazelma", "HLS HTTP ${r.code}: ${url.substringBefore('?')}"); return false }
        return r.text.trim().startsWith("#EXTM3U")
    }

    private fun collectCandidateUrls(body: String, document: Document, baseUrl: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        val cleaned = body.replace("\\/", "/").replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true).replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")

        Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
            .findAll(cleaned).forEach { m -> fixUrl(m.value, baseUrl)?.let { candidates.add(it) } }
        Regex("""(?i)(?:https?:)?//[^\s"'<>\\]*embedsnglys[^\s"'<>\\]*""")
            .findAll(cleaned).forEach { m -> fixUrl(m.value, baseUrl)?.let { candidates.add(it) } }
        
        Regex("""["']?(?:url|src|file|stream|streamUrl|embedUrl|playerUrl|iframe|m3u8|source)["']?\s*[:=]\s*["']([^"']+)["']""")
            .findAll(cleaned).forEach { m ->
                fixUrl(m.groupValues[1], baseUrl)?.let { candidates.add(it) }
            }

        document.select(
            "iframe[src], iframe[data-src], video[src], video[data-src], source[src], source[data-src], [data-embed], [data-video], [data-player], [data-stream]"
        ).forEach { el ->
            sequenceOf(el.attr("src"), el.attr("data-src"), el.attr("data-url"), el.attr("data-embed"),
                el.attr("data-video"), el.attr("data-player"), el.attr("data-stream")
            ).firstOrNull { it.isNotBlank() }?.let { raw -> fixUrl(raw, baseUrl)?.takeIf { it != baseUrl }?.let { candidates.add(it) } }
        }
        return candidates
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun tryEmit(url: String) {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            val isM3u8 = path.endsWith(".m3u8")
            val isMp4 = path.endsWith(".mp4")
            if (isM3u8 || isMp4) {
                if (isM3u8 && !isPlayableHls(url, "$mainUrl/", mainUrl)) return
                if (!emitted.add(url)) return
                callback(newExtractorLink(source = name, name = name, url = url,
                    type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    this.referer = "https://beyazelma78.com/"
                    this.headers = mapOf(
                        "Origin" to "https://beyazelma78.com",
                        "Referer" to "https://beyazelma78.com/",
                        "User-Agent" to browserUserAgent
                    )
                    quality = Qualities.Unknown.value
                })
                found = true
                Log.d("Beyazelma", "AKIŞ EKLENDİ: ${url.substringBefore('?')}")
            } else {
                runCatching { loadExtractor(url, "$mainUrl/", subtitleCallback) { link -> if (emitted.add(link.url)) { callback(link); found = true } } }
            }
        }

        suspend fun scanPage(pageRaw: String, referer: String, depth: Int) {
            if (depth > 3 || visited.size >= 20) return
            val pageUrl = fixUrl(pageRaw) ?: return
            if (!visited.add(pageUrl)) return

            val response = runCatching {
                app.get(pageUrl, headers = pageHeaders + ("Referer" to referer), allowRedirects = true)
            }.getOrNull() ?: return
            val body = response.text
            val doc = response.document
            Log.d("Beyazelma", "Tarama: ${pageUrl.substringBefore('?')} (d=$depth) bodyLen=${body.length}")

            doc.select("track[src], track[data-src]").forEach { t ->
                val s = fixUrl(t.attr("src").ifBlank { t.attr("data-src") }, pageUrl) ?: return@forEach
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(s)) {
                    val lang = t.attr("srclang").ifBlank { t.attr("label") }.ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(lang, s))
                }
            }

            val candidates = collectCandidateUrls(body, doc, pageUrl)
            Log.d("Beyazelma", "HTML aday sayısı: ${candidates.size}")

            if (pageUrl.contains("/kanal/") && depth == 0) {
                val slug = runCatching { URI(pageUrl).path.substringAfterLast("/") }.getOrNull() ?: ""
                Log.d("Beyazelma", "Slug=$slug")

                // 1) Sayfa içindeki tüm script'lerde ve HTML'de "tr-" ile başlayan gizli slug'ı ara
                var embedSlug: String? = null
                val allText = body + doc.select("script:not([src])").joinToString(" ") { it.data() }
                Regex("""["'](tr-[a-z0-9-]+)["']""").findAll(allText).forEach { m ->
                    val candidate = m.groupValues[1]
                    Log.d("Beyazelma", "Gizli slug adayı (HTML/script): $candidate")
                    if (embedSlug == null) embedSlug = candidate
                }

                // 2) Embed HTML sayfasını doğru başlıklarla dene (slug bilinmiyorsa veya yedek olarak)
                if (embedSlug == null) {
                    val embedUrls = listOf(
                        "https://embed.beyazelma78.com/?slug=$slug",
                        "https://embed.beyazelma78.com/?id=$slug",
                        "https://embed.beyazelma78.com/?channel=$slug",
                        "https://embed.beyazelma78.com/$slug"
                    )
                    for (url in embedUrls) {
                        val resp = runCatching { 
                            app.get(url, headers = pageHeaders + mapOf(
                                "Referer" to "https://beyazelma78.com/",
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                            )).text 
                        }.getOrNull() ?: continue
                        
                        Log.d("Beyazelma", "EMBED denemesi: $url → len=${resp.length}")
                        if (resp.contains("StreamPlayer.mount")) {
                            Regex("""StreamPlayer\.mount\s*\(\s*['"][^'"]+['"]\s*,\s*\{.*?"slug"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(resp)?.let { m ->
                                embedSlug = m.groupValues[1]
                                Log.d("Beyazelma", "Embed slug bulundu ($url): $embedSlug")
                                break
                            }
                        }
                    }
                }

                // 3) player.js içindeki mount parametrelerini ara (yedek)
                if (embedSlug == null) {
                    val playerJsUrl = "https://embed.beyazelma78.com/player.js"
                    val playerJs = runCatching {
                        app.get(playerJsUrl, headers = pageHeaders + mapOf("Referer" to "https://beyazelma78.com/"), allowRedirects = true).text
                    }.getOrNull()
                    
                    if (playerJs != null) {
                        Log.d("Beyazelma", "player.js indirildi, boyut: ${playerJs.length}")
                        Regex("""StreamPlayer\.mount\s*\(\s*['"][^'"]+['"]\s*,\s*\{.*?"slug"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                            .find(playerJs)?.let { m ->
                                embedSlug = m.groupValues[1]
                                Log.d("Beyazelma", "Embed slug player.js'den bulundu: $embedSlug")
                            }
                    }
                }

                // 4) embedSlug bulunduysa /api/play/{embedSlug} çağır
                if (embedSlug != null) {
                    val apiPlayUrl = "https://embed.beyazelma78.com/api/play/$embedSlug"
                    Log.d("Beyazelma", "API Play URL: $apiPlayUrl")
                    val apiResp = runCatching {
                        app.get(apiPlayUrl, headers = pageHeaders + mapOf(
                            "Referer" to "https://embed.beyazelma78.com/",
                            "Origin" to "https://embed.beyazelma78.com"
                        )).text
                    }.getOrNull()

                    if (apiResp != null) {
                        Log.d("Beyazelma", "API yanıtı: ${apiResp.take(500)}")
                        try {
                            val json = JSONObject(apiResp)
                            val primary = json.optString("primary", "")
                            if (primary.isNotBlank()) {
                                Log.d("Beyazelma", "Primary akış bulundu: $primary")
                                candidates.add(primary)
                            }
                            val fallbacks = json.optJSONArray("fallbacks")
                            if (fallbacks != null) {
                                for (i in 0 until fallbacks.length()) {
                                    val fallback = fallbacks.optString(i, "")
                                    if (fallback.isNotBlank()) {
                                        Log.d("Beyazelma", "Fallback akış bulundu: $fallback")
                                        candidates.add(fallback)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Beyazelma", "JSON parse hatası: ${e.message}")
                        }
                    }
                }
            }

            Log.d("Beyazelma", "Toplam aday: ${candidates.size}")
            for (cand in candidates.take(60)) {
                tryEmit(cand)
                if (!cand.endsWith(".m3u8") && !cand.endsWith(".mp4") && depth < 3) {
                    scanPage(cand, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$mainUrl/", 0)
        if (!found) Log.w("Beyazelma", "Bağlantı bulunamadı. Denenen sayfa=${visited.size}")
        return found
    }
}
