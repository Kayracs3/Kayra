package com.Kayracs3

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * ClipBox
 *
 * TMDB is used for catalogue/metadata through TmdbProvider.
 * VixSrc is used for the actual playable stream.
 *
 * The important part here is that TmdbProvider's default load() can receive
 * a TMDB URL, while CloudStream may carry a TmdbLink JSON between search()
 * and load(). We normalize that JSON back into a real TMDB URL before asking
 * the parent TmdbProvider to build the detail response.
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
     * Internal transport object used only between search/load/loadLinks.
     * type removes the ambiguity between a TMDB movie and a TMDB TV item when
     * the object does not yet contain season/episode information.
     */
    private data class ClipTmdbLink(
        val imdbID: String? = null,
        val tmdbID: Int? = null,
        val episode: Int? = null,
        val season: Int? = null,
        val movieName: String? = null,
        val type: String? = null
    )

    private fun parseTmdbLink(data: String): TmdbLink? {
        return runCatching {
            parseJson<TmdbLink>(data.trim())
        }.getOrNull()
    }

    private fun parseClipTmdbLink(data: String): ClipTmdbLink? {
        return runCatching {
            parseJson<ClipTmdbLink>(data.trim())
        }.getOrNull()
    }

    /**
     * Fallback for forks/older builds that may pass a real TMDB URL.
     */
    private fun parseTmdbUrl(data: String): Triple<Int, String, Pair<Int?, Int?>?>? {
        val movie = Regex(
            """themoviedb\.org/movie/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (movie != null) {
            return Triple(movie.groupValues[1].toIntOrNull() ?: return null, "movie", null)
        }

        val tv = Regex(
            """themoviedb\.org/tv/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (tv != null) {
            return Triple(tv.groupValues[1].toIntOrNull() ?: return null, "tv", null)
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
     * Extract one JS/JSON field from a block of HTML.
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
                """(?:["']?$field["']?)\s*:\s*(\\d+)""",
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
     * Builds the signed VixSrc HLS URL from window.masterPlaylist.
     */
    private fun extractMasterPlaylist(html: String): String? {
        val normalized = cleanEmbeddedValue(html)

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
                val result = cleanEmbeddedValue(found)
                if (result.contains(".m3u8", true)) return result
                if (result.contains("/playlist/", true)) {
                    var playlist = result
                    if (!playlist.contains("h=", true)) playlist += "&h=1"
                    if (!playlist.contains("lang=", true)) playlist += "&lang=en"
                    return playlist
                }
            }
        }

        return Regex(
            """<script[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).mapNotNull { match ->
            val script = cleanEmbeddedValue(match.groupValues.getOrNull(1).orEmpty())
            Regex(
                """https?://[^\s\"'<>]+(?:\.m3u8|/playlist/)[^\s\"'<>]*""",
                RegexOption.IGNORE_CASE
            ).find(script)?.value?.trimEnd(')', ']', '}', ';', ',')?.let { candidate ->
                var result = candidate
                if (result.contains("/playlist/", true)) {
                    if (!result.contains("h=", true)) result += "&h=1"
                    if (!result.contains("lang=", true)) result += "&lang=en"
                }
                result
            }
        }.firstOrNull()
    }

    private fun buildVixsrcPlayerUrl(
        tmdbId: Int,
        type: String?,
        season: Int?,
        episode: Int?
    ): String {
        val isTv = type.equals("tv", true) || (season != null && episode != null)

        return if (isTv) {
            if (season == null || episode == null) return ""
            "$VIXSRC_URL/tv/$tmdbId/$season/$episode"
        } else {
            "$VIXSRC_URL/movie/$tmdbId"
        }
    }

    /**
     * Rewrites TmdbProvider's search URLs into a JSON that also carries the
     * media type. The inherited TmdbProvider load() can therefore be fed a
     * deterministic TMDB URL later.
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        val results = runCatching { super.search(query) }.getOrNull() ?: return null

        return results.mapNotNull { result ->
            val type = if (result.type == TvType.TvSeries) "tv" else "movie"
            val data = ClipTmdbLink(
                tmdbID = when (result) {
                    is com.lagradost.cloudstream3.MovieSearchResponse -> result.id
                    is com.lagradost.cloudstream3.TvSeriesSearchResponse -> result.id
                    else -> null
                },
                movieName = result.name,
                type = type
            )

            // If the inherited result doesn't expose a numeric ID, keep the
            // original URL so the parent implementation can still handle it.
            val tmdbId = data.tmdbID
            val url = if (tmdbId != null) {
                data.toJson()
            } else {
                result.url
            }

            if (type == "tv") {
                newTvSeriesSearchResponse(result.name, url, TvType.TvSeries) {
                    this.posterUrl = result.posterUrl
                }
            } else {
                newMovieSearchResponse(result.name, url, TvType.Movie) {
                    this.posterUrl = result.posterUrl
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /**
     * Fix the main reason for the original ErrorLoadingException:
     * TmdbProvider can load a real TMDB URL, but ClipBox search results may
     * contain a TmdbLink JSON. Normalize that JSON and ask the parent provider
     * to construct the full detail/episode response.
     *
     * We try the normalized URL first and fall back to the original value so
     * older CloudStream builds remain compatible.
     */
    override suspend fun load(url: String): LoadResponse? {
        val clip = parseClipTmdbLink(url)

        if (clip?.tmdbID != null) {
            val type = clip.type ?: if (clip.season != null || clip.episode != null) "tv" else "movie"
            val tmdbUrl = if (type.equals("tv", true)) {
                "https://www.themoviedb.org/tv/${clip.tmdbID}"
            } else {
                "https://www.themoviedb.org/movie/${clip.tmdbID}"
            }

            val normalized = runCatching { super.load(tmdbUrl) }.getOrNull()
            if (normalized != null) return normalized
        }

        return runCatching { super.load(url) }.getOrNull()
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
        var type: String? = null

        // Preferred path: our own transport JSON.
        val clip = parseClipTmdbLink(data)
        if (clip != null) {
            tmdbId = clip.tmdbID
            season = clip.season
            episode = clip.episode
            type = clip.type
        }

        // Standard CloudStream TmdbLink path from TmdbProvider-generated
        // episode/movie responses.
        if (tmdbId == null) {
            val tmdbLink = parseTmdbLink(data)
            if (tmdbLink != null) {
                tmdbId = tmdbLink.tmdbID
                season = tmdbLink.season
                episode = tmdbLink.episode
                type = if (season != null && episode != null) "tv" else "movie"
            }
        }

        // Last compatibility path: direct TMDB URL.
        if (tmdbId == null) {
            val parsed = parseTmdbUrl(data)
            if (parsed != null) {
                tmdbId = parsed.first
                type = parsed.second
            }
        }

        val id = tmdbId ?: return false

        val playerUrl = buildVixsrcPlayerUrl(
            tmdbId = id,
            type = type,
            season = season,
            episode = episode
        )

        if (playerUrl.isBlank()) return false

        val response = runCatching {
            app.get(
                url = playerUrl,
                headers = VIXSRC_HEADERS,
                referer = "$VIXSRC_URL/"
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..399) return false

        val html = response.text
        val streamUrl = extractMasterPlaylist(html) ?: return false
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
