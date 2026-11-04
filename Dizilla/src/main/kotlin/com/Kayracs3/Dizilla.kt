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

    // The series title must travel with each episode to the playback resolver.
    private fun cleanSeriesTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var value = raw
            .replace(Regex("""\s+"""), " ")
            .trim()

        value = value
            .replace(Regex("""(?i)\s*[-|–—]\s*dizilla\b.*$"""), "")
            .replace(Regex("""(?i)\s*[-|–—]\s*yabancı dizi izle.*$"""), "")
            .replace(Regex("""(?i)\s+izle$"""), "")
            .trim(' ', '-', '|', '–', '—')

        if (value.startsWith("Dizilla |", ignoreCase = true)) {
            value = value.substringAfter('|').trim()
        }

        val normalized = value.lowercase(java.util.Locale.ROOT)
        if (
            normalized.isBlank() ||
            normalized == "dizilla" ||
            normalized == "yabancı dizi" ||
            normalized == "yabancı dizi izle" ||
            normalized == "dizi izle" ||
            normalized == "ana sayfa"
        ) {
            return null
        }

        return value.takeIf { it.length <= 120 }
    }

    private fun titleFromSeriesUrl(rawUrl: String): String? {
        var slug = rawUrl
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .trim()

        slug = slug
            .replace(Regex("""(?i)-izle$"""), "")
            .replace(Regex("""(?i)-(?:hd|1080p)$"""), "")
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        val cleaned = cleanSeriesTitle(slug) ?: return null
        return cleaned.split(Regex("""\s+""")).joinToString(" ") { word ->
            word.replaceFirstChar { first -> first.uppercase() }
        }
    }

    private fun jsonLdSeriesTitle(
        document: Document,
        seriesUrl: String
    ): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val currentUrl = (canonicalizeDizillaUrl(seriesUrl) ?: seriesUrl).trimEnd('/')

        fun walk(value: Any?, depth: Int) {
            if (depth > MAX_JSON_SEARCH_DEPTH) return

            when (value) {
                is JSONObject -> {
                    val typeValue = value.opt("@type")
                    val typeNames = when (typeValue) {
                        is String -> listOf(typeValue)
                        is JSONArray -> (0 until typeValue.length()).map { typeValue.optString(it) }
                        else -> emptyList()
                    }
                    val isTvSeries = typeNames.any {
                        it.contains("TVSeries", ignoreCase = true) ||
                            it.equals("Series", ignoreCase = true)
                    }

                    if (isTvSeries) {
                        val title = cleanSeriesTitle(value.optString("name").takeIf { it.isNotBlank() })
                        if (title != null) {
                            val references = mutableListOf<String>()
                            firstString(value, "url", "@id")?.let(references::add)

                            when (val mainEntity = value.opt("mainEntityOfPage")) {
                                is String -> references.add(mainEntity)
                                is JSONObject -> firstString(mainEntity, "@id", "url")?.let(references::add)
                            }

                            val matchesCurrentPage = references.any { reference ->
                                (canonicalizeDizillaUrl(reference) ?: reference).trimEnd('/') == currentUrl
                            }
                            val seasons = value.opt("containsSeason")
                            val seasonCount = when (seasons) {
                                is JSONArray -> seasons.length()
                                is JSONObject -> 1
                                else -> 0
                            }
                            val score = 100 + seasonCount + if (matchesCurrentPage) 1000 else 0
                            candidates.add(score to title)
                        }
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

        document.select("script[type='application/ld+json']").forEach { script ->
            val raw = script.data().takeIf { it.isNotBlank() }
                ?: script.html().takeIf { it.isNotBlank() }
                ?: return@forEach

            try {
                walk(JSONObject(raw), 0)
            } catch (_: Exception) {
                try {
                    walk(JSONArray(raw), 0)
                } catch (_: Exception) {
                }
            }
        }

        return candidates.maxByOrNull { it.first }?.second
    }

    private fun jsonLdSeriesObject(
        document: Document,
        seriesUrl: String
    ): JSONObject? {
        val currentUrl = (canonicalizeDizillaUrl(seriesUrl) ?: seriesUrl).trimEnd('/')
        val candidates = mutableListOf<Pair<Int, JSONObject>>()

        fun walk(value: Any?, depth: Int) {
            if (depth > MAX_JSON_SEARCH_DEPTH) return

            when (value) {
                is JSONObject -> {
                    val rawType = value.opt("@type")
                    val types = when (rawType) {
                        is String -> listOf(rawType)
                        is JSONArray -> (0 until rawType.length()).map { rawType.optString(it) }
                        else -> emptyList()
                    }
                    val isSeries = types.any {
                        it.contains("TVSeries", ignoreCase = true) ||
                            it.equals("Series", ignoreCase = true)
                    }

                    if (isSeries) {
                        val references = mutableListOf<String>()
                        firstString(value, "url", "@id")?.let(references::add)
                        when (val mainEntity = value.opt("mainEntityOfPage")) {
                            is String -> references.add(mainEntity)
                            is JSONObject -> firstString(mainEntity, "@id", "url")?.let(references::add)
                        }

                        val matchesPage = references.any { reference ->
                            (canonicalizeDizillaUrl(reference) ?: reference).trimEnd('/') == currentUrl
                        }
                        val seasonCount = when (val seasons = value.opt("containsSeason")) {
                            is JSONArray -> seasons.length()
                            is JSONObject -> 1
                            else -> 0
                        }
                        val actorCount = when (val actors = value.opt("actor")) {
                            is JSONArray -> actors.length()
                            is JSONObject -> 1
                            else -> 0
                        }
                        val candidateScore =
                            (if (matchesPage) 10000 else 0) + seasonCount * 10 + actorCount
                        candidates.add(candidateScore to value)
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

        document.select("script[type='application/ld+json']").forEach { script ->
            val raw = script.data().takeIf { it.isNotBlank() }
                ?: script.html().takeIf { it.isNotBlank() }
                ?: return@forEach

            try {
                walk(JSONObject(raw), 0)
            } catch (_: Exception) {
                try {
                    walk(JSONArray(raw), 0)
                } catch (_: Exception) {
                }
            }
        }

        return candidates.maxByOrNull { it.first }?.second
    }

    private fun resolveSeriesTitle(
        document: Document,
        secure: JSONObject?,
        seriesUrl: String
    ): String {
        // Current Dizilla pages publish the actual series name in TVSeries JSON-LD.
        jsonLdSeriesTitle(document, seriesUrl)?.let { return it }

        // Only use series-specific fields here. Generic "name" and "title" keys
        // can belong to the site's own "Dizilla" branding in nested metadata.
        for (key in listOf("series_title", "object_name")) {
            val raw = findValueRecursive(secure, setOf(key))
            val candidate = cleanSeriesTitle(metadataValueToString(raw))
            if (candidate != null) return candidate
        }

        val pageCandidates = listOfNotNull(
            document.selectFirst("h1")?.text(),
            document.selectFirst("[data-series-title]")?.attr("data-series-title"),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='twitter:title']")?.attr("content"),
            document.title()
        )

        pageCandidates.firstNotNullOfOrNull(::cleanSeriesTitle)?.let { return it }
        return titleFromSeriesUrl(seriesUrl) ?: name
    }

    private fun encodeEpisodeData(episodeUrl: String, seriesTitle: String): String {
        val encodedTitle = Base64.encodeToString(
            seriesTitle.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return episodeUrl + EPISODE_TITLE_MARKER + encodedTitle
    }

    private fun decodeEpisodeData(data: String): Pair<String, String?> {
        val markerIndex = data.lastIndexOf(EPISODE_TITLE_MARKER)
        if (markerIndex < 0) return data to null

        val episodeUrl = data.substring(0, markerIndex)
        val encodedTitle = data.substring(markerIndex + EPISODE_TITLE_MARKER.length)
        val title = try {
            String(
                Base64.decode(
                    encodedTitle,
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                ),
                Charsets.UTF_8
            ).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        return if (
            episodeUrl.startsWith("http://", true) ||
            episodeUrl.startsWith("https://", true)
        ) {
            episodeUrl to title
        } else {
            data to null
        }
    }

    private fun resolveEpisodeDisplayTitle(
        document: Document,
        episodeUrl: String,
        storedTitle: String?
    ): String {
        storedTitle?.takeIf { it.isNotBlank() && !it.equals("Dizilla", true) }?.let { return it }

        val pageTitle = listOfNotNull(
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='twitter:title']")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.selectFirst("title")?.text()
        ).asSequence()
            .map { it.substringBefore("|").substringBefore(" - Dizilla").trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("Dizilla", true) }

        if (!pageTitle.isNullOrBlank()) return pageTitle

        val slug = episodeUrl
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace(Regex("""-\d+-sezon-\d+-bolum.*$"""), "")
            .replace('-', ' ')
            .trim()

        if (slug.isBlank()) return name
        return slug.split(Regex("""\s+""")).joinToString(" ") { word ->
            word.replaceFirstChar { first -> first.uppercase() }
        }
    }

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val SEARCH_PATH =
            "/api/bg/searchContent?searchterm="

        private const val AES_SEED =
            "!!22xx!!90!!"

        private const val STATIC_AES_KEY =
            "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

        // The current Pichive player still validates the legacy Dizilla origin.
        private const val PLAYER_REFERER = "https://dizilla.now/"

        private const val EPISODE_TITLE_MARKER = "::DIZILLA_SERIES_TITLE::"

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
                        this.score = item.score?.let { Score.from10(it) }
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

    private fun parseImdbScore(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().replace(',', '.')
        val number = Regex("""(?<![\d.])(?:10(?:\.0{1,2})?|[0-9](?:\.[0-9]{1,2})?)(?![\d.])""")
            .find(normalized)
            ?.value
            ?.toDoubleOrNull()
        return number?.takeIf { it in 0.0..10.0 }
    }

    private fun extractImdbScore(element: Element): Double? {
        val selectors = listOf(
            "[data-imdb]", "[data-imdb-rating]", "[data-imdb-score]",
            "[data-rating]", "[data-score]", "[class*='imdb']",
            "[class*='rating']", "[aria-label*='IMDb']", "[title*='IMDb']"
        )
        val nodes = buildList {
            add(element)
            selectors.forEach { selector ->
                element.select(selector).forEach { add(it) }
            }
        }.distinct()

        val attributes = listOf(
            "data-imdb", "data-imdb-rating", "data-imdb-score",
            "data-rating", "data-score", "data-value", "aria-label", "title"
        )
        val explicitScore = Regex(
            """(?i)(?:imdb|puan|rating|score)\D{0,16}(10(?:[.,]\d{1,2})?|[0-9](?:[.,]\d{1,2})?)"""
        )

        for (node in nodes) {
            for (attribute in attributes) {
                val value = node.attr(attribute).trim()
                if (value.isBlank()) continue
                parseImdbScore(value)?.let { return it }
                explicitScore.find(value)?.groupValues?.getOrNull(1)
                    ?.let(::parseImdbScore)?.let { return it }
            }

            val className = node.className().lowercase()
            if (className.contains("imdb") || className.contains("rating") || className.contains("score")) {
                parseImdbScore(node.ownText().ifBlank { node.text() })?.let { return it }
            }

            explicitScore.find(node.ownText())?.groupValues?.getOrNull(1)
                ?.let(::parseImdbScore)?.let { return it }
        }

        return null
    }

    private data class SeriesInfo(
        val title: String?,
        val slug: String?,
        val poster: String?,
        val score: Double?
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

                    val slug =
                        firstString(
                            value,
                            "series_slug",
                            "used_slug",
                            "slug"
                        )

                    val title =
                        listOf("series_title", "object_name", "name", "title")
                            .firstNotNullOfOrNull { key ->
                                cleanSeriesTitle(firstString(value, key))
                            }
                            ?: slug?.let(::titleFromSeriesUrl)

                    val poster =
                        firstString(
                            value,
                            "poster_url",
                            "object_poster_url",
                            "series_poster",
                            "poster"
                        )

                    val score =
                        parseImdbScore(
                            firstString(value, "imdb_point", "imdb_score", "imdb_rating", "ratingValue")
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
                                    poster?.replace("\\/", "/"),
                                    score
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

        val titleCandidates = listOfNotNull(
            selectFirst("span.mvk-t, .mvk-t, h1, h2, h3, h4, .title, .name, .card-title, [data-title]")?.let { heading ->
                heading.attr("data-title").takeIf { it.isNotBlank() }
                    ?: heading.text().trim().takeIf { it.isNotBlank() }
            },
            attr("data-title").takeIf { it.isNotBlank() },
            attr("title").removeSuffix(" izle").trim().takeIf { it.isNotBlank() },
            attr("aria-label").substringBefore(",").trim().takeIf { it.isNotBlank() },
            selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() },
            text().replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotBlank() && it.length <= 120 }
        )
        val title = titleCandidates.firstNotNullOfOrNull(::cleanSeriesTitle)
            ?: titleFromSeriesUrl(href)
            ?: return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = extractPoster(this@toSearchResponse)
            this.score = extractImdbScore(this@toSearchResponse)?.let { Score.from10(it) }
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

        // Current Dizilla frontend search route: /ara?q=...
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val searchUrl = "$mainUrl/ara?q=$encoded&page=1"

        val pageResults = try {
            val response = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                referer = "$mainUrl/"
            )

            Log.d("Dizilla", "Search page status=" + response.code + " bytes=" + response.text.length)

            if (response.isSuccessful) {
                val document = response.document
                val cards = document.select("a.pcard")
                    .distinctBy { it.attr("href") }
                    .mapNotNull { card ->
                        val href = card.attr("href")
                        if (!isSeriesDetailUrl(href)) null else card.toSearchResponse(href)
                    }

                if (cards.isNotEmpty()) cards else collectHtmlSeries(document)
            } else {
                Log.w("Dizilla", "Search page HTTP status=" + response.code)
                emptyList()
            }
        } catch (e: Exception) {
            Log.w("Dizilla", "Search page request failed: " + e.message)
            emptyList()
        }

        if (pageResults.isNotEmpty()) {
            Log.d("Dizilla", "Search page results=" + pageResults.size)
            return pageResults.distinctBy { it.url }.take(50)
        }

        // Backward-compatible fallback for the former encrypted API.
        return searchLegacyApi(cleanQuery)
    }

    private suspend fun searchLegacyApi(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = try {
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
            Log.w("Dizilla", "Legacy search request failed: " + e.message)
            return emptyList()
        }

        if (!response.isSuccessful) {
            Log.w("Dizilla", "Legacy search HTTP status=" + response.code)
            return emptyList()
        }

        val outer = try { JSONObject(response.text) } catch (_: Exception) { return emptyList() }
        if (!outer.optBoolean("success", false)) return emptyList()

        val encrypted = outer.optString("response").trim()
        val json = decryptSecureData(encrypted) ?: return emptyList()
        val result = json.optJSONArray("result") ?: return emptyList()
        val output = mutableListOf<SearchResponse>()

        for (index in 0 until result.length()) {
            val item = result.optJSONObject(index) ?: continue
            val slug = firstString(item, "used_slug", "slug") ?: continue
            val title = listOf("object_name", "name", "title")
                .firstNotNullOfOrNull { key -> cleanSeriesTitle(firstString(item, key)) }
                ?: titleFromSeriesUrl(slug)
                ?: continue
            val href = canonicalizeDizillaUrl(slug) ?: continue
            val poster = firstString(item, "object_poster_url", "poster_url")
            val score = parseImdbScore(
                firstString(item, "imdb_point", "imdb_score", "imdb_rating", "ratingValue", "score")
            )

            output += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = canonicalizeDizillaUrl(poster) ?: poster
                this.score = score?.let { Score.from10(it) }
            }
        }

        return output.distinctBy { it.url }.filter { matchesSearchQuery(query, it.name) }
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

        val pageResponse =
            app.get(
                seriesUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                referer = "$mainUrl/"
            )

        val document = pageResponse.document
        val pageHtml = pageResponse.text
        val schemaSeries = jsonLdSeriesObject(document, seriesUrl)

        val secure =
            combineMetadata(
                getSecureData(document),
                getStructuredData(document)
            )

        val title = resolveSeriesTitle(document, secure, seriesUrl)

        Log.d(
            "Dizilla",
            "Series display title resolved=$title url=$seriesUrl"
        )

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
            schemaSeries
                ?.optString("description")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: firstMetadata(
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

        val score =
            schemaSeries
                ?.optJSONObject("aggregateRating")
                ?.let { rating -> parseImdbScore(firstString(rating, "ratingValue", "rating")) }
                ?: parseImdbScore(
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
                )

        val tags =
            document
                .select("a[href*='dizi-turu']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

        val actors = extractSeriesActors(document, schemaSeries)

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
                .map { episode ->
                    // Carry the series title into loadLinks without losing episode metadata.
                    newEpisode(encodeEpisodeData(episode.data, title.trim())) {
                        this.name = episode.name
                        this.season = episode.season
                        this.episode = episode.episode
                        this.description = episode.description
                        this.posterUrl = episode.posterUrl
                    }
                }

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

        val recommendations =
            extractSimilarSeries(document, pageHtml, seriesUrl)

        Log.d(
            "Dizilla",
            "Detail metadata: JSON-LD=" + (schemaSeries != null) +
                " IMDb=" + (score?.toString() ?: "yok") +
                " actors=" + actors.size + " similar=" + recommendations.size
        )

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
            this.score = score?.let { Score.from10(it) }
            this.duration = null
            this.actors = actors.map { ActorData(it) }
            this.recommendations = recommendations
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

    private fun extractSeriesActors(
        document: Document,
        schemaSeries: JSONObject?
    ): List<Actor> {
        val actors = LinkedHashMap<String, Actor>()

        fun addActor(nameRaw: String?, imageRaw: Any? = null) {
            val actorName = nameRaw?.trim()?.replace(Regex("""\s+"""), " ")
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?: return
            val rawImage = metadataValueToString(imageRaw)
            val image = canonicalizeDizillaUrl(rawImage) ?: rawImage
            val key = actorName.lowercase(java.util.Locale.ROOT)
            val previous = actors[key]
            if (previous == null || (previous.image.isNullOrBlank() && !image.isNullOrBlank())) {
                actors[key] = Actor(actorName, image)
            }
        }

        fun readSchemaActor(value: Any?) {
            when (value) {
                is JSONObject -> {
                    addActor(
                        firstString(value, "name", "title"),
                        value.opt("image") ?: value.opt("photo")
                    )
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        readSchemaActor(value.opt(index))
                    }
                }

                is String -> addActor(value)
            }
        }

        readSchemaActor(schemaSeries?.opt("actor"))

        // Some Dizilla page revisions keep portraits in the cast cards rather than
        // in the JSON-LD Person object. Use the matching actor card image as fallback.
        document.select("a[href*='oyuncu'], [class*='actor'] a[href]").forEach { anchor ->
            val image = extractPoster(anchor)
                ?: anchor.parent()?.let { extractPoster(it) }
            val actorName = listOf(
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.text(),
                anchor.selectFirst("img")?.attr("alt")
            ).firstNotNullOfOrNull { candidate ->
                candidate?.trim()?.takeIf { it.isNotBlank() }
            }
            addActor(actorName, image)
        }

        return actors.values.toList()
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


    /**
     * Current Dizilla episodes may expose secureData only through Next.js
     * data requests, not in the rendered episode HTML.
     */
    private fun extractNextBuildId(html: String): String? {
        if (html.isBlank()) return null

        val script = Regex(
            """(?is)<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>"""
        ).find(html)?.groupValues?.getOrNull(1)

        val candidates = listOfNotNull(script, html)
        val buildIdPattern = Regex(
            """"buildId"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )

        for (candidate in candidates) {
            val normalized = candidate
                .replace("\\\"", "\"")
                .replace("\\\\\"", "\"")
                .replace("\\u0022", "\"")

            val direct = buildIdPattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }

            if (!direct.isNullOrBlank()) return direct

            try {
                val json = JSONObject(normalized)
                json.optString("buildId")
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            } catch (_: Exception) {
            }
        }

        return null
    }

    /**
     * Match the site's Next.js data flow: check the homepage first, then use
     * the current episode HTML as the documented fallback.
     */
    private suspend fun fetchDizillaBuildId(
        fallbackHtml: String
    ): String? {
        val homepageHtml = try {
            app.get(
                "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            ).text
        } catch (e: Exception) {
            Log.w("Dizilla", "BuildId homepage request failed: ${e.message}")
            ""
        }

        val homepageBuildId = extractNextBuildId(homepageHtml)
        if (!homepageBuildId.isNullOrBlank()) {
            Log.d("Dizilla", "Next.js buildId found on homepage")
            return homepageBuildId
        }

        val pageBuildId = extractNextBuildId(fallbackHtml)
        Log.d("Dizilla", "Next.js buildId found on episode page=${!pageBuildId.isNullOrBlank()}")
        return pageBuildId
    }

    private suspend fun loadEpisodeSecureData(
        episodeUrl: String,
        fallbackHtml: String
    ): JSONObject? {
        val slug = episodeUrl
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: return null

        val buildId = fetchDizillaBuildId(fallbackHtml) ?: return null
        val dataUrl = "$mainUrl/_next/data/$buildId/$slug.json"

        val response = try {
            app.get(
                dataUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to episodeUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = episodeUrl
            )
        } catch (e: Exception) {
            Log.w("Dizilla", "Next data request failed url=$dataUrl: ${e.message}")
            return null
        }

        Log.d(
            "Dizilla",
            "Next data response status=${response.code} bytes=${response.text.length}"
        )
        if (!response.isSuccessful) return null

        val page = try {
            JSONObject(response.text)
        } catch (e: Exception) {
            Log.w("Dizilla", "Next data JSON parse failed: ${e.message}")
            return null
        }

        val encrypted = page.optJSONObject("pageProps")
            ?.optString("secureData")
            ?.takeIf { it.isNotBlank() }
            ?: findStringRecursive(page, setOf("secureData"))

        if (encrypted.isNullOrBlank()) {
            Log.w("Dizilla", "Next data pageProps.secureData missing")
            return null
        }

        val decrypted = decryptSecureData(encrypted)
        Log.d("Dizilla", "Next data secureData decrypted=${decrypted != null}")
        return decrypted
    }

    private fun collectPlayersFromSecureData(
        secure: JSONObject?
    ): List<DizillaRscPlayer> {
        if (secure == null) return emptyList()

        val sources = secure
            .optJSONObject("RelatedResults")
            ?.optJSONObject("getEpisodeSources")
            ?.optJSONArray("result")
            ?: secure
                .optJSONObject("content")
                ?.optJSONObject("result")
                ?.optJSONObject("RelatedResults")
                ?.optJSONObject("getEpisodeSources")
                ?.optJSONArray("result")

        val items = mutableListOf<JSONObject>()
        if (sources != null) {
            for (index in 0 until sources.length()) {
                sources.optJSONObject(index)?.let(items::add)
            }
        } else {
            fun walk(value: Any?, depth: Int) {
                if (depth > MAX_JSON_SEARCH_DEPTH) return
                when (value) {
                    is JSONObject -> {
                        if (
                            listOf("source_content", "sourceContent", "embed_url", "iframe_url", "src")
                                .any { key -> value.has(key) && value.opt(key) != JSONObject.NULL }
                        ) {
                            items.add(value)
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

        val output = mutableListOf<DizillaRscPlayer>()
        for (item in items) {
            val rawContent = firstString(
                item,
                "source_content",
                "sourceContent",
                "embed_url",
                "iframe_url",
                "embed",
                "iframe",
                "src",
                "url",
                "link"
            ) ?: metadataValueToString(item.opt("source_content"))
                ?: continue

            val playerUrl = extractIframeUrl(rawContent)
                ?: rawContent.trim().takeIf {
                    it.startsWith("http://", true) || it.startsWith("https://", true)
                }
                ?: continue

            if (
                !playerUrl.startsWith("http://", true) &&
                !playerUrl.startsWith("https://", true)
            ) {
                continue
            }

            output += DizillaRscPlayer(
                url = playerUrl,
                name = firstString(item, "source_name", "name", "title").orEmpty(),
                quality = firstString(item, "quality_name", "quality").orEmpty(),
                language = firstString(item, "language_name", "langLabel", "language", "lang").orEmpty()
            )
        }

        return output.distinctBy { it.url }
    }

    private data class DizillaRscPlayer(
        val url: String,
        val name: String,
        val quality: String,
        val language: String
    )

    /**
     * The current site puts its player list in a Next.js Flight/RSC payload,
     * not in secureData or a normal iframe element.
     */
    private fun extractBalancedJson(
        text: String,
        startIndex: Int
    ): String? {
        if (startIndex < 0 || startIndex >= text.length) return null
        val first = text[startIndex]
        if (first != '[' && first != '{') return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in startIndex until text.length) {
            val char = text[index]

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun extractRscArray(
        rawHtml: String,
        propertyName: String
    ): JSONArray? {
        if (rawHtml.isBlank()) return null

        val candidates = listOf(
            rawHtml,
            rawHtml.replace("\\\"", "\""),
            rawHtml.replace("\\\\\"", "\"").replace("\\u0022", "\""),
            rawHtml.replace("\\u0022", "\"")
        ).distinct()

        val markers = listOf(
            "\"$propertyName\":[",
            "\"$propertyName\": [",
            "\"$propertyName\" :[",
            "\"$propertyName\" : ["
        )

        for (candidate in candidates) {
            for (marker in markers) {
                var cursor = 0
                while (cursor < candidate.length) {
                    val at = candidate.indexOf(marker, cursor)
                    if (at < 0) break

                    val openIndex = candidate.indexOf('[', at)
                    val json = extractBalancedJson(candidate, openIndex)
                    if (!json.isNullOrBlank()) {
                        try {
                            val array = JSONArray(json)
                            if (array.length() > 0) return array
                        } catch (_: Exception) {
                        }
                    }

                    cursor = at + marker.length
                }
            }
        }

        return null
    }

    private fun extractSimilarSeries(
        document: Document,
        pageHtml: String,
        currentSeriesUrl: String
    ): List<SearchResponse> {
        val current = (canonicalizeDizillaUrl(currentSeriesUrl) ?: currentSeriesUrl).trimEnd('/')
        val fromRsc = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        val similar = extractRscArray(pageHtml, "similar")

        if (similar != null) {
            for (index in 0 until similar.length()) {
                val item = similar.optJSONObject(index) ?: continue
                val rawUrl = firstString(item, "url", "href", "slug") ?: continue
                val relatedUrl = canonicalizeDizillaUrl(rawUrl) ?: continue
                if (relatedUrl.trimEnd('/') == current || !seen.add(relatedUrl)) continue

                val title =
                    cleanSeriesTitle(firstString(item, "title", "name"))
                        ?: titleFromSeriesUrl(relatedUrl)
                        ?: continue
                val posterRaw =
                    firstString(item, "poster", "poster_url", "image", "thumbnail")
                        ?: metadataValueToString(item.opt("poster"))
                val score = parseImdbScore(
                    firstString(item, "imdb_point", "imdb_score", "imdb_rating", "ratingValue", "score")
                )

                fromRsc += newTvSeriesSearchResponse(title, relatedUrl, TvType.TvSeries) {
                    this.posterUrl = canonicalizeDizillaUrl(posterRaw) ?: posterRaw
                    this.score = score?.let { Score.from10(it) }
                }
            }
        }

        if (fromRsc.isNotEmpty()) return fromRsc

        // HTML fallback for deployments where the similar list is rendered normally.
        val relatedContainers = document.select(
            "section[class*='similar'], div[class*='similar'], " +
                "section[id*='similar'], div[id*='similar'], " +
                "section[class*='related'], div[class*='related'], " +
                "section[id*='related'], div[id*='related']"
        ).filter { container ->
            val heading = container.selectFirst("h2, h3, h4")?.text().orEmpty()
            heading.contains("benzer", true) ||
                heading.contains("similar", true) ||
                heading.contains("ilgili", true)
        }

        return relatedContainers
            .flatMap { it.select("a[href*='/dizi/']").mapNotNull { anchor -> anchor.toSearchResponse() } }
            .filter { it.url.trimEnd('/') != current }
            .distinctBy { it.url }
            .take(MAX_MAIN_RESULTS)
    }

    private fun extractRscSources(
        rawHtml: String
    ): List<JSONArray> {
        // Flight values may be escaped once or twice inside the HTML response.
        val candidates = listOf(
            rawHtml.replace("\\\"", "\""),
            rawHtml.replace("\\\\\"", "\"").replace("\\u0022", "\""),
            rawHtml.replace("\\u0022", "\"")
        ).distinct()

        val markers = listOf(
            "\"sources\":[",
            "\"sources\": [",
            "\"sources\" :[",
            "\"sources\" : ["
        )
        val arrays = mutableListOf<JSONArray>()

        // Do not stop at the first non-empty "sources" array: Next.js pages can
        // contain unrelated arrays before the actual episode player list.
        for (html in candidates) {
            for (marker in markers) {
                var cursor = 0
                while (cursor < html.length) {
                    val at = html.indexOf(marker, cursor)
                    if (at < 0) break

                    val openIndex = html.indexOf('[', at + marker.lastIndex)
                    val json = extractBalancedJson(html, openIndex)
                    if (!json.isNullOrBlank()) {
                        try {
                            val array = JSONArray(json)
                            if (array.length() > 0) arrays.add(array)
                        } catch (_: Exception) {
                        }
                    }
                    cursor = at + marker.length
                }
            }
        }

        val unique = arrays.distinctBy { it.toString() }
        Log.d(
            "Dizilla",
            "RSC sources arrays parsed=${unique.size} counts=${unique.map { it.length() }}"
        )
        return unique
    }

    private fun collectRscPlayers(
        rawHtml: String
    ): List<DizillaRscPlayer> {
        val sourceArrays = extractRscSources(rawHtml)
        if (sourceArrays.isEmpty()) return emptyList()

        val players = mutableListOf<DizillaRscPlayer>()
        val urlKeys = arrayOf(
            "src", "url", "file", "embed", "iframe",
            "source_content", "sourceContent", "embed_url", "embedUrl",
            "iframe_url", "iframeUrl", "player_url", "playerUrl",
            "link", "source", "content", "value"
        )

        for (sources in sourceArrays) {
            for (index in 0 until sources.length()) {
                val item = sources.optJSONObject(index) ?: continue
                if (item.optBoolean("trailer", false)) continue

                val rawUrl = firstString(item, *urlKeys)
                    ?: metadataValueToString(item.opt("src"))
                    ?: metadataValueToString(item.opt("source"))
                    ?: continue

                // Some builds provide an iframe snippet or a relative URL instead
                // of a fully-qualified src string.
                val playerUrl = extractIframeUrl(rawUrl)
                    ?: canonicalizeDizillaUrl(rawUrl)
                    ?: continue
                if (
                    !playerUrl.startsWith("http://", true) &&
                    !playerUrl.startsWith("https://", true)
                ) {
                    continue
                }

                players += DizillaRscPlayer(
                    url = playerUrl,
                    name = firstString(item, "name", "title", "source_name").orEmpty(),
                    quality = firstString(item, "quality", "quality_name").orEmpty(),
                    language = firstString(
                        item,
                        "langLabel",
                        "language_name",
                        "language",
                        "lang"
                    ).orEmpty()
                )
            }
        }

        val result = players
            .groupBy { it.url }
            .values
            .map { entries ->
                val first = entries.first()
                first.copy(
                    language = entries.map { it.language }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" / ")
                )
            }

        if (result.isEmpty()) {
            val details = sourceArrays.take(4).mapIndexed { arrayIndex, array ->
                val itemDetails = (0 until minOf(array.length(), 3)).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val keys = item.keys().asSequence().take(12).toList()
                    val sample = firstString(item, *urlKeys)?.take(100).orEmpty()
                    "keys=$keys urlSample=$sample"
                }
                "array$arrayIndex(size=${array.length()}): ${itemDetails.joinToString("; ")}"
            }
            Log.w("Dizilla", "RSC arrays found but no player URLs: ${details.joinToString(" | ")}")
        } else {
            Log.d("Dizilla", "RSC playable players collected=${result.size}")
        }

        return result
    }

    private fun extractPlayerConfig(
        html: String
    ): JSONObject? {
        // Pichive may serialize the inline config with escaped quotes in its HTML.
        val candidates = listOf(
            html,
            html.replace("\\\"", "\""),
            html.replace("\\\\\"", "\"").replace("\\u0022", "\""),
            html.replace("\\u0022", "\"")
        ).distinct()

        for (candidate in candidates) {
            var cursor = 0
            while (cursor < candidate.length) {
                val markerIndex = candidate.indexOf("NP_CONFIG", cursor, ignoreCase = true)
                if (markerIndex < 0) break

                val objectStart = candidate.indexOf('{', markerIndex)
                if (objectStart >= 0) {
                    val json = extractBalancedJson(candidate, objectStart)
                    if (!json.isNullOrBlank()) {
                        try {
                            val config = JSONObject(json)
                            if (config.optJSONArray("sources") != null) {
                                return config
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                cursor = markerIndex + "NP_CONFIG".length
            }
        }

        // Some Pichive responses expose the HLS entries directly in a JSON
        // "sources" array without the window.NP_CONFIG assignment.
        val directSources = extractRscSources(html).firstOrNull { array ->
            (0 until array.length()).any { index ->
                val item = array.optJSONObject(index) ?: return@any false
                val candidate = firstString(item, "master", "src", "file", "url").orEmpty()
                (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) &&
                    (candidate.contains(".m3u8", true) || candidate.contains("m.php", true))
            }
        }
        if (directSources != null) {
            Log.d("Dizilla", "Pichive direct sources array recovered count=${directSources.length()}")
            return try {
                JSONObject().put("sources", directSources)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private suspend fun loadRscPlayer(
        player: DizillaRscPlayer,
        mediaTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seenSubtitles: MutableSet<String>
    ): Boolean {
        var playerHtml = ""
        var config: JSONObject? = null
        var responseCode = -1

        // Pichive serves different page variants depending on the browser identity.
        // Try the desktop UA used by the working reference provider first, then the
        // provider's normal UA for compatibility.
        val playerUserAgents = listOf(PLAYER_USER_AGENT, USER_AGENT).distinct()
        for (playerUa in playerUserAgents) {
            try {
                val response = app.get(
                    player.url,
                    headers = mapOf(
                        "User-Agent" to playerUa,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Referer" to PLAYER_REFERER
                    ),
                    referer = PLAYER_REFERER
                )
                responseCode = response.code
                playerHtml = response.text
                config = extractPlayerConfig(playerHtml)
                val uaLabel = if (playerUa == PLAYER_USER_AGENT) "desktop" else "mobile"
                Log.d(
                    "Dizilla",
                    "Pichive response status=$responseCode bytes=${playerHtml.length} ua=$uaLabel config=${config != null}"
                )
                if (config != null) break
            } catch (e: Exception) {
                val uaLabel = if (playerUa == PLAYER_USER_AGENT) "desktop" else "mobile"
                Log.w("Dizilla", "Pichive request failed ua=$uaLabel: ${e.message}")
            }
        }

        if (config == null) {
            val fallbackLabel = buildString {
                append(mediaTitle)
                if (player.language.isNotBlank()) append(" • ${player.language}")
                if (player.name.isNotBlank()) append(" • ${player.name}")
                if (player.quality.isNotBlank()) append(" • ${player.quality}")
            }
            val legacyDelivered = try {
                extractFromIframe(
                    iframeUrl = player.url,
                    label = fallbackLabel,
                    qualityName = player.quality,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    mediaTitle = mediaTitle,
                    requestReferer = PLAYER_REFERER
                )
            } catch (e: Exception) {
                Log.w("Dizilla", "Pichive legacy player fallback failed: ${e.message}")
                false
            }
            Log.d(
                "Dizilla",
                "Pichive NP_CONFIG missing; legacy openPlayer fallback delivered=$legacyDelivered " +
                    "status=$responseCode bytes=${playerHtml.length} " +
                    "hasIframe=${playerHtml.contains("<iframe", true)} " +
                    "hasOpenPlayer=${playerHtml.contains("openPlayer", true)} " +
                    "hasSources=${playerHtml.contains("sources", true)}"
            )
            return legacyDelivered
        }

        val masters = config.optJSONArray("sources") ?: return false
        val quality = Regex("""\d{3,4}""")
            .find(player.quality)
            ?.value
            ?.toIntOrNull()
            ?: Qualities.Unknown.value

        val linkName = buildString {
            append(mediaTitle)
            if (player.language.isNotBlank()) {
                append(" • ")
                append(player.language)
            }
            if (player.name.isNotBlank()) {
                append(" • ")
                append(player.name)
            }
            if (player.quality.isNotBlank()) {
                append(" • ")
                append(player.quality)
            }
        }

        var delivered = false

        for (index in 0 until masters.length()) {
            val source = masters.optJSONObject(index) ?: continue
            val master = firstString(source, "master", "src", "file", "url").orEmpty().trim()
            if (
                (!master.startsWith("http://", true) && !master.startsWith("https://", true)) ||
                (!master.contains(".m3u8", true) && !master.contains("m.php", true))
            ) {
                continue
            }

            val generated = try {
                M3u8Helper.generateM3u8(linkName, master, player.url)
            } catch (e: Exception) {
                Log.d("Dizilla", "Pichive M3U8 variants failed: ${e.message}")
                emptyList()
            }

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = mediaTitle,
                        name = linkName,
                        url = master,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = player.url
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to player.url
                        )
                        this.quality = quality
                    }
                )
            }

            delivered = true
        }

        val subtitles = config.optJSONArray("subtitles")
        if (subtitles != null) {
            for (index in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(index) ?: continue
                val subtitleUrl = item.optString("url").trim()
                if (
                    (!subtitleUrl.startsWith("http://", true) &&
                        !subtitleUrl.startsWith("https://", true)) ||
                    !seenSubtitles.add(subtitleUrl)
                ) {
                    continue
                }

                val language = item.optString("label").trim().ifBlank { "Türkçe" }
                subtitleCallback(
                    newSubtitleFile(language, subtitleUrl) {
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to player.url
                        )
                    }
                )
            }
        }

        Log.d(
            "Dizilla",
            "Pichive player parsed url=${player.url} masters=${masters.length()} delivered=$delivered subtitles=${subtitles?.length() ?: 0}"
        )
        return delivered
    }

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
        val (rawEpisodeUrl, storedSeriesTitle) = decodeEpisodeData(data)
        val episodeUrl =
            canonicalizeDizillaUrl(rawEpisodeUrl)
                ?: rawEpisodeUrl

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
        val displayTitle = resolveEpisodeDisplayTitle(
            episodeDocument,
            episodeUrl,
            storedSeriesTitle
        )
        Log.d(
            "Dizilla",
            "Episode page status=" + response.code +
                " bytes=" + response.text.length +
                " pageTitle=" + episodeDocument.title() +
                " mediaTitle=" + displayTitle
        )

        // Current Dizilla builds sometimes keep episode secureData only in /_next/data/*.json.
        val secure = loadEpisodeSecureData(episodeUrl, response.text)
            ?: getSecureData(episodeDocument)

        Log.d(
            "Dizilla",
            "Episode secureData available=" + (secure != null) +
                " topLevelKeys=" + (secure?.keys()?.asSequence()?.take(25)?.toList())
        )

        val securePlayers = collectPlayersFromSecureData(secure)
        val rscPlayers = collectRscPlayers(response.text)
        val candidates = (securePlayers + rscPlayers).distinctBy { it.url }

        Log.d(
            "Dizilla",
            "Playback candidates secureData=${securePlayers.size} RSC=${rscPlayers.size} total=${candidates.size}"
        )

        var delivered = false
        val seenSubtitles = HashSet<String>()

        for (player in candidates) {
            try {
                if (loadRscPlayer(player, displayTitle, subtitleCallback, callback, seenSubtitles)) {
                    delivered = true
                }
            } catch (e: Exception) {
                Log.w("Dizilla", "Player resolver failed url=${player.url}: ${e.message}")
            }
        }

        if (delivered) {
            Log.d("Dizilla", "New Dizilla player links delivered")
            return true
        }

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
                        append(displayTitle)
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
        callback: (ExtractorLink) -> Unit,
        mediaTitle: String = label.substringBefore(" • ").ifBlank { name },
        requestReferer: String = "$mainUrl/"
    ): Boolean {

        val iframeHtml =
            try {
                app.get(
                    iframeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to requestReferer
                    ),
                    referer = requestReferer
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
                ?: run {
                    Log.w("Dizilla", "Player page has no openPlayer token url=$iframeUrl bytes=${iframeHtml.length}")
                    return false
                }

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
                        source = mediaTitle,
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
