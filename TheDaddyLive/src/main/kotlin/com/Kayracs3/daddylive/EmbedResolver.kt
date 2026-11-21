package com.Kayracs3.daddylive

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import kotlinx.coroutines.CancellationException

private const val RESOLVER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Resolves publicly exposed DaddyLive player/embed pages.
 *
 * It checks ordinary page markup and script references first, delegates known
 * embeds to CloudStream extractors, and finally observes the player's normal
 * HLS request through WebViewResolver. It does not disable TLS verification
 * or bypass a protected API/login.
 */
class EmbedResolver : ExtractorApi() {
    override val mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive Embed Resolver"
    override val requiresReferer = false

    companion object {
        private val m3u8Regex = Regex(
            """https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val sourceFieldRegex = Regex(
            """(?:streamUrl|file|source)\s*:\s*["'](https?:[^"']+\.m3u8[^"']*)""",
            RegexOption.IGNORE_CASE
        )
        // Doğrulama (Turnstile / Cloudflare) isteyen sayfalarda boşuna beklememek için
        private val blockMarkers = listOf(
            "challenges.cloudflare.com/turnstile",
            "cf-turnstile",
            "Just a moment..."
        )
        private const val WEBVIEW_TIMEOUT_MS = 12_000L
        private const val MAX_WEBVIEW_TRIES = 2

        private val atobRegex = Regex(
            """(?:source\s*:\s*)?window\.atob\(["']([A-Za-z0-9+/=_-]+)["']\)""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun requestHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to RESOLVER_USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
        else uri.scheme + "://" + uri.host
    }.getOrNull()

    private fun absoluteUrl(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return runCatching { URI(base).resolve(raw).toString() }.getOrNull()
    }

    private fun appendDecodedScriptValues(html: String): String {
        val result = StringBuilder(html)
        for (match in atobRegex.findAll(html)) {
            val encoded = match.groupValues.getOrNull(1).orEmpty()
            val decoded = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                result.append('\n').append(decoded)
            }
        }
        return result.toString()
    }

    private suspend fun emitM3u8(
        mediaUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(pageUrl) ?: originOf(mediaUrl) ?: mainUrl
        val linkHeaders = mapOf(
            "User-Agent" to RESOLVER_USER_AGENT,
            "Referer" to pageUrl,
            "Origin" to origin
        )

        // If this is a master playlist, expose its available quality variants.
        // Media playlists and unparseable masters remain usable as a single link.
        val variants = HlsQuality.variants(mediaUrl, linkHeaders)
        if (variants.isEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "DaddyLive",
                    url = mediaUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = linkHeaders
                }
            )
            return
        }

        variants.forEach { variant ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "DaddyLive " + variant.label,
                    url = variant.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    this.quality = variant.height.takeIf { it > 0 }
                        ?: Qualities.Unknown.value
                    this.headers = linkHeaders
                }
            )
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
        val response = try {
            app.get(url, headers = requestHeaders(pageReferer), timeout = 10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }

        if (response.code !in 200..299) return

        // Sayfa doğrulama istiyorsa hemen vazgeç; sıradaki oynatıcıya geçilsin
        if (blockMarkers.any { response.text.contains(it, ignoreCase = true) }) return

        val document = response.document
        val normalizedHtml = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val scriptContent = appendDecodedScriptValues(normalizedHtml)

        val directUrls = linkedSetOf<String>()
        m3u8Regex.findAll(scriptContent).forEach {
            directUrls.add(it.value.trimEnd(';', ')', ']'))
        }
        sourceFieldRegex.findAll(scriptContent).forEach {
            directUrls.add(it.groupValues[1].replace("\\/", "/"))
        }

        document.select("iframe[src], video[src], source[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            val source = element.absUrl("src")
                .ifBlank { absoluteUrl(raw, url).orEmpty() }
            if (source.contains(".m3u8", ignoreCase = true)) {
                directUrls.add(source)
            }
        }

        directUrls.firstOrNull()?.let { mediaUrl ->
            emitM3u8(mediaUrl, url, callback)
            return
        }

        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { element -> absoluteUrl(element.attr("src"), url) }
            .distinct()

        // Not: loadExtractor kullanılmıyor. İlgisiz çıkarıcılar (ör. XStreamCdn) HTML
        // sayfalarda JSON hatası verip zaman kaybettiriyordu.
        for (iframeUrl in iframeUrls.take(MAX_WEBVIEW_TRIES)) {
            // Kayıtlı çıkarıcısı olmayan gömülü sayfalar için sayfanın normal HLS
            // isteğini gözlemle.
            val interceptedUrl = try {
                app.get(
                    iframeUrl,
                    referer = url,
                    headers = requestHeaders(url),
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                        timeout = WEBVIEW_TIMEOUT_MS
                    )
                ).url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (!interceptedUrl.isNullOrBlank() &&
                interceptedUrl.contains(".m3u8", ignoreCase = true)
            ) {
                emitM3u8(interceptedUrl, iframeUrl, callback)
                return
            }
        }
    }
}
