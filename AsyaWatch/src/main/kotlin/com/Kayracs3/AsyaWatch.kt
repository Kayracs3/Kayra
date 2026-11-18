package com.asyawatch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.net.URLEncoder

/**
 * AsyaWatch sağlayıcısı.
 * Kullanıcının verdiği AsyaWatchProvider kodu temel alınarak düzenlenmiştir.
 */
class AsyaWatchProvider : MainAPI() {
    override var name = "AsyaWatch"
    override var mainUrl = "https://asyawatch.com"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/json,*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to (mainUrl + "/"),
        )

    override val mainPage = mainPageOf(
        mainUrl + "/dizi-izle" to "Yeni Diziler",
        mainUrl + "/film-izle" to "Son Filmler",
    )

    private val cardSelector =
        "a.pcard[href], a[href*='/dizi/'], a[href*='/film/']"

    private val badImg = Regex(
        """(?i)^(?:data:|about:|javascript:)|/(?:pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(?:png|gif|jpe?g|svg|webp)(?:[?#]|$)"""
    )

    private val mediaRegex = Regex(
        """(?i)(?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^"'<>\s]*)?"""
    )

    private val playerUrlRegex = Regex(
        """(?i)["'](?:src|master|file|source|url)["']\s*:\s*["']((?:https?:)?//[^"']+)["']"""
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (page > 1) {
            request.data.trimEnd('/') + "?page=" + page
        } else {
            request.data
        }

        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        val url = mainUrl + "/ara?q=" + URLEncoder.encode(term, "UTF-8")
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrNull() ?: return emptyList()

        return document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }
    }

    private fun Element.toHtmlCard(): SearchResponse? {
        val href = absoluteUrl(attr("href"))?.takeIf { it.isNotBlank() } ?: return null
        val title = sequenceOf(
            selectFirst("a.tcn, b, h2, h3")?.text(),
            attr("title"),
            selectFirst("img")?.attr("alt"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull() ?: return null

        val image = selectFirst("img")
        val rawPoster = image?.let {
            sequenceOf(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src"),
            ).firstOrNull { value -> value.isNotBlank() }
        }?.takeIf { !badImg.containsMatchIn(it) }

        val poster = rawPoster?.let { absoluteUrl(it) }
        return if (href.contains("/dizi/", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            throw ErrorLoadingException("AsyaWatch içerik sayfası açılamadı")
        }

        val isSeries = url.contains("/dizi/", ignoreCase = true)
        // Önce gerçek TVSeries/Movie JSON-LD düğümünü seç; Article/CreativeWork
        // düğümü bölüm listesini gölgelememeli.
        val ld = readJsonLd(document, isSeries)
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Başlık okunamadı")

        val imageValue = when (val image = ld?.opt("image")) {
            is JSONArray -> image.optString(0)
            is JSONObject -> image.optString("url")
            is String -> image
            else -> null
        }

        val rawPoster = imageValue?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        val poster = rawPoster?.let { absoluteUrl(it, url) }

        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val rating = ld?.optJSONObject("aggregateRating")
            ?.optString("ratingValue")
            ?.takeIf { it.isNotBlank() }

        val actors = readActors(ld)

        // AsyaWatch sayfasındaki a.simr kartları öneri içerikleridir;
        // gerçek bölümler JSON-LD containsSeason.episode alanında listelenir.
        val recommendations = document.select("a.simr[href]").mapNotNull { anchor ->
            val href = absoluteUrl(anchor.attr("href"), url) ?: return@mapNotNull null
            val recommendationName = anchor.selectFirst("b")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newTvSeriesSearchResponse(recommendationName, href, TvType.TvSeries) {
                posterUrl = absoluteUrl(anchor.selectFirst("img")?.attr("src"), url)
            }
        }.distinctBy { it.url }

        val seasonNodes = when (val rawSeasons = ld?.opt("containsSeason")) {
            is JSONObject -> listOf(rawSeasons)
            is JSONArray -> (0 until rawSeasons.length()).mapNotNull { rawSeasons.optJSONObject(it) }
            else -> emptyList()
        }

        val jsonLdEpisodes = seasonNodes.flatMap { seasonNode ->
            val seasonNumber = seasonNode.optString("seasonNumber").toIntOrNull()
                ?: Regex("""(?i)(?:season|sezon)[\s-]*(\d+)""")
                    .find(seasonNode.optString("name"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            val episodeArray = seasonNode.optJSONArray("episode") ?: JSONArray()
            (0 until episodeArray.length()).mapNotNull { index ->
                val episodeNode = episodeArray.optJSONObject(index) ?: return@mapNotNull null
                val episodeUrl = absoluteUrl(episodeNode.optString("url"), url)
                    ?: return@mapNotNull null
                val episodeName = episodeNode.optString("name").ifBlank { "Bölüm" }
                val episodeNumber = episodeNode.opt("episodeNumber")?.toString()?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(episodeName)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = poster
                }
            }
        }.distinctBy { it.data }

        // Geriye dönük uyumluluk: JSON-LD eksikse yalnızca URL'si açıkça bölüm
        // belirten simr bağlantılarını yedek olarak kullan; öneri dizilerini bölüm sayma.
        val fallbackEpisodes = if (jsonLdEpisodes.isEmpty()) {
            document.select("a.simr[href]").mapNotNull { anchor ->
                val href = absoluteUrl(anchor.attr("href"), url) ?: return@mapNotNull null
                if (!href.contains("bolum-", true) && !href.contains("/episode/", true)) {
                    return@mapNotNull null
                }
                val label = anchor.text().trim().ifBlank { "Bölüm" }
                newEpisode(href) {
                    name = label
                    season = Regex("""(?i)sezon-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episode = Regex("""(?i)bolum-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    posterUrl = poster
                }
            }.distinctBy { it.data }
        } else {
            emptyList()
        }
        val episodes = if (jsonLdEpisodes.isNotEmpty()) jsonLdEpisodes else fallbackEpisodes

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private fun readJsonLd(
        document: org.jsoup.nodes.Document,
        preferSeries: Boolean,
    ): JSONObject? {
        val candidates = mutableListOf<JSONObject>()

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            val parsed: Any = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: return@forEach

            when (parsed) {
                is JSONObject -> {
                    val graph = parsed.optJSONArray("@graph")
                    if (graph != null) {
                        for (index in 0 until graph.length()) {
                            graph.optJSONObject(index)?.let(candidates::add)
                        }
                    } else {
                        candidates.add(parsed)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until parsed.length()) {
                        parsed.optJSONObject(index)?.let(candidates::add)
                    }
                }
            }
        }

        fun hasType(item: JSONObject, expected: String): Boolean {
            return when (val type = item.opt("@type")) {
                is String -> type.contains(expected, ignoreCase = true)
                is JSONArray -> (0 until type.length()).any {
                    type.optString(it).contains(expected, ignoreCase = true)
                }
                else -> false
            }
        }

        val preferredType = if (preferSeries) "TVSeries" else "Movie"
        return candidates.firstOrNull { hasType(it, preferredType) }
            ?: candidates.firstOrNull {
                hasType(it, "TVSeries") || hasType(it, "Movie")
            }
            ?: candidates.firstOrNull {
                hasType(it, "CreativeWork") || hasType(it, "Article")
            }
    }

    private fun readActors(ld: JSONObject?): List<Actor> {
        val raw = ld?.opt("actor") ?: return emptyList()
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) }
            else -> listOf(raw)
        }

        return values.mapNotNull { item ->
            val actorName = when (item) {
                is JSONObject -> item.optString("name")
                is String -> item
                else -> ""
            }.trim()

            actorName.takeIf { it.isNotBlank() }?.let { Actor(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageResponse = runCatching {
            app.get(data, headers = headers, referer = "$mainUrl/")
        }.getOrNull() ?: return false

        val pageText = pageResponse.text
            .replace("\\\"", "\"")
            .replace("\\/", "/")
        val document = pageResponse.document
        val candidates = linkedSetOf<String>()
        candidates.addAll(extractPageCandidates(pageText, data))
        candidates.addAll(extractPageSourceEmbeds(document, data))

        // AsyaWatch sayfasındaki JSON player tanımlarından embed adreslerini al.
        Regex("""(?i)["'](?:src|master)["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(pageText)
            .map { cleanUrl(it.groupValues[1]) }
            .filter {
                it.contains("pichive", true) ||
                    it.contains("hotlinger", true) ||
                    it.contains("playru", true) ||
                    it.isMediaUrl()
            }
            .forEach(candidates::add)

        // Bazı sayfalarda iframe URL'si yalnızca HTML niteliğinde yer alıyor.
        document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src]"
        ).forEach { element ->
            val raw = sequenceOf(element.attr("src"), element.attr("data-src"))
                .firstOrNull { it.isNotBlank() }
            absoluteUrl(raw, data)?.let(candidates::add)
        }

        var found = false

        for (candidate in candidates) {
            if (candidate.isMediaUrl()) {
                val path = candidate.substringBefore('?').lowercase()
                val type = if (path.endsWith(".mp4") || path.endsWith(".webm")) {
                    ExtractorLinkType.VIDEO
                } else if (path.endsWith(".mpd")) {
                    ExtractorLinkType.DASH
                } else {
                    ExtractorLinkType.M3U8
                }
                callback(
                    newExtractorLink(name, "AsyaWatch", candidate, type) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
                    }
                )
                found = true
                continue
            }

            // Yeni FourPichive oynatıcısı NP_CONFIG -> gate proof -> nm.php kullanır.
            val candidateHost = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")
            if (candidateHost == "four.pichive.online" ||
                candidateHost.endsWith(".four.pichive.online")
            ) {
                val fourPichiveFound = runCatching {
                    extractFourPichive(candidate, data, subtitleCallback, callback)
                }.getOrDefault(false)
                if (fourPichiveFound) {
                    found = true
                    continue
                }
            }

            // Embed sunucusunu kendi domain'i ve referer'iyle aç.
            var candidateFound = false
            val embed = runCatching {
                app.get(candidate, headers = headers, referer = "$mainUrl/").text
            }.getOrNull()

            if (embed.isNullOrBlank()) {
                var extractorFound = false
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        extractorFound = true
                    }
                }
                if (extractorFound) found = true
                continue
            }

            val origin = Regex("""^https?://[^/]+""")
                .find(candidate)?.value
            val token = Regex("""(?i)openPlayer\s*\(\s*['"]([^'"]+)""")
                .find(embed)?.groupValues?.getOrNull(1)

            // Asıl kaynak API'si bölüm sayfasının değil embed sunucusunun domain'inde.
            if (!origin.isNullOrBlank() && !token.isNullOrBlank()) {
                val sourceText = runCatching {
                    app.get(
                        "$origin/source2.php?v=$token",
                        headers = headers + mapOf("Accept" to "application/json", "Referer" to candidate),
                        referer = candidate,
                    ).text.replace("\\/", "/")
                }.getOrNull().orEmpty()

                val playlistArray = runCatching {
                    JSONObject(sourceText).optJSONArray("playlist")
                }.getOrNull()

                if (playlistArray != null) {
                    for (i in 0 until playlistArray.length()) {
                        val item = playlistArray.optJSONObject(i) ?: continue
                        val label = item.optString("title").trim().ifBlank { "AsyaWatch" }
                        val sources = item.optJSONArray("sources") ?: continue

                        for (j in 0 until sources.length()) {
                            val rawFile = sources.optJSONObject(j)
                                ?.optString("file")
                                ?.takeIf { it.startsWith("http", true) }
                                ?: continue
                            val master = if (rawFile.contains("m.php", ignoreCase = true)) {
                                rawFile.replace("m.php", "master.m3u8", ignoreCase = true)
                            } else {
                                rawFile
                            }
                            val path = master.substringBefore('?').lowercase()
                            val type = if (path.endsWith(".mp4") || path.endsWith(".webm")) {
                                ExtractorLinkType.VIDEO
                            } else if (path.endsWith(".mpd")) {
                                ExtractorLinkType.DASH
                            } else {
                                ExtractorLinkType.M3U8
                            }
                            callback(
                                newExtractorLink(name, label, master, type) {
                                    this.referer = candidate
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "User-Agent" to ua,
                                        "Referer" to candidate,
                                    )
                                }
                            )
                            candidateFound = true
                            found = true
                        }
                    }
                }
            }

            // Pichive player'larında master/file URL'si proof-of-work imzası ister.
            val rawMasters = Regex(
                """"(?:master|file)"\s*:\s*"(https?://[^"]+)"""",
                RegexOption.IGNORE_CASE,
            ).findAll(embed).map { it.groupValues[1].replace("\\/", "/") }.distinct()

            for (rawMaster in rawMasters) {
                val master = pichiveSign(embed, rawMaster)
                val playlist = runCatching {
                    app.get(
                        master,
                        headers = headers + mapOf("Accept" to "*/*", "Referer" to candidate),
                        referer = candidate,
                    ).text
                }.getOrNull().orEmpty()

                if (!playlist.startsWith("#EXTM3U") &&
                    !master.contains("pichive.online", ignoreCase = true)
                ) {
                    continue
                }

                callback(
                    newExtractorLink(name, "AsyaWatch", master, ExtractorLinkType.M3U8) {
                        this.referer = candidate
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to candidate,
                        )
                    }
                )
                candidateFound = true
                found = true
            }

            // Pichive dışındaki embed sağlayıcıları CloudStream extractor'larına bırak.
            if (!candidateFound) {
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        candidateFound = true
                        found = true
                    }
                }
            }
        }

        // Eski sayfa tipinde player token'ı bölüm sayfasında doğrudan bulunabiliyor.
        // Yeni embed çözümlemesi bağlantı bulamadıysa geriye dönük uyumluluk olarak dene.
        if (!found) {
            val videoId = sequenceOf(
                Regex("""(?i)window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""")
                    .find(pageText)?.groupValues?.getOrNull(1),
                Regex("""(?i)[?&]v=([\w-]+)""")
                    .find(pageText)?.groupValues?.getOrNull(1),
                Regex("""(?i)(?:videoId|video_id|data-video-id)\s*[:=]\s*['"]?([\w-]+)""")
                    .find(pageText)?.groupValues?.getOrNull(1),
            ).firstOrNull { !it.isNullOrBlank() }

            if (!videoId.isNullOrBlank()) {
                val sourceUrl = "$mainUrl/source2.php?v=$videoId"
                val sourceText = runCatching {
                    app.get(
                        sourceUrl,
                        headers = headers + mapOf("Accept" to "application/json", "Referer" to data),
                        referer = data,
                    ).text.replace("\\/", "/")
                }.getOrNull().orEmpty()

                val streams = buildSet {
                    mediaRegex.findAll(sourceText).forEach { add(cleanUrl(it.value)) }
                    playerUrlRegex.findAll(sourceText).forEach { add(cleanUrl(it.groupValues[1])) }
                    runCatching { JSONObject(sourceText) }.getOrNull()?.let { json ->
                        listOf("src", "master", "file", "url").forEach { key ->
                            json.optString(key).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                for (stream in streams) {
                    if (!stream.isMediaUrl()) continue
                    if (emitMedia(stream, data, callback)) found = true
                }
            }
        }

        // Mevcut sayfa içi track altyazıları video çözümleme sonrasında raporlanır.
        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitle = absoluteUrl(raw, data) ?: return@forEach
            runCatching {
                subtitleCallback(
                    newSubtitleFile(
                        lang = track.attr("label").ifBlank {
                            track.attr("srclang").ifBlank { "Türkçe" }
                        },
                        url = subtitle,
                    ) {
                        headers = mapOf("User-Agent" to ua, "Referer" to data)
                    }
                )
            }
        }

        return found
    }

    private fun looksLikePlayerUrl(url: String): Boolean {
        val value = url.lowercase()
        return url.isMediaUrl() ||
            listOf(
                "iframe",
                "player",
                "four.pichive.online",
                "pichive.online",
                "nm.php",
                "nl.php",
                "iframe.php",
                "hotlinger",
                "playru",
            ).any(value::contains)
    }

    private fun extractPageCandidates(raw: String, baseUrl: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val candidates = linkedSetOf<String>()
        val parsed = runCatching { org.jsoup.Jsoup.parse(normalized, baseUrl) }.getOrNull()
        parsed?.select("iframe, video, source, embed")?.forEach { element ->
            for (attribute in listOf("src", "data-src", "data-url", "data-embed", "href")) {
                absoluteUrl(element.attr(attribute), baseUrl)?.let(candidates::add)
            }
        }
        Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""")
            .findAll(normalized)
            .forEach { match ->
                val rawUrl = match.value.trimEnd('\\', ')', ']', ',', ';', '"')
                val absolute = absoluteUrl(rawUrl, baseUrl) ?: return@forEach
                if (looksLikePlayerUrl(absolute)) candidates.add(absolute)
            }
        return candidates.toList()
    }

    private fun extractPageSourceEmbeds(
        document: org.jsoup.nodes.Document,
        baseUrl: String,
    ): Set<String> {
        val script = document.selectFirst("script#__NEXT_DATA__") ?: return emptySet()
        val raw = script.data().ifBlank { script.html() }.trim()
        if (raw.isBlank()) return emptySet()

        val nextData = runCatching { JSONObject(raw) }.getOrNull() ?: return emptySet()
        val pageProps = nextData.optJSONObject("props")?.optJSONObject("pageProps")
            ?: return emptySet()
        val secureValue = pageProps.opt("secureData") ?: return emptySet()

        val secureObject = when (secureValue) {
            is JSONObject -> secureValue
            is String -> {
                val value = secureValue.trim().removeSurrounding("\"")
                val direct = runCatching { JSONObject(value) }.getOrNull()
                direct ?: runCatching {
                    val bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
                    JSONObject(String(bytes, Charsets.UTF_8))
                }.getOrNull() ?: return emptySet()
            }
            else -> return emptySet()
        }

        val found = linkedSetOf<String>()

        fun addFromHtml(rawHtml: String) {
            if (rawHtml.isBlank()) return
            val html = rawHtml
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u003d", "=")
                .replace("&amp;", "&")
            val parsed = runCatching { org.jsoup.Jsoup.parse(html, baseUrl) }.getOrNull()
            parsed?.select("iframe[src], iframe[data-src], video[src], source[src], embed[src]")
                ?.forEach { element ->
                    val rawUrl = sequenceOf(
                        element.attr("src"),
                        element.attr("data-src"),
                    ).firstOrNull { it.isNotBlank() }
                    absoluteUrl(rawUrl, baseUrl)?.let(found::add)
                }
            Regex("""(?i)<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                .findAll(html)
                .forEach { match ->
                    absoluteUrl(match.groupValues[1], baseUrl)?.let(found::add)
                }
            Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""")
                .findAll(html)
                .map { cleanUrl(it.value) }
                .filter {
                    it.contains("pichive.online", true) ||
                        it.contains("hotlinger", true) ||
                        it.contains("playru", true)
                }
                .forEach { rawUrl ->
                    absoluteUrl(rawUrl, baseUrl)?.let(found::add)
                }
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is String) {
                            if (key.contains("source_content", true) ||
                                key.contains("sourceContent", true) ||
                                child.contains("<iframe", true) ||
                                child.contains("pichive.online", true)
                            ) {
                                addFromHtml(child)
                            }
                        } else {
                            walk(child)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index))
                }
                is String -> addFromHtml(value)
            }
        }

        walk(secureObject)
        return found
    }

    private fun parseNpConfig(html: String): JSONObject? {
        val pattern = Regex(
            """window\.NP_CONFIG\s*=\s*(\{.+?\});\s*(?:window\.|</script>|$)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = pattern.find(html) ?: return null
        return runCatching { JSONObject(match.groupValues[1].trim()) }.getOrNull()
    }

    private fun appendQueryParameter(url: String, key: String, value: String): String {
        if (Regex("""(?i)(?:[?&])${Regex.escape(key)}=""").containsMatchIn(url)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$key=$value"
    }

    private fun pichiveBrowserHeaders(iframeUrl: String): Map<String, String> {
        val origin = runCatching {
            URI(iframeUrl).let { it.scheme + "://" + it.authority }
        }.getOrDefault("https://four.pichive.online")
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to iframeUrl,
            "Origin" to origin,
        )
    }

    private suspend fun extractFourPichive(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerHeaders = pichiveBrowserHeaders(iframeUrl)
        val response = runCatching {
            app.get(
                iframeUrl,
                headers = playerHeaders,
                referer = referer,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false
        val html = response.text
        val config = parseNpConfig(html) ?: return false
        val gate = config.optJSONObject("gate")
        // Çalışan AsyaWatch DEX sürümündeki NP_GATE / SHA-256 çözümleyicisi.
        val proof = if (gate != null) pichiveGateNonce(html) else null
        if (gate != null && proof == null) return false

        val origin = runCatching {
            URI(iframeUrl).let { it.scheme + "://" + it.authority }
        }.getOrDefault("https://four.pichive.online")

        // ExoPlayer/Cronet has a separate cookie jar; forward iframe cookies to the media requests.
        val cookies = response.headers.values("Set-Cookie")
            .mapNotNull { cookie ->
                cookie.substringBefore(';').trim().takeIf {
                    it.contains('=') && it.substringBefore('=').isNotBlank()
                }
            }
            .distinct()
            .joinToString("; ")

        val mediaHeaders = playerHeaders + mapOf(
            "Referer" to iframeUrl,
            "Origin" to origin,
            "Accept" to "*/*",
        ) + if (cookies.isBlank()) emptyMap() else mapOf("Cookie" to cookies)

        val subtitles = config.optJSONArray("subtitles")
        if (subtitles != null) {
            val seen = hashSetOf<String>()
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val rawUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                val absolute = absoluteUrl(rawUrl, iframeUrl) ?: continue
                val finalUrl = if (proof != null) appendQueryParameter(absolute, "p", proof) else absolute
                if (!seen.add(finalUrl)) continue

                val subtitle = SubtitleFile(
                    lang = sub.optString("label").ifBlank { "Altyazı" },
                    url = finalUrl,
                )
                subtitle.headers = mediaHeaders + ("Accept" to "text/vtt, text/plain, */*")
                subtitleCallback(subtitle)
            }
        }

        val sources = config.optJSONArray("sources") ?: return false
        var emitted = false
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val rawMaster = sequenceOf("master", "url", "file", "src")
                .mapNotNull { key ->
                    source.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
                }
                .firstOrNull() ?: continue
            val masterUrl = absoluteUrl(rawMaster, iframeUrl) ?: continue
            val parsed = runCatching { URI(masterUrl) }.getOrNull() ?: continue
            val masterHost = parsed.host.orEmpty()
            val masterPath = parsed.path.orEmpty()

            val validHost = masterHost.equals("four.pichive.online", true) ||
                masterHost.endsWith(".four.pichive.online", true)
            val validPath = masterPath.equals("/nm.php", true) ||
                masterPath.equals("/nl.php", true) ||
                masterPath.endsWith(".m3u8", true)
            if (!validHost || !validPath) continue

            val finalMaster = if (proof != null) appendQueryParameter(masterUrl, "p", proof) else masterUrl
            val label = sequenceOf("title", "name", "kind")
                .mapNotNull { key ->
                    source.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
                }
                .firstOrNull() ?: "Orijinal"

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = finalMaster,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = iframeUrl
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }
        return emitted
    }

    private fun pichiveGateNonce(html: String): String? {
        val gate = Regex(
            """"gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?"""
        ).find(html) ?: return null
        val n = gate.groupValues[1]
        val bits = gate.groupValues[2].toIntOrNull() ?: 12
        val digest = MessageDigest.getInstance("SHA-256")

        fun wordAt(message: String, index: Int): Long {
            val bytes = digest.digest(message.toByteArray(Charsets.UTF_8))
            val offset = index.coerceIn(0, 7) * 4
            return ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)
        }

        val function = Regex(
            """window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};"""
        ).find(html)

        if (function == null) {
            if (bits !in 1..32) return null
            for (i in 0 until 50_000_000) {
                if (wordAt("$n:$i", 0) ushr (32 - bits) == 0L) return i.toString()
            }
            return null
        }

        val nonceVariable = function.groupValues[1]
        val body = function.groupValues[3]
        val charMatch = Regex(
            """var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)"""
        ).find(body) ?: return null
        val saltVariable = charMatch.groupValues[1]
        val salt = charMatch.groupValues[2].split(',')
            .map { it.trim().toInt().toChar() }.joinToString("")

        val nonceMatch = Regex(
            """var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))"""
        ).find(body) ?: return null
        val nonceValueVariable = nonceMatch.groupValues[1]
        val radix = nonceMatch.groupValues[2].toIntOrNull()?.coerceIn(2, 36) ?: 10

        val maskMatch = Regex(
            """\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0"""
        ).find(body) ?: return null
        val parts = maskMatch.groupValues[1].split('+').map { it.trim() }
        val wordIndex = maskMatch.groupValues[2].toIntOrNull() ?: return null
        val operation = maskMatch.groupValues[3]
        val rhs = maskMatch.groupValues[4].toLongOrNull() ?: return null
        if (rhs !in 0L..31L) return null

        for (i in 0 until 50_000_000) {
            val nonce = java.lang.Long.toString(i.toLong(), radix)
            val message = buildString {
                for (part in parts) {
                    when (part) {
                        nonceVariable -> append(n)
                        saltVariable -> append(salt)
                        nonceValueVariable -> append(nonce)
                        else -> append(part.trim('\'', '"'))
                    }
                }
            }
            val word = wordAt(message, wordIndex)
            val valid = if (operation == ">>>") {
                (word ushr rhs.toInt()) == 0L
            } else {
                (word and rhs) == 0L
            }
            if (valid) return nonce
        }
        return null
    }

    private fun pichiveSign(html: String, url: String): String {
        if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) {
            return url
        }
        val nonce = pichiveGateNonce(html) ?: return url
        return "$url&p=$nonce"
    }

    private suspend fun emitMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = cleanUrl(url)
        val path = clean.substringBefore('?').lowercase()
        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(name, name, clean, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                )
            }
        )
        return true
    }

    private fun String.isMediaUrl(): Boolean {
        val path = lowercase().substringBefore('#').substringBefore('?')
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm")
    }

    private fun cleanUrl(raw: String): String {
        val value = raw.trim()
            .replace("\\/", "/")
            .trim('"', '\'', ' ', ')', ']', '}', ',')
        return if (value.startsWith("//")) "https:" + value else value
    }

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true) &&
                    !it.equals("undefined", ignoreCase = true)
            } ?: return null
        return runCatching { URI(base).resolve(value).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
