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
 * TMDB is used for catalogue/metadata and episode generation.
 * VixSrc is used for the actual playable stream.
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

    /*
     * This is the important fix.
     *
     * TmdbProvider's default load() only returns a LoadResponse when
     * useMetaLoadResponse is enabled, unless a child provider implements
     * loadFromTmdb/loadFromImdb itself. ClipBox does not need a separate
     * metadata provider, so let TmdbProvider build the movie/series response.
     * This also generates TmdbLink JSON for loadLinks().
     */
    override val useMetaLoadResponse: Boolean = true

    private fun parseTmdbLink(data: String): TmdbLink? {
        return runCatching {
            parseJson<TmdbLink>(data.trim())
        }.getOrNull()
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

    private fun appendParam(url: String, key: String, value: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$key=$value"
    }

    /**
     * Extract a field from a JS/JSON block.
     * Handles both quoted strings and numeric expires values.
     */
    private fun extractField(html: String, field: String): String? {
        val stringPattern = Regex(
            """(?:[\"']?$field[\"']?)\s*:\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        )
        stringPattern.find(html)?.groupValues?.getOrNull(1)?.let {
            return cleanEmbeddedValue(it)
        }

        val assignPattern = Regex(
            """(?:[\"']?$field[\"']?)\s*=\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        )
        assignPattern.find(html)?.groupValues?.getOrNull(1)?.let {
            return cleanEmbeddedValue(it)
        }

        val numberPattern = Regex(
            """(?:[\"']?$field[\"']?)\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
        numberPattern.find(html)?.groupValues?.getOrNull(1)?.let {
            return cleanEmbeddedValue(it)
        }

        return null
    }

    /**
     * VixSrc exposes the playable playlist through window.masterPlaylist.
     * Current format uses url + token + expires; direct playlist URLs are
     * supported as a fallback.
     */
    private fun extractMasterPlaylist(html: String): String? {
        val normalized = cleanEmbeddedValue(html)

        val masterMatch = Regex(
            """masterPlaylist""",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        if (masterMatch != null) {
            val startIndex = maxOf(0, masterMatch.range.first - 500)
            val endIndex = minOf(normalized.length, masterMatch.range.last + 7000)
            val masterBlock = normalized.substring(startIndex, endIndex)

            val baseUrl = extractField(masterBlock, "url")
            val token = extractField(masterBlock, "token")
            val expires = extractField(masterBlock, "expires")

            if (!baseUrl.isNullOrBlank() && !token.isNullOrBlank() && !expires.isNullOrBlank()) {
                var result = toVixsrcUrl(baseUrl)

                if (!result.contains("token=", ignoreCase = true)) {
                    result = appendParam(result, "token", token)
                }

                if (!result.contains("expires=", ignoreCase = true)) {
                    result = appendParam(result, "expires", expires)
                }

                if (!result.contains("h=", ignoreCase = true)) {
                    result = appendParam(result, "h", "1")
                }

                if (!result.contains("lang=", ignoreCase = true)) {
                    result = appendParam(result, "lang", "en")
                }

                return result
            }
        }

        // Fallback: direct .m3u8 / playlist URL embedded in page source.
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
            val found = pattern.find(normalized)
                ?.value
                ?.trimEnd(')', ']', '}', ';', ',')

            if (found.isNullOrBlank()) continue

            var result = cleanEmbeddedValue(found)

            if (result.contains("/playlist/", true)) {
                if (!result.contains("h=", true)) {
                    result = appendParam(result, "h", "1")
                }
                if (!result.contains("lang=", true)) {
                    result = appendParam(result, "lang", "en")
                }
            }

            return result
        }

        // Last fallback: inspect script blocks separately for escaped URLs.
        val scriptRegex = Regex(
            """<script[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val urlRegex = Regex(
            """https?://[^\s\"'<>]+(?:\.m3u8|/playlist/)[^\s\"'<>]*""",
            RegexOption.IGNORE_CASE
        )

        scriptRegex.findAll(html).forEach { match ->
            val script = cleanEmbeddedValue(match.groupValues.getOrNull(1).orEmpty())
            val found = urlRegex.find(script)?.value
                ?.trimEnd(')', ']', '}', ';', ',')
                ?: return@forEach

            var result = found
            if (result.contains("/playlist/", true)) {
                if (!result.contains("h=", true)) {
                    result = appendParam(result, "h", "1")
                }
                if (!result.contains("lang=", true)) {
                    result = appendParam(result, "lang", "en")
                }
            }
            return result
        }

        return null
    }

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
        val tmdbLink = parseTmdbLink(data) ?: return false
        val tmdbId = tmdbLink.tmdbID ?: return false
        val playerUrl = buildVixsrcPlayerUrl(
            tmdbId = tmdbId,
            season = tmdbLink.season,
            episode = tmdbLink.episode
        )

        println("ClipBox: loading VixSrc $playerUrl")

        val response = runCatching {
            app.get(
                url = playerUrl,
                headers = VIXSRC_HEADERS,
                referer = "$VIXSRC_URL/",
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..399) {
            println("ClipBox: VixSrc HTTP ${response.code}")
            return false
        }

        val streamUrl = extractMasterPlaylist(response.text) ?: return false
        val finalUrl = toVixsrcUrl(streamUrl)

        if (!finalUrl.startsWith("https://", true) &&
            !finalUrl.startsWith("http://", true)
        ) {
            return false
        }

        if (!finalUrl.contains(".m3u8", ignoreCase = true) &&
            !finalUrl.contains("/playlist/", ignoreCase = true)
        ) {
            return false
        }

        if (finalUrl.contains("themoviedb.org", ignoreCase = true)) {
            return false
        }

        println("ClipBox: stream=$finalUrl")

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
