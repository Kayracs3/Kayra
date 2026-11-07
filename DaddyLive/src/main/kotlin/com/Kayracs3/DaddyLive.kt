package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li" // Güncel domain
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
        // Hata düzeltmesi: Üçüncü parametre TvType olmalı
        return newLiveSearchResponse(title, fullUrl, TvType.Live)
    }

    // 2. Kanal detayı: watch.php sayfasından yayın linkini çıkarır
    override suspend fun load(url: String): LoadResponse? {
        // watch.php sayfasını çek
        val document = app.get(
            url,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0")
        ).document

        var streamUrl: String? = null

        // a) Sayfada doğrudan bir iframe var mı?
        val iframeSrc = document.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            // Iframe'in içine girip m3u8 ara
            streamUrl = extractM3u8FromPage(iframeSrc, url)
        }

        // b) Iframe yoksa veya m3u8 bulunamadıysa, sayfa kaynağında m3u8 ara
        if (streamUrl.isNullOrBlank()) {
            streamUrl = extractM3u8FromHtml(document.html())
        }

        // c) Hâlâ bulunamadıysa, stream-{id}.php formatını dene
        if (streamUrl.isNullOrBlank()) {
            val id = url.substringAfter("id=", "")
            if (id.isNotBlank()) {
                val streamPageUrl = "$mainUrl/stream/stream-$id.php"
                try {
                    val streamDoc = app.get(
                        streamPageUrl,
                        headers = mapOf("Referer" to url, "User-Agent" to "Mozilla/5.0")
                    ).document
                    val streamIframe = streamDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!streamIframe.isNullOrBlank()) {
                        streamUrl = extractM3u8FromPage(streamIframe, url)
                    }
                    if (streamUrl.isNullOrBlank()) {
                        streamUrl = extractM3u8FromHtml(streamDoc.html())
                    }
                } catch (_: Exception) { /* Sayfa açılamazsa yoksay */ }
            }
        }

        if (streamUrl.isNullOrBlank()) return null

        // Hata düzeltmesi: apiName parametresi kaldırıldı
        return newLiveStreamLoadResponse(
            url = url,
            name = document.title(),
            dataUrl = streamUrl
        )
    }

    // Yardımcı: Bir URL'yi açıp içinden m3u8 çıkarır (iframe için)
    private suspend fun extractM3u8FromPage(pageUrl: String, referer: String): String? {
        return try {
            val doc = app.get(pageUrl, headers = mapOf("Referer" to referer)).document
            extractM3u8FromHtml(doc.html())
        } catch (_: Exception) {
            null
        }
    }

    // Yardımcı: HTML metninde m3u8 arar
    private fun extractM3u8FromHtml(html: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(html)?.value
    }
}
