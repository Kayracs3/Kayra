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
        private const val KPORES_REGEX = """https?://[^\s"'<>\\]*(?:kpores\.(?:ru|com|net|org|to)|kpo\.re)[^\s"'<>\\]*"""
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

        // 2) Embed HTML içinde doğrudan kpores URL'si var mı?
        Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).findAll(embedHtml).forEach { m ->
            val kporesUrl = m.value.replace("\\/", "/")
            Log.d(name, "EMBED KPORES URL: $kporesUrl")
            if (tryKporesDirect(kporesUrl, embedHost, embedUrl, callback)) return true
        }

        // 3) Global stream URL değişkenlerini ara
        for (pattern in listOf(
            """window\.__streamUrl\s*=\s*["']([^"']+)["']""",
            """window\.__liveUrl\s*=\s*["']([^"']+)["']""",
            """window\.streamUrl\s*=\s*["']([^"']+)["']""",
            """window\.videoUrl\s*=\s*["']([^"']+)["']""",
            """window\.__SH_STREAM\s*=\s*["']([^"']+)["']""",
        )) {
            Regex(pattern, RegexOption.IGNORE_CASE).find(embedHtml)?.groupValues?.getOrNull(1)?.let { u ->
                Log.d(name, "GLOBAL STREAM URL: $u")
                val absolute = absolute(u, embedUrl)
                if (absolute.contains(".m3u8", true)) { emitLink(absolute, embedUrl, callback); return true }
                if (Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).containsMatchIn(absolute)) {
                    if (tryKporesDirect(absolute, embedHost, embedUrl, callback)) return true
                }
            }
        }

        // 4) StreamPlayer slug'ını çıkar ve API endpoint'lerini dene
        val streamPlayerSlug = extractStreamPlayerSlug(embedHtml)
        Log.d(name, "STREAMPLAYER SLUG: $streamPlayerSlug")

        if (!streamPlayerSlug.isNullOrBlank()) {
            if (tryStreamPlayerApis(streamPlayerSlug, embedHost, embedUrl, callback)) return true
        }

        // 5) JWPlayer
        if (processJwPlayer(embedHtml, embedUrl, callback)) return true

        // 6) Script src'lerini dene
        val scriptSrcs = mutableListOf<String>()
        Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(embedHtml).forEach { m ->
            scriptSrcs.add(m.groupValues[1].replace("\\/", "/").trim())
        }
        Log.d(name, "Embed içinde ${scriptSrcs.size} script src bulundu")

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

                // player.js içindeki API endpoint'lerini logla
                if (candidate.contains("player.js", true)) {
                    logPlayerApis(js)
                    // player.js içinde kpores URL'si var mı?
                    Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).findAll(js).take(5).forEach { m ->
                        Log.d(name, "PLAYER KPORES URL: ${m.value.replace("\\/", "/")}")
                    }
                }

                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(js)?.value?.let { m3u8 -> emitLink(m3u8, candidate, callback); return true }

                if (processJwPlayer(js, candidate, callback)) return true
            }
        }

        // 7) Son çare logları
        Log.e(name, "Yayın linki bulunamadı. Göreli yollar dökülüyor:")
        Regex("""["'](/[^"'\s]{3,200})["']""").findAll(embedHtml).map { it.groupValues[1] }.distinct().take(80).forEach {
            Log.d(name, "REL: $it")
        }

        return false
    }

    /** Embed HTML'den StreamPlayer slug'ını çıkarır. */
    private fun extractStreamPlayerSlug(embedHtml: String): String? {
        Regex("""StreamPlayer\.mount\(\s*['"][^'"]+['"]\s*,\s*(\{[\s\S]*?\})\s*\)""").find(embedHtml)?.groupValues?.getOrNull(1)?.let { config ->
            Log.d(name, "STREAMPLAYER CONFIG: ${config.take(500)}")
            return Regex(""""slug"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
        }
        // Alternatif: inline JSON objesindeki slug
        Regex(""""slug"\s*:\s*"(tr-[^"]+)"""").find(embedHtml)?.groupValues?.getOrNull(1)?.let {
            return it
        }
        return null
    }

    /** StreamPlayer slug'ı ile olası API endpoint'lerini dener. */
    private suspend fun tryStreamPlayerApis(
        spSlug: String,
        embedHost: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val encoded = runCatching { URLEncoder.encode(spSlug, "UTF-8") }.getOrDefault(spSlug)
        val endpoints = mutableListOf<String>()

        for (host in listOf(embedHost, mainUrl)) {
            endpoints.add("$host/api/stream/$encoded")
            endpoints.add("$host/api/player/$encoded")
            endpoints.add("$host/api/live/$encoded")
            endpoints.add("$host/api/source/$encoded")
            endpoints.add("$host/api/channel/$encoded")
            endpoints.add("$host/api/stream?slug=$encoded")
            endpoints.add("$host/api/player?slug=$encoded")
            endpoints.add("$host/api/live?slug=$encoded")
            endpoints.add("$host/api/source?slug=$encoded")
            endpoints.add("$host/api/channel?slug=$encoded")
            endpoints.add("$host/api/player?channel=$encoded")
            endpoints.add("$host/api/stream?src=$encoded")
        }

        for (url in endpoints) {
            val resp = runCatching {
                app.get(url, headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to embedHost,
                    "Accept" to "application/json, text/plain, */*",
                ), referer = embedUrl)
            }.getOrNull() ?: continue
            if (!resp.isSuccessful) {
                Log.d(name, "SP API HTTP ${resp.code}: $url")
                continue
            }
            val body = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            Log.d(name, "SP API OK (${body.length} char): $url => ${body.take(300)}")

            // m3u8 doğrudan
            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .find(body)?.value?.let { m3u8 -> emitLink(m3u8, url, callback); return true }

            // kpores URL'si
            Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).find(body)?.value?.let { kpores ->
                Log.d(name, "SP API KPORES BULDU: $kpores")
                if (tryKporesDirect(kpores, embedHost, embedUrl, callback)) return true
            }

            // JSON alanları
            for (p in listOf(
                """"url"\s*:\s*"([^"]+)"""",
                """"stream"\s*:\s*"([^"]+)"""",
                """"file"\s*:\s*"([^"]+)"""",
                """"source"\s*:\s*"([^"]+)"""",
                """"hls"\s*:\s*"([^"]+)"""",
                """"link"\s*:\s*"([^"]+)"""",
                """"src"\s*:\s*"([^"]+)"""",
                """"playlist"\s*:\s*"([^"]+)"""",
            )) {
                Regex(p, RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.let { u ->
                    val abs = absolute(u, url)
                    if (abs.contains(".m3u8", true) || abs.contains(".mp4", true)) {
                        emitLink(abs, url, callback); return true
                    }
                    if (Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).containsMatchIn(abs)) {
                        if (tryKporesDirect(abs, embedHost, embedUrl, callback)) return true
                    }
                }
            }
        }
        return false
    }

    /** Bir kpores URL'sini önce doğrudan, sonra /api/stream proxy'si ile dener. */
    private suspend fun tryKporesDirect(
        kporesUrl: String,
        embedHost: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Önce /api/stream?src=<kporesUrl> proxy'sini dene
        val encoded = runCatching { URLEncoder.encode(kporesUrl, "UTF-8") }.getOrDefault(kporesUrl)
        for (proxyBase in listOf(embedHost, mainUrl)) {
            val proxyUrl = "$proxyBase/api/stream?src=$encoded"
            val resp = runCatching {
                app.get(proxyUrl, headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to embedHost,
                    "Accept" to "application/json, text/plain, */*",
                ), referer = embedUrl)
            }.getOrNull() ?: continue
            if (!resp.isSuccessful) { Log.d(name, "KPores proxy HTTP ${resp.code}: $proxyUrl"); continue }
            val body = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            Log.d(name, "KPores proxy OK (${body.length} char): ${body.take(400)}")

            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .find(body)?.value?.let { m3u8 -> emitLink(m3u8, proxyUrl, callback); return true }

            for (p in listOf(
                """"url"\s*:\s*"([^"]+)"""",
                """"stream"\s*:\s*"([^"]+)"""",
                """"file"\s*:\s*"([^"]+)"""",
                """"source"\s*:\s*"([^"]+)"""",
                """"hls"\s*:\s*"([^"]+)"""",
                """"link"\s*:\s*"([^"]+)"""",
                """"src"\s*:\s*"([^"]+)"""",
            )) {
                Regex(p, RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.let { u ->
                    val abs = absolute(u, proxyUrl)
                    if (abs.contains(".m3u8", true) || abs.contains(".mp4", true)) {
                        emitLink(abs, proxyUrl, callback); return true
                    }
                }
            }

            // Yanıt doğrudan m3u8 içeriği mi?
            val trimmed = body.trim()
            if (trimmed.startsWith("#EXTM3U")) {
                emitLink(proxyUrl, proxyUrl, callback); return true
            }
        }

        // Doğrudan kpores URL'sini dene
        val directResp = runCatching {
            app.get(kporesUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedUrl,
                "Origin" to embedHost,
            ), referer = embedUrl, allowRedirects = true)
        }.getOrNull()
        if (directResp != null && directResp.isSuccessful) {
            val body = directResp.text
            if (body.trim().startsWith("#EXTM3U")) {
                emitLink(kporesUrl, kporesUrl, callback); return true
            }
            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .find(body)?.value?.let { m3u8 -> emitLink(m3u8, kporesUrl, callback); return true }
        }

        return false
    }

    /** player.js içindeki tüm /api/ endpoint'lerini loglar. */
    private fun logPlayerApis(js: String) {
        Log.d(name, "=== player.js API endpoint'leri ===")
        Regex("""["'`](/api/[a-zA-Z0-9/_.\-${'$'}]+)["'`]""").findAll(js).map { it.groupValues[1] }.distinct().forEach {
            Log.d(name, "PLAYER API: $it")
        }
        Regex("""["'`](https?://[^"'`]*?/api/[a-zA-Z0-9/_.\-${'$'}]+)["'`]""").findAll(js).map { it.groupValues[1] }.distinct().take(20).forEach {
            Log.d(name, "PLAYER FULL API: $it")
        }
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
        return processJwConfig(setupBlock, referer, callback)
    }

    private suspend fun processJwConfig(config: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        Regex(""""file"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)?.let {
            if (it.contains(".m3u8", true) || it.contains(".mp4", true)) { emitLink(it, referer, callback); return true }
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
