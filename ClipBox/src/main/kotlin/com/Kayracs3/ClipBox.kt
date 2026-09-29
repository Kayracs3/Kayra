package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
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

class ClipBox : TmdbProvider() {

    companion object {

        private const val TAG = "ClipBox"

        private const val TMDB_API_URL =
            "https://api.themoviedb.org/3"

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
            "Accept" to "application/json,text/plain,text/html,*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val STREAM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$VIXSRC_URL/",
            "Origin" to VIXSRC_URL,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private data class TmdbGenre(
            @JsonProperty("id")
            val id: Int? = null,

            @JsonProperty("name")
            val name: String? = null
        )

        private data class TmdbMovieResponse(
            @JsonProperty("id")
            val id: Int? = null,

            @JsonProperty("title")
            val title: String? = null,

            @JsonProperty("original_title")
            val originalTitle: String? = null,

            @JsonProperty("overview")
            val overview: String? = null,

            @JsonProperty("poster_path")
            val posterPath: String? = null,

            @JsonProperty("backdrop_path")
            val backdropPath: String? = null,

            @JsonProperty("release_date")
            val releaseDate: String? = null,

            @JsonProperty("vote_average")
            val voteAverage: Double? = null,

            @JsonProperty("runtime")
            val runtime: Int? = null,

            @JsonProperty("genres")
            val genres: List<TmdbGenre>? = null
        )

        private data class TmdbTvResponse(
            @JsonProperty("id")
            val id: Int? = null,

            @JsonProperty("name")
            val name: String? = null,

            @JsonProperty("original_name")
            val originalName: String? = null,

            @JsonProperty("overview")
            val overview: String? = null,

            @JsonProperty("poster_path")
            val posterPath: String? = null,

            @JsonProperty("backdrop_path")
            val backdropPath: String? = null,

            @JsonProperty("first_air_date")
            val firstAirDate: String? = null,

            @JsonProperty("vote_average")
            val voteAverage: Double? = null,

            @JsonProperty("episode_run_time")
            val episodeRunTime: List<Int>? = null,

            @JsonProperty("genres")
            val genres: List<TmdbGenre>? = null,

            @JsonProperty("seasons")
            val seasons: List<TmdbSeason>? = null
        )

        private data class TmdbSeason(
            @JsonProperty("id")
            val id: Int? = null,

            @JsonProperty("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("episode_count")
            val episodeCount: Int? = null
        )

        private data class TmdbSeasonResponse(
            @JsonProperty("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("episodes")
            val episodes: List<TmdbEpisode>? = null
        )

        private data class TmdbEpisode(
            @JsonProperty("id")
            val id: Int? = null,

            @JsonProperty("name")
            val name: String? = null,

            @JsonProperty("overview")
            val overview: String? = null,

            @JsonProperty("episode_number")
            val episodeNumber: Int? = null,

            @JsonProperty("season_number")
            val seasonNumber: Int? = null,

            @JsonProperty("still_path")
            val stillPath: String? = null,

            @JsonProperty("air_date")
            val airDate: String? = null,

            @JsonProperty("vote_average")
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
     * =========================================================
     * TMDB
     * =========================================================
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

            Log.e(
                TAG,
                "TMDB data parse failed: ${it.message}"
            )

        }.getOrNull()
    }

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
                "TMDB ID could not be parsed"
            )

            return null
        }

        val tmdbId =
            parsed.first

        val isTv =
            parsed.second

        Log.e(
            TAG,
            "TMDB ID=$tmdbId isTv=$isTv"
        )

        return try {

            if (isTv) {
                loadTv(tmdbId)
            } else {
                loadMovie(tmdbId)
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

        Log.e(
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

        return newMovieLoadResponse(
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
        }
    }

    private suspend fun loadTv(
        tmdbId: Int
    ): LoadResponse? {

        Log.e(
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

        Log.e(
            TAG,
            "TV $title seasons=${seasons.size}"
        )

        for (seasonInfo in seasons) {

            val seasonNumber =
                seasonInfo.seasonNumber
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

                        val epNo =
                            episodeData.episodeNumber
                                ?: return@forEach

                        val actualSeason =
                            episodeData.seasonNumber
                                ?: seasonNumber

                        val episodeName =
                            episodeData.name
                                ?: "Bölüm $epNo"

                        val link =
                            TmdbLink(
                                imdbID = null,
                                tmdbID = tmdbId,
                                episode = epNo,
                                season = actualSeason,
                                movieName = title
                            )

                        val episodeItem =
                            newEpisode(
                                link.toJson()
                            ) {

                                this.name =
                                    episodeName

                                this.season =
                                    actualSeason

                                this.episode =
                                    epNo

                                this.posterUrl =
                                    posterUrl(
                                        episodeData.stillPath
                                    )

                                this.description =
                                    episodeData.overview

                                this.score =
                                    Score.from10(
                                        episodeData.voteAverage
                                    )

                                addDate(
                                    episodeData.airDate
                                )
                            }

                        episodes +=
                            episodeItem
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

        return newTvSeriesLoadResponse(
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
    }

    /*
     * =========================================================
     * JSON YARDIMCILARI
     * =========================================================
     */

    private fun decodeJson(
        text: String
    ): JsonNode? {

        return runCatching {

            mapper.readTree(
                text
            )

        }.onFailure {

            Log.e(
                TAG,
                "JSON parse failed: ${it.message}"
            )

        }.getOrNull()
    }

    private fun findStringByKeys(
        node: JsonNode?,
        keys: Set<String>
    ): String? {

        if (node == null) {
            return null
        }

        if (node.isObject) {

            val fields =
                node.fields()

            while (
                fields.hasNext()
            ) {

                val entry =
                    fields.next()

                if (
                    keys.any {
                        it.equals(
                            entry.key,
                            true
                        )
                    }
                ) {

                    val value =
                        entry.value

                    if (
                        value.isValueNode &&
                        !value.isNull
                    ) {

                        val text =
                            value.asText()

                        if (
                            text.isNotBlank()
                        ) {
                            return text
                        }
                    }
                }
            }

            val secondPass =
                node.fields()

            while (
                secondPass.hasNext()
            ) {

                val result =
                    findStringByKeys(
                        secondPass.next().value,
                        keys
                    )

                if (
                    !result.isNullOrBlank()
                ) {
                    return result
                }
            }
        }

        if (node.isArray) {

            for (
                child in node
            ) {

                val result =
                    findStringByKeys(
                        child,
                        keys
                    )

                if (
                    !result.isNullOrBlank()
                ) {
                    return result
                }
            }
        }

        return null
    }

    private fun findPlaylistUrl(
        node: JsonNode?
    ): String? {

        if (node == null) {
            return null
        }

        if (node.isValueNode) {

            val value =
                node.asText()

            if (
                value.contains(
                    "/playlist/",
                    true
                ) ||
                value.contains(
                    ".m3u8",
                    true
                )
            ) {

                return value
            }
        }

        if (node.isObject) {

            val fields =
                node.fields()

            while (
                fields.hasNext()
            ) {

                val result =
                    findPlaylistUrl(
                        fields.next().value
                    )

                if (
                    !result.isNullOrBlank()
                ) {
                    return result
                }
            }
        }

        if (node.isArray) {

            for (
                child in node
            ) {

                val result =
                    findPlaylistUrl(
                        child
                    )

                if (
                    !result.isNullOrBlank()
                ) {
                    return result
                }
            }
        }

        return null
    }

    private fun normalizeUrl(
        value: String?
    ): String? {

        if (
            value.isNullOrBlank()
        ) {
            return null
        }

        var url =
            value
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\u002F",
                    "/"
                )
                .replace(
                    "\\u003A",
                    ":"
                )
                .replace(
                    "\\u0026",
                    "&"
                )
                .replace(
                    "&amp;",
                    "&"
                )
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()

        if (
            url.startsWith("//")
        ) {
            url =
                "https:$url"
        }

        if (
            url.startsWith("/")
        ) {
            url =
                "$VIXSRC_URL$url"
        }

        return url
    }

    private fun appendVixsrcParams(
        baseUrl: String,
        token: String?,
        expires: String?
    ): String {

        val separator =
            if (
                baseUrl.contains("?")
            ) {
                "&"
            } else {
                "?"
            }

        val result =
            StringBuilder(
                baseUrl
            )

        if (
            !token.isNullOrBlank() &&
            !baseUrl.contains(
                "token=",
                true
            )
        ) {

            result.append(
                "${separator}token=${token}"
            )

            if (
                !baseUrl.contains(
                    "?",
                    true
                ) ||
                baseUrl.contains(
                    "&"
                )
            ) {
                // intentionally empty
            }
        }

        val current =
            result.toString()

        val secondSeparator =
            if (
                current.contains("?")
            ) {
                "&"
            } else {
                "?"
            }

        if (
            !expires.isNullOrBlank() &&
            !current.contains(
                "expires=",
                true
            )
        ) {

            result.append(
                "${secondSeparator}expires=${expires}"
            )
        }

        val afterExpires =
            result.toString()

        val thirdSeparator =
            if (
                afterExpires.contains("?")
            ) {
                "&"
            } else {
                "?"
            }

        if (
            !afterExpires.contains(
                "h=",
                true
            )
        ) {

            result.append(
                "${thirdSeparator}h=1"
            )
        }

        val afterH =
            result.toString()

        val fourthSeparator =
            if (
                afterH.contains("?")
            ) {
                "&"
            } else {
                "?"
            }

        if (
            !afterH.contains(
                "lang=",
                true
            )
        ) {

            result.append(
                "${fourthSeparator}lang=en"
            )
        }

        return result.toString()
    }

    private fun createPlaylistFromApiJson(
        jsonText: String
    ): String? {

        val root =
            decodeJson(
                jsonText
            )
                ?: return null

        /*
         * Önce doğrudan playlist / m3u8 ara.
         */
        val direct =
            normalizeUrl(
                findPlaylistUrl(
                    root
                )
            )

        if (
            !direct.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "API DIRECT PLAYLIST = $direct"
            )

            return appendVixsrcParams(
                direct,
                token = null,
                expires = null
            )
        }

        /*
         * Sonra bilinen alanları ara.
         */
        val urlValue =
            normalizeUrl(
                findStringByKeys(
                    root,
                    setOf(
                        "playlist",
                        "playlistUrl",
                        "playlistURL",
                        "m3u8",
                        "m3u8Url",
                        "stream",
                        "streamUrl",
                        "source",
                        "sourceUrl",
                        "file",
                        "fileUrl",
                        "url"
                    )
                )
            )

        val token =
            findStringByKeys(
                root,
                setOf(
                    "token",
                    "Token"
                )
            )

        val expires =
            findStringByKeys(
                root,
                setOf(
                    "expires",
                    "expire",
                    "expiration"
                )
            )

        val videoId =
            findStringByKeys(
                root,
                setOf(
                    "video_id",
                    "videoId",
                    "videoID",
                    "stream_id",
                    "streamId",
                    "playlist_id",
                    "playlistId"
                )
            )

        if (
            !urlValue.isNullOrBlank()
        ) {

            Log.d(
                TAG,
                "API URL VALUE = $urlValue"
            )

            /*
             * URL zaten playlist ise doğrudan kullan.
             */
            if (
                urlValue.contains(
                    "/playlist/",
                    true
                ) ||
                urlValue.contains(
                    ".m3u8",
                    true
                )
            ) {

                return appendVixsrcParams(
                    urlValue,
                    token,
                    expires
                )
            }

            /*
             * API "url" olarak bir embed/page döndürüyorsa
             * loadLinks içerisinde ayrıca açacağız.
             */
            Log.d(
                TAG,
                "API returned non-playlist URL=$urlValue"
            )
        }

        /*
         * Eğer API video_id + token + expires döndürürse
         * playlist URL'sini doğrudan oluştur.
         */
        if (
            !videoId.isNullOrBlank()
        ) {

            val playlist =
                "$VIXSRC_URL/playlist/$videoId"

            Log.e(
                TAG,
                "API VIDEO ID = $videoId"
            )

            return appendVixsrcParams(
                playlist,
                token,
                expires
            )
        }

        /*
         * API cevabında embed URL ara.
         */
        val embed =
            normalizeUrl(
                findStringByKeys(
                    root,
                    setOf(
                        "embed",
                        "embedUrl",
                        "embedURL",
                        "player",
                        "playerUrl",
                        "playerURL"
                    )
                )
            )

        if (
            !embed.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "API EMBED = $embed"
            )

            /*
             * Embed URL'si VixSrc playlist ise doğrudan kullan.
             */
            if (
                embed.contains(
                    "/playlist/",
                    true
                ) ||
                    embed.contains(
                        ".m3u8",
                        true
                    )
            ) {

                return appendVixsrcParams(
                    embed,
                    token,
                    expires
                )
            }
        }

        Log.e(
            TAG,
            "API JSON did not contain an obvious stream"
        )

        Log.d(
            TAG,
            "API JSON = ${jsonText.take(6000)}"
        )

        return embed
    }

    /*
     * =========================================================
     * ESKİ HTML / EMBED FALLBACK
     * =========================================================
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
                "&quot;",
                "\""
            )
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
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
            result.startsWith("//")
        ) {
            result =
                "https:$result"
        }

        if (
            result.startsWith("/")
        ) {
            result =
                "$VIXSRC_URL$result"
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

        val lower =
            url.lowercase()

        return lower.contains(
            ".m3u8"
        ) ||
            lower.contains(
                "/playlist/"
            )
    }

    private fun extractOldHtmlStream(
        html: String
    ): String? {

        /*
         * 1. masterPlaylist / url alanları
         */
        val urlMatch =
            Regex(
                """(?:url|file|src|m3u8|playlist)\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(
                html
            )

        val token =
            Regex(
                """["']?token["']?\s*[:=]\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        val expires =
            Regex(
                """["']?(?:expires|expire)["']?\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        val url =
            cleanStreamUrl(
                urlMatch
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: ""
            )

        if (
            !url.isNullOrBlank() &&
            isPlayableStreamUrl(url)
        ) {

            return appendVixsrcParams(
                url,
                token,
                expires
            )
        }

        /*
         * 2. Doğrudan playlist URL'si
         */
        val direct =
            Regex(
                """https?://[^"'<>\s]+/playlist/[^"'<>\s]+""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.value

        if (
            !direct.isNullOrBlank()
        ) {

            return appendVixsrcParams(
                direct,
                token = null,
                expires = null
            )
        }

        /*
         * 3. Doğrudan .m3u8
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
            return m3u8
        }

        return null
    }

    /*
     * =========================================================
     * VIXSRC API
     * =========================================================
     */

    private fun buildVixsrcApiUrl(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String {

        return if (
            season != null &&
            episode != null
        ) {

            "$VIXSRC_URL/api/tv?id=$tmdbId&season=$season&episode=$episode"

        } else {

            "$VIXSRC_URL/api/movie?id=$tmdbId"
        }
    }

    private suspend fun resolveVixsrcApi(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String? {

        val apiUrl =
            buildVixsrcApiUrl(
                tmdbId,
                season,
                episode
            )

        Log.e(
            TAG,
            "VIXSRC API = $apiUrl"
        )

        val response =
            runCatching {

                app.get(
                    url = apiUrl,
                    headers = VIXSRC_HEADERS
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "VixSrc API request failed: ${it.message}",
                    it
                )
            }.getOrNull()
                ?: return null

        Log.e(
            TAG,
            "VixSrc API response code=${response.code}"
        )

        val body =
            response.text

        Log.d(
            TAG,
            "VixSrc API body length=${body.length}"
        )

        if (
            response.code !in 200..399 ||
            body.isBlank()
        ) {
            return null
        }

        val possible =
            createPlaylistFromApiJson(
                body
            )

        if (
            possible.isNullOrBlank()
        ) {
            return null
        }

        /*
         * Eğer API doğrudan playlist vermişse bitti.
         */
        if (
            isPlayableStreamUrl(
                possible
            )
        ) {

            Log.e(
                TAG,
                "API RESOLVED STREAM = $possible"
            )

            return possible
        }

        /*
         * API embed/page URL döndürdüyse aç.
         */
        val embedResponse =
            runCatching {

                app.get(
                    url = possible,
                    headers = VIXSRC_HEADERS
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "Embed request failed: ${it.message}",
                    it
                )
            }.getOrNull()
                ?: return null

        Log.e(
            TAG,
            "Embed response code=${embedResponse.code}"
        )

        val embedHtml =
            embedResponse.text

        Log.d(
            TAG,
            "Embed HTML length=${embedHtml.length}"
        )

        return extractOldHtmlStream(
            embedHtml
        )
    }

    private suspend fun resolveVixsrcPageFallback(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String? {

        val pageUrl =
            if (
                season != null &&
                episode != null
            ) {

                "$VIXSRC_URL/tv/$tmdbId/$season/$episode"

            } else {

                "$VIXSRC_URL/movie/$tmdbId"
            }

        Log.e(
            TAG,
            "VIXSRC PAGE FALLBACK = $pageUrl"
        )

        val response =
            runCatching {

                app.get(
                    url = pageUrl,
                    headers = VIXSRC_HEADERS
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "VixSrc page request failed: ${it.message}",
                    it
                )
            }.getOrNull()
                ?: return null

        Log.e(
            TAG,
            "VixSrc page response code=${response.code}"
        )

        val html =
            response.text

        Log.e(
            TAG,
            "VixSrc page HTML length=${html.length}"
        )

        return extractOldHtmlStream(
            html
        )
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

        /*
         * Önce yeni API.
         */
        var finalUrl =
            resolveVixsrcApi(
                tmdbId = tmdbId,
                season = season,
                episode = episode
            )

        /*
         * API başarısızsa eski page fallback.
         */
        if (
            finalUrl.isNullOrBlank()
        ) {

            Log.d(
                TAG,
                "VixSrc API did not resolve a stream, trying page fallback"
            )

            finalUrl =
                resolveVixsrcPageFallback(
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
        }

        if (
            finalUrl.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "========== VIXSRC STREAM NOT FOUND =========="
            )

            return false
        }

        finalUrl =
            cleanStreamUrl(
                finalUrl
            )
                ?: return false

        Log.e(
            TAG,
            "FINAL URL = $finalUrl"
        )

        if (
            !isPlayableStreamUrl(
                finalUrl
            )
        ) {

            Log.e(
                TAG,
                "FINAL URL NOT PLAYABLE = $finalUrl"
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
