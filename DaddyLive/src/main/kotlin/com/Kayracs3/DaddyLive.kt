package com.Kayracs3

import com.lagradost.cloudstream3.newHomePageResponse

import com.lagradost.cloudstream3.utils.ExtractorLink

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // ============================================================
    // 1. ANA SAYFA: 24-7-channels.php sayfasından kanalları çeker
    // ============================================================
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

    // ============================================================
    // 2. KANAL DETAYI: load() sadece kanal bilgisini döndürür
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("Referer" to mainUrl)).document
        return newLiveStreamLoadResponse(
            url = url,
            name = document.title(),
            dataUrl = url
        )
    }

    // ============================================================
    // 3. YAYIN LİNKİ ÇIKARMA: loadLinks() fonksiyonu
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        var masterM3u8Url: String? = null

        // --- 1. AŞAMA: watch.php sayfasından master m3u8 linkini bul ---
        val watchDoc = app.get(watchUrl, headers = mapOf("Referer" to mainUrl)).document
        val iframeSrc = watchDoc.selectFirst("iframe[src]")?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            val iframeDoc = app.get(iframeSrc, headers = mapOf("Referer" to watchUrl)).document
            masterM3u8Url = extractM3u8(iframeDoc.html())

            // İç içe iframe kontrolü
            if (masterM3u8Url.isNullOrBlank()) {
                val nestedIframe = iframeDoc.selectFirst("iframe[src]")?.attr("src")
                if (!nestedIframe.isNullOrBlank()) {
                    val nestedDoc = app.get(nestedIframe, headers = mapOf("Referer" to iframeSrc)).document
                    masterM3u8Url = extractM3u8(nestedDoc.html())
                }
            }
        }

        // Doğrudan watch.php sayfasında m3u8 ara
        if (masterM3u8Url.isNullOrBlank()) {
            masterM3u8Url = extractM3u8(watchDoc.html())
        }

        // stream-{id}.php formatını dene
        if (masterM3u8Url.isNullOrBlank()) {
            val id = watchUrl.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                val streamUrl = "$mainUrl/stream/stream-$id.php"
                try {
                    val streamDoc = app.get(streamUrl, headers = mapOf("Referer" to watchUrl)).document
                    val streamIframe = streamDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!streamIframe.isNullOrBlank()) {
                        val streamIframeDoc = app.get(streamIframe, headers = mapOf("Referer" to streamUrl)).document
                        masterM3u8Url = extractM3u8(streamIframeDoc.html())
                    }
                    if (masterM3u8Url.isNullOrBlank()) {
                        masterM3u8Url = extractM3u8(streamDoc.html())
                    }
                } catch (_: Exception) { /* yoksay */ }
            }
        }

        if (masterM3u8Url.isNullOrBlank()) return false

        // --- 2. AŞAMA: Ana liste ise içindeki ALT liste URL'sini bul ---
        var finalM3u8Url = masterM3u8Url
        try {
            val masterContent = app.get(
                masterM3u8Url!!,
                headers = mapOf("Referer" to watchUrl, "Origin" to mainUrl)
            ).text

            if (masterContent.contains("#EXT-X-STREAM-INF")) {
                val lines = masterContent.lines()
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        for (j in i + 1 until lines.size) {
                            val potentialUrl = lines[j].trim()
                            if (potentialUrl.isNotBlank() && !potentialUrl.startsWith("#")) {
                                finalM3u8Url = if (potentialUrl.startsWith("http")) {
                                    potentialUrl
                                } else {
                                    masterM3u8Url!!.substringBeforeLast("/") + "/" + potentialUrl
                                }
                                break
                            }
                        }
                        if (finalM3u8Url != masterM3u8Url) break
                    }
                }
            }
        } catch (_: Exception) {
            // Ana liste indirilemezse master URL'sini dene
        }

        // --- 3. AŞAMA: Oynatıcıya ExtractorLink sun ---
        if (!finalM3u8Url.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.headers = mapOf(
                        "Referer" to watchUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    this.requiresReferer = true
                }
            )
        }
        return true
    }

    // ============================================================
    // YARDIMCI FONKSİYON
    // ============================================================
    private fun extractM3u8(html: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(html)?.value
    }
}
