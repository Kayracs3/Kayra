package com.Kayracs3.newtane

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
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private data class NewTanePlayerPage(
    val url: String,
    val html: String
)

class NewTaneProvider : MainAPI() {
    override var mainUrl = "https://tv.newtane.com"
    override var name = "NewTane"
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

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

        private val SAFE_ID = Regex("""[A-Za-z0-9_-]{1,80}""")
        private val M3U8_REGEX = Regex(
            """https?://[^\s"'<>\]+?\.m3u8[^\s"'<>\]*""",
            RegexOption.IGNORE_CASE
        )
        private val IFRAME_REGEX = Regex(
            """<iframe[^>]+src=["']([^"'<>]+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val TURNSTILE_MARKERS = listOf(
            "challenges.cloudflare.com/turnstile",
            "cf-turnstile",
            "Just a moment...",
            "Attention Required"
        )
        private val GAMBLING_NAME_MARKERS = Regex(
            """(?i)(?:\bcasino\b|\bpoker\b|\bbetting\b|\bwager(?:ing)?\b|\bbahis\b|\biddaa\b)"""
        )
    }

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private val channelNames = ConcurrentHashMap<String, String>()

    override val mainPage = mainPageOf(
        "$API_BASE/api/channels" to "Tüm Kanallar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = fetchChannels()
        Log.d(name, "Kanal listesi: " + channels.size + " kayıt")
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        return fetchChannels().filter { it.name.contains(term, ignoreCase = true) }
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val response = try {
            app.get(
                "$API_BASE/api/channels",
                headers = apiHeaders,
                referer = "$mainUrl/"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(name, "Kanal API bağlantı hatası: " + e.message)
            return emptyList()
        }

        if (!response.isSuccessful) {
            Log.e(name, "Kanal API HTTP " + response.code)
            return emptyList()
        }

        val array = parseChannelArray(response.text)
        if (array == null) {
            Log.e(name, "Kanal API yanıtında kanal dizisi bulunamadı: " + response.text.take(300))
            return emptyList()
        }

        val output = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (index in 0 until array.length()) {
            val original = array.optJSONObject(index) ?: continue
            val channel = original.optJSONObject("channel") ?: original

            val id = firstString(
                channel,
                "channel_id", "channelId", "id", "slug", "channel"
            ) ?: continue

            if (!SAFE_ID.matches(id)) continue

            val title = firstString(
                channel,
                "channel_name", "channelName", "name", "title", "label"
            ) ?: continue

            // Bu sağlayıcı TV kataloğu içindir; kumar/bahis hizmeti olarak
            // adlandırılmış kayıtları katalogdan dahil etme.
            if (GAMBLING_NAME_MARKERS.containsMatchIn(title)) continue

            val liveValue = firstString(
                channel,
                "isLive", "is_live", "live", "status", "active"
            )
            if (liveValue != null && liveValue.lowercase() in setOf(
                    "false", "0", "offline", "ended", "inactive"
                )
            ) {
                continue
            }

            if (!seen.add(id)) continue

            val rawLogo = firstString(
                channel,
                "logo", "channel_logo", "logo_url", "image", "poster", "icon"
            )
            val logo = rawLogo?.let { absolute(it, mainUrl) }

            channelNames[id] = title
            output.add(
                newLiveSearchResponse(title, id, TvType.Live) {
                    this.posterUrl = logo
                }
            )
        }

        return output
    }

    private fun parseChannelArray(body: String): JSONArray? {
        runCatching { JSONArray(body) }.getOrNull()?.let { return it }

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val keys = listOf("channels", "data", "items", "results", "list")

        for (key in keys) {
            root.optJSONArray(key)?.let { return it }
        }

        val nested = root.optJSONObject("data")
            ?: root.optJSONObject("result")
            ?: root.optJSONObject("payload")

        if (nested != null) {
            for (key in keys) {
                nested.optJSONArray(key)?.let { return it }
            }
        }

        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            if (raw == JSONObject.NULL) continue

            val value = raw.toString().trim()
            if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = normalizeChannelId(url) ?: return null
        val title = channelNames[id] ?: "Kanal $id"

        return newLiveStreamLoadResponse(
            name = title,
            url = id,
            dataUrl = id
        )
    }

    private fun normalizeChannelId(input: String): String? {
        val value = input.trim()
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()

        return value.takeIf { SAFE_ID.matches(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelId = normalizeChannelId(data) ?: return false
        Log.d(name, "Yayın aranıyor: kanal=" + channelId)

        val playerPage = fetchPlayerPage(channelId) ?: return false
        val pageHtml = playerPage.html
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")

        if (hasTurnstile(pageHtml)) {
            Log.w(name, "Oynatıcı sayfası Cloudflare doğrulama ekranı döndürdü.")
            return false
        }

        M3U8_REGEX.find(pageHtml)?.value?.let { stream ->
            emitLink(stream, playerPage.url, callback)
            return true
        }

        val iframeUrls = IFRAME_REGEX.findAll(pageHtml)
            .map { absolute(it.groupValues[1], playerPage.url) }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .toList()

        if (iframeUrls.isEmpty()) {
            return tryDirectOrToken(pageHtml, playerPage.url, channelId, callback)
        }

        for (iframeUrl in iframeUrls) {
            val iframeResponse = try {
                app.get(
                    iframeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerPage.url,
                        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                    ),
                    referer = playerPage.url,
                    allowRedirects = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Iframe alınamadı: " + iframeUrl + " (" + e.message + ")")
                continue
            }

            if (!iframeResponse.isSuccessful) {
                Log.w(name, "Iframe HTTP " + iframeResponse.code + ": " + iframeUrl)
                continue
            }

            val iframeHtml = iframeResponse.text
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("&amp;", "&")

            if (hasTurnstile(iframeHtml)) {
                Log.w(name, "Embed Cloudflare doğrulaması döndürdü: " + iframeUrl)
                continue
            }

            M3U8_REGEX.find(iframeHtml)?.value?.let { stream ->
                emitLink(stream, iframeUrl, callback)
                return true
            }

            val tokenUrls = linkedSetOf<String>()
            val tokenRegexes = listOf(
                Regex("""["'](https?://[^"' ]*?/api/playlist-token\?slug=[^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^\s"'<>\]+?/api/playlist-token\?slug=[^\s"'<>\&]+)""", RegexOption.IGNORE_CASE),
                Regex("""["'](/api/playlist-token\?slug=[^"']+)["']""", RegexOption.IGNORE_CASE)
            )

            for (regex in tokenRegexes) {
                regex.findAll(iframeHtml).forEach { match ->
                    val raw = match.groupValues.lastOrNull().orEmpty()
                    if (raw.isNotBlank()) tokenUrls.add(absolute(raw, iframeUrl))
                }
            }

            for (tokenUrl in tokenUrls) {
                if (tryTokenEndpoint(tokenUrl, iframeUrl, channelId, callback)) {
                    return true
                }
            }

            val premiumSlug = Regex("""premium(\d+)""", RegexOption.IGNORE_CASE)
                .find(iframeHtml)?.value ?: "premium$channelId"

            val fallbackTokenUrls = listOf(
                "$EDGE_BASE/api/playlist-token?slug=$premiumSlug",
                "$EDGE_BASE/api/playlist-token?slug=$channelId"
            )

            for (tokenUrl in fallbackTokenUrls) {
                if (tryTokenEndpoint(tokenUrl, iframeUrl, channelId, callback)) {
                    return true
                }
            }

            if (tryDirectOrToken(iframeHtml, iframeUrl, channelId, callback)) {
                return true
            }
        }

        Log.e(name, "Yayın bağlantısı bulunamadı: kanal=" + channelId)
        return false
    }

    private suspend fun fetchPlayerPage(channelId: String): NewTanePlayerPage? {
        val bases = listOf(
            DLHD_BASE,
            "https://thedaddy.li",
            "https://daddylive.pk"
        ).distinct()

        for (base in bases) {
            val pageUrl = "$base/stream/stream-$channelId.php"
            val response = try {
                app.get(
                    pageUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$base/",
                        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                    ),
                    referer = "$base/",
                    allowRedirects = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Oynatıcı sunucusuna ulaşılamadı: " + base + " (" + e.message + ")")
                continue
            }

            if (!response.isSuccessful) {
                Log.w(name, "Oynatıcı HTTP " + response.code + ": " + pageUrl)
                continue
            }

            Log.d(name, "Oynatıcı sayfası alındı: " + pageUrl + " (" + response.text.length + " karakter)")
            return NewTanePlayerPage(pageUrl, response.text)
        }

        return null
    }

    private suspend fun tryTokenEndpoint(
        tokenUrl: String,
        iframeUrl: String,
        channelId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tokenResponse = try {
            app.get(
                tokenUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to iframeUrl,
                    "Origin" to EMBED_BASE,
                    "Accept" to "application/json, text/plain, */*"
                ),
                referer = iframeUrl,
                allowRedirects = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(name, "Token isteği başarısız: " + e.message)
            return false
        }

        if (!tokenResponse.isSuccessful) {
            Log.d(name, "Token API HTTP " + tokenResponse.code + ": " + tokenUrl)
            return false
        }

        val body = tokenResponse.text.replace("\\/", "/")
        val token = Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""["']([A-Za-z0-9_\-.]{30,})["']""")
                .find(body)?.groupValues?.getOrNull(1)
            ?: return false

        val streamUrl = "$EDGE_BASE/premium$channelId/index.m3u8?t=" +
            URLEncoder.encode(token, "UTF-8")

        emitLink(streamUrl, iframeUrl, callback)
        return true
    }

    private suspend fun tryDirectOrToken(
        html: String,
        referer: String,
        channelId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")

        M3U8_REGEX.find(normalized)?.value?.let { stream ->
            emitLink(stream, referer, callback)
            return true
        }

        val tokenRegex = Regex(
            """https?://[^\s"'<>\]+?/index\.m3u8\?[^\s"'<>\]*\bt=[^\s"'<>\&]+[^\s"'<>\]*""",
            RegexOption.IGNORE_CASE
        )
        tokenRegex.find(normalized)?.value?.let { stream ->
            emitLink(stream, referer, callback)
            return true
        }

        Log.d(name, "Doğrudan M3U8 bulunamadı: " + channelId)
        return false
    }

    private fun emitLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to EMBED_BASE
                )
            }
        )

        Log.d(name, "M3U8 bağlantısı eklendi: " + url)
    }

    private fun hasTurnstile(html: String): Boolean =
        TURNSTILE_MARKERS.any { marker ->
            html.contains(marker, ignoreCase = true)
        }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (value.isBlank()) return ""
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        if (value.startsWith("//")) return "https:$value"

        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrElse {
            base.trimEnd('/') + "/" + value.trimStart('/')
        }
    }
}
