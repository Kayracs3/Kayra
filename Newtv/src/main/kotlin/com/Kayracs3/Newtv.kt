package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
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
        // RepoLinkGenerator önbelleğini atlatmak için dataUrl ayırıcısı (slug'da görünmez)
        private const val CB_SEP = "|cb|"
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
        // ★ CACHE BUSTING: her ziyaret için farklı dataUrl üret.
        // Böylece RepoLinkGenerator eski sonucu tekrar kullanmaz ve loadLinks her seferinde çağrılır.
        val bust = System.currentTimeMillis()
        return newLiveStreamLoadResponse(
            name = "Kanal",
            url = url,
            dataUrl = "$slug$CB_SEP$bust",
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ★ load() içinde eklenen cache-bust ekini temizle
        val slug = data.substringBefore(CB_SEP).trim()
        if (slug.isBlank()) return false

        val channelPageUrl = "$mainUrl/kanal/$slug"
        Log.d(name, "loadLinks: slug=$slug (cache-bust temizlendi)")

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

        // 2) StreamPlayer slug'ını çıkar
        val streamPlayerSlug = extractStreamPlayerSlug(embedHtml)
        Log.d(name, "STREAMPLAYER SLUG: $streamPlayerSlug")

        // 3) ★ /api/play/<slug> endpoint'ini doğrulama + yenileme ile dene
        if (!streamPlayerSlug.isNullOrBlank()) {
            if (tryPlayApi(streamPlayerSlug, embedHost, embedUrl, callback)) return true
        }

        // 4) Embed HTML içinde doğrudan kpores URL'si
        Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).findAll(embedHtml).forEach { m ->
            val kporesUrl = m.value.replace("\\/", "/")
            Log.d(name, "EMBED KPORES URL: $kporesUrl")
            if (tryKporesDirect(kporesUrl, embedHost, embedUrl, callback)) return true
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

                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(js)?.value?.let { m3u8 -> emitLink(m3u8, candidate, callback); return true }

                if (processJwPlayer(js, candidate, callback)) return true
            }
        }

        Log.e(name, "Yayın linki bulunamadı. Göreli yollar dökülüyor:")
        Regex("""["'](/[^"'\s]{3,200})["']""").findAll(embedHtml).map { it.groupValues[1] }.distinct().take(80).forEach {
            Log.d(name, "REL: $it")
        }

        return false
    }

    /**
     * /api/play/<slug> endpoint'ini çağırır ve linki DOĞRULAYARAK döner.
     * Geçersiz/süresi dolmuş linkte 3 kez yeniden dener (1.5 sn arayla).
     */
    private suspend fun tryPlayApi(
        spSlug: String,
        embedHost: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val encoded = runCatching { URLEncoder.encode(spSlug, "UTF-8") }.getOrDefault(spSlug)
        val bases = listOf(mainUrl, embedHost).distinct()

        repeat(3) { attempt ->
            if (attempt > 0) {
                Log.d(name, "PLAY API yeniden deneniyor (deneme ${attempt + 1}/3), 1.5 sn bekleniyor...")
                delay(1500)
            }

            for (base in bases) {
                val url = "$base/api/play/$encoded"
                Log.d(name, "PLAY API deneniyor: $url")

                val resp = runCatching {
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to embedHost,
                            "Accept" to "application/json, text/plain, */*",
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        referer = embedUrl,
                    )
                }.getOrNull() ?: continue

                if (!resp.isSuccessful) {
                    Log.d(name, "PLAY API HTTP ${resp.code}: $url")
                    continue
                }

                val body = resp.text.replace("\\/", "/").replace("\\\"", "\"")
                Log.d(name, "PLAY API OK (${body.length} char): ${body.take(600)}")

                if (body.trim().startsWith("#EXTM3U")) {
                    emitLink(url, url, callback)
                    return true
                }

                val candidates = mutableListOf<String>()

                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(body)?.value?.let { candidates.add(it) }

                Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).find(body)?.value?.let { candidates.add(it) }

                for (p in listOf(
                    """"stream"\s*:\s*"([^"]+)"""",
                    """"url"\s*:\s*"([^"]+)"""",
                    """"file"\s*:\s*"([^"]+)"""",
                    """"source"\s*:\s*"([^"]+)"""",
                    """"hls"\s*:\s*"([^"]+)"""",
                    """"link"\s*:\s*"([^"]+)"""",
                    """"src"\s*:\s*"([^"]+)"""",
                    """"playlist"\s*:\s*"([^"]+)"""",
                    """"video"\s*:\s*"([^"]+)"""",
                    """"manifest"\s*:\s*"([^"]+)"""",
                )) {
                    Regex(p, RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.let { raw ->
                        val abs = absolute(raw, url)
                        if (abs.contains(".m3u8", true) || abs.contains(".mp4", true) ||
                            Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).containsMatchIn(abs)) {
                            candidates.add(abs)
                        }
                    }
                }

                for (candidate in candidates.distinct()) {
                    if (Regex(KPORES_REGEX, RegexOption.IGNORE_CASE).containsMatchIn(candidate)) {
                        if (tryKporesDirect(candidate, embedHost, embedUrl, callback)) return true
                        continue
                    }

                    if (validateStreamUrl(candidate, url)) {
                        Log.d(name, "★ Doğrulanmış link: $candidate")
                        emitLink(candidate, url, callback)
                        return true
                    } else {
                        Log.d(name, "Link geçersiz/süresi dolmuş: $candidate")
                    }
                }
            }
        }

        Log.e(name, "PLAY API tüm denemeler başarısız.")
        return false
    }

    /** m3u8 linkinin hâlâ geçerli olduğunu doğrular (200 + #EXTM3U). */
    private suspend fun validateStreamUrl(
        streamUrl: String,
        referer: String,
    ): Boolean {
        val resp = runCatching {
            app.get(
                streamUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                ),
                referer = referer,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        Log.d(name, "VALIDATE ${resp.code}: $streamUrl")
        if (!resp.isSuccessful) return false

        val body = resp.text
        if (!body.trimStart().startsWith("#EXTM3U")) {
            Log.d(name, "VALIDATE içerik #EXTM3U değil: ${body.take(200)}")
            return false
        }
        return true
    }

    /** Embed HTML'den StreamPlayer slug'ını çıkarır. */
    private fun extractStreamPlayerSlug(embedHtml: String): String? {
        Regex("""StreamPlayer\.mount\(\s*['"][^'"]+['"]\s*,\s*(\{[\s\S]*?\})\s*\)""").find(embedHtml)?.groupValues?.getOrNull(1)?.let { config ->
            Log.d(name, "STREAMPLAYER CONFIG: ${config.take(500)}")
            return Regex(""""slug"\s*:\s*"([^"]+)"""").find(config)?.groupValues?.getOrNull(1)
        }
        Regex(""""slug"\s*:\s*"(tr-[^"]+)"""").find(embedHtml)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** kpores URL'sini /api/stream proxy'si veya doğrudan dener; geçerliyse yayınlar. */
    private suspend fun tryKporesDirect(
        kporesUrl: String,
        embedHost: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
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
                """"src"\s*:\s*"([^"]+)"""",
                """"hls"\s*:\s*"([^"]+)"""",
            )) {
                Regex(p, RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.let { u ->
                    val abs = absolute(u, proxyUrl)
                    if (abs.contains(".m3u8", true) || abs.contains(".mp4", true)) {
                        emitLink(abs, proxyUrl, callback); return true
                    }
                }
            }

            if (body.trim().startsWith("#EXTM3U")) {
                emitLink(proxyUrl, proxyUrl, callback); return true
            }
        }

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
