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

    // ★★★ CORE.JS MEKANİZMASINA GÖRE GÜNCELLENMİŞ loadLinks ★★★
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

        suspend fun emitLink(link: String, referer: String, label: String = "") {
            if (link.isBlank()) return
            val clean = link.replace("&amp;", "&").replace("\\/", "/")
            val isStreamPhp = clean.contains("/api/stream.php", ignoreCase = true)
            val isDirectMedia = clean.contains(".m3u8", true) || clean.contains(".mp4", true) || clean.contains(".mpd", true)
            if (!isStreamPhp && !isDirectMedia) return
            val type = when {
                isStreamPhp || clean.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                clean.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback.invoke(newExtractorLink(source = name, name = name, url = clean, type = type) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to "https://ddizimom.net",
                )
            })
            found = true
            Log.d(name, "★ Link eklendi ($label): $clean")
        }

        fun extractFromHtml(html: String, referer: String, label: String) {
            Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { found@
                    try { emitLinkSuspend(it.value, referer, label) } catch (_: Exception) {}
                }
            Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach {
                    try { emitLinkSuspend(it.value, referer, label) } catch (_: Exception) {}
                }
        }

        // suspend uyumlu emitLink sarmalayıcı
        suspend fun emitLinkSuspend(link: String, referer: String, label: String) {
            emitLink(link, referer, label)
        }

        val document = getDocument(sourceUrl) ?: return false
        val html = document.html().replace("\\/", "/")

        // 1) Statik HTML'de stream.php veya doğrudan m3u8 (bazen olur)
        Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { emitLink(it.value, sourceUrl, "statik") }
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { emitLink(it.value, sourceUrl, "statik-media") }

        // 2) data-pv slug'ını bul
        if (!found) {
            val pvSlug = document.selectFirst("[data-pv]")?.attr("data-pv")?.trim()
            Log.d(name, "data-pv slug: $pvSlug")

            // core.js src'sini bul (base URL türetmek için)
            val coreJsRaw = document.select("script[src]").firstOrNull {
                val src = it.attr("src")
                src.contains("/e/c.js") || src.contains("/assets/js/core.js")
            }?.attr("src")
            Log.d(name, "core.js src: $coreJsRaw")

            val base = when {
                !coreJsRaw.isNullOrBlank() -> {
                    val abs = absolute(coreJsRaw, sourceUrl)
                    abs.replace(Regex("/(?:e/c|assets/js/core)\\.js.*$"), "")
                }
                else -> "https://play2.pilavyerplay.top"
            }
            Log.d(name, "Player BASE: $base")

            if (!pvSlug.isNullOrBlank()) {
                // s.php URL'sini kur
                val sPhpUrl = "$base/assets/js/s.php?s=" + URLEncoder.encode(pvSlug, "UTF-8")
                Log.d(name, "s.php URL: $sPhpUrl")

                val sPhpDoc = getDocument(sPhpUrl, sourceUrl)
                if (sPhpDoc != null) {
                    val sPhpHtml = sPhpDoc.html().replace("\\/", "/")
                    Log.d(name, "s.php HTML uzunluğu: ${sPhpHtml.length}")
                    Log.d(name, "s.php ilk 800 char: ${sPhpHtml.take(800)}")

                    // s.php içinde stream.php veya m3u8 ara
                    Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(sPhpHtml).forEach { emitLink(it.value, sPhpUrl, "s.php") }
                    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                        .findAll(sPhpHtml).forEach { emitLink(it.value, sPhpUrl, "s.php-media") }

                    // s.php içindeki iframe'ler
                    sPhpDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                        val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        if (src.isBlank()) return@forEach
                        val innerUrl = absolute(src, sPhpUrl)
                        if (innerUrl.contains("/api/stream.php", true)) {
                            emitLink(innerUrl, sPhpUrl, "s.php-iframe")
                        }
                    }

                    // s.php'de script varsa src'yi de çek
                    if (!found) {
                        sPhpDoc.select("script[src]").forEach { script ->
                            val src = script.attr("src").trim()
                            if (src.isBlank()) return@forEach
                            val fullSrc = absolute(src, sPhpUrl)
                            val scriptResp = runCatching {
                                app.get(fullSrc, headers = headers + mapOf("Referer" to sPhpUrl), referer = sPhpUrl, timeout = 8000)
                            }.getOrNull()
                            if (scriptResp != null && scriptResp.isSuccessful) {
                                val body = scriptResp.text.replace("\\/", "/")
                                Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                                    .findAll(body).forEach { emitLink(it.value, fullSrc, "s.php-script") }
                                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                                    .findAll(body).forEach { emitLink(it.value, fullSrc, "s.php-script-media") }
                            }
                        }
                    }
                } else {
                    Log.e(name, "s.php çekilemedi: $sPhpUrl")
                }
            }
        }

        // 3) Son çare: sayfadaki tüm script'leri tara
        if (!found) {
            for (script in document.select("script")) {
                val src = script.attr("src").trim()
                if (src.isNotBlank()) {
                    val scriptUrl = absolute(src, sourceUrl)
                    val scriptResp = runCatching {
                        app.get(scriptUrl, headers = headers + mapOf("Referer" to sourceUrl), referer = sourceUrl, timeout = 8000)
                    }.getOrNull()
                    if (scriptResp != null && scriptResp.isSuccessful) {
                        val body = scriptResp.text.replace("\\/", "/")
                        Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                            .findAll(body).forEach { emitLink(it.value, scriptUrl, "harici-script") }
                    }
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
