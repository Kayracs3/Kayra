package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
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
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
    )

    // ★★★ API TABANLI ARAMA (doğrulandı) ★★★
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

        // Filmler
        json.optJSONArray("movies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val poster = item.optString("poster").replace("\\/", "/").takeIf { it.isNotBlank() }
                results.add(newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster })
            }
        }

        // Diziler
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

    // ★★★ DETAY SAYFASI ★★★
    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url) ?: return null
        val title = pageTitle(document, url) ?: return null
        val poster = pagePoster(document, url)
        val plot = pagePlot(document)
        val year = pageYear(document, title)
        val genres = pageGenres(document)

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        // Dizi: bölüm listesi statik HTML'de yok, şimdilik tek bir "İzle" bölümü ekliyoruz.
        // Gerçek sezon/bölüm listesi için site içi JS API'si incelenmeli.
        val episodes = listOf(
            newEpisode(url) { this.name = "İzle" }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    // ★★★ OYNATICI ★★★
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = absolute(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        val document = getDocument(sourceUrl) ?: return false
        var found = false

        // 1) HTML içindeki doğrudan m3u8/mp4
        val html = document.html().replace("\\/", "/")
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                callback.invoke(newExtractorLink(source = name, name = name, url = m.value, type = ExtractorLinkType.M3U8) {
                    this.referer = sourceUrl
                    this.quality = Qualities.P1080.value
                })
                found = true
            }

        // 2) iframe'leri tara
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = absolute(iframe.attr("src"), sourceUrl)
            if (iframeUrl.isNotBlank()) {
                val iframeDoc = getDocument(iframeUrl) ?: return@forEach
                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .findAll(iframeDoc.html().replace("\\/", "/")).forEach { m ->
                        callback.invoke(newExtractorLink(source = name, name = name, url = m.value, type = ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                            this.quality = Qualities.P1080.value
                        })
                        found = true
                    }
            }
        }

        // 3) player shell veri özniteliği (varsa)
        document.select("[data-player-shell]").forEach { el ->
            val playerData = el.attr("data-player-shell")
            if (playerData.isNotBlank()) {
                Log.d(name, "Player shell verisi: $playerData")
                // Burada playerData içindeki JSON'dan video linki çıkarılabilir.
            }
        }

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

    private fun pageTitle(document: Document, url: String): String? {
        return document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().takeIf { it.isNotBlank() }
            ?: pathOf(url).substringAfterLast("/")
    }

    private fun pagePoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.let { absolute(it, baseUrl) }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
    }

    private fun pageYear(document: Document, title: String): Int? {
        for (script in document.select("script[type=application/ld+json]")) {
            val text = script.data()
            Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun pageGenres(document: Document): List<String> {
        val list = mutableListOf<String>()
        document.select("a[href*='/tur/']").forEach { list.add(it.text().trim()) }
        return list.distinct().take(20)
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun pathOf(url: String): String = runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }.getOrDefault("")
}
