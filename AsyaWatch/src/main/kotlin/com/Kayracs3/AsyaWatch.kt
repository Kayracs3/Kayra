package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 350L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "" to "Yeni Eklenen Diziler",
        "aksiyon" to "Aksiyon Dizi",
        "bilim-kurgu" to "Bilim Kurgu Dizi",
        "fantastik" to "Fantastik Dizi",
        "gerilim" to "Gerilim Dizi",
        "gizem" to "Gizem Dizi",
        "korku" to "Korku Dizi",
        "komedi" to "Komedi Dizi",
        "romantik" to "Romantik Dizi"
    )

    private val siteHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private val fourPichiveOrigin = "https://four.pichive.online"
    private val powSalt = "Tu6UwcgeK8"

    private data class PageData(
        val document: Document,
        val secureData: JSONObject?,
        val isRateLimited: Boolean = false
    )
    private data class SourceItem(val raw: String, val quality: String)

    @Volatile private var rateLimitedUntil = 0L
    @Volatile private var lastPosterFallbackAt = 0L
    private val posterFallbackCache = ConcurrentHashMap<String, String>()
    private val noPosterSentinel = "__NO_ASYAWATCH_POSTER__"
    private val powCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ... (diğer yardımcı fonksiyonlar aynı kalacak: isRateLimitResponse, noteRateLimit, isCoolingDown, titleFromUrl, field, objectField, arrayField, parseObject, decodeBase64Text, decodeApiResponse, absoluteUrl, imageUrl, imageFromJson, imageFromElement, scoreFrom, pageSecureData, nextFlightPayloads, jsonObjectAfterKey, nextFlightObject, detailCatalogEntry, mergeDetailObjects, getPageData, postJson, searchResponseFrom, getExploreJson, getMainPage, search, quickSearch, detailTitle, normalizedImageTitle, detailPoster, fillMissingPosters, detailYear, detailTags, detailActors, detailActorsFromDocument, detailTrailer, parseEpisodes, parseEpisodesFromDocument, load, sourceItems, looksLikePlayer, extractCandidates, looksLikeSubtitle, normalizeSubtitleText, explicitSubtitleLanguage, subtitleUrlQueryLanguage, subtitleUrlLanguage, subtitleLanguage, resolveSubtitleUrl, subtitleUrls, publishSubtitlesFromText, extractSubtitlesFromManifest, extractSubtitlesFromPlayerPage, isDirectMedia, qualityValue) ...
    // (Kodun geri kalanı aynı olduğu için burada tekrar edilmiyor, sadece değişen kısımlar gösteriliyor)

    // =====================================================================
    // Cloudflare cf_clearance çerezini WebView ile alma
    // =====================================================================

    /**
     * WebView kullanarak four.pichive.online için cf_clearance çerezini alır.
     * CloudStream'in WebViewResolver'ını kullanır.
     */
    private suspend fun getCfClearanceCookie(url: String): String? {
        return try {
            // WebViewResolver ile sayfayı yükle
            val resolver = com.lagradost.cloudstream3.utils.WebViewResolver()
            val result = resolver.resolveUsingWebView(
                url = url,
                additionalUrls = listOf(Regex("four\\.pichive\\.online")),
                useOkhttp = false // Cloudflare için OkHttp kullanma
            )

            if (result != null) {
                // WebView'den çerezleri al
                val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                if (cookies != null && cookies.contains("cf_clearance")) {
                    // cf_clearance çerezini ayıkla
                    val cfCookie = cookies.split(";").find { it.trim().startsWith("cf_clearance=") }
                    Log.d("AsyaWatch", "cf_clearance alındı: ${cfCookie?.take(50)}...")
                    return cfCookie?.trim()
                } else {
                    Log.w("AsyaWatch", "WebView'de cf_clearance bulunamadı. Çerezler: ${cookies?.take(200)}")
                }
            } else {
                Log.w("AsyaWatch", "WebViewResolver sonuç döndürmedi")
            }
            null
        } catch (e: Exception) {
            Log.e("AsyaWatch", "cf_clearance alma hatası: ${e.message}")
            null
        }
    }

    /**
     * track.php'yi dener: tarayıcıda gözlemlenen endpoint.
     * cf_clearance çerezi ile çağrılmalıdır.
     */
    private suspend fun tryTrackPhp(
        iframeUrl: String,
        iframeId: String,
        cfClearance: String?, // cf_clearance çerezi (varsa)
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val siteRoot = "$mainUrl/"
        val hRaw = Base64.encodeToString(siteRoot.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val hEncoded = URLEncoder.encode(hRaw, "UTF-8")
        val trackUrl = "$fourPichiveOrigin/track.php?v=$iframeId&h=$hEncoded"
        Log.d("AsyaWatch", "track.php deneniyor: v=$iframeId h=$hRaw")

        val headers = mutableMapOf(
            "User-Agent" to (siteHeaders["User-Agent"] ?: ""),
            "Accept" to "*/*",
            "Accept-Language" to (siteHeaders["Accept-Language"] ?: ""),
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to iframeUrl,
            "Origin" to fourPichiveOrigin,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Storage-Access" to "active"
        )

        // cf_clearance çerezini ekle
        if (cfClearance != null) {
            headers["Cookie"] = cfClearance
            Log.d("AsyaWatch", "track.php isteğine cf_clearance eklendi")
        } else {
            Log.w("AsyaWatch", "cf_clearance yok, track.php çalışmayabilir")
        }

        val resp = runCatching {
            app.get(trackUrl, headers = headers, referer = iframeUrl)
        }.getOrNull() ?: return false

        val ct = resp.headers["Content-Type"] ?: "yok"
        Log.d("AsyaWatch", "track.php HTTP ${resp.code}, bodyLen=${resp.text.length}, ct=$ct")

        if (resp.code == 403 || resp.code == 429) {
            Log.w("AsyaWatch", "track.php WAF/challenge bloklu (HTTP ${resp.code})")
            return false
        }
        if (!resp.isSuccessful || resp.text.isBlank()) return false

        val html = resp.text
        Log.d("AsyaWatch", "track.php body[${html.length}]: ${html.take(600).replace("\n", " ")}")

        // HTML içinde video URL'si ara
        val videoUrl = findVideoUrlInPlayerHtml(html)
        if (videoUrl != null) {
            Log.d("AsyaWatch", "track.php içinde video URL: ${videoUrl.take(120)}")
            val isHls = videoUrl.contains(".m3u8", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name FourPichive (track)",
                    url = videoUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = iframeUrl
                    this.headers = browserLikeHeaders(iframeUrl)
                    this.quality = qualityValue("", videoUrl)
                }
            )
            return true
        }

        // HTML içinde başka player URL'leri ara
        val playerUrls = Regex(
            """(?i)(?:https?:)?//[^\s"'<>]+(?:nm\.php|player\.php|embed\.php|get\.php|play\.php)\?[^\s"'<>]+"""
        ).findAll(html).map { it.value }.distinct().toList()

        for (pu in playerUrls) {
            Log.d("AsyaWatch", "track.php içinde player URL: ${pu.take(120)}")
            val presp = runCatching {
                app.get(pu, headers = browserLikeHeaders(iframeUrl), referer = iframeUrl)
            }.getOrNull() ?: continue
            Log.d("AsyaWatch", "  → HTTP ${presp.code}, bodyLen=${presp.text.length}")
            if (presp.isSuccessful) {
                val purl = findVideoUrlInPlayerHtml(presp.text)
                if (purl != null) {
                    Log.d("AsyaWatch", "  → video URL: ${purl.take(120)}")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name FourPichive (track+player)",
                            url = purl,
                            type = if (purl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = iframeUrl
                            this.headers = browserLikeHeaders(iframeUrl)
                            this.quality = qualityValue("", purl)
                        }
                    )
                    return true
                }
            }
        }

        // Altyazıları çıkar
        val localSeen = HashSet<String>()
        publishSubtitlesFromText(html, iframeUrl, localSeen, subtitleCallback, iframeUrl)

        return false
    }

    // =====================================================================
    // FourPichive iframe'i için özel extractor (güncellenmiş)
    // =====================================================================

    private suspend fun extractFourPichive(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = browserLikeHeaders(iframeUrl)
        val response = runCatching {
            app.get(iframeUrl, headers = headers, referer = referer, allowRedirects = true)
        }.getOrNull() ?: return false

        if (!response.isSuccessful) {
            Log.e("AsyaWatch", "FourPichive iframe HTTP ${response.code}")
            return false
        }

        val html = response.text
        if (html.isBlank()) return false

        // Çerezleri yakala (iframe'den)
        val cookies = runCatching {
            extractCookiesFromHeaders(response.headers)
        }.getOrNull()
        Log.d("AsyaWatch", "iframe Set-Cookie: ${cookies?.take(200) ?: "yok"}")

        val npConfig = parseNpConfig(html)
        val npId = npConfig?.optString("id")?.takeIf { it.isNotBlank() }
        Log.d("AsyaWatch", "NP_CONFIG: id=$npId, sources=${npConfig?.optJSONArray("sources")?.length() ?: 0}")

        // Altyazılar
        val localSeenSubs = HashSet<String>()
        runCatching {
            val subsArray = npConfig?.optJSONArray("subtitles")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val sub = subsArray.optJSONObject(i) ?: continue
                    val label = sub.optString("label", "Altyazı")
                    val url = sub.optString("url")
                    if (url.isNotBlank()) {
                        val subtitle = SubtitleFile(lang = label, url = url)
                        val subHeaders = siteHeaders.toMutableMap()
                        subHeaders["Referer"] = iframeUrl
                        subHeaders["Origin"] = fourPichiveOrigin
                        subHeaders["Accept"] = "text/vtt, text/plain, */*"
                        if (!cookies.isNullOrBlank()) subHeaders["Cookie"] = cookies
                        subtitle.headers = subHeaders
                        if (localSeenSubs.add(url)) subtitleCallback(subtitle)
                    }
                }
            }
        }
        if (npConfig == null) {
            publishSubtitlesFromText(html, iframeUrl, localSeenSubs, subtitleCallback, iframeUrl)
        }

        val masterUrl = npConfig?.optJSONArray("sources")?.optJSONObject(0)?.optString("master")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""(?i)"master"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() }

        if (masterUrl.isNullOrBlank()) {
            Log.e("AsyaWatch", "FourPichive: master URL bulunamadı")
            return false
        }

        Log.d("AsyaWatch", "FourPichive master: ${masterUrl.take(120)}")

        var finalUrl = masterUrl
        if (html.contains("NP_GATE") && npId != null) {
            // === 1. cf_clearance çerezini almaya çalış ===
            Log.d("AsyaWatch", "cf_clearance alınıyor...")
            val cfClearance = getCfClearanceCookie(iframeUrl)

            // === 2. cf_clearance ile track.php'yi dene ===
            if (cfClearance != null) {
                Log.d("AsyaWatch", "track.php deneniyor (cf_clearance ile)...")
                val trackSuccess = tryTrackPhp(iframeUrl, npId, cfClearance, subtitleCallback, callback)
                if (trackSuccess) {
                    Log.d("AsyaWatch", "track.php BAŞARILI!")
                    return true
                }
                Log.w("AsyaWatch", "track.php başarısız, PoW fallback...")
            } else {
                Log.w("AsyaWatch", "cf_clearance alınamadı, track.php atlanıyor")
            }

            // === 3. PoW'u dene (eski yöntem) ===
            val powResult = tryPowOnUrl(masterUrl, iframeUrl, npId, cookies)
            if (powResult != null) {
                finalUrl = powResult
                Log.d("AsyaWatch", "PoW BAŞARILI → ${finalUrl.take(120)}")
            } else {
                Log.w("AsyaWatch", "PoW başarısız, ham master URL + çerez verilecek")
            }
        }

        val isHls = finalUrl.contains(".m3u8", true)
        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val linkHeaders = headers.toMutableMap()
        if (!cookies.isNullOrBlank()) linkHeaders["Cookie"] = cookies

        callback(
            newExtractorLink(
                source = name,
                name = "$name FourPichive",
                url = finalUrl,
                type = type
            ) {
                this.referer = iframeUrl
                this.headers = linkHeaders
                this.quality = qualityValue("", finalUrl)
            }
        )
        Log.d("AsyaWatch", "FourPichive URL verildi (type=$type, cookie=${!cookies.isNullOrBlank()}): ${finalUrl.take(120)}")
        return true
    }

    // =====================================================================
    // loadLinks
    // =====================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val emittedUrls = HashSet<String>()
        val seenCandidates = HashSet<String>()
        val seenSubtitleUrls = LinkedHashSet<String>()
        val seenSubtitlePages = LinkedHashSet<String>()
        val seenSubtitleManifests = LinkedHashSet<String>()
        val chosenSubtitles = LinkedHashMap<String, SubtitleFile>()

        fun subtitleTrackPriority(subtitle: SubtitleFile): Int {
            val path = runCatching { URI(subtitle.url).path.orEmpty() }
                .getOrDefault(subtitle.url.substringBefore('?'))
            val segments = path.split('/').filter { it.isNotBlank() }
            val detectedLanguage = subtitleUrlLanguage(subtitle.url)
            val queryLanguage = subtitleUrlQueryLanguage(subtitle.url)
            val languageDirectory = segments.dropLast(1).any { directory ->
                val value = when {
                    directory.startsWith("sub_", true) -> directory.substring(4)
                    directory.startsWith("sub-", true) -> directory.substring(4)
                    else -> ""
                }
                value.isNotBlank() &&
                    explicitSubtitleLanguage(value) == detectedLanguage
            }
            val fileNameLanguage =
                explicitSubtitleLanguage(segments.lastOrNull().orEmpty()) == detectedLanguage
            val isHlsSubtitlePlaylist = Regex("""(?i)\.m3u8$""").containsMatchIn(path)
            val standaloneTextTrack = Regex(
                """(?i)\.(?:vtt|srt|ass|ssa|ttml|dfxp|webvtt)$"""
            ).containsMatchIn(path)

            return when {
                detectedLanguage == null && explicitSubtitleLanguage(subtitle.lang) == null -> 0
                languageDirectory && isHlsSubtitlePlaylist -> 12
                languageDirectory -> 10
                fileNameLanguage -> 9
                queryLanguage != null && queryLanguage == detectedLanguage -> 8
                detectedLanguage != null -> 6
                standaloneTextTrack -> 4
                isHlsSubtitlePlaylist -> 2
                else -> 1
            }
        }

        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val language = subtitleUrlLanguage(subtitle.url)
                ?: explicitSubtitleLanguage(subtitle.lang)
            if (language == "Türkçe" || language == "English") {
                val existing = chosenSubtitles[language]
                if (existing == null ||
                    subtitleTrackPriority(subtitle) > subtitleTrackPriority(existing)
                ) {
                    subtitle.lang = language
                    chosenSubtitles[language] = subtitle
                }
            }
        }

        suspend fun processCandidate(raw: String, referer: String, quality: String) {
            val candidate = absoluteUrl(raw, referer) ?: return
            if (!seenCandidates.add(candidate)) return
            if (candidate.startsWith("<", true) || candidate.contains("<!doctype", true)) return

            val candidatePath = runCatching { URI(candidate).path.orEmpty() }.getOrDefault("")
            if (candidatePath.isBlank() || candidatePath == "/") return

            if (isDirectMedia(candidate)) {
                if (candidate.contains(".m3u8", true)) {
                    extractSubtitlesFromManifest(
                        candidate, referer, seenSubtitleUrls, seenSubtitleManifests, emitSubtitle
                    )
                }
                if (emittedUrls.add(candidate)) {
                    val type = if (candidate.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name + " " + quality.ifBlank { "Kaynak" },
                            url = candidate,
                            type = type
                        ) {
                            this.referer = referer
                            this.quality = qualityValue(quality, candidate)
                        }
                    )
                    found = true
                }
                return
            }

            extractSubtitlesFromPlayerPage(
                candidate, referer, seenSubtitleUrls, seenSubtitlePages,
                seenSubtitleManifests, emitSubtitle
            )

            val candidateHost = runCatching { URI(candidate).host?.lowercase() }.getOrNull()
            val isFourPichive = candidateHost == "four.pichive.online" ||
                candidateHost?.endsWith(".four.pichive.online") == true ||
                candidateHost == "pichive.online" ||
                candidateHost?.endsWith(".pichive.online") == true

            if (isFourPichive) {
                Log.d("AsyaWatch", "FourPichive (özel extractor): ${candidate.take(120)}...")
                val extracted = runCatching {
                    extractFourPichive(candidate, referer, emitSubtitle) { link ->
                        if (emittedUrls.add(link.url)) {
                            callback(link)
                            found = true
                        }
                    }
                }.getOrElse { error ->
                    Log.e("AsyaWatch", "FourPichive hata: ${error.message}")
                    false
                }
                if (!extracted) Log.w("AsyaWatch", "FourPichive başarısız")
                return
            }

            try {
                loadExtractor(candidate, referer, emitSubtitle) { link ->
                    if (emittedUrls.add(link.url)) callback(link)
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        if (isDirectMedia(data)) processCandidate(data, "$mainUrl/", "")

        val page = runCatching { getPageData(data) }.getOrNull()
        val root = page?.secureData
        val related = objectField(root, "RelatedResults", "relatedResults")
        val isSeries = data.contains("/dizi/", true) ||
            objectField(related, "getSerieSeasonAndEpisodes", "getSeriesSeasons") != null

        if (page != null) {
            publishSubtitlesFromText(root?.toString().orEmpty(), data, seenSubtitleUrls, emitSubtitle)
            publishSubtitlesFromText(page.document.html(), data, seenSubtitleUrls, emitSubtitle)

            val sources = sourceItems(root, isSeries)
            val relatedForSources = objectField(root, "RelatedResults", "relatedResults")
            val episodeSourceHolder = objectField(relatedForSources, "getEpisodeSources", "getEpisodeSource")
            val episodeSourceRows = arrayField(episodeSourceHolder, "result", "sources")

            fun jsonKeys(value: JSONObject?): String {
                if (value == null) return "yok"
                val names = ArrayList<String>()
                val iterator = value.keys()
                while (iterator.hasNext() && names.size < 20) names.add(iterator.next())
                return names.joinToString().ifBlank { "boş" }
            }

            Log.d(
                "AsyaWatch",
                "Kaynak veri yapısı: secureData=${root != null}; rootKeys=${jsonKeys(root)}; relatedKeys=${jsonKeys(relatedForSources)}; episodeHolder=${episodeSourceHolder != null}; satır=${episodeSourceRows?.length() ?: 0}"
            )
            Log.d("AsyaWatch", "Kaynak analizi: kayıt=${sources.size}; dizi=$isSeries")

            for ((sourceIndex, source) in sources.withIndex()) {
                publishSubtitlesFromText(source.raw, data, seenSubtitleUrls, emitSubtitle)
                val candidates = extractCandidates(source.raw, data)
                val hosts = candidates.mapNotNull { candidate ->
                    runCatching { URI(candidate).host?.lowercase() }.getOrNull()
                }.distinct()
                Log.d(
                    "AsyaWatch",
                    "Kaynak ${sourceIndex + 1}: kalite=${source.quality.ifBlank { "bilinmiyor" }}; aday=${candidates.size}; alanadları=${hosts.joinToString().ifBlank { "yok" }}"
                )
                for (candidate in candidates) {
                    processCandidate(candidate, data, source.quality)
                }
            }

            val pageCandidates = extractCandidates(page.document.html(), data)
            val pageHosts = pageCandidates.mapNotNull { candidate ->
                runCatching { URI(candidate).host?.lowercase() }.getOrNull()
            }.distinct()

            Log.d(
                "AsyaWatch",
                "Sayfa adayları: adet=${pageCandidates.size}; alanadları=${pageHosts.joinToString().ifBlank { "yok" }}"
            )
            for (candidate in pageCandidates) {
                processCandidate(candidate, data, "")
            }
            if (looksLikePlayer(data)) processCandidate(data, "$mainUrl/", "")
        }

        chosenSubtitles.values.forEach { subtitleCallback(it) }
        return found
    }

    // =====================================================================
    // Yardımcı fonksiyonlar (aynı kalanlar)
    // =====================================================================

    // ... (isRateLimitResponse, noteRateLimit, isCoolingDown, titleFromUrl, field, objectField, arrayField, parseObject, decodeBase64Text, decodeApiResponse, absoluteUrl, imageUrl, imageFromJson, imageFromElement, scoreFrom, pageSecureData, nextFlightPayloads, jsonObjectAfterKey, nextFlightObject, detailCatalogEntry, mergeDetailObjects, getPageData, postJson, searchResponseFrom, getExploreJson, getMainPage, search, quickSearch, detailTitle, normalizedImageTitle, detailPoster, fillMissingPosters, detailYear, detailTags, detailActors, detailActorsFromDocument, detailTrailer, parseEpisodes, parseEpisodesFromDocument, load, sourceItems, looksLikePlayer, extractCandidates, looksLikeSubtitle, normalizeSubtitleText, explicitSubtitleLanguage, subtitleUrlQueryLanguage, subtitleUrlLanguage, subtitleLanguage, resolveSubtitleUrl, subtitleUrls, publishSubtitlesFromText, extractSubtitlesFromManifest, extractSubtitlesFromPlayerPage, isDirectMedia, qualityValue) ...
    // (Bu fonksiyonlar önceki kodla tamamen aynı olduğu için burada tekrar edilmiyor)
}
