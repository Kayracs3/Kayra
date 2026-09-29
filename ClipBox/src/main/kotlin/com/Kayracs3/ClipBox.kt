package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class ClipBox : TmdbProvider() {

    companion object {

        private const val TAG = "ClipBox"

        private const val VIXSRC_URL =
            "https://vixsrc.to"

        private const val DEFAULT_TIMEOUT =
            60000L

        private val VIXSRC_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$VIXSRC_URL/",
            "Origin" to VIXSRC_URL,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache"
        )
    }

    override var name: String = "ClipBox"

    override var lang: String = "tr"

    override var mainUrl: String =
        "https://www.themoviedb.org"

    override val hasMainPage: Boolean =
        true

    override val hasQuickSearch: Boolean =
        true

    override val providerType =
        ProviderType.DirectProvider

    override val supportedTypes: Set<TvType> =
        setOf(
            TvType.Movie,
            TvType.TvSeries
        )

    /*
     * TmdbProvider'ın kendi TMDB metadata sistemini kullanıyoruz.
     *
     * Böylece:
     * - film bilgileri
     * - dizi bilgileri
     * - bölümler
     * - posterler
     * - puanlar
     * - oyuncular
     * - fragmanlar
     *
     * TmdbProvider tarafından alınmaya devam eder.
     */
    override val useMetaLoadResponse: Boolean =
        true

    init {
        Log.e(
            TAG,
            "========== CLIPBOX CLASS CREATED =========="
        )
    }

    /*
     * =========================================================
     * LOAD
     * =========================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.e(
            TAG,
            "========== CLIPBOX LOAD CALLED =========="
        )

        Log.e(
            TAG,
            "LOAD URL = $url"
        )

        return try {

            val result =
                super.load(url)

            Log.e(
                TAG,
                "TMDB LOAD RESULT = ${result?.name}"
            )

            result

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "CLIPBOX LOAD ERROR: ${throwable.message}",
                throwable
            )

            null
        }
    }

    /*
     * =========================================================
     * VIXSRC URL
     * =========================================================
     */

    private fun buildVixsrcUrl(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String {

        return if (
            season != null &&
            episode != null
        ) {

            "$VIXSRC_URL/tv/$tmdbId/$season/$episode"

        } else {

            "$VIXSRC_URL/movie/$tmdbId"
        }
    }

    /*
     * =========================================================
     * URL TEMİZLEME
     * =========================================================
     */

    private fun cleanUrl(
        input: String
    ): String? {

        var value =
            input
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\u002F",
                    "/"
                )
                .replace(
                    "\\u002f",
                    "/"
                )
                .replace(
                    "\\u003A",
                    ":"
                )
                .replace(
                    "\\u003a",
                    ":"
                )
                .replace(
                    "\\u003F",
                    "?"
                )
                .replace(
                    "\\u003f",
                    "?"
                )
                .replace(
                    "\\u003D",
                    "="
                )
                .replace(
                    "\\u003d",
                    "="
                )
                .replace(
                    "\\u0026",
                    "&"
                )
                .replace(
                    "&amp;",
                    "&"
                )
                .replace(
                    "&quot;",
                    "\""
                )
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()

        value =
            value.trimEnd(
                ')',
                ']',
                '}',
                ';',
                ','
            )

        if (
            value.startsWith("//")
        ) {

            value =
                "https:$value"
        }

        if (
            value.startsWith("/")
        ) {

            value =
                "$VIXSRC_URL$value"
        }

        if (
            !value.startsWith(
                "http://",
                ignoreCase = true
            ) &&
            !value.startsWith(
                "https://",
                ignoreCase = true
            )
        ) {

            return null
        }

        return value
    }

    /*
     * =========================================================
     * PLAYLIST KONTROLÜ
     * =========================================================
     */

    private fun isPlaylistUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(
            "/playlist/"
        ) ||
            lower.contains(
                ".m3u8"
            )
    }

    /*
     * =========================================================
     * HTML FALLBACK
     * =========================================================
     *
     * WebView çalışmazsa eski tip HTML içerisinde
     * playlist / m3u8 aramayı da deniyoruz.
     */

    private fun extractPlaylistFromHtml(
        html: String
    ): String? {

        /*
         * 1. Doğrudan VixSrc playlist URL'si
         */
        val playlist =
            Regex(
                """https?://[^"'<>\s]+/playlist/[^"'<>\s]+""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.value

        if (
            !playlist.isNullOrBlank()
        ) {

            val cleaned =
                cleanUrl(playlist)

            if (
                !cleaned.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "HTML PLAYLIST = $cleaned"
                )

                return cleaned
            }
        }

        /*
         * 2. Doğrudan m3u8
         */
        val m3u8 =
            Regex(
                """https?://[^"'<>\s]+\.m3u8(?:\?[^"'<>\s]*)?""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.value

        if (
            !m3u8.isNullOrBlank()
        ) {

            val cleaned =
                cleanUrl(m3u8)

            if (
                !cleaned.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "HTML M3U8 = $cleaned"
                )

                return cleaned
            }
        }

        /*
         * 3. JSON benzeri url:"..."
         */
        val generic =
            Regex(
                """["'](?:url|file|src|playlist|m3u8)["']\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        if (
            !generic.isNullOrBlank()
        ) {

            val cleaned =
                cleanUrl(generic)

            if (
                !cleaned.isNullOrBlank() &&
                isPlaylistUrl(cleaned)
            ) {

                Log.e(
                    TAG,
                    "HTML GENERIC PLAYLIST = $cleaned"
                )

                return cleaned
            }
        }

        return null
    }

    /*
     * =========================================================
     * WEBVIEW VIXSRC RESOLVER
     * =========================================================
     *
     * Güncel VixSrc sayfası JavaScript ile çalıştığı için
     * normal app.get() ile HTML almak çoğu zaman sadece
     * Next.js başlangıç sayfasını döndürüyor.
     *
     * WebViewResolver JavaScript'i çalıştırıyor ve
     * oluşan /playlist/ isteğini yakalıyor.
     */

    private suspend fun resolveVixsrcWithWebView(
        pageUrl: String
    ): String? {

        Log.e(
            TAG,
            "========== VIXSRC WEBVIEW START =========="
        )

        Log.e(
            TAG,
            "WEBVIEW URL = $pageUrl"
        )

        var interceptedUrl: String? = null

        val resolver =
            WebViewResolver(
                interceptUrl =
                    Regex(
                        """/playlist/"""
                    ),

                additionalUrls =
                    listOf(
                        Regex(
                            """/playlist/"""
                        ),
                        Regex(
                            """\.m3u8(?:\?.*)?$""",
                            RegexOption.IGNORE_CASE
                        )
                    ),

                /*
                 * null -> WebView'in kendi gerçek User-Agent'ını kullan.
                 */
                userAgent = null,

                /*
                 * VixSrc JavaScript sayfası için
                 * WebView ağ yapısını doğrudan kullan.
                 */
                useOkhttp = false,

                timeout =
                    DEFAULT_TIMEOUT
            )

        return try {

            val result =
                resolver.resolveUsingWebView(
                    url = pageUrl,
                    referer = "$VIXSRC_URL/",
                    headers = VIXSRC_HEADERS,
                    method = "GET"
                ) { request ->

                    val requestUrl =
                        request.url.toString()

                    Log.e(
                        TAG,
                        "WEBVIEW REQUEST = $requestUrl"
                    )

                    if (
                        requestUrl.contains(
                            "/playlist/",
                            ignoreCase = true
                        ) ||
                        requestUrl.contains(
                            ".m3u8",
                            ignoreCase = true
                        )
                    ) {

                        interceptedUrl =
                            requestUrl

                        Log.e(
                            TAG,
                            "========== VIXSRC PLAYLIST INTERCEPTED =========="
                        )

                        Log.e(
                            TAG,
                            requestUrl
                        )

                        true

                    } else {

                        false
                    }
                }

            /*
             * Önce callback tarafından yakalanan URL.
             */
            var resolvedUrl =
                interceptedUrl

            /*
             * Resolver'ın final request'i.
             */
            if (
                resolvedUrl.isNullOrBlank()
            ) {

                val finalRequest =
                    result.first

                if (
                    finalRequest != null
                ) {

                    val finalUrl =
                        finalRequest.url.toString()

                    Log.e(
                        TAG,
                        "WEBVIEW FINAL REQUEST = $finalUrl"
                    )

                    if (
                        isPlaylistUrl(finalUrl)
                    ) {

                        resolvedUrl =
                            finalUrl
                    }
                }
            }

            /*
             * Additional URL'lerden bul.
             */
            if (
                resolvedUrl.isNullOrBlank()
            ) {

                val collected =
                    result.second

                Log.e(
                    TAG,
                    "WEBVIEW COLLECTED REQUESTS = ${collected.size}"
                )

                for (
                    request in collected
                ) {

                    val requestUrl =
                        request.url.toString()

                    Log.d(
                        TAG,
                        "WEBVIEW ADDITIONAL = $requestUrl"
                    )

                    if (
                        isPlaylistUrl(requestUrl)
                    ) {

                        Log.e(
                            TAG,
                            "WEBVIEW ADDITIONAL PLAYLIST = $requestUrl"
                        )

                        resolvedUrl =
                            requestUrl

                        break
                    }
                }
            }

            resolvedUrl

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "WEBVIEW RESOLVER ERROR: ${throwable.message}",
                throwable
            )

            null
        }
    }

    /*
     * =========================================================
     * NORMAL HTML FALLBACK
     * =========================================================
     */

    private suspend fun resolveVixsrcHtml(
        pageUrl: String
    ): String? {

        Log.e(
            TAG,
            "========== VIXSRC HTML FALLBACK =========="
        )

        Log.e(
            TAG,
            "HTML URL = $pageUrl"
        )

        return try {

            val response =
                com.lagradost.cloudstream3.app.get(
                    url = pageUrl,
                    headers = VIXSRC_HEADERS
                )

            Log.e(
                TAG,
                "VIXSRC HTML RESPONSE = ${response.code}"
            )

            val html =
                response.text

            Log.e(
                TAG,
                "VIXSRC HTML LENGTH = ${html.length}"
            )

            val result =
                extractPlaylistFromHtml(
                    html
                )

            if (
                result.isNullOrBlank()
            ) {

                Log.d(
                    TAG,
                    "No playlist found in static HTML"
                )
            }

            result

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "VIXSRC HTML ERROR: ${throwable.message}",
                throwable
            )

            null
        }
    }

    /*
     * =========================================================
     * LOAD LINKS
     * =========================================================
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.e(
            TAG,
            "========== LOADLINKS CALLED =========="
        )

        Log.e(
            TAG,
            "DATA = $data"
        )

        Log.e(
            TAG,
            "IS CASTING = $isCasting"
        )

        val tmdbLink =
            try {

                parseJson<TmdbLink>(
                    data
                )

            } catch (throwable: Throwable) {

                Log.e(
                    TAG,
                    "TMDB LINK PARSE ERROR: ${throwable.message}",
                    throwable
                )

                return false
            }

        /*
         * tmdbID nullable olduğu için burada
         * kesin olarak Int'e düşürüyoruz.
         */
        val tmdbId =
            tmdbLink.tmdbID
                ?: run {

                    Log.e(
                        TAG,
                        "TMDB ID = NULL"
                    )

                    return false
                }

        val season =
            tmdbLink.season

        val episode =
            tmdbLink.episode

        Log.e(
            TAG,
            "TMDB ID = $tmdbId"
        )

        Log.e(
            TAG,
            "SEASON = $season"
        )

        Log.e(
            TAG,
            "EPISODE = $episode"
        )

        val pageUrl =
            buildVixsrcUrl(
                tmdbId = tmdbId,
                season = season,
                episode = episode
            )

        Log.e(
            TAG,
            "VIXSRC PAGE = $pageUrl"
        )

        /*
         * =====================================================
         * 1. WEBVIEW
         * =====================================================
         */

        var finalUrl =
            resolveVixsrcWithWebView(
                pageUrl
            )

        /*
         * =====================================================
         * 2. STATIK HTML FALLBACK
         * =====================================================
         */

        if (
            finalUrl.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "WEBVIEW STREAM NOT FOUND"
            )

            finalUrl =
                resolveVixsrcHtml(
                    pageUrl
                )
        }

        /*
         * =====================================================
         * 3. SON KONTROL
         * =====================================================
         */

        val rawFinalUrl =
            finalUrl
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: run {

                    Log.e(
                        TAG,
                        "========== VIXSRC STREAM NOT FOUND =========="
                    )

                    return false
                }

        /*
         * Nullable String'i kesin olarak
         * String'e çeviriyoruz.
         */
        val playlistUrl =
            cleanUrl(
                rawFinalUrl
            )
                ?: run {

                    Log.e(
                        TAG,
                        "FINAL URL CLEAN FAILED"
                    )

                    return false
                }

        Log.e(
            TAG,
            "========== FINAL VIXSRC URL =========="
        )

        Log.e(
            TAG,
            playlistUrl
        )

        if (
            !isPlaylistUrl(
                playlistUrl
            )
        ) {

            Log.e(
                TAG,
                "FINAL URL IS NOT PLAYLIST = $playlistUrl"
            )

            return false
        }

        /*
         * =====================================================
         * STREAM HEADERS
         * =====================================================
         */

        val streamHeaders =
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageUrl,
                "Origin" to VIXSRC_URL,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9"
            )

        /*
         * =====================================================
         * EXTRACTOR LINK
         * =====================================================
         */

        newExtractorLink(
            source = name,
            name = "ClipBox • VixSrc",
            url = playlistUrl,
            type = ExtractorLinkType.M3U8
        ) {

            referer =
                pageUrl

            headers =
                streamHeaders

            quality =
                Qualities.Unknown.value
        }.let(
            callback
        )

        Log.e(
            TAG,
            "========== VIXSRC LINK SENT =========="
        )

        return true
    }
}
