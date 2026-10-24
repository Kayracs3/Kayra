package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PstronSH : MainAPI() {
    override var mainUrl = "https://patronsaha1.cfd"
    override var name = "PatronSaha"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val streamHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // ==================== ANA SAYFA ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/page/$page/", headers = streamHeaders).document
        val items = doc.select("div.video-item, article, .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList("Latest", items))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3, h2, .title, .video-title")?.text() ?: return null
        val img = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = fixUrlNull(img)
        }
    }

    // ==================== ARAMA ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = streamHeaders).document
        return doc.select("div.video-item, article, .item").mapNotNull { it.toSearchResult() }
    }

    // ==================== YÜKLEME ====================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = streamHeaders).document
        val title = doc.selectFirst("h1, .video-title")?.text() ?: "Untitled"
        val poster = doc.selectFirst("video")?.attr("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // ==================== LİNKLER (DÜZELTİLMİŞ) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = streamHeaders).document
        val html = doc.html()

        // Tüm adayları topla: (url, referer)
        val candidates = mutableListOf<Pair<String, String>>()

        // 1) baseurl + video slug -> https://{sub}.{hash}.cfd/patron/{slug}.m3u8
        val baseUrl = Regex("""baseurl\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val videoSlug = Regex("""(?:videoId|vid|slug|id)\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: extractSlugFromUrl(data)

        if (baseUrl != null && videoSlug != null) {
            candidates += "$baseUrl/patron/$videoSlug.m3u8" to data
        }

        // 2) Doğrudan m3u8 / iframe / video kaynakları
        doc.select("video source, video, iframe[src], a[href]").forEach { el ->
            val raw = when (el.tagName()) {
                "source" -> el.attr("src")
                "video" -> el.attr("src").ifBlank { el.attr("data-src") }
                "iframe" -> el.attr("src")
                else -> el.attr("href")
            }
            if (raw.isNotBlank()) {
                val full = fixUrl(raw)
                if (full.contains(".m3u8") || full.contains(".mp4") || full.contains("bsky")) {
                    candidates += full to data
                }
            }
        }

        // 3) HTML içinde gömülü m3u8'ler
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(html)
            .forEach { candidates += it.value to data }

        // 4) bsky reklam linklerini ayıkla, tekrarları sil
        val filtered = candidates
            .filter { (u, _) -> !isAdUrl(u) }
            .distinctBy { it.first }

        Log.d("Patron", "Toplam: ${candidates.size}, geçerli aday: ${filtered.size}")

        // 5) Her adayı test et
        for ((url, referer) in filtered) {
            if (url.contains(".m3u8")) {
                if (!isPlayableHls(url, referer, mainUrl)) {
                    Log.w("Patron", "HLS oynatılamıyor: $url")
                    continue
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = streamHeaders
                    }
                )
                return true
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.headers = streamHeaders
                    }
                )
                return true
            }
        }

        Log.w("Patron", "Geçerli kaynak bulunamadı")
        return false
    }

    private fun extractSlugFromUrl(url: String): String? {
        // https://patronsaha1.cfd/xyz/mono/ -> "mono"
        return Regex("""/([^/]+)/?$""").find(url.trimEnd('/'))?.groupValues?.get(1)
    }

    // ==================== REKLAM FİLTRESİ (DÜZELTİLMİŞ) ====================
    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        val regex = Regex(
            "(sayac|ads?|banner|reklam|analytics|googletagmanager|beacon|chat\\.js|/cdn-cgi/|" +
            "preroll|pre-roll|mid-roll|post-roll|vast|vpaid|ima|doubleclick|" +
            "googlesyndication|adserver|advertisement|commercial|promo|sponsor|" +
            "popunder|popads|trafficjunky|exoclick|juicyads|/ad/|/ads/|bsky|video\\.bsky)"
        )
        return regex.containsMatchIn(u)
    }

    // ==================== HLS TESTİ (DÜZELTİLMİŞ) ====================
    private suspend fun isPlayableHls(url: String, referer: String, origin: String): Boolean {
        // 1) Sadece User-Agent (bazı CDN'ler Origin/Referer görünce 403 döner)
        runCatching {
            app.get(url, headers = mapOf("User-Agent" to browserUserAgent), allowRedirects = true)
        }.getOrNull()?.let { r ->
            if (r.code in 200..299) {
                val body = r.text.trim()
                if (body.startsWith("#EXTM3U") &&
                    (body.contains(".ts") || body.contains(".m4s") || body.contains("EXT-X-STREAM-INF"))
                ) {
                    Log.d("Patron", "HLS OK (minimal UA): $url")
                    return true
                }
            }
        }

        // 2) User-Agent + Referer + Origin
        val full = streamHeaders + mapOf("Referer" to referer, "Origin" to origin)
        runCatching {
            app.get(url, headers = full, allowRedirects = true)
        }.getOrNull()?.let { r ->
            if (r.code in 200..299) {
                val body = r.text.trim()
                if (body.startsWith("#EXTM3U") &&
                    (body.contains(".ts") || body.contains(".m4s") || body.contains("EXT-X-STREAM-INF"))
                ) {
                    Log.d("Patron", "HLS OK (full headers): $url")
                    return true
                }
            }
        }

        // 3) User-Agent + sadece Referer
        runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to browserUserAgent, "Referer" to referer),
                allowRedirects = true
            )
        }.getOrNull()?.let { r ->
            if (r.code in 200..299) {
                val body = r.text.trim()
                if (body.startsWith("#EXTM3U") &&
                    (body.contains(".ts") || body.contains(".m4s") || body.contains("EXT-X-STREAM-INF"))
                ) {
                    Log.d("Patron", "HLS OK (UA+Referer): $url")
                    return true
                }
            }
        }

        Log.w("Patron", "HLS FAIL: $url")
        return false
    }
}
