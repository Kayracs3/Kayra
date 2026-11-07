package com.Kayracs3

import com.lagradost.cloudstream3.newHomePageResponse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://dlhd.pk"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
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

    override suspend fun load(url: String): LoadResponse? {
        val pageResponse = app.get(
            url,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        )
        val document = pageResponse.document

        val iframeUrl = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("script:containsData(player)")?.data()
                ?.let { extractUrlFromScript(it) }

        if (iframeUrl.isNullOrBlank()) {
            val apiUrl = extractApiEndpoint(document)
            if (apiUrl != null) {
                val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to url)).text
                val m3u8 = extractM3u8(apiResponse)
                if (m3u8 != null) {
                    return newLiveStreamLoadResponse(
                        url = url,
                        name = document.title(),
                        dataUrl = m3u8
                    )
                }
            }
            return null
        }

        val m3u8Url = extractM3u8FromIframe(iframeUrl)
        if (m3u8Url.isNullOrBlank()) return null

        return newLiveStreamLoadResponse(
            url = url,
            name = document.title(),
            dataUrl = m3u8Url
        )
    }

    private fun extractUrlFromScript(script: String): String? {
        val regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        return regex.find(script)?.value
    }

    private fun extractApiEndpoint(doc: Document): String? {
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
