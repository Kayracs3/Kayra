package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DdiziTel : MainAPI() {
    override var mainUrl = "https://www.ddizi.tel"
    override var name = "DDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/yeni-eklenenler8" to "Yeni Eklenenler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler" to "Eski Diziler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun decode(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("\\/", "/")
        .replace("\\u002f", "/")
        .replace("\\x2f", "/")
        .replace("\\u0026", "&")
        .replace("\\x26", "&")
        .replace("\\u003d", "=")
        .replace("\\x3d", "=")
        .replace("\\u003a", ":")
        .replace("\\x3a", ":")

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value == "#" ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("blob:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
            val uri = URI(resolved)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
                resolved
            } else null
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.lowercase())

    private fun isEpisodeUrl(url: String): Boolean = pathOf(url).startsWith("/izle/")
    private fun isSeriesUrl(url: String): Boolean = pathOf(url).startsWith("/diziler/")
    private fun isContentUrl(url: String): Boolean = isEpisodeUrl(url) || isSeriesUrl(url)

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        // Bazı DDizi bağlantıları "Berlin izle 1.Sezon 1.Bölüm" biçiminde.
        // Sezon/bölüm bilgisinden önceki "izle" kelimesini silip numaraları koru.
        .replace(
            Regex("""(?i)\s+izle(?=\s+(?:s\d{1,2}\s*e\d{1,3}|\d{1,2}\s*\.?\s*(?:sezon|season)))"""),
            ""
        )
        .replace(Regex("(?i)^poster\\s*"), "")
        .replace(Regex("(?i)\\s+izle\\s*$"), "")
        .replace(Regex("(?i)\\s+izle\\s+.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', ':', '·')

    private fun cleanSeriesTitle(raw: String?): String {
        var title = cleanTitle(raw)
        title = title.replace(
            Regex("(?i)\\s+\\d{1,3}\\s*\\.?\\s*(?:bölüm|bolum|episode|ep)\\b.*$"), ""
        )
        title = title.replace(Regex("(?i)\\s+son\\s+bölüm\\s*$"), "")
        title = title.replace(
            Regex("(?i)\\s+\\d{1,2}\\s*\\.?\\s*(?:sezon|season)\\b.*$"), ""
        )
        title = title.replace(Regex("(?i)\\s+dizisi\\s*$"), "")
        return title.trim()
    }

    private fun normalizeTitleForMatch(raw: String): String =
        cleanSeriesTitle(raw).lowercase()
            .replace("ı", "i")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun titleFromUrl(url: String): String {
        val slug = pathOf(url).substringAfterLast('/').substringBeforeLast('.')
        return cleanTitle(slug.replace('-', ' ').replace('_', ' '))
    }

    private fun imageFrom(img: Element?): String? {
        if (img == null) return null
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-lazy",
            "data-image", "data-poster", "srcset", "data-srcset", "src"
        )
        for (attr in attrs) {
            val value = img.attr(attr).trim()
            if (value.isBlank() || value.startsWith("data:", true)) continue
            val candidate = if (attr.contains("srcset")) {
                value.substringBefore(",").substringBefore(" ").trim()
            } else value
            fixUrl(candidate)?.let { return it }
        }
        return null
    }

    private fun posterFrom(element: Element?): String? {
        var current = element
        repeat(4) {
            val img = current?.selectFirst("img")
            imageFrom(img)?.let { return it }
            current = current?.parent()
        }
        return null
    }

    private fun posterOf(document: Document, base: String): String? {
        document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrl(it, base) }?.let { return it }
        document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?.let { fixUrl(it, base) }?.let { return it }
        return imageFrom(document.selectFirst("main img, #content img, .content img, img"))
    }

    private fun cardTitle(link: Element, url: String): String {
        val img = link.selectFirst("img")
        val raw = link.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: link.selectFirst("h2,h3,h4,.title,.name")?.text()?.takeIf { it.isNotBlank() }
            ?: link.text().takeIf { it.isNotBlank() }
            ?: titleFromUrl(url)
        return cleanTitle(raw).ifBlank { titleFromUrl(url) }
    }

    private fun isSidebarOrMenuLink(link: Element): Boolean {
        var current: Element? = link
        repeat(9) {
            val element = current ?: return false
            val tag = element.tagName().lowercase()
            if (tag == "aside" || tag == "nav" || tag == "header" || tag == "footer") {
                return true
            }

            val marker = (element.id() + " " + element.className()).lowercase()
            if (Regex(
                    """(^|[\s_-])(sidebar|side-bar|side_bar|left-sidebar|leftsidebar|left-bar|leftbar|left-menu|leftmenu|sol-menu|solmenu|sol_menu|sidemenu|side-menu|menu|menu-item|navigation|navbar|nav|widget|header|footer)([\s_-]|$)"""
                ).containsMatchIn(marker)
            ) return true

            current = element.parent()
        }
        return false
    }

    private fun hasSeriesCardContext(link: Element): Boolean {
        // Seri listelerinde poster/kapak görseli veya kart kapsayıcısı aranır.
        // Bu işaretler olmayan yalın metin bağlantıları genellikle sol menü linkleridir.
        if (posterFrom(link) != null) return true

        var current: Element? = link
        repeat(5) {
            val element = current ?: return false
            val marker = (element.id() + " " + element.className()).lowercase()
            if (Regex(
                    """(?i)(film|dizi|series|poster|card|entry|archive|result|grid)"""
                ).containsMatchIn(marker)
            ) return true
            current = element.parent()
        }
        return false
    }

    private fun parseCards(document: Document, mode: String? = null): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in document.select("a[href]")) {
            // Site menüsündeki dizi bağlantılarını katalog sonucu olarak ekleme.
            if (isSidebarOrMenuLink(link)) continue

            val url = fixUrl(link.attr("href")) ?: continue
            if (!isContentUrl(url)) continue
            if (mode == "episodes" && !isEpisodeUrl(url)) continue
            if (mode == "series" && !isSeriesUrl(url)) continue
            if (mode == "series" && !hasSeriesCardContext(link)) continue

            val title = cardTitle(link, url)
            if (title.length < 2 || title.length > 180) continue
            if (title.equals("izle", true) || title.equals("dizi izle", true)) continue

            val poster = posterFrom(link)
            val response = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
            found.putIfAbsent(url.trimEnd('/'), response)
        }
        return found.values.toList()
    }

    private fun findPageUrl(document: Document, page: Int): String? {
        val link = document.select("a[href]").firstOrNull {
            it.text().trim() == page.toString() &&
                fixUrl(it.attr("href"))?.let { url -> !isContentUrl(url) } == true
        } ?: return null
        return fixUrl(link.attr("href"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data.trimEnd('/')
        val first = runCatching {
            app.get(request.data, headers = requestHeaders, referer = mainUrl)
        }.getOrNull() ?: return null
        if (!first.isSuccessful) return null

        var document = first.document
        if (page > 1) {
            val nextUrl = findPageUrl(document, page)
                ?: if (base == "$mainUrl/yeni-eklenenler8") {
                    return newHomePageResponse(request.name, emptyList(), hasNext = false)
                } else {
                    "$base/page/$page/"
                }
            val next = runCatching {
                app.get(nextUrl, headers = requestHeaders, referer = request.data)
            }.getOrNull()
            if (next == null || !next.isSuccessful) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            document = next.document
        }

        val listingMode = when {
            base.contains("/yabanci-dizi-izle", true) ||
                base.contains("/eski.diziler", true) -> "series"
            else -> "episodes"
        }
        val items = parseCards(document, listingMode)
        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty() && findPageUrl(document, page + 1) != null
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val term = query.trim()
        if (term.isBlank()) return newSearchResponseList(emptyList(), false)

        val home = runCatching {
            app.get(mainUrl, headers = requestHeaders)
        }.getOrNull()
        val form = home?.document?.select("form")?.firstOrNull { candidate ->
            candidate.select("input").any {
                val type = it.attr("type").lowercase()
                type == "text" || type == "search" || it.attr("name").isNotBlank()
            }
        }

        val searchUrl = if (form != null) {
            val field = form.select("input[name]").firstOrNull {
                val type = it.attr("type").lowercase()
                type == "text" || type == "search" || type.isBlank()
            }?.attr("name").orEmpty().ifBlank { "s" }
            val action = fixUrl(form.attr("action"), mainUrl) ?: mainUrl
            val separator = if (action.contains("?")) "&" else "?"
            "$action$separator" + URLEncoder.encode(field, "UTF-8") + "=" +
                URLEncoder.encode(term, "UTF-8")
        } else {
            "$mainUrl/?s=" + URLEncoder.encode(term, "UTF-8")
        }

        val response = runCatching {
            app.get(searchUrl, headers = requestHeaders, referer = mainUrl)
        }.getOrNull() ?: return newSearchResponseList(emptyList(), false)
        if (!response.isSuccessful) return newSearchResponseList(emptyList(), false)
        val items = parseCards(response.document)
        return newSearchResponseList(items, false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1).items

    private fun episodeNumber(title: String, url: String): Int? {
        val combined = title + " " + url
        Regex("""(?i)\bs\d{1,2}\s*e(\d{1,3})\b""")
            .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode|ep)\b""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val slug = pathOf(url)
        Regex("""(?i)(\d{1,3})-(?:son-)?bolum(?:-|\.|/|$)""")
            .find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(?:son-|^)(\d{1,3})-bolum""")
            .find(slug.substringAfterLast('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun seasonNumber(title: String, url: String): Int {
        val combined = title + " " + url
        return Regex("""(?i)\bs(\d{1,2})\s*e\d{1,3}\b""")
            .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(\d{1,2})\s*\.?\s*(?:sezon|season)""")
                .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(?:sezon|season)[-_/ ]*(\d{1,2})""")
                .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 1
    }

    private fun isPartLink(title: String): Boolean =
        Regex("""(?i)\b(?:\d+\s*\.?\s*)?(?:parça|parca|part)\b""").containsMatchIn(title)

    private fun parseEpisodes(
        document: Document,
        baseUrl: String,
        expectedSeriesTitle: String? = null
    ): List<Episode> {
        val expected = expectedSeriesTitle?.takeIf { it.isNotBlank() }
            ?.let(::normalizeTitleForMatch).orEmpty()
        val episodes = LinkedHashMap<String, Episode>()
        for (link in document.select("a[href]")) {
            val url = fixUrl(link.attr("href"), baseUrl) ?: continue
            if (!isEpisodeUrl(url)) continue
            val rawTitle = link.attr("title").ifBlank { link.text() }
                .ifBlank { titleFromUrl(url) }
            val title = cleanTitle(rawTitle)
            if (title.isBlank() || isPartLink(title)) continue

            // Dizi sayfasının yan sütunundaki güncel bölümler farklı dizilere ait
            // olabiliyor. Beklenen dizi adıyla uyuşmayanları sezon listesine katma.
            if (expected.isNotBlank()) {
                val candidateSeries = normalizeTitleForMatch(title)
                if (candidateSeries != expected &&
                    !candidateSeries.startsWith("$expected ") &&
                    !expected.startsWith("$candidateSeries ")
                ) continue
            }

            val number = episodeNumber(title, url) ?: continue
            if (number < 1) continue
            val season = seasonNumber(title, url)
            episodes.putIfAbsent(url.trimEnd('/'), newEpisode(url) {
                name = title
                this.season = season
                episode = number
            })
        }
        return episodes.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
    }

    // DDizi bir dizinin eski bölümlerini /sayfa-1, /sayfa-2 ... adreslerine
    // bölüyor. Yalnızca ilk HTML sayfasını okumak, dizinin bölümlerinin çoğunu kaybettirir.
    private fun paginationRoot(url: String): String =
        pathOf(url).replace(Regex("""/sayfa-\d+/?$"""), "").trimEnd('/')

    private fun isSeriesPagination(url: String, seriesUrl: String): Boolean {
        val urlUri = runCatching { URI(url) }.getOrNull() ?: return false
        val baseUri = runCatching { URI(seriesUrl) }.getOrNull() ?: return false
        if (!urlUri.host.equals(baseUri.host, ignoreCase = true)) return false
        if (!Regex("""/sayfa-\d+/?$""").containsMatchIn(pathOf(url))) return false
        return paginationRoot(url) == paginationRoot(seriesUrl)
    }

    private suspend fun collectPaginatedEpisodes(
        firstDocument: Document,
        seriesUrl: String,
        expectedSeriesTitle: String
    ): List<Episode> {
        val queue = mutableListOf(seriesUrl)
        val queued = linkedSetOf(seriesUrl.trimEnd('/'))
        val documents = LinkedHashMap<String, Document>()
        var index = 0

        // Her sayfadaki sayfalama bağlantılarını takip et. Üst sınır, bozuk
        // sayfa bağlantılarının aşırı istek veya döngü oluşturmasını engeller.
        while (index < queue.size && documents.size < 40) {
            val pageUrl = queue[index++]
            val normalizedPageUrl = pageUrl.trimEnd('/')
            if (documents.containsKey(normalizedPageUrl)) continue

            val document = if (normalizedPageUrl == seriesUrl.trimEnd('/')) {
                firstDocument
            } else {
                getDocument(pageUrl) ?: continue
            }
            documents[normalizedPageUrl] = document

            for (link in document.select("a[href]")) {
                val nextUrl = fixUrl(link.attr("href"), pageUrl) ?: continue
                if (!isSeriesPagination(nextUrl, seriesUrl)) continue
                val key = nextUrl.trimEnd('/')
                if (queued.add(key) && queue.size < 40) queue.add(nextUrl)
            }
        }

        val allEpisodes = LinkedHashMap<String, Episode>()
        for (document in documents.values) {
            parseEpisodes(document, seriesUrl, expectedSeriesTitle).forEach { episode ->
                val episodeUrl = episode.data.trimEnd('/')
                allEpisodes.putIfAbsent(episodeUrl, episode)
            }
        }

        val result = allEpisodes.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
        Log.d("DDizi", "series pages=" + documents.size + ", episodes=" + result.size + ", series=" + seriesUrl)
        return result
    }

    private fun metadataTitle(document: Document, url: String): String {
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()
            ?: titleFromUrl(url)
        return cleanSeriesTitle(title).ifBlank { titleFromUrl(url) }
    }

    private fun plotFrom(document: Document): String? {
        document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val summary = document.selectFirst(
            ".ozet, .özet, #ozet, #ozet, .summary, .description, .aciklama, .video-info, .entry-content"
        )?.text()?.trim()
        if (!summary.isNullOrBlank()) return summary
        return document.select("p").map { it.text().trim() }
            .firstOrNull { it.length > 70 && !it.contains("yorum", true) }
    }

    private fun parseTags(document: Document): List<String> =
        document.select(
            ".kategori a, .category a, .categories a, .genres a, .genre a, " +
                "a[href*='/kategori/'], a[href*='/tur/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 60 }
            .distinct()
            .take(25)

    private suspend fun getDocument(url: String): Document? {
        val response = runCatching {
            app.get(url, headers = requestHeaders + ("Referer" to mainUrl), referer = mainUrl)
        }.getOrNull() ?: return null
        return if (response.isSuccessful) response.document else null
    }

    private fun seriesPageLink(document: Document): String? {
        val explicit = document.select("a[href*='/diziler/']").firstOrNull { link ->
            val text = link.text().lowercase()
            text.contains("dizi sayfas") || text.contains("dizi sayfasina") ||
                text.contains("dizinin sayfas")
        }
        if (explicit != null) return fixUrl(explicit.attr("href"))

        // Menüdeki dizi bağlantılarını yanlışlıkla seçmemek için sayfa başlığına
        // en çok benzeyen dizi bağlantısını tercih et.
        val h1 = cleanTitle(document.selectFirst("h1")?.text()).lowercase()
        return document.select("a[href*='/diziler/']").firstOrNull { link ->
            val label = cleanSeriesTitle(link.text()).lowercase()
            label.length > 2 && h1.contains(label)
        }?.let { fixUrl(it.attr("href")) }
    }

    private fun applyMetadata(response: LoadResponse, document: Document) {
        parseTags(document).takeIf { it.isNotEmpty() }?.let { response.tags = it }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = requestHeaders + ("Referer" to mainUrl), referer = mainUrl)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null

        val document = response.document
        val isEpisodePage = isEpisodeUrl(url)
        val pageTitle = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).ifBlank { titleFromUrl(url) }

        var seriesUrl = if (isSeriesUrl(url)) url else seriesPageLink(document)
        var seriesDocument: Document? = if (isSeriesUrl(url)) document else null

        if (isEpisodePage && seriesUrl != null && seriesUrl != url) {
            seriesDocument = getDocument(seriesUrl)
        }

        val metadataDocument = seriesDocument ?: document
        val title = when {
            isSeriesUrl(url) -> metadataTitle(document, url)
            seriesDocument != null -> metadataTitle(seriesDocument!!, seriesUrl ?: url)
            else -> cleanSeriesTitle(pageTitle).ifBlank { pageTitle }
        }

        val poster = posterOf(metadataDocument, seriesUrl ?: url)
            ?: posterOf(document, url)
        val plot = plotFrom(metadataDocument) ?: plotFrom(document)
        val episodes = (if (seriesUrl != null) {
            collectPaginatedEpisodes(metadataDocument, seriesUrl, title)
                .ifEmpty { parseEpisodes(document, url, title) }
        } else {
            parseEpisodes(metadataDocument, url, title)
        }).ifEmpty {
            if (isEpisodePage) {
                val n = episodeNumber(pageTitle, url) ?: 1
                listOf(newEpisode(url) {
                    name = pageTitle
                    season = seasonNumber(pageTitle, url)
                    episode = n
                })
            } else emptyList()
        }

        if (isEpisodePage || isSeriesUrl(url) || episodes.isNotEmpty()) {
            val loadUrl = if (isEpisodePage) seriesUrl ?: url else url
            return newTvSeriesLoadResponse(title, loadUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                applyMetadata(this, metadataDocument)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
            posterUrl = poster
            this.plot = plot
            applyMetadata(this, document)
        }
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4|m4v|webm|mpd)(?:[?#].*)?$""").containsMatchIn(url)

    private fun isScriptEndpoint(url: String): Boolean {
        val path = pathOf(url)
        val fileName = path.substringAfterLast('/')
        return path.endsWith(".js") ||
            (path.endsWith(".php") && fileName.startsWith("scripts"))
    }

    private fun addCandidate(output: MutableSet<String>, raw: String?, base: String) {
        val candidate = fixUrl(raw, base) ?: return
        if (candidate.length > 2500) return
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        if (host.contains("google-analytics") || host.contains("doubleclick") ||
            host.contains("googlesyndication") || host.contains("facebook.com") ||
            host.contains("api.whatsapp.com")
        ) return
        if (Regex("""(?i)\.(?:css|js|png|jpe?g|gif|webp|svg|woff2?|ttf|ico)(?:[?#].*)?$""")
                .containsMatchIn(candidate)) return
        if (candidate.trimEnd('/') == base.trimEnd('/')) return
        if (output.size < 40) output.add(candidate)
    }

    private fun collectPlayerUrls(document: Document, html: String, base: String): LinkedHashSet<String> {
        val output = LinkedHashSet<String>()
        val attrs = listOf("src", "data-src", "data-url", "data-embed", "data-iframe", "data-video")
        for (element in document.select(
            "iframe, video, source, embed, [data-src], [data-url], [data-embed], [data-iframe], [data-video]"
        )) {
            for (attr in attrs) {
                if (element.hasAttr(attr)) addCandidate(output, element.attr(attr), base)
            }
        }

        val decodedHtml = decode(html)
        val directPattern = Regex(
            """(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|m4v|webm|mpd)(?:\?[^"'<>\\\s]*)?"""
        )
        directPattern.findAll(decodedHtml).forEach { addCandidate(output, it.value, base) }

        val attrPattern = Regex(
            """(?i)(?:src|file|url|iframe|embed|video|source)\s*["']?\s*[:=]\s*["']([^"'<>]{5,1500})["']"""
        )
        attrPattern.findAll(decodedHtml).forEach { addCandidate(output, it.groupValues[1], base) }

        for (link in document.select("a[href]")) {
            val label = link.text().trim()
            val href = link.attr("href")
            if (Regex("""(?i)(parça|parca|\bpart\b|oynatıcı|oynatici|server|kaynak|\bvideo\b|\bizle\b)""")
                    .containsMatchIn(label) ||
                href.contains("/player/oynat/", true)
            ) {
                addCandidate(output, href, base)
            }
        }
        return output
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = runCatching {
            app.get(data, headers = requestHeaders + ("Referer" to mainUrl), referer = mainUrl)
        }.onFailure {
            Log.w("DDizi", "loadLinks request failed: $data", it)
        }.getOrNull() ?: return false
        if (!pageResponse.isSuccessful) return false

        val visited = HashSet<String>()
        val emitted = HashSet<String>()
        var found = false

        suspend fun resolve(candidate: String, referer: String, depth: Int) {
            if (depth > 3 || !visited.add(candidate)) return
            if (isDirectMedia(candidate)) {
                if (emitted.add(candidate)) {
                    val type = when {
                        candidate.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        candidate.contains(".mpd", true) -> INFER_TYPE
                        else -> ExtractorLinkType.VIDEO
                    }
                    callback(newExtractorLink(source = name, name = "DDizi", url = candidate, type = type) {
                        this.referer = referer
                        this.headers = requestHeaders + ("Referer" to referer)
                        this.quality = Regex("""(?i)(2160|1440|1080|720|480|360)""")
                            .find(candidate)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
                    })
                    found = true
                    return
                }
            }

            if (isScriptEndpoint(candidate)) {
                val scriptResponse = runCatching {
                    app.get(
                        candidate,
                        headers = requestHeaders + ("Referer" to referer),
                        referer = referer
                    )
                }.getOrNull() ?: return
                if (!scriptResponse.isSuccessful) return
                val scriptCandidates = collectPlayerUrls(
                    scriptResponse.document, scriptResponse.text, candidate
                )
                Log.d("DDizi", "script endpoint candidates=" + scriptCandidates.size)
                for (next in scriptCandidates) resolve(next, candidate, depth + 1)
                return
            }

            val linksBefore = emitted.size
            runCatching {
                loadExtractor(candidate, referer, subtitleCallback) { link ->
                    if (emitted.add(link.url)) {
                        callback(link)
                        found = true
                    }
                }
            }.onFailure {
                Log.d("DDizi", "extractor did not resolve candidate=$candidate", it)
            }
            if (emitted.size > linksBefore) return

            val nestedResponse = runCatching {
                app.get(
                    candidate,
                    headers = requestHeaders + ("Referer" to referer),
                    referer = referer
                )
            }.getOrNull() ?: return
            if (!nestedResponse.isSuccessful) return

            val nestedCandidates = collectPlayerUrls(
                nestedResponse.document, nestedResponse.text, candidate
            )
            Log.d("DDizi", "nested candidates=" + nestedCandidates.size + " at " + candidate)
            for (next in nestedCandidates) resolve(next, candidate, depth + 1)
        }

        val candidates = collectPlayerUrls(pageResponse.document, pageResponse.text, data)
        Log.d("DDizi", "loadLinks candidates=" + candidates.size + " for " + data)
        for (candidate in candidates) resolve(candidate, data, 0)
        if (!found) Log.w("DDizi", "No playable source found for " + data)
        return found
    }
}
