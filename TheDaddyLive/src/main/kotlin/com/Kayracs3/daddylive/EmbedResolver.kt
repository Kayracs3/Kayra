package com.Kayracs3.daddylive

import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull

private const val RESOLVER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Resolves publicly exposed DaddyLive player/embed pages.
 *
 * Arama sırası:
 *   1. Ana oynatıcı sayfası HTML'inde m3u8
 *   2. iframe HTML'inde m3u8        ← Turnstile'a takılmadan çözer
 *   3. Cloudflare Turnstile'lı iframe'ler için WebViewResolver (son çare)
 *
 * [KRİTİK] m3u8 isteklerinde "Origin" başlığı GÖNDERİLMEZ.
 * edge.wedd48c.top CDN'i Origin başlığını reddedip 403 dönüyordu.
 * Referer olarak ana site (thedaddy.li) kullanılır.
 */
class EmbedResolver : ExtractorApi() {
    override val mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive Embed Resolver"
    override val requiresReferer = false

    companion object {
        private const val TAG = "DaddyLiveResolver"

        // CDN'in kabul ettiği Referer — embed sayfası değil, ana site
        private const val MEDIA_REFERER = "https://thedaddy.li/"

        private val m3u8Regex = Regex(
            """(?:https?:)?//[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        private val sourceFieldRegex = Regex(
            """(?:streamUrl|file|source|src)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        private val turnstileMarkers = listOf(
            "challenges.cloudflare.com/turnstile",
            "cf-turnstile",
            "Just a moment..."
        )

        private const val WEBVIEW_TIMEOUT_MS = 20_000L
        private const val MAX_WEBVIEW_TRIES = 2

        private const val PREFLIGHT_TIMEOUT_S = 10L
        private const val PREFLIGHT_HARD_LIMIT_MS = 12_000L

        private val atobRegex = Regex(
            """(?:source\s*:\s*)?(?:window\.)?atob\(["']([A-Za-z0-9+/=_-]+)["']\)""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun pageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to RESOLVER_USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    /** m3u8/CDN istekleri için başlıklar — Origin YOK. */
    private fun mediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to RESOLVER_USER_AGENT,
        "Referer" to MEDIA_REFERER
    )

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
            }.getOrNull() ?: runCatching {
                String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                result.append('\n').append(decoded)
            }
        }
        return result.toString()
    }

    /**
     * Ortak m3u8 arayıcı. Hem ana sayfa hem iframe HTML'i için kullanılır.
     */
    private fun findM3u8(
        html: String,
        doc: org.jsoup.nodes.Document,
        base: String
    ): String? {
        // 1) <video>/<source>
        doc.select("video source[src], video[src], source[src]").forEach { el ->
            val s = el.absUrl("src").ifBlank { el.attr("src") }
            if (s.contains(".m3u8", ignoreCase = true)) return s
        }

        // 2) Doğrudan regex
        m3u8Regex.find(html)?.value?.trimEnd(';', ')', ']')?.let { raw ->
            val abs = absoluteUrl(raw, base) ?: raw
            if (abs.startsWith("http", ignoreCase = true)) return abs
        }

        // 3) streamUrl / file / source / src değişkenleri
        sourceFieldRegex.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            val abs = raw.replace("\\/", "/")
            if (abs.contains(".m3u8", ignoreCase = true)) {
                return absoluteUrl(abs, base) ?: abs
            }
        }
        return null
    }

    /**
     * [KRİTİK DÜZELTME]
     * - "Origin" başlığı kaldırıldı (edge.wedd48c.top 403 dönüyordu).
     * - Referer ana siteye (thedaddy.li) sabitlendi.
     * - HlsQuality ile master playlist varyantları çözülür.
     */
    private suspend fun emitM3u8(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mediaHeaders()

        val variants = HlsQuality.variants(mediaUrl, headers)
        if (variants.isEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "DaddyLive",
                    url = mediaUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = MEDIA_REFERER
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
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
                    this.referer = MEDIA_REFERER
                    this.quality = variant.height.takeIf { it > 0 }
                        ?: Qualities.Unknown.value
                    this.headers = headers
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

        // 1) Ana oynatıcı sayfasını çek
        val response = try {
            app.get(url, headers = pageHeaders(pageReferer), timeout = 10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "sayfa alınamadı: $url (${e.javaClass.simpleName})")
            return
        }

        if (response.code !in 200..299) {
            Log.d(TAG, "HTTP ${response.code}: $url")
            return
        }

        val document = response.document
        val normalizedHtml = response.text.replace("\\/", "/").replace("&amp;", "&")
        val scriptContent = appendDecodedScriptValues(normalizedHtml)

        // 2) Ana sayfada m3u8 var mı?
        findM3u8(scriptContent, document, url)?.let { mediaUrl ->
            Log.d(TAG, "ana sayfa HTML içinden akış: $mediaUrl")
            emitM3u8(mediaUrl, callback)
            return
        }

        // 3) iframe'leri dene
        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { element -> absoluteUrl(element.attr("src"), url) }
            .distinct()

        if (iframeUrls.isEmpty()) {
            Log.d(TAG, "iframe yok, akış bulunamadı: $url")
            return
        }

        var webViewTries = 0
        for (iframeUrl in iframeUrls) {

            // 3a) Preflight: iframe HTML'ini çek
            val iframeResponse = try {
                withTimeoutOrNull(PREFLIGHT_HARD_LIMIT_MS) {
                    app.get(
                        iframeUrl,
                        headers = pageHeaders(url),
                        timeout = PREFLIGHT_TIMEOUT_S
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (iframeResponse == null || iframeResponse.code !in 200..299) {
                Log.d(TAG, "iframe ulaşılamıyor: $iframeUrl")
                continue
            }

            val iframeHtml = iframeResponse.text
                .replace("\\/", "/")
                .replace("&amp;", "&")
            val iframeDoc = iframeResponse.document
            val iframeScript = appendDecodedScriptValues(iframeHtml)

            // ⭐ WebView'dan ÖNCE iframe HTML'inde m3u8 ara
            findM3u8(iframeScript, iframeDoc, iframeUrl)?.let { mediaUrl ->
                Log.d(TAG, "iframe HTML içinden akış: $mediaUrl")
                emitM3u8(mediaUrl, callback)
                return
            }

            if (turnstileMarkers.any { iframeHtml.contains(it, ignoreCase = true) }) {
                Log.d(TAG, "iframe Turnstile içeriyor, WebView denenecek: $iframeUrl")
            }

            // 3b) Son çare: WebView ile m3u8 isteğini yakala
            if (webViewTries >= MAX_WEBVIEW_TRIES) continue
            webViewTries++

            val interceptedUrl = try {
                app.get(
                    iframeUrl,
                    referer = url,
                    headers = pageHeaders(url),
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

            Log.d(TAG, "WebView sonucu ($iframeUrl): $interceptedUrl")
            if (!interceptedUrl.isNullOrBlank() &&
                interceptedUrl.contains(".m3u8", ignoreCase = true)
            ) {
                emitM3u8(interceptedUrl, callback)
                return
            }
        }

        Log.w(TAG, "Tüm yöntemler başarısız: $url")
    }
}
