package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.Locale
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
    // AES
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
    // DEBUG
    // =========================================================

    private fun log(message: String) {
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

        log(
            "MAIN PAGE -> ${request.data}"
        )

        return try {

            val jsonResponse =
                makeInatRequest(
                    request.data
                )
                    ?: return newHomePageResponse(
                        request.name,
                        emptyList(),
                        hasNext = false
                    )

            val results =
                getSearchResponseList(
                    jsonResponse
                )

            for (result in results) {

                val url =
                    result.url

                if (
                    !urlToSearchResponse.containsKey(
                        url
                    )
                ) {

                    urlToSearchResponse[url] =
                        result
                }
            }

            log(
                "MAIN PAGE RESULT -> ${results.size}"
            )

            newHomePageResponse(
                request.name,
                results,
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

                    val jsonResponse =
                        makeInatRequest(
                            pageData.data
                        )
                            ?: continue

                    val results =
                        getSearchResponseList(
                            jsonResponse
                        )

                    for (result in results) {

                        val url =
                            result.url

                        if (
                            !urlToSearchResponse
                                .containsKey(url)
                        ) {

                            urlToSearchResponse[url] =
                                result
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

                matchingResults +=
                    searchResponse
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

                val array =
                    JSONArray(
                        data
                    )

                for (
                    i in 0 until array.length()
                ) {

                    val item =
                        array.getJSONObject(
                            i
                        )

                    val content =
                        parseToChContent(
                            item
                        )

                    loadChContentLinks(
                        content,
                        subtitleCallback,
                        callback
                    )
                }

            } else {

                val item =
                    JSONObject(
                        data
                    )

                val content =
                    parseToChContent(
                        item
                    )

                loadChContentLinks(
                    content,
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

        return try {

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

                seasonDataList +=
                    SeasonData(
                        season = i + 1,
                        name = seasonName
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
                            "EPISODE JSON ERROR -> $seasonName",
                            e
                        )

                        continue
                    }

                for (
                    j in 0 until episodeArray.length()
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
                    episodes.mapValues {
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
                "TV SERIES LOAD ERROR",
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
                item.has("diziType")
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
                "MOVIE LOAD ERROR",
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

            val content =
                parseToChContent(
                    item
                )

            newLiveStreamLoadResponse(
                content.chName,
                item.toString(),
                item.toString()
            ) {

                this.posterUrl =
                    content.chImg
            }

        } catch (e: Exception) {

            logError(
                "LIVE LOAD ERROR",
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

            val content =
                parseToChContent(
                    item
                )

            newLiveStreamLoadResponse(
                name,
                item.toString(),
                item.toString()
            ) {

                this.posterUrl =
                    content.chImg
            }

        } catch (e: Exception) {

            logError(
                "SPORTS LIVE LOAD ERROR",
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
                item.has("diziType")
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
    // VK URL FIX
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

        var contentToProcess =
            chContent

        // =====================================================
        // SPECIAL SPORTS CONTENT
        // =====================================================

        if (
            chContent.chType ==
            "tekli_regex_lb_sh_3"
        ) {

            try {

                val jsonResponse =
                    runCatching {
                        makeInatRequest(
                            chContent.chUrl
                        )
                    }.getOrNull()
                        ?: getJsonFromEncryptedInatResponse(
                            app.get(
                                chContent.chUrl
                            ).text
                        )
                        ?: return

                val firstItem =
                    JSONObject(
                        jsonResponse
                    )

                firstItem.put(
                    "chHeaders",
                    chContent.chHeaders
                )

                firstItem.put(
                    "chReg",
                    chContent.chReg
                )

                firstItem.put(
                    "chName",
                    chContent.chName
                )

                firstItem.put(
                    "chImg",
                    chContent.chImg
                )

                firstItem.put(
                    "chType",
                    chContent.chType
                )

                contentToProcess =
                    parseToChContent(
                        firstItem
                    )

            } catch (e: Exception) {

                logError(
                    "SPORTS SOURCE ERROR",
                    e
                )

                return
            }
        }

        val sourceUrl =
            contentToProcess.chUrl

        // =====================================================
        // HEADERS
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
                    entry in jsonHeaders.keys()
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

            val type =
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

            callback(
                newExtractorLink(
                    source =
                        this.name,

                    name =
                        contentToProcess.chName,

                    url =
                        sourceUrl,

                    type =
                        type
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

                } catch (e: Exception) {

                    throw IllegalArgumentException(
                        "Invalid URL: $url",
                        e
                    )
                }

            if (
                hostName.isNullOrBlank()
            ) {

                throw IllegalArgumentException(
                    "Hostname not found: $url"
                )
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
                        chain
                            .request()

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
                    "INAT RESPONSE LENGTH -> ${encryptedResponse.length}"
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
                "MAKE INAT REQUEST ERROR -> $url",
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
            // FIRST LAYER
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

            val firstDecoded =
                cipher1.doFinal(
                    Base64.decode(
                        firstPart,
                        Base64.DEFAULT
                    )
                )

            // =================================================
            // SECOND LAYER
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
                    firstDecoded
                )
                    .split(":")
                    .firstOrNull()
                    ?: return null

            val secondDecoded =
                cipher2.doFinal(
                    Base64.decode(
                        secondInput,
                        Base64.DEFAULT
                    )
                )

            String(
                secondDecoded
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
    // SEARCH RESULT PARSER
    // =========================================================

    private fun getSearchResponseList(
        jsonResponse: String
    ): List<SearchResponse> {

        val results =
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
                // MOVIE / SERIES
                // =================================================

                if (
                    item.has(
                        "diziType"
                    )
                ) {

                    val itemName =
                        item.optString(
                            "diziName"
                        )

                    val type =
                        item.optString(
                            "diziType"
                        )

                    val poster =
                        item.optString(
                            "diziImg"
                        )

                    if (
                        itemName.isBlank()
                    ) {
                        continue
                    }

                    when (type) {

                        "dizi",
                        "dizi_mode" -> {

                            results +=
                                newTvSeriesSearchResponse(
                                    name =
                                        itemName,

                                    url =
                                        item.toString(),

                                    type =
                                        TvType.TvSeries
                                ) {

                                    this.posterUrl =
                                        poster
                                }
                        }

                        "film",
                        "film_mode" -> {

                            results +=
                                newMovieSearchResponse(
                                    name =
                                        itemName,

                                    url =
                                        item.toString(),

                                    type =
                                        TvType.Movie
                                ) {

                                    this.posterUrl =
                                        poster
                                }
                        }
                    }

                // =================================================
                // CHANNEL
                // =================================================

                } else if (
                    item.has("chName") &&
                    item.has("chUrl") &&
                    item.has("chImg")
                ) {

                    val itemName =
                        item.optString(
                            "chName"
                        )

                    val poster =
                        item.optString(
                            "chImg"
                        )

                    val chType =
                        item.optString(
                            "chType"
                        )

                    if (
                        itemName.isBlank()
                    ) {
                        continue
                    }

                    when (chType) {

                        "live_url",
                        "tekli_regex_lb_sh_3" -> {

                            results +=
                                newLiveSearchResponse(
                                    itemName,
                                    item.toString(),
                                    TvType.Live
                                ) {

                                    this.posterUrl =
                                        poster
                                }
                        }

                        else -> {

                            results +=
                                newMovieSearchResponse(
                                    itemName,
                                    item.toString(),
                                    TvType.Movie
                                ) {

                                    this.posterUrl =
                                        poster
                                }
                        }
                    }
                }
            }

        } catch (e: Exception) {

            logError(
                "SEARCH PARSER ERROR",
                e
            )
        }

        return results
    }
}


// =============================================================
// CH CONTENT MODEL
// =============================================================

private data class ChContent(

    val chName: String,

    val chUrl: String,

    val chImg: String,

    val chHeaders: String,

    val chReg: String,

    val chType: String
)


// =============================================================
// DZEN STREAM MODEL
// =============================================================

private data class InatDzenStream(
    val url: String?,
    val type: String?
)


// =============================================================
// CDN JWPLAYER
// =============================================================

class CDNJWPlayer : ExtractorApi() {

    override val name =
        "CDN JWPlayer"

    override val mainUrl =
        "https://cdn.jwplayer.com"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        callback(
            newExtractorLink(
                source =
                    name,

                name =
                    name,

                url =
                    url,

                type =
                    ExtractorLinkType.M3U8
            ) {

                quality =
                    Qualities.Unknown.value
            }
        )
    }
}


// =============================================================
// YANDEX DISK
// =============================================================

class DiskYandexComTr : ExtractorApi() {

    override val name =
        "DiskYandexComTr"

    override val mainUrl =
        "https://disk.yandex.com.tr"

    override val requiresReferer =
        false

    private val masterPlaylistRegex =
        Regex(
            """https?://[^\s"]*?master-playlist\.m3u8"""
        )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response =
            app.get(
                url =
                    url,

                referer =
                    "https://disk.yandex.com.tr/",

                headers =
                    mapOf(
                        "X-Requested-With" to
                            "XMLHttpRequest"
                    )
            )

        if (
            !response.isSuccessful
        ) {

            throw Exception(
                "Failed to fetch URL: ${response.code}"
            )
        }

        val match =
            masterPlaylistRegex
                .find(
                    response.text
                )

        if (
            match == null
        ) {

            throw Exception(
                "No master-playlist.m3u8 URL found"
            )
        }

        callback(
            newExtractorLink(
                source =
                    "Yandex Disk",

                name =
                    "Yandex Disk",

                url =
                    match.value,

                type =
                    ExtractorLinkType.M3U8
            ) {

                quality =
                    Qualities.Unknown.value
            }
        )
    }
}


// =============================================================
// DZEN.RU
// =============================================================

class DzenRu : ExtractorApi() {

    override val name =
        "DzenRu"

    override val mainUrl =
        "https://dzen.ru/"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val response =
                app.get(
                    url =
                        url,

                    headers =
                        mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.38 Mobile Safari/537.36",

                            "X-Requested-With" to
                                "XMLHttpRequest"
                        ),

                    referer =
                        mainUrl
                )

            val script =
                response
                    .document
                    .select("script")
                    .firstOrNull {
                        it.data().contains(
                            "\"streams\""
                        )
                    }
                    ?.data()
                    ?: return

            val streamsText =
                script
                    .substringAfter(
                        "\"streams\":",
                        ""
                    )
                    .substringBefore(
                        "],",
                        ""
                    ) + "]"

            if (
                streamsText == "]"
            ) {
                return
            }

            val streamsArray =
                JSONArray(
                    streamsText
                )

            for (
                i in 0 until streamsArray.length()
            ) {

                val stream =
                    streamsArray.getJSONObject(
                        i
                    )

                val streamUrl =
                    stream.optString(
                        "url"
                    )

                val type =
                    stream.optString(
                        "type"
                    )

                if (
                    streamUrl.isBlank()
                ) {
                    continue
                }

                val quality =
                    when {

                        type.contains(
                            "fullhd",
                            ignoreCase = true
                        ) ->
                            Qualities.P1080.value

                        type.contains(
                            "high",
                            ignoreCase = true
                        ) ->
                            Qualities.P720.value

                        type.contains(
                            "medium",
                            ignoreCase = true
                        ) ->
                            Qualities.P480.value

                        type.contains(
                            "low",
                            ignoreCase = true
                        ) ->
                            Qualities.P360.value

                        type.contains(
                            "lowest",
                            ignoreCase = true
                        ) ->
                            Qualities.P240.value

                        type.contains(
                            "tiny",
                            ignoreCase = true
                        ) ->
                            Qualities.P144.value

                        else ->
                            Qualities.Unknown.value
                    }

                val typeOfLink =
                    when (
                        type.lowercase(
                            Locale.ROOT
                        )
                    ) {

                        "hls" ->
                            ExtractorLinkType.M3U8

                        "dash" ->
                            ExtractorLinkType.DASH

                        else ->
                            ExtractorLinkType.VIDEO
                    }

                callback(
                    newExtractorLink(
                        source =
                            "$name - ${Qualities.getStringByInt(quality)}",

                        name =
                            "$name - ${Qualities.getStringByInt(quality)}",

                        url =
                            streamUrl,

                        type =
                            typeOfLink
                    ) {

                        this.referer =
                            ""

                        this.quality =
                            quality
                    }
                )
            }

        } catch (e: Exception) {

            Log.e(
                "DzenRu",
                "Extractor error",
                e
            )
        }
    }
}


// =============================================================
// VK
// =============================================================

class Vk : ExtractorApi() {

    override val name =
        "Vk"

    override val mainUrl =
        "https://vk.com/"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response =
            app.get(
                url =
                    url,

                headers =
                    mapOf(
                        "X-Requested-With" to
                            "XMLHttpRequest"
                    ),

                referer =
                    mainUrl
            )

        val m3u8Regex =
            Regex(
                """"([^"]*m3u8[^"]*)""""
            )

        val m3u8 =
            m3u8Regex
                .find(
                    response.text
                )
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    "\\/",
                    "/"
                )

        if (
            m3u8.isNullOrBlank()
        ) {
            return
        }

        callback(
            newExtractorLink(
                source =
                    name,

                name =
                    name,

                url =
                    m3u8,

                type =
                    ExtractorLinkType.M3U8
            ) {

                headers =
                    mapOf(
                        "Referer" to
                            mainUrl
                    )

                quality =
                    Qualities.Unknown.value
            }
        )
    }
}
