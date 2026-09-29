package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ClipBox : TmdbProvider() {

    companion object {

        private const val TAG = "ClipBox"

        private const val TMDB_API_URL =
            "https://api.themoviedb.org/3"

        /*
         * CloudStream'in kendi TmdbProvider'ında kullanılan
         * genel TMDB anahtarı.
         */
        private const val TMDB_API_KEY =
            "e6333b32409e02a4a6eba6fb7ff866bb"

        private const val VIXSRC_URL =
            "https://vixsrc.to"

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

        /*
         * Sadece ihtiyacımız olan TMDB alanlarını parse ediyoruz.
         * Böylece gereksiz credits / recommendations /
         * content_ratings vb. istekleri yapılmıyor.
         */

        @Serializable
        private data class TmdbMovieResponse(
            @JsonProperty("id")
            @SerialName("id")
            val id: Int? = null,

            @JsonProperty("title")
            @SerialName("title")
            val title: String? = null,

            @JsonProperty("original_title")
            @SerialName("original_title")
            val originalTitle: String? = null,

            @JsonProperty("overview")
            @SerialName("overview")
            val overview: String? = null,

            @JsonProperty("poster_path")
            @SerialName("poster_path")
            val posterPath: String? = null,

            @JsonProperty("backdrop_path")
            @SerialName("backdrop_path")
            val backdropPath: String? = null,

            @JsonProperty("release_date")
            @SerialName("release_date")
            val releaseDate: String? = null,

            @JsonProperty("vote_average")
            @SerialName("vote_average")
            val voteAverage: Double? = null,

            @JsonProperty("runtime")
            @SerialName("runtime")
            val runtime: Int? = null,

            @JsonProperty("genres")
            @SerialName("genres")
            val genres: List<TmdbGenre>? = null
        )

        @Serializable
        private data class TmdbTvResponse(
            @JsonProperty("id")
            @SerialName("id")
            val id: Int? = null,

            @JsonProperty("name")
            @SerialName("name")
            val name: String? = null,

            @JsonProperty("original_name")
            @SerialName("original_name")
            val originalName: String? = null,

            @JsonProperty("overview")
            @SerialName("overview")
            val overview: String? = null,

            @JsonProperty("poster_path")
            @SerialName("poster_path")
            val posterPath: String? = null,

            @JsonProperty("backdrop_path")
            @SerialName("backdrop_path")
            val backdropPath: String? = null,

            @JsonProperty("first_air_date")
            @SerialName("first_air_date")
            val firstAirDate: String? = null,

            @JsonProperty("vote_average")
            @SerialName("vote_average")
            val voteAverage: Double? = null,

            @JsonProperty("episode_run_time")
            @SerialName("episode_run_time")
            val episodeRunTime: List<Int>? = null,

            @JsonProperty("genres")
            @SerialName("genres")
            val genres: List<TmdbGenre>? = null,

            @JsonProperty("seasons")
            @SerialName("seasons")
            val seasons: List<TmdbSeason>? = null
        )

        @Serializable
        private data class TmdbGenre(
            @JsonProperty("id")
            @SerialName("id")
            val id: Int? = null,

            @JsonProperty("name")
            @SerialName("name")
            val name: String? = null
        )

        @Serializable
        private data class TmdbSeason(
            @JsonProperty("id")
            @SerialName("id")
            val id: Int? = null,

            @JsonProperty("season_number")
            @SerialName("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("episode_count")
            @SerialName("episode_count")
            val episodeCount: Int? = null
        )

        @Serializable
        private data class TmdbSeasonResponse(
            @JsonProperty("season_number")
            @SerialName("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("episodes")
            @SerialName("episodes")
            val episodes: List<TmdbEpisode>? = null
        )

        @Serializable
        private data class TmdbEpisode(
            @JsonProperty("id")
            @SerialName("id")
            val id: Int? = null,

            @JsonProperty("name")
            @SerialName("name")
            val name: String? = null,

            @JsonProperty("overview")
            @SerialName("overview")
            val overview: String? = null,

            @JsonProperty("episode_number")
            @SerialName("episode_number")
            val episodeNumber: Int? = null,

            @JsonProperty("season_number")
            @SerialName("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("still_path")
            @SerialName("still_path")
            val stillPath: String? = null,

            @JsonProperty("air_date")
            @SerialName("air_date")
            val airDate: String? = null,

            @JsonProperty("vote_average")
            @SerialName("vote_average")
            val voteAverage: Double? = null
        )
    }

    init {
        Log.e(
            TAG,
            "========== CLIPBOX CLASS CREATED =========="
        )
    }

    override var name: String = "ClipBox"

    override var lang: String = "tr"

    /*
     * TMDB URL'lerini kendimiz işlediğimiz için bunu gerçek
     * metadata adresi olarak tanımlıyoruz.
     */
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
     * ---------------------------------------------------------
     * TMDB
     * ---------------------------------------------------------
     */

    private fun posterUrl(
        path: String?
    ): String? {

        if (path.isNullOrBlank()) {
            return null
        }

        return if (
            path.startsWith(
                "http://",
                true
            ) ||
            path.startsWith(
                "https://",
                true
            )
        ) {
            path
        } else {
            "https://image.tmdb.org/t/p/w500$path"
        }
    }

    private fun backdropUrl(
        path: String?
    ): String? {

        if (path.isNullOrBlank()) {
            return null
        }

        return if (
            path.startsWith(
                "http://",
                true
            ) ||
            path.startsWith(
                "https://",
                true
            )
        ) {
            path
        } else {
            "https://image.tmdb.org/t/p/w1280$path"
        }
    }

    private suspend fun tmdbGet(
        path: String,
        extraParams: Map<String, String> = emptyMap()
    ): String {

        val params = buildMap {

            put(
                "api_key",
                TMDB_API_KEY
            )

            putAll(
                extraParams
            )
        }

        Log.d(
            TAG,
            "TMDB GET $path"
        )

        return app.get(
            url = "$TMDB_API_URL$path",
            params = params
        ).text
    }

    private fun parseTmdbId(
        url: String
    ): Pair<Int, Boolean>? {

        val match = Regex(
            """themoviedb\.org/(movie|tv)/(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(url)
            ?: return null

        val type =
            match.groupValues
                .getOrNull(1)
                .orEmpty()

        val id =
            match.groupValues
                .getOrNull(2)
                ?.toIntOrNull()
                ?: return null

        return Pair(
            id,
            type.equals(
                "tv",
                true
            )
        )
    }

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
                "TMDB data parse failed: ${it.message}"
            )

        }.getOrNull()
    }

    /*
     * ---------------------------------------------------------
     * KENDİ LOAD METODUMUZ
     * ---------------------------------------------------------
     *
     * TmdbProvider.load() kullanılmıyor.
     *
     * Böylece:
     *
     * APIRepository
     *      ↓
     * ClipBox.load()
     *      ↓
     * TMDB
     *      ↓
     * LoadResponse
     *
     * doğrudan gerçekleşiyor.
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

        val parsed =
            parseTmdbId(url)

        if (parsed == null) {

            Log.e(
                TAG,
                "TMDB ID could not be parsed from URL"
            )

            return null
        }

        val tmdbId =
            parsed.first

        val isTv =
            parsed.second

        Log.d(
            TAG,
            "TMDB ID=$tmdbId isTv=$isTv"
        )

        return try {

            if (isTv) {

                loadTv(
                    tmdbId
                )

            } else {

                loadMovie(
                    tmdbId
                )
            }

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "CLIPBOX LOAD ERROR: ${throwable.message}",
                throwable
            )

            null
        }
    }

    private suspend fun loadMovie(
        tmdbId: Int
    ): LoadResponse? {

        Log.d(
            TAG,
            "Loading TMDB movie $tmdbId"
        )

        val json =
            tmdbGet(
                "/movie/$tmdbId",
                mapOf(
                    "language" to "en-US"
                )
            )

        val movie =
            parseJson<TmdbMovieResponse>(
                json
            )

        val title =
            movie.title
                ?: movie.originalTitle
                ?: return null

        val year =
            movie.releaseDate
                ?.take(4)
                ?.toIntOrNull()

        val genres =
            movie.genres
                ?.mapNotNull {
                    it.name
                }

        val response =
            newMovieLoadResponse(
                name = title,
                url = "https://www.themoviedb.org/movie/$tmdbId",
                type = TvType.Movie,
                data = TmdbLink(
                    imdbID = null,
                    tmdbID = tmdbId,
                    episode = null,
                    season = null,
                    movieName = title
                )
            ) {

                posterUrl =
                    posterUrl(
                        movie.posterPath
                    )

                backgroundPosterUrl =
                    backdropUrl(
                        movie.backdropPath
                    )

                this.year =
                    year

                plot =
                    movie.overview

                score =
                    Score.from10(
                        movie.voteAverage
                    )

                tags =
                    genres

                duration =
                    movie.runtime

                Log.d(
                    TAG,
                    "Movie LoadResponse created for $title"
                )
            }

        return response
    }

    private suspend fun loadTv(
        tmdbId: Int
    ): LoadResponse? {

        Log.d(
            TAG,
            "Loading TMDB TV $tmdbId"
        )

        val json =
            tmdbGet(
                "/tv/$tmdbId",
                mapOf(
                    "language" to "en-US"
                )
            )

        val tv =
            parseJson<TmdbTvResponse>(
                json
            )

        val title =
            tv.name
                ?: tv.originalName
                ?: return null

        val episodes =
            mutableListOf<Episode>()

        val seasons =
            tv.seasons
                ?.filter {
                    (it.seasonNumber ?: 0) > 0
                }
                .orEmpty()

        Log.d(
            TAG,
            "TV $title seasons=${seasons.size}"
        )

        for (season in seasons) {

            val seasonNumber =
                season.seasonNumber
                    ?: continue

            Log.d(
                TAG,
                "Loading season $seasonNumber"
            )

            try {

                val seasonJson =
                    tmdbGet(
                        "/tv/$tmdbId/season/$seasonNumber",
                        mapOf(
                            "language" to "en-US"
                        )
                    )

                val seasonData =
                    parseJson<TmdbSeasonResponse>(
                        seasonJson
                    )

                seasonData.episodes
                    .orEmpty()
                    .forEach { episodeData ->

                        val episodeNumber =
                            episodeData.episodeNumber
                                ?: return@forEach

                        val actualSeason =
                            episodeData.seasonNumber
                                ?: seasonNumber

                        val episodeName =
                            episodeData.name
                                ?: "Bölüm $episodeNumber"

                        val link =
                            TmdbLink(
                                imdbID = null,
                                tmdbID = tmdbId,
                                episode = episodeNumber,
                                season = actualSeason,
                                movieName = title
                            )

                        val episode =
                            newEpisode(
                                link
                            ) {

                                name =
                                    episodeName

                                season =
                                    actualSeason

                                episode =
                                    episodeNumber

                                posterUrl =
                                    posterUrl(
                                        episodeData.stillPath
                                    )

                                description =
                                    episodeData.overview

                                score =
                                    Score.from10(
                                        episodeData.voteAverage
                                    )

                                addDate(
                                    episodeData.airDate
                                )
                            }

                        episodes += episode
                    }

            } catch (throwable: Throwable) {

                Log.e(
                    TAG,
                    "Season $seasonNumber failed: ${throwable.message}",
                    throwable
                )
            }
        }

        val year =
            tv.firstAirDate
                ?.take(4)
                ?.toIntOrNull()

        val genres =
            tv.genres
                ?.mapNotNull {
                    it.name
                }

        val duration =
            tv.episodeRunTime
                ?.filter {
                    it > 0
                }
                ?.average()
                ?.toInt()

        val response =
            newTvSeriesLoadResponse(
                name = title,
                url = "https://www.themoviedb.org/tv/$tmdbId",
                type = TvType.TvSeries,
                episodes = episodes
            ) {

                posterUrl =
                    posterUrl(
                        tv.posterPath
                    )

                backgroundPosterUrl =
                    backdropUrl(
                        tv.backdropPath
                    )

                this.year =
                    year

                plot =
                    tv.overview

                score =
                    Score.from10(
                        tv.voteAverage
                    )

                tags =
                    genres

                this.duration =
                    duration

                Log.e(
                    TAG,
                    "TV LoadResponse created: $title episodes=${episodes.size}"
                )
            }

        return response
    }

    /*
     * ---------------------------------------------------------
     * VIXSRC
     * ---------------------------------------------------------
     */

    private fun cleanEmbeddedValue(
        value: String
    ): String {

        return value
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
                "\\u0026",
                "&"
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
                "&amp;",
                "&"
            )
            .replace(
                "&#x26;",
                "&"
            )
            .replace(
                "&#38;",
                "&"
            )
            .replace(
                "&quot;",
                "\""
            )
            .replace(
                "&#39;",
                "'"
            )
            .replace(
                "&apos;",
                "'"
            )
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
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

            val value =
                pattern
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()

            if (!value.isNullOrBlank()) {

                val cleaned =
                    cleanEmbeddedValue(
                        value
                    )

                if (
                    cleaned.isNotBlank()
                ) {
                    return cleaned
                }
            }
        }

        return null
    }

    private fun cleanStreamUrl(
        url: String
    ): String? {

        var result =
            cleanEmbeddedValue(
                url
            )

        result =
            result
                .trim()
                .trimEnd(
                    ')',
                    ']',
                    '}',
                    ';',
                    ','
                )

        if (
            result.startsWith(
                "//"
            )
        ) {
            result =
                "https:$result"
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

    private fun isPlayableStreamUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(
            ".m3u8"
        ) ||
            value.contains(
                "/playlist/"
            ) ||
            (
                value.contains(
                    "/hls/"
                ) &&
                    (
                        value.contains(
                            "master"
                        ) ||
                            value.contains(
                                "playlist"
                            ) ||
                            value.contains(
                                ".m3u"
                            )
                    )
                )
    }

    private fun addCandidate(
        candidates: MutableList<String>,
        value: String?
    ) {

        if (
            value.isNullOrBlank()
        ) {
            return
        }

        val cleaned =
            cleanStreamUrl(
                value
            )
                ?: return

        if (
            !isPlayableStreamUrl(
                cleaned
            )
        ) {
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
            !candidates.contains(
                cleaned
            )
        ) {

            candidates += cleaned

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

            pattern
                .findAll(
                    html
                )
                .forEach { match ->

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

            pattern
                .findAll(
                    html
                )
                .forEach { match ->

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

        val fields = listOf(
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

        for (field in fields) {

            val value =
                extractField(
                    html,
                    field
                )

            if (
                !value.isNullOrBlank()
            ) {

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

        val scriptRegex =
            Regex(
                """<script[^>]*>(.*?)</script>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )

        scriptRegex
            .findAll(
                html
            )
            .forEachIndexed { index, match ->

                val script =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()

                if (
                    script.isBlank()
                ) {
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

        val regex =
            Regex(
                """["'`]([^"'`]+)["'`]""",
                RegexOption.IGNORE_CASE
            )

        regex
            .findAll(
                html
            )
            .forEach { match ->

                val value =
                    match
                        .groupValues
                        .getOrNull(1)
                        ?.trim()
                        ?: return@forEach

                val cleaned =
                    cleanEmbeddedValue(
                        value
                    )

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

        val lines =
            html
                .replace(
                    "><",
                    ">\n<"
                )
                .split(
                    '\n'
                )
                .filter { line ->

                    val lower =
                        line.lowercase()

                    lower.contains(
                        "m3u8"
                    ) ||
                        lower.contains(
                            "playlist"
                        ) ||
                        lower.contains(
                            "hls"
                        ) ||
                        lower.contains(
                            "source"
                        ) ||
                        lower.contains(
                            "stream"
                        ) ||
                        lower.contains(
                            "master"
                        ) ||
                        lower.contains(
                            "video"
                        )
                }

        lines
            .take(
                100
            )
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

        if (
            candidates.isEmpty()
        ) {

            Log.d(
                TAG,
                "No playable stream candidate found"
            )

            logInterestingHtml(
                html
            )

            return null
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

        if (
            selected != null
        ) {

            Log.e(
                TAG,
                "SELECTED STREAM = $selected"
            )

            return selected
        }

        return null
    }

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
     * ---------------------------------------------------------
     * LINKS
     * ---------------------------------------------------------
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
            "data=$data"
        )

        Log.e(
            TAG,
            "isCasting=$isCasting"
        )

        val tmdbLink =
            parseTmdbLink(
                data
            )

        if (
            tmdbLink == null
        ) {

            Log.e(
                TAG,
                "TMDB Link could not be parsed"
            )

            return false
        }

        val tmdbId =
            tmdbLink.tmdbID

        if (
            tmdbId == null
        ) {

            Log.e(
                TAG,
                "TMDB ID is null"
            )

            return false
        }

        val season =
            tmdbLink.season

        val episode =
            tmdbLink.episode

        Log.e(
            TAG,
            "TMDB id=$tmdbId season=$season episode=$episode"
        )

        val playerUrl =
            buildVixsrcUrl(
                tmdbId = tmdbId,
                season = season,
                episode = episode
            )

        Log.e(
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

        Log.e(
            TAG,
            "VixSrc response code=${response.code}"
        )

        if (
            response.code !in 200..399
        ) {

            Log.e(
                TAG,
                "VixSrc HTTP error=${response.code}"
            )

            return false
        }

        val html =
            response.text

        Log.e(
            TAG,
            "VixSrc HTML length=${html.length}"
        )

        if (
            html.isBlank()
        ) {

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

        Log.e(
            TAG,
            "FINAL URL = $finalUrl"
        )

        if (
            finalUrl.contains(
                "themoviedb.org",
                true
            )
        ) {

            Log.e(
                TAG,
                "Rejected TMDB URL"
            )

            return false
        }

        if (
            !isPlayableStreamUrl(
                finalUrl
            )
        ) {

            Log.e(
                TAG,
                "URL is not a playable HLS URL"
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
