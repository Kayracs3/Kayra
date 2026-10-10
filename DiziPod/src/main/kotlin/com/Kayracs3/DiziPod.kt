package com.Kayracs3

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.Normalizer

private const val TAG = "DIZIPOD"

@CloudstreamPlugin
class DiziPodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPod())
        registerExtractorAPI(DiziPodPlayer())
    }
}

// =============================================================================
//  Ana sağlayıcı
// =============================================================================
class DiziPod : MainAPI() {
    override var mainUrl = "https://dizipod.com"
    override var name = "DiziPod"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/filmler/" to "Filmler",
        "$mainUrl/yapim/netflix/" to "Netflix",
        "$mainUrl/yapim/prime-video/" to "Prime Video",
        "$mainUrl/yapim/hbo/" to "HBO",
        "$mainUrl/yapim/national-geographic/" to "National Geographic",
        "$mainUrl/yapim/hulu/" to "Hulu"
    )

    // ------------------------------------------------------------- yardımcılar

    private fun headers(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to referer
    )

    /** JSON/HTML içinde kaçışlı gelen adresleri düzeltir. */
    private fun absolute(raw: String): String {
        val s = raw
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\", "")
            .trim()
            .trim('"', '\'')
            .replace("&amp;", "&")
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$mainUrl$s"
            else -> "$mainUrl/$s"
        }
    }

    private fun typeOf(url: String) = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

    private fun pageUrl(base: String, page: Int): String = when {
        page <= 1 -> base
        base.contains("/yapim/") -> "$base?pg=$page"
        else -> "${base.trimEnd('/')}/page/$page/"
    }

    private fun cleanTitle(doc: Document): String =
        doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank {
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Dizipod")?.trim().orEmpty()
        }

    private fun poster(doc: Document): String =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty().ifBlank {
            doc.selectFirst(".poster img, .post-thumbnail img, img.wp-post-image")
                ?.attr("src")?.trim().orEmpty()
        }

    private fun plot(doc: Document): String =
        doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty().ifBlank {
            doc.selectFirst(".description,.content,.summary,.plot")?.text()?.trim().orEmpty()
        }

    private fun norm(s: String): String {
        val t = s.replace('ı', 'i').replace('İ', 'i').lowercase()
        val noMarks = Regex("\\p{M}+").replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        return Regex("[^a-z0-9]+").replace(noMarks, " ").trim()
    }

    // --------------------------------------------------------------- kartlar

    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<SearchResponse>()

        for (a in doc.select("a[href*=/diziler/], a[href*=/film/]")) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            // /diziler/ ya da /filmler/ liste sayfalarının kendisini atla
            if (href.trimEnd('/').endsWith("/diziler") || href.trimEnd('/').endsWith("/filmler")) continue

            val url = absolute(href)
            if (!seen.add(url)) continue

            val card = a.closest("article, .item, .post, .movie, .series, .video-card, .card") ?: a
            val img = card.selectFirst("img") ?: a.selectFirst("img")

            val title = a.attr("title").trim()
                .ifBlank { card.selectFirst("h2,h3,h4,.title,.name")?.text()?.trim().orEmpty() }
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
                .ifBlank { a.text().trim() }
            if (title.isBlank()) continue

            val posterUrl = img?.let {
                listOf("data-original", "data-src", "data-lazy-src", "src")
                    .map { k -> it.attr(k).trim() }
                    .firstOrNull { v -> v.isNotBlank() }
            }?.let { absolute(it) }

            val year = Regex("(?:19|20)\\d{2}").find(card.text())?.value?.toIntOrNull()

            if (typeOf(url) == TvType.Movie) {
                out += newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            } else {
                out += newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------- ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.d(TAG, "MAIN $url page=$page")
        val doc = app.get(url, headers = headers()).document
        return newHomePageResponse(request.name, parseCards(doc), hasNext = true)
    }

    // ------------------------------------------------------------------ arama

    override suspend fun search(query: String): List<SearchResponse> {
        val rawQuery = query.trim()
        val q = norm(rawQuery)
        if (q.isBlank()) return emptyList()

        fun relevant(list: List<SearchResponse>): List<SearchResponse> =
            list.filter {
                val title = norm(it.name)
                title.isNotBlank() && (title.contains(q) || q.contains(title))
            }

        val enc = URLEncoder.encode(rawQuery, "UTF-8")
        val ajaxDocument = runCatching {
            app.post(
                "$mainUrl/wp/wp-admin/admin-ajax.php",
                headers = headers() + ("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf(
                    "action" to "dp_live_search",
                    "query" to rawQuery,
                    "search" to rawQuery,
                    "term" to rawQuery
                )
            ).document
        }.onFailure {
            Log.w(TAG, "Live search isteği başarısız", it)
        }.getOrNull()

        if (ajaxDocument != null) {
            val parsed = parseCards(ajaxDocument).distinctBy { it.url }
            val matched = relevant(parsed)
            if (matched.isNotEmpty()) {
                Log.d(TAG, "SEARCH ajax q=$rawQuery items=${matched.size}")
                return matched
            }
        }

        val urls = listOf(
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc",
            "$mainUrl/?q=$enc",
            "$mainUrl/search/?q=$enc",
            "$mainUrl/arama/?q=$enc",
            "$mainUrl/arama/$enc"
        ).distinct()

        for (url in urls) {
            val doc = runCatching {
                app.get(url, headers = headers(), referer = "$mainUrl/", allowRedirects = true).document
            }.onFailure {
                Log.w(TAG, "SEARCH GET failed: $url", it)
            }.getOrNull() ?: continue
            val scope = doc.selectFirst("main, #main, .search-results, .content-area, #content")
                ?.outerHtml()?.let { Jsoup.parse(it, mainUrl) } ?: doc
            val parsed = parseCards(scope).distinctBy { it.url }
            val matched = relevant(parsed)
            Log.d(TAG, "SEARCH url=$url parsed=${parsed.size} matched=${matched.size}")
            if (matched.isNotEmpty()) return matched
        }
        return emptyList()
    }

    // ------------------------------------------------------------------- load

    private fun episodes(doc: Document): List<Episode> {
        val rx = Regex("-(\\d+)-sezon-(\\d+)-bolum/?(?:$|[?#])", RegexOption.IGNORE_CASE)
        val list = ArrayList<Episode>()
        val seen = HashSet<Pair<Int, Int>>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val m = rx.find(href) ?: continue
            val season = m.groupValues[1].toIntOrNull() ?: continue
            val ep = m.groupValues[2].toIntOrNull() ?: continue
            if (!seen.add(season to ep)) continue

            val label = a.text().trim()
            list += newEpisode(absolute(href)) {
                this.name = label.ifBlank { "$ep. Bölüm" }
                this.season = season
                this.episode = ep
            }
        }
        return list.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers()).document
        val title = cleanTitle(doc)
        val poster = poster(doc)
        val plot = plot(doc)
        val year = Regex("(?:19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes(doc)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // -------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = 0
        val counting: (ExtractorLink) -> Unit = {
            found++
            callback(it)
        }

        val res = app.get(data, headers = headers())
        val html = res.text
        val doc = res.document

        // 1) sayfa kimliği (post id)
        val postId = doc.selectFirst("#episode-player-container[data-post-id], [data-post-id]")
            ?.attr("data-post-id")?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("postid-(\\d+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("[\"']post_id[\"']\\s*[:=]\\s*[\"']?(\\d+)").find(html)?.groupValues?.get(1)

        // 2) oynatıcı embed adreslerini topla
        val embeds = LinkedHashSet<String>()

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=$postId"
            val body = runCatching {
                app.get(ajaxUrl, headers = headers(data) + ("X-Requested-With" to "XMLHttpRequest")).text
            }.getOrNull().orEmpty()
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\n", "\n")

            Jsoup.parse(body, mainUrl).select("iframe[src],iframe[data-src]").forEach { f ->
                val src = f.attr("data-src").ifBlank { f.attr("src") }
                if (src.isNotBlank()) {
                    val abs = absolute(src)
                    if (abs.startsWith("http")) embeds += abs
                }
            }
            Regex("https?://player\\.dizipod\\.com/embed/[^\"'\\\\\\s<]+", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { embeds += it.value.replace("&amp;", "&") }
        }

        // sayfa HTML'inde doğrudan gömülü olabilir
        Regex("https?://player\\.dizipod\\.com/embed/[^\"'\\\\\\s<]+", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { embeds += it.value.replace("&amp;", "&") }

        Log.d(TAG, "EMBEDS post=$postId count=${embeds.size} list=$embeds")

        // 3) her embed'i extractor'a ver
        for (embed in embeds) {
            try {
                loadExtractor(embed, "$mainUrl/", subtitleCallback, counting)
            } catch (e: Exception) {
                Log.e(TAG, "EMBED_FAIL $embed: ${e.message}")
            }
        }
        return found > 0
    }
}

// =============================================================================
//  player.dizipod.com çıkarıcısı
// =============================================================================
class DiziPodPlayer : ExtractorApi() {
    override val name = "DiziPod Player"
    override val mainUrl = "https://player.dizipod.com"
    override val requiresReferer = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    private data class SourceItem(val url: String, val label: String)

    private fun clean(s: String) = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\x26", "&")
        .replace("&amp;", "&")

    private fun qualityFromLabel(label: String): Int {
        val n = Regex("(\\d{3,4})").find(label)?.value?.toIntOrNull()
        return when {
            n == null -> Qualities.Unknown.value
            n >= 2160 -> Qualities.P2160.value
            n >= 1440 -> Qualities.P1440.value
            n >= 1080 -> Qualities.P1080.value
            n >= 720 -> Qualities.P720.value
            n >= 480 -> Qualities.P480.value
            n >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------------------------------------------------------- kaynak toplama

    private fun collectSources(text: String): LinkedHashSet<SourceItem> {
        val out = LinkedHashSet<SourceItem>()
        val body = clean(text)

        // sources:[{file:"...",label:"720p"}]
        val sourcesRx = Regex(
            "(?:sources\\s*:\\s*|[\"']sources[\"']\\s*:\\s*)\\[\\s*\\{([\\s\\S]*?)\\}\\s*\\]",
            RegexOption.IGNORE_CASE
        )
        val fileRx = Regex("(?:[\"']?file[\"']?\\s*:\\s*[\"'])([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val labelRx = Regex("(?:[\"']?label[\"']?\\s*:\\s*[\"'])([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        sourcesRx.findAll(body).forEach { block ->
            val inner = block.groupValues[1]
            val file = fileRx.find(inner)?.groupValues?.get(1)?.replace("\\", "")
            val label = labelRx.find(inner)?.groupValues?.get(1) ?: "720p"
            if (file != null && file.contains(".m3u8")) out += SourceItem(file, label)
        }

        // { ... file:"https://....m3u8" ... }
        Regex(
            "\\{[^{}]*?(?:[\"']?file[\"']?\\s*:\\s*[\"'])(https?://[^\"']+?\\.m3u8[^\"']*)[\"'][^{}]*?\\}",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(body).forEach { m ->
            val u = m.groupValues[1].replace("\\", "")
            val label = labelRx.find(m.value)?.groupValues?.get(1) ?: "HLS"
            out += SourceItem(u, label)
        }

        // tyuopix CDN
        Regex(
            "https?://[a-zA-Z0-9.-]+\\.tyuopix\\.com[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { out += SourceItem(it.value, "HLS") }

        // genel .m3u8
        Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { out += SourceItem(it.value, "HLS") }

        return out
    }

    // ------------------------------------------------------------ altyazılar

    private fun langLabel(label: String, fallback: String): String {
        val l = label.trim().lowercase().ifBlank { fallback.trim().lowercase() }
        return when {
            Regex("(^|[^a-z])(tr|tur|turkce|türkçe|turkish)([^a-z]|$)").containsMatchIn(l) -> "Türkçe"
            Regex("(^|[^a-z])(en|eng|english|ingilizce)([^a-z]|$)").containsMatchIn(l) -> "English"
            l.isBlank() || l == "default" -> "Altyazı"
            else -> label.trim().ifBlank { "Altyazı" }
        }
    }

    private fun absUrl(base: String, raw: String): String {
        val s = raw.trim().trim('"', '\'')
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> base.trimEnd('/') + s
            else -> "${base.trimEnd('/')}/$s"
        }
    }

    private fun collectSubtitles(text: String, base: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>() // url -> label
        val body = clean(text).replace("\\", "")

        // tracks:[{file:"..vtt", label:"Türkçe", kind:"captions"}]
        Regex("[\"']?tracks[\"']?\\s*:\\s*\\[([\\s\\S]*?)\\]", RegexOption.IGNORE_CASE).findAll(body).forEach { tr ->
            Regex("\\{([^{}]*)\\}").findAll(tr.groupValues[1]).forEach { obj ->
                val o = obj.groupValues[1]
                val kind = Regex("[\"']?kind[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1).orEmpty()
                if (kind.equals("chapters", true) || kind.equals("thumbnails", true)) return@forEach
                val file = Regex("[\"']?(?:file|src)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1) ?: return@forEach
                if (file.contains(".jpg", true)) return@forEach
                val label = Regex("[\"']?label[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1).orEmpty()
                out.putIfAbsent(absUrl(base, file), langLabel(label, kind))
            }
        }

        // <track src=... kind=... label=...>
        Jsoup.parse(body, base).select("track[src], track[data-src]").forEach { t ->
            val kind = t.attr("kind")
            if (kind.equals("chapters", true) || kind.equals("thumbnails", true)) return@forEach
            val src = t.attr("src").ifBlank { t.attr("data-src") }
            out.putIfAbsent(absUrl(base, src), langLabel(t.attr("label"), t.attr("srclang")))
        }

        // subtitle: "https://..."
        Regex(
            "[\"']?(?:subtitles?|sub|captions?|altyazi)[\"']?\\s*[:=]\\s*[\"'](https?://[^\"']+|/[^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { out.putIfAbsent(absUrl(base, it.groupValues[1]), "Altyazı") }

        // çıplak .vtt/.srt/.ass adresleri
        Regex("https?://[^\\s\"'<>]+?\\.(?:vtt|srt|ass)(?:\\?[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { out.putIfAbsent(it.value, "Altyazı") }

        return out
    }

    // ------------------------------------------------- Dean Edwards p,a,c,k,e,d

    private fun baseN(num: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }

    private fun unpackDeanEdwards(text: String): String? {
        val rx = Regex(
            "eval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*[rd]\\s*\\)" +
                "\\s*\\{[\\s\\S]+?\\}\\s*\\(\\s*(['\"])([\\s\\S]*?)\\1\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*," +
                "\\s*(['\"])([\\s\\S]*?)\\5\\.split\\s*\\(\\s*['\"]\\|['\"]\\s*\\)",
            RegexOption.IGNORE_CASE
        )
        val m = rx.find(text) ?: return null

        fun unesc(s: String) = s
            .replace("\\x27", "'").replace("\\x22", "\"")
            .replace("\\\"", "\"").replace("\\'", "'")
            .replace("\\\\", "\\")

        var payload = unesc(m.groupValues[2])
        val radix = m.groupValues[3].toIntOrNull() ?: return null
        val count = m.groupValues[4].toIntOrNull() ?: return null
        val dict = unesc(m.groupValues[6]).split("|")

        for (i in count - 1 downTo 0) {
            val word = dict.getOrNull(i) ?: continue
            if (word.isBlank()) continue
            payload = Regex("\\b" + Regex.escape(baseN(i, radix)) + "\\b").replace(payload, word)
        }
        return payload
    }

    // ------------------------------------------------------------------ getUrl

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer?.takeIf { it.isNotBlank() } ?: "https://dizipod.com/"
        val pageHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to ref,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
        Log.d(TAG, "PLAYER GET $url")

        val res = app.get(url, headers = pageHeaders)
        val html = res.text
        Log.d(TAG, "PLAYER HTTP url=$url bytes=${html.length}")

        val sources = LinkedHashSet<SourceItem>()
        val subs = LinkedHashMap<String, String>()

        sources += collectSources(html)
        collectSubtitles(html, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }

        // packed JS
        if (sources.isEmpty()) {
            val unpacked = unpackDeanEdwards(html)
            if (unpacked != null) {
                Log.d(TAG, "PACKER UNPACK OK bytes=${unpacked.length}")
                sources += collectSources(unpacked)
                collectSubtitles(unpacked, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }
            } else {
                Log.d(TAG, "PACKER NOT FOUND")
            }
        }

        // harici script dosyaları
        if (sources.isEmpty()) {
            val doc = Jsoup.parse(html, url)
            for (s in doc.select("script[src]")) {
                val raw = s.attr("src")
                val abs = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> mainUrl.trimEnd('/') + "/" + raw.trimStart('/')
                }
                val js = runCatching {
                    app.get(abs, headers = mapOf("User-Agent" to ua, "Referer" to url, "Accept" to "*/*")).text
                }.getOrNull() ?: continue
                sources += collectSources(js)
                collectSubtitles(js, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }
                if (sources.isNotEmpty()) break
            }
        }

        Log.d(TAG, "PLAYER candidates=${sources.size} subtitles=${subs.size}")

        // altyazılar
        val usedLabels = HashMap<String, Int>()
        subs.forEach { (subUrl, label) ->
            val n = (usedLabels[label] ?: 0) + 1
            usedLabels[label] = n
            subtitleCallback(newSubtitleFile(if (n > 1) "$label $n" else label, subUrl))
        }

        if (sources.isEmpty()) {
            Log.d(TAG, "PLAYER NO_HLS head=${html.take(200).replace("\n", " ")}")
            return
        }

        val streamHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to url,
            "Origin" to mainUrl
        )

        for (src in sources) {
            val link = src.url.replace("\\", "")
            if (!link.startsWith("http") || !link.contains(".m3u8")) continue
            Log.d(TAG, "HLS CANDIDATE label=${src.label} url=$link")

            // gerçekten m3u8 mi?
            val ok = runCatching {
                val probe = app.get(link, headers = streamHeaders)
                val ct = probe.headers["content-type"].orEmpty()
                probe.text.trimStart().startsWith("#EXTM3U") ||
                    ct.contains("mpegurl", true) || ct.contains("m3u", true)
            }.getOrElse {
                Log.e(TAG, "HLS PROBE ERROR ${it.message}")
                false
            }
            if (!ok) {
                Log.d(TAG, "HLS REJECT url=$link")
                continue
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${src.label}".trim(),
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = qualityFromLabel(src.label)
                    this.headers = streamHeaders
                }
            )
        }
    }
}
