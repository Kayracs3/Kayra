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
import java.net.URLEncoder

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

    override val mainPage = mainPageOf(apiChannels to "Tüm Kanallar")

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

        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { m3u8 -> emitLink(m3u8, channelPageUrl, callback); return true }

        val embedUrl = findEmbedUrl(html) ?: return false.also { Log.e(name, "Embed URL bulunamadı") }
        Log.d(name, "Embed URL: $embedUrl")

        val embedToken = extractQueryParam(embedUrl, "u")
        Log.d(name, "Embed token: ${embedToken?.take(40)}...")

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

        val embedHost = findEmbedHost(embedHtml) ?: "https://embed.${URI(mainUrl).host}"
        Log.d(name, "Türetilen embed host: $embedHost")

        // 1) Doğrudan m3u8
        Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.value?.let { m3u8 -> emitLink(m3u8, embedUrl, callback); return true }

        // 2) JWPlayer
        if (processJwPlayer(embedHtml, embedUrl, callback)) return true

        // 3) Inline <script> içerikleri
        Regex("""<script(?![^>]+src=)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE).findAll(embedHtml).forEach { m ->
            val inline = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .find(inline)?.value?.let { m3u8 -> emitLink(m3u8, embedUrl, callback); return true }
            if (processJwPlayer(inline, embedUrl, callback)) return true
            for (p in listOf(
                """"file"\s*:\s*"([^"]+)"""",
                """"source"\s*:\s*"([^"]+)"""",
                """"stream"\s*:\s*"([^"]+)"""",
            )) {
                Regex(p, RegexOption.IGNORE_CASE).find(inline)?.groupValues?.getOrNull(1)?.let { u ->
                    if (u.contains(".m3u8", true) || u.startsWith("http")) {
                        emitLink(u, embedUrl, callback); return true
                    }
                }
            }
            Regex("""location(?:\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(inline)?.groupValues?.getOrNull(1)?.let { loc ->
                val target = absolute(loc, embedUrl)
                if (target.contains(".m3u8", true)) { emitLink(target, embedUrl, callback); return true }
            }
        }

        // 4) iframe / video / source
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedHtml)?.groupValues?.getOrNull(1)?.let { nested ->
            for (candidate in resolveCandidates(nested, embedUrl, embedHost)) {
                Log.d(name, "Nested iframe deneniyor: $candidate")
                val r = runCatching { app.get(candidate, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl), referer = embedUrl) }.getOrNull() ?: continue
                if (!r.isSuccessful) continue
                val nestedHtml = r.text.replace("\\/", "/").replace("\\\"", "\"")
                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(nestedHtml)?.value?.let { m3u8 -> emitLink(m3u8, candidate, callback); return true }
                if (processJwPlayer(nestedHtml, candidate, callback)) return true
            }
        }

        Regex("""(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedHtml)?.groupValues?.getOrNull(1)?.let { src ->
            if (src.contains(".m3u8", true) || src.contains(".mp4", true)) {
                emitLink(absolute(src, embedUrl), embedUrl, callback); return true
            }
        }

        // 5) Script src'lerini çoklu host ile dene
        val scriptSrcs = mutableListOf<String>()
        Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(embedHtml).forEach { m ->
            scriptSrcs.add(m.groupValues[1].replace("\\/", "/").trim())
        }
        Log.d(name, "Embed içinde ${scriptSrcs.size} script src bulundu")

        var playerJs: String? = null

        for (src in scriptSrcs) {
            if (src.isBlank() || src.startsWith("data:")) continue
            for (candidate in resolveCandidates(src, embedUrl, embedHost)) {
                val host = runCatching { URI(candidate).host.orEmpty() }.getOrDefault("")
                if (host.contains("unpkg.com") || host.contains("jsdelivr.net") ||
                    host.contains("cloudflareinsights.com") || host.contains("google")) {
                    continue
                }
                Log.d(name, "Script deneniyor: $candidate")
                val scriptResp = runCatching {
                    app.get(candidate, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl), referer = embedUrl)
                }.getOrNull() ?: continue
                if (!scriptResp.isSuccessful) { Log.d(name, "Script HTTP ${scriptResp.code}: $candidate"); continue }
                Log.d(name, "Script OK (${scriptResp.text.length} char): $candidate")
                val js = scriptResp.text.replace("\\/", "/").replace("\\\"", "\"")

                if (candidate.contains("player.js", true) && playerJs == null) {
                    playerJs = js
                }

                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(js)?.value?.let { m3u8 -> emitLink(m3u8, candidate, callback); return true }

                if (processJwPlayer(js, candidate, callback)) return true

                for (p in listOf(
                    """"file"\s*:\s*"([^"]+)"""",
                    """"source"\s*:\s*"([^"]+)"""",
                    """"stream"\s*:\s*"([^"]+)"""",
                    """"hls"\s*:\s*"([^"]+)"""",
                )) {
                    Regex(p, RegexOption.IGNORE_CASE).find(js)?.groupValues?.getOrNull(1)?.let { u ->
                        if (u.contains(".m3u8", true) || u.startsWith("http")) {
                            emitLink(u, candidate, callback); return true
                        }
                    }
                }
            }
        }

        // 6) player.js'teki api/stream bağlamını ve fetch/XHR çağrılarını logla
        if (playerJs != null) {
            logApiStreamContext(playerJs)
            logFetchCalls(playerJs)
            logCryptoUsage(playerJs)
        }

        // 7) /api/stream endpoint'ini doğrudan dene (farklı başlıklarla)
        val streamCandidates = buildStreamCandidates(embedToken, slug, embedUrl, channelPageUrl)
        for (streamUrl in streamCandidates) {
            for (extraHeaders in listOf(
                mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl, "Origin" to embedHost, "Accept" to "application/json, text/plain, */*"),
                mapOf("User-Agent" to USER_AGENT, "Referer" to channelPageUrl, "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*"),
                mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/plain, */*"),
            )) {
                Log.d(name, "Denenen stream endpoint: $streamUrl | Headers: ${extraHeaders.keys}")
                val resp = runCatching {
                    app.get(streamUrl, headers = extraHeaders, referer = extraHeaders["Referer"] ?: embedUrl)
                }.getOrNull() ?: continue
                if (!resp.isSuccessful) { Log.d(name, "Stream HTTP ${resp.code}: $streamUrl"); continue }
                val body = resp.text.replace("\\/", "/").replace("\\\"", "\"")
                Log.d(name, "Stream yanıtı (${body.length} char): ${body.take(600)}")

                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(body)?.value?.let { m3u8 -> emitLink(m3u8, streamUrl, callback); return true }

                for (p in listOf(
                    """"url"\s*:\s*"([^"]+)"""",
                    """"stream"\s*:\s*"([^"]+)"""",
                    """"file"\s*:\s*"([^"]+)"""",
                    """"source"\s*:\s*"([^"]+)"""",
                    """"hls"\s*:\s*"([^"]+)"""",
                    """"link"\s*:\s*"([^"]+)"""",
                    """"playlist"\s*:\s*"([^"]+)"""",
                )) {
                    Regex(p, RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.let { u ->
                        if (u.contains(".m3u8", true) || u.contains(".mp4", true) || u.startsWith("http")) {
                            emitLink(u, streamUrl, callback); return true
                        }
                    }
                }

                val trimmed = body.trim()
                if (trimmed.startsWith("http") && (trimmed.contains(".m3u8") || trimmed.contains(".mp4"))) {
                    emitLink(trimmed, streamUrl, callback); return true
                }
            }
        }

        // 8) Son çare: tüm göreli yolları logla
        Log.e(name, "Yayın linki bulunamadı. Göreli yolları dökülüyor:")
        Regex("""["'](/[^"'\s]{3,200})["']""").findAll(embedHtml).map { it.groupValues[1] }.distinct().take(80).forEach {
            Log.d(name, "REL: $it")
        }
        Log.d(name, "=== Embed script src'leri ===")
        scriptSrcs.forEach { Log.d(name, "SCRIPT: $it") }

        return false
    }

    /** player.js içinde api/stream etrafındaki kodu loglar. */
    private fun logApiStreamContext(js: String) {
        Log.d(name, "=== player.js api/stream bağlamları ===")
        var idx = 0
        var count = 0
        while (count < 10) {
            idx = js.indexOf("api/stream", idx)
            if (idx == -1) break
            val start = maxOf(0, idx - 500)
            val end = minOf(js.length, idx + 500)
            Log.d(name, "API/STREAM CONTEXT [$count]: ${js.substring(start, end).replace("\n", " ")}")
            idx += 10
            count++
        }
        Regex("""src\s*=\s*([^;,\n]{1,200})""").findAll(js).take(20).forEach { m ->
            Log.d(name, "SRC ASSIGN: src = ${m.groupValues[1].take(200)}")
        }
    }

    /** player.js içindeki fetch/XHR çağrılarını loglar. */
    private fun logFetchCalls(js: String) {
        Log.d(name, "=== player.js fetch/XHR çağrıları ===")
        Regex("""fetch\s*\(\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(js).take(10).forEach {
            Log.d(name, "FETCH: ${it.groupValues[1]}")
        }
        Regex("""XMLHttpRequest""", RegexOption.IGNORE_CASE).findAll(js).take(5).forEach {
            val start = maxOf(0, it.range.first - 200)
            val end = minOf(js.length, it.range.last + 200)
            Log.d(name, "XHR CONTEXT: ${js.substring(start, end).replace("\n", " ")}")
        }
    }

    /** player.js içindeki şifreleme/kodlama kullanımını loglar. */
    private fun logCryptoUsage(js: String) {
        Log.d(name, "=== player.js crypto kullanımı ===")
        for (keyword in listOf("atob", "btoa", "CryptoJS", "encrypt", "decrypt", "AES", "subtle")) {
            val idx = js.indexOf(keyword, ignoreCase = true)
            if (idx != -1) {
                val start = maxOf(0, idx - 200)
                val end = minOf(js.length, idx + 200)
                Log.d(name, "CRYPTO [$keyword]: ${js.substring(start, end).replace("\n", " ")}")
            }
        }
    }

    /** /api/stream için olası src adayları üretir. */
    private fun buildStreamCandidates(
        embedToken: String?,
        slug: String,
        embedUrl: String,
        channelPageUrl: String,
    ): List<String> {
        val embedHost = "https://embed.beyazelma78.com"
        val candidates = mutableListOf<String>()
        val tokens = mutableListOf<String>()

        if (!embedToken.isNullOrBlank()) {
            tokens.add(embedToken)
            runCatching { tokens.add(URLEncoder.encode(embedToken, "UTF-8")) }
            tokens.add(embedToken.substringBefore("_"))
            tokens.add(embedToken.substringBefore("."))
            tokens.add(embedToken.substringAfterLast("_"))
        }
        tokens.add(slug)

        for (token in tokens.distinct()) {
            if (token.isBlank()) continue
            val enc = runCatching { URLEncoder.encode(token, "UTF-8") }.getOrDefault(token)
            candidates.add("$embedHost/api/stream?src=$enc")
            candidates.add("$mainUrl/api/stream?src=$enc")
        }

        return candidates.distinct()
    }

    /** URL'den query parametresi çıkarır. */
    private fun extractQueryParam(url: String, key: String): String? {
        return runCatching {
            Regex("""[?&]$key=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
        }.getOrNull()
    }

    private fun findEmbedHost(html: String): String? {
        Regex("""https?://(embed\.[a-z0-9.-]+)""", RegexOption.IGNORE_CASE).find(html)?.let {
            return "https://${it.groupValues[1]}"
        }
        Regex("""<base[^>]+href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            return it.groupValues[1].trimEnd('/')
        }
        return null
    }

    private fun resolveCandidates(src: String, embedUrl: String, embedHost: String): List<String> {
        val s = src.trim().replace("\\", "").trimEnd('"', '\'', ' ')
        if (s.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            list.add(s)
        } else if (s.startsWith("//")) {
            list.add("https:$s")
        } else if (s.startsWith("/")) {
            list.add("$mainUrl$s")
            if (embedHost.isNotBlank()) list.add("$embedHost$s")
        } else {
            list.add(absolute(s, embedUrl))
            if (embedHost.isNotBlank()) list.add("$embedHost/$s")
        }
        return list.distinct()
    }

    private fun findEmbedUrl(html: String): String? {
        fun clean(url: String): String = url.trim().trimEnd('\\', '"', '\'', '/', ' ')
        Regex("""["'](/api/embed\?[^"'\\]+)["'\\]?""").find(html)?.groupValues?.getOrNull(1)?.let {
            return "$mainUrl${clean(it)}"
        }
        Regex("""["'](https?://[^"'\\]*?/api/embed\?[^"'\\]+)["'\\]?""").find(html)?.groupValues?.getOrNull(1)?.let {
            return clean(it)
        }
        Regex("""<iframe[^>]+src=["']([^"'\\]+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)?.let {
            return absolute(clean(it), mainUrl)
        }
        return null
    }

    private suspend fun processJwPlayer(source: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val setupBlock = Regex("""\.setup\s*\(\s*(\{[\s\S]*?\})\s*\)""").find(source)?.groupValues?.getOrNull(1)
            ?: Regex("""setup\s*\(\s*(\{[\s\S]*?"(?:playlist|sources|file|mediaid|key)"[\s\S]*?\})\s*\)""").find(source)?.groupValues?.getOrNull(1)
        if (setupBlock.isNullOrBlank()) return processJwConfig(source, referer, callback)
        Log.d(name, "JWPlayer setup: ${setupBlock.take(600)}")
        return processJwConfig(setupBlock, referer, callback)
    }

    private suspend fun processJwConfig(config: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        Regex(""""file"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)?.let {
            if (it.contains(".m3u8", true) || it.contains(".mp4", true)) { emitLink(it, referer, callback); return true }
        }
        Regex(""""sources"\s*:\s*\[([\s\S]*?)]""").find(config)?.groupValues?.getOrNull(1)?.let { block ->
            Regex(""""file"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.getOrNull(1)?.let {
                if (it.contains(".m3u8", true) || it.contains(".mp4", true)) { emitLink(it, referer, callback); return true }
            }
        }
        val mediaid = Regex(""""mediaid"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
            ?: Regex(""""mediaId"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
        if (mediaid != null) {
            for (url in listOf(
                "https://cdn.jwplayer.com/v2/media/$mediaid",
                "https://cdn.jwplayer.com/v2/playlists/$mediaid",
                "https://content.jwplatform.com/feeds/$mediaid.json",
            )) {
                val r = runCatching { app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), referer = referer) }.getOrNull() ?: continue
                if (!r.isSuccessful) continue
                Regex(""""file"\s*:\s*"([^"]+)"""").find(r.text.replace("\\/", "/"))?.groupValues?.getOrNull(1)?.let {
                    if (it.contains(".m3u8", true) || it.contains(".mp4", true)) { emitLink(it, url, callback); return true }
                }
            }
        }
        return false
    }

    private suspend fun emitLink(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(newExtractorLink(source = name, name = name, url = url, type = ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to mainUrl)
        })
        Log.d(name, "★ Link eklendi: $url")
    }

    private fun absolute(raw: String, base: String): String {
        val v = raw.trim().replace("\\", "").trimEnd('"', '\'', ' ')
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { URI(base).resolve(v).toString() }.getOrElse { base.trimEnd('/') + "/" + v.trimStart('/') }
    }
}
