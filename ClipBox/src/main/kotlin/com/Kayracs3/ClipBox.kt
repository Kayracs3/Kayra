package com.Kayracs3

import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * ClipBox
 *
 * TMDB is used only for catalogue/metadata.
 * VixSrc is used for the actual playable stream.
 *
 * Playback flow:
 *
 * TMDB TmdbLink JSON
 *      -> VixSrc /movie/{tmdbId}
 *         or /tv/{tmdbId}/{season}/{episode}
 *      -> window.masterPlaylist
 *      -> playlist URL + token + expires
 *      -> signed HLS URL
 *      -> CloudStream ExoPlayer
 *
 * IMPORTANT:
 * Never return the TMDB detail URL as an ExtractorLink.
 */
class ClipBox : TmdbProvider() {

    companion object {
        private const val VIXSRC_URL = "https://vixsrc.to"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/153.0.0.0 Mobile Safari/537.36"

        private val VIXSRC_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$VIXSRC_URL/",
            "Origin" to VIXSRC_URL,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val STREAM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$VIXSRC_URL/",
            "Origin" to VIXSRC_URL,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }

    override var name: String = "ClipBox"
    override var lang: String = "tr"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true
    override val providerType = ProviderType.DirectProvider

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * Parse the TmdbProvider data normally produced by CloudStream.
     * Example:
     * {
     *   "imdbID":"tt...",
     *   "tmdbID":12345,
     *   "episode":1,
     *   "season":1,
     *   "movieName":"..."
     * }
     */
    private fun parseTmdbLink(data: String): TmdbLink? {
        return runCatching {
            parseJson<TmdbLink>(data.trim())
        }.getOrNull()
    }

    /**
     * Small compatibility fallback in case a fork passes the TMDB URL itself
     * instead of the normal TmdbLink JSON.
     */
    private fun parseTmdbUrl(data: String): Triple<Int, Int?, Int?>? {
        val movie = Regex(
            """themoviedb\.org/movie/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (movie != null) {
            return Triple(movie.groupValues[1].toIntOrNull() ?: return null, null, null)
        }

        val tv = Regex(
            """themoviedb\.org/tv/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (tv != null) {
            return Triple(tv.groupValues[1].toIntOrNull() ?: return null, null, null)
        }

        return null
    }

    private fun cleanEmbeddedValue(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&#x26;", "&")
            .replace("&quot;", "\"")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    private fun toVixsrcUrl(value: String): String {
        val url = cleanEmbeddedValue(value)

        return when {
            url.startsWith("https://", true) || url.startsWith("http://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$VIXSRC_URL$url"
            else -> "$VIXSRC_URL/${url.trimStart('/')}"
        }
    }

    /**
     * Extract a field from both JavaScript-object and JSON notation.
     * Accepts quoted and numeric expires values.
     */
    private fun extractField(html: String, field: String): String? {
        val patterns = listOf(
            Regex(
                """(?:["']?$field["']?)\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:["']?$field["']?)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:["']?$field["']?)\s*:\s*(\d+)""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return cleanEmbeddedValue(value)
        }

        return null
    }

    /**
     * Build the signed playlist URL from the current VixSrc page.
     *
     * Current VixSrc integrations expose window.masterPlaylist with:
     *   url
     *   token
     *   expires
     *
     * The generated URL follows the working VixSrc pattern:
     *   {url}?token=...&expires=...&h=1&lang=en
     */
    private fun extractMasterPlaylist(html: String): String? {
        val normalized = cleanEmbeddedValue(html)

        // Primary path: scope extraction to the masterPlaylist block so an
        // unrelated `url:` elsewhere in the page cannot be mistaken for the stream.
        val masterMatch = Regex(
            """masterPlaylist""",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        if (masterMatch != null) {
            val startIndex = maxOf(0, masterMatch.range.first - 300)
            val endIndex = minOf(normalized.length, masterMatch.range.last + 5000)
            val masterBlock = normalized.substring(startIndex, endIndex)

            val baseUrl = extractField(masterBlock, "url")
            val token = extractField(masterBlock, "token") ?: ""
            val expires = extractField(masterBlock, "expires")

            if (!baseUrl.isNullOrBlank() && !expires.isNullOrBlank()) {
                var result = toVixsrcUrl(baseUrl)

                if (!result.contains("token=", ignoreCase = true)) {
                    result += "${if (result.contains("?")) "&" else "?"}token=$token"
                }

                if (!result.contains("expires=", ignoreCase = true)) {
                    result += "&expires=$expires"
                }

                if (!result.contains("h=", ignoreCase = true)) {
                    result += "&h=1"
                }

                if (!result.contains("lang=", ignoreCase = true)) {
                    result += "&lang=en"
                }

                return result
            }
        }

        // Secondary path: direct playlist/m3u8 URL in the HTML.
        val directPatterns = listOf(
            Regex(
                """https?://[^\s\"'<>]+\.m3u8(?:\?[^\s\"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://[^\s\"'<>]+/playlist/[^\s\"'<>]+""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in directPatterns) {
            val found = pattern.find(normalized)?.value?.trimEnd(')', ']', '}', ';', ',')
            if (!found.isNullOrBlank()) {
                var result = cleanEmbeddedValue(found)
                if (result.contains(".m3u8", true) && !result.contains("token=", true)) {
                    // Do not invent a token for a complete direct m3u8 URL.
                    return result
                }
                if (result.contains("/playlist/", true)) {
                    if (!result.contains("h=", true)) result += "&h=1"
                    if (!result.contains("lang=", true)) result += "&lang=en"
                    return result
                }
            }
        }

        // Third path: inspect script tags. This catches escaped playlist URLs.
        Regex(
            """<script[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).forEach { match ->
            val script = cleanEmbeddedValue(match.groupValues.getOrNull(1).orEmpty())

            val candidate = Regex(
                """https?://[^\s\"'<>]+(?:\.m3u8|/playlist/)[^\s\"'<>]*""",
                RegexOption.IGNORE_CASE
            ).find(script)?.value

            if (!candidate.isNullOrBlank()) {
                var result = candidate.trimEnd(')', ']', '}', ';', ',')
                if (result.contains("/playlist/", true) && !result.contains("h=", true)) {
                    result += "&h=1"
                }
                if (result.contains("/playlist/", true) && !result.contains("lang=", true)) {
                    result += "&lang=en"
                }
                if (result.contains(".m3u8", true) || result.contains("/playlist/", true)) {
                    return result
                }
            }
        }

        return null
    }

    /**
     * Construct the exact VixSrc player URL.
     */
    private fun buildVixsrcPlayerUrl(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String {
        return if (season != null && episode != null) {
            "$VIXSRC_URL/tv/$tmdbId/$season/$episode"
        } else {
            "$VIXSRC_URL/movie/$tmdbId"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var tmdbId: Int? = null
        var season: Int? = null
        var episode: Int? = null

        // Normal CloudStream TmdbProvider path.
        val tmdbLink = parseTmdbLink(data)
        if (tmdbLink != null) {
            tmdbId = tmdbLink.tmdbID
            season = tmdbLink.season
            episode = tmdbLink.episode
        }

        // Compatibility fallback.
        if (tmdbId == null) {
            val parsedUrl = parseTmdbUrl(data)
            if (parsedUrl != null) {
                tmdbId = parsedUrl.first
                season = parsedUrl.second
                episode = parsedUrl.third
            }
        }

        val id = tmdbId ?: return false
        val playerUrl = buildVixsrcPlayerUrl(id, season, episode)

        val response = runCatching {
            app.get(
                url = playerUrl,
                headers = VIXSRC_HEADERS
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..399) return false

        val html = response.text
        val streamUrl = extractMasterPlaylist(html) ?: return false

        // Absolute media URLs only. The TMDB page is never returned here.
        val finalUrl = toVixsrcUrl(streamUrl)
        if (!finalUrl.startsWith("https://", true) &&
            !finalUrl.startsWith("http://", true)
        ) {
            return false
        }

        val looksPlayable =
            finalUrl.contains(".m3u8", ignoreCase = true) ||
                finalUrl.contains("/playlist/", ignoreCase = true)

        if (!looksPlayable) return false
        if (finalUrl.contains("themoviedb.org", ignoreCase = true)) return false

        newExtractorLink(
            source = name,
            name = "ClipBox • VixSrc",
            url = finalUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "$VIXSRC_URL/"
            headers = STREAM_HEADERS
            quality = Qualities.Unknown.value
        }.let(callback)

        return true
    }
}
