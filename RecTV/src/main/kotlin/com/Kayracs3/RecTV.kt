```kotlin
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

            val offset =
                index * 2

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

            if (
                now <
                jwtExpirationTimestamp - 300
            ) {
                return token
            }
        }

        return try {

            val currentNow =
                System.currentTimeMillis() / 1000L

            val recheckJwt =
                cachedJwt

            if (
                recheckJwt != null &&
                currentNow <
                jwtExpirationTimestamp - 300
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
                    ?.takeIf {
                        it.isNotBlank()
                    }

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
            (System.currentTimeMillis() / 1000L)
                .toString()

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
                raw.copyOfRange(
                    0,
                    12
                )

            val cipherTextAndTag =
                raw.copyOfRange(
                    12,
                    raw.size
                )

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

            val body =
                "{}"

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
                .takeIf {
                    it.isNotBlank()
                }

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
                value.toString()
                    .trim()

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

                is String -> {

                    when (
                        value
                            .trim()
                            .lowercase()
                    ) {

                        "true",
                        "1",
                        "yes" ->
                            return true

                        "false",
                        "0",
                        "no" ->
                            return false
                    }
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

        return optArray(
            obj,
            *keys
        )
            ?.length()
            ?.let {
                it > 0
            }
            ?: false
    }

    private fun objectToString(
        obj: JSONObject
    ): String =
        obj.toString()

    // =========================================================
    // ROBUST HOME PAGE JSON PARSER
    // =========================================================

    private fun extractItemObjects(
        text: String
    ): List<JSONObject> {

        val result =
            mutableListOf<JSONObject>()

        fun addArray(
            array: JSONArray?
        ) {

            if (array == null) {
                return
            }

            for (index in 0 until array.length()) {

                val obj =
                    array.optJSONObject(index)

                if (obj != null) {
                    result += obj
                }
            }
        }

        try {

            val directArray =
                JSONArray(text)

            addArray(directArray)

            if (result.isNotEmpty()) {
                return result
            }

        } catch (_: Exception) {
        }

        fun scanObject(
            obj: JSONObject,
            depth: Int = 0
        ) {

            if (
                depth > 5 ||
                result.isNotEmpty()
            ) {
                return
            }

            val priorityKeys =
                listOf(
                    "data",
                    "results",
                    "items",
                    "movies",
                    "series",
                    "channels",
                    "posters",
                    "contents",
                    "records",
                    "rows",
                    "list"
                )

            for (key in priorityKeys) {

                val array =
                    obj.optJSONArray(key)

                if (array != null) {

                    addArray(array)

                    if (result.isNotEmpty()) {
                        return
                    }
                }

                val child =
                    obj.optJSONObject(key)

                if (child != null) {

                    scanObject(
                        child,
                        depth + 1
                    )

                    if (result.isNotEmpty()) {
                        return
                    }
                }
            }

            val keys =
                obj.keys()

            while (keys.hasNext()) {

                val key =
                    keys.next()

                val value =
                    obj.opt(key)

                when (value) {

                    is JSONArray -> {

                        addArray(value)

                        if (result.isNotEmpty()) {
                            return
                        }
                    }

                    is JSONObject -> {

                        scanObject(
                            value,
                            depth + 1
                        )

                        if (result.isNotEmpty()) {
                            return
                        }
                    }
                }
            }
        }

        try {

            val root =
                JSONObject(text)

            scanObject(root)

        } catch (_: Exception) {
        }

        return result
    }

    // =========================================================
    // IMAGE NORMALIZATION
    // =========================================================

    private fun normalizeImageUrl(
        value: String?
    ): String? {

        val url =
            value
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        return when {

            url.startsWith(
                "http://"
            ) ||
                url.startsWith(
                    "https://"
                ) ->
                url

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                "$mainUrl$url"

            else ->
                "$mainUrl/$url"
        }
    }

    private fun optImageUrl(
        obj: JSONObject
    ): String? {

        val simpleKeys =
            listOf(
                "image",
                "poster",
                "poster_url",
                "posterUrl",
                "thumbnail",
                "thumbnail_url",
                "thumb",
                "cover",
                "cover_url",
                "image_url",
                "imageUrl"
            )

        for (key in simpleKeys) {

            val value =
                obj.opt(key)

            when (value) {

                is String -> {

                    normalizeImageUrl(
                        value
                    )?.let {
                        return it
                    }
                }

                is JSONObject -> {

                    val nested =
                        optStringOrNull(
                            value,
                            "url",
                            "src",
                            "image",
                            "poster",
                            "path",
                            "original",
                            "medium",
                            "large"
                        )

                    normalizeImageUrl(
                        nested
                    )?.let {
                        return it
                    }
                }
            }
        }

        val images =
            obj.optJSONObject(
                "images"
            )

        if (images != null) {

            val nested =
                optStringOrNull(
                    images,
                    "poster",
                    "poster_url",
                    "cover",
                    "image",
                    "url",
                    "src"
                )

            normalizeImageUrl(
                nested
            )?.let {
                return it
            }
        }

        return null
    }

    // =========================================================
    // TYPE DETECTION
    // =========================================================

    private fun detectItemType(
        obj: JSONObject
    ): String {

        val explicit =
            optStringOrNull(
                obj,
                "type",
                "content_type",
                "contentType",
                "kind",
                "model_type",
                "modelType"
            )
                ?.lowercase()
                .orEmpty()

        return when {

            explicit.contains(
                "live"
            ) ||
                explicit.contains(
                    "channel"
                ) ||
                explicit.contains(
                    "sport"
                ) ||
                explicit.contains(
                    "canli"
                ) ->
                "live"

            explicit.contains(
                "serie"
            ) ||
                explicit.contains(
                    "series"
                ) ||
                explicit.contains(
                    "tvshow"
                ) ||
                explicit == "tv" ||
                explicit.contains(
                    "dizi"
                ) ->
                "serie"

            else ->
                "movie"
        }
    }

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

                java.net.URI(
                    url
                ).rawPath

            } catch (_: Exception) {

                url.substringBefore(
                    "?"
                )
            }

        val response =
            try {

                app.get(
                    url,
                    headers = getSignedHeaders(
                        method = "GET",
                        path = path
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

        Log.d(
            "RecTV",
            "MAIN PAGE [${request.name}] LENGTH=${response.text.length}"
        )

        Log.d(
            "RecTV",
            "MAIN RAW: ${response.text.take(6000)}"
        )

        val objects =
            extractItemObjects(
                response.text
            )

        Log.d(
            "RecTV",
            "MAIN PARSED OBJECTS=${objects.size}"
        )

        if (objects.isEmpty()) {

            Log.e(
                "RecTV",
                "Main page returned no content objects. URL=$url"
            )

            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val results =
            mutableListOf<SearchResponse>()

        for (item in objects) {

            val id =
                optIntOrNull(
                    item,
                    "id",
                    "movie_id",
                    "serie_id",
                    "channel_id"
                ) ?: 0

            val title =
                optStringOrNull(
                    item,
                    "title",
                    "name",
                    "movie_name",
                    "serie_name",
                    "channel_name"
                ) ?: continue

            val image =
                optImageUrl(
                    item
                )

            val detectedType =
                detectItemType(
                    item
                )

            val label =
                optStringOrNull(
                    item,
                    "label",
                    "status"
                ).orEmpty()

            val forcedLive =
                request.name.equals(
                    "Spor",
                    ignoreCase = true
                ) ||
                    request.name.equals(
                        "Canlı TV",
                        ignoreCase = true
                    ) ||
                    label.equals(
                        "CANLI",
                        ignoreCase = true
                    )

            item.put(
                "_rectv_section",
                request.name
            )

            if (id > 0) {

                item.put(
                    "_rectv_id",
                    id
                )
            }

            val data =
                item.toString()

            when {

                forcedLive ||
                    detectedType == "live" -> {

                    results +=
                        newLiveSearchResponse(
                            title,
                            data,
                            TvType.Live
                        ) {

                            this.posterUrl =
                                image
                        }
                }

                detectedType == "serie" -> {

                    results +=
                        newTvSeriesSearchResponse(
                            title,
                            data,
                            TvType.TvSeries
                        ) {

                            this.posterUrl =
                                image
                        }
                }

                else -> {

                    results +=
                        newMovieSearchResponse(
                            title,
                            data,
                            TvType.Movie
                        ) {

                            this.posterUrl =
                                image
                        }
                }
            }

            if (
                results.size >= 24
            ) {
                break
            }
        }

        Log.d(
            "RecTV",
            "MAIN RESULTS=${results.size}"
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = objects.size >= 24
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
                .encode(
                    query,
                    "UTF-8"
                )
                .replace(
                    "+",
                    "%20"
                )

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
            parseObject(
                response.text
            )

        val results =
            mutableListOf<SearchResponse>()

        if (json == null) {

            val objects =
                extractItemObjects(
                    response.text
                )

            for (item in objects) {

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                val image =
                    optImageUrl(
                        item
                    )

                when (
                    detectItemType(
                        item
                    )
                ) {

                    "live" -> {

                        results +=
                            newLiveSearchResponse(
                                title,
                                item.toString(),
                                TvType.Live
                            ) {

                                this.posterUrl =
                                    image
                            }
                    }

                    "serie" -> {

                        results +=
                            newTvSeriesSearchResponse(
                                title,
                                item.toString(),
                                TvType.TvSeries
                            ) {

                                this.posterUrl =
                                    image
                            }
                    }

                    else -> {

                        results +=
                            newMovieSearchResponse(
                                title,
                                item.toString(),
                                TvType.Movie
                            ) {

                                this.posterUrl =
                                    image
                            }
                    }
                }
            }

            return results
        }

        val channels =
            optArray(
                json,
                "channels"
            )

        if (channels != null) {

            for (
                index in 0 until channels.length()
            ) {

                val item =
                    channels.optJSONObject(
                        index
                    ) ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                val image =
                    optImageUrl(
                        item
                    )

                results +=
                    newLiveSearchResponse(
                        title,
                        objectToString(item),
                        TvType.Live
                    ) {

                        this.posterUrl =
                            image
                    }
            }
        }

        val posters =
            optArray(
                json,
                "posters",
                "results",
                "items",
                "data"
            )

        if (posters != null) {

            for (
                index in 0 until posters.length()
            ) {

                val item =
                    posters.optJSONObject(
                        index
                    ) ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                val image =
                    optImageUrl(
                        item
                    )

                when (
                    detectItemType(
                        item
                    )
                ) {

                    "serie" -> {

                        results +=
                            newTvSeriesSearchResponse(
                                title,
                                objectToString(item),
                                TvType.TvSeries
                            ) {

                                this.posterUrl =
                                    image
                            }
                    }

                    "live" -> {

                        results +=
                            newLiveSearchResponse(
                                title,
                                objectToString(item),
                                TvType.Live
                            ) {

                                this.posterUrl =
                                    image
                            }
                    }

                    else -> {

                        results +=
                            newMovieSearchResponse(
                                title,
                                objectToString(item),
                                TvType.Movie
                            ) {

                                this.posterUrl =
                                    image
                            }
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
            parseObject(
                url
            ) ?: return null

        val id =
            optIntOrNull(
                veri,
                "id",
                "_rectv_id",
                "movie_id",
                "serie_id",
                "channel_id"
            ) ?: return null

        val title =
            optStringOrNull(
                veri,
                "title",
                "name",
                "movie_name",
                "serie_name",
                "channel_name"
            ) ?: "RecTV"

        val image =
            optImageUrl(
                veri
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

        val rawType =
            optStringOrNull(
                veri,
                "type",
                "content_type",
                "contentType",
                "kind",
                "model_type",
                "modelType"
            )
                ?.lowercase()
                .orEmpty()

        val section =
            optStringOrNull(
                veri,
                "_rectv_section"
            ).orEmpty()

        val detectedType =
            detectItemType(
                veri
            )

        val isSeries =
            detectedType == "serie" ||
                rawType == "series" ||
                rawType == "tv" ||
                rawType == "tvshow" ||
                rawType == "dizi" ||
                section.contains(
                    "dizi",
                    ignoreCase = true
                )

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

        if (isSeries) {

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
                parseArray(
                    response.text
                ) ?: run {

                    val json =
                        parseObject(
                            response.text
                        )

                    optArray(
                        json ?: JSONObject(),
                        "data",
                        "results",
                        "seasons",
                        "items"
                    )
                } ?: return null

            val episodes =
                mutableMapOf<
                    DubStatus,
                    MutableList<Episode>
                >()

            val numberRegex =
                Regex(
                    "\\d+"
                )

            for (
                seasonIndex in 0 until seasons.length()
            ) {

                val season =
                    seasons.optJSONObject(
                        seasonIndex
                    ) ?: continue

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
                        ) ||
                            seasonTitle.contains(
                                "altyazi",
                                ignoreCase = true
                            ) ->
                            DubStatus.Subbed

                        seasonTitle.contains(
                            "dublaj",
                            ignoreCase = true
                        ) ->
                            DubStatus.Dubbed

                        else ->
                            DubStatus.None
                    }

                val seasonNumber =
                    numberRegex
                        .find(
                            seasonTitle
                        )
                        ?.value
                        ?.toIntOrNull()

                val seasonEpisodes =
                    optArray(
                        season,
                        "episodes",
                        "bolumler",
                        "items"
                    ) ?: continue

                for (
                    episodeIndex in 0 until seasonEpisodes.length()
                ) {

                    val episodeJson =
                        seasonEpisodes.optJSONObject(
                            episodeIndex
                        ) ?: continue

                    val episodeTitle =
                        optStringOrNull(
                            episodeJson,
                            "title",
                            "name"
                        )
                            ?: "${episodeIndex + 1}. Bölüm"

                    val episodeNumber =
                        numberRegex
                            .find(
                                episodeTitle
                            )
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
                        .getOrPut(
                            dubStatus
                        ) {
                            mutableListOf()
                        }
                        .add(
                            newEpisode(
                                episodeJson.toString()
                            ) {

                                this.name =
                                    episodeTitle

                                this.season =
                                    seasonNumber

                                this.episode =
                                    episodeNumber

                                this.description =
                                    episodeDescription

                                this.posterUrl =
                                    image
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
                        .mapValues {
                            it.value.toList()
                        }
                        .toMutableMap()

                this.posterUrl =
                    image

                this.plot =
                    description

                this.year =
                    year

                this.tags =
                    genres
            }
        }

        // =====================================================
        // CANLI TV / SPOR
        // =====================================================

        val isLive =
            optStringOrNull(
                veri,
                "label",
                "status"
            ).equals(
                "CANLI",
                ignoreCase = true
            ) ||
                detectedType == "live" ||
                rawType.contains(
                    "channel"
                ) ||
                rawType.contains(
                    "live"
                ) ||
                rawType.contains(
                    "sport"
                ) ||
                section.equals(
                    "Spor",
                    ignoreCase = true
                ) ||
                section.equals(
                    "Canlı TV",
                    ignoreCase = true
                ) ||
                veri.has(
                    "channel_id"
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

                        parseObject(
                            response.text
                        ) ?: veri

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
                    "name",
                    "channel_name"
                ) ?: title,
                url,
                fullChannel.toString()
            ) {

                this.posterUrl =
                    optImageUrl(
                        fullChannel
                    ) ?: image

                this.plot =
                    optStringOrNull(
                        fullChannel,
                        "description",
                        "plot",
                        "overview"
                    ) ?: description

                this.tags =
                    categories
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

                    parseObject(
                        response.text
                    ) ?: veri

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
                "name",
                "movie_name"
            ) ?: title,
            url,
            TvType.Movie,
            fullMovie.toString()
        ) {

            this.posterUrl =
                optImageUrl(
                    fullMovie
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
            data.startsWith(
                "http://"
            ) ||
                data.startsWith(
                    "https://"
                )
        ) {

            val linkType =
                when {

                    data.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) ->
                        ExtractorLinkType.M3U8

                    data.contains(
                        ".mp4",
                        ignoreCase = true
                    ) ->
                        ExtractorLinkType.VIDEO

                    else ->
                        INFER_TYPE
                }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = linkType
                ) {

                    this.referer =
                        REFERER

                    this.headers =
                        mapOf(
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
            parseObject(
                data
            )

        item
            ?.optJSONArray(
                "sources"
            )
            ?.let { array ->

                for (
                    index in 0 until array.length()
                ) {

                    array
                        .optJSONObject(
                            index
                        )
                        ?.let { source ->

                            sources +=
                                source
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
                    "id",
                    "_rectv_id",
                    "movie_id",
                    "serie_id",
                    "channel_id"
                ) ?: 0

            if (id > 0) {

                try {

                    val type =
                        detectItemType(
                            item
                        )

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

                        parseObject(
                            response.text
                        )
                            ?.optJSONArray(
                                "sources"
                            )
                            ?.let { array ->

                                for (
                                    index in 0 until array.length()
                                ) {

                                    array
                                        .optJSONObject(
                                            index
                                        )
                                        ?.let { source ->

                                            sources +=
                                                source
                                        }
                                }
                            }

                    } else {

                        val isChannel =
                            type == "live" ||
                                optStringOrNull(
                                    item,
                                    "label"
                                ).equals(
                                    "CANLI",
                                    ignoreCase = true
                                ) ||
                                item.has(
                                    "channel_id"
                                ) ||
                                optStringOrNull(
                                    item,
                                    "_rectv_section"
                                ).equals(
                                    "Canlı TV",
                                    ignoreCase = true
                                ) ||
                                optStringOrNull(
                                    item,
                                    "_rectv_section"
                                ).equals(
                                    "Spor",
                                    ignoreCase = true
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

                            parseObject(
                                response.text
                            )
                                ?.optJSONArray(
                                    "sources"
                                )
                                ?.let { array ->

                                    for (
                                        index in 0 until array.length()
                                    ) {

                                        array
                                            .optJSONObject(
                                                index
                                            )
                                            ?.let { source ->

                                                sources +=
                                                    source
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
        // Episode source
        // -----------------------------------------------------

        if (sources.isEmpty()) {

            item
                ?.optJSONArray(
                    "source"
                )
                ?.let { array ->

                    for (
                        index in 0 until array.length()
                    ) {

                        array
                            .optJSONObject(
                                index
                            )
                            ?.let { source ->

                                sources +=
                                    source
                            }
                    }
                }
        }

        // -----------------------------------------------------
        // Tek source objesi
        // -----------------------------------------------------

        if (
            sources.isEmpty() &&
            item != null
        ) {

            item
                .optJSONObject(
                    "source"
                )
                ?.let { source ->

                    sources +=
                        source
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
                    "enc_url",
                    "encUrl"
                )

            val sourceId =
                optIntOrNull(
                    source,
                    "id",
                    "source_id",
                    "sourceId"
                )

            val locked =
                optBooleanOrNull(
                    source,
                    "locked",
                    "is_locked",
                    "isLocked"
                ) == true

            val enc =
                if (
                    !encrypted.isNullOrBlank()
                ) {

                    encrypted

                } else if (
                    (
                        locked ||
                            encrypted.isNullOrBlank()
                        ) &&
                        sourceId != null
                ) {

                    unlockSource(
                        sourceId
                    )

                } else {

                    null
                }

            val streamUrl =
                if (
                    !enc.isNullOrBlank()
                ) {

                    decryptEncUrl(
                        enc
                    )

                } else {

                    optStringOrNull(
                        source,
                        "url",
                        "stream_url",
                        "streamUrl",
                        "file"
                    )
                }

            if (
                streamUrl.isNullOrBlank()
            ) {
                continue
            }

            val sourceTitle =
                optStringOrNull(
                    source,
                    "title",
                    "quality",
                    "type",
                    "name"
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
                        streamUrl.contains(
                            "master.m3u8",
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
                    "quality",
                    "resolution"
                )
                    ?.filter {
                        it.isDigit()
                    }
                    ?.toIntOrNull()
                    ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $sourceTitle",
                    url = streamUrl,
                    type = linkType
                ) {

                    this.referer =
                        REFERER

                    this.headers =
                        mapOf(
                            "Referer" to REFERER,
                            "User-Agent" to USER_AGENT
                        )

                    this.quality =
                        quality
                }
            )

            delivered = true
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
                    .removeHeader(
                        "If-None-Match"
                    )
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

        if (
            array == null ||
            array.length() == 0
        ) {
            return null
        }

        val result =
            mutableListOf<String>()

        for (
            index in 0 until array.length()
        ) {

            val value =
                array.opt(index)

            when (value) {

                is JSONObject -> {

                    optStringOrNull(
                        value,
                        "title",
                        "name",
                        "label"
                    )?.let {
                        result += it
                    }
                }

                is String -> {

                    value
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            result += it
                        }
                }
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

        if (
            array == null ||
            array.length() == 0
        ) {
            return null
        }

        val result =
            mutableListOf<String>()

        for (
            index in 0 until array.length()
        ) {

            val value =
                array.opt(index)

            when (value) {

                is JSONObject -> {

                    optStringOrNull(
                        value,
                        "title",
                        "name",
                        "label"
                    )?.let {
                        result += it
                    }
                }

                is String -> {

                    value
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            result += it
                        }
                }
            }
        }

        return result
            .distinct()
            .takeIf {
                it.isNotEmpty()
            }
    }
}
```
