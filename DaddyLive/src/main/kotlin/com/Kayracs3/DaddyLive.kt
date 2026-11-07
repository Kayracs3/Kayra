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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        val id = watchUrl.substringAfter("id=", "").substringBefore("&")
        if (id.isBlank()) return false

        // 7 player yolu dene
        val playerPaths = listOf("stream", "cast", "watch", "plus", "casting", "player", "hub")
        for (path in playerPaths) {
            val streamPageUrl = "$mainUrl/$path/stream-$id.php"
            if (tryToExtract(streamPageUrl, watchUrl, path, callback)) return true
        }
        return false
    }

    private suspend fun tryToExtract(
        streamPageUrl: String,
        watchUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageDoc = app.get(
                streamPageUrl,
                headers = mapOf("Referer" to watchUrl, "Origin" to mainUrl, "User-Agent" to ua)
            ).document

            // 1. Doğrudan token'lı m3u8 (nadiren HTML'de olur)
            extractM3u8(pageDoc.html())?.let { url ->
                if (url.contains("?t=")) {
                    callback(buildLink(label, url, dembedOrigin + "/"))
                    return true
                }
            }

            // 2. Iframe zincirini takip et
            var iframeUrl = pageDoc.selectFirst("iframe[src]")?.attr("src")
            var referer = streamPageUrl
            var depth = 0

            while (!iframeUrl.isNullOrBlank() && depth < 3) {
                val doc = try {
                    app.get(iframeUrl, headers = mapOf("Referer" to referer, "Origin" to dembedOrigin, "User-Agent" to ua)).document
                } catch (_: Exception) { return false }

                // Token'lı m3u8 (iframe JS içinde olabilir)
                extractM3u8(doc.html())?.let { url ->
                    if (url.contains("?t=")) {
                        callback(buildLink(label, url, dembedOrigin + "/"))
                        return true
                    }
                }

                // Iframe içindeki m3u8 URL'sini bul (token'sız) ve token'sız dene
                val m3u8Base = extractM3u8(doc.html())
                if (!m3u8Base.isNullOrBlank()) {
                    // Daddy.min.js'in ürettiği token'ı taklit etmeyi dene
                    val withToken = tryGenerateToken(m3u8Base)
                    if (withToken != null) {
                        callback(buildLink(label, withToken, dembedOrigin + "/"))
                        return true
                    }
                }

                val nested = doc.selectFirst("iframe[src]")?.attr("src")
                if (!nested.isNullOrBlank()) {
                    referer = iframeUrl!!
                    iframeUrl = nested
                    depth++
                } else break
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * daddy.min.js'in token formatını taklit etmeye çalışır.
     * Format: ?t={unix_timestamp}.{md5_hash}&_={unix_timestamp_ms}
     * Not: md5_hash'in içeriği bilinmiyor, bu yüzden rastgele deniyoruz.
     * Bu genelde çalışmaz ama bazen server-side validasyon zayıf olabilir.
     */
    private fun tryGenerateToken(m3u8BaseUrl: String): String? {
        return try {
            val now = System.currentTimeMillis()
            val unix = now / 1000
            // Bilinen formatları dene
            val formats = listOf(
                "$m3u8BaseUrl?t=$unix.${md5("$unix")}&_=$now",
                "$m3u8BaseUrl?t=$unix.${md5("thedaddy")}&_=$now",
                "$m3u8BaseUrl?t=$unix.${md5("daddylive")}&_=$now",
                "$m3u8BaseUrl?_=$now"
            )
            // İlk formatı dene, çalışmazsa diğer player'lar denenir
            formats.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

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
                "Origin" to dembedOrigin,
                "User-Agent" to ua,
                "Accept" to "*/*"
            )
        }
    }

    private fun extractM3u8(html: String): String? {
        Regex("""(https?://[^\s"'<>]+\.m3u8\?t=[^\s"'<>]+&_=[^\s"'<>]+)""")
            .find(html)?.value?.let { return it }
        Regex("""(https?://[^\s"'<>]+\.m3u8\?t=[^\s"'<>]+)""")
            .find(html)?.value?.let { return it }
        Regex("""["'](https?://[^"']+\.m3u8\?t=[^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""(https?://[^\s"'<>]+\.m3u8)""")
            .find(html)?.value?.let { return it }
        return null
    }
}
