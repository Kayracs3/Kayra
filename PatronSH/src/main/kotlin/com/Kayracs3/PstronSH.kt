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

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    // ==================== ANA SAYFA ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val standard = doc
            .select(".video, .item, article, .thumb, .post, .entry, .movie-item, .thumb-block")
            .mapNotNull { it.toSearchResult() }

        val items = if (standard.isNotEmpty()) standard else {
            // Fallback: resim içeren tüm linkleri tara
            val seen = mutableSetOf<String>()
            doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return@mapNotNull null
                val img = a.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
                if (img.isNullOrBlank()) return@mapNotNull null
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (title.length < 2) return@mapNotNull null
                if (!seen.add(href)) return@mapNotNull null
                newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                    this.posterUrl = fixUrlNull(img)
                }
            }
        }
        return newHomePageResponse("Latest", items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return null

        val title = selectFirst("h3, h2, .title, .video-title, .name")?.text()?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: linkEl.text().ifBlank { null }
            ?: return null

        val img = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
        }

        return newMovieSearchResponse(title.trim(), fixUrl(href), TvType.NSFW) {
            this.posterUrl = fixUrlNull(img)
        }
    }

    // ==================== ARAMA ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to userAgent)).document
        return doc
            .select(".video, .item, article, .thumb, .post, .entry, .movie-item, .thumb-block")
            .mapNotNull { it.toSearchResult() }
    }

    // ==================== YÜKLEME ====================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("h1, .video-title, .title")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("video")?.attr("poster")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // ==================== LİNKLER ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
        val html = doc.html()

        val candidates = mutableListOf<Pair<String, String>>()

        // 1) baseurl + videoId -> {baseurl}/patron/{videoId}.m3u8
        val baseUrl = Regex("""baseurl\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val videoId = Regex("""(?:videoId|vid|slug|video|file|id)\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""/([^/]+)/?$""").find(data.trimEnd('/'))?.groupValues?.get(1)

        if (baseUrl != null && videoId != null) {
            candidates += "$baseUrl/patron/$videoId.m3u8" to data
        }

        // 2) HTML içindeki tüm m3u8 URL'leri
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(html).forEach { candidates += it.value to data }

        // 3) <video> / <source> / <iframe> etiketleri
        doc.select("video, video source, iframe[src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }
            if (raw.isNotBlank()) {
                val full = fixUrl(raw)
                if (full.contains(".m3u8") || full.contains(".mp4")) {
                    candidates += full to data
                }
            }
        }

        // 4) Reklamları (bsky dahil) çıkar, tekrarları sil
        val filtered = candidates.filter { !isAdUrl(it.first) }.distinctBy { it.first }
        Log.d("Patron", "toplam=${candidates.size} gecerli=${filtered.size}")

        // 5) Her adayı test et; yalnızca gerçekten açılanı kullanıcıya göster
        for ((url, referer) in filtered) {
            if (url.contains(".m3u8")) {
                if (!isPlayableHls(url, referer)) {
                    Log.w("Patron", "HLS testi basarisiz, atlaniyor: $url")
                    continue
                }
                callback(newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = mapOf("User-Agent" to userAgent)
                })
                return true
            } else {
                callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = mapOf("User-Agent" to userAgent)
                })
                return true
            }
        }
        Log.w("Patron", "Gecerli kaynak bulunamadi")
        return false
    }

    // ==================== REKLAM FİLTRESİ ====================
    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("bsky") || u.contains("video.bsky")) return true
        return Regex(
            "(sayac|ads?|banner|reklam|analytics|googletagmanager|beacon|chat\\.js|/cdn-cgi/|" +
            "preroll|pre-roll|mid-roll|post-roll|vast|vpaid|ima|doubleclick|" +
            "googlesyndication|adserver|advertisement|commercial|promo|sponsor|" +
            "popunder|popads|trafficjunky|exoclick|juicyads|/ad/|/ads/)"
        ).containsMatchIn(u)
    }

    // ==================== HLS TESTİ ====================
    private suspend fun isPlayableHls(url: String, referer: String): Boolean {
        // 3 farklı header kombinasyonu dene — 403'ü aşmak için
        val attempts = listOf(
            mapOf("User-Agent" to userAgent),
            mapOf("User-Agent" to userAgent, "Referer" to referer),
            mapOf("User-Agent" to userAgent, "Referer" to referer, "Origin" to mainUrl)
        )
        for (h in attempts) {
            val r = runCatching {
                app.get(url, headers = h, allowRedirects = true)
            }.getOrNull() ?: continue
            if (r.code !in 200..299) continue
            val body = r.text.trim()
            if (body.startsWith("#EXTM3U") &&
                (body.contains(".ts") || body.contains(".m4s") || body.contains("EXT-X-STREAM-INF"))
            ) {
                Log.d("Patron", "HLS OK: $url")
                return true
            }
        }
        return false
    }
}
