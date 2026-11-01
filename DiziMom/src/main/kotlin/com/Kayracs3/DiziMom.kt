package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziMom : MainAPI() {
    override var mainUrl = "https://ddizimom.net"
    override var name = "DiziMom"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler?sirala=yeni" to "Yeni Eklenen Filmler",
        "$mainUrl/diziler?sirala=yeni" to "Yeni Eklenen Diziler",
        "$mainUrl/filmler" to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val address = if (page <= 1) request.data else request.data.trimEnd('/') + "?page=$page"
        val document = getDocument(address)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = parseCatalog(document, address)
        val next = page < 20 && results.isNotEmpty()
        Log.d(name, "Katalog ${request.name}: ${results.size} kayıt")
        return newHomePageResponse(request.name, results, hasNext = next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()

        val encoded = URLEncoder.encode(value, "UTF-8")
        val apiUrl = "$mainUrl/ara/oneri?q=$encoded"

        val response = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/",
                ),
                referer = "$mainUrl/",
                timeout = 15000,
                allowRedirects = true,
            )
        }.getOrNull()

        if (response == null || !response.isSuccessful) {
            Log.e(name, "Arama API başarısız: ${response?.code}")
            return emptyList()
        }

        val json = runCatching { org.json.JSONObject(response.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        json.optJSONArray("movies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val poster = item.optString("poster").replace("\\/", "/").takeIf { it.isNotBlank() }
                results.add(newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster })
            }
        }

        json.optJSONArray("series")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val poster = item.optString("poster").replace("\\/", "/").takeIf { it.isNotBlank() }
                results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster })
            }
        }

        Log.d(name, "Arama API: '${value}' için ${results.size} sonuç")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url) ?: return null
        val title = pageTitle(document, url) ?: return null
        val poster = pagePoster(document, url)
        val plot = pagePlot(document)
        val year = pageYear(document, title)
        val genres = pageGenres(document)
        val rating = pageRating(document)
        val actors = pageActors(document)

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                rating?.let { score = Score.from10(it) }
                if (actors.isNotEmpty()) addActors(actors.map { Actor(it) })
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonLinks = document.select("div.flex.flex-wrap.gap-2 a[href*='?sezon=']")
        Log.d(name, "Bulunan sezon linki sayısı: ${seasonLinks.size}")

        if (seasonLinks.isNotEmpty()) {
            for (seasonLink in seasonLinks) {
                val href = seasonLink.attr("href").trim()
                if (href.isBlank()) continue
                val seasonUrl = absolute(href, url).substringBefore("#")
                val seasonNum = Regex("""sezon=(\d+)""").find(seasonUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                Log.d(name, "Sezon URL: $seasonUrl, Sezon No: $seasonNum")
                val seasonDoc = getDocument(seasonUrl) ?: continue
                val seasonEpisodes = parseEpisodes(seasonDoc, seasonUrl, seasonNum ?: 1)
                episodes.addAll(seasonEpisodes)
            }
        } else {
            val defaultEpisodes = parseEpisodes(document, url, 1)
            episodes.addAll(defaultEpisodes)
        }

        Log.d(name, "Toplam bölüm sayısı: ${episodes.size}")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            rating?.let { score = Score.from10(it) }
            if (actors.isNotEmpty()) addActors(actors.map { Actor(it) })
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String, defaultSeason: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val selectedEpisodeLinks =
            document.select("div.grid.grid-cols-2.gap-4 a[href*='/sezon-'][href*='/bolum-']")
        val episodeLinks = if (selectedEpisodeLinks.isNotEmpty()) {
            selectedEpisodeLinks
        } else {
            document.select("a[href*='/sezon-'][href*='/bolum-']")
        }
        Log.d(name, "parseEpisodes: ${episodeLinks.size} bölüm linki bulundu (base: $baseUrl)")

        for (link in episodeLinks) {
            val href = link.attr("href").trim()
            if (href.isBlank()) continue

            val fullUrl = absolute(href, baseUrl)
            val match = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(fullUrl) ?: continue
            val seasonNum = match.groupValues[1].toIntOrNull() ?: defaultSeason
            val episodeNum = match.groupValues[2].toIntOrNull() ?: continue

            val episodeName = link.selectFirst("p.text-sm.font-semibold")?.text()?.trim()
                ?: link.text().trim().ifBlank { "Bölüm $episodeNum" }

            val episodePoster = imageFrom(link, baseUrl)
            val episode = newEpisode(fullUrl) {
                this.name = episodeName
                this.season = seasonNum
                this.episode = episodeNum
                this.posterUrl = episodePoster
            }
            episodes.add(episode)
        }
        return episodes
    }

    // ★★★ DÜZELTİLMİŞ loadLinks ★★★
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = absolute(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        Log.d(name, "loadLinks başladı: $sourceUrl")
        var found = false

        fun emitLink(link: String, referer: String) {
            if (link.isBlank()) return
            val type = when {
                link.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                link.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback.invoke(newExtractorLink(source = name, name = name, url = link, type = type) {
                this.referer = referer
                this.quality = Qualities.P1080.value
            })
            found = true
            Log.d(name, "★ Link eklendi: $link")
        }

        val document = getDocument(sourceUrl) ?: run {
            Log.e(name, "Bölüm sayfası çekilemedi: $sourceUrl")
            return false
        }

        val html = document.html().replace("\\/", "/")
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { emitLink(it.value, sourceUrl) }

        for (script in document.select("script")) {
            val inline = script.data()
            if (inline.isNotBlank()) {
                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .findAll(inline).forEach { emitLink(it.value, sourceUrl) }
                Regex("""["'](?:file|src|url|source|stream|playlist|manifest|hls)["']\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(inline).forEach { m ->
                        val candidate = m.groupValues[1]
                        if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true) || candidate.contains(".mpd", true))
                            emitLink(absolute(candidate, sourceUrl), sourceUrl)
                    }
            }

            val src = script.attr("src").trim()
            if (src.isNotBlank()) {
                val scriptUrl = absolute(src, sourceUrl)
                val scriptResp = runCatching {
                    app.get(scriptUrl, headers = headers + mapOf("Referer" to sourceUrl), referer = sourceUrl, timeout = 10000)
                }.getOrNull()
                if (scriptResp != null && scriptResp.isSuccessful) {
                    val scriptBody = scriptResp.text.replace("\\/", "/")
                    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                        .findAll(scriptBody).forEach { emitLink(it.value, scriptUrl) }
                    Regex("""["'](?:file|src|url|source|stream|playlist|manifest|hls)["']\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(scriptBody).forEach { m ->
                            val candidate = m.groupValues[1]
                            if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true) || candidate.contains(".mpd", true))
                                emitLink(absolute(candidate, scriptUrl), scriptUrl)
                        }
                }
            }
        }

        document.select("iframe[src], iframe[data-src], video[src], video source[src], source[src], source[data-src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }
            if (raw.isBlank()) return@forEach
            val full = absolute(raw, sourceUrl)
            if (full.isBlank()) return@forEach
            if (el.tagName().equals("iframe", true)) {
                val iframeResp = runCatching {
                    app.get(full, headers = headers + mapOf("Referer" to sourceUrl), referer = sourceUrl, timeout = 10000)
                }.getOrNull()
                if (iframeResp != null && iframeResp.isSuccessful) {
                    val iframeBody = iframeResp.text.replace("\\/", "/")
                    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                        .findAll(iframeBody).forEach { emitLink(it.value, full) }
                    Regex("""["'](?:file|src|url|source|stream|playlist|manifest|hls)["']\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(iframeBody).forEach { m ->
                            val candidate = m.groupValues[1]
                            if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true) || candidate.contains(".mpd", true))
                                emitLink(absolute(candidate, full), full)
                        }
                }
            } else if (full.contains(".m3u8", true) || full.contains(".mp4", true) || full.contains(".mpd", true)) {
                emitLink(full, sourceUrl)
            }
        }

        document.select("[data-player-shell]").forEach { el ->
            val raw = el.attr("data-player-shell")
            if (raw.isNotBlank()) {
                Log.d(name, "data-player-shell bulundu: ${raw.take(300)}")
                val decoded = raw.replace("&quot;", "\"").replace("\\/", "/")
                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .findAll(decoded).forEach { emitLink(it.value, sourceUrl) }
                Regex("""["'](?:file|src|url|source|stream|playlist|manifest|hls)["']\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(decoded).forEach { m ->
                        val candidate = m.groupValues[1]
                        if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true) || candidate.contains(".mpd", true))
                            emitLink(absolute(candidate, sourceUrl), sourceUrl)
                    }
            }
        }

        if (!found) Log.e(name, "Hiçbir oynatma linki bulunamadı.")
        return found
    }

    // --- Yardımcı fonksiyonlar ---

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            val response = app.get(url, headers = headers + ("Referer" to referer), referer = referer, timeout = 15000, allowRedirects = true)
            if (response.isSuccessful) response.document else null
        } catch (e: Exception) {
            Log.e(name, "Sayfa hatası: $url; ${e.message}")
            null
        }
    }

    private fun parseCatalog(document: Document, baseUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in document.select("a[href]")) {
            val url = absolute(link.attr("href"), baseUrl).substringBefore("#")
            if (url.isBlank()) continue
            if (!url.contains("/film/") && !url.contains("/dizi/")) continue

            val card = findCard(link)
            val poster = imageFrom(link, baseUrl) ?: imageFrom(card, baseUrl)
            val title = listOf(
                link.attr("title"), link.attr("aria-label"),
                link.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
                link.selectFirst("img")?.attr("alt").orEmpty(),
                link.text(),
                card.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
            ).firstOrNull { it.isNotBlank() }?.trim() ?: continue
            if (title.length < 2) continue

            val key = canonical(url)
            val item = if (url.contains("/film/")) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            }
            found.putIfAbsent(key, item)
        }
        return found.values.toList()
    }

    private fun findCard(link: Element): Element {
        var best = link
        var current: Element? = link
        repeat(3) {
            val item = current ?: return@repeat
            if (item.selectFirst("img") != null) best = item
            val parent = item.parent() ?: return@repeat
            if (parent.select("a[href]").size > 10) return@repeat
            current = parent
            best = parent
        }
        return best
    }

    private fun imageFrom(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", true)) element else element.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        return if (src.isNotBlank()) absolute(src, baseUrl) else null
    }

    private fun pageTitle(document: Document, url: String): String? {
        return document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().takeIf { it.isNotBlank() }
            ?: pathOf(url).substringAfterLast("/")
    }

    private fun pagePoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.let { absolute(it, baseUrl) }
            ?: document.selectFirst(".poster img, .detail-poster img")?.let { imageFrom(it, baseUrl) }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .plot, .summary")?.text()?.trim()
    }

    private fun detailValue(document: Document, label: String): String? {
        val row = document.select("dl.divide-y.text-sm > div").firstOrNull {
            it.selectFirst("dt")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        } ?: return null
        return row.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun pageYear(document: Document, title: String): Int? {
        val value = detailValue(document, "Yapım Yılı")
        Regex("""(19\d{2}|20\d{2})""").find(value.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        for (script in document.select("script[type=application/ld+json]")) {
            val text = script.data()
            Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})")
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? {
        val value = detailValue(document, "IMDB Puanı") ?: return null
        val rating = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""")
            .find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
        return rating?.takeIf { it in 0.0..10.0 }
    }

    private fun pageGenres(document: Document): List<String> {
        val detailGenre = detailValue(document, "Tür")
        if (!detailGenre.isNullOrBlank()) {
            return detailGenre.split(',', '·')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)
        }

        val list = mutableListOf<String>()
        document.select("a[href*='/tur/'], .genres a, .genre a").forEach {
            list.add(it.text().trim())
        }
        return list.filter { it.isNotBlank() }.distinct().take(20)
    }

    private fun pageActors(document: Document): List<String> {
        val actors = LinkedHashSet<String>()
        document.select("#cast-grid > a[href*='/oyuncu/'], #cast-grid a[href*='/oyuncu/']")
            .forEach { card ->
                val name = card.selectFirst("p[title]")?.attr("title")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("p")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                        ?.takeIf { it.isNotBlank() }

                if (!name.isNullOrBlank()) actors.add(name)
            }
        return actors.take(50).toList()
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun canonical(url: String): String = absolute(url, mainUrl).substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun pathOf(url: String): String = runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }.getOrDefault("")
}
