package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InatBox : MainAPI() {

    override var name = "InatBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L
    override val getMainPageTimeoutMs = 30_000L

    private val tabCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
    private val urlToSearchResponse = ConcurrentHashMap<String, SearchResponse>()

    companion object {
        private const val CACHE_TTL_MS = 60 * 1000L
        private const val HMK = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"
        private const val LEGACY_AES_KEY = "ywevqtjrurkwtqgz"
        private val SECURE_RANDOM = SecureRandom()
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun generateRandomKey(length: Int = 16): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val sb = StringBuilder(length)

            repeat(length) {
                sb.append(chars[SECURE_RANDOM.nextInt(chars.length)])
            }

            return sb.toString()
        }

        fun bytesToHex(bytes: ByteArray): String {
            val hex = CharArray(bytes.size * 2)

            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX_CHARS[b ushr 4]
                hex[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }

            return String(hex)
        }

        fun sha256Hex(data: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return bytesToHex(md.digest(data.toByteArray(StandardCharsets.UTF_8)))
        }

        fun hmacSha256(data: String, key: String): String {
            val mac = Mac.getInstance("HmacSHA256")

            mac.init(
                SecretKeySpec(
                    key.toByteArray(StandardCharsets.UTF_8),
                    "HmacSHA256"
                )
            )

            return bytesToHex(
                mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            )
        }

        fun signRequest(method: String, url: String, body: String): Map<String, String> {
            val uriPath = try {
                URI(url).rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            } catch (_: Exception) {
                "/"
            }

            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonceBytes = ByteArray(16)
            SECURE_RANDOM.nextBytes(nonceBytes)

            val nonce = bytesToHex(nonceBytes)
            val bodyHash = sha256Hex(body)

            val signString =
                "${method.uppercase(Locale.ROOT)}\n$uriPath\n$timestamp\n$nonce\n$bodyHash"

            val signature = hmacSha256(signString, HMK)

            return mapOf(
                "X-Ts" to timestamp,
                "X-Nc" to nonce,
                "X-Sg" to signature
            )
        }

        fun decryptAesLayer(encryptedTextWithIv: String, key: String): String? {
            return runCatching {
                val parts = encryptedTextWithIv.split(":")

                if (parts.size < 2) {
                    return null
                }

                val cipherBytes = Base64.decode(parts[0].trim(), Base64.DEFAULT)
                val ivBytes = Base64.decode(parts[1].trim(), Base64.DEFAULT)

                val keyStr = when {
                    key.length < 16 -> key.padEnd(16, '0')
                    key.length > 16 -> key.substring(0, 16)
                    else -> key
                }

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(
                        keyStr.toByteArray(StandardCharsets.ISO_8859_1),
                        "AES"
                    ),
                    IvParameterSpec(ivBytes)
                )

                String(
                    cipher.doFinal(cipherBytes),
                    StandardCharsets.ISO_8859_1
                )
            }.getOrNull()
        }

        fun decryptDoubleAes(encryptedResponse: String, key: String): String? {
            return runCatching {
                val layer1 = decryptAesLayer(encryptedResponse, key) ?: return null
                val layer2 = decryptAesLayer(layer1, key) ?: return null

                val text = String(
                    layer2.toByteArray(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8
                ).trim()

                val lastBracket = text.lastIndexOf(']')
                val lastBrace = text.lastIndexOf('}')
                val endIdx = maxOf(lastBracket, lastBrace)

                if (endIdx != -1 && endIdx < text.length - 1) {
                    text.substring(0, endIdx + 1)
                } else {
                    text
                }
            }.getOrNull()
        }

        fun verifyAndStripHmacSuffix(text: String): String? {
            if (text.length <= 64) return null

            val payload = text.substring(0, text.length - 64)
            val signature = text.substring(text.length - 64)
            val expected = hmacSha256(payload, HMK)

            return if (signature.equals(expected, ignoreCase = true)) {
                payload
            } else {
                payload
            }
        }
    }

    override val mainPage = mainPageOf(
        "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php" to "Spor",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php" to "Ulusal Kanallar",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/haber.php" to "Haber Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/cocuk.php" to "Çocuk Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/sinema.php" to "Sinema Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/belgesel.php" to "Belgesel Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ex/index.php" to "Exxen",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php" to "Netflix",
        "https://sprboxs.bar/CDN/001/SPR/v2/ccc/a/index.php" to "TOD",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/hb/index.php" to "BluTV & HBO",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/dsny/index.php" to "Disney+",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ga/index.php" to "Gain",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/amz/index.php" to "Amazon Prime",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tbi/index.php" to "Tabii",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php" to "Yerli Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php" to "Yabancı Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php" to "Yerli Filmler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/mubi.php" to "Mubi",
        "https://4k.filmizleeeee.cfd/4k/01/public/catalog-exo.php" to "4K Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val now = System.currentTimeMillis()
        val cached = tabCache[url]

        val allResults = if (
            cached != null &&
            now - cached.first < CACHE_TTL_MS &&
            cached.second.isNotEmpty()
        ) {
            cached.second
        } else {
            val jsonResponse = makeInatPostRequest(url)

            if (jsonResponse.isNullOrBlank()) {
                cached?.second ?: emptyList()
            } else {
                val results = getSearchResponseList(jsonResponse)

                if (results.isNotEmpty()) {
                    tabCache[url] = Pair(System.currentTimeMillis(), results)
                    results.forEach {
                        urlToSearchResponse.putIfAbsent(it.url, it)
                    }
                }

                results
            }
        }

        if (allResults.isEmpty()) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val isLiveCategory = request.name in listOf(
            "Spor",
            "Ulusal Kanallar",
            "Haber Kanalları",
            "Çocuk Kanalları",
            "Sinema Kanalları",
            "Belgesel Kanalları"
        )

        if (allResults.size <= 150) {
            return if (page == 1) {
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            allResults,
                            isHorizontalImages = isLiveCategory
                        )
                    ),
                    hasNext = false
                )
            } else {
                newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }
        }

        val pageSize = 50
        val startIndex = (page - 1) * pageSize

        if (startIndex >= allResults.size) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val endIndex = minOf(startIndex + pageSize, allResults.size)
        val pagedList = allResults.subList(startIndex, endIndex)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    pagedList,
                    isHorizontalImages = isLiveCategory
                )
            ),
            hasNext = endIndex < allResults.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(Locale.forLanguageTag("tr"))

        if (q.isBlank()) {
            return emptyList()
        }

        if (tabCache.isEmpty()) {
            val keyUrls = listOf(
                "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php",
                "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php",
                "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php",
                "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php",
                "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php",
                "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php"
            )

            for (url in keyUrls) {
                try {
                    val res = makeInatPostRequest(url) ?: continue
                    val items = getSearchResponseList(res)

                    if (items.isNotEmpty()) {
                        tabCache[url] = Pair(System.currentTimeMillis(), items)

                        items.forEach {
                            urlToSearchResponse.putIfAbsent(it.url, it)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        "InatBox",
                        "Search preload error: ${e.message}"
                    )
                }
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(
                Regex.escape(query),
                RegexOption.IGNORE_CASE
            )
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (
                regex.containsMatchIn(searchResponse.name) ||
                searchResponse.name
                    .lowercase(Locale.forLanguageTag("tr"))
                    .contains(q)
            ) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val item = JSONObject(url)

            if (!inatContentAllowed(item)) {
                return null
            }

            if (item.has("diziType")) {
                val type = item.optString("diziType")

                when {
                    type.contains("dizi", ignoreCase = true) ->
                        parseTvSeriesResponse(item)

                    type.contains("film", ignoreCase = true) ->
                        parseMovieResponse(item)

                    else -> null
                }
            } else if (
                item.has("chName") &&
                item.has("chUrl") &&
                item.has("chImg")
            ) {
                val chType = item.optString("chType")
                val chUrl = item.optString("chUrl")

                when {
                    chType.contains("SsprDrm", ignoreCase = true) ->
                        parseSSportResponse(item)

                    chType.contains("live", ignoreCase = true) ||
                        chType.contains("cable", ignoreCase = true) ->
                        parseLiveStreamLoadResponse(item)

                    chType.contains("tekli", ignoreCase = true) &&
                        !chUrl.contains("filmizleeeee", ignoreCase = true) ->
                        parseLiveSportsStreamLoadResponse(item)

                    else ->
                        parseMovieResponse(item)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Load error: ${e.message}",
                e
            )
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var loaded = false
            val trimmed = data.trim()

            when {
                trimmed.startsWith("[") -> {
                    val jsonArray = JSONArray(trimmed)

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.optJSONObject(i) ?: continue

                        if (
                            loadChContentLinks(
                                parseToChContent(item),
                                subtitleCallback,
                                callback
                            )
                        ) {
                            loaded = true
                        }
                    }
                }

                trimmed.startsWith("{") -> {
                    val item = JSONObject(trimmed)

                    loaded = loadChContentLinks(
                        parseToChContent(item),
                        subtitleCallback,
                        callback
                    )
                }

                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> {
                    loaded = emitDirectOrRawStream(
                        name = "InatBox",
                        url = trimmed,
                        headers = emptyMap(),
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }

            loaded
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Error on loadLinks: ${e::class.simpleName} - ${e.message}",
                e
            )
            false
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.optString("diziName")
        val url = item.optString("diziUrl")
        val plot = item.optString("diziDetay", "")

        if (name.isBlank() || url.isBlank()) {
            return null
        }

        val jsonResponse = makeInatPostRequest(url) ?: return null
        val jsonArray = runCatching {
            JSONArray(jsonResponse)
        }.getOrNull() ?: return null

        return try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.optString(
                    "diziName",
                    "Sezon ${i + 1}"
                )

                seasonDataList.add(
                    SeasonData(
                        season = i + 1,
                        name = seasonName
                    )
                )

                val seasonUrl = seasonItem.optString("diziUrl")

                if (seasonUrl.isBlank()) {
                    continue
                }

                val episodeResponse = makeInatPostRequest(seasonUrl) ?: continue
                val episodeArray = runCatching {
                    JSONArray(episodeResponse)
                }.getOrNull() ?: continue

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.optString(
                            "chName",
                            "Bölüm ${j + 1}"
                        )
                        val episodePoster = episodeItem.optString(
                            "chImg",
                            ""
                        )

                        episodes
                            .getOrPut(DubStatus.None) {
                                mutableListOf()
                            }
                            .add(
                                newEpisode(episodeItem.toString()) {
                                    this.name = episodeName
                                    this.posterUrl = episodePoster
                                    this.season = i + 1
                                    this.episode = j + 1
                                }
                            )
                    } catch (_: JSONException) {
                    }
                }
            }

            val posterUrl = if (jsonArray.length() > 0) {
                jsonArray
                    .getJSONObject(0)
                    .optString(
                        "diziImg",
                        item.optString("diziImg", "")
                    )
            } else {
                item.optString("diziImg", "")
            }

            newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes
                    .mapValues { it.value.toList() }
                    .toMutableMap()

                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV response: ${e.message}"
            )
            null
        }
    }

    private suspend fun parseSSportResponse(
        item: JSONObject
    ): LoadResponse? {
        return try {
            val name = item.optString(
                "chName",
                "S Sport Plus"
            )
            val posterUrl = item.optString(
                "chImg",
                ""
            )

            val rawResponse = app.get(
                "https://sprspr.help/CDN/SSP/bir-p-no-cron.php",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "https://google.com/"
                )
            ).text

            val jsonResponse = JSONObject(rawResponse)
            val categories = jsonResponse.optJSONArray("Categories")
                ?: return null

            val firstCategory = categories.optJSONObject(0)
                ?: return null

            val contents = firstCategory.optJSONArray("Contents")
                ?: return null

            val episodes = mutableListOf<Episode>()

            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                val epName = content.optString("Title", "")
                val description = content.optString("Description", "")
                val medias = content.optJSONArray("Medias")

                val mediaUrl = if (medias != null && medias.length() > 0) {
                    medias
                        .optJSONObject(0)
                        ?.optString("URL", "")
                        ?: ""
                } else {
                    ""
                }

                if (mediaUrl.isNotEmpty()) {
                    episodes.add(
                        newEpisode(mediaUrl) {
                            this.name = epName
                            this.description = description
                            this.episode = i + 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.TvSeries
            ) {
                this.episodes = mutableMapOf(
                    DubStatus.None to episodes
                )
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "S Sport error: ${e.message}"
            )
            null
        }
    }

    private suspend fun parseMovieResponse(
        item: JSONObject
    ): LoadResponse? {
        return try {
            if (item.has("diziType")) {
                val name = item.optString("diziName")
                val url = item.optString("diziUrl")
                val posterUrl = item.optString("diziImg", "")
                val plot = item.optString("diziDetay", "")

                if (name.isBlank() || url.isBlank()) {
                    return null
                }

                val jsonResponse = makeInatPostRequest(url)
                    ?: return null

                val jsonArray = JSONArray(jsonResponse)

                newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.optString("chName")

                if (name.isBlank()) {
                    return null
                }

                val posterUrl = item.optString("chImg", "")

                newMovieLoadResponse(
                    name,
                    item.toString(),
                    TvType.Movie,
                    item.toString()
                ) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Movie error: ${e.message}"
            )
            null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)

            newLiveStreamLoadResponse(
                chContent.chName,
                item.toString(),
                item.toString()
            ) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Live sports error: ${e.message}"
            )
            null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)

            newLiveStreamLoadResponse(
                chContent.chName,
                item.toString(),
                item.toString()
            ) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Live stream error: ${e.message}"
            )
            null
        }
    }

    private fun inatContentAllowed(
        item: JSONObject
    ): Boolean {
        val type = if (item.has("diziType")) {
            item.optString("diziType")
        } else {
            item.optString("chType")
        }

        return type !in setOf(
            "link",
            "web",
            "link_mode",
            "web_mode",
            "destek",
            "destek_mode"
        )
    }

    private fun String.vkSourceFix(): String {
        return if (startsWith("act")) {
            "https://vk.com/al_video.php?$this"
        } else {
            this
        }
    }

    private fun parseToChContent(
        item: JSONObject
    ): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.opt("chHeaders")?.toString() ?: "null",
            chReg = item.opt("chReg")?.toString() ?: "null",
            chType = item.optString("chType")
        )
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var sourceUrl = chContent.chUrl.trim()

        if (sourceUrl.isBlank()) {
            return false
        }

        if (sourceUrl.startsWith("//")) {
            sourceUrl = "https:$sourceUrl"
        }

        if (sourceUrl.startsWith("act", ignoreCase = true)) {
            sourceUrl = sourceUrl.vkSourceFix()
        }

        val headers = mutableMapOf<String, String>()

        parseFirstJsonObject(chContent.chHeaders)?.let { jsonHeaders ->
            val keys = jsonHeaders.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonHeaders.optString(key)

                if (value.isBlank()) {
                    continue
                }

                val normalizedKey = when (key.lowercase(Locale.ROOT)) {
                    "useragent", "user_agent" -> "User-Agent"
                    "xrequestedwith", "x_requested_with" -> "X-Requested-With"
                    "referrer" -> "Referer"
                    else -> key
                }

                headers[normalizedKey] = value
            }
        }

        parseFirstJsonObject(chContent.chReg)?.let { jsonReg ->
            jsonReg
                .optString("playSH2")
                .takeIf { it.isNotBlank() }
                ?.let {
                    headers["Cookie"] = it
                }
        }

        if (headers["User-Agent"].isNullOrBlank()) {
            headers["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) " +
                    "Gecko/20100101 Firefox/134.0"
        }

        if (
            chContent.chType.contains("tekli", ignoreCase = true) &&
            !isDirectStream(sourceUrl)
        ) {
            val resolved = resolveTekliRegexStream(
                sourceUrl,
                headers,
                parseFirstJsonObject(chContent.chReg)?.optString("Regex1"),
                parseFirstJsonObject(chContent.chReg)?.optString("Regex2"),
                parseFirstJsonObject(chContent.chReg)?.optString("Regex2p")
            )

            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved.trim()

                if (sourceUrl.startsWith("//")) {
                    sourceUrl = "https:$sourceUrl"
                }
            }
        }

        if (
            sourceUrl.contains("filmizleeeee", ignoreCase = true) &&
            !isDirectStream(sourceUrl)
        ) {
            val resolved = resolveFilmizleStream(
                sourceUrl,
                headers
            )

            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved.trim()

                if (sourceUrl.startsWith("//")) {
                    sourceUrl = "https:$sourceUrl"
                }
            }
        }

        return emitDirectOrRawStream(
            name = chContent.chName.ifBlank { "InatBox" },
            url = sourceUrl,
            headers = headers,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private fun parseFirstJsonObject(
        raw: String
    ): JSONObject? {
        val text = raw.trim()

        if (
            text.isBlank() ||
            text.equals("null", ignoreCase = true)
        ) {
            return null
        }

        return runCatching {
            when {
                text.startsWith("[") ->
                    JSONArray(text).optJSONObject(0)

                text.startsWith("{") ->
                    JSONObject(text)

                else ->
                    null
            }
        }.getOrNull()
    }

    private fun buildDefaultReferer(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)

            if (
                !uri.scheme.isNullOrBlank() &&
                !uri.host.isNullOrBlank()
            ) {
                "${uri.scheme}://${uri.host}/"
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private suspend fun emitDirectOrRawStream(
        name: String,
        url: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.isBlank()) {
            return false
        }

        val cleanUrl = url.trim()

        if (isDirectStream(cleanUrl)) {
            val linkType = when {
                cleanUrl.contains(".m3u8", ignoreCase = true) ||
                    cleanUrl.contains("/hls/", ignoreCase = true) ->
                    ExtractorLinkType.M3U8

                cleanUrl.contains(".mpd", ignoreCase = true) ||
                    cleanUrl.contains("/dash/", ignoreCase = true) ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }

            val workingHeaders = headers.toMutableMap()

            if (workingHeaders["Referer"].isNullOrBlank()) {
                buildDefaultReferer(cleanUrl)
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        workingHeaders["Referer"] = it
                    }
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = name,
                    url = cleanUrl,
                    type = linkType
                ) {
                    this.referer = workingHeaders["Referer"].orEmpty()
                    this.headers = workingHeaders
                    this.quality = guessQuality(cleanUrl)
                }
            )

            Log.d(
                "InatBox",
                "Direct stream emitted without probe: $cleanUrl"
            )

            return true
        }

        val referer = headers["Referer"].orEmpty()

        val extractorFound = runCatching {
            loadExtractor(
                cleanUrl,
                referer,
                subtitleCallback
            ) {
                callback.invoke(it)
            }
        }.getOrDefault(false)

        if (extractorFound) {
            return true
        }

        Log.w(
            "InatBox",
            "No extractor/direct stream found: $cleanUrl"
        )

        return false
    }

    private fun guessQuality(
        url: String
    ): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            Regex("(?:^|[^0-9])1080(?:p)?(?:[^0-9]|$)")
                .containsMatchIn(lower) ->
                Qualities.P1080.value

            Regex("(?:^|[^0-9])720(?:p)?(?:[^0-9]|$)")
                .containsMatchIn(lower) ->
                Qualities.P720.value

            Regex("(?:^|[^0-9])480(?:p)?(?:[^0-9]|$)")
                .containsMatchIn(lower) ->
                Qualities.P480.value

            Regex("(?:^|[^0-9])360(?:p)?(?:[^0-9]|$)")
                .containsMatchIn(lower) ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private suspend fun resolveTekliRegexStream(
        url: String,
        headers: Map<String, String>,
        regex1: String?,
        regex2: String?,
        regex2p: String?
    ): String? {

        // 1) Güncel API akışı: imzalı POST
        runCatching {
            val modernJson = makeInatPostRequest(url)

            extractChUrl(modernJson)?.let {
                Log.d(
                    "InatBox",
                    "tekli_regex resolved via signed POST"
                )
                return it
            }
        }

        // 2) Eski InatBox akışı: sabit AES anahtarlı POST
        runCatching {
            val legacyJson = makeLegacyInatRequest(url)

            extractChUrl(legacyJson)?.let {
                Log.d(
                    "InatBox",
                    "tekli_regex resolved via legacy POST"
                )
                return it
            }
        }

        // 3) Bazı kaynaklar doğrudan GET ile şifreli gövde döndürüyor
        runCatching {
            val response = app.get(
                url,
                headers = headers
            ).text

            val decrypted = decryptLegacyInatResponse(response)

            extractChUrl(decrypted)?.let {
                Log.d(
                    "InatBox",
                    "tekli_regex resolved via legacy GET decrypt"
                )
                return it
            }
        }

        // 4) Regex1/Regex2 tabanlı özel çözüm
        val r1 = regex1?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            val reqHeaders = headers.toMutableMap()

            reqHeaders.putAll(
                signRequest(
                    "GET",
                    url,
                    ""
                )
            )

            reqHeaders["Cache-Control"] = "no-cache"

            val response = app.get(
                url,
                headers = reqHeaders
            )

            if (!response.isSuccessful) {
                return@runCatching null
            }

            val raw = response.text.trim()

            val p1 = decryptAesLayer(
                raw,
                r1
            ) ?: return@runCatching null

            val keys = buildList {
                regex2
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)

                regex2p
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)

                if (isEmpty()) {
                    add(r1)
                }
            }.distinct()

            for (key in keys) {
                val p2 = decryptAesLayer(
                    p1,
                    key
                ) ?: continue

                val stripped =
                    verifyAndStripHmacSuffix(p2) ?: p2

                extractChUrl(stripped)?.let {
                    return@runCatching it
                }
            }

            null
        }.getOrNull()
    }

    private fun extractChUrl(
        rawJson: String?
    ): String? {
        if (rawJson.isNullOrBlank()) {
            return null
        }

        return runCatching {
            val trimmed = rawJson.trim()

            when {
                trimmed.startsWith("{") ->
                    JSONObject(trimmed)
                        .optString("chUrl", null)

                trimmed.startsWith("[") ->
                    JSONArray(trimmed)
                        .optJSONObject(0)
                        ?.optString("chUrl", null)

                else ->
                    null
            }?.takeIf {
                !it.isNullOrBlank()
            }
        }.getOrNull()
    }

    private fun isDirectStream(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv") ||
            lower.contains("/master.m3u8") ||
            lower.contains("/index.m3u8") ||
            lower.contains("/playlist.m3u8") ||
            lower.contains("/manifest.mpd") ||
            lower.contains("/hls/") ||
            lower.contains("/dash/")
    }

    private suspend fun resolveFilmizleStream(
        url: String,
        headers: Map<String, String>
    ): String? {
        var response = try {
            app.get(
                url,
                headers = headers,
                referer = headers["Referer"]
            ).text
        } catch (_: Exception) {
            return null
        }

        repeat(3) {
            val separator = response.lastIndexOf(':')

            if (separator < 1) {
                return@repeat
            }

            val encrypted = response
                .substring(0, separator)
                .trim()

            val encodedKey = response
                .substring(separator + 1)
                .trim()

            val key = try {
                String(
                    Base64.decode(
                        encodedKey,
                        Base64.DEFAULT
                    ),
                    StandardCharsets.UTF_8
                )
            } catch (_: Exception) {
                return@repeat
            }

            response = decryptAesLayer(
                encrypted,
                key
            ) ?: return@repeat

            extractChUrl(response)?.let {
                return it
            }
        }

        return null
    }

    private suspend fun makeLegacyInatRequest(
        url: String
    ): String? {
        return runCatching {
            val hostName = URI(url).host
                ?: return@runCatching null

            val requestBody = "1=$LEGACY_AES_KEY&0=$LEGACY_AES_KEY"

            val headers = mapOf(
                "Cache-Control" to "no-cache",
                "Content-Length" to requestBody.length.toString(),
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Host" to hostName,
                "Referer" to "https://speedrestapi.com/",
                "User-Agent" to "speedrestapi",
                "X-Requested-With" to "com.bp.box"
            )

            val response = app.post(
                url = url,
                headers = headers,
                requestBody = requestBody.toRequestBody(
                    "application/x-www-form-urlencoded; charset=UTF-8"
                        .toMediaType()
                )
            )

            if (!response.isSuccessful) {
                return@runCatching null
            }

            decryptLegacyInatResponse(response.text)
        }.getOrNull()
    }

    private fun decryptLegacyInatResponse(
        response: String
    ): String? {
        return runCatching {
            val firstPart = response.substringBefore(":")

            val keySpec = SecretKeySpec(
                LEGACY_AES_KEY.toByteArray(StandardCharsets.UTF_8),
                "AES"
            )

            val iv = IvParameterSpec(
                LEGACY_AES_KEY.toByteArray(StandardCharsets.UTF_8)
            )

            val cipher1 = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher1.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                iv
            )

            val layer1 = cipher1.doFinal(
                Base64.decode(
                    firstPart,
                    Base64.DEFAULT
                )
            )

            val nested = String(
                layer1,
                StandardCharsets.UTF_8
            ).substringBefore(":")

            val cipher2 = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher2.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                iv
            )

            String(
                cipher2.doFinal(
                    Base64.decode(
                        nested,
                        Base64.DEFAULT
                    )
                ),
                StandardCharsets.UTF_8
            ).trim()
        }.getOrNull()
    }

    private suspend fun makeInatPostRequest(
        url: String,
        retryCount: Int = 2
    ): String? {
        val hostName = try {
            URI(url).host ?: "speedrestapi.com"
        } catch (_: Exception) {
            "speedrestapi.com"
        }

        repeat(retryCount) { attempt ->
            try {
                val dynamicKey = generateRandomKey(16)
                val requestBody = "1=$dynamicKey&0=$dynamicKey"

                val headers = mutableMapOf(
                    "User-Agent" to "speedrestapi",
                    "X-Requested-With" to "com.bp.box",
                    "Referer" to "https://speedrestapi.com/",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Cache-Control" to "no-cache",
                    "Host" to hostName
                )

                headers.putAll(
                    signRequest(
                        "POST",
                        url,
                        requestBody
                    )
                )

                val response = app.post(
                    url = url,
                    headers = headers,
                    requestBody = requestBody.toRequestBody(
                        "application/x-www-form-urlencoded; charset=UTF-8"
                            .toMediaType()
                    )
                )

                if (
                    response.isSuccessful &&
                    response.text.isNotBlank()
                ) {
                    val decrypted = decryptDoubleAes(
                        response.text,
                        dynamicKey
                    )

                    if (!decrypted.isNullOrBlank()) {
                        return decrypted
                    }
                }
            } catch (e: Exception) {
                if (attempt == retryCount - 1) {
                    Log.e(
                        "InatBox",
                        "Post request failed for $url: ${e.message}",
                        e
                    )
                }
            }
        }

        return null
    }

    private fun getSearchResponseList(
        jsonResponse: String
    ): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()

        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                if (item.has("diziType")) {
                    val name = item.optString(
                        "diziName",
                        "İsimsiz"
                    )

                    val type = item.optString("diziType")
                    val posterUrl = item.optString(
                        "diziImg",
                        ""
                    )

                    when {
                        type.contains(
                            "dizi",
                            ignoreCase = true
                        ) -> {
                            searchResults.add(
                                newTvSeriesSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        type.contains(
                            "film",
                            ignoreCase = true
                        ) -> {
                            searchResults.add(
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }

                    continue
                }

                if (
                    item.has("chName") &&
                    item.has("chUrl") &&
                    item.has("chImg")
                ) {
                    val name = item.optString(
                        "chName",
                        "İsimsiz"
                    )

                    val posterUrl = item.optString(
                        "chImg",
                        ""
                    )

                    val chType = item.optString("chType")
                    val chUrl = item.optString("chUrl")

                    when {
                        chType.contains(
                            "live",
                            ignoreCase = true
                        ) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        chType.contains(
                            "cable",
                            ignoreCase = true
                        ) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        chType.contains(
                            "tekli",
                            ignoreCase = true
                        ) &&
                            !chUrl.contains(
                                "filmizleeeee",
                                ignoreCase = true
                            ) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        else -> {
                            searchResults.add(
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse JSON response: ${e.message}",
                e
            )
        }

        return searchResults
    }
}
