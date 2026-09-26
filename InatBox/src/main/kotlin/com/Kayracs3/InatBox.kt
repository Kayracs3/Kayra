package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InatBox : MainAPI() {

    // =========================================================
    // INATBOX CONTENT SERVER
    // =========================================================

    private val contentUrl =
        "https://diziboxen.help/CDN/001/002/dizibox"

    // =========================================================
    // BASIC INFO
    // =========================================================

    override var name = "InatBox"

    override val hasMainPage = true

    override var lang = "tr"

    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    override var sequentialMainPage = false

    // =========================================================
    // SEARCH CACHE
    // =========================================================

    private val urlToSearchResponse =
        mutableMapOf<String, SearchResponse>()

    // =========================================================
    // AES KEY
    // =========================================================

    private val aesKey =
        "ywevqtjrurkwtqgz"

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(

        "$contentUrl/tv/list1.php" to
            "Spor ve Kanallar",

        "$contentUrl/tv/list2.php" to
            "Kanallar Liste 2",

        "$contentUrl/tv/sinema.php" to
            "Sinema Kanalları",

        "$contentUrl/tv/belgesel.php" to
            "Belgesel Kanalları",

        "$contentUrl/tv/ulusal.php" to
            "Ulusal Kanallar",

        "$contentUrl/tv/haber.php" to
            "Haber Kanalları",

        "$contentUrl/tv/cocuk.php" to
            "Çocuk Kanalları",

        "$contentUrl/tv/dini.php" to
            "Dini Kanallar",

        "$contentUrl/ex/index.php" to
            "EXXEN",

        "$contentUrl/ga/index.php" to
            "Gain",

        "$contentUrl/nf/index.php" to
            "Netflix",

        "$contentUrl/dsny/index.php" to
            "Disney+",

        "$contentUrl/amz/index.php" to
            "Amazon Prime",

        "$contentUrl/hb/index.php" to
            "HBO Max",

        "$contentUrl/tbi/index.php" to
            "Tabii",

        "$contentUrl/film/mubi.php" to
            "Mubi",

        "$contentUrl/yabanci-dizi/index.php" to
            "Yabancı Diziler",

        "$contentUrl/yerli-dizi/index.php" to
            "Yerli Diziler",

        "$contentUrl/film/yerli-filmler.php" to
            "Yerli Filmler",

        "$contentUrl/film/4k-film-exo.php" to
            "4K Film İzle | Exo"
    )

    // =========================================================
    // LOG
    // =========================================================

    private fun log(
        message: String
    ) {
        Log.d(
            "InatBox",
            message
        )
    }

    private fun logError(
        message: String,
        throwable: Throwable? = null
    ) {
        Log.e(
            "InatBox",
            message,
            throwable
        )
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {

            log(
                "MAIN PAGE -> ${request.data}"
            )

            val jsonResponse =
                makeInatRequest(
                    request.data
                )
                    ?: return newHomePageResponse(
                        request.name,
                        emptyList(),
                        hasNext = false
                    )

            val searchResults =
                getSearchResponseList(
                    jsonResponse
                )

            for (
                searchResponse in searchResults
            ) {

                val url =
                    searchResponse.url

                if (
                    !urlToSearchResponse
                        .containsKey(url)
                ) {

                    urlToSearchResponse[url] =
                        searchResponse
                }
            }

            log(
                "MAIN PAGE RESULT -> ${searchResults.size}"
            )

            newHomePageResponse(
                request.name,
                searchResults,
                hasNext = false
            )

        } catch (e: Exception) {

            logError(
                "MAIN PAGE ERROR",
                e
            )

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (
            urlToSearchResponse.isEmpty()
        ) {

            for (
                pageData in mainPage
            ) {

                try {

                    val url =
                        pageData.data

                    val jsonResponse =
                        makeInatRequest(
                            url
                        )
                            ?: continue

                    val searchResults =
                        getSearchResponseList(
                            jsonResponse
                        )

                    for (
                        searchResponse in searchResults
                    ) {

                        val contentUrl =
                            searchResponse.url

                        if (
                            !urlToSearchResponse
                                .containsKey(contentUrl)
                        ) {

                            urlToSearchResponse[
                                contentUrl
                            ] =
                                searchResponse
                        }
                    }

                } catch (e: Exception) {

                    logError(
                        "SEARCH CACHE ERROR",
                        e
                    )
                }
            }
        }

        val matchingResults =
            mutableListOf<SearchResponse>()

        val regex =
            try {

                Regex(
                    query,
                    RegexOption.IGNORE_CASE
                )

            } catch (_: Exception) {

                Regex(
                    Regex.escape(query),
                    RegexOption.IGNORE_CASE
                )
            }

        for (
            (_, searchResponse)
            in urlToSearchResponse
        ) {

            if (
                regex.containsMatchIn(
                    searchResponse.name
                )
            ) {

                matchingResults.add(
                    searchResponse
                )
            }
        }

        return matchingResults
            .distinctBy {
                it.name
            }
    }

    // =========================================================
    // QUICK SEARCH
    // =========================================================

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        return try {

            val item =
                JSONObject(
                    url
                )

            if (
                !inatContentAllowed(
                    item
                )
            ) {

                return null
            }

            if (
                item.has(
                    "diziType"
                )
            ) {

                item.getString(
                    "diziName"
                )

                val type =
                    item.getString(
                        "diziType"
                    )

                when (type) {

                    "dizi",
                    "dizi_mode" -> {

                        parseTvSeriesResponse(
                            item
                        )
                    }

                    "film",
                    "film_mode" -> {

                        parseMovieResponse(
                            item
                        )
                    }

                    else -> {

                        null
                    }
                }

            } else if (
                item.has("chName") &&
                item.has("chUrl") &&
                item.has("chImg")
            ) {

                item.getString(
                    "chName"
                )

                val chType =
                    item.getString(
                        "chType"
                    )

                when (chType) {

                    "live_url",
                    "cable_sh" -> {

                        parseLiveStreamLoadResponse(
                            item
                        )
                    }

                    "tekli_regex_lb_sh_3" -> {

                        parseLiveSportsStreamLoadResponse(
                            item
                        )
                    }

                    else -> {

                        parseMovieResponse(
                            item
                        )
                    }
                }

            } else {

                null
            }

        } catch (e: Exception) {

            logError(
                "LOAD ERROR",
                e
            )

            null
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        log(
            "LOAD LINKS -> $data"
        )

        return try {

            if (
                data.startsWith("[")
            ) {

                val chContentJsonArray =
                    JSONArray(
                        data
                    )

                for (
                    i in 0 until
                    chContentJsonArray.length()
                ) {

                    val chContentJsonObject =
                        chContentJsonArray.getJSONObject(
                            i
                        )

                    val chContent =
                        parseToChContent(
                            chContentJsonObject
                        )

                    loadChContentLinks(
                        chContent,
                        subtitleCallback,
                        callback
                    )
                }

            } else {

                val chContentJsonObject =
                    JSONObject(
                        data
                    )

                val chContent =
                    parseToChContent(
                        chContentJsonObject
                    )

                loadChContentLinks(
                    chContent,
                    subtitleCallback,
                    callback
                )
            }

            true

        } catch (e: Exception) {

            logError(
                "LOAD LINKS ERROR",
                e
            )

            false
        }
    }

    // =========================================================
    // TV SERIES
    // =========================================================

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {

        val episodes =
            mutableMapOf<
                DubStatus,
                MutableList<Episode>
            >()

        val seasonDataList =
            mutableListOf<SeasonData>()

        val name =
            item.getString(
                "diziName"
            )

        val url =
            item.getString(
                "diziUrl"
            )

        val plot =
            item.getString(
                "diziDetay"
            )

        val jsonResponse =
            makeInatRequest(
                url
            )
                ?: return null

        val jsonArray =
            JSONArray(
                jsonResponse
            )

        return try {

            for (
                i in 0 until jsonArray.length()
            ) {

                val seasonItem =
                    jsonArray.getJSONObject(
                        i
                    )

                val seasonName =
                    seasonItem.getString(
                        "diziName"
                    )

                val seasonData =
                    SeasonData(
                        season = i + 1,
                        name = seasonName
                    )

                seasonDataList.add(
                    seasonData
                )

                val seasonUrl =
                    seasonItem.getString(
                        "diziUrl"
                    )

                val episodeResponse =
                    makeInatRequest(
                        seasonUrl
                    )
                        ?: continue

                val episodeArray =
                    try {

                        JSONArray(
                            episodeResponse
                        )

                    } catch (e: Exception) {

                        logError(
                            "FAILED TO PARSE EPISODE JSON FOR SEASON: $seasonName",
                            e
                        )

                        continue
                    }

                for (
                    j in 0 until
                    episodeArray.length()
                ) {

                    try {

                        val episodeItem =
                            episodeArray.getJSONObject(
                                j
                            )

                        val episodeName =
                            episodeItem.getString(
                                "chName"
                            )

                        val episodePoster =
                            episodeItem.getString(
                                "chImg"
                            )

                        episodes
                            .getOrPut(
                                DubStatus.None
                            ) {
                                mutableListOf()
                            }
                            .add(
                                newEpisode(
                                    episodeItem.toString()
                                ) {

                                    this.name =
                                        episodeName

                                    this.posterUrl =
                                        episodePoster

                                    this.season =
                                        i + 1

                                    this.episode =
                                        j + 1
                                }
                            )

                    } catch (_: JSONException) {
                    }
                }
            }

            if (
                jsonArray.length() == 0
            ) {

                return null
            }

            val firstSeason =
                jsonArray.getJSONObject(
                    0
                )

            val posterUrl =
                firstSeason.getString(
                    "diziImg"
                )

            newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {

                this.episodes =
                    episodes
                        .mapValues {
                            it.value.toList()
                        }
                        .toMutableMap()

                this.posterUrl =
                    posterUrl

                this.plot =
                    plot

                this.seasonNames =
                    seasonDataList
            }

        } catch (e: Exception) {

            logError(
                "FAILED TO PARSE TV SERIES RESPONSE",
                e
            )

            null
        }
    }

    // =========================================================
    // MOVIE
    // =========================================================

    private suspend fun parseMovieResponse(
        item: JSONObject
    ): LoadResponse? {

        return try {

            if (
                item.has(
                    "diziType"
                )
            ) {

                val name =
                    item.getString(
                        "diziName"
                    )

                val url =
                    item.getString(
                        "diziUrl"
                    )

                val posterUrl =
                    item.getString(
                        "diziImg"
                    )

                val plot =
                    item.getString(
                        "diziDetay"
                    )

                val jsonResponse =
                    makeInatRequest(
                        url
                    )
                        ?: return null

                val jsonArray =
                    JSONArray(
                        jsonResponse
                    )

                newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {

                    this.posterUrl =
                        posterUrl

                    this.plot =
                        plot
                }

            } else {

                val name =
                    item.getString(
                        "chName"
                    )

                val posterUrl =
                    item.getString(
                        "chImg"
                    )

                newMovieLoadResponse(
                    name,
                    item.toString(),
                    TvType.Movie,
                    item.toString()
                ) {

                    this.posterUrl =
                        posterUrl
                }
            }

        } catch (e: Exception) {

            logError(
                "FAILED TO PARSE MOVIE RESPONSE",
                e
            )

            null
        }
    }

    // =========================================================
    // LIVE SPORTS
    // =========================================================

    private suspend fun parseLiveSportsStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {

        return try {

            val chContent =
                parseToChContent(
                    item
                )

            val posterUrl =
                chContent.chImg

            newLiveStreamLoadResponse(
                name,
                item.toString(),
                item.toString()
            ) {

                this.posterUrl =
                    posterUrl
            }

        } catch (e: Exception) {

            logError(
                "FAILED TO PARSE SPORTS LIVE STREAM RESPONSE",
                e
            )

            null
        }
    }

    // =========================================================
    // LIVE STREAM
    // =========================================================

    private suspend fun parseLiveStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {

        return try {

            val chContent =
                parseToChContent(
                    item
                )

            val channelName =
                chContent.chName

            val posterUrl =
                chContent.chImg

            newLiveStreamLoadResponse(
                channelName,
                item.toString(),
                item.toString()
            ) {

                this.posterUrl =
                    posterUrl
            }

        } catch (e: Exception) {

            logError(
                "FAILED TO PARSE LIVE STREAM RESPONSE",
                e
            )

            null
        }
    }

    // =========================================================
    // CONTENT FILTER
    // =========================================================

    private fun inatContentAllowed(
        item: JSONObject
    ): Boolean {

        val type =
            if (
                item.has(
                    "diziType"
                )
            ) {

                item.getString(
                    "diziType"
                )

            } else {

                item.optString(
                    "chType"
                )
            }

        return when (type) {

            "link",
            "web" -> false

            else -> true
        }
    }

    // =========================================================
    // VK SOURCE FIX
    // =========================================================

    private fun String.vkSourceFix(): String {

        return if (
            startsWith(
                "act"
            )
        ) {

            "https://vk.com/al_video.php?$this"

        } else {

            this
        }
    }

    // =========================================================
    // CH CONTENT
    // =========================================================
    //
    // ChContent ayrı dosyada:
    // InatBoxModels.kt
    //
    // Burada yeniden tanımlanmıyor.
    // =========================================================

    private fun parseToChContent(
        item: JSONObject
    ): ChContent {

        return ChContent(

            chName =
                item.getString(
                    "chName"
                ),

            chUrl =
                item
                    .getString(
                        "chUrl"
                    )
                    .vkSourceFix(),

            chImg =
                item.getString(
                    "chImg"
                ),

            chHeaders =
                item.optString(
                    "chHeaders",
                    "null"
                ),

            chReg =
                item.optString(
                    "chReg",
                    "null"
                ),

            chType =
                item.optString(
                    "chType",
                    ""
                )
        )
    }

    // =========================================================
    // LOAD CONTENT LINKS
    // =========================================================

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val chType =
            chContent.chType

        var contentToProcess =
            chContent

        // =====================================================
        // SPECIAL SPORTS SOURCE
        // =====================================================

        if (
            chType ==
            "tekli_regex_lb_sh_3"
        ) {

            val name =
                chContent.chName

            val url =
                chContent.chUrl

            val posterUrl =
                chContent.chImg

            val headers =
                chContent.chHeaders

            val reg =
                chContent.chReg

            val type =
                chContent.chType

            val jsonResponse =
                runCatching {

                    makeInatRequest(
                        url
                    )

                }.getOrNull()
                    ?: getJsonFromEncryptedInatResponse(
                        app
                            .get(url)
                            .text
                    )
                    ?: return

            val firstItem =
                JSONObject(
                    jsonResponse
                )

            firstItem.put(
                "chHeaders",
                headers
            )

            firstItem.put(
                "chReg",
                reg
            )

            firstItem.put(
                "chName",
                name
            )

            firstItem.put(
                "chImg",
                posterUrl
            )

            firstItem.put(
                "chType",
                type
            )

            contentToProcess =
                parseToChContent(
                    firstItem
                )
        }

        val sourceUrl =
            contentToProcess.chUrl

        // =====================================================
        // SOURCE HEADERS
        // =====================================================

        val headers =
            mutableMapOf<String, String>()

        try {

            val chHeaders =
                contentToProcess.chHeaders

            val chReg =
                contentToProcess.chReg

            if (
                chHeaders != "null" &&
                chHeaders.isNotBlank()
            ) {

                val jsonHeaders =
                    JSONArray(
                        chHeaders
                    )
                        .getJSONObject(
                            0
                        )

                for (
                    entry in
                    jsonHeaders.keys()
                ) {

                    headers[entry] =
                        jsonHeaders[entry]
                            .toString()
                }
            }

            if (
                chReg != "null" &&
                chReg.isNotBlank()
            ) {

                val jsonReg =
                    JSONArray(
                        chReg
                    )
                        .getJSONObject(
                            0
                        )

                val cookie =
                    jsonReg.optString(
                        "playSH2"
                    )

                if (
                    cookie.isNotBlank()
                ) {

                    headers["Cookie"] =
                        cookie
                }
            }

        } catch (e: Exception) {

            logError(
                "HEADER PARSE ERROR",
                e
            )
        }

        // =====================================================
        // EXTRACTOR
        // =====================================================

        log(
            "SOURCE URL -> $sourceUrl"
        )

        val extractorFound =
            try {

                loadExtractor(
                    sourceUrl,
                    subtitleCallback,
                    callback
                )

            } catch (e: Exception) {

                logError(
                    "EXTRACTOR ERROR",
                    e
                )

                false
            }

        // =====================================================
        // DIRECT FALLBACK
        // =====================================================

        if (
            !extractorFound
        ) {

            val extractorType =
                when {

                    sourceUrl.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) -> {

                        ExtractorLinkType.M3U8
                    }

                    sourceUrl.contains(
                        ".mpd",
                        ignoreCase = true
                    ) -> {

                        ExtractorLinkType.DASH
                    }

                    else -> {

                        ExtractorLinkType.VIDEO
                    }
                }

            log(
                "DIRECT FALLBACK -> $sourceUrl"
            )

            callback.invoke(
                newExtractorLink(
                    source =
                        this.name,

                    name =
                        contentToProcess.chName,

                    url =
                        sourceUrl,

                    type =
                        extractorType
                ) {

                    this.headers =
                        headers

                    this.quality =
                        Qualities.Unknown.value
                }
            )
        }
    }

    // =========================================================
    // INAT REQUEST
    // =========================================================

    private suspend fun makeInatRequest(
        url: String
    ): String? {

        return try {

            val hostName =
                try {

                    URI(
                        url
                    ).host
                        ?: throw IllegalArgumentException(
                            "Invalid URL: $url"
                        )

                } catch (e: Exception) {

                    logError(
                        "FAILED TO EXTRACT HOSTNAME: $url",
                        e
                    )

                    return null
                }

            val headers =
                mapOf(

                    "Cache-Control" to
                        "no-cache",

                    "Content-Length" to
                        "37",

                    "Content-Type" to
                        "application/x-www-form-urlencoded; charset=UTF-8",

                    "Host" to
                        hostName,

                    "Referer" to
                        "https://speedrestapi.com/",

                    "X-Requested-With" to
                        "com.bp.box"
                )

            val requestBody =
                "1=$aesKey&0=$aesKey"

            val interceptor =
                Interceptor { chain ->

                    val request =
                        chain.request()

                    val newRequest =
                        request
                            .newBuilder()
                            .header(
                                "User-Agent",
                                "speedrestapi"
                            )
                            .build()

                    chain.proceed(
                        newRequest
                    )
                }

            val response =
                app.post(
                    url =
                        url,

                    headers =
                        headers,

                    requestBody =
                        requestBody.toRequestBody(
                            contentType =
                                "application/x-www-form-urlencoded; charset=UTF-8"
                                    .toMediaType()
                        ),

                    interceptor =
                        interceptor
                )

            if (
                response.isSuccessful
            ) {

                val encryptedResponse =
                    response.text

                log(
                    "ENCRYPTED RESPONSE LENGTH -> ${encryptedResponse.length}"
                )

                getJsonFromEncryptedInatResponse(
                    encryptedResponse
                )

            } else {

                logError(
                    "INAT REQUEST FAILED -> ${response.code}"
                )

                null
            }

        } catch (e: Exception) {

            logError(
                "MAKE INAT REQUEST ERROR",
                e
            )

            null
        }
    }

    // =========================================================
    // AES DECRYPT
    // =========================================================

    private fun getJsonFromEncryptedInatResponse(
        response: String
    ): String? {

        return try {

            val algorithm =
                "AES/CBC/PKCS5Padding"

            val keySpec =
                SecretKeySpec(
                    aesKey.toByteArray(),
                    "AES"
                )

            // =================================================
            // FIRST DECRYPTION
            // =================================================

            val cipher1 =
                Cipher.getInstance(
                    algorithm
                )

            cipher1.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                IvParameterSpec(
                    aesKey.toByteArray()
                )
            )

            val firstPart =
                response
                    .split(":")
                    .firstOrNull()
                    ?: return null

            val firstIterationData =
                cipher1.doFinal(
                    Base64.decode(
                        firstPart,
                        Base64.DEFAULT
                    )
                )

            // =================================================
            // SECOND DECRYPTION
            // =================================================

            val cipher2 =
                Cipher.getInstance(
                    algorithm
                )

            cipher2.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                IvParameterSpec(
                    aesKey.toByteArray()
                )
            )

            val secondInput =
                String(
                    firstIterationData
                )
                    .split(":")
                    .firstOrNull()
                    ?: return null

            val secondIterationData =
                cipher2.doFinal(
                    Base64.decode(
                        secondInput,
                        Base64.DEFAULT
                    )
                )

            // =================================================
            // JSON
            // =================================================

            String(
                secondIterationData
            )

        } catch (e: Exception) {

            logError(
                "DECRYPTION FAILED",
                e
            )

            null
        }
    }

    // =========================================================
    // SEARCH RESPONSE PARSER
    // =========================================================

    private fun getSearchResponseList(
        jsonResponse: String
    ): List<SearchResponse> {

        val searchResults =
            mutableListOf<SearchResponse>()

        try {

            val jsonArray =
                JSONArray(
                    jsonResponse
                )

            for (
                i in 0 until jsonArray.length()
            ) {

                val item =
                    jsonArray.getJSONObject(
                        i
                    )

                if (
                    !inatContentAllowed(
                        item
                    )
                ) {
                    continue
                }

                // =================================================
                // SERIES / MOVIES
                // =================================================

                if (
                    item.has(
                        "diziType"
                    )
                ) {

                    val name =
                        item.getString(
                            "diziName"
                        )

                    val type =
                        item.getString(
                            "diziType"
                        )

                    val posterUrl =
                        item.getString(
                            "diziImg"
                        )

                    when (type) {

                        "dizi",
                        "dizi_mode" -> {

                            val searchResponse =
                                newTvSeriesSearchResponse(
                                    name,
                                    item.toString()
                                ) {

                                    this.posterUrl =
                                        posterUrl
                                }

                            searchResults.add(
                                searchResponse
                            )
                        }

                        "film",
                        "film_mode" -> {

                            val searchResponse =
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {

                                    this.posterUrl =
                                        posterUrl
                                }

                            searchResults.add(
                                searchResponse
                            )
                        }
                    }

                // =================================================
                // CHANNELS
                // =================================================

                } else if (
                    item.has("chName") &&
                    item.has("chUrl") &&
                    item.has("chImg")
                ) {

                    val name =
                        item.getString(
                            "chName"
                        )

                    val posterUrl =
                        item.getString(
                            "chImg"
                        )

                    val chType =
                        item.getString(
                            "chType"
                        )

                    when (chType) {

                        "live_url",
                        "tekli_regex_lb_sh_3" -> {

                            val searchResponse =
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {

                                    this.posterUrl =
                                        posterUrl
                                }

                            searchResults.add(
                                searchResponse
                            )
                        }

                        else -> {

                            val searchResponse =
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {

                                    this.posterUrl =
                                        posterUrl
                                }

                            searchResults.add(
                                searchResponse
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {

            logError(
                "FAILED TO PARSE SEARCH JSON",
                e
            )
        }

        return searchResults
    }
}
