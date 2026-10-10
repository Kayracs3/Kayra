package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {

    override var mainUrl = "https://dizipal1588.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    // Cache poster URLs discovered from individual episode pages. Failed lookups are
    // remembered too, so repeated home-page refreshes do not refetch the same page.
    private val episodePosterCache = LinkedHashMap<String, String>()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Güncel Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/hd-film-izle" to "Filmler",
        "$mainUrl/kanal/exxen" to "Exxen",
        "$mainUrl/kanal/disney" to "Disney+",
        "$mainUrl/kanal/netflix" to "Netflix",
        "$mainUrl/kanal/amazon" to "Amazon",
        "$mainUrl/kanal/apple-tv" to "Apple TV+",
        "$mainUrl/kanal/max" to "Max",
        "$mainUrl/kanal/hulu" to "Hulu",
        "$mainUrl/kanal/tod" to "TOD",
        "$mainUrl/kanal/tabii" to "tabii",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = runCatching {
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false,
        )

        val baseUrl = documentBase(document, url)
        val isLatestEpisodesPage = request.data.trimEnd('/') == mainUrl.trimEnd('/')
        var results = if (isLatestEpisodesPage) {
            parseCurrentEpisodeSection(document, baseUrl)
        } else {
            parseListing(document, baseUrl)
        }

        // The homepage carousel may expose episode names but omit their posters
        // from the anchor itself. Consult the full "Tümünü Gör" listing whenever
        // any poster is missing, even if the homepage already returned 11+ entries.
        if (isLatestEpisodesPage &&
            (results.size < 11 || results.any { it.posterUrl.isNullOrBlank() })
        ) {
            val moreUrl = findCurrentEpisodesMoreUrl(document, baseUrl)
                ?: "$mainUrl/yeni-eklenen-dizi-bolumler?page=2"
            val moreDocument = runCatching {
                app.get(
                    moreUrl,
                    headers = headers + ("Referer" to url),
                    referer = url,
                    allowRedirects = true,
                    timeout = 12000,
                ).document
            }.onFailure {
                Log.d("DiziPal", "Tümünü Gör istek hatası: $moreUrl; ${it.message}")
            }.getOrNull()

            if (moreDocument != null) {
                val moreBaseUrl = documentBase(moreDocument, moreUrl)
                val moreResults = parseCurrentEpisodeSection(moreDocument, moreBaseUrl)
                val homepageCount = results.size
                val homepagePosterCount = results.count { !it.posterUrl.isNullOrBlank() }
                if (moreResults.isNotEmpty()) {
                    // Full-list posters take precedence; homepage results fill
                    // missing posters for entries that are absent from that page.
                    results = mergeEpisodeSearchResults(moreResults, results)
                }
                Log.d(
                    "DiziPal",
                    "Tümünü Gör kontrolü: adres=$moreUrl; ana=$homepageCount/" +
                        "$homepagePosterCount afiş; tam liste=${moreResults.size}/" +
                        "${moreResults.count { !it.posterUrl.isNullOrBlank() }} afiş; " +
                        "birleşik=${results.size}/" +
                        "${results.count { !it.posterUrl.isNullOrBlank() }} afiş",
                )
            } else {
                Log.d("DiziPal", "Tümünü Gör sayfası açılamadı: $moreUrl")
            }
        }

        // If a homepage/listing card exposes no image URL, try the exact episode page
        // as a fallback instead of leaving that entry posterless.
        if (isLatestEpisodesPage) {
            results = resolveMissingEpisodePosters(results, baseUrl)
        }

        Log.d(
            "DiziPal",
            "Ana sayfa yükleme (${request.name}): ${results.size} kayıt, " +
                "${results.count { !it.posterUrl.isNullOrBlank() }} afiş",
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = page < 50 && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/arama-yap?q=$encoded",
            "$mainUrl/arama-yap?search=$encoded",
        )

        for (url in urls) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = "$mainUrl/arama-yap",
                    allowRedirects = true,
                ).document
            }.getOrNull() ?: continue

            val baseUrl = documentBase(document, url)
            val results = parseListing(document, baseUrl)
                .filterNot { isEpisodeUrl(it.url) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = normalizeUrl(url, mainUrl)

        val response = runCatching {
            app.get(
                pageUrl,
                headers = headers,
                referer = "$mainUrl/",
                allowRedirects = true,
            )
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document

        if (isEpisodeUrl(pageUrl)) {
            return loadEpisode(pageUrl, document)
        }

        val title = pageTitle(document, pageUrl) ?: return null
        val poster = posterOf(document)
        val plot = pagePlot(document)
        val year = pageYear(document)
        val rating = pageRating(document)

        val lower = pageUrl.lowercase()
        val isMovie = lower.contains("/movies/") || lower.contains("/movie/")

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl,
            ) {
                posterUrl = poster
                posterHeaders = posterRequestHeaders(documentBase(document, pageUrl))
                this.plot = plot
                this.year = year
                rating?.let { score = Score.from10(it) }
            }
        }

        val episodes = parseEpisodes(document, poster, documentBase(document, pageUrl))

        return newTvSeriesLoadResponse(
            title,
            pageUrl,
            TvType.TvSeries,
            episodes,
        ) {
            posterUrl = poster
            posterHeaders = posterRequestHeaders(documentBase(document, pageUrl))
            this.plot = plot
            this.year = year
            rating?.let { score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = normalizeUrl(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        val response = runCatching {
            app.get(
                sourceUrl,
                headers = headers,
                referer = "$mainUrl/",
                timeout = 15000,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        val document = response.document
        val raw = buildString {
            append(response.text)
            append("\n")
            append(document.html())
            document.select("script, noscript, template").forEach {
                append("\n")
                append(it.data())
                append("\n")
                append(it.html())
            }
        }.decodeEscapes()

        var found = false

        // Direct media fallback.
        for (media in findMediaUrls(raw)) {
            if (emitMediaLink(media, sourceUrl, callback)) {
                found = true
            }
        }

        val encrypted = extractEncryptedConfig(raw)

        if (encrypted != null) {
            val player = decryptDizipalConfig(encrypted)

            if (player.isNotBlank()) {
                Log.d("DiziPal", "Player URL: " + player.take(200))

                val iframeUrl = normalizeUrl(player, sourceUrl)

                val iframeResponse = runCatching {
                    app.get(
                        iframeUrl,
                        headers = headers + (
                            "Referer" to sourceUrl
                        ),
                        timeout = 15000,
                        allowRedirects = true,
                    )
                }.getOrNull()

                if (iframeResponse != null && iframeResponse.isSuccessful) {
                    val iframeDocument = iframeResponse.document
                    val iframeHtml = buildString {
                        append(iframeResponse.text)
                        append("\n")
                        append(iframeDocument.html())
                        iframeDocument.select("script, noscript, template").forEach {
                            append("\n")
                            append(it.data())
                            append("\n")
                            append(it.html())
                        }
                    }.decodeEscapes()

                    if (extractSubtitles(
                            iframeHtml,
                            iframeUrl,
                            subtitleCallback,
                        )
                    ) {
                        found = true
                    }

                    // Standard DPlayer flow:
                    // window.openPlayer("playlistId") -> source2.php?v=playlistId
                    val playlistId = Regex(
                        """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""",
                        RegexOption.IGNORE_CASE,
                    ).find(iframeHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (!playlistId.isNullOrBlank()) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val source2Url =
                            origin.trimEnd('/') +
                                "/source2.php?v=" +
                                URLEncoder.encode(playlistId, "UTF-8")

                        val source2Response = runCatching {
                            app.get(
                                source2Url,
                                headers = headers + mapOf(
                                    "Referer" to iframeUrl,
                                    "Accept" to "application/json,text/plain,*/*",
                                ),
                                timeout = 15000,
                                allowRedirects = true,
                            )
                        }.getOrNull()

                        if (source2Response != null && source2Response.isSuccessful) {
                            val source2Text =
                                source2Response.text.decodeEscapes()

                            for (media in extractFileUrls(source2Text)) {
                                if (emitMediaLink(
                                        media,
                                        iframeUrl,
                                        callback,
                                    )
                                ) {
                                    found = true
                                }
                            }

                            if (extractSubtitles(
                                    source2Text,
                                    iframeUrl,
                                    subtitleCallback,
                                )
                            ) {
                                found = true
                            }
                        }
                    }

                    // Some player revisions put the final URL directly in the iframe.
                    for (media in findMediaUrls(iframeHtml)) {
                        if (emitMediaLink(
                                media,
                                iframeUrl,
                                callback,
                            )
                        ) {
                            found = true
                        }
                    }

                    // HTML video/source fallback.
                    iframeDocument.select(
                        "video[src], video source[src], source[src], " +
                            "video[data-src], source[data-src]"
                    ).forEach { element ->
                        val media = element.attr("src")
                            .ifBlank { element.attr("data-src") }

                        if (media.isNotBlank()) {
                            val finalUrl = normalizeUrl(media, iframeUrl)
                            if (isMediaUrl(finalUrl) &&
                                emitMediaLink(
                                    finalUrl,
                                    iframeUrl,
                                    callback,
                                )
                            ) {
                                found = true
                            }
                        }
                    }
                }
            }
        }

        // Direct iframe/media fallback on the source page.
        document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], " +
                "source[src], [data-video-url], [data-stream]"
        ).forEach { element ->
            val rawUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video-url") }
                .ifBlank { element.attr("data-stream") }

            if (rawUrl.isBlank()) return@forEach

            val target = normalizeUrl(rawUrl, sourceUrl)

            if (isMediaUrl(target) &&
                emitMediaLink(target, sourceUrl, callback)
            ) {
                found = true
            }
        }

        // Public HTML subtitle tracks.
        document.select("track[src], track[data-src]").forEach { track ->
            val rawUrl = track.attr("src")
                .ifBlank { track.attr("data-src") }

            if (rawUrl.isBlank()) return@forEach

            runCatching {
                subtitleCallback(
                    newSubtitleFile(
                        lang = track.attr("label").ifBlank { "Türkçe" },
                        url = normalizeUrl(rawUrl, sourceUrl),
                    ) {
                        headers = mediaHeaders(sourceUrl)
                    },
                )
            }
        }

        return found
    }

    private suspend fun resolveMissingEpisodePosters(
        results: List<SearchResponse>,
        baseUrl: String,
    ): List<SearchResponse> {
        var requested = 0
        var fixed = 0

        results.forEach { item ->
            if (!item.posterUrl.isNullOrBlank() || !isEpisodeUrl(item.url)) {
                return@forEach
            }

            val key = canonicalContentPath(normalizeUrl(item.url, baseUrl))
            val cachedPoster = episodePosterCache[key]
            if (!cachedPoster.isNullOrBlank()) {
                item.posterUrl = cachedPoster
                item.posterHeaders = posterRequestHeaders(item.url)
                fixed++
                return@forEach
            }

            // Cap fallback requests per refresh, but do not permanently remember
            // failures: a timeout or temporary site error must be retryable.
            if (requested >= 8) return@forEach
            requested++

            Log.d("DiziPal", "Afiş yedeği deneniyor: ${item.url}")
            val poster = findPosterOnEpisodeDetailPage(item.url)
            if (!poster.isNullOrBlank()) {
                episodePosterCache[key] = poster
                item.posterUrl = poster
                item.posterHeaders = posterRequestHeaders(item.url)
                fixed++
            }
        }

        Log.d(
            "DiziPal",
            "Eksik afiş yedeği: $requested bölüm sayfası kontrol edildi, " +
                "$fixed afiş tamamlandı, " +
                "${results.count { it.posterUrl.isNullOrBlank() }} afiş hâlâ eksik",
        )
        return results
    }

    private suspend fun findPosterOnEpisodeDetailPage(episodeUrl: String): String? {
        val response = runCatching {
            app.get(
                episodeUrl,
                headers = headers + ("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                timeout = 3500,
                allowRedirects = true,
            )
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document
        val baseUrl = documentBase(document, episodeUrl)
        val title = pageTitle(document, baseUrl).orEmpty()

        // Prefer the provider's strict detail-page poster parser first.
        posterOf(document)
            ?.takeIf(::isLikelyPosterUrl)
            ?.let { return it }

        // On an exact /bolum/ page, its OpenGraph/Twitter image belongs to
        // this episode's series. The CDN filename is often an opaque ID, so
        // requiring the series title to appear in the URL incorrectly drops it.
        val metadataImages = document.select(
            "meta[property='og:image'], meta[name='og:image'], " +
                "meta[name='twitter:image'], meta[property='twitter:image'], " +
                "meta[itemprop='image'], link[rel='image_src']"
        ).mapNotNull { element ->
            val raw = element.attr("content").ifBlank { element.attr("href") }
            raw.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, baseUrl) }
        }.distinct()

        metadataImages.firstOrNull { image ->
            isLikelyPosterUrl(image)
        }?.let {
            Log.d("DiziPal", "Afiş yedeği: bölüm meta görseli bulundu")
            return it
        }

        // The episode page frequently links to its parent series separately
        // from the episode URL. Select only a non-recommendation series link
        // whose label overlaps the episode title, then read its own image.
        val titleTokens = normalizeTitleForMatch(title)
            .split(" ")
            .filter { it.length >= 3 && !it.all(Char::isDigit) }
            .filterNot { it in setOf("sezon", "season", "bolum", "episode") }
            .toSet()

        val seriesCandidates = document.select("a[href]").mapNotNull { anchor ->
            val target = normalizeUrl(anchor.attr("href"), baseUrl)
            if (!isCatalogItemUrl(target) || isInsideHardExcludedSection(anchor)) {
                return@mapNotNull null
            }

            val label = listOf(
                anchor.text(),
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.selectFirst("img")?.attr("alt").orEmpty(),
                target.substringAfterLast('/').replace('-', ' '),
            ).filter { it.isNotBlank() }.joinToString(" ")
            val anchorTokens = normalizeTitleForMatch(label)
                .split(" ")
                .filter { it.length >= 3 && !it.all(Char::isDigit) }
                .toSet()
            val score = if (titleTokens.isEmpty()) 0.0 else {
                titleTokens.intersect(anchorTokens).size.toDouble() /
                    titleTokens.size.toDouble()
            }

            val poster = posterFromElement(anchor, baseUrl)
                ?.takeIf(::isLikelyPosterUrl)
                ?: return@mapNotNull null

            Triple(score, poster, label)
        }

        val matchedSeries = seriesCandidates
            .filter { it.first >= 0.30 }
            .maxByOrNull { it.first }
            ?.second
        if (!matchedSeries.isNullOrBlank()) {
            Log.d("DiziPal", "Afiş yedeği: bölüm sayfasındaki dizi kartı bulundu")
            return matchedSeries
        }

        // If the episode page links to its parent series but does not embed its
        // poster, fetch that exact series page as the last reliable fallback.
        val seriesTitle = title
            .replace(
                Regex("""(?i)\s*\d+\s*(?:\.\s*)?(?:sezon|season)\s*\d+\s*(?:\.\s*)?(?:bölüm|bolum|episode).*?$"""),
                "",
            )
            .replace(Regex("""(?i)\s*\d+\s*[x×]\s*\d+.*$"""), "")
            .trim()
        val seriesTokens = posterMatchTokens(seriesTitle)
        val seriesPage = document.select("a[href]")
            .mapNotNull { anchor ->
                val target = normalizeUrl(anchor.attr("href"), baseUrl)
                if (!isCatalogItemUrl(target) ||
                    isInsideHardExcludedSection(anchor)
                ) return@mapNotNull null

                val label = listOf(
                    anchor.text(),
                    anchor.attr("title"),
                    anchor.attr("aria-label"),
                    anchor.selectFirst("img")?.attr("alt").orEmpty(),
                    target.substringAfterLast('/').replace('-', ' '),
                ).filter { it.isNotBlank() }.joinToString(" ")
                val tokens = posterMatchTokens(label)
                val score = if (seriesTokens.isEmpty()) 0.0 else {
                    seriesTokens.intersect(tokens).size.toDouble() /
                        seriesTokens.size.toDouble()
                }
                if (score < 0.50) return@mapNotNull null
                score to target
            }
            .maxByOrNull { it.first }
            ?.second

        if (!seriesPage.isNullOrBlank()) {
            val seriesResponse = runCatching {
                app.get(
                    seriesPage,
                    headers = headers + ("Referer" to episodeUrl),
                    referer = episodeUrl,
                    timeout = 2500,
                    allowRedirects = true,
                )
            }.getOrNull()
            if (seriesResponse != null && seriesResponse.isSuccessful) {
                val seriesDocument = seriesResponse.document
                val seriesBase = documentBase(seriesDocument, seriesPage)
                posterOf(seriesDocument)
                    ?.takeIf(::isLikelyPosterUrl)
                    ?.let {
                        Log.d("DiziPal", "Afiş yedeği: dizi detay sayfasından bulundu")
                        return it
                    }

                val imageFromSeries = seriesDocument.select(
                    "meta[property='og:image'], meta[name='og:image'], " +
                        "meta[name='twitter:image'], meta[property='twitter:image'], " +
                        "[itemprop='image']"
                ).mapNotNull { element ->
                    val raw = element.attr("content")
                        .ifBlank { element.attr("src") }
                        .ifBlank { element.attr("data-src") }
                    raw.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, seriesBase) }
                }.firstOrNull(::isLikelyPosterUrl)
                if (!imageFromSeries.isNullOrBlank()) {
                    Log.d("DiziPal", "Afiş yedeği: dizi meta görselinden bulundu")
                    return imageFromSeries
                }
            }
        }

        Log.d("DiziPal", "Afiş yedeği sonuçsuz: $episodeUrl")
        return null
    }

    private suspend fun loadEpisode(
        url: String,
        document: Document,
    ): LoadResponse {
        val title = pageTitle(document, url) ?: "DiziPal Bölüm"
        val poster = posterOf(document)
        val numbers = episodeNumbersFrom(title) ?: episodeNumbersFrom(url.substringBefore("?"))

        val episode = newEpisode(url) {
            name = if (numbers != null) {
                "Bölüm " + numbers.second
            } else {
                title
            }

            if (numbers != null) {
                season = numbers.first
                this.episode = numbers.second
            }

            posterUrl = poster
        }

        val seriesTitle = title
            .replace(
                Regex(
                    """(?i)\s*\d+\s*[xX]\s*\d+.*$"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?i)\s*\d+\.\s*Sezon\s*\d+\.\s*Bölüm.*$"""
                ),
                "",
            )
            .trim()
            .ifBlank { title }

        return newTvSeriesLoadResponse(
            seriesTitle,
            url,
            TvType.TvSeries,
            listOf(episode),
        ) {
            posterUrl = poster
            posterHeaders = posterRequestHeaders(documentBase(document, url))
            plot = pagePlot(document)
            year = pageYear(document)
            pageRating(document)?.let { score = Score.from10(it) }
        }
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
        baseUrl: String,
    ): List<Episode> {
        val result = ArrayList<Episode>()
        val linksByEpisode = LinkedHashMap<String, MutableList<Element>>()

        // Collect all visible links before filtering by episode numbering.
        // Some cards expose season/episode text on a sibling link rather than
        // on the title link, so grouping by canonical URL is important.
        document.select("a[href]").forEach { link ->
            val href = normalizeUrl(link.attr("href"), baseUrl)
            if (!isEpisodeUrl(href)) return@forEach

            val key = canonicalContentPath(href)
            linksByEpisode.getOrPut(key) { ArrayList() }.add(link)
        }

        val seen = HashSet<String>()
        linksByEpisode.values.forEachIndexed { index, links ->
            val href = normalizeUrl(links.first().attr("href"), baseUrl)
            val key = canonicalContentPath(href)
            if (!seen.add(key)) return@forEachIndexed

            // Use a card's text for parsing numbers, but only if the card
            // contains links to this one episode. This avoids reading details
            // from neighbouring episode cards.
            val cardContext = episodeCardContext(links.first(), href, baseUrl)
            // Only parse numbers from this episode's own title labels or URL.
            // The surrounding card may contain season tabs and unrelated numbers.
            val numbers = links.asSequence()
                .flatMap { link ->
                    sequenceOf(
                        link.text(),
                        link.attr("title"),
                        link.attr("aria-label"),
                        link.selectFirst("img")?.attr("alt").orEmpty(),
                    )
                }
                .mapNotNull { candidate -> episodeNumbersFrom(candidate) }
                .firstOrNull()
                ?: episodeNumbersFrom(href.substringBefore("?"))

            // Do not drop a valid /bolum/ URL simply because the website's
            // markup omits "Sezon/Bölüm" from its text. This was why only a
            // few of the site's episode cards could appear at a time.
            val episodeName = episodeNameFallback(links, cardContext, href, index + 1)
                .ifBlank {
                    if (numbers != null) "Bölüm " + numbers.second else "Bölüm " + (index + 1)
                }

            // Only use an image inside an anchor for this exact episode URL.
            // Never borrow the first image from a parent that may contain
            // several episode cards.
            val episodePoster = links.asSequence()
                .mapNotNull { link ->
                    posterFromElement(link, baseUrl)
                        ?.takeIf {
                            it.startsWith("http://", true) ||
                                it.startsWith("https://", true)
                        }
                }
                .firstOrNull()
                ?: poster

            result += newEpisode(href) {
                name = episodeName
                if (numbers != null) {
                    season = numbers.first
                    episode = numbers.second
                }
                posterUrl = episodePoster
            }
        }

        // Also inspect script/template data. The previous pattern only caught
        // absolute URLs; sites may store these as relative "/bolum/..." paths.
        val html = document.html().decodeEscapes()
        val absoluteEpisodeUrl = Regex(
            """(?i)(?:https?:)?//[^"'<>\\s]+/bolum/[^"'<>\\s"'?&,#]+"""
        )
        for (match in absoluteEpisodeUrl.findAll(html)) {
            addScriptEpisode(match.value, baseUrl, poster, seen, result)
        }

        val relativeEpisodeUrl = Regex(
            """(?i)(?<![A-Za-z0-9])(/bolum/[^"'<>\\s"'?&,#]+)"""
        )
        for (match in relativeEpisodeUrl.findAll(html)) {
            addScriptEpisode(
                match.groupValues.getOrNull(1).orEmpty(),
                baseUrl,
                poster,
                seen,
                result,
            )
        }

        val finalEpisodes = result
            .distinctBy { canonicalContentPath(it.data) }
            .sortedWith(
                compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() }
            )

        Log.d(
            "DiziPal",
            "Bölüm ayrıştırma: " + finalEpisodes.size +
                " bölüm bulundu; " +
                finalEpisodes.count { !it.posterUrl.isNullOrBlank() } +
                " bölümde afiş var",
        )
        return finalEpisodes
    }

    private fun episodeCardContext(
        link: Element,
        episodeUrl: String,
        baseUrl: String,
    ): String {
        val targetPath = canonicalContentPath(normalizeUrl(episodeUrl, baseUrl))
        var current: Element? = link.parent()

        repeat(6) {
            val card = current ?: return ""
            val episodeTargets = card.select("a[href]")
                .mapNotNull { candidate ->
                    normalizeUrl(candidate.attr("href"), baseUrl)
                        .takeIf { isEpisodeUrl(it) }
                }
                .distinctBy { canonicalContentPath(it) }

            if (episodeTargets.size == 1 &&
                canonicalContentPath(episodeTargets.first()) == targetPath
            ) {
                val imageLabels = card.select("img[alt], img[title], [aria-label], [title]")
                    .map { element ->
                        element.attr("alt") + " " +
                            element.attr("title") + " " +
                            element.attr("aria-label")
                    }
                    .joinToString(" ")

                return card.text() + " " + imageLabels
            }

            current = card.parent()
        }
        return ""
    }

    private fun episodeNameFallback(
        links: List<Element>,
        cardContext: String,
        episodeUrl: String,
        fallbackNumber: Int,
    ): String {
        val ignored = setOf(
            "izle", "bölümü izle", "bolumu izle", "oynat", "fragman",
            "watch", "play", "poster", "afiş", "afis",
        )
        val candidates = buildList {
            links.forEach { link ->
                add(link.attr("title"))
                add(link.attr("aria-label"))
                add(link.selectFirst("img")?.attr("alt").orEmpty())
                add(link.text())
            }
            add(cardContext)
        }.map { cleanCardTitle(it).trim() }
            .filter { it.isNotBlank() && it.lowercase() !in ignored }

        candidates.firstOrNull {
            Regex("""(?i)(sezon|season|bölüm|bolum|episode)\s*\d+""")
                .containsMatchIn(it)
        }?.let { return it }

        candidates.firstOrNull { it.length in 4..100 }
            ?.let { return it }

        val slug = runCatching {
            URI(episodeUrl).path.orEmpty()
                .substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(Regex("""(?i)\b(bolum|episode|sezon|season)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }.getOrDefault("")

        return slug.takeIf { it.isNotBlank() } ?: "Bölüm $fallbackNumber"
    }

    private fun addScriptEpisode(
        rawUrl: String,
        baseUrl: String,
        poster: String?,
        seen: MutableSet<String>,
        result: MutableList<Episode>,
    ) {
        val href = normalizeUrl(rawUrl, baseUrl)
        if (!isEpisodeUrl(href)) return

        val key = canonicalContentPath(href)
        if (!seen.add(key)) return

        // Script-only links may not have card titles or images. Preserve every
        // unique episode route and use the series poster as a safe fallback.
        val numbers = episodeNumbersFrom(href)
        result += newEpisode(href) {
            name = if (numbers != null) {
                "Bölüm " + numbers.second
            } else {
                episodeNameFallback(emptyList(), "", href, result.size + 1)
            }
            if (numbers != null) {
                season = numbers.first
                episode = numbers.second
            }
            posterUrl = poster
        }
    }

    private fun episodeListingTitle(
        link: Element,
        episodeUrl: String,
        baseUrl: String,
        card: Element?,
    ): String {
        val cardContext = episodeCardContext(link, episodeUrl, baseUrl)
        val candidates = listOf(
            link.selectFirst("h1,h2,h3,h4,.title,.name")?.text(),
            link.attr("title"),
            link.attr("aria-label"),
            link.text(),
            link.selectFirst("img")?.attr("alt"),
            card?.selectFirst(".title,.name,h1,h2,h3,h4")?.text(),
            card?.text(),
            cardContext,
        ).filterNot { it.isNullOrBlank() }.map { it.orEmpty().trim() }

        val raw = candidates.firstOrNull {
            Regex("""(?i)(?:sezon|season|bölüm|bolum|episode)\s*\d+""")
                .containsMatchIn(it)
        } ?: candidates.firstOrNull().orEmpty()

        var title = raw
            .replace(Regex("""\s+"""), " ")
            .trim()
        title = title.replace(
            Regex("""(?i)\s+\d+\s+(?:saniye|dakika|saat|gün|gun|hafta|ay|yıl|yil)\s+önce\s*$"""),
            "",
        ).trim()
        title = title.replace(
            Regex("""(?i)^\s*\d+\s+(?=.*(?:sezon|season|bölüm|bolum|episode))"""),
            "",
        ).trim()
        title = title.replace(Regex("""(?i)^(?:dublaj|altyazı)\s+"""), "").trim()

        if (title.isNotBlank()) return title

        return runCatching {
            URI(episodeUrl).path.orEmpty()
                .substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
                .trim()
        }.getOrDefault("").ifBlank { "Bölüm" }
    }

    private fun findCurrentEpisodesMoreUrl(
        document: Document,
        baseUrl: String,
    ): String? {
        val heading = document.select("h1,h2,h3,h4,h5,h6").firstOrNull {
            val title = normalizeTitleForMatch(it.text())
            title.contains("guncel bolum") ||
                title.contains("son eklenen bolum") ||
                title.contains("yeni bolumler")
        } ?: return null

        var current: Element? = heading.parent()
        repeat(10) {
            val container = current ?: return null
            val hasEpisodeLinks = container.select("a[href]").any { link ->
                isEpisodeUrl(normalizeUrl(link.attr("href"), baseUrl))
            }
            if (hasEpisodeLinks) {
                val more = container.select("a[href]").firstOrNull { link ->
                    val label = normalizeTitleForMatch(
                        link.text() + " " +
                            link.attr("aria-label") + " " +
                            link.attr("title")
                    )
                    label.contains("tumunu gor") ||
                        label.contains("tum bolumleri gor") ||
                        label.contains("hepsini gor")
                }

                if (more != null) {
                    val href = normalizeUrl(more.attr("href"), baseUrl)
                    val samePage = canonicalContentPath(href) ==
                        canonicalContentPath(baseUrl)
                    val sameSite = originOf(href) == originOf(baseUrl)
                    if (href.startsWith("http", true) && !samePage && sameSite) {
                        return href
                    }
                }
            }
            current = container.parent()
        }

        // Some templates place the control just outside the heading container.
        for (link in document.select("a[href]")) {
            val label = normalizeTitleForMatch(
                link.text() + " " + link.attr("aria-label") + " " + link.attr("title")
            )
            if (!label.contains("tumunu gor")) continue
            val href = normalizeUrl(link.attr("href"), baseUrl)
            if (!href.startsWith("http", true) ||
                originOf(href) != originOf(baseUrl) ||
                canonicalContentPath(href) == canonicalContentPath(baseUrl)
            ) continue

            val surroundingHasEpisodes = link.parents().take(5).any { ancestor ->
                ancestor.select("a[href]").count { item ->
                    isEpisodeUrl(normalizeUrl(item.attr("href"), baseUrl))
                } >= 3
            }
            if (surroundingHasEpisodes) return href
        }

        return null
    }

    private fun mergeEpisodeSearchResults(
        preferred: List<SearchResponse>,
        additional: List<SearchResponse>,
    ): List<SearchResponse> {
        val merged = LinkedHashMap<String, SearchResponse>()

        (preferred + additional).forEach { item ->
            val key = canonicalContentPath(normalizeUrl(item.url, mainUrl))
            val previous = merged[key]
            if (previous == null) {
                merged[key] = item
            } else if (previous.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank()) {
                previous.posterUrl = item.posterUrl
                previous.posterHeaders = item.posterHeaders
            }
        }

        return merged.values.toList()
    }

    private fun parseCurrentEpisodeSection(
        document: Document,
        baseUrl: String,
    ): List<SearchResponse> {
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        val currentHeading = headings.firstOrNull {
            val title = normalizeTitleForMatch(it.text())
            title.contains("guncel bolum") ||
                title.contains("son eklenen bolum") ||
                title.contains("yeni bolumler")
        }

        var bestLinks: List<Element> = emptyList()
        var bestCount = 0

        if (currentHeading != null) {
            // Look at each ancestor, including containers that also contain
            // h2/h3 card titles. Card headings are not section boundaries.
            // The real episode strip typically contains 11-25 unique links;
            // a 3-card nested carousel must not win over its larger parent.
            for (ancestor in currentHeading.parents()) {
                val candidates = ancestor.select("a[href]").filter { link ->
                    val href = normalizeUrl(link.attr("href"), baseUrl)
                    // This heading already scopes us to "Güncel Bölümler".
                    // Do not discard its links based on generic CSS names like
                    // "popular" or "related"; themes reuse those on current episodes.
                    isEpisodeUrl(href)
                }
                val count = candidates
                    .map { canonicalContentPath(normalizeUrl(it.attr("href"), baseUrl)) }
                    .distinct()
                    .size

                if (count in 11..40 && count > bestCount) {
                    bestLinks = candidates
                    bestCount = count
                }
            }

            // Some site revisions wrap the heading separately from the list.
            // Scan forward until a known major homepage section starts. Do not
            // stop at generic h2 tags because those may be episode-card titles.
            if (bestCount < 11) {
                val allElements = document.getAllElements()
                val headingIndex = allElements.indexOfFirst { it === currentHeading }
                if (headingIndex >= 0) {
                    val sectionLinks = ArrayList<Element>()
                    for (index in (headingIndex + 1) until allElements.size) {
                        val element = allElements[index]
                        val tag = element.tagName().lowercase()

                        if (tag in setOf("h1", "h2", "h3", "h4")) {
                            val headingText = normalizeTitleForMatch(element.text())
                            val isMajorBoundary = listOf(
                                "trend filmler",
                                "son eklenen filmler",
                                "en cok izlenen diziler",
                                "en cok izlenen filmler",
                                "yabanci dizi izle",
                                "hd film izle",
                                "tum filmler",
                                "dizi arsivi",
                            ).any { headingText.contains(it) }

                            if (isMajorBoundary) break
                        }

                        if (tag != "a" || !element.hasAttr("href")) continue
                        val href = normalizeUrl(element.attr("href"), baseUrl)
                        if (isEpisodeUrl(href)) {
                            sectionLinks.add(element)
                        }
                    }

                    val count = sectionLinks
                        .map { canonicalContentPath(normalizeUrl(it.attr("href"), baseUrl)) }
                        .distinct()
                        .size

                    if (count > bestCount) {
                        bestLinks = sectionLinks
                        bestCount = count
                    }
                }
            }
        }

        // Last-resort fallback: retain all real episode links from the page
        // instead of returning just a small nested carousel. Duplicate URLs are
        // removed below, and links in Trend/sidebar/footer sections are rejected.
        if (bestCount < 11 || bestLinks.isEmpty()) {
            val allEpisodeLinks = document.select("a[href]").filter { link ->
                val href = normalizeUrl(link.attr("href"), baseUrl)
                isEpisodeUrl(href)
            }
            val allCount = allEpisodeLinks
                .map { canonicalContentPath(normalizeUrl(it.attr("href"), baseUrl)) }
                .distinct()
                .size

            if (allCount > bestCount) {
                bestLinks = allEpisodeLinks
                bestCount = allCount
            }
        }

        val grouped = LinkedHashMap<String, MutableList<Element>>()
        bestLinks.forEach { link ->
            val href = normalizeUrl(link.attr("href"), baseUrl)
            if (!isEpisodeUrl(href)) return@forEach
            grouped.getOrPut(canonicalContentPath(href)) { ArrayList() }.add(link)
        }

        val results = ArrayList<SearchResponse>()
        grouped.values.forEachIndexed { index, links ->
            val href = normalizeUrl(links.first().attr("href"), baseUrl)
            val poster = links.asSequence()
                .mapNotNull { link ->
                    posterFromElement(link, baseUrl)
                        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                }
                .firstOrNull()
                ?: links.asSequence()
                    .mapNotNull { link -> posterFromNearbyCard(link, href, baseUrl) }
                    .firstOrNull()

            val title = links.asSequence()
                .map { link -> episodeListingTitle(link, href, baseUrl, null) }
                .firstOrNull { it.isNotBlank() }
                ?: "Bölüm ${index + 1}"

            results += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                posterHeaders = posterRequestHeaders(baseUrl)
            }
        }

        val pageEpisodeCount = document.select("a[href]")
            .count { isEpisodeUrl(normalizeUrl(it.attr("href"), baseUrl)) }
        Log.d(
            "DiziPal",
            "Güncel Bölümler: ${results.size} kayıt, " +
                "${results.count { !it.posterUrl.isNullOrBlank() }} afiş; " +
                "seçilen bağlantı: $bestCount; başlık=${currentHeading?.text().orEmpty()}; " +
                "sayfadaki bölüm bağlantısı=$pageEpisodeCount",
        )
        return results
    }

    private fun parseListing(
        document: Document,
        baseUrl: String,
    ): List<SearchResponse> {
        val resultsByUrl = LinkedHashMap<String, SearchResponse>()
        val posterSelector = posterImageSelector()
        val trendUrls = findTrendUrls(document, baseUrl)
        val anchors = listingAnchors(document, baseUrl, trendUrls)

        var posterCount = 0
        anchors.forEach { link ->
            val href = normalizeUrl(link.attr("href"), baseUrl)
            val isEpisode = isEpisodeUrl(href)
            if (href.isBlank() || (!isCatalogItemUrl(href) && !isEpisode)) return@forEach
            if (trendUrls.any { sameContentUrl(it, href) }) return@forEach
            // Episode cards are often placed in a Swiper/carousel. Keep those
            // when they are genuine episode entries, but still reject Trend,
            // related, sidebar, and footer sections.
            if (if (isEpisode) isInsideHardExcludedSection(link) else isInsideExcludedSection(link, baseUrl)) {
                return@forEach
            }

            val lower = href.lowercase()
            if (lower.contains("/arama-yap") ||
                lower.contains("/profil") ||
                lower.contains("/iletisim") ||
                lower.contains("discord.gg") ||
                lower.contains("twitter.com")
            ) return@forEach

            val isSeries = lower.contains("/series/")
            val isMovie = lower.contains("/movies/") || lower.contains("/movie/")
            if (!isSeries && !isMovie && !isEpisode) return@forEach

            // First inspect the exact detail link. Only if it has no image,
            // allow borrowing a single image from its own one-title card.
            val directPoster = posterFromElement(link, baseUrl)
            val poster = directPoster ?: posterFromNearbyCard(link, href, baseUrl)
            val card = if (directPoster != null || poster == null) {
                null
            } else {
                link.parents().firstOrNull { ancestor ->
                    val targets = ancestor.select("a[href]")
                        .mapNotNull { candidate ->
                            normalizeUrl(candidate.attr("href"), baseUrl)
                                .takeIf { isContentTargetUrl(it) }
                        }
                        .distinctBy { canonicalContentPath(it) }

                    val posterUrls = ancestor.select(posterSelector)
                        .mapNotNull { candidate ->
                            posterRawFromElement(candidate)
                                ?.let { normalizeUrl(it, baseUrl) }
                                ?.takeIf { it.startsWith("http", true) }
                        }
                        .distinct()

                    targets.size == 1 &&
                        sameContentUrl(targets.first(), href) &&
                        posterUrls.size == 1 &&
                        posterUrls.first() == poster
                }
            }

            val title = if (isEpisode) {
                episodeListingTitle(link, href, baseUrl, card)
            } else {
                cleanCardTitle(
                    listOf(
                        link.selectFirst("img")?.attr("alt"),
                        link.selectFirst("h1,h2,h3,h4,.title,.name")?.text(),
                        link.attr("title"),
                        link.attr("aria-label"),
                        link.text(),
                        card?.selectFirst("[title]")?.attr("title"),
                    ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                )
            }
            if (title.isBlank()) return@forEach

            val existing = resultsByUrl[href]
            if (existing != null) {
                // Repeated links to the same title may fill a missing poster,
                // but may not replace a poster that already belongs to it.
                if (existing.posterUrl.isNullOrBlank() && !poster.isNullOrBlank()) {
                    existing.posterUrl = poster
                    existing.posterHeaders = posterRequestHeaders(baseUrl)
                    posterCount++
                }
                return@forEach
            }

            val rating = scoreFromText(
                listOf(link.text(), link.attr("title"), card?.text())
                    .joinToString(" ")
            )
            val item: SearchResponse = when {
                isMovie -> newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = posterRequestHeaders(baseUrl)
                    rating?.let { score = Score.from10(it) }
                }

                else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = posterRequestHeaders(baseUrl)
                    rating?.let { score = Score.from10(it) }
                }
            }

            resultsByUrl[href] = item
            if (!poster.isNullOrBlank()) posterCount++
        }

        Log.d(
            "DiziPal",
            "Katalog: " + resultsByUrl.size + " kayıt, " + posterCount +
                " afiş; " + trendUrls.size + " trend bağlantısı listeden çıkarıldı",
        )
        return resultsByUrl.values.toList()
    }

    private fun listingAnchors(
        document: Document,
        baseUrl: String,
        trendUrls: Set<String>,
    ): List<Element> {
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        val resultsHeading = headings.firstOrNull {
            val title = normalizeTitleForMatch(it.text())
            title.contains("filtrelenmis sonuclar") ||
                title == "diziler" ||
                title.contains("yabanci dizi") ||
                title.contains("dizi arsivi") ||
                title.contains("tum diziler") ||
                title.contains("tum filmler")
        }

        // Platform pages have an explicit "Filtrelenmiş Sonuçlar" heading.
        // Use its nearest bounded results container, not every link in the
        // complete document (which also contains the Trending footer).
        if (resultsHeading != null) {
            var bestCandidates: List<Element> = emptyList()
            var bestCount = 0

            for (ancestor in resultsHeading.parents()) {
                val hasOtherSection = ancestor.select("h1,h2,h3,h4,h5,h6").any { heading ->
                    heading !== resultsHeading && isExcludedSectionHeading(heading.text())
                }
                if (hasOtherSection) continue

                val candidates = ancestor.select("a[href]").filter { link ->
                    val url = normalizeUrl(link.attr("href"), baseUrl)
                    isListingItemUrl(url) &&
                        trendUrls.none { sameContentUrl(it, url) } &&
                        !shouldSkipListingLink(link, url, baseUrl)
                }
                val distinctCount = candidates
                    .map { normalizeUrl(it.attr("href"), baseUrl) }
                    .distinctBy { canonicalContentPath(it) }
                    .size

                // Do not stop at the first 3-card carousel. Keep examining
                // the same bounded section and use the ancestor with the
                // largest number of unique content/episode links.
                if (distinctCount in 3..120 && distinctCount > bestCount) {
                    bestCandidates = candidates
                    bestCount = distinctCount
                }
                if (distinctCount > 120) break
            }

            if (bestCount >= 3) return bestCandidates
        }

        // Fallback: use the page's main content region when it exists.
        val main = document.selectFirst(
            "main, #main, #content, #primary, .site-main, .main-content, " +
                ".content-area, .archive-content, .catalog-content"
        )
        val source = main?.select("a[href]") ?: document.select("a[href]")
        return source.filter { link ->
            val url = normalizeUrl(link.attr("href"), baseUrl)
            isListingItemUrl(url) &&
                trendUrls.none { sameContentUrl(it, url) } &&
                !shouldSkipListingLink(link, url, baseUrl)
        }
    }

    private fun findTrendUrls(
        document: Document,
        baseUrl: String,
    ): Set<String> {
        val result = LinkedHashSet<String>()
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        for (heading in headings) {
            if (!isTrendHeading(heading.text())) continue

            // The first ancestor containing actual title links is the trend
            // component; stop before climbing into a page-wide content wrapper.
            for (ancestor in heading.parents()) {
                val urls = ancestor.select("a[href]")
                    .mapNotNull { link ->
                        normalizeUrl(link.attr("href"), baseUrl)
                            .takeIf { isListingItemUrl(it) }
                    }
                    .distinctBy { canonicalContentPath(it) }

                if (urls.isNotEmpty() && urls.size <= 18) {
                    result.addAll(urls)
                    break
                }
                if (urls.size > 18) break
            }
        }

        return result
    }

    private fun isTrendHeading(value: String): Boolean {
        val title = normalizeTitleForMatch(value)
        return title.contains("trend diz") ||
            title.contains("populer diz") ||
            title.contains("trending") ||
            title.contains("onerilen diz") ||
            title.contains("benzer diz")
    }

    private fun isExcludedSectionHeading(value: String): Boolean {
        val title = normalizeTitleForMatch(value)
        return isTrendHeading(value) ||
            title.contains("son eklenen") ||
            title.contains("benzer") ||
            title.contains("onerilen")
    }

    private fun isInsideExcludedSection(
        element: Element,
        baseUrl: String,
    ): Boolean {
        val excludedClassOrId = Regex(
            "(?i)(trend|trending|popular|populer|sidebar|footer|recommend|" +
                "related|carousel|swiper|slick|owl|breadcrumb|navbar|navigation|menu)"
        )

        var current: Element? = element
        repeat(8) {
            val node = current ?: return false
            if (excludedClassOrId.containsMatchIn(node.attr("class")) ||
                excludedClassOrId.containsMatchIn(node.id())
            ) return true

            current = node.parent()
        }
        return false
    }

    private fun canonicalContentPath(url: String): String {
        return runCatching {
            URI(url).path.orEmpty().lowercase().trimEnd('/')
        }.getOrDefault(url.lowercase().trimEnd('/'))
    }

    private fun sameContentUrl(first: String, second: String): Boolean {
        return canonicalContentPath(first) == canonicalContentPath(second)
    }

    private fun hasNextPage(
        document: Document,
        page: Int,
    ): Boolean {
        if (document.select("a[href]").any { element ->
                val text = element.text().trim().lowercase()
                text.contains("sonraki") ||
                    text == "next" ||
                    element.attr("rel").equals("next", true)
            }
        ) return true

        val next = page + 1

        return document.select("a[href]").any {
            val href = it.attr("href")
            href.contains("page=" + next) ||
                href.contains("sayfa=" + next) ||
                href.contains("/page/" + next + "/")
        }
    }

    private fun buildPageUrl(
        base: String,
        page: Int,
    ): String {
        if (page <= 1) return base

        return if (base.contains("?")) {
            base + "&page=" + page
        } else {
            base + "?page=" + page
        }
    }

    private fun documentBase(
        document: Document,
        fallback: String,
    ): String {
        val location = document.location().trim()
        return location.takeIf {
            it.startsWith("http://", true) ||
                it.startsWith("https://", true)
        } ?: fallback
    }

    private fun srcSetCandidate(
        value: String,
    ): String? {
        if (value.isBlank()) return null

        return value.split(",")
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull().orEmpty()
                if (url.isBlank()) return@mapNotNull null

                val descriptor = parts.getOrNull(1).orEmpty()
                val size = descriptor.removeSuffix("w")
                    .removeSuffix("x")
                    .toDoubleOrNull() ?: 0.0

                url to size
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun posterRawFromElement(
        image: Element,
    ): String? {
        val styleUrl = Regex(
            """(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)"""
        ).find(image.attr("style"))
            ?.groupValues
            ?.getOrNull(1)

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-lazy"),
            image.attr("data-lazyload"),
            image.attr("data-lazyload-src"),
            image.attr("data-src-original"),
            image.attr("data-original"),
            image.attr("data-original-src"),
            image.attr("data-original-url"),
            image.attr("data-image"),
            image.attr("data-image-src"),
            image.attr("data-image-original"),
            image.attr("data-img"),
            image.attr("data-img-url"),
            image.attr("data-thumb"),
            image.attr("data-thumb-url"),
            image.attr("data-poster"),
            image.attr("data-poster-url"),
            image.attr("data-poster-src"),
            image.attr("data-flickity-lazyload"),
            image.attr("data-flickity-lazyload-src"),
            image.attr("data-echo"),
            image.attr("data-echo-lazy"),
            image.attr("data-background"),
            image.attr("data-background-image"),
            image.attr("data-background-src"),
            image.attr("data-bg"),
            image.attr("data-bg-src"),
            image.attr("data-url"),
            image.attr("data-cfsrc"),
            image.attr("data-cf-src"),
            srcSetCandidate(image.attr("data-srcset")),
            srcSetCandidate(image.attr("data-lazy-srcset")),
            image.attr("src"),
            srcSetCandidate(image.attr("srcset")),
            image.attr("content"),
            styleUrl,
        )

        return candidates.firstOrNull { raw ->
            val value = raw.orEmpty().trim()
            value.isNotBlank() &&
                !value.startsWith("data:", true) &&
                !value.equals("about:blank", true) &&
                !value.contains("placeholder", true) &&
                !value.contains("loading.gif", true) &&
                !value.contains("transparent.gif", true) &&
                !value.contains("spacer.gif", true) &&
                !value.contains("pixel.gif", true)
        }
    }

    private fun posterFromElement(
        element: Element?,
        baseUrl: String = mainUrl,
    ): String? {
        if (element == null) return null

        val selfRaw = posterRawFromElement(element)
        val image = if (
            element.tagName().equals("img", true) ||
            element.tagName().equals("source", true) ||
            !selfRaw.isNullOrBlank()
        ) {
            element
        } else {
            element.select(posterImageSelector()).firstOrNull { candidate ->
                posterRawFromElement(candidate) != null
            } ?: return null
        }

        val raw = posterRawFromElement(image) ?: return null
        val url = normalizeUrl(raw, baseUrl)

        return url.takeIf {
            it.startsWith("http://", true) ||
                it.startsWith("https://", true)
        }
    }

    private fun posterFromNearbyCard(
        link: Element,
        targetUrl: String,
        baseUrl: String,
    ): String? {
        val targetPath = canonicalContentPath(normalizeUrl(targetUrl, baseUrl))

        for (ancestor in link.parents()) {
        val episodeLabel = episodeListingTitle(link, targetUrl, baseUrl, null) +
            " " + targetUrl.substringAfterLast('/').replace('-', ' ').replace('_', ' ')
        val targetTokens = posterMatchTokens(episodeLabel)

            // Some homepage cards expose the poster on a sibling series anchor or
            // a CSS/lazy-load image instead of the episode anchor. Match image
            // labels and linked slugs to this episode so we can find its own
            // poster even when the card wrapper also contains other links.
            if (targetTokens.isNotEmpty()) {
                val labelledPosters = ancestor.select(posterImageSelector())
                    .mapNotNull { image ->
                        val poster = posterFromElement(image, baseUrl)
                            ?.takeIf { candidate ->
                                candidate.startsWith("http://", true) ||
                                    candidate.startsWith("https://", true)
                            }
                            ?: return@mapNotNull null
                        if (!isLikelyPosterUrl(poster)) return@mapNotNull null

                        val imageAnchor = image.parents()
                            .firstOrNull { it.tagName().equals("a", true) }
                        val candidateLabel = listOf(
                            image.attr("alt"),
                            image.attr("title"),
                            image.attr("data-title"),
                            image.attr("data-name"),
                            image.attr("aria-label"),
                            imageAnchor?.text().orEmpty(),
                            imageAnchor?.attr("title").orEmpty(),
                            imageAnchor?.attr("aria-label").orEmpty(),
                            imageAnchor?.attr("href").orEmpty()
                                .substringAfterLast('/').replace('-', ' '),
                            poster.substringBefore('?').substringBefore('#')
                                .substringAfterLast('/').replace('-', ' ').replace('_', ' '),
                        ).filter { it.isNotBlank() }.joinToString(" ")

                        val candidateTokens = posterMatchTokens(candidateLabel)
                        val overlap = targetTokens.intersect(candidateTokens).size
                        val score = overlap.toDouble() / targetTokens.size.toDouble()
                        val minimumOverlap = if (targetTokens.size <= 2) 1 else 2
                        if (overlap < minimumOverlap || score < 0.40) {
                            return@mapNotNull null
                        }
                        Triple(score, poster, imageAnchor?.attr("href").orEmpty())
                    }

                val bestScore = labelledPosters.maxOfOrNull { it.first }
                if (bestScore != null && bestScore >= 0.40) {
                    val bestPosters = labelledPosters
                        .filter { it.first == bestScore }
                        .map { it.second }
                        .distinct()
                    // Only use a label match when it uniquely identifies an
                    // image URL; otherwise leave it blank rather than borrow
                    // another show's poster.
                    if (bestPosters.size == 1) return bestPosters.first()
                }
            }
            // The cover and title are often separate links inside the same
            // card. Prefer an image explicitly linked to this exact series or
            // movie; this works even when the card also contains badges/logos.
            val matchingLinks = ancestor.select("a[href]").filter { candidate ->
                val candidateUrl = normalizeUrl(candidate.attr("href"), baseUrl)
                isListingItemUrl(candidateUrl) &&
                    canonicalContentPath(candidateUrl) == targetPath
            }
            for (candidate in matchingLinks) {
                // Because this anchor points to the exact catalog item, trust
                // its non-placeholder image URL even when the server uses a
                // URL without a .jpg/.webp suffix.
                val linkedPoster = posterFromElement(candidate, baseUrl)
                    ?.takeIf {
                        it.startsWith("http://", true) ||
                            it.startsWith("https://", true)
                    }
                if (!linkedPoster.isNullOrBlank()) return linkedPoster
            }

            // Fallback for cards that use an unlinked background image. Only
            // accept it when this small card points to one catalog item and
            // has one unambiguous image, so a neighbouring Trend poster cannot
            // be borrowed.
            val contentTargets = ancestor.select("a[href]")
                .mapNotNull { candidate ->
                    normalizeUrl(candidate.attr("href"), baseUrl)
                        .takeIf { isListingItemUrl(it) }
                }
                .distinctBy { canonicalContentPath(it) }

            val targetIsEpisode = isEpisodeUrl(targetUrl)
            val episodeTargets = contentTargets.filter { isEpisodeUrl(it) }
                .distinctBy { canonicalContentPath(it) }
            val catalogueTargets = contentTargets.filter { isCatalogItemUrl(it) }
                .distinctBy { canonicalContentPath(it) }

            val oneEpisodeCard = targetIsEpisode &&
                episodeTargets.size == 1 &&
                canonicalContentPath(episodeTargets.first()) == targetPath &&
                catalogueTargets.size <= 1
            val oneCatalogueCard = !targetIsEpisode &&
                contentTargets.size == 1 &&
                canonicalContentPath(contentTargets.first()) == targetPath

            if (!oneEpisodeCard && !oneCatalogueCard) {
                continue
            }

            // On DiziPal, an episode-title anchor can point to /bolum/... while
            // the poster anchor beside it points to /series/.... When the same
            // small card has exactly one episode and one series URL, read the
            // image directly from that series anchor rather than requiring the
            // whole card to contain only one distinct image (badges may exist).
            if (oneEpisodeCard && catalogueTargets.size == 1) {
                val cataloguePath = canonicalContentPath(catalogueTargets.first())
                val catalogueAnchor = ancestor.select("a[href]").firstOrNull { candidate ->
                    val candidateUrl = normalizeUrl(candidate.attr("href"), baseUrl)
                    isCatalogItemUrl(candidateUrl) &&
                        canonicalContentPath(candidateUrl) == cataloguePath
                }
                val linkedSeriesPoster = posterFromElement(catalogueAnchor, baseUrl)
                    ?.takeIf {
                        it.startsWith("http://", true) || it.startsWith("https://", true)
                    }
                if (!linkedSeriesPoster.isNullOrBlank()) return linkedSeriesPoster
            }

            val posters = ancestor.select(posterImageSelector())
                .mapNotNull { candidate ->
                    posterRawFromElement(candidate)
                        ?.let { normalizeUrl(it, baseUrl) }
                        ?.takeIf {
                            it.startsWith("http://", true) ||
                                it.startsWith("https://", true)
                        }
                }
                .toSet()

            if (posters.size == 1) return posters.first()
        }
        return null
    }

    private fun posterMatchTokens(value: String): Set<String> {
        val stopWords = setOf(
            "sezon", "season", "bolum", "episode", "izle", "watch",
            "poster", "afis", "cover", "image", "thumbnail",
            "jpg", "jpeg", "png", "webp", "avif", "gif", "cdn",
            "series", "movie", "movies", "dizi", "film",
        )
        return normalizeTitleForMatch(value)
            .split(" ")
            .filter { token ->
                token.length >= 3 &&
                    token !in stopWords &&
                    !token.all { it.isDigit() } &&
                    !Regex("""(?i)^(?:s\d+e\d+|\d+x\d+)$""").matches(token)
            }
            .toSet()
    }

    private fun isContentTargetUrl(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return false
        }
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
            .trimEnd('/')
        if (path.isBlank() || path == "/") return false

        val excludedPrefixes = listOf(
            "/kanal/", "/category/", "/tag/", "/arama", "/search", "/profil",
            "/iletisim", "/login", "/register", "/yabanci-dizi-izle",
            "/hd-film-izle", "/page/", "/wp-", "/feed",
        )
        if (excludedPrefixes.any { path.startsWith(it) }) return false

        return isCatalogItemUrl(url) || isEpisodeUrl(url) ||
            path.trim('/').split('/').filter { it.isNotBlank() }.size >= 2
    }

    private fun posterRequestHeaders(baseUrl: String = mainUrl): Map<String, String> {
        val siteOrigin = originOf(baseUrl) ?: mainUrl
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$siteOrigin/",
            "Origin" to siteOrigin,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        )
    }

    private fun posterOf(
        document: Document,
    ): String? {
        val baseUrl = documentBase(document, mainUrl)
        val title = pageTitle(document, baseUrl).orEmpty()

        // If Open Graph points to a trending poster with a different title,
        // reject it instead of copying that image into every episode.
        val ogImage = document.selectFirst("meta[property='og:image'], meta[name='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it, baseUrl) }

        if (!ogImage.isNullOrBlank() && isLikelyPosterUrl(ogImage)) {
            val correspondingImage = document.select(posterImageSelector())
                .firstOrNull { element ->
                    posterRawFromElement(element)
                        ?.let { normalizeUrl(it, baseUrl) == ogImage } == true
                }
            if (correspondingImage != null &&
                isDetailPosterContext(correspondingImage, baseUrl) &&
                isPosterLabelCompatible(correspondingImage, title)
            ) {
                return ogImage
            }

            if (posterUrlContainsTitle(ogImage, title)) return ogImage
        }

        // Use title-specific poster containers first. A labelled image whose
        // name belongs to Reacher/another Trending show must not be reused for
        // an unrelated page.
        val selectors = listOf(
            "[itemprop='image']",
            ".detail-poster img",
            ".detail-poster",
            ".series-poster img",
            ".movie-poster img",
            ".poster-container img",
            ".poster-container",
            ".single-poster img",
            ".entry-poster img",
            ".post-thumbnail img",
            ".film-poster img",
            ".cover-image img",
            ".cover img",
            ".poster img",
            ".poster",
        )

        for (selector in selectors) {
            for (element in document.select(selector)) {
                val image = if (
                    element.tagName().equals("img", true) ||
                    element.tagName().equals("source", true) ||
                    !posterRawFromElement(element).isNullOrBlank()
                ) {
                    element
                } else {
                    element.select(posterImageSelector()).firstOrNull { candidate ->
                        posterRawFromElement(candidate) != null
                    }
                } ?: continue

                // Validate the actual image node, not just its wrapper. The
                // wrapper normally has no alt/title and could hide a Trending
                // poster labelled with a different show's name.
                if (!isDetailPosterContext(image, baseUrl)) continue
                if (!isPosterLabelCompatible(image, title)) continue
                posterFromElement(image, baseUrl)
                    ?.takeIf(::isLikelyPosterUrl)
                    ?.let { return it }
            }
        }

        // A hero image may sit close to the title, but only accept an ancestor
        // that contains no links to other content items and an unambiguous
        // image, so a Trend carousel can never lend its first poster.
        val heading = document.selectFirst("h1")
        var ancestor = heading?.parent()
        repeat(4) {
            val container = ancestor ?: return@repeat
            val itemUrls = container.select("a[href]")
                .mapNotNull { link ->
                    normalizeUrl(link.attr("href"), baseUrl)
                        .takeIf { isContentTargetUrl(it) }
                }
                .toSet()
            val images = container.select(posterImageSelector())
                .filter { posterRawFromElement(it) != null }
            val imageUrls = images.mapNotNull { element ->
                posterRawFromElement(element)
                    ?.let { normalizeUrl(it, baseUrl) }
                    ?.takeIf { it.startsWith("http", true) }
            }.toSet()

            if (itemUrls.isEmpty() && imageUrls.size == 1) {
                val candidate = images.firstOrNull()
                if (candidate != null && isPosterLabelCompatible(candidate, title)) {
                    posterFromElement(candidate, baseUrl)
                        ?.takeIf(::isLikelyPosterUrl)
                        ?.let { return it }
                }
            }
            ancestor = container.parent()
        }

        // Missing is preferable to displaying a poster for a different show.
        return null
    }

    private fun isDetailPosterContext(element: Element, pageUrl: String): Boolean {
        var current: Element? = element
        val excludedSection = Regex(
            "(?i)(trend|trending|popular|populer|recommend|related|carousel|swiper|slick|owl|sidebar|footer)"
        )
        val pageBoundary = Regex(
            "(?i)(site-main|site-content|content-area|main-content|page-content|archive-content|primary-content|entry-content)"
        )

        repeat(8) {
            val node = current ?: return true
            val classesAndId = node.attr("class") + " " + node.id()

            // Reject a poster inside an identified Trend/recommendation card.
            if (excludedSection.containsMatchIn(classesAndId)) return false

            // Detail pages often include their season/episode links alongside
            // the real cover. Stop before scanning the whole page, where those
            // links (and recommendations) would incorrectly invalidate it.
            if (node.tagName().lowercase() in setOf("main", "body", "html", "footer") ||
                pageBoundary.containsMatchIn(classesAndId)
            ) return true

            // Jsoup Element.select() excludes the current element itself.
            // Check an enclosing anchor directly so a linked Trend image fails.
            if (node.tagName().equals("a", true) && node.hasAttr("href")) {
                val target = normalizeUrl(node.attr("href"), pageUrl)
                if (isCatalogItemUrl(target) && !sameContentUrl(target, pageUrl)) {
                    return false
                }
            }

            // Only other series/movie links can invalidate a detail poster.
            // Episode links on the current series page are expected and safe.
            val targets = node.select("a[href]")
                .mapNotNull { link ->
                    normalizeUrl(link.attr("href"), pageUrl)
                        .takeIf { isCatalogItemUrl(it) }
                }
                .distinctBy { canonicalContentPath(it) }

            if (targets.any { !sameContentUrl(it, pageUrl) }) return false
            current = node.parent()
        }
        return true
    }

    private fun isPosterLabelCompatible(element: Element, pageTitle: String): Boolean {
        val labels = listOf(
            element.attr("alt"),
            element.attr("title"),
            element.attr("data-title"),
            element.attr("data-name"),
            element.attr("aria-label"),
        )
        val label = labels.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (label.isBlank()) return true

        val normalizedLabel = normalizeTitleForMatch(label)
        val normalizedTitle = normalizeTitleForMatch(pageTitle)
        if (normalizedLabel in setOf("poster", "image", "thumbnail", "afis", "cover")) {
            return true
        }
        if (normalizedLabel.isBlank() || normalizedTitle.isBlank()) return true

        if (normalizedLabel.contains(normalizedTitle) ||
            normalizedTitle.contains(normalizedLabel)
        ) return true

        val titleTokens = normalizedTitle.split(" ").filter { it.length >= 3 }.toSet()
        val labelTokens = normalizedLabel.split(" ").filter { it.length >= 3 }.toSet()
        if (titleTokens.isEmpty() || labelTokens.isEmpty()) return true
        return titleTokens.intersect(labelTokens).size.toDouble() /
            titleTokens.size.toDouble() >= 0.5
    }

    private fun normalizeTitleForMatch(value: String): String =
        value.lowercase()
            .replace("&", " and ")
            .replace(Regex("(?i)\\b(izle|hd|dizi|film|poster|afis|cover|image)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun posterUrlContainsTitle(url: String, title: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .substringAfterLast('/')
        if (path.isBlank()) return false
        val titleTokens = normalizeTitleForMatch(title)
            .split(" ").filter { it.length >= 3 }.toSet()
        val pathTokens = normalizeTitleForMatch(path)
            .split(" ").filter { it.length >= 3 }.toSet()
        if (titleTokens.isEmpty() || pathTokens.isEmpty()) return false
        return titleTokens.intersect(pathTokens).size.toDouble() /
            titleTokens.size.toDouble() >= 0.5
    }

    private fun posterImageSelector(): String =
        "img, picture source, " +
            "[data-src], [data-lazy-src], [data-lazyload], [data-lazyload-src], " +
            "[data-original], [data-original-src], [data-original-url], " +
            "[data-image], [data-image-src], [data-image-original], " +
            "[data-img], [data-img-url], [data-thumb], [data-thumb-url], " +
            "[data-poster], [data-poster-url], [data-poster-src], " +
            "[data-flickity-lazyload], [data-flickity-lazyload-src], " +
            "[data-echo], [data-echo-lazy], [data-background], " +
            "[data-background-image], [data-background-src], [data-bg], " +
            "[data-bg-src], [data-srcset], [data-lazy-srcset], [style*=background]"

    private fun isListingItemUrl(url: String): Boolean {
        return isCatalogItemUrl(url) || isEpisodeUrl(url)
    }

    private fun shouldSkipListingLink(
        link: Element,
        url: String,
        baseUrl: String,
    ): Boolean {
        return if (isEpisodeUrl(url)) {
            isInsideHardExcludedSection(link)
        } else {
            isInsideExcludedSection(link, baseUrl)
        }
    }

    private fun isInsideHardExcludedSection(element: Element): Boolean {
        val excluded = Regex(
            "(?i)(trend|trending|popular|populer|sidebar|footer|recommend|related|" +
                "breadcrumb|navbar|navigation|menu)"
        )
        var current: Element? = element
        repeat(10) {
            val node = current ?: return false
            if (excluded.containsMatchIn(node.attr("class")) ||
                excluded.containsMatchIn(node.id())
            ) return true
            current = node.parent()
        }
        return false
    }

    private fun isCatalogItemUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
        return path.contains("/series/") ||
            path.contains("/movies/") ||
            path.contains("/movie/")
    }

    private fun isLikelyPosterUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault("")
        if (path.isBlank()) return false
        if (path.startsWith("/series/") ||
            path.startsWith("/movies/") ||
            path.startsWith("/movie/") ||
            path.startsWith("/bolum/")
        ) return false

        val extension = path.substringAfterLast('.', "")
        return extension in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") ||
            path.contains("image") ||
            path.contains("poster") ||
            path.contains("thumb") ||
            path.contains("cover") ||
            path.contains("upload") ||
            path.contains("cdn")
    }

    private fun pageTitle(
        document: Document,
        url: String,
    ): String? {
        return listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")
                ?.attr("content"),
            document.selectFirst("title")?.text(),
            url.substringAfterLast('/')
                .replace('-', ' '),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.let(::cleanCardTitle)
    }

    private fun pagePlot(
        document: Document,
    ): String? {
        return listOf(
            document.selectFirst("meta[property='og:description']")
                ?.attr("content"),
            document.selectFirst("meta[name='description']")
                ?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
            document.selectFirst(".summary")?.text(),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
    }

    private fun pageYear(
        document: Document,
    ): Int? {
        val focused = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")
                ?.attr("content"),
            document.selectFirst("meta[name='description']")
                ?.attr("content"),
            document.selectFirst(".year")?.text(),
        )
            .filterNotNull()
            .joinToString(" ")

        return Regex(
            """(?<!\d)(?:19|20)\d{2}(?!\d)"""
        )
            .find(focused)
            ?.value
            ?.toIntOrNull()
    }

    private fun pageRating(
        document: Document,
    ): Double? {
        return scoreFromText(document.text())
    }

    private fun scoreFromText(
        text: String?,
    ): Double? {
        if (text.isNullOrBlank()) return null

        val imdb = Regex(
            """(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        )
            .find(text)

        val imdbScore = imdb
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

        if (imdbScore != null) return imdbScore

        // Kanal/listing kartlarında puan çoğu zaman "2026 7.7 Başlık"
        // şeklinde IMDb etiketi olmadan gösteriliyor.
        val yearScore = Regex(
            """(?<!\d)(?:19|20)\d{2}\s+([0-9](?:[.,][0-9])?)(?!\d)"""
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

        return yearScore
    }

    private fun cleanCardTitle(
        raw: String,
    ): String {
        var title = raw
            .replace(Regex("\\s+"), " ")
            .trim()

        title = title
            .replace(
                Regex("(?i)^Dublaj\\s+"),
                "",
            )
            .replace(
                Regex("(?i)^Altyazı\\s+"),
                "",
            )

        // Current film cards can contain:
        // Dublaj Altyazı 2025 7.0 Barselo
        title = title.replace(
            Regex(
                """(?i)^(?:19|20)\d{2}\s+\d+(?:[.,]\d+)?\s+"""
            ),
            "",
        )

        title = title.replace(
            Regex(
                """(?i)\s+\d+\.\s*Sezon\s+\d+\.\s*Bölüm\s*$"""
            ),
            "",
        )

        return title.trim()
    }

    private fun episodeNumbersFrom(
        text: String,
    ): Pair<Int, Int>? {
        val source = text
            .replace("\\/", "/")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Accept only explicit, adjacent season/episode pairs. Avoid pairing
        // unrelated numbers from page/card text, which caused scrambled metadata.
        val patterns = listOf(
            Regex(
                """(?i)\b(?:sezon|season)[\s._:#-]*(\d{1,2})[\s._:#-]*(?:bölüm|bolum|episode)[\s._:#-]*(\d{1,3})\b"""
            ),
            Regex(
                """(?i)\b(?:bölüm|bolum|episode)[\s._:#-]*(\d{1,3})[\s._:#-]*(?:sezon|season)[\s._:#-]*(\d{1,2})\b"""
            ),
            Regex(
                """(?i)\bS(\d{1,2})[\s._-]*E(\d{1,3})\b"""
            ),
            Regex(
                """(?i)\b(\d{1,2})[\s._-]*x[\s._-]*(\d{1,3})\b"""
            ),
        )

        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(source) ?: continue
            val first = match.groupValues[1].toIntOrNull() ?: continue
            val second = match.groupValues[2].toIntOrNull() ?: continue
            val numbers = if (index == 1) second to first else first to second

            if (numbers.first in 1..100 && numbers.second in 1..999) {
                return numbers
            }
        }

        return null
    }

    private fun isEpisodeUrl(
        url: String,
    ): Boolean {
        return runCatching {
            URI(url).path
                ?.lowercase()
                ?.startsWith("/bolum/") == true
        }.getOrDefault(false)
    }

    private fun isMediaUrl(
        url: String,
    ): Boolean {
        val value = url.lowercase()
        return Regex(
            """(?i)\.(?:m3u8|mpd|mp4|webm)(?:[?#]|$)"""
        ).containsMatchIn(value) ||
            value.contains("/hls/") ||
            value.contains("/hls2/")
    }

    private fun findMediaUrls(
        text: String,
    ): Set<String> {
        val found = LinkedHashSet<String>()

        listOf(
            Regex(
                """https?://[^"'<>\\s]+?\.m3u8(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.mp4(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.mpd(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://[^"'<>\\s]+?\.webm(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
        ).forEach { regex ->
            regex.findAll(text).forEach { match ->
                val url = cleanUrl(match.value)
                if (url.isNotBlank()) found += url
            }
        }

        return found
    }

    private fun extractFileUrls(
        text: String,
    ): Set<String> {
        val found = LinkedHashSet<String>()

        Regex(
            """"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(text)
            .forEach { match ->
                val url = cleanUrl(match.groupValues[1])

                if (url.isNotBlank() &&
                    (isMediaUrl(url) || url.contains("m.php", true))
                ) {
                    found += url
                }
            }

        Regex(
            """(?i)"(?:source|src|url|hls)"\s*:\s*"([^"]+)""""
        )
            .findAll(text)
            .forEach { match ->
                val url = cleanUrl(match.groupValues[1])

                if (url.isNotBlank() &&
                    (isMediaUrl(url) || url.contains("m.php", true))
                ) {
                    found += url
                }
            }

        return found
    }

    private suspend fun emitMediaLink(
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var url = cleanUrl(rawUrl)

        if (url.startsWith("//")) {
            url = "https:" + url
        }

        if (url.contains("m.php", true)) {
            url = url.replace(
                Regex("(?i)m\\.php"),
                "master.m3u8",
            )
        }

        if (!url.startsWith("http", true)) return false

        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "DiziPal",
                url = url,
                type = type,
            ) {
                quality = qualityFromUrl(url)
                this.referer = referer
                headers = mediaHeaders(referer)
            }
        )

        return true
    }

    private fun mediaHeaders(
        referer: String,
    ): Map<String, String> {
        val result = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to referer,
        )

        originOf(referer)?.let {
            result["Origin"] = it
        }

        return result
    }

    private fun qualityFromUrl(
        url: String,
    ): Int {
        val value = url.lowercase()

        return when {
            "2160" in value || "4k" in value ->
                Qualities.P2160.value

            "1440" in value ->
                Qualities.P1440.value

            "1080" in value ->
                Qualities.P1080.value

            "720" in value ->
                Qualities.P720.value

            "480" in value ->
                Qualities.P480.value

            "360" in value ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private suspend fun emitSubtitle(
        url: String,
        lang: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return try {
            subtitleCallback(
                newSubtitleFile(
                    lang = lang,
                    url = url,
                ) {
                    headers = mediaHeaders(referer)
                }
            )
            true
        } catch (e: Exception) {
            Log.d("DiziPal", "Subtitle skipped: " + e.message)
            false
        }
    }

    private suspend fun extractSubtitles(
        text: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        var found = false
        val seen = HashSet<String>()

        suspend fun register(rawUrl: String?, rawLang: String? = null) {
            var url = normalizeUrl(rawUrl, referer)
            if (url.isBlank() || !url.startsWith("http", true)) return

            url = cleanUrl(url)
            if (url.isBlank() || !seen.add(url)) return

            val lower = url.lowercase()
            val subtitleLike =
                lower.endsWith(".srt") ||
                    lower.endsWith(".vtt") ||
                    lower.endsWith(".ass") ||
                    lower.endsWith(".ssa") ||
                    lower.contains("subtitle") ||
                    lower.contains("subtitles") ||
                    lower.contains("/subs/") ||
                    lower.contains("/sub/") ||
                    lower.contains("caption") ||
                    lower.contains("captions") ||
                    lower.contains("sub.php") ||
                    lower.contains("subtitle.php")

            if (!subtitleLike) return

            val lang = decodeJsonText(rawLang.orEmpty()).trim()
                .ifBlank { languageFromSubtitleUrl(url) }

            if (emitSubtitle(
                    url,
                    lang.ifBlank { "Türkçe" },
                    referer,
                    subtitleCallback,
                )
            ) {
                found = true
            }
        }

        // Common DPlayer/JSON layouts:
        // {"file":"...vtt","label":"Türkçe"}
        val fileLabelRegex = Regex(
            """"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        for (match in fileLabelRegex.findAll(text)) {
            register(
                match.groupValues[1],
                match.groupValues[2],
            )
        }

        // Some versions use label before file.
        val labelFileRegex = Regex(
            """"label"\s*:\s*"([^"]+)"\s*,\s*"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        for (match in labelFileRegex.findAll(text)) {
            register(
                match.groupValues[2],
                match.groupValues[1],
            )
        }

        // tracks/captions/subtitle objects can use url/src instead of file.
        val trackObjectRegex = Regex(
            """(?is)"(?:track|tracks|caption|captions|subtitle|subtitles?)"\s*:\s*(?:\[[^\]]*\]|\{[^}]*\})"""
        )
        val trackUrlRegex = Regex(
            """(?i)"(?:file|src|url|source)"\s*:\s*"([^"]+)""""
        )
        val trackLabelRegex = Regex(
            """(?i)"(?:label|lang|language|name)"\s*:\s*"([^"]+)""""
        )
        for (block in trackObjectRegex.findAll(text)) {
            val value = block.value
            val label = trackLabelRegex.find(value)
                ?.groupValues
                ?.getOrNull(1)

            for (urlMatch in trackUrlRegex.findAll(value)) {
                register(urlMatch.groupValues[1], label)
            }
        }

        // Scalar subtitle fields.
        val scalarSubtitleRegex = Regex(
            """(?i)"(?:subtitle|subtitles?|caption|captions?|subtitle_url|subtitleUrl|caption_url|captionUrl|sub_url|subUrl)"\s*:\s*"([^"]+)""""
        )
        for (match in scalarSubtitleRegex.findAll(text)) {
            register(match.groupValues[1])
        }

        // Bare subtitle URLs, including protocol-relative and extensionless
        // subtitle endpoints used by some player revisions.
        val subtitleUrlRegex = Regex(
            """(?i)(?:(?:https?:)?//|/)[^"'<>\\s]+(?:\.srt|\.vtt|\.ass|\.ssa)(?:\?[^"'<>\\s]*)?"""
        )
        for (match in subtitleUrlRegex.findAll(text)) {
            register(match.value)
        }

        val subtitleEndpointRegex = Regex(
            """(?i)(?:(?:https?:)?//)[^"'<>\\s]*(?:subtitle|subtitles|caption|captions|sub\.php|subtitle\.php)[^"'<>\\s]*"""
        )
        for (match in subtitleEndpointRegex.findAll(text)) {
            register(match.value)
        }

        // HTML <track> elements that survived Jsoup parsing.
        val trackDocument = org.jsoup.Jsoup.parse(text)
        for (track in trackDocument.select(
            "track[src], track[data-src], track[kind='subtitles'], track[kind='captions']"
        )) {
            val rawUrl = track.attr("src")
                .ifBlank { track.attr("data-src") }

            register(
                rawUrl,
                track.attr("label")
                    .ifBlank { track.attr("srclang") }
                    .ifBlank { track.attr("lang") },
            )
        }

        return found
    }

    private fun languageFromSubtitleUrl(
        url: String,
    ): String {
        val value = url.lowercase()

        return when {
            Regex("""(?:^|[^a-z])(?:tr|tur|turkish)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Türkçe"

            Regex("""(?:^|[^a-z])(?:en|eng|english)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "English"

            Regex("""(?:^|[^a-z])(?:de|ger|german)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Deutsch"

            Regex("""(?:^|[^a-z])(?:fr|fre|french)(?:[^a-z]|$)""")
                .containsMatchIn(value) -> "Français"

            else -> "Altyazı"
        }
    }

    private data class EncryptedConfig(
        val ciphertext: String,
        val iv: String,
        val salt: String,
    )

    private fun extractEncryptedConfig(
        raw: String,
    ): EncryptedConfig? {
        val text = raw
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .decodeEscapes()

        val ciphertext = Regex(
            """"ciphertext"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val iv = Regex(
            """"iv"\s*:\s*"([0-9a-fA-F]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val salt = Regex(
            """"salt"\s*:\s*"([0-9a-fA-F]+)"""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return EncryptedConfig(
            ciphertext = ciphertext,
            iv = iv,
            salt = salt,
        )
    }

    private fun decryptDizipalConfig(
        config: EncryptedConfig,
    ): String {
        val passphrase =
            "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

        return runCatching {
            val salt = hexToBytes(config.salt)
            val iv = hexToBytes(config.iv)
            val ciphertext = Base64.decode(
                config.ciphertext,
                Base64.DEFAULT,
            )

            val factory = SecretKeyFactory.getInstance(
                "PBKDF2WithHmacSHA512"
            )

            val keySpec = PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                999,
                256,
            )

            val key = factory
                .generateSecret(keySpec)
                .encoded

            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )

            String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8,
            )
                .replace("\\/", "/")
                .trim()
        }.onFailure {
            Log.e(
                "DiziPal",
                "Decrypt failed: " + it.message,
            )
        }.getOrDefault("")
    }

    private fun hexToBytes(
        value: String,
    ): ByteArray {
        val clean = value.trim()

        if (clean.length % 2 != 0) {
            throw IllegalArgumentException("Invalid hex string")
        }

        return ByteArray(clean.length / 2) { index ->
            clean.substring(
                index * 2,
                index * 2 + 2,
            ).toInt(16).toByte()
        }
    }

    private fun normalizeUrl(
        raw: String?,
        base: String,
    ): String {
        var url = raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim('"', '\'')

        return when {
            url.startsWith("//") ->
                "https:" + url

            url.startsWith("http://", true) ||
                url.startsWith("https://", true) ->
                url

            url.startsWith("/") ->
                (originOf(base) ?: mainUrl) + url

            url.isBlank() ->
                ""

            else ->
                runCatching {
                    URI(base).resolve(url).toString()
                }.getOrElse {
                    mainUrl + "/" + url.removePrefix("./")
                }
        }
    }

    private fun cleanUrl(
        raw: String,
    ): String {
        return raw
            .trim()
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', ')', ']', '}', ',', ';')
    }

    private fun String.decodeEscapes(): String {
        return this
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
    }

    private fun decodeJsonText(
        value: String,
    ): String {
        return value
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u00fc", "ü")
            .replace("\\u00e7", "ç")
            .replace("\\u011f", "ğ")
            .replace("\\u015f", "ş")
            .decodeEscapes()
    }

    private fun originOf(
        url: String,
    ): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            scheme + "://" + host
        }.getOrNull()
    }
}
