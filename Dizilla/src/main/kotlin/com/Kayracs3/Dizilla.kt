package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizilla : MainAPI() {

    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override var sequentialMainPage = true

    private val paginationSignatures =
        HashMap<String, String>()

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val SEARCH_PATH =
            "/api/bg/searchContent?searchterm="

        private const val AES_SEED =
            "!!22xx!!90!!"

        private const val STATIC_AES_KEY =
            "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

        private val INVALID_EPISODE_TITLES =
            setOf(
                "the scandal izle",
                "reacher izle",
                "law & order izle",
                "law and order izle"
            )
    }

    private val aesKey: ByteArray by lazy {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(AES_SEED.toByteArray(Charsets.UTF_8))

        val base64 =
            Base64.encodeToString(
                digest,
                Base64.NO_WRAP
            )

        base64
            .substring(0, 32)
            .toByteArray(Charsets.UTF_8)
    }

    // =========================================================
    // AES / SECURE DATA
    // =========================================================

    private fun decryptWithKey(
        encrypted: String,
        key: ByteArray
    ): JSONObject? {
        return try {
            val clean =
                encrypted
                    .trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\/", "/")

            val cipher =
                Cipher.getInstance("AES/CBC/PKCS5Padding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(ByteArray(16))
            )

            val decoded =
                Base64.decode(
                    clean,
                    Base64.DEFAULT
                )

            val plain =
                cipher.doFinal(decoded)

            JSONObject(
                String(
                    plain,
                    Charsets.UTF_8
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptSecureData(
        encrypted: String
    ): JSONObject? {
        if (encrypted.isBlank()) return null

        decryptWithKey(
            encrypted,
            aesKey
        )?.let { return it }

        return decryptWithKey(
            encrypted,
            STATIC_AES_KEY.toByteArray(Charsets.UTF_8)
        )
    }

    private fun getNextData(
        document: Document
    ): String? {
        return document
            .selectFirst("script#__NEXT_DATA__")
            ?.data()
            ?.takeIf { it.isNotBlank() }
    }

    private fun getSecureData(
        document: Document
    ): JSONObject? {

        val nextData =
            getNextData(document)
                ?: return null

        return try {
            val nextJson =
                JSONObject(nextData)

            val direct =
                nextJson
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optString("secureData")
                    ?.takeIf { it.isNotBlank() }

            if (!direct.isNullOrBlank()) {
                decryptSecureData(direct)?.let { return it }
            }

            val encrypted =
                findStringRecursive(
                    nextJson,
                    setOf("secureData")
                )

            encrypted?.let {
                decryptSecureData(it)
            }
        } catch (e: Exception) {
            Log.e(
                "Dizilla",
                "secureData bulunamadı: ${e.message}"
            )
            null
        }
    }

    private fun findStringRecursive(
        value: Any?,
        keys: Set<String>
    ): String? {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val child = value.opt(key)

                    if (
                        key in keys &&
                        child is String &&
                        child.isNotBlank()
                    ) {
                        return child
                    }

                    val found =
                        findStringRecursive(
                            child,
                            keys
                        )

                    if (!found.isNullOrBlank()) {
                        return found
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val found =
                        findStringRecursive(
                            value.opt(index),
                            keys
                        )

                    if (!found.isNullOrBlank()) {
                        return found
                    }
                }
            }
        }

        return null
    }

    private fun findValueRecursive(
        value: Any?,
        keys: Set<String>
    ): Any? {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val child = value.opt(key)

                    if (
                        key in keys &&
                        child != null &&
                        child != JSONObject.NULL
                    ) {
                        return child
                    }

                    val found =
                        findValueRecursive(
                            child,
                            keys
                        )

                    if (found != null) {
                        return found
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val found =
                        findValueRecursive(
                            value.opt(index),
                            keys
                        )

                    if (found != null) {
                        return found
                    }
                }
            }
        }

        return null
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage =
        mainPageOf(
            "${mainUrl}/arsiv" to "Yeni Eklenen Diziler",
            "${mainUrl}/yabanci-dizi-izle" to "Yabancı Diziler",
            "${mainUrl}/anime-izle" to "Asya Dizileri",
            "${mainUrl}/kdrama-izle" to "Kore Dizileri"
        )

    private fun buildPageCandidates(
        baseUrl: String,
        page: Int
    ): List<String> {

        if (page <= 1) {
            return listOf(baseUrl)
        }

        val result =
            LinkedHashSet<String>()

        val trimmed =
            baseUrl.trimEnd('/')

        if (baseUrl.contains("?")) {
            result += "$baseUrl&page=$page"
            result += "$baseUrl&paged=$page"
            result += "$baseUrl&sayfa=$page"
            result += "$baseUrl&p=$page"
        } else {
            result += "$baseUrl?page=$page"
            result += "$baseUrl?paged=$page"
            result += "$baseUrl?sayfa=$page"
            result += "$baseUrl?p=$page"
        }

        result += "$trimmed/page/$page"
        result += "$trimmed/page/$page/"
        result += "$trimmed/$page"
        result += "$trimmed/$page/"

        return result.toList()
    }

    private fun isNewAddedArchive(
        requestData: String
    ): Boolean {
        val normalized =
            requestData
                .trimEnd('/')
                .removeSuffix("/")
                .lowercase()

        return normalized ==
            "${mainUrl.trimEnd('/').lowercase()}/arsiv"
    }

    private fun extractMainPageResults(
        document: Document,
        requestData: String
    ): List<SearchResponse> {

        val results =
            LinkedHashMap<String, SearchResponse>()

        // =====================================================
        // YENİ EKLENEN DİZİLER
        // =====================================================
        // Sitenin HTML'indeki gerçek alan:
        // .new-added-list > a[href^="/dizi/"]
        // =====================================================
        if (isNewAddedArchive(requestData)) {

            document
                .select(
                    ".new-added-list > a[href^='/dizi/']"
                )
                .forEach { element ->

                    val href =
                        fixUrlNull(
                            element.attr("href")
                        )
                            ?: return@forEach

                    val title =
                        element
                            .selectFirst("h3")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: element
                                .attr("title")
                                .trim()
                                .removeSuffix(" izle")
                                .trim()
                                .takeIf { it.isNotBlank() }
                            ?: element
                                .selectFirst("img")
                                ?.attr("alt")
                                ?.trim()
                                ?.replace(
                                    Regex(
                                        "\\s*-\\s*\\d{4}\\s+izle$"
                                    ),
                                    ""
                                )
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                            ?: return@forEach

                    val image =
                        element.selectFirst("img")

                    val poster =
                        image?.let {
                            listOf(
                                it.attr("src"),
                                it.attr("data-src"),
                                it.attr("data-lazy-src"),
                                it.attr("data-original"),
                                it.attr("data-image")
                            )
                                .firstNotNullOfOrNull { candidate ->
                                    fixUrlNull(candidate)
                                        ?.takeIf { url -> url.isNotBlank() }
                                }
                        }

                    results[href] =
                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {
                            this.posterUrl = poster
                        }
                }

            Log.d(
                "Dizilla",
                "Yeni Eklenen Diziler HTML kart sayısı = ${results.size}"
            )

            return results.values.toList()
        }

        // =====================================================
        // DİĞER ANA SAYFALAR
        // =====================================================

        val secure =
            getSecureData(document)

        if (secure != null) {

            collectSeriesFromJson(
                secure
            ).forEach { item ->

                val slug =
                    item.slug
                        ?: return@forEach

                val href =
                    if (slug.startsWith("http")) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                val title =
                    item.title
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                results[href] =
                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = item.poster
                    }
            }
        }

        if (results.isEmpty()) {

            document
                .select("a[href^='/dizi/']")
                .forEach { element ->

                    val item =
                        element.toSearchResponse()
                            ?: return@forEach

                    results[item.url] = item
                }
        }

        return results.values.toList()
    }

    private fun pageSignature(
        results: List<SearchResponse>
    ): String {
        return results
            .take(30)
            .joinToString("|") {
                "${it.url}|${it.name}"
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val candidates =
            buildPageCandidates(
                request.data,
                page
            )

        var selectedResults =
            emptyList<SearchResponse>()

        var selectedUrl =
            request.data

        for (candidate in candidates) {

            val document =
                try {
                    app.get(
                        candidate,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                            "Referer" to "$mainUrl/"
                        ),
                        referer = "$mainUrl/"
                    ).document
                } catch (e: Exception) {
                    Log.d(
                        "Dizilla",
                        "Pagination candidate failed: $candidate -> ${e.message}"
                    )
                    continue
                }

            val results =
                extractMainPageResults(
                    document,
                    candidate
                )

            Log.d(
                "Dizilla",
                "Page $page candidate=$candidate results=${results.size}"
            )

            if (results.isEmpty()) {
                continue
            }

            selectedResults = results
            selectedUrl = candidate
            break
        }

        if (selectedResults.isEmpty()) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val signature =
            pageSignature(selectedResults)

        val previousSignature =
            paginationSignatures[request.data]

        if (
            page > 1 &&
            previousSignature == signature
        ) {
            Log.d(
                "Dizilla",
                "Page $page aynı sonuçları döndürdü."
            )

            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        paginationSignatures[request.data] =
            signature

        Log.d(
            "Dizilla",
            "Page $page selected=$selectedUrl count=${selectedResults.size}"
        )

        return newHomePageResponse(
            request.name,
            selectedResults,
            hasNext = true
        )
    }

    // =========================================================
    // SERIES ITEM / JSON
    // =========================================================

    private data class SeriesInfo(
        val title: String?,
        val slug: String?,
        val poster: String?
    )

    private fun collectSeriesFromJson(
        json: JSONObject
    ): List<SeriesInfo> {

        val output =
            mutableListOf<SeriesInfo>()

        fun walk(value: Any?) {
            when (value) {

                is JSONObject -> {

                    val title =
                        firstString(
                            value,
                            "series_title",
                            "object_name",
                            "name",
                            "title"
                        )

                    val slug =
                        firstString(
                            value,
                            "series_slug",
                            "used_slug",
                            "slug"
                        )

                    val poster =
                        firstString(
                            value,
                            "poster_url",
                            "object_poster_url",
                            "series_poster",
                            "poster"
                        )

                    if (
                        !title.isNullOrBlank() &&
                        !slug.isNullOrBlank()
                    ) {
                        output +=
                            SeriesInfo(
                                title,
                                slug,
                                poster?.replace("\\/", "/")
                            )
                    }

                    val keys = value.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        walk(value.opt(key))
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index))
                    }
                }
            }
        }

        walk(json)

        return output.distinctBy { it.slug }
    }

    private fun firstString(
        obj: JSONObject,
        vararg keys: String
    ): String? {

        for (key in keys) {
            val value = obj.opt(key)

            if (
                value != null &&
                value != JSONObject.NULL
            ) {
                val text =
                    value
                        .toString()
                        .trim()

                if (
                    text.isNotBlank() &&
                    text != "null"
                ) {
                    return text
                }
            }
        }

        return null
    }

    private fun Element.toSearchResponse(): SearchResponse? {

        val href =
            fixUrlNull(attr("href"))
                ?: return null

        val title =
            selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: selectFirst("h3")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: attr("title")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl =
                extractPoster(this@toSearchResponse)
        }
    }

    // =========================================================
    // POSTER
    // =========================================================

    private fun extractPoster(
        element: Element
    ): String? {

        val image =
            element.selectFirst("img")
                ?: return null

        val candidates =
            listOf(
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("data-image"),
                image.attr("data-lazy")
            )

        for (candidate in candidates) {
            val fixed =
                fixUrlNull(candidate)

            if (!fixed.isNullOrBlank()) {
                return fixed
            }
        }

        val srcSet =
            image
                .attr("srcset")
                .trim()

        if (srcSet.isNotBlank()) {
            val first =
                srcSet
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.substringBefore(" ")

            return fixUrlNull(first)
        }

        return null
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val response =
            try {
                app.post(
                    "$mainUrl$SEARCH_PATH$encoded",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/"
                    ),
                    referer = "$mainUrl/"
                )
            } catch (e: Exception) {
                Log.e(
                    "Dizilla",
                    "Search failed: ${e.message}"
                )
                return emptyList()
            }

        val outer =
            try {
                JSONObject(response.text)
            } catch (_: Exception) {
                return emptyList()
            }

        if (
            !outer.optBoolean(
                "success",
                false
            )
        ) {
            return emptyList()
        }

        val encrypted =
            outer
                .optString("response")
                .trim()

        val json =
            decryptSecureData(encrypted)
                ?: return emptyList()

        val result =
            json.optJSONArray("result")
                ?: return emptyList()

        val output =
            mutableListOf<SearchResponse>()

        for (index in 0 until result.length()) {

            val item =
                result.optJSONObject(index)
                    ?: continue

            val title =
                firstString(
                    item,
                    "object_name",
                    "name",
                    "title"
                )
                    ?: continue

            val slug =
                firstString(
                    item,
                    "used_slug",
                    "slug"
                )
                    ?: continue

            val href =
                if (slug.startsWith("http")) {
                    slug
                } else {
                    "$mainUrl/${slug.trimStart('/')}"
                }

            val poster =
                firstString(
                    item,
                    "object_poster_url",
                    "poster_url"
                )

            output +=
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
        }

        return output.distinctBy { it.url }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> =
        search(query)

    // =========================================================
    // LOAD SERIES
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.d(
            "Dizilla",
            "Current series slug=${url.trimEnd('/').substringAfterLast('/')}"
        )

        val document =
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                referer = "$mainUrl/"
            ).document

        val secure =
            getSecureData(document)

        val title =
            firstMetadata(
                document,
                secure,
                setOf(
                    "series_title",
                    "object_name",
                    "title",
                    "name"
                )
            )
                ?: document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                ?: url
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .replace("-", " ")

        val poster =
            firstMetadata(
                document,
                secure,
                setOf(
                    "poster_url",
                    "object_poster_url",
                    "series_poster",
                    "poster"
                )
            )
                ?: document
                    .selectFirst("meta[property='og:image']")
                    ?.attr("content")
                    ?.trim()

        val description =
            firstMetadata(
                document,
                secure,
                setOf(
                    "series_description",
                    "description",
                    "plot",
                    "overview",
                    "summary"
                )
            )
                ?: document
                    .selectFirst(
                        "meta[property='og:description']"
                    )
                    ?.attr("content")
                    ?.trim()

        val yearText =
            firstMetadata(
                document,
                secure,
                setOf(
                    "release_date",
                    "object_release_year",
                    "release_year",
                    "year"
                )
            )

        val year =
            yearText?.let {
                Regex("""\d{4}""")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }

        val scoreText =
            firstMetadata(
                document,
                secure,
                setOf(
                    "imdb_point",
                    "imdb_score",
                    "imdb_rating",
                    "rating",
                    "score"
                )
            )

        val score =
            scoreText
                ?.replace(",", ".")
                ?.toDoubleOrNull()

        val tags =
            document
                .select("a[href*='dizi-turu']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

        val actors =
            document
                .select("a[href*='oyuncu']")
                .map {
                    Actor(it.text().trim())
                }
                .filter {
                    it.name.isNotBlank()
                }

        // =====================================================
        // TÜM BÖLÜMLER
        // =====================================================

        val episodes =
            mutableListOf<Episode>()

        if (secure != null) {

            val secureEpisodes =
                collectEpisodesFromJson(secure)

            Log.d(
                "Dizilla",
                "secureData episode count = ${secureEpisodes.size}"
            )

            secureEpisodes.forEach { info ->

                val slug =
                    info.slug
                        ?: return@forEach

                val href =
                    if (slug.startsWith("http")) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                episodes +=
                    newEpisode(href) {

                        this.name =
                            info.title
                                ?: if (info.episode != null) {
                                    "${info.episode}. Bölüm"
                                } else {
                                    "Bölüm"
                                }

                        this.season = info.season
                        this.episode = info.episode
                        this.description = info.description
                        this.posterUrl = info.poster
                    }
            }
        }

        if (episodes.isEmpty()) {

            val nextData =
                getNextData(document)

            if (!nextData.isNullOrBlank()) {

                try {

                    val nextJson =
                        JSONObject(nextData)

                    val nextEpisodes =
                        collectEpisodesFromJson(nextJson)

                    Log.d(
                        "Dizilla",
                        "__NEXT_DATA__ episode count = ${nextEpisodes.size}"
                    )

                    nextEpisodes.forEach { info ->

                        val slug =
                            info.slug
                                ?: return@forEach

                        val href =
                            if (slug.startsWith("http")) {
                                slug
                            } else {
                                "$mainUrl/${slug.trimStart('/')}"
                            }

                        episodes +=
                            newEpisode(href) {

                                this.name =
                                    info.title
                                        ?: if (info.episode != null) {
                                            "${info.episode}. Bölüm"
                                        } else {
                                            "Bölüm"
                                        }

                                this.season = info.season
                                this.episode = info.episode
                                this.description = info.description
                                this.posterUrl = info.poster
                            }
                    }

                } catch (e: Exception) {
                    Log.e(
                        "Dizilla",
                        "__NEXT_DATA__ parse failed: ${e.message}"
                    )
                }
            }
        }

        val htmlEpisodes =
            collectEpisodesFromHtml(document)

        Log.d(
            "Dizilla",
            "HTML episode count = ${htmlEpisodes.size}"
        )

        episodes += htmlEpisodes

        val finalEpisodes =
            episodes
                .distinctBy { it.data }
                .sortedWith(
                    compareBy(
                        { it.season ?: 0 },
                        { it.episode ?: 0 },
                        { it.name }
                    )
                )

        if (finalEpisodes.isEmpty()) {
            throw ErrorLoadingException(
                "Dizilla: Hiç bölüm bulunamadı."
            )
        }

        Log.d(
            "Dizilla",
            "Bulunan bölüm sayısı = ${finalEpisodes.size}"
        )

        val seasonCount =
            finalEpisodes
                .mapNotNull { it.season }
                .distinct()
                .size

        Log.d(
            "Dizilla",
            "Bulunan sezon sayısı = $seasonCount"
        )

        return newTvSeriesLoadResponse(
            title.trim(),
            url,
            TvType.TvSeries,
            finalEpisodes
        ) {

            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags

            if (score != null) {
                this.score =
                    Score.from10(score)
            }

            this.duration = null

            addActors(actors)
        }
    }

    // =========================================================
    // METADATA
    // =========================================================

    private fun firstMetadata(
        document: Document,
        secure: JSONObject?,
        keys: Set<String>
    ): String? {

        if (secure != null) {

            findValueRecursive(
                secure,
                keys
            )?.let {

                val value =
                    it.toString().trim()

                if (
                    value.isNotBlank() &&
                    value != "null"
                ) {
                    return value
                }
            }
        }

        val metaCandidates =
            when {

                "series_description" in keys ->
                    listOf(
                        "meta[property='og:description']",
                        "meta[name='description']"
                    )

                "poster_url" in keys ->
                    listOf(
                        "meta[property='og:image']",
                        "meta[name='twitter:image']"
                    )

                else ->
                    listOf(
                        "meta[property='og:title']"
                    )
            }

        for (selector in metaCandidates) {

            val value =
                document
                    .selectFirst(selector)
                    ?.attr("content")
                    ?.trim()

            if (!value.isNullOrBlank()) {
                return value
            }
        }

        return null
    }

    // =========================================================
    // EPISODE MODEL
    // =========================================================

    private data class EpisodeInfo(
        val season: Int?,
        val episode: Int?,
        val slug: String?,
        val title: String?,
        val description: String?,
        val poster: String?
    )

    private fun normalizeEpisodeTitle(
        value: String?
    ): String {

        return value
            ?.lowercase()
            ?.replace("ı", "i")
            ?.replace("ş", "s")
            ?.replace("ğ", "g")
            ?.replace("ü", "u")
            ?.replace("ö", "o")
            ?.replace("ç", "c")
            ?.replace(
                Regex("""\s+"""),
                " "
            )
            ?.trim()
            ?: ""
    }

    private fun isInvalidEpisodeTitle(
        value: String?
    ): Boolean {

        val normalized =
            normalizeEpisodeTitle(value)

        if (normalized.isBlank()) {
            return false
        }

        return INVALID_EPISODE_TITLES
            .map {
                normalizeEpisodeTitle(it)
            }
            .contains(normalized)
    }

    // =========================================================
    // EPISODES FROM JSON
    // =========================================================

    private fun collectEpisodesFromJson(
        json: JSONObject
    ): List<EpisodeInfo> {

        val output =
            mutableListOf<EpisodeInfo>()

        fun walk(value: Any?) {

            when (value) {

                is JSONObject -> {

                    val season =
                        firstInt(
                            value,
                            "season_no",
                            "season",
                            "season_number",
                            "seasonNumber"
                        )

                    val episode =
                        firstInt(
                            value,
                            "episode_no",
                            "episode",
                            "episode_number",
                            "episodeNumber",
                            "episode_num"
                        )

                    val slug =
                        firstString(
                            value,
                            "episode_slug",
                            "episode_url",
                            "episode_link",
                            "used_slug",
                            "slug",
                            "url",
                            "href",
                            "link"
                        )

                    val title =
                        firstString(
                            value,
                            "episode_title",
                            "episode_name",
                            "original_title",
                            "post_title",
                            "name",
                            "title"
                        )

                    val description =
                        firstString(
                            value,
                            "episode_description",
                            "description",
                            "overview",
                            "plot",
                            "summary"
                        )

                    val poster =
                        firstString(
                            value,
                            "episode_poster",
                            "poster_url",
                            "object_poster_url",
                            "poster",
                            "image"
                        )

                    if (!slug.isNullOrBlank()) {

                        val parsed =
                            parseSeasonEpisodeFromSlug(slug)

                        val finalSeason =
                            season ?: parsed.first

                        val finalEpisode =
                            episode ?: parsed.second

                        if (
                            finalSeason != null &&
                            finalEpisode != null &&
                            !isInvalidEpisodeTitle(title)
                        ) {
                            output +=
                                EpisodeInfo(
                                    season = finalSeason,
                                    episode = finalEpisode,
                                    slug = slug,
                                    title = title,
                                    description = description,
                                    poster = poster?.replace("\\/", "/")
                                )
                        }
                    }

                    val keys = value.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        walk(value.opt(key))
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index))
                    }
                }
            }
        }

        walk(json)

        return output.distinctBy {
            val cleanSlug =
                it.slug
                    ?.replace("\\/", "/")
                    ?.trim()

            "${it.season}|${it.episode}|$cleanSlug"
        }
    }

    private fun firstInt(
        obj: JSONObject,
        vararg keys: String
    ): Int? {

        for (key in keys) {

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

    private fun parseSeasonEpisodeFromSlug(
        slug: String
    ): Pair<Int?, Int?> {

        val clean =
            slug
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .trim()
                .substringBefore("?")
                .substringBefore("#")
                .substringAfterLast("/")

        val patterns =
            listOf(

                Regex(
                    """-(\d+)-sezon-(\d+)-bolum""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """(\d+)-sezon-(\d+)-bolum""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """sezon-(\d+)-bolum-(\d+)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """sezon[-_/](\d+)[-_/]bolum[-_/](\d+)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """(?:^|-)(?:s)(\d{1,2})(?:-|_)?(?:e)(\d{1,3})(?:-|_)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """(?:^|-)(\d{1,2})x(\d{1,3})(?:-|$)""",
                    RegexOption.IGNORE_CASE
                )
            )

        for (pattern in patterns) {

            val match =
                pattern.find(clean)
                    ?: continue

            val season =
                match
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()

            val episode =
                match
                    .groupValues
                    .getOrNull(2)
                    ?.toIntOrNull()

            if (
                season != null &&
                episode != null
            ) {
                return Pair(
                    season,
                    episode
                )
            }
        }

        return Pair(
            null,
            null
        )
    }

    // =========================================================
    // EPISODES FROM HTML
    // =========================================================

    private fun collectEpisodesFromHtml(
        document: Document
    ): List<Episode> {

        val output =
            mutableListOf<Episode>()

        val seen =
            HashSet<String>()

        fun addEpisode(
            rawHref: String?,
            element: Element? = null
        ) {

            if (rawHref.isNullOrBlank()) {
                return
            }

            var href =
                rawHref
                    .trim()
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")

            if (href.startsWith("//")) {
                href = "https:$href"
            }

            val fixed =
                fixUrlNull(href)
                    ?: return

            val rawSlug =
                fixed
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .substringBefore("?")
                    .substringBefore("#")

            val parsed =
                parseSeasonEpisodeFromSlug(rawSlug)

            val season =
                parsed.first ?: return

            val episode =
                parsed.second ?: return

            val title =
                element
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: element
                        ?.attr("title")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: element
                        ?.selectFirst("img")
                        ?.attr("alt")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: "${episode}. Bölüm"

            if (isInvalidEpisodeTitle(title)) {
                return
            }

            if (!seen.add(fixed)) {
                return
            }

            output +=
                newEpisode(fixed) {
                    this.name = title
                    this.season = season
                    this.episode = episode
                }
        }

        document
            .select(
                "a[href], [data-href], [data-url], [data-link], [data-episode-url]"
            )
            .forEach { element ->

                val href =
                    element
                        .attr("href")
                        .takeIf { it.isNotBlank() }
                        ?: element
                            .attr("data-href")
                            .takeIf { it.isNotBlank() }
                        ?: element
                            .attr("data-url")
                            .takeIf { it.isNotBlank() }
                        ?: element
                            .attr("data-link")
                            .takeIf { it.isNotBlank() }
                        ?: element
                            .attr("data-episode-url")
                            .takeIf { it.isNotBlank() }

                addEpisode(
                    href,
                    element
                )
            }

        val rawHtml =
            document
                .html()
                .replace("\\/", "/")
                .replace("\\u002F", "/")

        val urlRegex =
            Regex(
                """(?i)(?:https?:)?//[^\"'<>\\\s]+|/[^\"'<>\\\s]+"""
            )

        urlRegex
            .findAll(rawHtml)
            .forEach { match ->
                addEpisode(match.value)
            }

        return output.distinctBy { it.data }
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

        Log.d(
            "Dizilla",
            "loadLinks = $data"
        )

        val episodeDocument =
            try {
                app.get(
                    data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    ),
                    referer = "$mainUrl/"
                ).document
            } catch (e: Exception) {
                Log.e(
                    "Dizilla",
                    "Episode page failed: ${e.message}"
                )
                return false
            }

        val secure =
            getSecureData(episodeDocument)

        if (secure != null) {

            val sources =
                secure
                    .optJSONObject("RelatedResults")
                    ?.optJSONObject("getEpisodeSources")
                    ?.optJSONArray("result")
                    ?: secure
                        .optJSONObject("content")
                        ?.optJSONObject("result")
                        ?.optJSONObject("RelatedResults")
                        ?.optJSONObject("getEpisodeSources")
                        ?.optJSONArray("result")

            if (sources != null) {

                var delivered = false

                for (index in 0 until sources.length()) {

                    val item =
                        sources.optJSONObject(index)
                            ?: continue

                    val sourceContent =
                        firstString(
                            item,
                            "source_content"
                        )
                            ?: continue

                    val iframe =
                        extractIframeUrl(sourceContent)
                            ?: continue

                    val sourceName =
                        firstString(
                            item,
                            "source_name"
                        )

                    val language =
                        firstString(
                            item,
                            "language_name"
                        )

                    val qualityName =
                        firstString(
                            item,
                            "quality_name"
                        )

                    val label =
                        buildString {
                            append(name)

                            if (!sourceName.isNullOrBlank()) {
                                append(" • ")
                                append(sourceName)
                            }

                            if (!language.isNullOrBlank()) {
                                append(" • ")
                                append(language)
                            }

                            if (!qualityName.isNullOrBlank()) {
                                append(" • ")
                                append(qualityName)
                            }
                        }

                    try {
                        if (
                            extractFromIframe(
                                iframe,
                                label,
                                qualityName.orEmpty(),
                                subtitleCallback,
                                callback
                            )
                        ) {
                            delivered = true
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "Dizilla",
                            "Source error: ${e.message}"
                        )
                    }
                }

                if (delivered) {
                    return true
                }
            }
        }

        return fallbackIframe(
            episodeDocument,
            subtitleCallback,
            callback
        )
    }

    private fun extractIframeUrl(
        sourceContent: String
    ): String? {

        try {

            val parsed =
                Jsoup.parse(sourceContent)

            val src =
                parsed
                    .selectFirst("iframe")
                    ?.attr("src")
                    ?.trim()

            fixUrlNull(src)?.let {
                return it
            }
        } catch (_: Exception) {
        }

        val regex =
            Regex(
                """(?:https?:)?//([A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9+/=]+)"""
            )

        val match =
            regex.find(sourceContent)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                "https://$it"
            }
    }

    private suspend fun extractFromIframe(
        iframeUrl: String,
        label: String,
        qualityName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val iframeHtml =
            try {
                app.get(
                    iframeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    ),
                    referer = "$mainUrl/"
                ).text
            } catch (e: Exception) {
                Log.e(
                    "Dizilla",
                    "Iframe failed: ${e.message}"
                )
                return false
            }

        if (iframeHtml.isBlank()) {
            return false
        }

        extractSubtitles(
            iframeHtml,
            subtitleCallback
        )

        val token =
            Regex(
                """window\.openPlayer\(['\"]([^'\"]+)['\"]"""
            )
                .find(iframeHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex(
                    """openPlayer\(['\"]([^'\"]+)['\"]"""
                )
                    .find(iframeHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: return false

        val host =
            iframeUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")

        if (host.isBlank()) {
            return false
        }

        val source2 =
            try {
                app.get(
                    "https://$host/source2.php?v=$token",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to iframeUrl
                    ),
                    referer = iframeUrl
                ).text
            } catch (e: Exception) {
                Log.e(
                    "Dizilla",
                    "source2 failed: ${e.message}"
                )
                return false
            }

        val sourceJson =
            try {
                JSONObject(source2)
            } catch (_: Exception) {
                return false
            }

        if (
            !sourceJson.optBoolean(
                "state",
                true
            )
        ) {
            return false
        }

        val playlist =
            sourceJson.optJSONArray("playlist")
                ?: return false

        var delivered = false

        for (playlistIndex in 0 until playlist.length()) {

            val playlistItem =
                playlist.optJSONObject(playlistIndex)
                    ?: continue

            val sources =
                playlistItem.optJSONArray("sources")
                    ?: continue

            for (sourceIndex in 0 until sources.length()) {

                val source =
                    sources.optJSONObject(sourceIndex)
                        ?: continue

                val type =
                    source
                        .optString("type")
                        .trim()
                        .lowercase()

                if (
                    type != "hls" &&
                    type != "m3u8"
                ) {
                    continue
                }

                var file =
                    source
                        .optString("file")
                        .trim()

                if (file.isBlank()) {
                    continue
                }

                file =
                    file.replace("\\", "")

                if (file.startsWith("//")) {
                    file = "https:$file"
                } else if (file.startsWith("/")) {
                    file = "https://$host$file"
                } else if (
                    !file.startsWith("http://") &&
                    !file.startsWith("https://")
                ) {
                    file = "https://$host/$file"
                }

                val masterUrl =
                    file.replace(
                        "m.php",
                        "master.m3u8"
                    )

                val quality =
                    Regex("""\d{3,4}""")
                        .find(qualityName)
                        ?.value
                        ?.toIntOrNull()
                        ?: Qualities.Unknown.value

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {

                        this.referer = iframeUrl

                        this.headers =
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframeUrl
                            )

                        this.quality = quality
                    }
                )

                try {
                    M3u8Helper
                        .generateM3u8(
                            label,
                            masterUrl,
                            iframeUrl
                        )
                        .forEach(callback)
                } catch (e: Exception) {
                    Log.d(
                        "Dizilla",
                        "M3U8 variant error: ${e.message}"
                    )
                }

                delivered = true
            }
        }

        return delivered
    }

    // =========================================================
    // SUBTITLE
    // =========================================================

    private suspend fun extractSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {

        val regex =
            Regex(
                """"file":"([^"]+)","label":"([^"]+)""""
            )

        val seen =
            HashSet<String>()

        regex
            .findAll(html)
            .forEach { match ->

                val rawUrl =
                    match
                        .groupValues
                        .getOrNull(1)
                        ?: return@forEach

                val rawLang =
                    match
                        .groupValues
                        .getOrNull(2)
                        ?: return@forEach

                val url =
                    rawUrl.replace("\\", "")

                if (
                    url.isBlank() ||
                    !seen.add(url)
                ) {
                    return@forEach
                }

                val language =
                    rawLang
                        .replace("\\u0131", "ı")
                        .replace("\\u0130", "İ")
                        .replace("\\u00fc", "ü")
                        .replace("\\u00e7", "ç")
                        .replace("\\u00f6", "ö")
                        .replace("\\u011f", "ğ")
                        .replace("\\u015f", "ş")
                        .replace("\\u00dc", "Ü")
                        .replace("\\u00d6", "Ö")
                        .replace("\\u00c7", "Ç")
                        .replace("\\u011e", "Ğ")
                        .replace("\\u015e", "Ş")

                try {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = language,
                            url = fixUrl(url)
                        )
                    )
                } catch (e: Exception) {
                    Log.d(
                        "Dizilla",
                        "Subtitle error: ${e.message}"
                    )
                }
            }
    }

    // =========================================================
    // FALLBACK IFRAME
    // =========================================================

    private suspend fun fallbackIframe(
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val iframe =
            document
                .select("iframe")
                .firstOrNull {

                    val src =
                        it.attr("src")

                    src.contains(
                        "player",
                        ignoreCase = true
                    ) ||
                        src.contains(
                            "embed",
                            ignoreCase = true
                        ) ||
                        src.contains(
                            "watch",
                            ignoreCase = true
                        )
                }
                ?.attr("src")

        val iframeUrl =
            fixUrlNull(iframe)
                ?: return false

        return extractFromIframe(
            iframeUrl,
            name,
            "",
            subtitleCallback,
            callback
        )
    }
}
