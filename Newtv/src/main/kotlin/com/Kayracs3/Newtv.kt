package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

class NewTV : MainAPI() {
    override var mainUrl = "https://tv.newtane.com"
    override var name = "NewLive"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val API_BASE = "https://newtvproxy.newtane.com"
        private const val DLHD_BASE = "https://dlhd.pk"
        private const val EMBED_BASE = "https://dembed.st"
        private const val EDGE_BASE = "https://edge.wedd48c.top"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf("$API_BASE/api/channels" to "Tüm Kanallar")

    private fun extractChannelId(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t
        return Regex("""(\d+)/?$""").find(t)?.groupValues?.getOrNull(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        Log.d(name, "getMainPage: ${channels.size} kanal")
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val resp = runCatching {
            app.get("$API_BASE/api/channels", headers = headers, referer = "$mainUrl/")
        }.getOrNull() ?: return emptyList()
        if (!resp.isSuccessful) return emptyList()
        val json = runCatching { JSONArray(resp.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val id = obj.optString("channel_id").takeIf { it.isNotBlank() } ?: continue
            val nm = obj.optString("channel_name").takeIf { it.isNotBlank() } ?: continue
            results.add(newLiveSearchResponse(nm, id, TvType.Live) { this.posterUrl = null })
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val v = query.trim()
        if (v.length < 2) return emptyList()
        return fetchChannels().filter { it.name.contains(v, true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = extractChannelId(url) ?: return null
        return newLiveStreamLoadResponse(name = "Kanal $id", url = url, dataUrl = id)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channelId = extractChannelId(data) ?: return false
        Log.d(name, "loadLinks: channelId=$channelId")

        val dlhdUrl = "$DLHD_BASE/stream/stream-$channelId.php"
        val resp = runCatching {
            app.get(
                dlhdUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$DLHD_BASE/"),
                referer = "$DLHD_BASE/",
                allowRedirects = true,
                timeout = 20000
            )
        }.getOrNull() ?: return false
        if (!resp.isSuccessful) { Log.e(name, "dlhd HTTP ${resp.code}"); return false }

        val html = resp.text.replace("\\/", "/")
        Log.d(name, "dlhd HTML: ${html.length} char")

        // iframe'i bul
        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (iframeSrc.isNullOrBlank()) {
            Log.e(name, "iframe bulunamadı")
            return tryDirectOrToken(html, dlhdUrl, channelId, callback)
        }

        val iframeUrl = absolute(iframeSrc, dlhdUrl)
        Log.d(name, "iframe: $iframeUrl")

        val iframeResp = runCatching {
            app.get(
                iframeUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to dlhdUrl, "Accept" to "text/html,*/*"),
                referer = dlhdUrl,
                timeout = 20000,
                allowRedirects = true
            )
        }.getOrNull()
        if (iframeResp == null || !iframeResp.isSuccessful) {
            Log.e(name, "iframe çekilemedi: ${iframeResp?.code}")
            return false
        }

        val iframeHtml = iframeResp.text.replace("\\/", "/").replace("\\\"", "\"")
        Log.d(name, "iframe HTML: ${iframeHtml.length} char")

        // 1) Token'lı m3u8 doğrudan var mı? (index.m3u8?t=...)
        Regex("""https?://[^\s"'<>\\]+?/index\.m3u8\?[^\s"'<>\\]*t=[^\s"'<>\\&]+[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(iframeHtml)?.value?.let { fullM3u8 ->
                emitLink(fullM3u8, iframeUrl, callback)
                return true
            }

        // 2) playlist-token URL'sini iframe HTML'inden tam olarak çıkar
        val tokenUrlPatterns = listOf(
            Regex("""["'](https?://[^"']*?/api/playlist-token\?slug=[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+?/api/playlist-token\?slug=[^\s"'<>\\&]+)""", RegexOption.IGNORE_CASE),
            Regex("""["'](/api/playlist-token\?slug=[^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        val tokenUrls = mutableSetOf<String>()
        for (p in tokenUrlPatterns) {
            p.findAll(iframeHtml).forEach { m ->
                val raw = m.groupValues.lastOrNull() ?: m.value
                val cleaned = raw.replace("&amp;", "&").trim()
                if (cleaned.isNotBlank()) tokenUrls.add(absolute(cleaned, iframeUrl))
            }
        }
        Log.d(name, "iframe içinde bulunan token URL'leri: $tokenUrls")

        for (tokenUrl in tokenUrls) {
            val tokenResp = runCatching {
                app.get(
                    tokenUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to iframeUrl,
                        "Origin" to EMBED_BASE,
                        "Accept" to "application/json, text/plain, */*",
                    ),
                    referer = iframeUrl,
                    timeout = 15000,
                )
            }.getOrNull() ?: continue
            if (!tokenResp.isSuccessful) { Log.d(name, "token URL HTTP ${tokenResp.code}: $tokenUrl"); continue }

            val tokenBody = tokenResp.text
            Log.d(name, "token yanıtı: ${tokenBody.take(300)}")
            val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenBody)?.groupValues?.getOrNull(1)
                ?: Regex("""["']([A-Za-z0-9_\-\.]{30,})["']""").find(tokenBody)?.groupValues?.getOrNull(1)
            if (!token.isNullOrBlank()) {
                val ts = System.currentTimeMillis()
                val m3u8 = "$EDGE_BASE/premium$channelId/index.m3u8?t=" +
                        java.net.URLEncoder.encode(token, "UTF-8") + "&_=$ts"
                emitLink(m3u8, iframeUrl, callback)
                return true
            }
        }

        // 3) Token endpoint'lerini doğrudan dene (iframe'de bulunamadıysa)
        val slug = Regex("""premium(\d+)""").find(iframeHtml)?.value ?: "premium$channelId"
        val directTokenUrls = listOf(
            "$EDGE_BASE/api/playlist-token?slug=$slug",
            "$EDGE_BASE/api/playlist-token?slug=$channelId",
        )
        for (tokenUrl in directTokenUrls) {
            val tokenResp = runCatching {
                app.get(
                    tokenUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to iframeUrl,
                        "Origin" to EMBED_BASE,
                        "Accept" to "application/json, text/plain, */*",
                    ),
                    referer = iframeUrl,
                    timeout = 15000,
                )
            }.getOrNull() ?: continue
            if (!tokenResp.isSuccessful) { Log.d(name, "token endpoint HTTP ${tokenResp.code}: $tokenUrl"); continue }

            val tokenBody = tokenResp.text
            Log.d(name, "token yanıtı ($tokenUrl): ${tokenBody.take(300)}")
            val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenBody)?.groupValues?.getOrNull(1)
                ?: Regex("""["']([A-Za-z0-9_\-\.]{30,})["']""").find(tokenBody)?.groupValues?.getOrNull(1)
            if (!token.isNullOrBlank()) {
                val ts = System.currentTimeMillis()
                val m3u8 = "$EDGE_BASE/$slug/index.m3u8?t=" +
                        java.net.URLEncoder.encode(token, "UTF-8") + "&_=$ts"
                emitLink(m3u8, iframeUrl, callback)
                return true
            }
        }

        // 4) iframe HTML'inde token'sız index.m3u8 varsa
        Regex("""https?://[^\s"'<>\\]+?/index\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(iframeHtml)?.value?.let { m3u8 ->
                emitLink(m3u8, iframeUrl, callback)
                return true
            }

        Log.e(name, "stream bulunamadı: id=$channelId")
        return false
    }

    private suspend fun tryDirectOrToken(html: String, referer: String, channelId: String, callback: (ExtractorLink) -> Unit): Boolean {
        Regex("""https?://[^\s"'<>\\]+?/index\.m3u8\?[^\s"'<>\\]*t=[^\s"'<>\\&]+[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { m3u8 -> emitLink(m3u8, referer, callback); return true }
        Regex("""https?://[^\s"'<>\\]+?/index\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { m3u8 -> emitLink(m3u8, referer, callback); return true }
        return false
    }

    private suspend fun emitLink(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        // ★ Origin başlığı artık embed.st (iframe'in gerçek origin'i)
        callback.invoke(newExtractorLink(source = name, name = name, url = url, type = ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to EMBED_BASE,
            )
        })
        Log.d(name, "★ Link eklendi: $url")
    }

    private fun absolute(raw: String, base: String): String {
        val v = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { java.net.URI(base).resolve(v).toString() }.getOrElse { base + "/" + v.removePrefix("/") }
    }
}
