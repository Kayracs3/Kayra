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

            // Önce sayfa kaynağında token'lı m3u8 ara
            extractM3u8(pageDoc.html())?.let { url ->
                callback(buildLink(label, url, dembedOrigin + "/"))
                return true
            }

            // Iframe zincirini takip et
            var iframeUrl = pageDoc.selectFirst("iframe[src]")?.attr("src")
            var referer = streamPageUrl
            var depth = 0

            while (!iframeUrl.isNullOrBlank() && depth < 3) {
                val doc = try {
                    app.get(iframeUrl, headers = mapOf("Referer" to referer, "Origin" to dembedOrigin, "User-Agent" to ua)).document
                } catch (_: Exception) { return false }

                extractM3u8(doc.html())?.let { url ->
                    callback(buildLink(label, url, dembedOrigin + "/"))
                    return true
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

    private suspend fun buildLink(label: String, url: String, referer: String): ExtractorLink {
        // URL'de token varsa doğrudan kullan, yoksa resolveM3u8 dene
        val finalUrl = if (url.contains("?t=")) {
            url
        } else {
            resolveM3u8(url, referer) ?: url
        }

        return newExtractorLink(
            source = this.name,
            name = "${this.name} - $label",
            url = finalUrl,
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

    /** Ana listeyi indirip içindeki varyant (media) m3u8 URL'sini bulur. */
    private suspend fun resolveM3u8(masterUrl: String, referer: String): String? {
        return try {
            val content = app.get(masterUrl, headers = mapOf("Referer" to referer, "Origin" to dembedOrigin, "User-Agent" to ua)).text
            if (content.contains("#EXT-X-STREAM-INF")) {
                val lines = content.lines()
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        for (j in i + 1 until lines.size) {
                            val line = lines[j].trim()
                            if (line.isNotBlank() && !line.startsWith("#")) {
                                return if (line.startsWith("http")) line
                                else masterUrl.substringBeforeLast("/") + "/" + line
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * HTML içinde m3u8 arar. Öncelik sırası:
     * 1. Token'lı (?t=...&_=...)
     * 2. Token'lı (?t=...)
     * 3. Token'sız
     */
    private fun extractM3u8(html: String): String? {
        // 1. Tam token formatı
        Regex("""(https?://[^\s"'<>]+\.m3u8\?t=[^\s"'<>]+&_=[^\s"'<>]+)""")
            .find(html)?.value?.let { return it }
        // 2. Sadece t parametresi
        Regex("""(https?://[^\s"'<>]+\.m3u8\?t=[^\s"'<>]+)""")
            .find(html)?.value?.let { return it }
        // 3. Token'sız
        Regex("""(https?://[^\s"'<>]+\.m3u8)""")
            .find(html)?.value?.let { return it }
        return null
    }
}
