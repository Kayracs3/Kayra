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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$API_BASE/api/channels" to "Tüm Kanallar",
    )

    private fun extractChannelId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() }) return trimmed
        return Regex("""(\d+)/?$""").find(trimmed)?.groupValues?.getOrNull(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        Log.d(name, "getMainPage: ${channels.size} kanal")
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val resp = runCatching {
            app.get("$API_BASE/api/channels", headers = headers, referer = "$mainUrl/")
        }.getOrNull()
        if (resp == null) { Log.e(name, "Kanal listesi bağlantı hatası"); return emptyList() }
        if (!resp.isSuccessful) { Log.e(name, "Kanal listesi HTTP ${resp.code}"); return emptyList() }

        val json = runCatching { JSONArray(resp.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val id = obj.optString("channel_id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("channel_name").takeIf { it.isNotBlank() } ?: continue

            results.add(newLiveSearchResponse(name, id, TvType.Live) {
                this.posterUrl = null // Cloudflare logo'ları 403 ile engelliyor
            })
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()
        return fetchChannels().filter { it.name.contains(value, true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channelId = extractChannelId(url)
        Log.d(name, "load: gelen url=$url, channelId=$channelId")
        if (channelId == null) return null
        return newLiveStreamLoadResponse(
            name = "Kanal $channelId",
            url = url,
            dataUrl = channelId
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channelId = extractChannelId(data)
        Log.d(name, "loadLinks: gelen data=$data, channelId=$channelId")
        if (channelId == null) return false

        val dlhdUrl = "$DLHD_BASE/stream/stream-$channelId.php"
        Log.d(name, "dlhd.pk deneniyor: $dlhdUrl")

        val resp = runCatching {
            app.get(
                dlhdUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$DLHD_BASE/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = "$DLHD_BASE/",
                allowRedirects = true,
                timeout = 20000
            )
        }.getOrNull()

        if (resp == null) { Log.e(name, "dlhd.pk bağlantı hatası"); return false }
        if (!resp.isSuccessful) { Log.e(name, "dlhd.pk HTTP ${resp.code}"); return false }

        val html = resp.text.replace("\\/", "/")
        Log.d(name, "dlhd.pk HTML uzunluğu: ${html.length}")
        Log.d(name, "dlhd.pk ilk 1500 char: ${html.take(1500)}")

        // 1) Doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).find(html)?.value?.let { m3u8 ->
            emitLink(m3u8, dlhdUrl, callback)
            return true
        }

        // 2) playlist-token API'si var mı? (DaddyLive ailesinin yeni sistemi)
        val slugMatch = Regex("""playlist-token\?slug=([^&"'\s]+)""").find(html)
            ?: Regex("""stream-(?:stream-)?(\d+)\.php""").find(dlhdUrl)?.let { null }
        // dlhd.pk'de slug genelde "premium{id}" formatında olur; sayfada ipucu varsa al, yoksa varsayılan dene
        val slug = slugMatch?.groupValues?.getOrNull(1) ?: "premium$channelId"
        Log.d(name, "Olası slug: $slug")

        val tokenEndpoints = listOf(
            "https://edge.wedd48c.top/api/playlist-token?slug=$slug",
            "$DLHD_BASE/api/playlist-token?slug=$slug",
            "https://edge.wedd48c.top/api/playlist-token?slug=$channelId",
        )
        for (tokenUrl in tokenEndpoints) {
            val tokenResp = runCatching {
                app.get(tokenUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to dlhdUrl), referer = dlhdUrl, timeout = 15000)
            }.getOrNull() ?: continue
            if (!tokenResp.isSuccessful) { Log.d(name, "token endpoint başarısız: $tokenUrl (${tokenResp.code})"); continue }

            val tokenBody = tokenResp.text
            Log.d(name, "token yanıtı ($tokenUrl): ${tokenBody.take(300)}")

            val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenBody)?.groupValues?.getOrNull(1)
                ?: Regex("""["']([A-Za-z0-9_\-\.]{30,})["']""").find(tokenBody)?.groupValues?.getOrNull(1)

            if (!token.isNullOrBlank()) {
                val ts = System.currentTimeMillis()
                val m3u8 = "https://edge.wedd48c.top/$slug/index.m3u8?t=" + java.net.URLEncoder.encode(token, "UTF-8") + "&_=$ts"
                emitLink(m3u8, dlhdUrl, callback)
                return true
            }
        }

        // 3) iframe içinde başka bir player
        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!iframeSrc.isNullOrBlank()) {
            val iframeUrl = absolute(iframeSrc, dlhdUrl)
            Log.d(name, "iframe bulundu: $iframeUrl")
            val iframeResp = runCatching {
                app.get(iframeUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to dlhdUrl), referer = dlhdUrl, allowRedirects = true, timeout = 15000)
            }.getOrNull()
            if (iframeResp != null && iframeResp.isSuccessful) {
                val iframeHtml = iframeResp.text.replace("\\/", "/")
                Log.d(name, "iframe HTML (${iframeHtml.length}): ${iframeHtml.take(1000)}")
                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).find(iframeHtml)?.value?.let { m3u8 ->
                    emitLink(m3u8, iframeUrl, callback)
                    return true
                }
            }
        }

        // 4) JSON içinde stream alanı
        val patterns = listOf(
            """"stream_url"\s*:\s*"([^"]+)"""",
            """"streamUrl"\s*:\s*"([^"]+)"""",
            """"m3u8"\s*:\s*"([^"]+)"""",
            """"file"\s*:\s*"([^"]+)"""",
            """"src"\s*:\s*"([^"]+)"""",
            """"source"\s*:\s*"([^"]+)"""",
            """"link"\s*:\s*"([^"]+)"""",
        )
        for (p in patterns) {
            val m = Regex(p, RegexOption.IGNORE_CASE).find(html) ?: continue
            val u = m.groupValues[1].trim()
            if (u.contains(".m3u8", true) || u.startsWith("http")) {
                emitLink(u, dlhdUrl, callback)
                return true
            }
        }

        Log.e(name, "dlhd.pk üzerinden stream bulunamadı (id=$channelId)")
        return false
    }

    private suspend fun emitLink(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(newExtractorLink(source = name, name = name, url = url, type = ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to DLHD_BASE,
            )
        })
        Log.d(name, "★ Link eklendi: $url")
    }

    private fun absolute(raw: String, base: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { java.net.URI(base).resolve(v).toString() }.getOrElse { DLHD_BASE + "/" + v.removePrefix("/") }
    }
}
