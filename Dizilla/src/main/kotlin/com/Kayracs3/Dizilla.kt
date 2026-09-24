package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
    override val supportedTypes = setOf(TvType.TvSeries)

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
                """^(?:[a-z0-9]+-)*\d+-sezon-\d+-bolum(?:-[a-z0-9]+)?$""",
                RegexOption.IGNORE_CASE
            )
    }

    // =========================================================
    // AES
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

    private fun decryptSecureData(
        encryptedBase64: String
    ): JSONObject? {

        if (encryptedBase64.isBlank()) {
            return null
        }

        return try {

            val cipher =
                Cipher.getInstance(
                    "AES/CBC/PKCS5Padding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    aesKey,
                    "AES"
                ),
                IvParameterSpec(
                    ByteArray(16)
                )
            )

            val decoded =
                Base64.decode(
                    encryptedBase64,
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

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "AES decrypt failed: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // NEXT.JS BUILD ID
    // =========================================================

    private fun extractBuildId(
        html: String
    ): String? {

        if (html.isBlank()) {
            return null
        }

        return try {

            val script =
                Jsoup
                    .parse(html)
                    .selectFirst(
                        "script#__NEXT_DATA__"
                    )
                    ?.data()
                    ?: return null

            JSONObject(
                script
            )
                .optString(
                    "buildId"
                )
                .takeIf {
                    it.isNotBlank()
                }

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "buildId parse failed: ${e.message}"
            )

            null
        }
    }

    private suspend fun getBuildId(
        fallbackHtml: String? = null
    ): String? {

        if (!fallbackHtml.isNullOrBlank()) {

            extractBuildId(
                fallbackHtml
            )?.let {
                return it
            }
        }

        return try {

            val html =
                app.get(
                    mainUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).text

            extractBuildId(
                html
            )

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "Homepage buildId failed: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // MAIN PAGE
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl =
            request.data

        val document =
            try {

                app.get(
                    pageUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).document

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "MainPage failed: ${e.message}"
                )

                return newHomePageResponse(
                    request.name,
                    emptyList()
                )
            }

        val results =
            LinkedHashMap<String, SearchResponse>()

        document
            .select(
                "a[href*='/dizi/']"
            )
            .forEach { element ->

                val response =
                    element.toDizillaSearchResponse()
                        ?: return@forEach

                results[
                    response.url
                ] = response
            }

        if (results.isEmpty()) {

            document
                .select(
                    "a"
                )
                .filter {
                    it.selectFirst("img") != null
                }
                .forEach { element ->

                    val href =
                        fixUrlNull(
                            element.attr("href")
                        )
                            ?: return@forEach

                    if (
                        href.contains("-sezon-")
                        ||
                        href.contains("-bolum-")
                    ) {
                        return@forEach
                    }

                    val response =
                        element.toDizillaSearchResponse(
                            forceHref = href
                        )
                            ?: return@forEach

                    results[
                        response.url
                    ] = response
                }
        }

        return newHomePageResponse(
            request.name,
            results.values.toList()
        )
    }

    // =========================================================
    // MAIN PAGE CARD
    // =========================================================

    private fun Element.toDizillaSearchResponse(
        forceHref: String? = null
    ): SearchResponse? {

        val href =
            forceHref
                ?: fixUrlNull(
                    attr("href")
                )
                ?: return null

        if (
            href.contains("-sezon-")
            ||
            href.contains("-bolum-")
        ) {
            return null
        }

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

        val poster =
            extractPoster(
                this
            )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = poster
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

        val values =
            listOf(
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original")
            )

        for (value in values) {

            val fixed =
                fixUrlNull(
                    value
                )

            if (!fixed.isNullOrBlank()) {
                return fixed
            }
        }

        val srcset =
            image
                .attr("srcset")
                .trim()

        if (srcset.isNotBlank()) {

            val first =
                srcset
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.split(" ")
                    ?.firstOrNull()

            val fixed =
                fixUrlNull(
                    first
                )

            if (!fixed.isNullOrBlank()) {
                return fixed
            }
        }

        return null
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val searchUrl =
            mainUrl +
                SEARCH_PATH +
                encodedQuery

        val response =
            try {

                app.post(
                    searchUrl,
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
                    "Search request failed: ${e.message}"
                )

                return emptyList()
            }

        val outer =
            try {

                JSONObject(
                    response.text
                )

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "Search JSON parse failed: ${e.message}"
                )

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

        if (encrypted.isBlank()) {
            return emptyList()
        }

        val decrypted =
            decryptSecureData(
                encrypted
            )
                ?: return emptyList()

        val result =
            decrypted.optJSONArray(
                "result"
            )
                ?: return emptyList()

        val responses =
            ArrayList<SearchResponse>()

        for (
            index in 0 until result.length()
        ) {

            val item =
                result.optJSONObject(
                    index
                )
                    ?: continue

            val title =
                item
                    .optString(
                        "object_name"
                    )
                    .trim()

            if (title.isBlank()) {
                continue
            }

            var slug =
                item
                    .optString(
                        "used_slug"
                    )
                    .trim()

            if (slug.isBlank()) {
                continue
            }

            slug =
                slug.trimStart('/')

            val url =
                if (
                    slug.startsWith("http://")
                    ||
                    slug.startsWith("https://")
                ) {
                    slug
                } else {
                    "$mainUrl/$slug"
                }

            val poster =
                item
                    .optString(
                        "object_poster_url"
                    )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }

            responses +=
                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    this.posterUrl =
                        poster
                }
        }

        return responses.distinctBy {
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

        val html =
            try {

                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).text

            } catch (e: Exception) {

                throw ErrorLoadingException(
                    "Dizilla sayfası alınamadı: ${e.message}"
                )
            }

        if (html.isBlank()) {

            throw ErrorLoadingException(
                "Dizilla boş cevap döndürdü."
            )
        }

        val document =
            Jsoup.parse(
                html
            )

        val title =
            extractMeta(
                document,
                "og:title"
            )
                ?.removeSuffix(
                    " - Dizilla"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
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

        val poster =
            extractMeta(
                document,
                "og:image"
            )
                ?: extractPoster(
                    document
                )

        val description =
            extractMeta(
                document,
                "og:description"
            )
                ?: document
                    .selectFirst(
                        "meta[name=description]"
                    )
                    ?.attr("content")
                    ?.trim()

        val buildId =
            getBuildId(
                html
            )
                ?: throw ErrorLoadingException(
                    "Dizilla buildId bulunamadı."
                )

        val episodeSlugs =
            extractEpisodeSlugs(
                document
            )

        val episodes =
            ArrayList<Episode>()

        val seen =
            HashSet<String>()

        for (
            slug in episodeSlugs
        ) {

            val episode =
                loadEpisode(
                    buildId,
                    slug
                )
                    ?: continue

            val key =
                "${episode.season ?: 0}-" +
                    "${episode.episode ?: 0}-" +
                    episode.name

            if (
                !seen.add(
                    key
                )
            ) {
                continue
            }

            episodes +=
                episode
        }

        if (episodes.isEmpty()) {

            throw ErrorLoadingException(
                "Dizilla bölüm listesi bulunamadı."
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.sortedWith(
                compareBy(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )
        ) {

            this.posterUrl =
                poster

            this.plot =
                description
        }
    }

    // =========================================================
    // META
    // =========================================================

    private fun extractMeta(
        document: Document,
        property: String
    ): String? {

        return document
            .selectFirst(
                "meta[property='$property']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "meta[name='$property']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
    }

    // =========================================================
    // EPISODE SLUGS
    // =========================================================

    private fun extractEpisodeSlugs(
        document: Document
    ): List<String> {

        return document
            .select(
                "a[href]"
            )
            .mapNotNull {

                val href =
                    it.attr(
                        "href"
                    )
                        .trim()
                        .trimEnd('/')

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                href
                    .substringAfterLast('/')
                    .removePrefix("/")
            }
            .filter {

                EPISODE_SLUG_REGEX.matches(
                    it
                )
            }
            .distinct()
    }

    // =========================================================
    // LOAD EPISODE
    // =========================================================

    private suspend fun loadEpisode(
        buildId: String,
        episodeSlug: String
    ): Episode? {

        return try {

            val dataUrl =
                "$mainUrl/$NEXT_DATA_PATH/" +
                    "$buildId/" +
                    "$episodeSlug.json"

            val json =
                app.get(
                    dataUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text

            val page =
                JSONObject(
                    json
                )

            val pageProps =
                page.optJSONObject(
                    "pageProps"
                )
                    ?: return null

            val secureData =
                pageProps
                    .optString(
                        "secureData"
                    )
                    .trim()

            if (secureData.isBlank()) {
                return null
            }

            val decrypted =
                decryptSecureData(
                    secureData
                )
                    ?: return null

            val contentItem =
                decrypted.optJSONObject(
                    "contentItem"
                )

            val season =
                contentItem
                    ?.optInt(
                        "season_no"
                    )
                    ?.takeIf {
                        it > 0
                    }
                    ?: SEASON_REGEX
                        .find(
                            episodeSlug
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: 1

            val episode =
                contentItem
                    ?.optInt(
                        "episode_no"
                    )
                    ?.takeIf {
                        it > 0
                    }
                    ?: EPISODE_REGEX
                        .find(
                            episodeSlug
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

            val episodeName =
                firstNonBlank(
                    contentItem,
                    "episode_title",
                    "original_title",
                    "post_title",
                    "title",
                    "name"
                )
                    ?.removeSuffix(
                        " - Dizilla"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "${episode ?: 0}. Bölüm"

            val episodeDescription =
                firstNonBlank(
                    contentItem,
                    "description",
                    "overview",
                    "plot"
                )

            newEpisode(
                "$mainUrl/$episodeSlug"
            ) {

                this.name =
                    episodeName

                this.season =
                    season

                this.episode =
                    episode

                this.description =
                    episodeDescription
            }

        } catch (e: Exception) {

            Log.e(
                "Dizilla",
                "Episode parse failed: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // JSON VALUE HELPER
    // =========================================================

    private fun firstNonBlank(
        obj: JSONObject?,
        vararg keys: String
    ): String? {

        if (obj == null) {
            return null
        }

        for (key in keys) {

            val value =
                obj
                    .optString(
                        key
                    )
                    .trim()

            if (
                value.isNotBlank()
                &&
                value != "null"
            ) {
                return value
            }
        }

        return null
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
            "loadLinks data = $data"
        )

        val episodeHtml =
            try {

                app.get(
                    data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                ).text

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "Episode page failed: ${e.message}"
                )

                return false
            }

        val episodeDocument =
            Jsoup.parse(
                episodeHtml
            )

        val buildId =
            getBuildId(
                episodeHtml
            )
                ?: return false

        val episodeSlug =
            data
                .trimEnd('/')
                .substringAfterLast('/')

        if (episodeSlug.isBlank()) {
            return false
        }

        val jsonUrl =
            "$mainUrl/$NEXT_DATA_PATH/" +
                "$buildId/" +
                "$episodeSlug.json"

        val pageJson =
            try {

                app.get(
                    jsonUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data
                    )
                ).text

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "NEXT data failed: ${e.message}"
                )

                return false
            }

        val page =
            try {

                JSONObject(
                    pageJson
                )

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "NEXT JSON parse failed: ${e.message}"
                )

                return false
            }

        val pageProps =
            page.optJSONObject(
                "pageProps"
            )
                ?: return fallbackIframe(
                    episodeDocument,
                    subtitleCallback,
                    callback
                )

        val secureData =
            pageProps
                .optString(
                    "secureData"
                )
                .trim()

        if (secureData.isBlank()) {

            return fallbackIframe(
                episodeDocument,
                subtitleCallback,
                callback
            )
        }

        val decrypted =
            decryptSecureData(
                secureData
            )
                ?: return fallbackIframe(
                    episodeDocument,
                    subtitleCallback,
                    callback
                )

        val sources =
            decrypted
                .optJSONObject(
                    "RelatedResults"
                )
                ?.optJSONObject(
                    "getEpisodeSources"
                )
                ?.optJSONArray(
                    "result"
                )
                ?: decrypted
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
            sources == null
            ||
            sources.length() == 0
        ) {

            return fallbackIframe(
                episodeDocument,
                subtitleCallback,
                callback
            )
        }

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
                item
                    .optString(
                        "source_content"
                    )
                    .trim()

            if (sourceContent.isBlank()) {
                continue
            }

            val iframeUrl =
                extractIframeUrl(
                    sourceContent
                )
                    ?: continue

            val sourceName =
                item
                    .optString(
                        "source_name"
                    )
                    .trim()

            val languageName =
                item
                    .optString(
                        "language_name"
                    )
                    .trim()

            val qualityName =
                item
                    .optString(
                        "quality_name"
                    )
                    .trim()

            val label =
                buildString {

                    append(name)

                    if (sourceName.isNotBlank()) {
                        append(" • ")
                        append(sourceName)
                    }

                    if (languageName.isNotBlank()) {
                        append(" • ")
                        append(languageName)
                    }

                    if (qualityName.isNotBlank()) {
                        append(" • ")
                        append(qualityName)
                    }
                }

            try {

                if (
                    extractFromIframe(
                        iframeUrl,
                        label,
                        qualityName,
                        subtitleCallback,
                        callback
                    )
                ) {

                    delivered = true
                }

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "Source failed: ${e.message}"
                )
            }
        }

        return delivered
    }

    // =========================================================
    // IFRAME URL
    // =========================================================

    private fun extractIframeUrl(
        sourceContent: String
    ): String? {

        if (sourceContent.isBlank()) {
            return null
        }

        try {

            val parsed =
                Jsoup.parse(
                    sourceContent
                )

            val iframe =
                parsed
                    .selectFirst(
                        "iframe"
                    )
                    ?.attr(
                        "src"
                    )
                    ?.trim()

            val fixed =
                fixUrlNull(
                    iframe
                )

            if (!fixed.isNullOrBlank()) {
                return fixed
            }

        } catch (_: Exception) {
        }

        val regex =
            Regex(
                """(?:https?:)?//([a-zA-Z0-9.-]+/iframe\.php\?v=[A-Za-z0-9+/=]+)"""
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
    // IFRAME -> PLAYER -> SOURCE2 -> M3U8
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

        // =====================================================
        // SUBTITLES
        // =====================================================

        extractSubtitles(
            iframeHtml,
            subtitleCallback
        )

        // =====================================================
        // OPEN PLAYER TOKEN
        // =====================================================

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

        Log.d(
            "Dizilla",
            "player token bulundu."
        )

        // =====================================================
        // SOURCE2
        // =====================================================

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

        if (host.isBlank()) {
            return false
        }

        val source2Url =
            "https://$host/source2.php?v=$token"

        val source2Text =
            try {

                app.get(
                    source2Url,
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

        val source2 =
            try {

                JSONObject(
                    source2Text
                )

            } catch (e: Exception) {

                Log.e(
                    "Dizilla",
                    "source2 JSON failed: ${e.message}"
                )

                return false
            }

        if (
            !source2.optBoolean(
                "state",
                true
            )
        ) {
            return false
        }

        val playlist =
            source2.optJSONArray(
                "playlist"
            )
                ?: return false

        var delivered =
            false

        // =====================================================
        // PLAYLIST
        // =====================================================

        for (
            playlistIndex in 0 until playlist.length()
        ) {

            val playlistItem =
                playlist.optJSONObject(
                    playlistIndex
                )
                    ?: continue

            val videoSources =
                playlistItem.optJSONArray(
                    "sources"
                )
                    ?: continue

            for (
                sourceIndex in 0 until videoSources.length()
            ) {

                val source =
                    videoSources.optJSONObject(
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
                    type != "hls"
                    &&
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
                    !file.startsWith("http://")
                    &&
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

                Log.d(
                    "Dizilla",
                    "M3U8 » $masterUrl"
                )

                val quality =
                    parseQuality(
                        qualityName
                    )

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
                        "M3U8 variant parse failed: ${e.message}"
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

        val seen =
            HashSet<String>()

        val regex =
            Regex(
                """"file":"([^"]+)","label":"([^"]+)""""
            )

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

                val rawLabel =
                    match
                        .groupValues
                        .getOrNull(2)
                        ?: return@forEach

                val cleanUrl =
                    rawUrl
                        .replace(
                            "\\",
                            ""
                        )

                if (
                    cleanUrl.isBlank()
                    ||
                    !seen.add(
                        cleanUrl
                    )
                ) {
                    return@forEach
                }

                val language =
                    rawLabel
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
                                cleanUrl
                            )
                        )
                    )

                } catch (e: Exception) {

                    Log.d(
                        "Dizilla",
                        "Subtitle failed: ${e.message}"
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

        Log.d(
            "Dizilla",
            "Fallback iframe » $iframeUrl"
        )

        return extractFromIframe(
            iframeUrl,
            name,
            "",
            subtitleCallback,
            callback
        )
    }

    // =========================================================
    // QUALITY
    // =========================================================

    private fun parseQuality(
        value: String
    ): Int {

        val number =
            Regex(
                """\d{3,4}"""
            )
                .find(
                    value
                )
                ?.value
                ?.toIntOrNull()

        return number
            ?: Qualities.Unknown.value
    }
}
