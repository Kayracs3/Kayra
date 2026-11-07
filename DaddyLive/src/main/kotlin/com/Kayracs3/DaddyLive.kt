package com.daddylive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://dlhd.pk"       // Ana domain (zamanla değişebilir)
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // Kanal listesini ana sayfadan veya bir API endpoint'inden çeker.
    // Site yapısı değiştiğinde buradaki CSS seçicileri güncellemeniz gerekir.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        // Örnek seçici: kanal kartları "a" etiketi içinde "stream-" linki barındırır.
        val channelElements = document.select("a[href*='/stream/stream-']")
        val home = channelElements.mapNotNull { it.toLiveSearchResponse() }
        return newHomePageResponse(HomePageList("Live Channels", home))
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse? {
        val title = this.text().trim().ifBlank { this.attr("title").trim() }
        val href = this.attr("href")
        if (title.isBlank() || href.isBlank()) return null
        return newLiveSearchResponse(title, href, this@DaddyLiveProvider.name)
    }

    // Seçilen kanal sayfasına gidip yayın URL'sini (m3u8) çıkarır.
    // Bu kısım sitenin token/header mantığına göre uyarlanmalıdır.
    override suspend fun load(url: String): LoadResponse? {
        // 1. Kanal sayfasını aç (ör. https://dlhd.pk/stream/stream-tnt-sports-1-uk.php)
        val pageResponse = app.get(url, headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        ))
        val document = pageResponse.document

        // 2. Sayfa içindeki oynatıcı script'ini veya iframe'ini bul.
        // Çoğu dlhd sitesi "player" veya "embed" içeren bir iframe kullanır.
        val iframeUrl = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("script:containsData(player)")?.data()
                ?.let { extractUrlFromScript(it) }

        if (iframeUrl.isNullOrBlank()) {
            // Eğer doğrudan m3u8 yoksa, dlhd API'sine istek atmayı deneyin.
            // Örnek: https://dlhd.pk/stream/stream-tnt-sports-1-uk.php?api=1 gibi bir endpoint olabilir.
            // Bu kısım sitenin gerçek API'sine göre düzenlenmelidir.
            val apiUrl = extractApiEndpoint(document)
            if (apiUrl != null) {
                val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to url)).text
                val m3u8 = extractM3u8(apiResponse)
                if (m3u8 != null) {
                    return newLiveStreamLoadResponse(
                        url = url,
                        name = document.title(),
                        dataUrl = m3u8,
                        headers = mapOf("Referer" to url, "User-Agent" to "Mozilla/5.0")
                    )
                }
            }
            return null
        }

        // 3. Iframe içinden m3u8'i çıkar (genellikle iframe içinde başka bir script olur).
        val m3u8Url = extractM3u8FromIframe(iframeUrl)
        if (m3u8Url.isNullOrBlank()) return null

        return newLiveStreamLoadResponse(
            url = url,
            name = document.title(),
            dataUrl = m3u8Url,
            headers = mapOf(
                "Referer" to iframeUrl,
                "Origin" to "https://dlhd.pk",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        )
    }

    // Yardımcı fonksiyonlar (sitenin gerçek yapısına göre düzenlenmeli)

    private fun extractUrlFromScript(script: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(script)?.value
    }

    private fun extractApiEndpoint(doc: org.jsoup.nodes.Document): String? {
        // Örnek: sayfada "data-api" veya "fetch(" ile başlayan bir endpoint arayın.
        val script = doc.select("script").joinToString("\n") { it.data() }
        val regex = Regex("""(https?://[^\s"']*/api/[^\s"']+)""")
        return regex.find(script)?.value
    }

    private fun extractM3u8(text: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(text)?.value
    }

    private suspend fun extractM3u8FromIframe(iframeUrl: String): String? {
        return try {
            val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl)).document
            val scriptData = iframeDoc.select("script").joinToString("\n") { it.data() }
            val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            regex.find(scriptData)?.value
        } catch (e: Exception) {
            null
        }
    }
}