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
import java.util.Calendar
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

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val SEARCH_PATH =
            "/api/bg/searchContent?searchterm="

        private const val NEXT_DATA_PATH =
            "_next/data"

        private const val AES_SEED =
            "!!22xx!!90!!"

        private const val STATIC_AES_KEY =
            "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

        private val SEASON_REGEX =
            Regex(
                """-(\d+)-sezon""",
                RegexOption.IGNORE_CASE
            )

        private val EPISODE_REGEX =
            Regex(
                """-(\d+)-bolum""",
                RegexOption.IGNORE_CASE
            )

        private val EPISODE_SLUG_REGEX =
            Regex(
                """(?:^|-)(\d+)-sezon-(\d+)-bolum""",
                RegexOption.IGNORE_CASE
            )
    }

    // =========================================================
    // AES KEY
    // =========================================================

    private val aesKey: ByteArray by lazy {

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    AES_SEED.toByteArray(
                        Charsets.UTF_8
                    )
                )

        val base64 =
            Base64.encodeToString(
                digest,
                Base64.NO_WRAP
            )

        base64
            .substring(0, 32)
            .toByteArray(
                Charsets.UTF_8
            )
    }

    // =========================================================
    // SECURE DATA ÇÖZME
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
                    .replace(
                        "\\/",
                        "/"
                    )

            val cipher =
                Cipher.getInstance(
                    "AES/CBC/PKCS5Padding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    key,
                    "AES"
                ),
                IvParameterSpec(
                    ByteArray(16)
                )
            )

            val decoded =
                Base64.decode(
                    clean,
                    Base64.DEFAULT
                )

            val plain =
                cipher.doFinal(
                    decoded
                )

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

        if (encrypted.isBlank()) {
            return null
        }

        decryptWithKey(
            encrypted,
            aesKey
        )?.let {
            return it
        }

        return decryptWithKey(
            encrypted,
            STATIC_AES_KEY.toByteArray(
                Charsets.UTF_8
            )
        )
    }

    // =========================================================
    // NEXT DATA
    // =========================================================

    private fun getNextData(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "script#__NEXT_DATA__"
            )
            ?.data()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun getSecureData(
        document: Document
    ): JSONObject? {

        val nextData =
            getNextData(
                document
            )
                ?: return null

        return try {

            val nextJson =
                JSONObject(
                    nextData
                )

            val direct =
                nextJson
                    .optJSONObject(
                        "props"
                    )
                    ?.optJSONObject(
                        "pageProps"
                    )
                    ?.optString(
                        "secureData"
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }

            if (
                !direct.isNullOrBlank()
            ) {

                decryptSecureData(
                    direct
                )?.let {
                    return it
                }
            }

            val encrypted =
                findStringRecursive(
                    nextJson,
                    setOf(
                        "secureData"
                    )
                )

            encrypted?.let {
                decryptSecureData(
                    it
                )
            }

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "secureData bulunamadı: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // GENERIC JSON SEARCH
    // =========================================================

    private fun findStringRecursive(
        value: Any?,
        keys: Set<String>
    ): String? {

        when (value) {

            is JSONObject -> {

                val iterator =
                    value.keys()

                while (
                    iterator.hasNext()
                ) {

                    val key =
                        iterator.next()

                    val child =
                        value.opt(
                            key
                        )

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

                    if (
                        !found.isNullOrBlank()
                    ) {
                        return found
                    }
                }
            }

            is JSONArray -> {

                for (
                    index in 0 until value.length()
                ) {

                    val found =
                        findStringRecursive(
                            value.opt(index),
                            keys
                        )

                    if (
                        !found.isNullOrBlank()
                    ) {
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

                val iterator =
                    value.keys()

                while (
                    iterator.hasNext()
                ) {

                    val key =
                        iterator.next()

                    val child =
                        value.opt(
                            key
                        )

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

                    if (
                        found != null
                    ) {
                        return found
                    }
                }
            }

            is JSONArray -> {

                for (
                    index in 0 until value.length()
                ) {

                    val found =
                        findValueRecursive(
                            value.opt(index),
                            keys
                        )

                    if (
                        found != null
                    ) {
                        return found
                    }
                }
            }
        }

        return null
    }

    // =========================================================
    // BUILD ID
    // =========================================================

    private fun extractBuildId(
        document: Document
    ): String? {

        val nextData =
            getNextData(
                document
            )
                ?: return null

        return try {

            JSONObject(
                nextData
            )
                .optString(
                    "buildId"
                )
                .takeIf {
                    it.isNotBlank()
                }

        } catch (_: Exception) {

            null
        }
    }

    private suspend fun getBuildId(
        document: Document? = null
    ): String? {

        document
            ?.let {
                extractBuildId(
                    it
                )
            }
            ?.let {
                return it
            }

        return try {

            val home =
                app.get(
                    mainUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).document

            extractBuildId(
                home
            )

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "Build ID alınamadı: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // ANA SAYFALAR
    // =========================================================

    override val mainPage =
        mainPageOf(

            "${mainUrl}/arsiv"
                to "Yeni Eklenen Diziler",

            "${mainUrl}/yabanci-dizi-izle"
                to "Yabancı Diziler",

            "${mainUrl}/anime-izle"
                to "Asya Dizileri",

            "${mainUrl}/kdrama-izle"
                to "Kore Dizileri"
        )

    // =========================================================
    // MAIN PAGE + PAGINATION
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val targetUrl: String

        /*
         * =====================================================
         * DİZILLA ARŞİV SAYFALAMASI
         * =====================================================
         *
         * Site 24 kayıtlık sayfalar kullanıyor.
         *
         * Örnek:
         *
         * /arsiv?page=1&tab=1&sort=date_desc
         * /arsiv?page=2&tab=1&sort=date_desc
         * /arsiv?page=3&tab=1&sort=date_desc
         *
         * Ayrıca site kendi filtrelerini de bekliyor.
         */

        if (
            request.data.contains(
                "/arsiv"
            )
        ) {

            val yil =
                Calendar
                    .getInstance()
                    .get(
                        Calendar.YEAR
                    )

            targetUrl =
                buildString {

                    append(
                        request.data
                    )

                    append(
                        if (
                            request.data.contains("?")
                        ) {
                            "&"
                        } else {
                            "?"
                        }
                    )

                    append(
                        "page="
                    )

                    append(
                        page
                    )

                    append(
                        "&tab=1"
                    )

                    append(
                        "&sort=date_desc"
                    )

                    append(
                        "&filterType=2"
                    )

                    append(
                        "&imdbMin=5"
                    )

                    append(
                        "&imdbMax=10"
                    )

                    append(
                        "&yearMin=1900"
                    )

                    append(
                        "&yearMax="
                    )

                    append(
                        yil
                    )
                }

        } else {

            targetUrl =
                if (
                    page <= 1
                ) {

                    request.data

                } else {

                    val separator =
                        if (
                            request.data.contains(
                                "?"
                            )
                        ) {
                            "&"
                        } else {
                            "?"
                        }

                    "${request.data}" +
                        "${separator}page=$page"
                }
        }

        Log.d(
            "Dizilla",
            "MainPage page=$page -> $targetUrl"
        )

        val document =
            try {

                app.get(
                    targetUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).document

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "MainPage error: ${e.message}"
                )

                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }

        val results =
            LinkedHashMap<String, SearchResponse>()

        // =====================================================
        // SECURE DATA
        // =====================================================

        val secure =
            getSecureData(
                document
            )

        if (
            secure != null
        ) {

            collectSeriesFromJson(
                secure
            ).forEach { item ->

                val title =
                    item.title
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return@forEach

                val slug =
                    item.slug
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return@forEach

                val href =
                    if (
                        slug.startsWith(
                            "http://"
                        ) ||
                        slug.startsWith(
                            "https://"
                        )
                    ) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                results[href] =
                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {

                        this.posterUrl =
                            item.poster
                    }
            }
        }

        // =====================================================
        // ARŞİV HTML
        // =====================================================

        if (
            results.isEmpty() &&
            request.data.contains(
                "/arsiv"
            )
        ) {

            document
                .select(
                    "a.w-full"
                )
                .forEach { element ->

                    val item =
                        element
                            .toArchiveSearchResponse()
                            ?: return@forEach

                    results[
                        item.url
                    ] = item
                }
        }

        // =====================================================
        // NORMAL DİZİ HTML
        // =====================================================

        if (
            results.isEmpty()
        ) {

            document
                .select(
                    "a[href*='/dizi/']"
                )
                .forEach { element ->

                    val item =
                        element
                            .toSearchResponse()
                            ?: return@forEach

                    results[
                        item.url
                    ] = item
                }
        }

        // =====================================================
        // GENEL FALLBACK
        // =====================================================

        if (
            results.isEmpty()
        ) {

            document
                .select(
                    "a[href]"
                )
                .filter {
                    it.selectFirst(
                        "img"
                    ) != null
                }
                .forEach { element ->

                    val href =
                        fixUrlNull(
                            element.attr(
                                "href"
                            )
                        )
                            ?: return@forEach

                    if (
                        href.contains(
                            "-sezon-"
                        ) ||
                        href.contains(
                            "-bolum-"
                        )
                    ) {
                        return@forEach
                    }

                    val title =
                        element
                            .selectFirst(
                                "h2"
                            )
                            ?.text()
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: element
                                .selectFirst(
                                    "h3"
                                )
                                ?.text()
                                ?.trim()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                            ?: element
                                .selectFirst(
                                    "img"
                                )
                                ?.attr(
                                    "alt"
                                )
                                ?.trim()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                            ?: return@forEach

                    results[href] =
                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {

                            this.posterUrl =
                                extractPoster(
                                    element
                                )
                        }
                }
        }

        val items =
            results
                .values
                .toList()

        Log.d(
            "Dizilla",
            "MainPage page=$page sonuç=${items.size}"
        )

        /*
         * Site sayfa başına 24 kayıt verdiği için
         * 24 veya daha fazla kayıt varsa bir sonraki
         * sayfa istenebilir.
         */

        val hasNext =
            items.size >= 24

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNext
        )
    }

    // =========================================================
    // JSON SERIES
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

        fun walk(
            value: Any?
        ) {

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
                                poster?.replace(
                                    "\\/",
                                    "/"
                                )
                            )
                    }

                    val keys =
                        value.keys()

                    while (
                        keys.hasNext()
                    ) {

                        val key =
                            keys.next()

                        walk(
                            value.opt(
                                key
                            )
                        )
                    }
                }

                is JSONArray -> {

                    for (
                        index in
                        0 until value.length()
                    ) {

                        walk(
                            value.opt(
                                index
                            )
                        )
                    }
                }
            }
        }

        walk(
            json
        )

        return output.distinctBy {
            it.slug
        }
    }

    // =========================================================
    // JSON HELPERS
    // =========================================================

    private fun firstString(
        obj: JSONObject,
        vararg keys: String
    ): String? {

        for (
            key in keys
        ) {

            val value =
                obj.opt(
                    key
                )

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

    // =========================================================
    // SEARCH CARD
    // =========================================================

    private fun Element.toSearchResponse():
        SearchResponse? {

        val href =
            fixUrlNull(
                attr(
                    "href"
                )
            )
                ?: return null

        val title =
            selectFirst(
                "h2"
            )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: selectFirst(
                    "h3"
                )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: attr(
                    "title"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: selectFirst(
                    "img"
                )
                    ?.attr(
                        "alt"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {

            this.posterUrl =
                extractPoster(
                    this@toSearchResponse
                )
        }
    }

    // =========================================================
    // ARCHIVE SEARCH CARD
    // =========================================================

    private fun Element.toArchiveSearchResponse():
        SearchResponse? {

        val href =
            fixUrlNull(
                attr(
                    "href"
                )
            )
                ?: return null

        val title =
            selectFirst(
                "h2"
            )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: selectFirst(
                    "h3"
                )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: attr(
                    "title"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: selectFirst(
                    "img"
                )
                    ?.attr(
                        "alt"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        val score =
            selectFirst(
                "div.absolute.bottom-0 span"
            )
                ?.text()
                ?.trim()

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {

            this.posterUrl =
                extractPoster(
                    this@toArchiveSearchResponse
                )

            if (
                !score.isNullOrBlank()
            ) {

                this.score =
                    Score.from10(
                        score
                    )
            }
        }
    }

    // =========================================================
    // POSTER
    // =========================================================

    private fun extractPoster(
        element: Element
    ): String? {

        val image =
            element.selectFirst(
                "img"
            )
                ?: return null

        val candidates =
            listOf(
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("data-image")
            )

        for (
            candidate in candidates
        ) {

            val fixed =
                fixUrlNull(
                    candidate
                )

            if (
                !fixed.isNullOrBlank()
            ) {

                return fixed
            }
        }

        val srcSet =
            image
                .attr(
                    "srcset"
                )
                .trim()

        if (
            srcSet.isNotBlank()
        ) {

            val first =
                srcSet
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.substringBefore(
                        " "
                    )

            return fixUrlNull(
                first
            )
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
                        "User-Agent" to
                            USER_AGENT,
                        "Accept" to
                            "application/json, text/plain, */*",
                        "X-Requested-With" to
                            "XMLHttpRequest",
                        "Referer" to
                            "$mainUrl/"
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

                JSONObject(
                    response.text
                )

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
                .optString(
                    "response"
                )
                .trim()

        val json =
            decryptSecureData(
                encrypted
            )
                ?: return emptyList()

        val result =
            json.optJSONArray(
                "result"
            )
                ?: return emptyList()

        val output =
            mutableListOf<SearchResponse>()

        for (
            index in 0 until result.length()
        ) {

            val item =
                result.optJSONObject(
                    index
                )
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
                if (
                    slug.startsWith(
                        "http"
                    )
                ) {

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

                    this.posterUrl =
                        poster
                }
        }

        return output.distinctBy {
            it.url
        }
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

        val document =
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT
                )
            ).document

        val secure =
            getSecureData(
                document
            )

        // =====================================================
        // TITLE
        // =====================================================

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
                    .selectFirst(
                        "h1"
                    )
                    ?.text()
                    ?.trim()
                ?: url
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .replace(
                        "-",
                        " "
                    )

        // =====================================================
        // POSTER
        // =====================================================

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
                ?: extractPoster(
                    document
                )

        // =====================================================
        // DESCRIPTION
        // =====================================================

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
                    ?.attr(
                        "content"
                    )
                    ?.trim()

        // =====================================================
        // YEAR
        // =====================================================

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

                Regex(
                    """\d{4}"""
                )
                    .find(
                        it
                    )
                    ?.value
                    ?.toIntOrNull()
            }

        // =====================================================
        // IMDb
        // =====================================================

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
                ?.replace(
                    ",",
                    "."
                )
                ?.toDoubleOrNull()

        // =====================================================
        // TAGS
        // =====================================================

        val tags =
            document
                .select(
                    "a[href*='dizi-turu']"
                )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        // =====================================================
        // ACTORS
        // =====================================================

        val actors =
            document
                .select(
                    "a[href*='oyuncu']"
                )
                .map {
                    Actor(
                        it.text().trim()
                    )
                }
                .filter {
                    it.name.isNotBlank()
                }

        // =====================================================
        // EPISODES
        // =====================================================

        val episodes =
            mutableListOf<Episode>()

        // -----------------------------------------------------
        // SECURE DATA EPISODES
        // -----------------------------------------------------

        if (
            secure != null
        ) {

            collectEpisodesFromJson(
                secure
            ).forEach { info ->

                val slug =
                    info.slug
                        ?: return@forEach

                val href =
                    if (
                        slug.startsWith(
                            "http"
                        )
                    ) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                episodes +=
                    newEpisode(
                        href
                    ) {

                        this.name =
                            info.title
                                ?: if (
                                    info.episode != null
                                ) {
                                    "${info.episode}. Bölüm"
                                } else {
                                    "Bölüm"
                                }

                        this.season =
                            info.season

                        this.episode =
                            info.episode

                        this.description =
                            info.description

                        this.posterUrl =
                            info.poster
                    }
            }
        }

        // -----------------------------------------------------
        // HTML EPISODES
        // -----------------------------------------------------

        collectEpisodesFromHtml(
            document
        ).forEach { episode ->

            episodes +=
                episode
        }

        // -----------------------------------------------------
        // DEDUPE + SORT
        // -----------------------------------------------------

        val finalEpisodes =
            episodes
                .distinctBy {
                    it.data
                }
                .sortedWith(
                    compareBy(
                        { it.season ?: 0 },
                        { it.episode ?: 0 },
                        { it.name }
                    )
                )

        if (
            finalEpisodes.isEmpty()
        ) {

            throw ErrorLoadingException(
                "Dizilla: Hiç bölüm bulunamadı."
            )
        }

        val seasonCount =
            finalEpisodes
                .mapNotNull {
                    it.season
                }
                .distinct()
                .size

        Log.d(
            "Dizilla",
            "Bulunan bölüm = ${finalEpisodes.size}"
        )

        Log.d(
            "Dizilla",
            "Bulunan sezon = $seasonCount"
        )

        return newTvSeriesLoadResponse(
            title.trim(),
            url,
            TvType.TvSeries,
            finalEpisodes
        ) {

            this.posterUrl =
                poster

            this.plot =
                description

            this.year =
                year

            this.tags =
                tags

            if (
                score != null
            ) {

                this.score =
                    Score.from10(
                        score
                    )
            }

            addActors(
                actors
            )
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

        if (
            secure != null
        ) {

            findValueRecursive(
                secure,
                keys
            )?.let {

                val value =
                    it
                        .toString()
                        .trim()

                if (
                    value.isNotBlank() &&
                    value != "null"
                ) {

                    return value
                }
            }
        }

        val metaSelectors =
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

        for (
            selector in metaSelectors
        ) {

            val value =
                document
                    .selectFirst(
                        selector
                    )
                    ?.attr(
                        "content"
                    )
                    ?.trim()

            if (
                !value.isNullOrBlank()
            ) {

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

    // =========================================================
    // JSON EPISODES
    // =========================================================

    private fun collectEpisodesFromJson(
        json: JSONObject
    ): List<EpisodeInfo> {

        val output =
            mutableListOf<EpisodeInfo>()

        fun walk(
            value: Any?
        ) {

            when (value) {

                is JSONObject -> {

                    val season =
                        firstInt(
                            value,
                            "season_no",
                            "season",
                            "season_number"
                        )

                    val episode =
                        firstInt(
                            value,
                            "episode_no",
                            "episode",
                            "episode_number"
                        )

                    val slug =
                        firstString(
                            value,
                            "episode_slug",
                            "used_slug",
                            "episode_url",
                            "slug"
                        )

                    val title =
                        firstString(
                            value,
                            "episode_title",
                            "original_title",
                            "episode_name",
                            "post_title"
                        )

                    val description =
                        firstString(
                            value,
                            "episode_description",
                            "description",
                            "overview",
                            "plot"
                        )

                    val poster =
                        firstString(
                            value,
                            "episode_poster",
                            "poster_url",
                            "object_poster_url"
                        )

                    if (
                        (
                            season != null ||
                            episode != null
                        )
                        &&
                        !slug.isNullOrBlank()
                        &&
                        (
                            slug.contains(
                                "-sezon-",
                                ignoreCase = true
                            )
                            ||
                            slug.contains(
                                "-bolum-",
                                ignoreCase = true
                            )
                        )
                    ) {

                        val parsed =
                            parseSeasonEpisodeFromSlug(
                                slug
                            )

                        output +=
                            EpisodeInfo(
                                season
                                    ?: parsed.first,

                                episode
                                    ?: parsed.second,

                                slug,

                                title,

                                description,

                                poster
                            )
                    }

                    val keys =
                        value.keys()

                    while (
                        keys.hasNext()
                    ) {

                        val key =
                            keys.next()

                        walk(
                            value.opt(
                                key
                            )
                        )
                    }
                }

                is JSONArray -> {

                    for (
                        index in
                        0 until value.length()
                    ) {

                        walk(
                            value.opt(
                                index
                            )
                        )
                    }
                }
            }
        }

        walk(
            json
        )

        return output.distinctBy {
            "${it.season}|${it.episode}|${it.slug}"
        }
    }

    private fun firstInt(
        obj: JSONObject,
        vararg keys: String
    ): Int? {

        for (
            key in keys
        ) {

            val value =
                obj.opt(
                    key
                )

            when (value) {

                is Number ->
                    return value.toInt()

                is String -> {

                    value
                        .trim()
                        .toIntOrNull()
                        ?.let {
                            return it
                        }
                }
            }
        }

        return null
    }

    private fun parseSeasonEpisodeFromSlug(
        slug: String
    ): Pair<Int?, Int?> {

        val match =
            EPISODE_SLUG_REGEX.find(
                slug
            )

        return Pair(
            match
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull(),

            match
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
        )
    }

    // =========================================================
    // HTML EPISODES
    // =========================================================

    private fun collectEpisodesFromHtml(
        document: Document
    ): List<Episode> {

        val output =
            mutableListOf<Episode>()

        document
            .select(
                "a[href]"
            )
            .forEach { element ->

                val href =
                    fixUrlNull(
                        element.attr(
                            "href"
                        )
                    )
                        ?: return@forEach

                val slug =
                    href
                        .trimEnd('/')
                        .substringAfterLast('/')

                if (
                    !slug.contains(
                        "-sezon-",
                        ignoreCase = true
                    )
                    ||
                    !slug.contains(
                        "-bolum",
                        ignoreCase = true
                    )
                ) {

                    return@forEach
                }

                val parsed =
                    parseSeasonEpisodeFromSlug(
                        slug
                    )

                val season =
                    parsed.first
                        ?: return@forEach

                val episode =
                    parsed.second
                        ?: return@forEach

                val title =
                    element
                        .text()
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: "${episode}. Bölüm"

                output +=
                    newEpisode(
                        href
                    ) {

                        this.name =
                            title

                        this.season =
                            season

                        this.episode =
                            episode
                    }
            }

        return output.distinctBy {
            it.data
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

        Log.d(
            "Dizilla",
            "loadLinks = $data"
        )

        val episodeDocument =
            try {

                app.get(
                    data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).document

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "Episode page failed: ${e.message}"
                )

                return false
            }

        val secure =
            getSecureData(
                episodeDocument
            )

        if (
            secure != null
        ) {

            val sources =
                secure
                    .optJSONObject(
                        "RelatedResults"
                    )
                    ?.optJSONObject(
                        "getEpisodeSources"
                    )
                    ?.optJSONArray(
                        "result"
                    )
                    ?: secure
                        .optJSONObject(
                            "content"
                        )
                        ?.optJSONObject(
                            "result"
                        )
                        ?.optJSONObject(
                            "RelatedResults"
                        )
                        ?.optJSONObject(
                            "getEpisodeSources"
                        )
                        ?.optJSONArray(
                            "result"
                        )

            if (
                sources != null
            ) {

                var delivered =
                    false

                for (
                    index in
                    0 until sources.length()
                ) {

                    val item =
                        sources.optJSONObject(
                            index
                        )
                            ?: continue

                    val sourceContent =
                        firstString(
                            item,
                            "source_content"
                        )
                            ?: continue

                    val iframe =
                        extractIframeUrl(
                            sourceContent
                        )
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

                            append(
                                name
                            )

                            if (
                                !sourceName.isNullOrBlank()
                            ) {

                                append(
                                    " • "
                                )

                                append(
                                    sourceName
                                )
                            }

                            if (
                                !language.isNullOrBlank()
                            ) {

                                append(
                                    " • "
                                )

                                append(
                                    language
                                )
                            }

                            if (
                                !qualityName.isNullOrBlank()
                            ) {

                                append(
                                    " • "
                                )

                                append(
                                    qualityName
                                )
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

                            delivered =
                                true
                        }

                    } catch (e: Exception) {

                        Log.e(
                            "Dizilla",
                            "Source error: ${e.message}"
                        )
                    }
                }

                if (
                    delivered
                ) {
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

    // =========================================================
    // IFRAME URL
    // =========================================================

    private fun extractIframeUrl(
        sourceContent: String
    ): String? {

        try {

            val parsed =
                Jsoup.parse(
                    sourceContent
                )

            val src =
                parsed
                    .selectFirst(
                        "iframe"
                    )
                    ?.attr(
                        "src"
                    )
                    ?.trim()

            fixUrlNull(
                src
            )?.let {
                return it
            }

        } catch (_: Exception) {
        }

        val regex =
            Regex(
                """(?:https?:)?//([A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9+/=]+)"""
            )

        val match =
            regex.find(
                sourceContent
            )

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                "https://$it"
            }
    }

    // =========================================================
    // PLAYER
    // =========================================================

    private suspend fun extractFromIframe(
        iframeUrl: String,
        label: String,
        qualityName: String,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
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

        if (
            iframeHtml.isBlank()
        ) {
            return false
        }

        extractSubtitles(
            iframeHtml,
            subtitleCallback
        )

        val token =
            Regex(
                """window\.openPlayer\(['"]([^'"]+)['"]"""
            )
                .find(
                    iframeHtml
                )
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex(
                    """openPlayer\(['"]([^'"]+)['"]"""
                )
                    .find(
                        iframeHtml
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                ?: return false

        val host =
            iframeUrl
                .removePrefix(
                    "https://"
                )
                .removePrefix(
                    "http://"
                )
                .substringBefore(
                    "/"
                )

        if (
            host.isBlank()
        ) {
            return false
        }

        val source2Text =
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

                JSONObject(
                    source2Text
                )

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
            sourceJson.optJSONArray(
                "playlist"
            )
                ?: return false

        var delivered =
            false

        for (
            playlistIndex in
            0 until playlist.length()
        ) {

            val playlistItem =
                playlist.optJSONObject(
                    playlistIndex
                )
                    ?: continue

            val sources =
                playlistItem.optJSONArray(
                    "sources"
                )
                    ?: continue

            for (
                sourceIndex in
                0 until sources.length()
            ) {

                val source =
                    sources.optJSONObject(
                        sourceIndex
                    )
                        ?: continue

                val type =
                    source
                        .optString(
                            "type"
                        )
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
                        .optString(
                            "file"
                        )
                        .trim()

                if (
                    file.isBlank()
                ) {
                    continue
                }

                file =
                    file.replace(
                        "\\",
                        ""
                    )

                if (
                    file.startsWith(
                        "//"
                    )
                ) {

                    file =
                        "https:$file"

                } else if (
                    file.startsWith(
                        "/"
                    )
                ) {

                    file =
                        "https://$host$file"

                } else if (
                    !file.startsWith(
                        "http://"
                    )
                    &&
                    !file.startsWith(
                        "https://"
                    )
                ) {

                    file =
                        "https://$host/$file"
                }

                val masterUrl =
                    file.replace(
                        "m.php",
                        "master.m3u8"
                    )

                val quality =
                    Regex(
                        """\d{3,4}"""
                    )
                        .find(
                            qualityName
                        )
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

                        this.referer =
                            iframeUrl

                        this.headers =
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframeUrl
                            )

                        this.quality =
                            quality
                    }
                )

                try {

                    M3u8Helper
                        .generateM3u8(
                            label,
                            masterUrl,
                            iframeUrl
                        )
                        .forEach(
                            callback
                        )

                } catch (e: Exception) {

                    Log.d(
                        "Dizilla",
                        "M3U8 variant error: ${e.message}"
                    )
                }

                delivered =
                    true
            }
        }

        return delivered
    }

    // =========================================================
    // SUBTITLE
    // =========================================================

    private suspend fun extractSubtitles(
        html: String,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit
    ) {

        val regex =
            Regex(
                """"file":"([^"]+)","label":"([^"]+)""""
            )

        val seen =
            HashSet<String>()

        regex
            .findAll(
                html
            )
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
                    rawUrl.replace(
                        "\\",
                        ""
                    )

                if (
                    url.isBlank()
                    ||
                    !seen.add(
                        url
                    )
                ) {
                    return@forEach
                }

                val language =
                    rawLang
                        .replace(
                            "\\u0131",
                            "ı"
                        )
                        .replace(
                            "\\u0130",
                            "İ"
                        )
                        .replace(
                            "\\u00fc",
                            "ü"
                        )
                        .replace(
                            "\\u00e7",
                            "ç"
                        )
                        .replace(
                            "\\u00f6",
                            "ö"
                        )
                        .replace(
                            "\\u011f",
                            "ğ"
                        )
                        .replace(
                            "\\u015f",
                            "ş"
                        )
                        .replace(
                            "\\u00dc",
                            "Ü"
                        )
                        .replace(
                            "\\u00d6",
                            "Ö"
                        )
                        .replace(
                            "\\u00c7",
                            "Ç"
                        )
                        .replace(
                            "\\u011e",
                            "Ğ"
                        )
                        .replace(
                            "\\u015e",
                            "Ş"
                        )

                try {

                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = language,
                            url = fixUrl(
                                url
                            )
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
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        val iframe =
            document
                .select(
                    "iframe"
                )
                .firstOrNull {

                    val src =
                        it.attr(
                            "src"
                        )

                    src.contains(
                        "player",
                        ignoreCase = true
                    )
                    ||
                    src.contains(
                        "embed",
                        ignoreCase = true
                    )
                    ||
                    src.contains(
                        "watch",
                        ignoreCase = true
                    )
                }
                ?.attr(
                    "src"
                )

        val iframeUrl =
            fixUrlNull(
                iframe
            )
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
