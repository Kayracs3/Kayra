package com.Kayracs3

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dramadizilerim : MainAPI() {

    override var name = "DramaDizilerim"
    override var mainUrl = "https://dramadizilerim.com"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi?page={page}" to "Son Eklenen Diziler"
    )

    // ------------------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------------------

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

    override suspend fun search(query: String): List<SearchResponse> {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(rawQuery, "UTF-8")
        val candidates = listOf(
            "$mainUrl/dizi?search=$encoded&page=1",
            "$mainUrl/series?search=$encoded&page=1",
            "$mainUrl/dizi?s=$encoded&page=1",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/arama/$encoded",
            "$mainUrl/dizi?page=1",
            "$mainUrl/series?page=1",
        ).distinct()

        val found = linkedMapOf<String, SearchResponse>()
        for (url in candidates) {
            val doc = runCatching {
                app.get(url, headers = browserHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.onFailure {
                android.util.Log.w("DramaDizilerim", "Arama isteği başarısız: $url", it)
            }.getOrNull() ?: continue

            val cards = parseCards(doc)
            for (item in cards) {
                if (!matchesSearchQuery(rawQuery, item.title)) continue
                found.putIfAbsent(item.url, item.toSearchResponse(this@Dramadizilerim))
            }
            android.util.Log.d("DramaDizilerim", "Arama: $url cards=${cards.size}, matches=${found.size}")
            if (found.isNotEmpty()) break
        }
        return found.values.toList()
    }

    // ------------------------------------------------------------------------
    // HOME / MAIN PAGE
    // ------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("{page}", page.toString())
        val doc = app.get(url, headers = browserHeaders).document

        val results = parseCards(doc).map { it.toSearchResponse(this) }
        val hasNext = hasNextPage(doc, page)

        return newHomePageResponse(request.name, results, hasNext)
    }

    // ------------------------------------------------------------------------
    // DETAIL
    // ------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = browserHeaders + ("Referer" to "$mainUrl/"))
        val doc = response.document

        if (isAgeRestrictedSeries(doc)) {
            android.util.Log.w("DramaDizilerim", "Yaş sınırı etiketli dizi listelenmedi")
            return null
        }

        val title = cleanTitle(
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: url.substringAfterLast('/').replace('-', ' ')
        )

        val poster = firstNonBlank(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            doc.selectFirst("main img, article img, .detail img, .details img")?.let { imageUrl(it) }
        )?.let { fixUrl(it) }

        val plot = firstNonBlank(
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst(".description, .plot, .summary, #description")?.text()
        )?.trim()

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(doc.text())?.value?.toIntOrNull()

        val imdb = Regex("(?i)IMDb(?:\\s*Puanı)?\\s*[:|]?\\s*(\\d+(?:[.,]\\d+)?)")
            .find(doc.text())?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()

        val episodeList = doc.select("a[href*='/izle/']")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val fullUrl = fixUrl(href)
                val ep = Regex("(?:[?&])e=(\\d+)").find(fullUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)\\bBölüm\\s*(\\d+)").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)\\b(\\d+)\\s*\\.\\s*Bölüm\\b").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                val season = Regex("(?:[?&])s=(\\d+)").find(fullUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(fullUrl) {
                    this.episode = ep
                    this.season = season
                    this.name = "Bölüm $ep"
                }
            }
            .distinctBy { "${it.season}-${it.episode}-${it.data}" }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

        val movieLike = episodeList.isEmpty()
        if (movieLike) {
            val response = newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                score = imdb?.let { Score.from10(it) }
            }
            return response
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            score = imdb?.let { Score.from10(it) }
        }
    }

    // ------------------------------------------------------------------------
    // VIDEO LINKS
    // ------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) return false

        val episodePage = runCatching {
            app.get(
                episodeUrl,
                headers = browserHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return false

        val html = episodePage.text
        val episodeDoc = episodePage.document

        // Önce sitenin resmi playback API'sini kullan. Ticket'lar her istek için
        // yeniden üretildiğinden URL'leri önceden saklamıyoruz.
        var foundLink = false
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            foundLink = true
            callback(link)
        }

        val episodeId = extractPlaybackId(html, episodeDoc, "episodeId")
        val nextEpisodeId = extractPlaybackId(html, episodeDoc, "nextEpisodeId")

        if (!episodeId.isNullOrBlank()) {
            val playbackParams = buildList {
                add("episodeId=" + URLEncoder.encode(episodeId, "UTF-8"))
                if (!nextEpisodeId.isNullOrBlank()) {
                    add("nextEpisodeId=" + URLEncoder.encode(nextEpisodeId, "UTF-8"))
                }
            }.joinToString("&")
            val playbackUrl = "$mainUrl/api/playback?$playbackParams"

            val playbackResponse = runCatching {
                app.get(
                    playbackUrl,
                    headers = browserHeaders + mapOf(
                        "Referer" to episodeUrl,
                        "Origin" to mainUrl,
                        "Accept" to "application/json, text/plain, */*",
                        "Cache-Control" to "no-cache"
                    ),
                    allowRedirects = true
                )
            }.onFailure {
                android.util.Log.w("DramaDizilerim", "Playback API isteği başarısız", it)
            }.getOrNull()

            if (playbackResponse != null) {
                val playbackJson = runCatching {
                    org.json.JSONObject(playbackResponse.text)
                }.onFailure {
                    android.util.Log.w(
                        "DramaDizilerim",
                        "Playback API JSON okunamadı; bodyLength=${playbackResponse.text.length}",
                        it
                    )
                }.getOrNull()

                if (playbackJson != null && playbackJson.optBoolean("success", false)) {
                    val kind = playbackJson.optString("kind", "mp4").lowercase()
                    val mediaPath = playbackJson.optString("url", "")
                        .takeIf { it.isNotBlank() && it != "null" }

                    if (mediaPath != null) {
                        val mediaUrl = fixUrl(mediaPath)
                        val mediaType = if (kind.contains("hls") || kind.contains("m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            // /api/media?ticket=... uzantısız görünür; API kind=mp4
                            // dediği için VIDEO tipiyle doğrudan oynatıcıya verilir.
                            ExtractorLinkType.VIDEO
                        }

                        trackedCallback(
                            newExtractorLink(
                                source = name,
                                name = "$name (${kind.uppercase()})",
                                url = mediaUrl,
                                type = mediaType
                            ) {
                                this.referer = episodeUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to episodeUrl,
                                    "Origin" to mainUrl,
                                    "Accept" to "*/*"
                                )
                            }
                        )
                    }

                    val captions = playbackJson.optJSONArray("captions")
                    val captionCount = captions?.length() ?: 0
                    for (index in 0 until captionCount) {
                        val caption = captions?.optJSONObject(index) ?: continue
                        val captionPath = caption.optString("url", "")
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?: continue
                        val language = caption.optString("label", "")
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?: caption.optString("language", "Türkçe")
                        val subtitleName = if (language.startsWith("tr", true)) {
                            "Türkçe"
                        } else {
                            language.replace('_', '-')
                        }

                        subtitleCallback(
                            newSubtitleFile(
                                subtitleName,
                                fixUrl(captionPath)
                            )
                        )
                    }

                    android.util.Log.d(
                        "DramaDizilerim",
                        "Playback API success=true kind=$kind media=${mediaPath != null} captions=$captionCount"
                    )
                    if (foundLink) return true
                } else {
                    android.util.Log.w(
                        "DramaDizilerim",
                        "Playback API success=false veya yanıt biçimi beklenmiyor"
                    )
                }
            }
        } else {
            android.util.Log.w(
                "DramaDizilerim",
                "Bölüm HTML içinde episodeId bulunamadı; eski kaynak taraması denenecek"
            )
        }

        // 1) Önce sayfada doğrudan HLS / MP4 varsa kullan.
        val directCandidates = linkedSetOf<String>()
        directCandidates += findMediaUrls(html)
        directCandidates += episodeDoc.select("video source[src], video[src], source[src]")
            .mapNotNull { it.attr("src").takeIf(String::isNotBlank) }
        directCandidates += episodeDoc.select("meta[property=og:video], meta[property=og:video:url]")
            .mapNotNull { it.attr("content").takeIf(String::isNotBlank) }

        directCandidates.map(::fixUrl).forEach { url ->
            emitMediaLink(url, episodeUrl, trackedCallback)
        }

        // 2) Sayfadaki embed iframe.
        val iframe = episodeDoc.selectFirst("iframe[src], iframe[data-src]")
        val iframeUrl = iframe?.let {
            val src = firstNonBlank(it.attr("src"), it.attr("data-src")) ?: return@let null
            fixUrl(src)
        }

        if (!iframeUrl.isNullOrBlank()) {
            val embedResponse = runCatching {
                app.get(
                    iframeUrl,
                    headers = browserHeaders + ("Referer" to episodeUrl)
                )
            }.getOrNull()

            if (embedResponse != null) {
                val embedHtml = embedResponse.text
                val embedDoc = embedResponse.document

                // 2a) Embed statik olarak URL veriyorsa.
                findMediaUrls(embedHtml).map(::fixUrl).forEach { url ->
                    emitMediaLink(url, iframeUrl, trackedCallback)
                }
                embedDoc.select("video source[src], video[src], source[src]")
                    .mapNotNull { it.attr("src").takeIf(String::isNotBlank) }
                    .map(::fixUrl)
                    .forEach { url ->
                        emitMediaLink(url, iframeUrl, trackedCallback)
                    }

                // 2b) Embed sorgusundaki subtitle URL'si.
                parseQuery(iframeUrl)["sub"]?.let { encoded ->
                    decodeB64Url(encoded)?.takeIf { it.startsWith("http") }?.let { subUrl ->
                        subtitleCallback(
                            newSubtitleFile(
                                "Türkçe",
                                subUrl
                            )
                        )
                    }
                }

                // 2c) Bazı sürümlerde video URL'si ct + iv ile AES olarak taşınıyor.
                val q = parseQuery(iframeUrl)
                val ct = q["ct"]
                val iv = q["iv"]
                val videoId = q["video_id"] ?: ""
                val timestamp = q["_t"] ?: ""

                if (!ct.isNullOrBlank() && !iv.isNullOrBlank()) {
                    val inlineKeys = Regex(
                        "(?i)(?:key|secret|aes[_-]?key|player[_-]?key)\\s*[:=]\\s*[\\\"']([^\\\"']{8,128})[\\\"']"
                    ).findAll(embedHtml).map { it.groupValues[1] }.toList()

                    val keys = linkedSetOf<String>().apply {
                        addAll(inlineKeys)
                        add("dramadizilerim.com")
                        add("dramadizilerim")
                        add("DramaDizilerim")
                        add("Dramadizilerim")
                        add("dramadizilerim-player")
                        add("dramadizilerim.com-player")
                        add("dramawave")
                        add("DramaWave")
                        add("mydramawave.com")
                        add("player")
                        if (videoId.isNotBlank()) add(videoId)
                        if (timestamp.isNotBlank()) add(timestamp)
                        if (videoId.isNotBlank() && timestamp.isNotBlank()) {
                            add("$videoId$timestamp")
                            add("$timestamp$videoId")
                            add("$videoId:$timestamp")
                            add("$timestamp:$videoId")
                        }
                    }

                    tryDecryptMedia(ct, iv, keys).forEach { url ->
                        emitMediaLink(url, iframeUrl, trackedCallback)
                    }
                }
            }

            // 3) Bilinen extractor'lardan biri iframe'i destekliyorsa.
            runCatching {
                loadExtractor(
                    iframeUrl,
                    episodeUrl,
                    subtitleCallback,
                    trackedCallback
                )
            }
        }

        // Duplicate çağrılarda bile başarı dönmesi CloudStream tarafında daha
        // güvenli davranış verir; callback'e hiç medya düşmediyse false döner.
        return foundLink
    }

    // ------------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------------

    private data class CardItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType = TvType.TvSeries
    ) {
        fun toSearchResponse(api: MainAPI): SearchResponse {
            return when (type) {
                TvType.Movie -> api.newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                }
                else -> api.newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }
        }
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<CardItem> {
        val out = LinkedHashMap<String, CardItem>()

        // Detay linkleri sitede /dizi/<slug> şeklinde geliyor. Nav linkleri,
        // sayfalama ve kendine referans veren genel linkler filtreleniyor.
        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            if (raw.isBlank()) return@forEach
            if (!raw.contains("/dizi/")) return@forEach
            if (raw.contains("/dizi/\"")) return@forEach

            val cardContainer = a.parents().firstOrNull { parent ->
                parent.tagName() == "article" ||
                    parent.tagName() == "li" ||
                    parent.className().contains("card", ignoreCase = true)
            }
            val cardText = (cardContainer?.text().orEmpty() + " " + a.text()).trim()
            if (Regex("(?i)(?:^|\\\\s)\\\\+18(?:\\\\s|$)").containsMatchIn(cardText)) {
                return@forEach
            }

            val url = fixUrl(raw)
            val title = cleanTitle(
                firstNonBlank(
                    a.selectFirst("h2, h3, h4, .title, .name")?.text(),
                    a.attr("title"),
                    a.text()
                ).orEmpty()
            )

            if (title.isBlank()) return@forEach
            if (title.equals("Diziler", ignoreCase = true)) return@forEach
            if (title.length < 2) return@forEach

            val poster = a.selectFirst("img")?.let(::imageUrl)?.let(::fixUrl)
            out[url] = CardItem(title, url, poster)
        }

        return out.values.toList()
    }

    private fun isAgeRestrictedSeries(doc: org.jsoup.nodes.Document): Boolean {
        return doc.select("a, span, li, [class*=tag], [class*=badge], [class*=age]").any { element ->
            element.text().trim().equals("+18", ignoreCase = true)
        }
    }

    private fun hasNextPage(doc: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val next = doc.select("a[href]").firstOrNull { a ->
            val text = a.text().trim().lowercase()
            text.contains("sonraki") || text == "next" || text == ">"
        }
        if (next != null) return true

        return doc.text().contains("${currentPage + 1} /", ignoreCase = true)
    }

    private suspend fun emitMediaLink(
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = cleanMediaUrl(rawUrl) ?: return
        if (!url.startsWith("http")) return
        if (!url.contains(".m3u8", true) &&
            !url.contains(".mp4", true) &&
            !url.contains(".mkv", true) &&
            !url.contains(".webm", true)) return

        val type = if (url.contains(".m3u8", true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        val quality = when {
            Regex("(?i)(2160|4k)").containsMatchIn(url) -> Qualities.P2160.value
            Regex("(?i)1080").containsMatchIn(url) -> Qualities.P1080.value
            Regex("(?i)720").containsMatchIn(url) -> Qualities.P720.value
            Regex("(?i)480").containsMatchIn(url) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun findMediaUrls(text: String): Set<String> {
        val result = linkedSetOf<String>()
        val regexes = listOf(
            Regex("https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\\"'<>\\s]+\\.mp4(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\\"'<>\\s]+\\.mkv(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\\"'<>\\s]+\\.webm(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
        )

        regexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                result += match.value
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
            }
        }

        return result
    }

    /**
     * episodeId / nextEpisodeId değerleri bölüm sayfasının HTML veya JSON
     * state alanlarından çıkarılır. UUID formatı dışında bir değer kabul edilmez.
     */
    /**
     * episodeId / nextEpisodeId değerlerini bölüm HTML'i, data-* öznitelikleri
     * ve sayfaya gömülü JSON/JavaScript state alanlarından çıkarır.
     */
    private fun extractPlaybackId(
        html: String,
        doc: org.jsoup.nodes.Document,
        key: String
    ): String? {
        val uuid = """([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"""
        val isNext = key == "nextEpisodeId"
        val keyNames = if (isNext) {
            listOf("nextEpisodeId", "next_episode_id", "next-episode-id")
        } else {
            listOf("episodeId", "episode_id", "episode-id")
        }
        val normalizedHtml = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\/", "/")

        // Önce açık JSON/JavaScript özelliklerini kontrol et.
        for (keyName in keyNames) {
            val escapedKey = Regex.escape(keyName)
            val pattern = Regex(
                """(?is)["']?$escapedKey["']?\s*[:=]\s*["']$uuid["']"""
            )
            val value = pattern.find(normalizedHtml)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return value
        }

        val uuidOnly = Regex("(?i)^$uuid$")
        // data-episode-id, data-next-episode-id ve benzeri HTML öznitelikleri.
        val expectedAttrPart = if (isNext) "next" else "episode"
        for (element in doc.getAllElements()) {
            for (attribute in element.attributes()) {
                val attrName = attribute.key.lowercase()
                val relevant = attrName.contains("episode") &&
                    (if (isNext) attrName.contains("next") else !attrName.contains("next"))
                if (relevant && uuidOnly.matches(attribute.value)) {
                    return attribute.value
                }
            }
        }

        // Bazı temalarda ID, episode/player kapsayıcısının data-id alanındadır.
        val dataIdSelector = if (isNext) {
            "[data-next-episode-id], [data-next-episodeid]"
        } else {
            "[data-episode-id], [data-episodeid], [id*=episode][data-id], [class*=episode][data-id], [id*=player][data-id], [class*=player][data-id]"
        }
        val dataIdElement = doc.selectFirst(dataIdSelector)
        if (dataIdElement != null) {
            val attrs = if (isNext) {
                listOf("data-next-episode-id", "data-next-episodeid")
            } else {
                listOf("data-episode-id", "data-episodeid", "data-id")
            }
            for (attr in attrs) {
                val value = dataIdElement.attr(attr)
                if (uuidOnly.matches(value)) return value
            }
        }

        val containers = if (isNext) {
            listOf("nextEpisode", "next_episode")
        } else {
            listOf("episode", "currentEpisode", "current_episode")
        }
        for (container in containers) {
            val escapedContainer = Regex.escape(container)
            val nestedPattern = Regex(
                """(?is)["']?$escapedContainer["']?\s*:\s*\{[\s\S]{0,2000}?["']id["']\s*:\s*["']$uuid["']"""
            )
            val value = nestedPattern.find(normalizedHtml)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return value
        }

        return null
    }

    private fun tryDecryptMedia(ct: String, ivB64: String, keys: Set<String>): Set<String> {
        val result = linkedSetOf<String>()
        val cipherBytes = runCatching { Base64.decode(ct, Base64.DEFAULT) }.getOrNull() ?: return result
        val iv = runCatching { Base64.decode(ivB64, Base64.DEFAULT) }.getOrNull() ?: return result
        if (iv.size != 16) return result

        for (keyText in keys) {
            val variants = linkedSetOf<ByteArray>()
            variants += keyText.toByteArray(StandardCharsets.UTF_8)
            variants += md5(keyText)
            variants += sha256(keyText)

            for (rawKey in variants) {
                val sizes = listOf(16, 24, 32)
                for (size in sizes) {
                    val normalized = normalizeKey(rawKey, size)
                    val clear = runAes(cipherBytes, iv, normalized) ?: continue
                    val decoded = runCatching {
                        String(clear, StandardCharsets.UTF_8)
                    }.getOrNull() ?: continue

                    listOf(decoded, URLDecoder.decode(decoded, "UTF-8")).forEach { candidate ->
                        val clean = cleanMediaUrl(candidate)
                        if (clean != null &&
                            (clean.contains(".m3u8", true) || clean.contains(".mp4", true) ||
                                clean.contains(".mkv", true) || clean.contains(".webm", true))
                        ) {
                            result += clean
                        }
                    }
                }
            }
        }

        return result
    }

    private fun runAes(data: ByteArray, iv: ByteArray, key: ByteArray): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            cipher.doFinal(data)
        }.getOrNull()
    }

    private fun md5(value: String): ByteArray = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun normalizeKey(source: ByteArray, size: Int): ByteArray {
        if (source.size == size) return source
        if (source.size > size) return source.copyOf(size)
        return ByteArray(size) { index -> source[index % source.size] }
    }

    private fun cleanMediaUrl(value: String): String? {
        var url = value.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim('"', '\'')

        if (url.startsWith("\\\"http")) url = url.removePrefix("\\\"")
        if (!url.startsWith("http")) return null

        // JSON içinden çıkan URL'lerde sondaki escape karakterlerini temizle.
        url = url.replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")

        return url
    }

    private fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()

        return query.split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (key.isBlank()) null else key to URLDecoder.decode(value, "UTF-8")
            }
            .toMap()
    }

    private fun decodeB64Url(value: String): String? {
        return runCatching {
            val normalized = value.replace(' ', '+')
            String(Base64.decode(normalized, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun imageUrl(image: Element): String? {
        return firstNonBlank(
            image.attr("src"),
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original")
        )
    }

    private fun fixUrl(url: String): String {
        val cleaned = url.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> mainUrl + cleaned
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            else -> "$mainUrl/${cleaned.removePrefix("./")}"
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun cleanTitle(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+DIZILER\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*DramaBox\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun normalizeSearch(text: String): String =
        text.lowercase()
            .replace('ı', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
