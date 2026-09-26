package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RecTV : MainAPI() {

    override var mainUrl = "https://a.prectv71.lol"
    override var name = "RecTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Live,
        TvType.TvSeries
    )

    companion object {
        private const val USER_AGENT = "googleusercontent"
        private const val REFERER = "https://twitter.com/"
        private const val APP_VERSION = "157"
        private const val CLIENT_ID = "rectv-android"

        private const val SW_KEY =
            "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"

        private const val HMAC_KEY =
            "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"

        private const val AES_KEY_HEX =
            "666482389dc76bfa57068407418f7dac9f6c14b6868856b169165b9fac7d812e"
    }

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var jwtExpirationTimestamp: Long = 0L

    // =========================================================
    // CRYPTO / SIGNATURE
    // =========================================================

    private fun sha256Hex(
        data: String
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    data.toByteArray(Charsets.UTF_8)
                )

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun hmacSha256Hex(
        key: String,
        message: String
    ): String {
        val mac =
            Mac.getInstance("HmacSHA256")

        mac.init(
            SecretKeySpec(
                key.toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            )
        )

        return mac
            .doFinal(
                message.toByteArray(Charsets.UTF_8)
            )
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private fun hexToBytes(
        hex: String
    ): ByteArray {
        val output =
            ByteArray(hex.length / 2)

        for (index in output.indices) {
            val offset = index * 2
            output[index] =
                hex
                    .substring(offset, offset + 2)
                    .toInt(16)
                    .toByte()
        }

        return output
    }

    // =========================================================
    // JWT
    // =========================================================

    private suspend fun getJwt(): String? {

        val now =
            System.currentTimeMillis() / 1000L

        cachedJwt?.let { token ->
            if (now < jwtExpirationTimestamp - 300) {
                return token
            }
        }

        return try {

            val currentNow =
                System.currentTimeMillis() / 1000L

            val recheckJwt = cachedJwt
            if (
                recheckJwt != null &&
                currentNow < jwtExpirationTimestamp - 300
            ) {
                return recheckJwt
            }

            val path =
                "/api/attest/verify"

            val body =
                "{}"

            val headers =
                getSignedHeaders(
                    method = "POST",
                    path = path,
                    body = body,
                    includeAuth = false
                ).toMutableMap()

            headers["Content-Type"] =
                "application/json"

            val requestBody =
                body.toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val response =
                app.post(
                    "$mainUrl$path",
                    headers = headers,
                    requestBody = requestBody
                )

            val json =
                try {
                    JSONObject(response.text)
                } catch (_: Exception) {
                    null
                }

            val token =
                json
                    ?.optString("jwt")
                    ?.takeIf { it.isNotBlank() }

            if (token != null) {

                cachedJwt =
                    token

                var expiration =
                    json.optLong(
                        "exp",
                        currentNow + 7000L
                    )

                try {

                    val parts =
                        token.split(".")

                    if (parts.size >= 2) {

                        val payloadBytes =
                            Base64.decode(
                                parts[1],
                                Base64.URL_SAFE or
                                    Base64.NO_PADDING or
                                    Base64.NO_WRAP
                            )

                        val payloadJson =
                            String(
                                payloadBytes,
                                Charsets.UTF_8
                            )

                        Regex(
                            "\"exp\"\\s*:\\s*(\\d+)"
                        )
                            .find(payloadJson)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                            ?.let { value ->
                                expiration = value
                            }
                    }

                } catch (_: Exception) {
                }

                jwtExpirationTimestamp =
                    expiration

                token

            } else {
                cachedJwt
            }

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to fetch JWT: ${e.message}"
            )

            cachedJwt
        }
    }

    private suspend fun getSignedHeaders(
        method: String,
        path: String,
        body: String = "",
        includeAuth: Boolean = true
    ): Map<String, String> {

        val timestamp =
            (System.currentTimeMillis() / 1000L).toString()

        val nonce =
            UUID.randomUUID().toString()

        val bodyHash =
            sha256Hex(body)

        val message =
            "$method\n$path\n$timestamp\n$nonce\n$bodyHash"

        val signature =
            hmacSha256Hex(
                HMAC_KEY,
                message
            )

        val headers =
            mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "X-Timestamp" to timestamp,
                "X-Nonce" to nonce,
                "X-Signature" to signature,
                "X-App-Version" to APP_VERSION,
                "X-Client-Id" to CLIENT_ID
            )

        if (includeAuth) {

            val token =
                getJwt()

            if (!token.isNullOrBlank()) {
                headers["Authorization"] =
                    "Bearer $token"
            }
        }

        return headers
    }

    // =========================================================
    // STREAM URL DECRYPTION
    // =========================================================

    private fun decryptEncUrl(
        encUrl: String
    ): String? {

        return try {

            val raw =
                Base64.decode(
                    encUrl,
                    Base64.DEFAULT
                )

            if (raw.size < 28) {
                return null
            }

            val iv =
                raw.copyOfRange(0, 12)

            val cipherTextAndTag =
                raw.copyOfRange(12, raw.size)

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            val keySpec =
                SecretKeySpec(
                    hexToBytes(AES_KEY_HEX),
                    "AES"
                )

            val gcmSpec =
                GCMParameterSpec(
                    128,
                    iv
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                gcmSpec
            )

            val decrypted =
                cipher.doFinal(
                    cipherTextAndTag
                )

            String(
                decrypted,
                Charsets.UTF_8
            )

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to decrypt URL: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // SOURCE UNLOCK
    // =========================================================

    private suspend fun unlockSource(
        sourceId: Int
    ): String? {

        return try {

            val path =
                "/api/source/unlock-ad/$sourceId/$SW_KEY/"

            val body = "{}"

            val headers =
                getSignedHeaders(
                    method = "POST",
                    path = path,
                    body = body
                ).toMutableMap()

            headers["Content-Type"] =
                "application/json"

            val requestBody =
                body.toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val response =
                app.post(
                    "$mainUrl$path",
                    headers = headers,
                    requestBody = requestBody
                )

            JSONObject(response.text)
                .optString("enc_url")
                .takeIf { it.isNotBlank() }

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to unlock source $sourceId: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // JSON HELPERS
    // =========================================================

    private fun parseObject(
        text: String
    ): JSONObject? {

        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArray(
        text: String
    ): JSONArray? {

        return try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun optStringOrNull(
        obj: JSONObject,
        vararg keys: String
    ): String? {

        for (key in keys) {

            if (!obj.has(key)) {
                continue
            }

            val value =
                obj.opt(key)

            if (
                value == null ||
                value == JSONObject.NULL
            ) {
                continue
            }

            val text =
                value.toString().trim()

            if (text.isNotBlank()) {
                return text
            }
        }

        return null
    }

    private fun optIntOrNull(
        obj: JSONObject,
        vararg keys: String
    ): Int? {

        for (key in keys) {

            if (!obj.has(key)) {
                continue
            }

            val value =
                obj.opt(key)

            when (value) {

                is Number ->
                    return value.toInt()

                is String ->
                    value
                        .trim()
                        .toIntOrNull()
                        ?.let {
                            return it
                        }
            }
        }

        return null
    }

    private fun optBooleanOrNull(
        obj: JSONObject,
        vararg keys: String
    ): Boolean? {

        for (key in keys) {

            if (!obj.has(key)) {
                continue
            }

            val value =
                obj.opt(key)

            when (value) {

                is Boolean ->
                    return value

                is String ->
                    when (value.trim().lowercase()) {
                        "true", "1", "yes" -> return true
                        "false", "0", "no" -> return false
                    }
            }
        }

        return null
    }

    private fun optArray(
        obj: JSONObject,
        vararg keys: String
    ): JSONArray? {

        for (key in keys) {

            val array =
                obj.optJSONArray(key)

            if (array != null) {
                return array
            }
        }

        return null
    }

    private fun hasArrayItems(
        obj: JSONObject,
        vararg keys: String
    ): Boolean {
        return optArray(obj, *keys)
            ?.length()
            ?.let { it > 0 }
            ?: false
    }

    private fun objectToString(
        obj: JSONObject
    ): String = obj.toString()

    // =========================================================
    // HOME PAGE
    // =========================================================

    override val mainPage =
        mainPageOf(
            "${mainUrl}/api/channel/by/filtres/1/0/SAYFA/${SW_KEY}/" to "Spor",
            "${mainUrl}/api/channel/by/filtres/0/0/SAYFA/${SW_KEY}/" to "Canlı TV",
            "${mainUrl}/api/movie/by/filtres/0/created/SAYFA/${SW_KEY}/" to "Son Filmler",
            "${mainUrl}/api/serie/by/filtres/0/created/SAYFA/${SW_KEY}/" to "Son Diziler",
            "${mainUrl}/api/movie/by/filtres/14/created/SAYFA/${SW_KEY}/" to "Aile",
            "${mainUrl}/api/movie/by/filtres/1/created/SAYFA/${SW_KEY}/" to "Aksiyon",
            "${mainUrl}/api/movie/by/filtres/13/created/SAYFA/${SW_KEY}/" to "Animasyon",
            "${mainUrl}/api/movie/by/filtres/19/created/SAYFA/${SW_KEY}/" to "Belgesel",
            "${mainUrl}/api/movie/by/filtres/4/created/SAYFA/${SW_KEY}/" to "Bilim Kurgu",
            "${mainUrl}/api/movie/by/filtres/2/created/SAYFA/${SW_KEY}/" to "Dram",
            "${mainUrl}/api/movie/by/filtres/10/created/SAYFA/${SW_KEY}/" to "Fantastik",
            "${mainUrl}/api/movie/by/filtres/3/created/SAYFA/${SW_KEY}/" to "Komedi",
            "${mainUrl}/api/movie/by/filtres/8/created/SAYFA/${SW_KEY}/" to "Korku",
            "${mainUrl}/api/movie/by/filtres/17/created/SAYFA/${SW_KEY}/" to "Macera",
            "${mainUrl}/api/movie/by/filtres/5/created/SAYFA/${SW_KEY}/" to "Romantik"
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageIndex =
            page - 1

        val url =
            request.data.replace(
                "SAYFA",
                pageIndex.toString()
            )

        val path =
            try {
                java.net.URI(url).rawPath
            } catch (_: Exception) {
                url.substringBefore("?")
            }

        val response =
            try {
                app.get(
                    url,
                    headers = getSignedHeaders(
                        "GET",
                        path
                    )
                )
            } catch (e: Exception) {
                Log.e(
                    "RecTV",
                    "Main page failed: ${e.message}"
                )

                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }

        val array =
            parseArray(response.text)
                ?: return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )

        val results =
            mutableListOf<SearchResponse>()

        for (index in 0 until array.length()) {

            val item =
                array.optJSONObject(index)
                    ?: continue

            val id =
                optIntOrNull(
                    item,
                    "id"
                )
                    ?: 0

            val title =
                optStringOrNull(
                    item,
                    "title",
                    "name"
                )
                    ?: continue

            val image =
                optStringOrNull(
                    item,
                    "image",
                    "poster",
                    "poster_url"
                )

            val type =
                optStringOrNull(
                    item,
                    "type"
                )
                    ?.lowercase()

            val label =
                optStringOrNull(
                    item,
                    "label"
                )
                    .orEmpty()

            val isLive =
                label.equals("CANLI", ignoreCase = true) ||
                    request.name == "Spor" ||
                    request.name == "Canlı TV"

            val data =
                objectToString(item)

            when {

                isLive -> {
                    results +=
                        newLiveSearchResponse(
                            title,
                            data,
                            TvType.Live
                        ) {
                            this.posterUrl = image
                        }
                }

                type == "serie" -> {
                    results +=
                        newTvSeriesSearchResponse(
                            title,
                            data,
                            TvType.TvSeries
                        ) {
                            this.posterUrl = image
                        }
                }

                else -> {
                    results +=
                        newMovieSearchResponse(
                            title,
                            data,
                            TvType.Movie
                        ) {
                            this.posterUrl = image
                        }
                }
            }

            if (results.size >= 24) {
                break
            }
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = array.length() >= 24
        )
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded =
            URLEncoder
                .encode(query, "UTF-8")
                .replace("+", "%20")

        val path =
            "/api/search/$encoded/$SW_KEY/"

        val response =
            try {
                app.get(
                    "$mainUrl$path",
                    headers = getSignedHeaders(
                        "GET",
                        path
                    )
                )
            } catch (e: Exception) {
                Log.e(
                    "RecTV",
                    "Search failed: ${e.message}"
                )
                return emptyList()
            }

        val json =
            parseObject(response.text)
                ?: return emptyList()

        val results =
            mutableListOf<SearchResponse>()

        val channels =
            optArray(
                json,
                "channels"
            )

        if (channels != null) {

            for (index in 0 until channels.length()) {

                val item =
                    channels.optJSONObject(index)
                        ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    )
                        ?: continue

                val image =
                    optStringOrNull(
                        item,
                        "image",
                        "poster",
                        "poster_url"
                    )

                results +=
                    newLiveSearchResponse(
                        title,
                        objectToString(item),
                        TvType.Live
                    ) {
                        this.posterUrl = image
                    }
            }
        }

        val posters =
            optArray(
                json,
                "posters"
            )

        if (posters != null) {

            for (index in 0 until posters.length()) {

                val item =
                    posters.optJSONObject(index)
                        ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    )
                        ?: continue

                val image =
                    optStringOrNull(
                        item,
                        "image",
                        "poster",
                        "poster_url"
                    )

                val type =
                    optStringOrNull(
                        item,
                        "type"
                    )
                    ?.lowercase()

                if (type == "serie") {

                    results +=
                        newTvSeriesSearchResponse(
                            title,
                            objectToString(item),
                            TvType.TvSeries
                        ) {
                            this.posterUrl = image
                        }

                } else {

                    results +=
                        newMovieSearchResponse(
                            title,
                            objectToString(item),
                            TvType.Movie
                        ) {
                            this.posterUrl = image
                        }
                }
            }
        }

        return results
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> =
        search(query)

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val veri =
            parseObject(url)
                ?: return null

        val id =
            optIntOrNull(
                veri,
                "id"
            )
                ?: return null

        val title =
            optStringOrNull(
                veri,
                "title",
                "name"
            )
                ?: "RecTV"

        val image =
            optStringOrNull(
                veri,
                "image",
                "poster",
                "poster_url"
            )

        val description =
            optStringOrNull(
                veri,
                "description",
                "plot",
                "overview"
            )

        val year =
            optIntOrNull(
                veri,
                "year"
            )

        val type =
            optStringOrNull(
                veri,
                "type"
            )
                ?.lowercase()

        val genres =
            parseGenres(
                optArray(
                    veri,
                    "genres"
                )
            )

        // =====================================================
        // DIZI
        // =====================================================

        if (type == "serie") {

            val path =
                "/api/season/by/serie/$id/$SW_KEY/"

            val response =
                try {
                    app.get(
                        "$mainUrl$path",
                        headers = getSignedHeaders(
                            "GET",
                            path
                        )
                    )
                } catch (e: Exception) {
                    Log.e(
                        "RecTV",
                        "Series request failed: ${e.message}"
                    )
                    return null
                }

            val seasons =
                parseArray(response.text)
                    ?: return null

            val episodes =
                mutableMapOf<
                    DubStatus,
                    MutableList<Episode>
                >()

            val numberRegex =
                Regex("\\d+")

            for (seasonIndex in 0 until seasons.length()) {

                val season =
                    seasons.optJSONObject(seasonIndex)
                        ?: continue

                val seasonTitle =
                    optStringOrNull(
                        season,
                        "title",
                        "name"
                    )
                        ?: "Sezon ${seasonIndex + 1}"

                val dubStatus =
                    when {
                        seasonTitle.contains(
                            "altyazı",
                            ignoreCase = true
                        ) || seasonTitle.contains(
                            "altyazi",
                            ignoreCase = true
                        ) -> DubStatus.Subbed

                        seasonTitle.contains(
                            "dublaj",
                            ignoreCase = true
                        ) -> DubStatus.Dubbed

                        else -> DubStatus.None
                    }

                val seasonNumber =
                    numberRegex
                        .find(seasonTitle)
                        ?.value
                        ?.toIntOrNull()

                val seasonEpisodes =
                    optArray(
                        season,
                        "episodes"
                    )
                        ?: continue

                for (episodeIndex in 0 until seasonEpisodes.length()) {

                    val episodeJson =
                        seasonEpisodes.optJSONObject(episodeIndex)
                            ?: continue

                    val episodeTitle =
                        optStringOrNull(
                            episodeJson,
                            "title",
                            "name"
                        )
                            ?: "${episodeIndex + 1}. Bölüm"

                    val episodeNumber =
                        numberRegex
                            .find(episodeTitle)
                            ?.value
                            ?.toIntOrNull()

                    val episodeDescription =
                        if (
                            seasonTitle.contains(
                                ".S ",
                                ignoreCase = false
                            )
                        ) {
                            seasonTitle.substringAfter(
                                ".S "
                            )
                        } else {
                            seasonTitle
                        }

                    episodes
                        .getOrPut(dubStatus) {
                            mutableListOf()
                        }
                        .add(
                            newEpisode(
                                episodeJson.toString()
                            ) {
                                this.name = episodeTitle
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.description = episodeDescription
                                this.posterUrl = image
                            }
                        )
                }
            }

            if (episodes.isEmpty()) {
                return null
            }

            return newAnimeLoadResponse(
                title,
                url,
                TvType.TvSeries,
                comingSoonIfNone = false
            ) {
                this.episodes =
                    episodes
                        .mapValues { it.value.toList() }
                        .toMutableMap()

                this.posterUrl = image
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }

        // =====================================================
        // CANLI TV / SPOR
        // =====================================================

        val isLive =
            optStringOrNull(
                veri,
                "label"
            ).equals(
                "CANLI",
                ignoreCase = true
            ) ||
                hasArrayItems(
                    veri,
                    "categories"
                )

        if (isLive) {

            val fullChannel =
                if (
                    !hasArrayItems(
                        veri,
                        "sources"
                    )
                ) {

                    val path =
                        "/api/channel/by/$id/$SW_KEY/"

                    try {
                        val response =
                            app.get(
                                "$mainUrl$path",
                                headers = getSignedHeaders(
                                    "GET",
                                    path
                                )
                            )

                        parseObject(response.text)
                            ?: veri

                    } catch (e: Exception) {

                        Log.e(
                            "RecTV",
                            "Channel request failed: ${e.message}"
                        )

                        veri
                    }

                } else {
                    veri
                }

            val categories =
                parseCategories(
                    optArray(
                        fullChannel,
                        "categories"
                    )
                )

            return newLiveStreamLoadResponse(
                optStringOrNull(
                    fullChannel,
                    "title",
                    "name"
                ) ?: title,
                url,
                fullChannel.toString()
            ) {
                this.posterUrl =
                    optStringOrNull(
                        fullChannel,
                        "image",
                        "poster",
                        "poster_url"
                    ) ?: image

                this.plot =
                    optStringOrNull(
                        fullChannel,
                        "description",
                        "plot"
                    ) ?: description

                this.tags = categories
            }
        }

        // =====================================================
        // FILM
        // =====================================================

        val fullMovie =
            if (
                !hasArrayItems(
                    veri,
                    "sources"
                )
            ) {

                val path =
                    "/api/movie/by/$id/$SW_KEY/"

                try {
                    val response =
                        app.get(
                            "$mainUrl$path",
                            headers = getSignedHeaders(
                                "GET",
                                path
                            )
                        )

                    parseObject(response.text)
                        ?: veri

                } catch (e: Exception) {

                    Log.e(
                        "RecTV",
                        "Movie request failed: ${e.message}"
                    )

                    veri
                }

            } else {
                veri
            }

        return newMovieLoadResponse(
            optStringOrNull(
                fullMovie,
                "title",
                "name"
            ) ?: title,
            url,
            TvType.Movie,
            fullMovie.toString()
        ) {

            this.posterUrl =
                optStringOrNull(
                    fullMovie,
                    "image",
                    "poster",
                    "poster_url"
                ) ?: image

            this.plot =
                optStringOrNull(
                    fullMovie,
                    "description",
                    "plot",
                    "overview"
                ) ?: description

            this.year =
                optIntOrNull(
                    fullMovie,
                    "year"
                ) ?: year

            this.tags =
                parseGenres(
                    optArray(
                        fullMovie,
                        "genres"
                    )
                )
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        if (
            data.startsWith("http://") ||
            data.startsWith("https://")
        ) {

            val linkType =
                when {
                    data.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) -> ExtractorLinkType.M3U8

                    data.contains(
                        ".mp4",
                        ignoreCase = true
                    ) -> ExtractorLinkType.VIDEO

                    else -> INFER_TYPE
                }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = linkType
                ) {
                    this.referer = REFERER
                    this.headers = mapOf(
                        "Referer" to REFERER,
                        "User-Agent" to USER_AGENT
                    )
                    this.quality =
                        Qualities.Unknown.value
                }
            )

            return true
        }

        val sources =
            mutableListOf<JSONObject>()

        // -----------------------------------------------------
        // RecItem
        // -----------------------------------------------------
        val item =
            parseObject(data)

        item
            ?.optJSONArray("sources")
            ?.let { array ->
                for (index in 0 until array.length()) {
                    array
                        .optJSONObject(index)
                        ?.let { source ->
                            sources += source
                        }
                }
            }

        // -----------------------------------------------------
        // RecItem içinden kaynak bulunmadıysa full data çek
        // -----------------------------------------------------
        if (
            sources.isEmpty() &&
            item != null
        ) {

            val id =
                optIntOrNull(
                    item,
                    "id"
                )
                    ?: 0

            if (id > 0) {

                try {

                    val type =
                        optStringOrNull(
                            item,
                            "type"
                        )
                            ?.lowercase()

                    if (type == "movie") {

                        val path =
                            "/api/movie/by/$id/$SW_KEY/"

                        val response =
                            app.get(
                                "$mainUrl$path",
                                headers = getSignedHeaders(
                                    "GET",
                                    path
                                )
                            )

                        parseObject(response.text)
                            ?.optJSONArray("sources")
                            ?.let { array ->
                                for (index in 0 until array.length()) {
                                    array
                                        .optJSONObject(index)
                                        ?.let { source ->
                                            sources += source
                                        }
                                }
                            }

                    } else {

                        val isChannel =
                            optStringOrNull(
                                item,
                                "label"
                            ).equals(
                                "CANLI",
                                ignoreCase = true
                            ) ||
                                hasArrayItems(
                                    item,
                                    "categories"
                                )

                        if (isChannel) {

                            val path =
                                "/api/channel/by/$id/$SW_KEY/"

                            val response =
                                app.get(
                                    "$mainUrl$path",
                                    headers = getSignedHeaders(
                                        "GET",
                                        path
                                    )
                                )

                            parseObject(response.text)
                                ?.optJSONArray("sources")
                                ?.let { array ->
                                    for (index in 0 until array.length()) {
                                        array
                                            .optJSONObject(index)
                                            ?.let { source ->
                                                sources += source
                                            }
                                    }
                                }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        "RecTV",
                        "Failed to fetch item sources: ${e.message}"
                    )
                }
            }
        }

        // -----------------------------------------------------
        // Episode
        // -----------------------------------------------------
        if (sources.isEmpty()) {

            item
                ?.optJSONArray("source")
                ?.let { array ->
                    for (index in 0 until array.length()) {
                        array
                            .optJSONObject(index)
                            ?.let { source ->
                                sources += source
                            }
                    }
                }
        }

        if (sources.isEmpty()) {
            return false
        }

        var delivered =
            false

        for (source in sources) {

            val encrypted =
                optStringOrNull(
                    source,
                    "enc_url"
                )

            val sourceId =
                optIntOrNull(
                    source,
                    "id"
                )

            val locked =
                optBooleanOrNull(
                    source,
                    "locked"
                ) == true

            val enc =
                if (!encrypted.isNullOrBlank()) {
                    encrypted
                } else if (
                    (locked || encrypted.isNullOrBlank()) &&
                    sourceId != null
                ) {
                    unlockSource(sourceId)
                } else {
                    null
                }

            val streamUrl =
                if (!enc.isNullOrBlank()) {
                    decryptEncUrl(enc)
                } else {
                    optStringOrNull(
                        source,
                        "url"
                    )
                }

            if (streamUrl.isNullOrBlank()) {
                continue
            }

            val sourceTitle =
                optStringOrNull(
                    source,
                    "title",
                    "quality",
                    "type"
                ) ?: "Kaynak"

            val sourceType =
                optStringOrNull(
                    source,
                    "type"
                )
                    ?.lowercase()
                    .orEmpty()

            val linkType =
                when {
                    streamUrl.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) ||
                        sourceType == "m3u8" ->
                        ExtractorLinkType.M3U8

                    sourceType == "mp4" ||
                        streamUrl.endsWith(
                            ".mp4",
                            ignoreCase = true
                        ) ->
                        ExtractorLinkType.VIDEO

                    else ->
                        INFER_TYPE
                }

            val quality =
                optStringOrNull(
                    source,
                    "quality"
                )
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()
                    ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $sourceTitle",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = REFERER
                    this.headers = mapOf(
                        "Referer" to REFERER,
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = quality
                }
            )

            delivered =
                true
        }

        return delivered
    }

    // =========================================================
    // VIDEO INTERCEPTOR
    // =========================================================

    override fun getVideoInterceptor(
        extractorLink: ExtractorLink
    ): Interceptor {

        return Interceptor { chain ->

            val modifiedRequest =
                chain
                    .request()
                    .newBuilder()
                    .removeHeader("If-None-Match")
                    .header(
                        "User-Agent",
                        USER_AGENT
                    )
                    .header(
                        "Referer",
                        REFERER
                    )
                    .build()

            chain.proceed(
                modifiedRequest
            )
        }
    }

    // =========================================================
    // CATEGORY PARSERS
    // =========================================================

    private fun parseGenres(
        array: JSONArray?
    ): List<String>? {

        if (array == null || array.length() == 0) {
            return null
        }

        val result =
            mutableListOf<String>()

        for (index in 0 until array.length()) {

            val item =
                array.optJSONObject(index)
                    ?: continue

            optStringOrNull(
                item,
                "title",
                "name"
            )?.let {
                result += it
            }
        }

        return result
            .distinct()
            .takeIf {
                it.isNotEmpty()
            }
    }

    private fun parseCategories(
        array: JSONArray?
    ): List<String>? {

        if (array == null || array.length() == 0) {
            return null
        }

        val result =
            mutableListOf<String>()

        for (index in 0 until array.length()) {

            val item =
                array.optJSONObject(index)
                    ?: continue

            optStringOrNull(
                item,
                "title",
                "name"
            )?.let {
                result += it
            }
        }

        return result
            .distinct()
            .takeIf {
                it.isNotEmpty()
            }
    }
}
