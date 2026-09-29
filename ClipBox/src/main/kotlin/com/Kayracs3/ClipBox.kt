package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class ClipBox : TmdbProvider() {

    companion object {

        private const val TAG = "ClipBox"

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

    init {
        Log.e(TAG, "========== CLIPBOX CLASS CREATED ==========")
        Log.e(TAG, "ClipBox provider instance initialized")
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

    private fun parseTmdbLink(
        data: String
    ): TmdbLink? {

        return runCatching {
            parseJson<TmdbLink>(
                data.trim()
            )
        }.onFailure {
            Log.d(
                TAG,
                "TMDB JSON parse failed: ${it.message}"
            )
        }.getOrNull()
    }

    private fun parseTmdbUrl(
        data: String
    ): Triple<Int, Int?, Int?>? {

        val movie = Regex(
            """themoviedb\.org/movie/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (movie != null) {

            val id = movie.groupValues
                .getOrNull(1)
                ?.toIntOrNull()

            if (id != null) {
                return Triple(
                    id,
                    null,
                    null
                )
            }
        }

        val tv = Regex(
            """themoviedb\.org/tv/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(data)

        if (tv != null) {

            val id = tv.groupValues
                .getOrNull(1)
                ?.toIntOrNull()

            if (id != null) {
                return Triple(
                    id,
                    null,
                    null
                )
            }
        }

        return null
    }

    private fun cleanEmbeddedValue(
        value: String
    ): String {

        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("\\u003F", "?")
            .replace("\\u003f", "?")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .replace("&#x26;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    private fun toVixsrcUrl(
        value: String
    ): String {

        val url = cleanEmbeddedValue(value)

        return when {

            url.startsWith(
                "https://",
                true
            ) ||
                url.startsWith(
                    "http://",
                    true
                ) -> {
                url
            }

            url.startsWith("//") -> {
                "https:$url"
            }

            url.startsWith("/") -> {
                "$VIXSRC_URL$url"
            }

            else -> {
                "$VIXSRC_URL/${url.trimStart('/')}"
            }
        }
    }

    private fun extractField(
        html: String,
        field: String
    ): String? {

        val patterns = listOf(

            Regex(
                """["']?$field["']?\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["']?$field["']?\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["']?$field["']?\s*:\s*`([^`]+)`""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["']?$field["']?\s*=\s*`([^`]+)`""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["']?$field["']?\s*:\s*([^,\}\n]+)""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            val value = pattern
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!value.isNullOrBlank()) {

                val cleaned =
                    cleanEmbeddedValue(value)

                if (cleaned.isNotBlank()) {
                    return cleaned
                }
            }
        }

        return null
    }

    private fun isPlayableStreamUrl(
        url: String
    ): Boolean {

        val value = url.lowercase()

        if (value.contains(".m3u8")) {
            return true
        }

        if (value.contains("/playlist/")) {
            return true
        }

        if (
            value.contains("/hls/") &&
            (
                value.contains("master") ||
                    value.contains("playlist") ||
                    value.contains(".m3u")
            )
        ) {
            return true
        }

        return false
    }

    private fun cleanStreamUrl(
        url: String
    ): String? {

        var result =
            cleanEmbeddedValue(url)

        result = result
            .trim()
            .trimEnd(
                ')',
                ']',
                '}',
                ';',
                ','
            )

        if (result.startsWith("//")) {
            result = "https:$result"
        }

        if (result.startsWith("/")) {
            result = "$VIXSRC_URL$result"
        }

        if (
            !result.startsWith(
                "http://",
                true
            ) &&
            !result.startsWith(
                "https://",
                true
            )
        ) {
            return null
        }

        return result
    }

    private fun addCandidate(
        list: MutableList<String>,
        value: String?
    ) {

        if (value.isNullOrBlank()) {
            return
        }

        val cleaned =
            cleanStreamUrl(value)
                ?: return

        if (!isPlayableStreamUrl(cleaned)) {
            return
        }

        if (
            cleaned.contains(
                "themoviedb.org",
                true
            )
        ) {
            return
        }

        if (
            cleaned.contains(
                "image",
                true
            ) &&
            !cleaned.contains(
                ".m3u8",
                true
            )
        ) {
            return
        }

        if (!list.contains(cleaned)) {

            list.add(cleaned)

            Log.d(
                TAG,
                "STREAM CANDIDATE = $cleaned"
            )
        }
    }

    private fun extractAbsoluteStreamUrls(
        html: String,
        candidates: MutableList<String>
    ) {

        val patterns = listOf(

            Regex(
                """https?://[^"'<>\\\s]+?\.m3u8(?:\?[^"'<>\\\s]*)?""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """https?://[^"'<>\\\s]+?/playlist/[^"'<>\\\s]+""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """https?:\\/\\/[^"'<>\\\s]+?\\.m3u8(?:\?[^"'<>\\\s]*)?""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """https?:\\/\\/[^"'<>\\\s]+?\\/playlist\\/[^"'<>\\\s]+""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            pattern.findAll(html).forEach { match ->

                addCandidate(
                    candidates,
                    match.value
                )
            }
        }
    }

    private fun extractRelativeStreamUrls(
        html: String,
        candidates: MutableList<String>
    ) {

        val patterns = listOf(

            Regex(
                """["'`](/[^"'`\\\s]*\.m3u8(?:\?[^"'`\\\s]*)?)["'`]""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["'`](/[^"'`\\\s]*/playlist/[^"'`\\\s]*)["'`]""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["'`]([^"'`\\\s]*?/playlist/[^"'`\\\s]*)["'`]""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            pattern.findAll(html).forEach { match ->

                addCandidate(
                    candidates,
                    match.groupValues
                        .getOrNull(1)
                )
            }
        }
    }

    private fun extractByFieldNames(
        html: String,
        candidates: MutableList<String>
    ) {

        val fieldNames = listOf(
            "masterPlaylist",
            "master_playlist",
            "playlist",
            "playlistUrl",
            "playlistURL",
            "playlist_url",
            "m3u8",
            "m3u8Url",
            "m3u8URL",
            "m3u8_url",
            "hls",
            "hlsUrl",
            "hlsURL",
            "hls_url",
            "stream",
            "streamUrl",
            "streamURL",
            "stream_url",
            "source",
            "sourceUrl",
            "sourceURL",
            "source_url",
            "file",
            "fileUrl",
            "fileURL",
            "file_url",
            "src",
            "videoUrl",
            "videoURL",
            "video_url",
            "playUrl",
            "playURL",
            "play_url"
        )

        for (field in fieldNames) {

            val value =
                extractField(
                    html = html,
                    field = field
                )

            if (!value.isNullOrBlank()) {

                Log.d(
                    TAG,
                    "FIELD [$field] = $value"
                )

                addCandidate(
                    candidates,
                    value
                )
            }
        }
    }

    private fun extractScriptStreams(
        html: String,
        candidates: MutableList<String>
    ) {

        val scriptRegex = Regex(
            """<script[^>]*>(.*?)</script>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )

        scriptRegex.findAll(html)
            .forEachIndexed { index, match ->

                val script =
                    match.groupValues
                        .getOrNull(1)
                        .orEmpty()

                if (script.isBlank()) {
                    return@forEachIndexed
                }

                Log.d(
                    TAG,
                    "SCRIPT[$index] length=${script.length}"
                )

                extractAbsoluteStreamUrls(
                    script,
                    candidates
                )

                extractRelativeStreamUrls(
                    script,
                    candidates
                )

                extractByFieldNames(
                    script,
                    candidates
                )
            }
    }

    private fun extractQuotedUrls(
        html: String,
        candidates: MutableList<String>
    ) {

        val quotedUrlRegex = Regex(
            """["'`]([^"'`]+)["'`]""",
            RegexOption.IGNORE_CASE
        )

        quotedUrlRegex.findAll(html)
            .forEach { match ->

                val value =
                    match.groupValues
                        .getOrNull(1)
                        ?.trim()
                        ?: return@forEach

                val cleaned =
                    cleanEmbeddedValue(value)

                if (
                    cleaned.contains(
                        ".m3u8",
                        true
                    ) ||
                    cleaned.contains(
                        "/playlist/",
                        true
                    )
                ) {

                    addCandidate(
                        candidates,
                        cleaned
                    )
                }
            }
    }

    private fun logInterestingHtml(
        html: String
    ) {

        val interestingLines = html
            .replace(
                "><",
                ">\n<"
            )
            .split('\n')
            .filter { line ->

                val lower =
                    line.lowercase()

                lower.contains("m3u8") ||
                    lower.contains("playlist") ||
                    lower.contains("hls") ||
                    lower.contains("source") ||
                    lower.contains("stream") ||
                    lower.contains("master") ||
                    lower.contains("video")
            }

        interestingLines
            .take(100)
            .forEachIndexed { index, line ->

                Log.d(
                    TAG,
                    "INTERESTING[$index]=${line.take(1500)}"
                )
            }
    }

    private fun extractMasterPlaylist(
        html: String
    ): String? {

        Log.d(
            TAG,
            "Starting VixSrc stream extraction..."
        )

        Log.d(
            TAG,
            "HTML length=${html.length}"
        )

        val candidates =
            mutableListOf<String>()

        extractAbsoluteStreamUrls(
            html,
            candidates
        )

        extractRelativeStreamUrls(
            html,
            candidates
        )

        extractByFieldNames(
            html,
            candidates
        )

        extractScriptStreams(
            html,
            candidates
        )

        extractQuotedUrls(
            html,
            candidates
        )

        if (candidates.isEmpty()) {

            Log.d(
                TAG,
                "No playable stream candidate found."
            )

            logInterestingHtml(
                html
            )
        }

        val selected =
            candidates
                .sortedWith(
                    compareByDescending<String> { url ->

                        when {

                            url.contains(
                                ".m3u8",
                                true
                            ) -> 3

                            url.contains(
                                "/playlist/",
                                true
                            ) -> 2

                            else -> 1
                        }
                    }
                )
                .firstOrNull()

        if (selected != null) {

            Log.d(
                TAG,
                "SELECTED STREAM = $selected"
            )

            return selected
        }

        Log.d(
            TAG,
            "playlist extraction failed"
        )

        return null
    }

    private fun buildVixsrcPlayerUrl(
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
            "data=$data"
        )

        Log.e(
            TAG,
            "isCasting=$isCasting"
        )

        Log.d(
            TAG,
            "loadLinks data=$data"
        )

        var tmdbId: Int? = null
        var season: Int? = null
        var episode: Int? = null

        val tmdbLink =
            parseTmdbLink(data)

        if (tmdbLink != null) {

            tmdbId =
                tmdbLink.tmdbID

            season =
                tmdbLink.season

            episode =
                tmdbLink.episode

            Log.d(
                TAG,
                "TMDB JSON -> id=$tmdbId season=$season episode=$episode"
            )
        }

        if (tmdbId == null) {

            val parsedUrl =
                parseTmdbUrl(data)

            if (parsedUrl != null) {

                tmdbId =
                    parsedUrl.first

                season =
                    parsedUrl.second

                episode =
                    parsedUrl.third

                Log.d(
                    TAG,
                    "TMDB URL -> id=$tmdbId season=$season episode=$episode"
                )
            }
        }

        val id =
            tmdbId
                ?: run {
                    Log.e(
                        TAG,
                        "TMDB ID could not be extracted from data"
                    )
                    return false
                }

        val playerUrl =
            buildVixsrcPlayerUrl(
                tmdbId = id,
                season = season,
                episode = episode
            )

        Log.d(
            TAG,
            "loading VixSrc $playerUrl"
        )

        val response =
            runCatching {

                app.get(
                    url = playerUrl,
                    headers = VIXSRC_HEADERS
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "VixSrc request failed: ${it.message}",
                    it
                )

            }.getOrNull()
                ?: return false

        Log.d(
            TAG,
            "VixSrc response code=${response.code}"
        )

        if (
            response.code !in 200..399
        ) {

            Log.e(
                TAG,
                "VixSrc returned HTTP ${response.code}"
            )

            return false
        }

        val html =
            response.text

        Log.d(
            TAG,
            "VixSrc HTML length=${html.length}"
        )

        if (html.isBlank()) {

            Log.e(
                TAG,
                "VixSrc HTML empty"
            )

            return false
        }

        val streamUrl =
            extractMasterPlaylist(
                html
            )
                ?: return false

        val finalUrl =
            cleanStreamUrl(
                streamUrl
            )
                ?: return false

        Log.d(
            TAG,
            "FINAL URL = $finalUrl"
        )

        if (
            !finalUrl.startsWith(
                "https://",
                true
            ) &&
            !finalUrl.startsWith(
                "http://",
                true
            )
        ) {

            Log.e(
                TAG,
                "Final URL is not HTTP/HTTPS"
            )

            return false
        }

        val looksPlayable =
            finalUrl.contains(
                ".m3u8",
                ignoreCase = true
            ) ||
                finalUrl.contains(
                    "/playlist/",
                    ignoreCase = true
                )

        if (!looksPlayable) {

            Log.e(
                TAG,
                "Final URL does not look playable: $finalUrl"
            )

            return false
        }

        if (
            finalUrl.contains(
                "themoviedb.org",
                ignoreCase = true
            )
        ) {

            Log.e(
                TAG,
                "Rejected TMDB URL as stream"
            )

            return false
        }

        newExtractorLink(
            source = name,
            name = "ClipBox • VixSrc",
            url = finalUrl,
            type = ExtractorLinkType.M3U8
        ) {

            referer =
                "$VIXSRC_URL/"

            headers =
                STREAM_HEADERS

            quality =
                Qualities.Unknown.value
        }.let(callback)

        Log.e(
            TAG,
            "VixSrc link successfully sent to CloudStream"
        )

        return true
    }
}
