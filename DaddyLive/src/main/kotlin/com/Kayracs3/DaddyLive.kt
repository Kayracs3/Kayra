package com.Kayracs3

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class DaddyLiveProvider : MainAPI() {

    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = getChannels()
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        return getChannels().filter {
            it.name.contains(term, ignoreCase = true)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    private suspend fun getChannels(): List<LiveSearchResponse> {
        val response = app.get(
            "$mainUrl/24-7-channels.php",
            headers = requestHeaders(mainUrl),
            allowRedirects = true
        )

        if (!response.isSuccessful) {
            throw ErrorLoadingException("DaddyLive kanal sayfası açılamadı.")
        }

        val document = response.document
        val channels = document
            .select("a[href*='watch.php?id='], a[href*='watch.php?channel=']")
            .mapNotNull { it.toLiveSearchResponse() }
            .filter { isAllowedChannel(it.name) }
            .distinctBy { it.url }

        if (channels.isNotEmpty()) return channels

        // Bazı sayfa sürümlerinde 24/7 liste yerine ana sayfa üzerinden
        // kanal bağlantıları yayınlanıyor. Aynı herkese açık HTML yapısını dene.
        val home = app.get(
            "$mainUrl/index.php",
            headers = requestHeaders(mainUrl),
            allowRedirects = true
        ).document

        val homeChannels = home
            .select("a[href*='watch.php?id='], a[href*='watch.php?channel=']")
            .mapNotNull { it.toLiveSearchResponse() }
            .filter { isAllowedChannel(it.name) }
            .distinctBy { it.url }

        if (homeChannels.isNotEmpty()) return homeChannels

        throw ErrorLoadingException(
            "DaddyLive kanal listesi HTML içinde bulunamadı. " +
                "Sayfa artık bağlantıları JavaScript ile oluşturuyor olabilir."
        )
    }

    private fun isAllowedChannel(rawName: String): Boolean {
        val normalized = rawName.lowercase()
            .replace(Regex("[^a-z0-9+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val adultMarkers = listOf(
            "18+", "18 plus", "adult", "adults", "xxx", "porn", "porno",
            "erotic", "erotica", "playboy", "hustler", "sex channel",
            "adult channel", "live cam", "live cams", "cam feed",
            "big brother live cam", "big brother live feed",
            "fanduel", "draftkings", "bet365", "betway", "sportsbook",
            "1xbet", "pinnacle", "william hill", "betfair", "pointsbet",
            "bovada", "caesars sportsbook"
        )

        return adultMarkers.none { marker -> normalized.contains(marker) }
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse? {
        val href = attr("href").trim()
        val title = attr("data-title").trim()
            .ifBlank { attr("title").trim() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }

        if (title.isBlank() || href.isBlank()) return null

        val fullUrl = fixUrl(href)
        val logo = selectFirst("img")?.let { image ->
            image.attr("data-src")
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
        }

        return newLiveSearchResponse(title, fullUrl, TvType.Live) {
            this.posterUrl = logo
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = requestHeaders(mainUrl)
        ).document

        val title = document.title().trim().ifBlank { "DaddyLive" }
        if (!isAllowedChannel(title)) return null

        return newLiveStreamLoadResponse(
            url = url,
            name = title,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Uri.parse(data).getQueryParameter("id")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        // Eski/kaydedilmiş bağlantılardan da 18+ kanalların açılmasını engelle.
        val watchPage = try {
            app.get(data, headers = requestHeaders(mainUrl)).document
        } catch (_: Exception) {
            return false
        }
        val channelTitle = watchPage.selectFirst("h1, h2")?.text()
            ?.substringBefore("(ID")
            ?.trim().orEmpty()
        if (channelTitle.isNotBlank() && !isAllowedChannel(channelTitle)) return false

        // Site API belgesinde yayınlanan altı geçerli player klasörü.
        val playerPaths = listOf(
            "stream",
            "cast",
            "watch",
            "plus",
            "casting",
            "player"
        )

        for (path in playerPaths) {
            val playerUrl = "$mainUrl/$path/stream-$id.php"

            if (
                tryToExtract(
                    playerUrl,
                    data,
                    path,
                    subtitleCallback,
                    callback
                )
            ) {
                return true
            }
        }

        return false
    }

    private suspend fun tryToExtract(
        playerUrl: String,
        watchUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerResponse = try {
            app.get(
                playerUrl,
                headers = requestHeaders(watchUrl)
            )
        } catch (_: Exception) {
            return false
        }

        if (!playerResponse.isSuccessful) return false

        var currentUrl = playerUrl
        var currentHtml = playerResponse.text
        var currentDocument = Jsoup.parse(currentHtml, currentUrl)

        val directUrl = extractM3u8(currentHtml)
        if (
            !directUrl.isNullOrBlank() &&
            tryEmitM3u8(label, directUrl, currentUrl, callback)
        ) {
            return true
        }

        if (tryLoadExtractor(playerUrl, watchUrl, subtitleCallback, callback)) {
            return true
        }

        for (depth in 0 until 3) {
            val iframeSource = firstIframe(currentDocument) ?: break
            val iframeUrl = resolveUrl(currentUrl, iframeSource)

            if (
                tryLoadExtractor(
                    iframeUrl,
                    currentUrl,
                    subtitleCallback,
                    callback
                )
            ) {
                return true
            }

            val iframeResponse = try {
                app.get(
                    iframeUrl,
                    headers = requestHeaders(currentUrl)
                )
            } catch (_: Exception) {
                break
            }

            if (!iframeResponse.isSuccessful) break

            currentHtml = iframeResponse.text
            currentUrl = iframeUrl
            currentDocument = Jsoup.parse(currentHtml, currentUrl)

            val iframeM3u8 = extractM3u8(currentHtml)
            if (
                !iframeM3u8.isNullOrBlank() &&
                tryEmitM3u8(label, iframeM3u8, currentUrl, callback)
            ) {
                return true
            }
        }

        return false
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var linkFound = false

            loadExtractor(
                url,
                referer,
                subtitleCallback
            ) {
                linkFound = true
                callback(it)
            }

            // loadExtractor true dönebilir ama extractor hiç bağlantı üretmemiş olabilir.
            // Yalnızca gerçek bir ExtractorLink callback'i geldiyse başarılı say.
            linkFound
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryEmitM3u8(
        label: String,
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = resolveUrl(referer, rawUrl)

        val manifestResponse = try {
            app.get(
                streamUrl,
                headers = requestHeaders(referer)
            )
        } catch (_: Exception) {
            return false
        }

        if (!manifestResponse.isSuccessful) return false

        val manifest = manifestResponse.text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!manifest.startsWith("#EXTM3U")) return false

        callback(
            newExtractorLink(
                source = name,
                name = name + " - " + label,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = requestHeaders(referer)
            }
        )

        return true
    }

    private fun firstIframe(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst(
            "iframe[src], iframe[data-src], iframe[data-lazy-src]"
        ) ?: return null

        return iframe.attr("data-src")
            .takeIf { it.isNotBlank() }
            ?: iframe.attr("data-lazy-src")
                .takeIf { it.isNotBlank() }
            ?: iframe.attr("src")
                .takeIf { it.isNotBlank() }
    }

    private fun extractM3u8(html: String): String? {
        val normalized = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val match = Regex(
            """(?:https?:)?//[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).find(normalized) ?: return null

        return match.value.trimEnd(')', ']', '}', ',', ';')
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String): String {
        val cleaned = rawUrl
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun requestHeaders(referer: String): Map<String, String> {
        val origin = try {
            val uri = URI(referer)
            val scheme = uri.scheme
            val authority = uri.rawAuthority

            if (scheme.isNullOrBlank() || authority.isNullOrBlank()) {
                mainUrl
            } else {
                scheme + "://" + authority
            }
        } catch (_: Exception) {
            mainUrl
        }

        return mapOf(
            "Referer" to referer,
            "Origin" to origin,
            "User-Agent" to userAgent,
            "Accept" to "*/*"
        )
    }
}
