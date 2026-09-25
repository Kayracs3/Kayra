package com.Kayracs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Clean, editable reconstruction of the HDFilmCehennemi provider found in the
 * user-supplied HDFilmCehennemi.cs3.
 *
 * Important: the .cs3 contained only manifest.json + classes.dex, so the original
 * Kotlin source was not present. This file recreates the observable provider
 * behaviour rather than pretending to be the original source byte-for-byte.
 */
class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "user-agent" to USER_AGENT,
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        val url = request.data.replace("sayfano", page.toString())
        val response = app.get(
            url,
            headers = requestHeaders,
            referer = mainUrl,
            interceptor = interceptor
        )

        if (response.text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        return runCatching {
            val payload: HDFC = mapper.readValue(response.text)
            val document = Jsoup.parse(payload.html)
            val items = document.select("a").mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, items)
        }.getOrElse {
            Log.e("HDCH", "getMainPage parse error: ${it.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title").trim()
        val href = fixUrlNull(attr("href")) ?: return null
        if (title.isBlank() || href.isBlank()) return null

        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src").takeUnless { it.isNullOrBlank() }
                ?: selectFirst("img")?.attr("src")
        )
        val score = selectFirst("span.imdb")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=$query",
            headers = mapOf("X-Requested-With" to "fetch"),
            referer = mainUrl,
            interceptor = interceptor
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            runCatching {
                val document = Jsoup.parse(resultHtml)
                val title = document.selectFirst("h4.title")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: return@runCatching null
                val href = fixUrlNull(document.selectFirst("a")?.attr("href"))
                    ?: return@runCatching null
                val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
                    ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster?.replace("/thumb/", "/list/")
                }
            }.getOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.section-title")?.text()
            ?.substringBefore(" izle")?.trim() ?: return null

        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src")
                .takeUnless { it.isNullOrBlank() }
                ?: document.select("aside.post-info-poster img").lastOrNull()?.attr("src")
        )

        val tags = document.select("div.post-info-genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val isSeries = document.select("div.seasons").isNotEmpty()
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating = document.selectFirst("div.post-info-imdb-rating span")?.text()
            ?.substringBefore("(")?.trim()
        val actors = document.select("div.post-info-cast a").mapNotNull { actor ->
            val actorName = actor.selectFirst("strong")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(actor.selectFirst("img")?.attr("data-src")))
        }

        val recommendations = document
            .select("div.section-slider-container div.slider-slide")
            .mapNotNull { slide ->
                val recName = slide.selectFirst("a")?.attr("title")?.trim()
                    .takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                val recHref = fixUrlNull(slide.selectFirst("a")?.attr("href"))
                    ?: return@mapNotNull null
                val recPoster = fixUrlNull(slide.selectFirst("img")?.attr("data-src"))
                    ?: fixUrlNull(slide.selectFirst("img")?.attr("src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    posterUrl = recPoster
                }
            }

        val trailer = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")
            ?.substringAfter("trailer/", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }

        return if (isSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull { episode ->
                val episodeName = episode.selectFirst("h4")?.text()?.trim()
                    .takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                val episodeUrl = fixUrlNull(episode.attr("href")) ?: return@mapNotNull null
                val episodeNumber = Regex("""(\d+)\. ?Bölüm""")
                    .find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val seasonNumber = Regex("""(\d+)\. ?Sezon""")
                    .find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    this.episode = episodeNumber
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "data » $data")

        val document = app.get(
            data,
            referer = mainUrl,
            interceptor = interceptor
        ).document

        // 1) Normal provider/player iframes.
        document.select("div.video-container iframe[data-src], div.video-container iframe[src]")
            .forEach { frame ->
                val iframe = frame.attr("data-src").ifBlank { frame.attr("src") }
                if (iframe.isNotBlank()) {
                    runCatching {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }.onFailure { Log.e("HDCH", "loadExtractor failed: ${it.message}") }
                }
            }

        // 2) Alternative language/quality buttons used by the current site.
        document.select("div.alternative-links").forEach { group ->
            val langCode = group.attr("data-lang").uppercase()

            group.select("button.alternative-link").forEach { button ->
                val label = button.text().replace("(HDrip Xbet)", "").trim()
                val sourceName = listOf(label, langCode).filter { it.isNotBlank() }.joinToString(" ")
                val videoId = button.attr("data-video").trim()
                if (videoId.isBlank()) return@forEach

                runCatching {
                    val apiResponse = app.get(
                        "${mainUrl}/video/$videoId/",
                        interceptor = interceptor,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text

                    var iframe = extractIframe(apiResponse)
                    if (iframe.isNullOrBlank()) {
                        Log.d("HDCH", "No iframe for videoID=$videoId")
                        return@runCatching
                    }

                    iframe = decodeUrl(iframe)
                    if (iframe.contains("rapidrame", ignoreCase = true)) {
                        val id = Regex("rapidrame_id=([A-Za-z0-9_-]+)")
                            .find(iframe)?.groupValues?.getOrNull(1)
                        if (!id.isNullOrBlank()) {
                            iframe = "${mainUrl}/rplayer/$id"
                        }
                    }

                    Log.d("HDCH", "$sourceName » $videoId » $iframe")

                    // Try a normal CloudStream extractor first.
                    runCatching {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }

                    // Some HDFilmCehennemi players expose the HLS URL directly.
                    extractDirectPlayer(iframe, sourceName, data, subtitleCallback, callback)
                }.onFailure {
                    Log.e("HDCH", "alternative source failed: ${it.message}")
                }
            }
        }

        return true
    }

    /**
     * Reconstructed from the installed DEX bytecode.
     *
     * The bytecode explicitly calls getAndUnpack(), Rhino Context.enter(),
     * optimizationLevel = -1, languageVersion = 200 (ES6), evaluateString()
     * three times, and then splits the collector result by "|||" while keeping
     * only values beginning with "http".
     */
    private fun decryptWithRhino(script: String): List<String> {
        val unpacked = runCatching { getAndUnpack(script) }.getOrNull() ?: return emptyList()
        if (unpacked.isBlank()) return emptyList()

        val polyfill = """
            var console = { log: function() {} };
            var _b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            if (typeof btoa === 'undefined') {
                btoa = function(input) {
                    var str = String(input);
                    var output = '';
                    for (var block = 0, charCode, idx = 0, map = _b64chars;
                        str.charAt(idx | 0) || (map = '=', idx % 1);
                        output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
                        charCode = str.charCodeAt(idx += 3/4);
                        if (charCode > 0xFF) throw new Error('btoa failed');
                        block = block << 8 | charCode;
                    }
                    return output;
                };
            }
            if (typeof atob === 'undefined') {
                atob = function(input) {
                    var str = String(input).replace(/[=]+$/, '');
                    if (str.length % 4 == 1) throw new Error('atob failed');
                    var output = '';
                    for (var bc = 0, bs = 0, buffer, idx = 0;
                        buffer = str.charAt(idx++);
                        ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer,
                            bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0
                    ) {
                        buffer = _b64chars.indexOf(buffer);
                    }
                    return output;
                };
            }
        """.trimIndent()

        val collector = """
            var __found_links__ = [];
            for (var k in this) {
                try {
                    var v = this[k];
                    if (typeof v === 'string' && (v.indexOf('.m3u8') !== -1 || v.indexOf('master') !== -1)) {
                        if (__found_links__.indexOf(v) === -1) __found_links__.push(v);
                    }
                } catch(e){}
            }
            __found_links__.join('|||');
        """.trimIndent()

        var context: org.mozilla.javascript.Context? = null
        return try {
            context = org.mozilla.javascript.Context.enter()
            context.optimizationLevel = -1
            context.languageVersion = 200
            val scope = context.initStandardObjects()

            context.evaluateString(scope, polyfill, "polyfill", 1, null)
            context.evaluateString(scope, unpacked, "unpacked", 1, null)
            val result = context.evaluateString(scope, collector, "collector", 1, null)

            result?.toString()
                ?.split("|||")
                ?.filter { it.startsWith("http") }
                ?: emptyList()
        } catch (e: Throwable) {
            Log.e("HDCH", "Rhino decrypt error: ${e.message}")
            emptyList()
        } finally {
            runCatching { org.mozilla.javascript.Context.exit() }
        }
    }

    /**
     * DEX-derived player dispatcher. The installed build has this separate
     * suspend function and uses the rapidrame_id / /rplayer/ paths before
     * falling back to the local source decoder or CloudStream extractor.
     */
    private suspend fun processIframe(
        iframe: String,
        referer: String,
        source: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var playerUrl = iframe
            .replace("\\/", "/")
            .trim()

        Regex("rapidrame_id=([a-zA-Z0-9]+)")
            .find(playerUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { playerUrl = "${mainUrl}/rplayer/$it/" }

        if (playerUrl.contains("/rplayer/")) {
            Regex("/rplayer/([a-zA-Z0-9]+)")
                .find(playerUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { playerUrl = "${mainUrl}/rplayer/$it/" }
        }

        val raw = runCatching {
            app.get(
                playerUrl,
                referer = referer,
                interceptor = interceptor,
                headers = mapOf("Accept" to "*/*")
            ).text
        }.getOrElse {
            Log.e("HDCH", "loadExtractor error: ${it.message}")
            return false
        }

        if (raw.isBlank() || raw.contains("about:blank")) {
            runCatching {
                loadExtractor(playerUrl, referer, subtitleCallback, callback)
            }.onFailure { Log.e("HDCH", "loadExtractor error: ${it.message}") }
            return true
        }

        // The installed DEX checks the player source for an embedded local
        // decoder path; preserve that behaviour before extractor fallback.
        val script = Jsoup.parse(raw).select("script")
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains("sources:") || it.contains("eval(function(p,a,c,k,e,d)") }

        if (!script.isNullOrBlank()) {
            val decryptedUrls = decryptWithRhino(script)
            decryptedUrls.forEach { url ->
                callback(
                    newExtractorLink(
                        source = source.ifBlank { "HDFilmCehennemi" },
                        name = source.ifBlank { "HDFilmCehennemi" },
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to playerUrl,
                            "Accept" to "*/*"
                        )
                    }
                )
            }
            if (decryptedUrls.isNotEmpty()) return true
        }

        runCatching {
            loadExtractor(playerUrl, referer, subtitleCallback, callback)
        }.onFailure { Log.e("HDCH", "loadExtractor error: ${it.message}") }

        Jsoup.parse(raw).select("track[src], track[data-src], track[kind='captions']")
            .forEach { track ->
                val url = fixUrlNull(track.attr("src").ifBlank { track.attr("data-src") }) ?: return@forEach
                val lang = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Türkçe" } }
                subtitleCallback(newSubtitleFile(lang, url))
            }
        return true
    }

    private suspend fun extractDirectPlayer(
        playerUrl: String,
        sourceName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                playerUrl,
                referer = referer,
                interceptor = interceptor,
                headers = mapOf("Accept" to "*/*")
            )
        }.getOrNull() ?: return

        val raw = response.text
        val document = Jsoup.parse(raw)

        // Search plain HTML/JS first.
        val candidates = linkedSetOf<String>()
        candidates += Regex("""https?://[^\\s\\"'<>]+\\.m3u8[^\\s\\"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(raw).map { decodeUrl(it.value) }
        candidates += Regex("""['\"]([^'\"]+\\.m3u8[^'\"]*)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(raw).map { decodeUrl(it.groupValues[1]) }

        // CloudStream's unpacker is useful for common packer-based player pages.
        document.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }
            if (scriptText.contains("sources:", ignoreCase = true) || scriptText.contains("eval(function(p,a,c,k,e,d)") ) {
                val unpacked = runCatching { getAndUnpack(scriptText) }.getOrNull()
                if (!unpacked.isNullOrBlank()) {
                    candidates += Regex("""https?://[^\\s\\"'<>]+\\.m3u8[^\\s\\"'<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(unpacked).map { decodeUrl(it.value) }
                }
            }
        }

        candidates.filter { it.startsWith("http") }.distinct().forEach { m3u8 ->
            callback(
                newExtractorLink(
                    source = sourceName.ifBlank { "HDFilmCehennemi" },
                    name = sourceName.ifBlank { "HDFilmCehennemi" },
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerUrl
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerUrl,
                        "Accept" to "*/*"
                    )
                }
            )
        }

        // Basic VTT/SRT track support from player HTML.
        document.select("track[src], track[data-src], track[kind=\"captions\"]").forEach { track ->
            val subtitle = fixUrlNull(
                track.attr("src").takeUnless { it.isBlank() } ?: track.attr("data-src")
            ) ?: return@forEach

            val language = track.attr("label").ifBlank {
                track.attr("srclang").ifBlank { "Türkçe" }
            }
            subtitleCallback(newSubtitleFile(language, subtitle))
        }
    }

    private fun extractIframe(value: String): String? {
        val patterns = listOf(
            Regex("""data-src=\\?\"([^\"]+)""", RegexOption.IGNORE_CASE),
            Regex("""data-src=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""<iframe[^>]+src=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""\"iframe\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
        )

        for (regex in patterns) {
            val match = regex.find(value) ?: continue
            val candidate = decodeUrl(match.groupValues[1])
            if (candidate.isNotBlank()) return candidate
        }
        return null
    }

    private fun decodeUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\\\", "\\")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
            .trim()
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val doc = Jsoup.parse(response.peekBody(1024L * 1024L).string())
            val title = doc.selectFirst("title")?.text().orEmpty()

            return if (
                title.equals("Just a moment...", ignoreCase = true) ||
                title.equals("Bir dakika lütfen...", ignoreCase = true)
            ) {
                cloudflareKiller.intercept(chain)
            } else {
                response
            }
        }
    }

    data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = emptyList()
    )

    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: String,
        @JsonProperty("keywords") val keywords: Boolean
    )
}

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
