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

    override var mainUrl = "https://dizilla.la"
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

    /**
     * Resolve relative paths against the current site host and migrate links
     * saved with the former Dizilla domains. External player hosts stay intact.
     */
    private fun canonicalizeDizillaUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null

        val cleaned =
            rawUrl
                .trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")

        val resolved =
            fixUrlNull(cleaned)
                ?: return null

        val legacyHost =
            Regex("""(?i)^https?://(?:www\.)?dizilla\.(?:now|club)(?=[:/]|$)""")

        return legacyHost.replaceFirst(
            resolved,
            mainUrl.trimEnd('/')
        )
    }

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

        private const val MAX_MAIN_RESULTS = 24
        private const val MAX_JSON_SEARCH_DEPTH = 32

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
                try {
                    Base64.decode(clean, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP)
                }

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

        val clean =
            encrypted
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")
                .replace("\\/", "/")

        // Some site revisions expose this payload as plain JSON.
        try {
            return JSONObject(clean)
        } catch (_: Exception) {
        }

        // Also accept a Base64-wrapped JSON payload before trying AES.
        val decodedPayload =
            try {
                String(
                    Base64.decode(clean, Base64.DEFAULT),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                try {
                    String(
                        Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP),
                        Charsets.UTF_8
                    )
                } catch (_: Exception) {
                    ""
                }
            }

        if (decodedPayload.isNotBlank()) {
            try {
                return JSONObject(decodedPayload)
            } catch (_: Exception) {
            }
        }

        decryptWithKey(
            clean,
            aesKey
        )?.let { return it }

        return decryptWithKey(
            clean,
            STATIC_AES_KEY.toByteArray(Charsets.UTF_8)
        )
    }

    private fun getNextData(
        document: Document
    ): String? {
        val preferred =
            document
                .selectFirst("script#__NEXT_DATA__")
                ?.let { script ->
                    script.data().takeIf { it.isNotBlank() }
                        ?: script.html().takeIf { it.isNotBlank() }
                }
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (!preferred.isNullOrBlank()) {
            return preferred
        }

        val scripts =
            document
                .select("script")
                .mapNotNull { script ->
                    val content =
                        script.data().takeIf { it.isNotBlank() }
                            ?: script.html().takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null

                    content.trim().takeIf { it.isNotBlank() }
                }

        return scripts.firstOrNull {
            it.contains("secureData", ignoreCase = true)
        } ?: scripts.firstOrNull {
            val value = it.trimStart()
            value.startsWith("{") || value.startsWith("[")
        }
    }

    private fun getStructuredData(
        document: Document
    ): JSONObject? {
        val graph = JSONArray()

        document
            .select("script[type='application/ld+json']")
            .forEach { script ->
                val raw =
                    script.data().takeIf { it.isNotBlank() }
                        ?: script.html().takeIf { it.isNotBlank() }
                        ?: return@forEach

                val value = raw.trim()
                if (value.isBlank()) return@forEach

                try {
                    graph.put(JSONObject(value))
                } catch (_: Exception) {
                    try {
                        graph.put(JSONArray(value))
                    } catch (_: Exception) {
                    }
                }
            }

        if (graph.length() == 0) return null

        return try {
            JSONObject().put("@graph", graph)
        } catch (_: Exception) {
            null
        }
    }

    private fun combineMetadata(
        primary: JSONObject?,
        structured: JSONObject?
    ): JSONObject? {
        if (primary == null) return structured
        if (structured == null) return primary

        return try {
            JSONObject()
                .put("primary", primary)
                .put("structured", structured)
        } catch (_: Exception) {
            primary
        }
    }

    private fun getSecureData(
        document: Document
    ): JSONObject? {

        val nextData =
            getNextData(document)
                ?: return null

        /*
         * Önce büyük __NEXT_DATA__ JSON'unu JSONObject'e
         * çevirmeden secureData string'ini doğrudan bul.
         * TV Box'ta gereksiz büyük obje ağacı oluşturulmasını
         * engeller.
         */
        try {

            val directRegex =
                Regex(
                    """[\"]secureData[\"]\s*:\s*[\"]([^\"]+)[\"]"""
                )

            val directMatch =
                directRegex.find(nextData)

            val direct =
                directMatch
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?.takeIf { it.isNotBlank() }

            if (!direct.isNullOrBlank()) {
                decryptSecureData(direct)?.let { return it }
            }

        } catch (e: Exception) {

            Log.d(
                "Dizilla",
                "secureData hızlı arama başarısız: ${e.message}"
            )
        }

        /*
         * Fallback: yalnızca hızlı arama başarısızsa JSON oluştur.
         */
        return try {
            val nextJson = JSONObject(nextData)

            when (
                val secureValue =
                    findValueRecursive(
                        nextJson,
                        setOf("secureData")
                    )
            ) {
                is JSONObject -> secureValue
                is JSONArray -> JSONObject().put("result", secureValue)
                is String -> decryptSecureData(secureValue)
                else -> {
                    val encrypted =
                        findStringRecursive(
                            nextJson,
                            setOf("secureData")
                        )

                    encrypted?.let { decryptSecureData(it) }
                }
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
        keys: Set<String>,
        depth: Int = 0
    ): String? {

        if (depth > MAX_JSON_SEARCH_DEPTH) {
            return null
        }

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
                            keys,
                            depth + 1
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
                            keys,
                            depth + 1
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
        keys: Set<String>,
        depth: Int = 0
    ): Any? {

        if (depth > MAX_JSON_SEARCH_DEPTH) {
            return null
        }

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
                            keys,
                            depth + 1
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
                            keys,
                            depth + 1
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
            (canonicalizeDizillaUrl(requestData) ?: requestData)
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
            collectHtmlSeries(document, ".new-added-list")
                .forEach { item ->
                    results[item.url] = item
                }

            Log.d(
                "Dizilla",
                "Yeni Eklenen Diziler HTML kart sayısı = ${results.size}"
            )

            // Eski kart kapsayıcısı değişmişse genel HTML/JSON
            // yedeklerine geç; boş listeyi burada döndürme.
            if (results.isNotEmpty()) {
                return results.values.take(24).toList()
            }
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
                    canonicalizeDizillaUrl(slug)
                        ?: return@forEach

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
                        this.posterUrl =
                            canonicalizeDizillaUrl(item.poster) ?: item.poster
                    }
            }
        }

        if (results.isEmpty()) {
            collectHtmlSeries(document)
                .forEach { item ->
                    results[item.url] = item
                }

            Log.d(
                "Dizilla",
                "Genel HTML dizi kartı sayısı = ${results.size}"
            )
        }

        return results.values
            .take(24)
            .toList()
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

        val requestData =
            canonicalizeDizillaUrl(request.data)
                ?: request.data

        val candidates =
            buildPageCandidates(
                requestData,
                page
            )

        val previousSignature =
            paginationSignatures[requestData]

        var selectedResults =
            emptyList<SearchResponse>()

        var selectedUrl =
            request.data

        for (candidate in candidates) {

            val document =
                try {
                    val response =
                        app.get(
                            candidate,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                                "Referer" to "$mainUrl/"
                            ),
                            referer = "$mainUrl/"
                        )

                    val parsedDocument = response.document
                    Log.d(
                        "Dizilla",
                        "Page response status=${response.code} bytes=${response.text.length} " +
                            "title=${parsedDocument.title()} " +
                            "body=${parsedDocument.body().text().take(140)}"
                    )
                    parsedDocument
                } catch (e: Exception) {
                    Log.d(
                        "Dizilla",
                        "Pagination candidate failed: $candidate -> ${e.message}"
                    )
                    continue
                }

            /*
             * ÖNEMLİ:
             * candidate sayfa 2/3 URL'si olsa bile parser'a
             * her zaman orijinal request.data veriyoruz.
             * Böylece /arsiv + query parametresi hâlâ
             * .new-added-list filtresini kullanıyor.
             */
            val results =
                extractMainPageResults(
                    document,
                    requestData
                )

            Log.d(
                "Dizilla",
                "Page $page candidate=$candidate results=${results.size}"
            )

            if (results.isEmpty()) {
                continue
            }

            val signature =
                pageSignature(results)

            /*
             * Sayfa 2 için site aynı 24 kartı döndürürse
             * bu adayı kabul etmiyoruz; sonraki olası pagination
             * biçimini deniyoruz.
             */
            if (
                page > 1 &&
                previousSignature != null &&
                signature == previousSignature
            ) {
                Log.d(
                    "Dizilla",
                    "Aday aynı 24 kartı döndürdü, sonraki aday deneniyor: $candidate"
                )
                continue
            }

            selectedResults =
                results.take(24)

            selectedUrl =
                candidate

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

        paginationSignatures[requestData] =
            signature

        Log.d(
            "Dizilla",
            "Page $page selected=$selectedUrl count=${selectedResults.size}"
        )

        return newHomePageResponse(
            request.name,
            selectedResults,
            hasNext = selectedResults.size >= 24
        )
    }

    private data class SeriesInfo(
        val title: String?,
        val slug: String?,
        val poster: String?
    )

    private fun normalizeMenuText(
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
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?: ""
    }

    private val menuFilterNames = setOf(
        "seçimi özelleştir",
        "filtrele",
        "yeni eklenenler",
        "imdb 6.9 üstü",
        "yerli diziler",
        "aile",
        "aksiyon",
        "animasyon",
        "bilim kurgu",
        "dram",
        "fantastik",
        "gerilim",
        "gizem",
        "komedi",
        "korku",
        "macera",
        "romantik",
        "savaş",
        "suç",
        "western",
        "amerika",
        "çin",
        "endonezya",
        "filipinler",
        "fransa",
        "güney kore",
        "hindistan",
        "ingiltere",
        "ispanya",
        "japonya",
        "kore",
        "malezya",
        "tayland",
        "tayvan",
        "türkiye"
    )

    private fun isMenuFilterName(
        title: String?
    ): Boolean {
        return normalizeMenuText(title) in
            menuFilterNames.map { normalizeMenuText(it) }.toSet()
    }

    private fun collectSeriesFromJson(
        json: JSONObject
    ): List<SeriesInfo> {

        val output =
            mutableListOf<SeriesInfo>()

        val seen =
            HashSet<String>()

        fun walk(value: Any?) {

            if (output.size >= MAX_MAIN_RESULTS) {
                return
            }

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

                        val key =
                            slug.trim()

                        if (seen.add(key)) {
                            output +=
                                SeriesInfo(
                                    title,
                                    slug,
                                    poster?.replace("\\/", "/")
                                )
                        }
                    }

                    if (output.size >= MAX_MAIN_RESULTS) {
                        return
                    }

                    val keys = value.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        walk(value.opt(key))

                        if (output.size >= MAX_MAIN_RESULTS) {
                            return
                        }
                    }
                }

                is JSONArray -> {

                    for (index in 0 until value.length()) {
                        walk(value.opt(index))

                        if (output.size >= MAX_MAIN_RESULTS) {
                            return
                        }
                    }
                }
            }
        }

        walk(json)

        return output.take(MAX_MAIN_RESULTS)
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

    private fun isSeriesDetailUrl(
        rawUrl: String?
    ): Boolean {
        if (rawUrl.isNullOrBlank()) return false

        val fixed =
            canonicalizeDizillaUrl(rawUrl)
                ?: return false

        return fixed.startsWith(mainUrl.trimEnd('/') + "/", ignoreCase = true) &&
            fixed.contains("/dizi/", ignoreCase = true)
    }

    private fun collectHtmlSeries(
        document: Document,
        containerSelector: String? = null
    ): List<SearchResponse> {
        val selector =
            if (containerSelector.isNullOrBlank()) {
                "a[href], [data-href], [data-url]"
            } else {
                "$containerSelector a[href], " +
                    "$containerSelector [data-href], " +
                    "$containerSelector [data-url]"
            }

        val output = LinkedHashMap<String, SearchResponse>()

        document.select(selector).forEach { element ->
            val rawHref =
                element.attr("href").takeIf { it.isNotBlank() }
                    ?: element.attr("data-href").takeIf { it.isNotBlank() }
                    ?: element.attr("data-url").takeIf { it.isNotBlank() }
                    ?: element.selectFirst("a[href]")?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                    ?: return@forEach

            if (!isSeriesDetailUrl(rawHref)) return@forEach

            val item =
                element.toSearchResponse(rawHref)
                    ?: return@forEach

            output.putIfAbsent(item.url, item)
            if (output.size >= MAX_MAIN_RESULTS) return@forEach
        }

        return output.values.toList()
    }

    private fun Element.toSearchResponse(
        candidateHref: String? = null
    ): SearchResponse? {
        val rawHref =
            candidateHref?.takeIf { it.isNotBlank() }
                ?: attr("href").takeIf { it.isNotBlank() }
                ?: attr("data-href").takeIf { it.isNotBlank() }
                ?: attr("data-url").takeIf { it.isNotBlank() }
                ?: selectFirst("a[href]")?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val href =
            canonicalizeDizillaUrl(rawHref)
                ?: return null

        val title =
            selectFirst("h1, h2, h3, h4, .title, .name, .card-title, [data-title]")
                ?.let { heading ->
                    heading.attr("data-title").takeIf { it.isNotBlank() }
                        ?: heading.text().trim().takeIf { it.isNotBlank() }
                }
                ?: attr("data-title").trim().takeIf { it.isNotBlank() }
                ?: attr("title").trim().removeSuffix(" izle").trim()
                    .takeIf { it.isNotBlank() }
                ?: attr("aria-label").trim().takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("alt")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: text().replace(Regex("""\s+"""), " ").trim()
                    .takeIf { it.isNotBlank() && it.length <= 120 }
                ?: return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = extractPoster(this@toSearchResponse)
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
                canonicalizeDizillaUrl(candidate)

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

            return canonicalizeDizillaUrl(first)
        }

        return null
    }

    // =========================================================
    // SEARCH
    // =========================================================

    private fun matchesSearchQuery(query: String, title: String): Boolean {
        fun normalize(value: String): String {
            val lowered = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        val ignored = setOf("the", "a", "an", "of", "and", "or", "ve")
        val year = Regex("(?:18|19|20|21)\\d{2}")
        val words = q.split(' ').filterNot { it in ignored || year.matches(it) }
        if (words.isEmpty()) return q == t
        val titleWords = t.split(' ').toSet()
        val numberWords = mapOf("0" to "zero", "1" to "one", "2" to "two", "3" to "three",
            "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight",
            "9" to "nine", "10" to "ten")
        return words.all { word ->
            val mapped = numberWords[word]
            titleWords.any { candidate ->
                candidate == word || (mapped != null && candidate == mapped) ||
                    (candidate.length >= 4 && word.length >= 4 &&
                        kotlin.math.abs(candidate.length - word.length) <= 2 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded =
            URLEncoder.encode(
                cleanQuery,
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

        if (!response.isSuccessful) {
            Log.w("Dizilla", "Search HTTP status=${response.code}")
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
                canonicalizeDizillaUrl(slug)
                    ?: continue

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
                        canonicalizeDizillaUrl(poster) ?: poster
                }
        }

        return output.distinctBy { it.url }.filter { matchesSearchQuery(cleanQuery, it.name) }
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
        val seriesUrl =
            canonicalizeDizillaUrl(url)
                ?: url

        Log.d(
            "Dizilla",
            "Current series slug=${seriesUrl.trimEnd('/').substringAfterLast('/')}"
        )

        val document =
            app.get(
                seriesUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                referer = "$mainUrl/"
            ).document

        val secure =
            combineMetadata(
                getSecureData(document),
                getStructuredData(document)
            )

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
                ?: seriesUrl
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
                    "poster",
                    "image_url",
                    "thumbnailUrl",
                    "image"
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
                    "datePublished",
                    "startDate",
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
                    "ratingValue",
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
                    canonicalizeDizillaUrl(slug)
                        ?: return@forEach

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
                        this.posterUrl = canonicalizeDizillaUrl(info.poster) ?: info.poster
                    }
            }
        }

        /*
         * secureData içinde bölüm bulunamadıysa
         * __NEXT_DATA__'yı ikinci kaynak olarak kullan.
         * İkisini aynı anda taramıyoruz; TV Box yükü azalıyor.
         */
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
                            canonicalizeDizillaUrl(slug)
                                ?: return@forEach

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
                                this.posterUrl = canonicalizeDizillaUrl(info.poster) ?: info.poster
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

        /*
         * HTML taraması sadece JSON kaynaklarında hiç bölüm
         * bulunamazsa çalışıyor. Sayfanın tamamını regex ile
         * taramıyoruz; yalnızca muhtemel bölüm linklerini seçiyoruz.
         */
        if (episodes.isEmpty()) {

            val htmlEpisodes =
                collectEpisodesFromHtml(document)

            Log.d(
                "Dizilla",
                "HTML episode count = ${htmlEpisodes.size}"
            )

            episodes += htmlEpisodes
        }

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
            seriesUrl,
            TvType.TvSeries,
            finalEpisodes
        ) {

            this.posterUrl =
                canonicalizeDizillaUrl(poster) ?: poster
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

    private fun metadataValueToString(
        value: Any?
    ): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() }
            is Number -> value.toString()
            is JSONObject -> {
                listOf("url", "contentUrl", "src", "name", "value")
                    .firstNotNullOfOrNull { key ->
                        val nested = value.opt(key)
                        if (nested == null || nested == JSONObject.NULL) {
                            null
                        } else {
                            metadataValueToString(nested)
                        }
                    }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nested = metadataValueToString(value.opt(index))
                    if (!nested.isNullOrBlank()) return nested
                }
                null
            }
            else -> null
        }
    }

    private fun firstMetadata(
        document: Document,
        secure: JSONObject?,
        keys: Set<String>
    ): String? {

        if (secure != null) {
            findValueRecursive(
                secure,
                keys
            )?.let { rawValue ->
                metadataValueToString(rawValue)?.let { value ->
                    if (
                        value.isNotBlank() &&
                        value != "null"
                    ) {
                        return value
                    }
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
                .lowercase()

        val patterns =
            listOf(

                Regex(
                    """(?:^|[-_/])(?:season|sezon)[-_/]?(\d{1,2})[-_/]+(?:episode|bolum|ep)[-_/]?(\d{1,3})(?:[-_/]|$)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """(?:^|[-_/])s(\d{1,2})[-_/]?e(\d{1,3})(?:[-_/]|$)""",
                    RegexOption.IGNORE_CASE
                ),

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
                canonicalizeDizillaUrl(href)
                    ?: return

            val parsed =
                parseSeasonEpisodeFromSlug(fixed)

            fun readDataNumber(attribute: String): Int? {
                return element?.attr(attribute)?.toIntOrNull()
                    ?: element?.selectFirst("[$attribute]")?.attr(attribute)?.toIntOrNull()
                    ?: element?.parent()?.attr(attribute)?.toIntOrNull()
            }

            val season =
                parsed.first
                    ?: readDataNumber("data-season")
                    ?: readDataNumber("data-season-number")
                    ?: return

            val episode =
                parsed.second
                    ?: readDataNumber("data-episode")
                    ?: readDataNumber("data-episode-number")
                    ?: return

            val title =
                element
                    ?.selectFirst("h2, h3, h4")
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
                    ?: element
                        ?.attr("aria-label")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: element
                        ?.text()
                        ?.replace(Regex("""\s+"""), " ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && it.length <= 180 }
                    ?: "${episode}. Bölüm"

            val image =
                element?.selectFirst("img")

            val episodePoster =
                image?.let {
                    listOf(
                        it.attr("src"),
                        it.attr("data-src"),
                        it.attr("data-lazy-src"),
                        it.attr("data-original"),
                        it.attr("data-image")
                    ).firstNotNullOfOrNull { candidate ->
                        canonicalizeDizillaUrl(candidate)?.takeIf { url -> url.isNotBlank() }
                    }
                }

            val episodeDescription =
                element
                    ?.selectFirst(".episode-description, .description, .desc, p")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

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
                    this.posterUrl = episodePoster
                    this.description = episodeDescription
                }
        }

        /*
         * Yalnızca bölüm biçimine benzeyen linkleri seçiyoruz.
         * Sayfanın tamamını document.html() ile regex taramıyoruz.
         */
        document
            .select(
                "a[href*='-sezon-'][href*='-bolum'], " +
                    "a[href*='sezon-'][href*='bolum-'], " +
                    "a[href*='season'][href*='episode'], " +
                    "a[href*='/episode/'], a[href*='/bolum/'], " +
                    "[data-href*='-sezon-'][data-href*='-bolum'], " +
                    "[data-url*='-sezon-'][data-url*='-bolum'], " +
                    "[data-season][data-episode]"
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
                            .selectFirst("a[href]")
                            ?.attr("href")
                            ?.takeIf { it.isNotBlank() }

                addEpisode(
                    href,
                    element
                )
            }

        return output.distinctBy { it.data }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================


    private suspend fun tryDizillaExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed =
            canonicalizeDizillaUrl(
                url.trim()
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
            ) ?: return false

        return try {
            val found =
                loadExtractor(
                    fixed,
                    referer,
                    subtitleCallback
                ) {
                    callback(it)
                }

            Log.d("Dizilla", "Yerleşik extractor url=" + fixed + " found=" + found)
            found
        } catch (e: Exception) {
            Log.w("Dizilla", "Yerleşik extractor başarısız url=" + fixed + ": " + e.message)
            false
        }
    }

    private fun collectPlayerIframes(
        document: Document
    ): List<String> {
        val output = LinkedHashSet<String>()

        document
            .select(
                "#playerLsDizilla iframe[src], " +
                    "#playerLsDizilla iframe[data-src], " +
                    "#playerLsDizilla iframe[data-lazy-src], " +
                    "iframe[src], iframe[data-src], iframe[data-lazy-src]"
            )
            .forEach { frame ->
                val raw =
                    frame.attr("data-src").takeIf { it.isNotBlank() }
                        ?: frame.attr("data-lazy-src").takeIf { it.isNotBlank() }
                        ?: frame.attr("src").takeIf { it.isNotBlank() }
                        ?: return@forEach

                canonicalizeDizillaUrl(raw)?.let { output.add(it) }
            }

        return output.toList()
    }

    private suspend fun loadLegacyDizillaPlayers(
        document: Document,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = HashSet<String>()
        var delivered = false

        // Older Dizilla pages expose alternate players as links.
        val playerLinks =
            document.select(
                "a[href*='player'], a[data-href*='player'], a[data-url*='player']"
            )

        for (element in playerLinks) {
            val raw =
                element.attr("href").takeIf { it.isNotBlank() }
                    ?: element.attr("data-href").takeIf { it.isNotBlank() }
                    ?: element.attr("data-url").takeIf { it.isNotBlank() }
                    ?: continue

            val playerUrl = canonicalizeDizillaUrl(raw) ?: continue
            if (!visited.add(playerUrl)) continue

            val playerDocument =
                try {
                    val response =
                        app.get(
                            playerUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to episodeUrl
                            ),
                            referer = episodeUrl
                        )

                    Log.d(
                        "Dizilla",
                        "Player alternative status=" + response.code + " url=" + playerUrl
                    )
                    response.document
                } catch (e: Exception) {
                    Log.w(
                        "Dizilla",
                        "Player alternative failed url=" + playerUrl + ": " + e.message
                    )
                    null
                }

            if (playerDocument != null) {
                val frames = collectPlayerIframes(playerDocument)
                for (frame in frames) {
                    if (!visited.add(frame)) continue
                    if (
                        tryDizillaExtractor(
                            frame,
                            playerUrl,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        delivered = true
                    }
                }
            }

            // Some alternatives point directly to a supported player.
            if (
                !delivered &&
                tryDizillaExtractor(
                    playerUrl,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )
            ) {
                delivered = true
            }
        }

        // The legacy template used #playerLsDizilla iframe directly.
        val directFrames = collectPlayerIframes(document)
        for (frame in directFrames) {
            if (!visited.add(frame)) continue
            if (
                tryDizillaExtractor(
                    frame,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )
            ) {
                delivered = true
            }
        }

        Log.d(
            "Dizilla",
            "Legacy fallback frames=" + directFrames.size +
                " alternatives=" + playerLinks.size +
                " delivered=" + delivered
        )

        return delivered
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl =
            canonicalizeDizillaUrl(data)
                ?: data

        Log.d("Dizilla", "loadLinks = " + episodeUrl)

        val response =
            try {
                app.get(
                    episodeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Referer" to "$mainUrl/"
                    ),
                    referer = "$mainUrl/"
                )
            } catch (e: Exception) {
                Log.e("Dizilla", "Episode page failed: " + e.message)
                return false
            }

        val episodeDocument = response.document
        Log.d(
            "Dizilla",
            "Episode page status=" + response.code +
                " bytes=" + response.text.length +
                " title=" + episodeDocument.title()
        )

        val secure = getSecureData(episodeDocument)
        Log.d(
            "Dizilla",
            "Episode secureData available=" + (secure != null) +
                " topLevelKeys=" + (secure?.keys()?.asSequence()?.take(25)?.toList())
        )

        var delivered = false

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

            val sourceItems = mutableListOf<JSONObject>()

            if (sources != null) {
                for (index in 0 until sources.length()) {
                    sources.optJSONObject(index)?.let { sourceItems.add(it) }
                }
            } else {
                // Search nested source objects when the API response shape changes.
                fun walk(value: Any?, depth: Int) {
                    if (depth > MAX_JSON_SEARCH_DEPTH) return

                    when (value) {
                        is JSONObject -> {
                            val sourceKeys = listOf(
                                "source_content",
                                "sourceContent",
                                "embed_url",
                                "iframe_url",
                                "embed",
                                "iframe"
                            )

                            if (
                                sourceKeys.any { key ->
                                    value.has(key) && value.opt(key) != JSONObject.NULL
                                }
                            ) {
                                sourceItems.add(value)
                            }

                            val keys = value.keys()
                            while (keys.hasNext()) {
                                walk(value.opt(keys.next()), depth + 1)
                            }
                        }

                        is JSONArray -> {
                            for (index in 0 until value.length()) {
                                walk(value.opt(index), depth + 1)
                            }
                        }
                    }
                }

                walk(secure, 0)
            }

            Log.d(
                "Dizilla",
                "Episode source objects=" + sourceItems.size +
                    " directResultCount=" + (sources?.length() ?: 0)
            )

            for (item in sourceItems.distinctBy {
                firstString(
                    it,
                    "source_content",
                    "sourceContent",
                    "embed_url",
                    "iframe_url",
                    "embed",
                    "iframe",
                    "url"
                ).orEmpty()
            }) {
                val sourceContent =
                    firstString(
                        item,
                        "source_content",
                        "sourceContent",
                        "embed_url",
                        "iframe_url",
                        "embed",
                        "iframe",
                        "url",
                        "link"
                    ) ?: metadataValueToString(item.opt("source_content"))
                    ?: continue

                val sourceName =
                    firstString(item, "source_name", "name", "title")
                val language =
                    firstString(item, "language_name", "language")
                val qualityName =
                    firstString(item, "quality_name", "quality")

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

                val iframe = extractIframeUrl(sourceContent)

                Log.d(
                    "Dizilla",
                    "Source candidate name=" + sourceName.orEmpty() +
                        " contentLength=" + sourceContent.length +
                        " iframe=" + (!iframe.isNullOrBlank())
                )

                if (!iframe.isNullOrBlank()) {
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
                            continue
                        }
                    } catch (e: Exception) {
                        Log.w("Dizilla", "Custom iframe resolver failed: " + e.message)
                    }

                    if (
                        tryDizillaExtractor(
                            iframe,
                            episodeUrl,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        delivered = true
                        continue
                    }
                } else {
                    val direct = fixUrlNull(sourceContent.trim())
                    if (
                        !direct.isNullOrBlank() &&
                        tryDizillaExtractor(
                            direct,
                            episodeUrl,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        delivered = true
                    }
                }
            }
        }

        if (delivered) return true

        // Restore the older Dizilla player-page and iframe flow.
        if (
            loadLegacyDizillaPlayers(
                episodeDocument,
                episodeUrl,
                subtitleCallback,
                callback
            )
        ) {
            return true
        }

        val fallbackFrames = collectPlayerIframes(episodeDocument)
        for (frame in fallbackFrames) {
            if (
                tryDizillaExtractor(
                    frame,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )
            ) {
                delivered = true
            }
        }

        if (!delivered) {
            Log.w(
                "Dizilla",
                "No playable link extracted. secureData=" + (secure != null) +
                    " iframes=" + fallbackFrames.size
            )
        }

        return delivered
    }


    private fun extractIframeUrl(
        sourceContent: String
    ): String? {
        val clean =
            sourceContent
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")
                .trim()

        try {
            val parsed = Jsoup.parse(clean)
            val src =
                parsed
                    .selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                    ?.let { frame ->
                        frame.attr("data-src").takeIf { it.isNotBlank() }
                            ?: frame.attr("data-lazy-src").takeIf { it.isNotBlank() }
                            ?: frame.attr("src").takeIf { it.isNotBlank() }
                    }
                    ?.trim()

            canonicalizeDizillaUrl(src)?.let { return it }
        } catch (_: Exception) {
        }

        if (
            clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("//")
        ) {
            canonicalizeDizillaUrl(clean)?.let { return it }
        }

        val attributeMatch =
            Regex(
                """(?is)(?:src|url|iframe_url|embed_url)\s*[:=]\s*['"]([^'"]+)['"]"""
            ).find(clean)

        val attributeUrl =
            attributeMatch?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")

        canonicalizeDizillaUrl(attributeUrl)?.let { return it }

        val urlMatch =
            Regex(
                """(?i)(?:https?:)?//[^\s"'<>]+"""
            ).find(clean)
                ?.value
                ?.trimEnd(')', ']', '}', ',', ';', '.')

        return canonicalizeDizillaUrl(urlMatch)
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
