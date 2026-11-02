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
            val logo = obj.optString("logo_url").replace("\\/", "/").takeIf { it.isNotBlank() }

            results.add(newLiveSearchResponse(name, id, TvType.Live) {
                this.posterUrl = logo
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
        // url = channel_id
        return newLiveStreamLoadResponse(name = "Kanal $url", url = url, dataUrl = url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channelId = data.trim()
        if (channelId.isBlank()) return false

        Log.d(name, "loadLinks başladı: channelId=$channelId")

        // Olası stream endpoint'leri (tahmini)
        val candidates = listOf(
            "$API_BASE/api/channels/$channelId/stream",
            "$API_BASE/api/channels/$channelId",
            "$API_BASE/api/stream/$channelId",
            "$API_BASE/api/live/$channelId",
            "$API_BASE/api/play/$channelId",
            "$API_BASE/api/playlist/$channelId",
            "$API_BASE/api/player/$channelId",
            "$API_BASE/api/channels/$channelId/play",
            "$API_BASE/api/channels/$channelId/playlist",
            "$API_BASE/api/channels/$channelId/m3u8",
            "$API_BASE/live/$channelId.m3u8",
            "$API_BASE/channels/$channelId.m3u8",
        )

        for (endpoint in candidates) {
            val resp = runCatching {
                app.get(endpoint, headers = headers, referer = "$mainUrl/")
            }.getOrNull() ?: continue
            if (!resp.isSuccessful) { Log.d(name, "Endpoint başarısız: $endpoint (${resp.code})"); continue }

            val body = resp.text.replace("\\/", "/")
            Log.d(name, "Endpoint yanıtı ($endpoint): ${body.take(300)}")

            // 1) Doğrudan m3u8 içeriyorsa
            val m3u8 = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).find(body)?.value
            if (m3u8 != null) {
                callback.invoke(newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                })
                Log.d(name, "★ Link eklendi (doğrudan): $m3u8")
                return true
            }

            // 2) JSON içindeki stream alanları
            val patterns = listOf(
                """"stream_url"\s*:\s*"([^"]+)"""",
                """"streamUrl"\s*:\s*"([^"]+)"""",
                """"m3u8_url"\s*:\s*"([^"]+)"""",
                """"url"\s*:\s*"([^"]+)"""",
                """"stream"\s*:\s*"([^"]+)"""",
                """"m3u8"\s*:\s*"([^"]+)"""",
                """"file"\s*:\s*"([^"]+)"""",
                """"src"\s*:\s*"([^"]+)"""",
                """"link"\s*:\s*"([^"]+)"""",
            )
            for (p in patterns) {
                val m = Regex(p, RegexOption.IGNORE_CASE).find(body) ?: continue
                val u = m.groupValues[1].trim()
                if (u.isBlank()) continue
                if (!u.contains(".m3u8", true) && !u.startsWith("http")) continue
                callback.invoke(newExtractorLink(source = name, name = name, url = u, type = ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                })
                Log.d(name, "★ Link eklendi (JSON): $u")
                return true
            }
        }

        Log.e(name, "Kanal $channelId için stream URL bulunamadı")
        return false
    }
}
