package com.dizican

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class DiziCaNProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://dizican.cc"
    override var name = "DiziCaN"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.map { rowName ->
        MainPageData(rowName, when (rowName) {           
            "Fantastik" -> "https://dizican.cc/category/fantastik-filmleri-izle/"
            "Gençlik" -> "https://dizican.cc/category/genclik/"
            "Gerilim" -> "$mainUrl/category/gerilim-filmleri-izle/"
            "Aksiyon" -> "$mainUrl/dizi-kategori/aksiyon-dizileri-izle/"
            "Dram" -> "$mainUrl/dizi-kategori/dram-dizileri-izle/"
            "Komedi" -> "$mainUrl/dizi-kategori/komedi-dizileri-izle/"
            "Korku" -> "$mainUrl/dizi-kategori/korku-dizileri-izle/"
            "Romantik" -> "$mainUrl/dizi-kategori/romantik-dizileri-izle/"
            "Bilim Kurgu" -> "$mainUrl/dizi-kategori/bilim-kurgu-dizileri-izle/"
            "Gizem" -> "$mainUrl/dizi-kategori/gizem-dizileri-izle/"
            "Suç" -> "$mainUrl/dizi-kategori/suc-dizileri-izle/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name in ROWS) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val doc = app.get(request.data, headers = uaHeaders).document
            return newHomePageResponse(request.name, cards(doc), hasNext = false)
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Fantastik", "Gençlik", "Gerilim", "Aksiyon", "Dram",
            "Komedi", "Korku", "Romantik", "Bilim Kurgu", "Gizem", "Suç")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        val first = cards(app.get(url, headers = uaHeaders).document, query)
        if (first.isNotEmpty() || !query.contains(' ')) return first
        val token = query.trim().split(Regex("\\s+")).first()
        return cards(app.get("$mainUrl/?s=" + java.net.URLEncoder.encode(token, "UTF-8"), headers = uaHeaders).document, token)
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> =
        doc.select("div.col-lg-8 a[href], div.dz-grid a[href]").ifEmpty { doc.select("a[href]") }.mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/bolum/").none { path.startsWith(it) } || path.trim('/').length < 4) return@mapNotNull null
        if (path.startsWith("/ulke/") || path.startsWith("/category/") || path.startsWith("/dizi-kategori/")) return@mapNotNull null
        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val boxTitle = card.parents().firstOrNull { it.hasClass("movie-box") || it.tagName() == "article" }
            ?.selectFirst(".name, .title, h2, h3")?.text()?.trim().orEmpty()
        val title = card.attr("title").trim()
            .ifBlank { boxTitle }
            .ifBlank { img.attr("alt").trim() }
            .ifBlank { card.text().trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        }
    }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: throw ErrorLoadingException("$name: başlık okunamadı")
        ).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val basePath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val slug = basePath.trim('/').split('/').lastOrNull().orEmpty()
        val slugBase = slug.removeSuffix("-izle").removeSuffix("-film-izle")
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!path.contains("/bolum/")) return@mapNotNull null
            val seasonEp = Regex("""(\d+)-sezon-(\d+)-bolum""").find(path)
            val number = seasonEp?.groupValues?.get(2)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[^/]*?[-_](\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(href) {
                if (seasonEp != null) this.season = seasonEp.groupValues[1].toIntOrNull() ?: 1
                this.episode = number
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val firstResp = app.get(data, headers = uaHeaders)
        val baseData = data.trimEnd('/')
        val partUrls = (listOf(data) + firstResp.document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href.trimEnd('/').matches(Regex(Regex.escape(baseData) + """/\d+"""))) href else null
        }).distinct().take(6)

        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null) {
            if (!stream.startsWith("http") || isAdvertisementResource(stream) || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
            if (isM3u8 && explicitQuality == null) {
                val playlist = runCatching { app.get(stream, headers = uaHeaders, referer = referer).text }.getOrNull().orEmpty()
                for ((h, vUrl) in parseHlsVariants(stream, playlist)) {
                    emit(vUrl, "$label ${h}p", referer, h)
                }
            }
        }

        for (pageUrl in partUrls) {
            val resp = if (pageUrl == data) firstResp else runCatching { app.get(pageUrl, headers = uaHeaders, referer = data) }.getOrNull() ?: continue
            val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
                .findAll(text).map { it.groupValues[1] }.toList()
            val regexFrames = Regex("""<iframe\b[^>]*?\b(?:src|data-src|vdo-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(text).mapNotNull { fixUrlNull(it.groupValues[1]?.replace("&amp;", "&")) }.toList()
            val domFrames = resp.document.select("iframe").mapNotNull {
                val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
                fixUrlNull(raw)
            }
            val frames = (domFrames + regexFrames).distinct().filterNot(::isAdvertisementResource)
            // Page-level raw media URLs are intentionally not emitted as "Direct".
            // DiziCaN includes short preroll/ad clips beside the real player URL.
            // Resolve iframe/embed URLs through their provider extractors instead.
            val handledVidmoly = mutableSetOf<String>()

            for (embed in embeds) {
                val absolute = fixUrl(embed)
                if (absolute == pageUrl) continue
                if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
                if (isVidmolyUrl(absolute)) {
                    if (handledVidmoly.add(absolute)) {
                        val extracted = loadVidmolySources(absolute, pageUrl, subtitleCallback, callback)
                        if (extracted) found = true
                        else if (loadExtractor(vidmolyExtractorUrl(absolute), pageUrl, subtitleCallback, callback)) found = true
                    }
                    continue
                }
                if (absolute.contains(".mp4") || absolute.contains(".m3u8")) {
                    emit(absolute, "Embed", pageUrl)
                    continue
                }
                val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = pageUrl).text }.getOrNull() ?: continue
                val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
                for (m in innerMedia) emit(m.value, "Embed", absolute)
            }
            for (frame in frames) {
                if (isVidmolyUrl(frame)) {
                    if (handledVidmoly.add(frame)) {
                        val extracted = loadVidmolySources(frame, pageUrl, subtitleCallback, callback)
                        if (extracted) found = true
                        else if (loadExtractor(vidmolyExtractorUrl(frame), pageUrl, subtitleCallback, callback)) found = true
                    }
                    continue
                }
                if (!frame.startsWith("http") || frame.endsWith("/embed/") || frame.endsWith("/video/") ||
                    frame.contains("vzaar.com") || frame.contains("youtu")) continue
                Regex("""[?&]video=([^&#"'\s]+)""").find(frame)?.groupValues?.get(1)?.let { enc ->
                    val decoded = runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(enc)
                    val resolved = runCatching { java.net.URI(frame).resolve(decoded).toString() }.getOrDefault(decoded)
                    if (resolved.contains(".m3u8") || resolved.contains(".mp4")) {
                        emit(resolved, "HintDizi", frame)
                        return@let
                    }
                }
                if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                    emit(frame, "Embed", pageUrl)
                    continue
                }
                if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) {
                    if (loadOkRu(frame, callback)) found = true
                    continue
                }
                val seenBefore = seen.size
                val inner = runCatching { app.get(frame, headers = uaHeaders, referer = pageUrl).text }.getOrNull()
                if (inner != null) {
                    val innerText = inner.replace("\\/", "/")
                    for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                        emit(m.value, "Embed", frame)
                    }
                    for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)"""").findAll(innerText)) {
                        val subUrl = fixUrl(sub.groupValues[1])
                        if (!isAdvertisementResource(subUrl) && loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                    }
                }
                if (seen.size == seenBefore && loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
            }
            val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
            for (m in subs) {
                subtitleCallback.invoke(SubtitleFile("tr", m.value))
            }
        }
        if (!found) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }

    /**
     * Vidmoly bazen HLS adresini JWPlayer setup betiğinden ayrı bir betikte tutar.
     * Resmî çıkarıcı yalnızca sources: bulunan ilk betiği okuduğu için bu betikler atlanabilir.
     * Tüm betikleri JWPlayer yardımcısından geçir; ayrıca canlı ve imzalı vmpx HLS adresini
     * sayfa açıldığı anda yakala. İmzalı URL kalıcı olarak saklanmaz.
     */
    private suspend fun loadVidmolySources(
        rawEmbed: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = vidmolyExtractorUrl(rawEmbed)
        val requestHeaders = mapOf(
            "user-agent" to browserHeaders.getValue("user-agent"),
            "Sec-Fetch-Dest" to "iframe",
        )
        val response = runCatching {
            app.get(embedUrl, headers = requestHeaders, referer = pageReferer)
        }.getOrNull() ?: return false

        val embedHost = runCatching {
            val uri = URI(embedUrl)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault("https://vidmoly.biz/")

        var found = false
        val emitted = mutableSetOf<String>()

        fun emitLink(link: ExtractorLink) {
            val stream = link.url
            if (!stream.startsWith("http") || isAdvertisementResource(stream) || !emitted.add(stream)) return
            link.referer = embedUrl
            link.headers = link.headers + requestHeaders
            callback(link)
            found = true
        }

        val scripts = response.document.select("script").map { it.data() }.filter { it.isNotBlank() }
        for (rawScript in scripts) {
            val candidates = linkedSetOf<String>()
            candidates.add(rawScript)
            candidates.add(
                rawScript.replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("&amp;", "&")
            )
            runCatching { JsUnpacker(rawScript).unpack() }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != rawScript }
                ?.let { candidates.add(it) }

            for (script in candidates) {
                runCatching {
                    JwPlayerHelper.extractStreamLinks(
                        script,
                        "Vidmoly",
                        embedHost,
                        { link -> emitLink(link) },
                        subtitleCallback,
                    )
                }
            }
        }

        // Vidmoly's hls2 URL may be present outside the JWPlayer sources array.
        // Extract it dynamically from the current embed response; signed query values expire.
        val normalizedHtml = response.text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
        val vmpxHls = Regex(
            """https?://[^"'\s<>]*vmpx\.online/[^"'\s<>]+?(?:\.m3u8|master\.txt)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
        for (match in vmpxHls.findAll(normalizedHtml)) {
            val stream = match.value.trimEnd(',', ';', ')', ']')
            if (stream.isBlank() || isAdvertisementResource(stream) || !emitted.add(stream)) continue
            callback(
                newExtractorLink(name, "Vidmoly HLS", stream, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.headers = requestHeaders
                }
            )
            found = true
        }
        return found
    }

    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("user-agent" to "Mozilla/5.0 (Linux; Android 13)", "Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") && !isAdvertisementResource(it) } ?: continue
            val label = item.optString("name").ifBlank { "OkRu" }
            val q = when (label.lowercase()) {
                "mobile", "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> getQualityFromName(label.uppercase())
            }
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = q
                }
            )
            found = true
        }
        return found
    }
}


// -----------------------------------------------------------------------------
// DiziCaN'a özel küçük yardımcılar. Başka modüllere bağlı değildir.
// -----------------------------------------------------------------------------

private val browserHeaders: Map<String, String> = mapOf(
    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "accept-language" to "tr-TR,tr;q=0.9",
)

private val PLACEHOLDER_IMG = Regex(
    """^(data:|about:|javascript:)|/(pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(png|gif|jpg|jpeg|svg|webp)""",
    RegexOption.IGNORE_CASE,
)
private val BG_URL = Regex("""url\(['"]?([^'")\s]+)['"]?\)""", RegexOption.IGNORE_CASE)

private fun Element.posterSrcLazy(): String? {
    for (attrName in listOf("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "data-lazy", "data-bg", "data-background", "data-image", "data-poster", "src")) {
        val value = attr(attrName).trim()
        if (value.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(value)) return value
    }
    for (attrName in listOf("data-srcset", "srcset")) {
        val value = attr(attrName).trim().substringBefore(',').trim().substringBefore(' ').trim()
        if (value.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(value)) return value
    }
    val style = attr("style").trim()
    if (style.isNotEmpty()) {
        val background = BG_URL.find(style)?.groupValues?.get(1)?.trim().orEmpty()
        if (background.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(background)) return background
    }
    return null
}

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME=\"(\d{3,4})p?\"""", RegexOption.IGNORE_CASE)

private fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
    if (!m3u8Text.contains("#EXT-X-STREAM-INF") ||
        m3u8Text.contains("TYPE=AUDIO", ignoreCase = true) ||
        m3u8Text.contains("AUDIO=", ignoreCase = true)
    ) return emptyList()

    val base = runCatching { URI(masterUrl.trim()) }.getOrNull() ?: return emptyList()
    val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val variants = ArrayList<Pair<Int, String>>()
    val seen = HashSet<String>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val height = HLS_RES.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: HLS_NAME.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            var next = index + 1
            while (next < lines.size && lines[next].startsWith("#")) next++
            if (next < lines.size) {
                val target = runCatching {
                    val raw = lines[next]
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else base.resolve(raw).toString()
                }.getOrNull()
                if (!target.isNullOrBlank() && height > 0 && seen.add("$height|$target")) {
                    variants.add(height to target)
                }
                index = next
            }
        }
        index++
    }
    return variants.sortedByDescending { it.first }
}

/**
 * Açıkça reklam olarak işaretlenmiş medya adreslerini ele.
 * Aynı dosyanın içine birleştirilmiş (server-side stitched) reklamları bu kontrol ayıramaz.
 */
private fun isAdvertisementResource(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    val host = uri.host.orEmpty().lowercase()
    val knownAdHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "propellerads.com",
    )
    if (knownAdHosts.any { host == it || host.endsWith(".$it") }) return true

    val adPathSegments = setOf("ad", "ads", "advert", "advertisement", "preroll", "pre-roll", "vast", "vpaid", "commercial")
    if (uri.rawPath.orEmpty().lowercase().split('/').any { it in adPathSegments }) return true

    val adQueryKeys = setOf("ad", "adtag", "ad_tag", "vast", "vasturl", "preroll", "advertisement")
    return uri.rawQuery.orEmpty().split('&')
        .map { it.substringBefore('=').lowercase() }
        .any { it in adQueryKeys }
}

private fun vidmolyExtractorUrl(rawUrl: String): String {
    val host = runCatching { URI(rawUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    // vidmoly.net embed pages currently redirect to vidmoly.biz.
    // Start from .biz so CloudStream selects Vidmolybiz and resolves relative media URLs correctly.
    return if (host == "vidmoly.net") {
        rawUrl.replace("://vidmoly.net", "://vidmoly.biz", ignoreCase = true)
    } else {
        rawUrl
    }
}

private fun isVidmolyUrl(rawUrl: String): Boolean {
    val host = runCatching { URI(rawUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    return listOf("vidmoly.net", "vidmoly.to", "vidmoly.biz", "vidmoly.me")
        .any { host == it || host.endsWith(".$it") }
}
