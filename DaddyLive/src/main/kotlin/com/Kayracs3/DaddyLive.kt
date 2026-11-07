package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    // CDN'in izin verdiği origin (response header'dan)
    private val dembedOrigin = "https://dembed.st"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/24-7-channels.php").document
        val channels = document.select("a[href*='/watch.php?id=']").mapNotNull { it.toLiveSearchResponse() }
        return newHomePageResponse(request.name, channels)
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse? {
        val title = text().trim(); val href = attr("href")
        if (title.isBlank() || href.isBlank()) return null
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        return newLiveSearchResponse(title, fullUrl, TvType.Live)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document
        return newLiveStreamLoadResponse(url = url, name = doc.title(), dataUrl = url)
    }

    // ============================================================
    // ANA MANTIK: 7 player yolu × çoklu iframe derinliği
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        val id = watchUrl.substringAfter("id=", "").substringBefore("&")
        if (id.isBlank()) return false

        // 7 farklı player yolu
        val playerPaths = listOf("stream", "cast", "watch", "plus", "casting", "player", "hub")

        for (path in playerPaths) {
            val streamPageUrl = "$mainUrl/$path/stream-$id.php"
            val found = tryToExtract(streamPageUrl, watchUrl, path, callback)
            if (found) return true
        }
        return false
    }

    /**
     * Bir player sayfasını açar, iframe'lerin içine iner, token'lı m3u8 arar.
     */
    private suspend fun tryToExtract(
        streamPageUrl: String,
        watchUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // 1. Player sayfasını çek
            val pageDoc = app.get(
                streamPageUrl,
                headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to ua
                )
            ).document

            // 2. Sayfa kaynağında token'lı/token'sız m3u8 var mı?
            extractM3u8(pageDoc.html())?.let { url ->
                callback(buildLink(label, url, dembedOrigin + "/"))
                return true
            }

            // 3. Iframe varsa içine gir (derinlik 3'e kadar)
            var iframeUrl = pageDoc.selectFirst("iframe[src]")?.attr("src")
            var referer = streamPageUrl
            var depth = 0

            while (!iframeUrl.isNullOrBlank() && depth < 3) {
                val doc = try {
                    app.get(
                        iframeUrl,
                        headers = mapOf(
                            "Referer" to referer,
                            "Origin" to dembedOrigin,
                            "User-Agent" to ua
                        )
                    ).document
                } catch (_: Exception) { return false }

                // Iframe içinde m3u8
                extractM3u8(doc.html())?.let { url ->
                    callback(buildLink(label, url, dembedOrigin + "/"))
                    return true
                }

                // Alt iframe'e in
                val nested = doc.selectFirst("iframe[src]")?.attr("src")
                if (!nested.isNullOrBlank()) {
                    referer = iframeUrl!!
                    iframeUrl = nested
                    depth++
                } else {
                    break
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    // ExtractorLink oluşturma — DOĞRU Origin: dembed.st
    // ============================================================
    private suspend fun buildLink(label: String, url: String, referer: String): ExtractorLink {
        return newExtractorLink(
            source = this.name,
            name = "${this.name} - $label",
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.headers = mapOf(
                "Referer" to referer,
                "Origin" to dembedOrigin,      // ✅ CDN'in izin verdiği origin
                "User-Agent" to ua,
                "Accept" to "*/*"
            )
        }
    }

    /**
     * HTML içinde m3u8 arar. Önce token'lı (?t=...), sonra token'sız.
     */
    private fun extractM3u8(html: String): String? {
        // 1. Token'lı: .m3u8?t=...&_=...
        Regex("""(https?://[^\s"'<>]+\.m3u8\?[^\s"'<>]*t=[^\s"'<>]*)""")
            .find(html)?.value?.let { return it }
        // 2. Token'sız: .m3u8
        Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            .find(html)?.value?.let { return it }
        return null
    }
}
