package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*

class SinezyTo : MainAPI() {
    override var mainUrl = "https://sinezy.si"
    override var name = "Sinezy"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/izle/en-yeni-filmler/" to "Filmler",
        "$mainUrl/izle/bilim-kurgu-filmleri/" to "Bilimkurgu"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val value = url.trim()
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    /** Lazy-load placeholder'larını (data:image, boş, gif) eler. */
    private fun realImage(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isBlank() || v.startsWith("data:", true)) return null
        return fixUrl(v)
    }

    /** Kartın afişini bulur: lazy-load öznitelikleri src'den ÖNCE denenir. */
    private fun posterOf(link: org.jsoup.nodes.Element): String? {
        val img = link.selectFirst("img")
            ?: link.parent()?.selectFirst("img")
            ?: link.parent()?.parent()?.selectFirst("img")
            ?: return null

        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-lazy", "data-img", "src"
        )
        for (a in attrs) realImage(img.attr(a))?.let { return it }

        // srcset / data-srcset -> ilk adres
        for (a in listOf("data-srcset", "srcset")) {
            val first = img.attr(a).split(",").firstOrNull()?.trim()?.substringBefore(" ")
            realImage(first)?.let { return it }
        }
        return null
    }

    /** "Poster 6.5 Saplantı" gibi bozuk link metni yerine temiz başlık üretir. */
    private fun titleOf(link: org.jsoup.nodes.Element): String {
        val fromTitle = link.attr("title").trim().removeSuffix(" izle").removeSuffix(" İzle").trim()
        if (fromTitle.isNotBlank()) return fromTitle
        val fromAlt = link.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            .removeSuffix(" izle").trim()
        if (fromAlt.isNotBlank()) return fromAlt
        return link.text().trim()
            .removePrefix("Poster").trim()
            .replace(Regex("^\\d+(\\.\\d+)?\\s+"), "")
            .trim()
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("a[href]").mapNotNull { link ->
            val href = fixUrl(link.attr("href")) ?: return@mapNotNull null
            val isDizi = href.contains("/dizi/", true)
            val looksLikeCard = link.attr("title").trim().endsWith("izle", true) && link.selectFirst("img") != null
            if (!href.contains("/film/", true) && !isDizi && !looksLikeCard) return@mapNotNull null

            val title = titleOf(link)
            if (title.isBlank()) return@mapNotNull null
            val poster = posterOf(link)

            if (isDizi) {
                newTvSeriesSearchResponse(title, href) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href) { posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // site sayfalaması: /page/N/
        val pageUrl = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val response = app.get(pageUrl, headers = headers, referer = mainUrl)
        if (!response.isSuccessful) return null

        val items = parseCards(response.document)

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val response = app.get(url, headers = headers, referer = mainUrl)

        if (!response.isSuccessful) {
            return newSearchResponseList(emptyList(), false)
        }

        val items = parseCards(response.document)

        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = headers, referer = mainUrl)
        if (!response.isSuccessful) return null

        val document = response.document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let(::fixUrl)
        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".description")?.text()?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()

        if (url.contains("/film/", true)) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            emptyList()
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers, referer = mainUrl)
        if (!response.isSuccessful) return false

        val document = response.document
        val candidates = LinkedHashSet<String>()

        document.select("iframe[src], video[src], source[src], [data-src]").forEach { element ->
            val value = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-url") }

            if (value.isNotBlank()) {
                val fixed = fixUrl(value) ?: return@forEach
                candidates.add(fixed)
            }
        }

        for (candidate in candidates) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = candidate,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data
                    )
                }
            )
        }

        return candidates.isNotEmpty()
    }
}
