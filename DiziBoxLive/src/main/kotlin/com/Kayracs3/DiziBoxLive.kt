package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziBoxLive : MainAPI() {

    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBOX Live"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/efsane-diziler/" to "Evsane Diziler",
        "$mainUrl/arsiv/" to "Arşiv",
    )

    // =========================================================================
    //  Ana sayfa / arama
    // =========================================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = requestDocument(url)
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = document
            .select("a[href*='/diziler/']")
            .asSequence()
            .filterNot { isEpisodeAnchor(it) }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
        )

        for (url in urls) {
            val document = requestDocument(url) ?: continue

            val results = document
                .select("a[href*='/diziler/']")
                .asSequence()
                .filterNot { isEpisodeAnchor(it) }
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .toList()

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    // =========================================================================
    //  Detay sayfası
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        val normalized = fixUrl(url)
        val document = requestDocument(normalized) ?: return null
        val title = pageTitle(document) ?: return null
        val episodes = parseEpisodes(document)

        val poster = posterOf(document)
        val plot = pagePlot(document)
        val meta = pageMeta(document)

        val episodeList = episodes.ifEmpty {
            listOf(
                newEpisode(normalized) {
                    name = title
                    season = episodeSeason(normalized)
                    episode = episodeNumber(normalized)
                    posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            normalized,
            TvType.TvSeries,
            episodeList,
        ) {
            posterUrl = poster
            this.plot = plot
            year = meta.year
            meta.rating?.let { score = Score.from10(it) }
            if (meta.genres.isNotEmpty()) tags = meta.genres
            if (meta.actors.isNotEmpty()) {
                actors = meta.actors.map { ActorData(Actor(it)) }
            }
            addTrailer(meta.trailer)
        }
    }

    // =========================================================================
    //  Video bağlantıları
    // =========================================================================

    /** Gönderilen link sayısını ve WebView denemelerini sayar. */
    private class Sink(private val target: (ExtractorLink) -> Unit) {
        var count = 0
        var webViewTries = 0
        val send: (ExtractorLink) -> Unit = {
            count++
            target(it)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val response = runCatching {
            app.get(
                episodeUrl,
                headers = headers,
                referer = "$mainUrl/",
            )
        }.getOrNull() ?: return false

        val document = response.document
        val sink = Sink(callback)
        val seen = HashSet<String>()

        // Bölüm sayfası + "alternatif kaynak" sayfaları
        val pages = LinkedHashMap<String, Document>()
        pages[episodeUrl] = document

        for (alt in alternativePages(document, episodeUrl).take(6)) {
            val altDoc = runCatching {
                app.get(alt, headers = headers, referer = episodeUrl).document
            }.getOrNull() ?: continue
            pages[alt] = altDoc
        }

        for ((pageUrl, doc) in pages) {
            resolvePage(doc, pageUrl, 0, seen, subtitleCallback, sink)
        }

        // Hiçbir şey bulunamadıysa son çare: sayfayı WebView ile aç
        if (sink.count == 0) {
            interceptProvider(episodeUrl, "$mainUrl/", subtitleCallback, sink)
        }

        return sink.count > 0
    }

    /**
     * Bir sayfadaki video kaynaklarını çözer:
     *  1) sayfaya gömülü doğrudan m3u8/mp4 adresleri (paketli JS dahil)
     *  2) iframe / data-* adayları -> loadExtractor
     *  3) extractor bulamazsa iç içe iframe'e inip aynısını tekrarlar
     *  4) olmazsa WebView ile ağ trafiğini dinler
     */
    private suspend fun resolvePage(
        doc: Document,
        pageUrl: String,
        depth: Int,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: Sink,
    ) {
        collectSubtitles(doc, subtitleCallback)

        // 1) gömülü doğrudan medya adresleri
        for (media in scanMedia(doc.html())) {
            if (seen.add(media)) emitMedia(media, pageUrl, sink.send)
        }

        doc.select("script")
            .map { it.data() }
            .filter { it.contains("eval(function(p,a,c,k") }
            .forEach { packed ->
                val unpacked = runCatching { getAndUnpack(packed) }.getOrNull()
                    ?: return@forEach
                for (media in scanMedia(unpacked)) {
                    if (seen.add(media)) emitMedia(media, pageUrl, sink.send)
                }
            }

        // 2) embed adayları
        for (candidate in embedCandidates(doc)) {
            val clean = candidate.decodeEmbedded()
            if (clean.isBlank() || isBlocked(clean) || isStaticAsset(clean)) continue
            if (!seen.add(clean)) continue

            if (isMediaUrl(clean)) {
                emitMedia(clean, pageUrl, sink.send)
                continue
            }

            val before = sink.count

            runCatching {
                loadExtractor(clean, pageUrl, subtitleCallback, sink.send)
            }
            if (sink.count > before) continue

            // 3) iç içe iframe / oynatıcı sayfası
            if (depth < 2) {
                val nested = runCatching {
                    app.get(
                        clean,
                        headers = headers + mapOf("Referer" to pageUrl),
                        referer = pageUrl,
                    )
                }.getOrNull()

                if (nested != null && nested.isSuccessful) {
                    resolvePage(
                        nested.document,
                        clean,
                        depth + 1,
                        seen,
                        subtitleCallback,
                        sink,
                    )
                }
            }

            // 4) WebView (en fazla 2 kez, her biri yavaştır)
            if (sink.count == before && isPlayerUrl(clean) && sink.webViewTries < 2) {
                sink.webViewTries++
                interceptProvider(clean, pageUrl, subtitleCallback, sink)
            }
        }
    }

    private suspend fun interceptProvider(
        providerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: Sink,
    ): Boolean {
        val resolver = runCatching {
            WebViewResolver(
                interceptUrl = Regex(
                    """(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)"""
                ),
                additionalUrls = listOf(
                    Regex(
                        """(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)"""
                    )
                ),
                useOkhttp = false,
                timeout = 25_000L,
            )
        }.getOrNull() ?: return false

        val response = runCatching {
            app.get(
                providerUrl,
                headers = headers + mapOf("Referer" to referer),
                referer = referer,
                interceptor = resolver,
            )
        }.getOrNull() ?: return false

        collectSubtitles(response.document, subtitleCallback)

        val intercepted = response.url.orEmpty()
        if (!isMediaUrl(intercepted)) return false

        emitMedia(intercepted, providerUrl, sink.send)
        return true
    }

    private suspend fun emitMedia(
        url: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lower = url.lowercase()

        val type = when {
            lower.contains(".m3u8") || lower.contains("/hls") ->
                ExtractorLinkType.M3U8
            lower.contains(".mpd") ->
                ExtractorLinkType.DASH
            else ->
                ExtractorLinkType.VIDEO
        }

        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to sourcePage,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        originOf(sourcePage)?.let { mediaHeaders["Origin"] = it }

        callback(
            newExtractorLink(
                source = name,
                name = hostOf(url),
                url = url,
                type = type,
            ) {
                referer = sourcePage
                headers = mediaHeaders
                quality = qualityFromUrl(url)
            }
        )
    }

    /** HTML/JS metni içindeki doğrudan video adreslerini bulur. */
    private fun scanMedia(raw: String): List<String> {
        val text = raw.decodeEmbedded()
        val out = LinkedHashSet<String>()

        Regex(
            """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { out += it.value.trimEnd(')', ']', '}', ';', ',') }

        Regex(
            """(?<![:\w])//[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { out += "https:" + it.value.trimEnd(')', ']', '}', ';', ',') }

        return out.filter { !it.contains("/thumb", true) && !it.contains("preview", true) }
    }

    /** Sayfadaki oynatıcı adayları: iframe'ler önce, sonra data-* ve dış oynatıcı linkleri. */
    private fun embedCandidates(document: Document): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { el ->
            val raw = el.attr("src")
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-lazy-src") }
            normalizeUrl(raw)?.let(out::add)
        }

        document.select("video[src], video source[src], source[src]").forEach { el ->
            extractUrl(el)?.let(out::add)
        }

        document.select("[data-embed], [data-player], [data-url], [data-href], [data-src]")
            .filterNot { it.tagName() == "iframe" || it.tagName() == "img" }
            .forEach { el -> extractUrl(el)?.let(out::add) }

        document.select("a[href]").forEach { el ->
            val u = extractUrl(el) ?: return@forEach
            if (isMediaUrl(u) || (!isSameSite(u) && isPlayerUrl(u))) out += u
        }

        return out
    }

    /** "Alternatif kaynak" sekmelerinin açtığı sayfaları bulur. */
    private fun alternativePages(document: Document, pageUrl: String): List<String> {
        val stem = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('?')

        val strong = document
            .select("#alternatif, .alternatif, [id*=alternat], [class*=alternat]")
            .flatMap { it.select("a[href]") }

        val weak = document
            .select("[class*=kaynak], [id*=kaynak], [class*=woca]")
            .flatMap { it.select("a[href]") }
            .filter { stem.isNotBlank() && it.attr("href").contains(stem, ignoreCase = true) }

        return (strong + weak)
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .filter { href ->
                href != pageUrl &&
                    !href.contains("#") &&
                    !href.startsWith("javascript", ignoreCase = true) &&
                    isSameSite(href)
            }
            .distinct()
    }

    // =========================================================================
    //  Sayfa bilgileri (başlık, özet, puan, tür, oyuncu, fragman)
    // =========================================================================

    private suspend fun requestDocument(url: String): Document? {
        return runCatching {
            app.get(
                url,
                headers = headers,
                referer = "$mainUrl/",
            ).document
        }.getOrNull()
    }

    private fun pageTitle(document: Document): String? {
        return document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[name='description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .select("article p, .description, .summary")
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private data class PageMeta(
        val year: Int?,
        val rating: Double?,
        val genres: List<String>,
        val actors: List<String>,
        val trailer: String?,
    )

    private val yearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val pipeSplit = Regex("\\s*[|│•·]\\s*")

    /** "2019" ya da "2019 Yapımı" gibi kısa, yıl içeren parça. */
    private fun isYearPart(s: String) = s.length <= 14 && yearRegex.containsMatchIn(s)

    private fun pageMeta(document: Document): PageMeta {
        val ld = jsonLdNodes(document)
        val ldMain = ld.firstOrNull { node ->
            val type = node.opt("@type").toString()
            type.contains("TVSeries", true) || type.contains("Series", true) ||
                type.contains("Movie", true) || type.contains("CreativeWork", true)
        }

        // Başlığa en yakın bloklar (kenar çubuğundaki diğer dizileri karıştırmamak için)
        val scopes = infoScopes(document)
        val pipeParts = pipeLine(scopes)

        return PageMeta(
            year = pageYear(document, ldMain, pipeParts, scopes),
            rating = pageRating(document, ldMain, scopes),
            genres = pageGenres(ldMain, pipeParts, scopes),
            actors = pageActors(ldMain, pipeParts, scopes),
            trailer = pageTrailer(document, ldMain),
        )
    }

    /** h1'den yukarı doğru en fazla 5 kapsayıcı (en yakından en uzağa). */
    private fun infoScopes(document: Document): List<Element> {
        val out = ArrayList<Element>()
        var current: Element? = document.selectFirst("h1")?.parent()
        var guard = 0
        while (current != null && guard < 5 && current.tagName() != "body") {
            out.add(current)
            current = current.parent()
            guard++
        }
        return out
    }

    // ----------------------------------------------------------- JSON-LD

    private fun jsonLdNodes(document: Document): List<JSONObject> {
        val out = ArrayList<JSONObject>()

        fun walk(any: Any?) {
            when (any) {
                is JSONObject -> {
                    out += any
                    walk(any.opt("@graph"))
                }
                is JSONArray -> for (i in 0 until any.length()) walk(any.opt(i))
            }
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            runCatching { walk(JSONTokener(script.data().trim()).nextValue()) }
        }
        return out
    }

    private fun jsonNames(any: Any?): List<String> = when (any) {
        is String -> any.split(",").map { it.trim() }.filter { it.isNotBlank() }
        is JSONObject -> listOfNotNull(any.optString("name").trim().takeIf { it.isNotBlank() })
        is JSONArray -> (0 until any.length()).flatMap { jsonNames(any.opt(it)) }
        else -> emptyList()
    }

    // ------------------------------------------------------- metadata satırı

    /**
     * "Ülke | 2019 | Dram, Gerilim | Oyuncu, Oyuncu" biçimli satırı bulur.
     * Eski sürüm sadece ownText'e bakıyordu; metin alt etiketlere bölünmüşse
     * (ör. <span>2019</span>) satırı hiç bulamıyordu. Burada text() kullanılır.
     */
    private fun pipeLine(scopes: List<Element>): List<String> {
        for (scope in scopes) {
            val best = scope.getAllElements()
                .asSequence()
                .map { it.text().trim().replace(Regex("\\s+"), " ") }
                .filter { it.length in 8..500 && yearRegex.containsMatchIn(it) }
                .map { line -> line to line.split(pipeSplit).map { it.trim() } }
                .filter { (_, parts) -> parts.size >= 3 }
                .filter { (_, parts) -> parts.any { isYearPart(it) } }
                .minByOrNull { (line, _) -> line.length }
            if (best != null) return best.second
        }
        return emptyList()
    }

    /** "Tür: Dram, Aksiyon" gibi etiketli satırları okur. */
    private fun labelValue(scopes: List<Element>, labels: String): String? {
        val rx = Regex("^(?:$labels)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        for (scope in scopes) {
            for (el in scope.select("li, p, div, span, td, dd, tr")) {
                val t = el.text().trim()
                if (t.length > 300) continue
                val v = rx.find(t)?.groupValues?.get(1)?.trim()
                if (!v.isNullOrBlank()) return v
            }
        }
        return null
    }

    private fun splitList(value: String?): List<String> =
        value.orEmpty()
            .split(Regex("[,/]"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 60 }
            .distinct()

    // ------------------------------------------------------------------ yıl

    private fun pageYear(
        document: Document,
        ld: JSONObject?,
        pipe: List<String>,
        scopes: List<Element>,
    ): Int? {
        ld?.let {
            listOf("datePublished", "dateCreated", "startDate").forEach { key ->
                yearRegex.find(it.optString(key))?.value?.toIntOrNull()?.let { y -> return y }
            }
        }

        pipe.firstOrNull { isYearPart(it) }
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            ?.let { return it }

        labelValue(scopes, "Yıl|Yapım Yılı|Çıkış Yılı|Yayın Yılı")
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            ?.let { return it }

        return document.selectFirst("h1")?.text()
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
    }

    // ---------------------------------------------------------------- puan

    private fun numberIn(text: String): Double? =
        Regex("(\\d{1,2}(?:[.,]\\d{1,2})?)").findAll(text)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 1.0..10.0 }

    /**
     * IMDb puanı. Önce başlığa yakın bloklarda "IMDb" etiketli değer aranır
     * (eski sürüm tüm sayfadaki İLK "imdb" metnini alıyordu; o da genelde
     * kenar çubuğundaki başka bir dizinin puanı oluyordu).
     */
    private fun pageRating(document: Document, ld: JSONObject?, scopes: List<Element>): Double? {
        val labelled = Regex("(?i)imdb[^0-9]{0,15}(\\d{1,2}(?:[.,]\\d{1,2})?)")

        for (scope in scopes) {
            // class/id içinde "imdb" geçen öğeler
            scope.getAllElements()
                .filter { it.className().contains("imdb", true) || it.id().contains("imdb", true) }
                .forEach { el ->
                    numberIn(el.text())?.let { return it }
                }

            // "IMDb: 8.1" biçimli metin
            labelled.findAll(scope.text())
                .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .firstOrNull { it in 1.0..10.0 }
                ?.let { return it }
        }

        // schema.org
        document.selectFirst("[itemprop=ratingValue]")?.let { el ->
            numberIn(el.attr("content").ifBlank { el.text() })?.let { return it }
        }
        ld?.optJSONObject("aggregateRating")?.let { agg ->
            numberIn(agg.optString("ratingValue"))?.let { return it }
        }
        return null
    }

    // ----------------------------------------------------------------- tür

    private fun pageGenres(ld: JSONObject?, pipe: List<String>, scopes: List<Element>): List<String> {
        ld?.opt("genre")?.let { jsonNames(it) }?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(12) }

        splitList(labelValue(scopes, "Tür|Türü|Türler|Kategori|Kategoriler|Janr"))
            .takeIf { it.isNotEmpty() }
            ?.let { return it.take(12) }

        // "Ülke | Yıl | Türler | Oyuncular": yıldan sonraki ilk parça
        val yearIdx = pipe.indexOfFirst { isYearPart(it) }
        if (yearIdx >= 0) {
            splitList(pipe.getOrNull(yearIdx + 1)).takeIf { it.isNotEmpty() }
                ?.let { return it.take(12) }
        }

        for (scope in scopes) {
            val links = scope.select(
                "a[href*=/tur/], a[href*=/turler/], a[href*=/kategori/], a[href*=/category/], a[rel~=tag]"
            ).map { it.text().trim() }.filter { it.isNotBlank() && it.length <= 30 }.distinct()
            if (links.isNotEmpty()) return links.take(12)
        }
        return emptyList()
    }

    // -------------------------------------------------------------- oyuncu

    private fun pageActors(ld: JSONObject?, pipe: List<String>, scopes: List<Element>): List<String> {
        ld?.opt("actor")?.let { jsonNames(it) }?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(20) }

        splitList(labelValue(scopes, "Oyuncular|Oyuncu|Yıldızlar|Cast"))
            .takeIf { it.isNotEmpty() }
            ?.let { return it.take(20) }

        val yearIdx = pipe.indexOfFirst { isYearPart(it) }
        if (yearIdx >= 0 && pipe.size > yearIdx + 2) {
            splitList(pipe.drop(yearIdx + 2).joinToString(","))
                .takeIf { it.isNotEmpty() }
                ?.let { return it.take(20) }
        }

        for (scope in scopes) {
            val links = scope.select(
                "a[href*=/oyuncu/], a[href*=/oyuncular/], a[href*=/actor/], a[href*=/cast/]"
            ).map { it.text().trim() }.filter { it.isNotBlank() && it.length <= 40 }.distinct()
            if (links.isNotEmpty()) return links.take(20)
        }
        return emptyList()
    }

    // -------------------------------------------------------------- fragman

    private fun youtubeUrl(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isBlank()) return null
        val id = Regex("(?:v=|/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(v)?.groupValues?.get(1)
        if (id != null) return "https://www.youtube.com/watch?v=$id"
        return if (v.contains("youtube", true)) normalizeUrl(v) else null
    }

    private fun pageTrailer(document: Document, ld: JSONObject?): String? {
        ld?.optJSONObject("trailer")?.let { t ->
            listOf("embedUrl", "contentUrl", "url").forEach { key ->
                youtubeUrl(t.optString(key))?.let { return it }
            }
        }

        document.select("[data-trailer], [data-youtube], [data-yt], [data-fragman]").forEach { el ->
            listOf("data-trailer", "data-youtube", "data-yt", "data-fragman").forEach { key ->
                youtubeUrl(el.attr(key).decodeEmbedded())?.let { return it }
            }
        }

        document.select(
            "iframe[src*=youtube], iframe[data-src*=youtube], " +
                "a[href*=youtube.com/watch], a[href*=youtu.be], a[href*=youtube.com/embed]"
        ).forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }.ifBlank { el.attr("href") }
            youtubeUrl(raw.decodeEmbedded())?.let { return it }
        }

        return youtubeUrl(document.selectFirst("meta[property='og:video']")?.attr("content"))
    }

    // =========================================================================
    //  Bölümler
    // =========================================================================

    private fun parseEpisodes(document: Document): List<Episode> {
        val pattern = Regex(
            "(?i)(\\d+)\\.?\\s*Sezon\\s*(\\d+)\\.?\\s*Bölüm"
        )
        val showPoster = posterOf(document)

        return document
            .select("a[href]")
            .mapNotNull { element ->
                val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val text = element.text().trim()
                val match = pattern.find(text) ?: return@mapNotNull null

                val season = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    name = text
                    this.season = season
                    this.episode = episode
                    posterUrl = showPoster
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun isEpisodeAnchor(element: Element): Boolean {
        return Regex(
            "(?i)\\d+\\.?\\s*Sezon\\s*\\d+\\.?\\s*Bölüm"
        ).containsMatchIn(element.text())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val path = href.lowercase()

        if (!path.contains("/diziler/") || isEpisodeAnchor(this)) return null

        val title = text()
            .trim()
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        if (title.isBlank() || title.length > 120) return null

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries,
        ) {
            posterUrl = posterFromElement(this@toSearchResponse)
        }
    }

    // =========================================================================
    //  Afiş
    // =========================================================================

    private fun posterOf(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document
                .select("img")
                .asSequence()
                .mapNotNull { posterOfElement(it) }
                .firstOrNull()
    }

    private fun posterFromElement(element: Element): String? {
        element.selectFirst("img")?.let {
            posterOfElement(it)?.let { value -> return value }
        }

        var current: Element? = element.parent()

        repeat(4) {
            val parent = current ?: return@repeat

            parent.select("img").firstOrNull()?.let {
                posterOfElement(it)?.let { value -> return value }
            }

            current = parent.parent()
        }

        return null
    }

    private fun posterOfElement(element: Element): String? {
        val raw = listOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("src"),
            element.attr("data-srcset"),
            element.attr("srcset"),
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            ?: return null

        return raw
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
    }

    // =========================================================================
    //  URL yardımcıları
    // =========================================================================

    private fun normalizeUrl(raw: String?): String? {
        val value = raw?.trim()?.decodeEmbedded().orEmpty()
        if (value.isBlank() || value.startsWith("about:") || value.startsWith("javascript")) return null
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> fixUrl(value)
            else -> null
        }
    }

    private fun extractUrl(element: Element): String? {
        val attrs = listOf(
            "href",
            "src",
            "data-src",
            "data-url",
            "data-href",
            "data-embed",
            "data-player",
        )

        for (attrName in attrs) {
            val raw = element.attr(attrName).trim()
            if (raw.isBlank()) continue

            val value = raw.decodeEmbedded()

            Regex(
                "https?://[^\\s\\\"'<>]+",
                RegexOption.IGNORE_CASE
            )
                .find(value)
                ?.value
                ?.trimEnd(')', ']', '}', ';', ',')
                ?.let { return it }

            if (value.startsWith("//")) {
                return "https:$value"
            }

            if (value.startsWith("/")) {
                return fixUrl(value)
            }
        }

        return null
    }

    private fun isSameSite(url: String): Boolean {
        fun host(u: String) = runCatching { URI(u).host }.getOrNull()?.removePrefix("www.")
        val a = host(url) ?: return false
        return a.equals(host(mainUrl), ignoreCase = true)
    }

    private fun isBlocked(url: String): Boolean {
        val v = url.lowercase()
        return v.contains("youtube.com") || v.contains("youtu.be") ||
            v.contains("facebook.com") || v.contains("twitter.com") ||
            v.contains("instagram.com") || v.contains("t.me/") || v.contains("telegram")
    }

    private fun isStaticAsset(url: String): Boolean {
        return Regex(
            "(?i)\\.(?:jpe?g|png|gif|webp|svg|ico|css|js|woff2?|ttf)(?:$|[?#])"
        ).containsMatchIn(url)
    }

    private suspend fun collectSubtitles(
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        document.select("track[src], track[data-src]").forEach { track ->
            val src = track
                .attr("src")
                .ifBlank { track.attr("data-src") }
                .takeIf { it.isNotBlank() }
                ?: return@forEach

            subtitleCallback(
                newSubtitleFile(
                    track.attr("label").ifBlank { "Türkçe" },
                    fixUrl(src),
                )
            )
        }
    }

    private fun isPlayerUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vidmoly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki") ||
            value.contains("streamtape") ||
            value.contains("doodstream") ||
            value.contains("filemoon") ||
            value.contains("/embed") ||
            value.contains("player")
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains(".m3u8") ||
            value.contains(".mpd") ||
            Regex("(?i)\\.(?:mp4|webm)(?:$|[?#])").containsMatchIn(value) ||
            value.contains("/hls/")
    }

    private fun qualityFromUrl(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1440") -> Qualities.P1440.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun hostOf(url: String): String {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "DiziBOX"
    }

    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                return@runCatching null
            }
            uri.scheme + "://" + uri.host
        }.getOrNull()
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "${base.trimEnd('/')}/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val next = page + 1

        return document.select("a[href]").any { element ->
            val href = fixUrlNull(element.attr("href")).orEmpty()
            val label = element.text().trim().lowercase()

            label.contains("sonraki") ||
                label.contains("next") ||
                href.contains("/page/$next/")
        }
    }

    private fun episodeSeason(url: String): Int? {
        return Regex("(?i)(\\d+)[-_]?sezon")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun episodeNumber(url: String): Int? {
        return listOf(
            Regex("(?i)(?:bolum|bölüm)[-_]?(\\d+)"),
            Regex("(?i)(\\d+)[-_]?bolum"),
        )
            .asSequence()
            .mapNotNull { pattern ->
                pattern.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .firstOrNull()
    }

    /**
     * Önceki sürümde "\\\\/" yazılmıştı: bu, düz metindeki "\\/" (iki ters bölü)
     * dizisini arar, JSON'daki "\/" kaçışını DEĞİL. Burada tek ters bölü aranır.
     */
    private fun String.decodeEmbedded(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\\"", "\"")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
    }
}
