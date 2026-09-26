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

        private val EPISODE_SLUG_REGEX =
            Regex(
                """(?:^|-)(\d+)-sezon-(\d+)-bolum""",
                RegexOption.IGNORE_CASE
            )

        /*
         * Örnek:
         *
         * reacher-izle-1-sezon-1-bolum
         *
         * -> reacher-izle
         */
        private val EPISODE_SUFFIX_REGEX =
            Regex(
                """-\d+-sezon-\d+-bolum(?:-[a-z0-9-]+)?$""",
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
    // SECURE DATA
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
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optString("secureData")
                    ?.takeIf {
                        it.isNotBlank()
                    }

            if (!direct.isNullOrBlank()) {

                decryptSecureData(
                    direct
                )?.let {
                    return it
                }
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

                while (iterator.hasNext()) {

                    val key =
                        iterator.next()

                    val child =
                        value.opt(key)

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

                for (
                    index in 0 until value.length()
                ) {

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

                val iterator =
                    value.keys()

                while (iterator.hasNext()) {

                    val key =
                        iterator.next()

                    val child =
                        value.opt(key)

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

                for (
                    index in 0 until value.length()
                ) {

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
    // ANA SAYFA
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
    // PAGINATION
    // =========================================================

    /*
     * Sayfa URL'sini oluştur.
     *
     * Öncelik:
     * ?page=2
     *
     * Daha sonra diğer yaygın biçimler.
     */
    private fun buildPaginationCandidates(
        baseUrl: String,
        page: Int
    ): List<String> {

        if (page <= 1) {
            return listOf(baseUrl)
        }

        val clean =
            baseUrl.trimEnd('/')

        val separator =
            if (clean.contains("?")) {
                "&"
            } else {
                "?"
            }

        return listOf(
            "$clean${separator}page=$page",
            "$clean${separator}paged=$page",
            "$clean${separator}sayfa=$page",
            "$clean${separator}p=$page",
            "$clean/page/$page",
            "$clean/sayfa/$page"
        ).distinct()
    }

    /*
     * Sayfa içindeki gerçek pagination bağlantısını bul.
     *
     * Bu fonksiyon yalnızca sayfa üzerinde gerçekten
     * mevcut olan bağlantıyı kullanır.
     */
    private fun findPaginationUrl(
        document: Document,
        page: Int
    ): String? {

        if (page <= 1) {
            return null
        }

        document
            .select("a[href]")
            .forEach { element ->

                val href =
                    element
                        .attr("href")
                        .trim()

                if (href.isBlank()) {
                    return@forEach
                }

                val text =
                    element
                        .text()
                        .trim()

                val aria =
                    element
                        .attr("aria-label")
                        .trim()

                val title =
                    element
                        .attr("title")
                        .trim()

                val normalizedHref =
                    href.lowercase()

                if (
                    normalizedHref.contains(
                        "page=$page"
                    ) ||
                    normalizedHref.contains(
                        "paged=$page"
                    ) ||
                    normalizedHref.contains(
                        "sayfa=$page"
                    ) ||
                    normalizedHref.contains(
                        "p=$page"
                    ) ||
                    normalizedHref.contains(
                        "/page/$page"
                    ) ||
                    normalizedHref.contains(
                        "/sayfa/$page"
                    )
                ) {

                    return fixUrlNull(href)
                }

                if (
                    text == page.toString() ||
                    aria == page.toString() ||
                    title == page.toString()
                ) {

                    return fixUrlNull(href)
                }
            }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        Log.d(
            "Dizilla",
            "getMainPage page=$page base=${request.data}"
        )

        /*
         * Sayfa 1.
         */
        if (page <= 1) {

            val document =
                try {

                    app.get(
                        request.data,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT
                        )
                    ).document

                } catch (e: Exception) {

                    Log.e(
                        "Dizilla",
                        "Main page hatası: ${e.message}"
                    )

                    return newHomePageResponse(
                        request.name,
                        emptyList(),
                        hasNext = false
                    )
                }

            val results =
                parseMainPageResults(
                    document
                )

            Log.d(
                "Dizilla",
                "Sayfa 1 sonuç=${results.size}"
            )

            /*
             * Burada özellikle TRUE bırakıyoruz.
             *
             * CloudStream böylece 2. sayfayı isteyebiliyor.
             */
            return newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        }

        /*
         * Önce mevcut sayfanın gerçek pagination linkini
         * bulmaya çalışıyoruz.
         *
         * Bulamazsak doğrudan ?page=N deniyoruz.
         */
        var targetUrls =
            mutableListOf<String>()

        try {

            val firstDocument =
                app.get(
                    request.data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).document

            findPaginationUrl(
                firstDocument,
                page
            )?.let {
                targetUrls.add(it)
            }

        } catch (e: Exception) {

            Log.d(
                "Dizilla",
                "Pagination linki okunamadı: ${e.message}"
            )
        }

        targetUrls.addAll(
            buildPaginationCandidates(
                request.data,
                page
            )
        )

        targetUrls =
            targetUrls
                .distinct()
                .toMutableList()

        var selectedResults =
            emptyList<SearchResponse>()

        var selectedUrl: String? = null

        for (
            candidate in targetUrls
        ) {

            try {

                Log.d(
                    "Dizilla",
                    "Pagination deneniyor: $candidate"
                )

                val document =
                    app.get(
                        candidate,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT
                        ),
                        referer = request.data
                    ).document

                val results =
                    parseMainPageResults(
                        document
                    )

                if (
                    results.isNotEmpty()
                ) {

                    /*
                     * Çok önemli:
                     *
                     * Eğer sayfa 2 isteği bize tekrar sayfa 1
                     * sonuçlarını veriyorsa onu kabul etmiyoruz.
                     *
                     * Bunun için sayfa 1'i karşılaştırıyoruz.
                     */
                    val firstPageResults =
                        if (page > 1) {

                            try {

                                app.get(
                                    request.data,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT
                                    )
                                ).document.let {
                                    parseMainPageResults(it)
                                }

                            } catch (_: Exception) {
                                emptyList()
                            }

                        } else {
                            emptyList()
                        }

                    val firstUrls =
                        firstPageResults
                            .map {
                                it.url
                            }
                            .toSet()

                    val differentResults =
                        results.filter {
                            it.url !in firstUrls
                        }

                    /*
                     * Eğer gerçekten farklı içerikler geldiyse
                     * bu sayfayı kabul ediyoruz.
                     */
                    if (
                        differentResults.isNotEmpty()
                    ) {

                        selectedResults =
                            results

                        selectedUrl =
                            candidate

                        break
                    }

                    /*
                     * Bazı sayfalarda içeriklerin bir kısmı
                     * önceki sayfayla aynı olabilir.
                     *
                     * Yine de sonuç sayısı yeterliyse kabul et.
                     */
                    if (
                        page == 2 &&
                        results.size >= 5
                    ) {

                        selectedResults =
                            results

                        selectedUrl =
                            candidate

                        break
                    }

                }

            } catch (e: Exception) {

                Log.d(
                    "Dizilla",
                    "Pagination başarısız: $candidate -> ${e.message}"
                )
            }
        }

        Log.d(
            "Dizilla",
            "page=$page url=$selectedUrl sonuç=${selectedResults.size}"
        )

        /*
         * Sonuç geldiyse CloudStream'e bir sonraki sayfayı
         * da denemesine izin veriyoruz.
         *
         * Bir sonraki istekte sonuç gelmezse zincir doğal olarak
         * duracaktır.
         */
        return newHomePageResponse(
            request.name,
            selectedResults,
            hasNext = selectedResults.isNotEmpty()
        )
    }

    // =========================================================
    // ANA SAYFA PARSE
    // =========================================================

    private fun parseMainPageResults(
        document: Document
    ): List<SearchResponse> {

        val results =
            LinkedHashMap<String, SearchResponse>()

        val secure =
            getSecureData(
                document
            )

        if (secure != null) {

            collectSeriesFromJson(
                secure
            ).forEach { item ->

                val slug =
                    item.slug
                        ?: return@forEach

                val href =
                    if (
                        slug.startsWith("http")
                    ) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                if (
                    !isSeriesSlug(href)
                ) {
                    return@forEach
                }

                val title =
                    item.title
                        ?: return@forEach

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

        /*
         * SecureData çalışmazsa HTML.
         */
        if (results.isEmpty()) {

            document
                .select(
                    "a[href*='/dizi/']"
                )
                .forEach { element ->

                    val item =
                        element.toSearchResponse()
                            ?: return@forEach

                    results[item.url] =
                        item
                }
        }

        return results.values.toList()
    }

    private fun isSeriesSlug(
        url: String
    ): Boolean {

        val slug =
            url
                .trimEnd('/')
                .substringAfterLast('/')

        if (slug.isBlank()) {
            return false
        }

        return !slug.contains(
            "-sezon-",
            ignoreCase = true
        ) &&
        !slug.contains(
            "-bolum-",
            ignoreCase = true
        )
    }

    // =========================================================
    // SERIES MODEL
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
                            "object_name"
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
                            "object_poster_url"
                        )

                    if (
                        !title.isNullOrBlank() &&
                        !slug.isNullOrBlank() &&
                        !slug.contains(
                            "-sezon-",
                            ignoreCase = true
                        ) &&
                        !slug.contains(
                            "-bolum-",
                            ignoreCase = true
                        )
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

                    while (keys.hasNext()) {

                        val key =
                            keys.next()

                        walk(
                            value.opt(key)
                        )
                    }
                }

                is JSONArray -> {

                    for (
                        index in 0 until value.length()
                    ) {

                        walk(
                            value.opt(index)
                        )
                    }
                }
            }
        }

        walk(json)

        return output.distinctBy {
            it.slug
        }
    }

    private fun firstString(
        obj: JSONObject,
        vararg keys: String
    ): String? {

        for (key in keys) {

            val value =
                obj.opt(key)

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

    private fun Element.toSearchResponse():
        SearchResponse? {

        val href =
            fixUrlNull(
                attr("href")
            )
                ?: return null

        val title =
            selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: selectFirst("h3")
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: attr("title")
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: selectFirst("img")
                    ?.attr("alt")
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
                image.attr("data-image")
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
                .optString("response")
                .trim()

        val json =
            decryptSecureData(
                encrypted
            )
                ?: return emptyList()

        val result =
            json.optJSONArray("result")
                ?: return emptyList()

        val output =
            mutableListOf<SearchResponse>()

        for (
            index in 0 until result.length()
        ) {

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
                if (
                    slug.startsWith("http")
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

        val currentSeriesSlug =
            url
                .trimEnd('/')
                .substringAfterLast('/')
                .trim()

        Log.d(
            "Dizilla",
            "Current series slug=$currentSeriesSlug"
        )

        // =====================================================
        // METADATA
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
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                ?: currentSeriesSlug
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
                ?: extractPoster(document)

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
            yearText
                ?.let {
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

        if (secure != null) {

            collectEpisodesFromJson(
                secure,
                currentSeriesSlug
            ).forEach { info ->

                val slug =
                    info.slug
                        ?: return@forEach

                val href =
                    if (
                        slug.startsWith("http")
                    ) {
                        slug
                    } else {
                        "$mainUrl/${slug.trimStart('/')}"
                    }

                episodes +=
                    newEpisode(href) {

                        this.name =
                            info.title
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: info.episode
                                    ?.let {
                                        "$it. Bölüm"
                                    }
                                ?: "Bölüm"

                        this.season =
                            info.season

                        this.episode =
                            info.episode

                        this.description =
                            info.description

                        /*
                         * Yalnızca gerçek episode poster.
                         */
                        this.posterUrl =
                            info.poster
                    }
            }
        }

        /*
         * HTML fallback.
         */
        collectEpisodesFromHtml(
            document,
            currentSeriesSlug
        ).forEach { episode ->

            /*
             * JSON'da bulunan aynı bölümü tekrar ekleme.
             */
            if (
                episodes.none {
                    it.data == episode.data
                }
            ) {
                episodes += episode
            }
        }

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

        if (finalEpisodes.isEmpty()) {

            throw ErrorLoadingException(
                "Dizilla: Hiç bölüm bulunamadı."
            )
        }

        Log.d(
            "Dizilla",
            "Bulunan bölüm sayısı=${finalEpisodes.size}"
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

            if (score != null) {

                this.score =
                    Score.from10(score)
            }

            this.duration =
                null

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

    // =========================================================
    // NORMALIZE SERIES SLUG
    // =========================================================

    private fun normalizeSlug(
        value: String
    ): String {

        return value
            .trim()
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase()
            .replace(
                "_",
                "-"
            )
            .replace(
                Regex("""-+"""),
                "-"
            )
            .trim('-')
    }

    // =========================================================
    // EPISODE FILTER
    // =========================================================

    private fun isEpisodeForSeries(
        episodeSlug: String,
        seriesSlug: String
    ): Boolean {

        val cleanEpisode =
            normalizeSlug(
                episodeSlug
            )

        val cleanSeries =
            normalizeSlug(
                seriesSlug
            )

        if (
            cleanEpisode.isBlank() ||
            cleanSeries.isBlank()
        ) {
            return false
        }

        /*
         * Gerçek bölüm URL'si olmalı.
         */
        val match =
            EPISODE_SLUG_REGEX.find(
                cleanEpisode
            )
                ?: return false

        /*
         * Sezon/bölüm sonrasını kaldır.
         */
        val episodeBase =
            cleanEpisode
                .substring(
                    0,
                    match.range.first
                )
                .trimEnd('-')

        if (
            episodeBase.isBlank()
        ) {
            return false
        }

        /*
         * En güvenli kontrol:
         *
         * reacher-izle
         * reacher-izle-1-sezon-1-bolum
         */
        if (
            episodeBase == cleanSeries
        ) {
            return true
        }

        /*
         * SEO eki varsa:
         *
         * reacher-izle-dizi
         * reacher-izle-1-sezon-1-bolum
         */
        if (
            episodeBase.startsWith(
                "$cleanSeries-"
            )
        ) {
            return true
        }

        /*
         * Ters eşleşmeyi yalnızca uzun sluglarda kullan.
         */
        if (
            cleanSeries.length >= 8 &&
            cleanSeries.startsWith(
                "$episodeBase-"
            )
        ) {
            return true
        }

        return false
    }

    // =========================================================
    // EPISODES FROM JSON
    // =========================================================

    private fun collectEpisodesFromJson(
        json: JSONObject,
        currentSeriesSlug: String
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
                            "slug",
                            "episode_url"
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

                    /*
                     * Burada object_poster_url YOK.
                     *
                     * Bu alan başka dizinin posterini
                     * bölüme taşıyabiliyordu.
                     */
                    val poster =
                        firstString(
                            value,
                            "episode_poster",
                            "episode_poster_url",
                            "episode_image",
                            "episode_image_url"
                        )

                    if (
                        !slug.isNullOrBlank() &&
                        (
                            season != null ||
                            episode != null
                        ) &&
                        isEpisodeForSeries(
                            slug,
                            currentSeriesSlug
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

                    while (keys.hasNext()) {

                        val key =
                            keys.next()

                        walk(
                            value.opt(key)
                        )
                    }
                }

                is JSONArray -> {

                    for (
                        index in 0 until value.length()
                    ) {

                        walk(
                            value.opt(index)
                        )
                    }
                }
            }
        }

        walk(json)

        return output
            .filter {
                it.season != null &&
                it.episode != null &&
                !it.slug.isNullOrBlank()
            }
            .distinctBy {
                "${it.season}|${it.episode}|${normalizeSlug(it.slug!!)}"
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
            EPISODE_SLUG_REGEX.find(slug)

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
    // EPISODES FROM HTML
    // =========================================================

    private fun collectEpisodesFromHtml(
        document: Document,
        currentSeriesSlug: String
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
                        element.attr("href")
                    )
                        ?: return@forEach

                val rawSlug =
                    href
                        .trimEnd('/')
                        .substringAfterLast('/')

                /*
                 * Önce gerçek episode URL'si mi?
                 */
                if (
                    !EPISODE_SLUG_REGEX
                        .containsMatchIn(rawSlug)
                ) {
                    return@forEach
                }

                /*
                 * Açılan diziyle aynı mı?
                 *
                 * Böylece The Scandal / Reacher /
                 * Law & Order gibi başka içerikler
                 * bölümlere karışmaz.
                 */
                if (
                    !isEpisodeForSeries(
                        rawSlug,
                        currentSeriesSlug
                    )
                ) {
                    return@forEach
                }

                val parsed =
                    parseSeasonEpisodeFromSlug(
                        rawSlug
                    )

                val season =
                    parsed.first
                        ?: return@forEach

                val episode =
                    parsed.second
                        ?: return@forEach

                /*
                 * HTML'de bazen:
                 *
                 * "Reacher izle"
                 *
                 * gibi dizi başlığı linkin içine
                 * yazılabiliyor.
                 *
                 * Bölüm numarası olan metin varsa kullan,
                 * yoksa doğrudan X. Bölüm yaz.
                 */
                val rawTitle =
                    element
                        .text()
                        .trim()

                val title =
                    if (
                        rawTitle.isBlank() ||
                        rawTitle.length > 80 ||
                        rawTitle.equals(
                            currentSeriesSlug.replace(
                                "-",
                                " "
                            ),
                            ignoreCase = true
                        )
                    ) {
                        "$episode. Bölüm"
                    } else {
                        rawTitle
                    }

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

                        /*
                         * HTML'den poster taşımıyoruz.
                         *
                         * Böylece yanlış dizi posterleri
                         * episode listesine bulaşmıyor.
                         */
                        this.posterUrl =
                            null
                    }
            }

        return output
            .distinctBy {
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
            "loadLinks=$data"
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

        if (secure != null) {

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

            if (sources != null) {

                var delivered =
                    false

                for (
                    index in 0 until sources.length()
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

                            append(name)

                            if (
                                !sourceName.isNullOrBlank()
                            ) {
                                append(" • ")
                                append(sourceName)
                            }

                            if (
                                !language.isNullOrBlank()
                            ) {
                                append(" • ")
                                append(language)
                            }

                            if (
                                !qualityName.isNullOrBlank()
                            ) {
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

    // =========================================================
    // IFRAME
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

        if (iframeHtml.isBlank()) {
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
                .find(iframeHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex(
                    """openPlayer\(['"]([^'"]+)['"]"""
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
            sourceJson.optJSONArray(
                "playlist"
            )
                ?: return false

        var delivered =
            false

        for (
            playlistIndex in 0 until playlist.length()
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
                sourceIndex in 0 until sources.length()
            ) {

                val source =
                    sources.optJSONObject(
                        sourceIndex
                    )
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
                    file.replace(
                        "\\",
                        ""
                    )

                if (
                    file.startsWith("//")
                ) {

                    file =
                        "https:$file"

                } else if (
                    file.startsWith("/")
                ) {

                    file =
                        "https://$host$file"

                } else if (
                    !file.startsWith("http://") &&
                    !file.startsWith("https://")
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

                delivered = true
            }
        }

        return delivered
    }

    // =========================================================
    // SUBTITLES
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
                    rawUrl.replace(
                        "\\",
                        ""
                    )

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
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
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
