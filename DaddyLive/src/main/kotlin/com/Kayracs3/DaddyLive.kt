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

    // 1. Ana sayfa: 24-7-channels.php sayfasından kanalları çeker
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/24-7-channels.php").document
        val channelElements = document.select("a[href*='/watch.php?id=']")
        val home = channelElements.mapNotNull { it.toLiveSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse? {
        val title = this.text().trim()
        val href = this.attr("href")
        if (title.isBlank() || href.isBlank()) return null
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        return newLiveSearchResponse(title, fullUrl, TvType.Live)
    }

    // 2. Kanal detayı: load() sadece kanal bilgisini döndürür.
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("Referer" to mainUrl)).document
        return newLiveStreamLoadResponse(
            url = url,
            name = document.title(),
            dataUrl = url // Yayın linki loadLinks() içinde çıkarılacak
        )
    }

    // 3. YAYIN LİNKİ ÇIKARMA: loadLinks() fonksiyonu implement edildi.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        var m3u8Url: String? = null

        // a) watch.php sayfasındaki iframe'i bul ve içine gir
        val watchDoc = app.get(watchUrl, headers = mapOf("Referer" to mainUrl)).document
        val iframeSrc = watchDoc.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val iframeDoc = app.get(iframeSrc, headers = mapOf("Referer" to watchUrl)).document
            // iframe içindeki script'lerde m3u8 ara
            m3u8Url = extractM3u8(iframeDoc.html())
            // iframe içinde başka bir iframe varsa onu da dene
            if (m3u8Url.isNullOrBlank()) {
                val nestedIframe = iframeDoc.selectFirst("iframe[src]")?.attr("src")
                if (!nestedIframe.isNullOrBlank()) {
                    val nestedDoc = app.get(nestedIframe, headers = mapOf("Referer" to iframeSrc)).document
                    m3u8Url = extractM3u8(nestedDoc.html())
                }
            }
        }

        // b) watch.php sayfasında doğrudan m3u8 ara
        if (m3u8Url.isNullOrBlank()) {
            m3u8Url = extractM3u8(watchDoc.html())
        }

        // c) watch.php?id=XXX -> stream-XXX.php dönüşümünü dene
        if (m3u8Url.isNullOrBlank()) {
            val id = watchUrl.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                val streamUrl = "$mainUrl/stream/stream-$id.php"
                try {
                    val streamDoc = app.get(streamUrl, headers = mapOf("Referer" to watchUrl)).document
                    val streamIframe = streamDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!streamIframe.isNullOrBlank()) {
                        val streamIframeDoc = app.get(streamIframe, headers = mapOf("Referer" to streamUrl)).document
                        m3u8Url = extractM3u8(streamIframeDoc.html())
                    }
                    if (m3u8Url.isNullOrBlank()) {
                        m3u8Url = extractM3u8(streamDoc.html())
                    }
                } catch (_: Exception) { /* yoksay */ }
            }
        }

        // Bulunan m3u8 linkini ExtractorLink olarak geri döndür
        if (!m3u8Url.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.headers = mapOf(
                        "Referer" to watchUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0"
                    )
                }
            )
        }
        return true
    }

    private fun extractM3u8(html: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(html)?.value
    }
}
