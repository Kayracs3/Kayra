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
        return newHomePageResponse(request.name, fetchChannels(), hasNext = false)
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val resp = runCatching {
            app.get(apiChannels, headers = headers, referer = "$mainUrl/")
        }.getOrNull() ?: return emptyList()
        if (!resp.isSuccessful) return emptyList()

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
        Log.d(name, "loadLinks: slug=$slug")

        val pageResp = runCatching {
            app.get(channelPageUrl, headers = mapOf("User-Agent" to USER_AGENT), referer = "$mainUrl/")
        }.getOrNull() ?: return false
        if (!pageResp.isSuccessful) return false

        val html = pageResp.text.replace("\\/", "/")
        Log.d(name, "Kanal HTML: ${html.length} char")

        // 1) Sayfada doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { m3u8 -> emitLink(m3u8, channelPageUrl, callback); return true }

        // 2) Embed URL'sini bul (artık mutlak URL)
        val embedUrl = findEmbedUrl(html)
        if (embedUrl.isNullOrBlank()) {
            Log.e(name, "Embed URL bulunamadı")
            return false
        }
        Log.d(name, "Embed URL: $embedUrl")

        // 3) Embed sayfasını çek
        val embedResp = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to channelPageUrl,
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = channelPageUrl,
            )
        }.getOrNull() ?: return false
        if (!embedResp.isSuccessful) { Log.e(name, "Embed HTTP ${embedResp.code}"); return false }

        val embedHtml = embedResp.text.replace("\\/", "/").replace("\\\"", "\"")
        Log.d(name, "Embed HTML: ${embedHtml.length} char")

        // 4) Embed içinde doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.value?.let { m3u8 -> emitLink(m3u8, embedUrl, callback); return true }

        // 5) JWPlayer setup config
        if (processJwPlayer(embedHtml, embedUrl, callback)) return true

        // 6) __NEXT_DATA__ içinde JWPlayer
        val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)
        if (!nextData.isNullOrBlank() && processJwPlayer(nextData, embedUrl, callback)) return true

        // 7) JSON alanları
        for (p in listOf(
            """"file"\s*:\s*"([^"]+)"""",
            """"source"\s*:\s*"([^"]+)"""",
            """"stream"\s*:\s*"([^"]+)"""",
            """"hls"\s*:\s*"([^"]+)"""",
        )) {
            Regex(p, RegexOption.IGNORE_CASE).find(embedHtml)?.groupValues?.getOrNull(1)?.let { u ->
                if (u.contains(".m3u8", true) || u.startsWith("http")) {
                    emitLink(u, embedUrl, callback); return true
                }
            }
        }

        // 8) Nested iframe
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)?.let { nested ->
                val nestedUrl = absolute(nested, embedUrl)
                Log.d(name, "Nested iframe: $nestedUrl")
                val nestedResp = runCatching {
                    app.get(nestedUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl), referer = embedUrl)
                }.getOrNull()
                if (nestedResp != null && nestedResp.isSuccessful) {
                    val nestedHtml = nestedResp.text.replace("\\/", "/").replace("\\\"", "\"")
                    Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                        .find(nestedHtml)?.value?.let { m3u8 -> emitLink(m3u8, nestedUrl, callback); return true }
                    if (processJwPlayer(nestedHtml, nestedUrl, callback)) return true
                }
            }

        // 9) Son çare: URL dökümü
        Log.e(name, "Yayın linki bulunamadı. Embed HTML URL'leri:")
        Regex("""(?:https?:)?//[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(embedHtml)
            .map { it.value }
            .filter { it.length in 10..500 }
            .distinct()
            .take(80)
            .forEach { Log.d(name, "EMBED URL: $it") }

        return false
    }

    /**
     * Embed URL'sini bulur. Her zaman mainUrl'e göre çözer (kanal sayfasına göre değil)
     * ve sondaki ters slash / kaçış karakterlerini temizler.
     */
    private fun findEmbedUrl(html: String): String? {
        // Ters slash temizleyici
        fun clean(url: String): String = url.trim().trimEnd('\\', '"', '\'', '/', ' ')

        // 1) /api/embed?u=...  (relatif, mainUrl'e göre)
        Regex("""["'](/api/embed\?[^"'\\]+)["'\\]?""").find(html)?.groupValues?.getOrNull(1)?.let {
            return "$mainUrl${clean(it)}"
        }

        // 2) https://beyazelma78.com/api/embed?u=...  (mutlak)
        Regex("""["'](https?://[^"'\\]*?/api/embed\?[^"'\\]+)["'\\]?""").find(html)?.groupValues?.getOrNull(1)?.let {
            return clean(it)
        }

        // 3) /kanal/{slug}/api/embed?u=...  (yanlış formda gelirse düzelt)
        Regex("""["'](/kanal/[^"'\\]+/api/embed\?[^"'\\]+)["'\\]?""").find(html)?.groupValues?.getOrNull(1)?.let {
            val path = clean(it)
            val fixed = path.replace(Regex("""^/kanal/[^/]+"""), "")
            return "$mainUrl$fixed"
        }

        // 4) Doğrudan iframe
        Regex("""<iframe[^>]+src=["']([^"'\\]+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let {
            return absolute(clean(it), mainUrl)
        }

        return null
    }

    private suspend fun processJwPlayer(
        source: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val setupBlock = Regex("""\.setup\s*\(\s*(\{[\s\S]*?\})\s*\)""").find(source)?.groupValues?.getOrNull(1)
            ?: Regex("""setup\s*\(\s*(\{[\s\S]*?"(?:playlist|sources|file|mediaid|key)"[\s\S]*?\})\s*\)""").find(source)?.groupValues?.getOrNull(1)
        if (setupBlock.isNullOrBlank()) {
            return processJwConfig(source, referer, callback)
        }
        Log.d(name, "JWPlayer setup bulundu (${setupBlock.length} char)")
        Log.d(name, "JWPlayer setup: ${setupBlock.take(600)}")
        return processJwConfig(setupBlock, referer, callback)
    }

    private suspend fun processJwConfig(
        config: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // 1) Doğrudan file
        Regex(""""file"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)?.let { fileUrl ->
            if (fileUrl.contains(".m3u8", true) || fileUrl.contains(".mp4", true)) {
                emitLink(fileUrl, referer, callback); return true
            }
        }

        // 2) sources[].file
        Regex(""""sources"\s*:\s*\[([\s\S]*?)]""").find(config)?.groupValues?.getOrNull(1)?.let { sourcesBlock ->
            Regex(""""file"\s*:\s*"([^"]+)"""").find(sourcesBlock)?.groupValues?.getOrNull(1)?.let { src ->
                if (src.contains(".m3u8", true) || src.contains(".mp4", true)) {
                    emitLink(src, referer, callback); return true
                }
            }
        }

        // 3) playlist URL'si
        Regex(""""playlist"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)?.let { playlistUrl ->
            Log.d(name, "JWPlayer playlist URL: $playlistUrl")
            val resolved = absolute(playlistUrl, referer)
            val playlistResp = runCatching {
                app.get(resolved, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), referer = referer)
            }.getOrNull()
            if (playlistResp != null && playlistResp.isSuccessful) {
                val playlistBody = playlistResp.text.replace("\\/", "/")
                Regex(""""file"\s*:\s*"([^"]+)"""").find(playlistBody)?.groupValues?.getOrNull(1)?.let { src ->
                    if (src.contains(".m3u8", true) || src.contains(".mp4", true)) {
                        emitLink(src, resolved, callback); return true
                    }
                }
                if (processJwConfig(playlistBody, resolved, callback)) return true
            }
        }

        // 4) mediaid
        val mediaid = Regex(""""mediaid"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
            ?: Regex(""""mediaId"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)

        if (mediaid != null) {
            for (url in listOf(
                "https://cdn.jwplayer.com/v2/media/$mediaid",
                "https://cdn.jwplayer.com/v2/playlists/$mediaid",
                "https://content.jwplatform.com/feeds/$mediaid.json",
            )) {
                val r = runCatching {
                    app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), referer = referer)
                }.getOrNull() ?: continue
                if (!r.isSuccessful) continue
                val body = r.text.replace("\\/", "/")
                Regex(""""file"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)?.let { src ->
                    if (src.contains(".m3u8", true) || src.contains(".mp4", true)) {
                        emitLink(src, url, callback); return true
                    }
                }
            }
        }

        // 5) key + pid
        val key = Regex(""""key"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
        val pid = Regex(""""pid"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)

        if (key != null && pid != null) {
            val mediaIdOnly = key.substringBefore("/")
            val signature = key.substringAfter("/", "")
            Log.d(name, "JW key → mediaId=$mediaIdOnly, pid=$pid")
            for (url in listOf(
                "https://cdn.jwplayer.com/v2/media/$mediaIdOnly?sig=$signature",
                "https://cdn.jwplayer.com/v2/media/$mediaIdOnly?token=$signature",
                "https://cdn.jwplayer.com/v2/playlists/$mediaIdOnly?sig=$signature",
                "https://content.jwplatform.com/feeds/$mediaIdOnly.json?sig=$signature",
                "https://cdn.jwplayer.com/v2/media/$key",
            )) {
                val r = runCatching {
                    app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), timeout = 10000)
                }.getOrNull() ?: continue
                if (!r.isSuccessful) { Log.d(name, "JW HTTP ${r.code}: $url"); continue }
                val body = r.text.replace("\\/", "/")
                Log.d(name, "JW yanıt: ${body.take(300)}")
                Regex(""""file"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)?.let { src ->
                    if (src.contains(".m3u8", true) || src.contains(".mp4", true)) {
                        emitLink(src, url, callback); return true
                    }
                }
            }
        }

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
        val v = raw.trim().replace("\\", "").trimEnd('"', '\'', ' ')
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { URI(base).resolve(v).toString() }
            .getOrElse { base.trimEnd('/') + "/" + v.trimStart('/') }
    }
}
