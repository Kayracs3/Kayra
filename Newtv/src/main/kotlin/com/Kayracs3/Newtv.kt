package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class BeyazElma : MainAPI() {
    override var mainUrl = "https://beyazelma78.com"
    override var name = "BeyazElma"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }

    private val apiChannels = "$mainUrl/api/channels"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        apiChannels to "Tüm Kanallar",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val resp = runCatching {
            app.get(apiChannels, headers = headers, referer = "$mainUrl/")
        }.getOrNull()
        if (resp == null) { Log.e(name, "channels API bağlantı hatası"); return emptyList() }
        if (!resp.isSuccessful) { Log.e(name, "channels API HTTP ${resp.code}"); return emptyList() }

        val arr = runCatching { JSONArray(resp.text) }.getOrNull()
            ?: runCatching { JSONObject(resp.text).optJSONArray("channels") }.getOrNull()
            ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val nameCh = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val logo = obj.optString("logo").takeIf { it.isNotBlank() }?.let { absolute(it, mainUrl) }
            if (!obj.optBoolean("isLive", true)) continue
            results.add(newLiveSearchResponse(nameCh, slug, TvType.Live) { this.posterUrl = logo })
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val v = query.trim()
        if (v.length < 2) return emptyList()
        return fetchChannels().filter { it.name.contains(v, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/").substringBefore("?").trim()
        if (slug.isBlank()) return null
        return newLiveStreamLoadResponse(name = "Kanal", url = url, dataUrl = slug)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.trim()
        if (slug.isBlank()) return false

        val channelPageUrl = "$mainUrl/kanal/$slug"
        Log.d(name, "loadLinks: slug=$slug, url=$channelPageUrl")

        val pageResp = runCatching {
            app.get(channelPageUrl, headers = mapOf("User-Agent" to USER_AGENT), referer = "$mainUrl/")
        }.getOrNull() ?: return false
        if (!pageResp.isSuccessful) { Log.e(name, "Kanal sayfası HTTP ${pageResp.code}"); return false }

        val html = pageResp.text.replace("\\/", "/")
        Log.d(name, "Kanal HTML: ${html.length} char")

        // 1) Doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { m3u8 -> emitLink(m3u8, channelPageUrl, callback); return true }

        // 2) /api/embed URL'sini bul
        val embedUrl = findEmbedUrl(html, channelPageUrl)
        if (!embedUrl.isNullOrBlank()) {
            Log.d(name, "Embed URL: $embedUrl")
            if (processEmbed(embedUrl, channelPageUrl, callback)) return true
        } else {
            Log.e(name, "/api/embed URL'si bulunamadı")
        }

        Log.e(name, "Yayın linki bulunamadı: $slug")
        return false
    }

    private fun findEmbedUrl(html: String, baseUrl: String): String? {
        // 1) __NEXT_DATA__ içinde
        val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!nextData.isNullOrBlank()) {
            Regex("""["'](/api/embed\?[^"']+)["']""").find(nextData)?.groupValues?.getOrNull(1)?.let {
                return absolute(it, baseUrl)
            }
        }

        // 2) Tüm <script> içeriklerinde
        Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val script = m.groupValues[1]
            Regex("""["'](/api/embed\?[^"']+)["']""").find(script)?.groupValues?.getOrNull(1)?.let {
                return absolute(it, baseUrl)
            }
            Regex("""["'](https?://[^"']*?/api/embed\?[^"']+)["']""").find(script)?.groupValues?.getOrNull(1)?.let {
                return it
            }
        }

        // 3) RSC payload içinde
        Regex("""self\.__next_f\.push\(\[1,"([\s\S]*?)"\]\)""").findAll(html).forEach { m ->
            val payload = m.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
            Regex("""["'](/api/embed\?[^"']+)["']""").find(payload)?.groupValues?.getOrNull(1)?.let {
                return absolute(it, baseUrl)
            }
        }

        return null
    }

    private suspend fun processEmbed(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name, "Embed çekiliyor: $embedUrl")
        val embedResp = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = referer,
            )
        }.getOrNull() ?: return false
        if (!embedResp.isSuccessful) { Log.e(name, "Embed HTTP ${embedResp.code}"); return false }

        val embedHtml = embedResp.text.replace("\\/", "/").replace("\\\"", "\"")
        Log.d(name, "Embed HTML: ${embedHtml.length} char")
        Log.d(name, "Embed ilk 500: ${embedHtml.take(500)}")

        // Doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.value?.let { m3u8 -> emitLink(m3u8, embedUrl, callback); return true }

        // JSON alanları
        for (p in listOf(
            """"file"\s*:\s*"([^"]+)"""",
            """"source"\s*:\s*"([^"]+)"""",
            """"stream"\s*:\s*"([^"]+)"""",
            """"src"\s*:\s*"([^"]+)"""",
            """"hls"\s*:\s*"([^"]+)"""",
        )) {
            Regex(p, RegexOption.IGNORE_CASE).find(embedHtml)?.groupValues?.getOrNull(1)?.let { u ->
                if (u.contains(".m3u8", true) || u.startsWith("http")) {
                    emitLink(u, embedUrl, callback); return true
                }
            }
        }

        // Nested iframe
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)?.let { nested ->
                val nestedUrl = absolute(nested, embedUrl)
                Log.d(name, "Nested iframe: $nestedUrl")
                return runCatching { processEmbed(nestedUrl, embedUrl, callback) }.getOrDefault(false)
            }

        // Embed içinde __NEXT_DATA__
        val embedNextData = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)
        if (!embedNextData.isNullOrBlank()) {
            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .find(embedNextData)?.value?.let { m3u8 -> emitLink(m3u8, embedUrl, callback); return true }
        }

        Log.d(name, "Embed içinde m3u8 bulunamadı")
        return false
    }

    private suspend fun emitLink(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(newExtractorLink(source = name, name = name, url = url, type = ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to mainUrl,
            )
        })
        Log.d(name, "★ Link eklendi: $url")
    }

    private fun absolute(raw: String, base: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { URI(base).resolve(v).toString() }
            .getOrElse { base.trimEnd('/') + "/" + v.trimStart('/') }
    }
}
