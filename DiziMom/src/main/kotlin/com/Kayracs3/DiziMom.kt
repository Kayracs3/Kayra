package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * DiziMom katalog sağlayıcısı.
 * İçerik detaylarında bulunan metadata ve sezon/bölüm kayıtlarını okur.
 * Video akışı çıkarımı yapmaz.
 */
class DiziMom : MainAPI() {
    override var mainUrl = "https://ddizimom.net"
    override var name = "DiziMom"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val vpnStatus = VPNStatus.None

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/takvim" to "Yayın Takvimi",
        "$mainUrl/tur/aile" to "Aile",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/aksiyon-macera" to "Aksiyon & Macera",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/anime" to "Anime",
        "$mainUrl/tur/belgesel" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "$mainUrl/tur/bilim-kurgu" to "Bilim-Kurgu",
        "$mainUrl/tur/biyografi" to "Biyografi",
        "$mainUrl/tur/cocuklar" to "Çocuklar",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerceklik" to "Gerçeklik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/haber" to "Haber",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/muzik" to "Müzik",
        "$mainUrl/tur/olmeden-once-izle" to "Ölmeden Önce İzle",
        "$mainUrl/tur/pembe-dizi" to "Pembe Dizi",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/savas" to "Savaş",
        "$mainUrl/tur/savas-politik" to "Savaş & Politik",
        "$mainUrl/tur/suc" to "Suç",
        "$mainUrl/tur/talk" to "Talk",
        "$mainUrl/tur/tarih" to "Tarih",
        "$mainUrl/tur/tv-film" to "TV Film",
        "$mainUrl/tur/vahsi-bati" to "Vahşi Batı",
    )

    private enum class Kind { MOVIE, SERIES, ANIME }

    private data class EpisodeNumber(val season: Int?, val episode: Int?)

    private fun norm(value: String): String =
        value.lowercase(Locale("tr"))
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
            .replace(Regex("""\s+"""), " ").trim()

    private fun absoluteUrl(raw: String, base: String = mainUrl): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/").trim('"', '\'')
        if (value.isBlank() || value.startsWith("javascript:", true) || value.startsWith("data:", true)) return ""
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }
            .getOrElse { mainUrl.trimEnd('/') + "/" + value.trimStart('/') }
    }

    private fun pathOf(url: String): String =
        runCatching { URI(absoluteUrl(url)).path.orEmpty() }.getOrDefault("")

    private fun kindOf(url: String): Kind? =
        when (pathOf(url).trim('/').substringBefore('/').lowercase()) {
            "film" -> Kind.MOVIE
            "dizi" -> Kind.SERIES
            "anime" -> Kind.ANIME
            else -> null
        }

    private fun isContentUrl(url: String): Boolean {
        val parts = pathOf(url).trim('/').split('/').filter { it.isNotBlank() }
        return parts.size == 2 && parts[0].lowercase() in setOf("film", "dizi", "anime")
    }

    private fun isEpisodeUrl(url: String): Boolean =
        Regex("""(?i)^/(?:dizi|anime)/[^/]+/(?:sezon-\d+|season-\d+)/(?:bolum-\d+|episode-\d+)/?$""")
            .containsMatchIn(pathOf(url))

    private fun parentSeriesUrl(url: String): String {
        val parts = pathOf(url).trim('/').split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2 && parts[0] in setOf("dizi", "anime")) {
            "$mainUrl/" + parts.take(2).joinToString("/")
        } else url
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        if (url.isBlank()) return null
        return runCatching {
            val response = app.get(
                url,
                headers = headers + mapOf("Referer" to referer),
                referer = referer,
                timeout = 20000,
                allowRedirects = true,
            )
            if (response.isSuccessful) response.document else null
        }.onFailure {
            Log.d(name, "Sayfa alınamadı: $url / " + it.message)
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = absoluteUrl(request.data)
        val firstDocument = getDocument(base)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val finalUrl = if (page <= 1) base else {
            findPageUrl(firstDocument, page) ?: makePageUrl(base, page)
        }
        val document = if (page <= 1) firstDocument else getDocument(finalUrl, base)
        if (document == null) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = parseCatalog(document)
        Log.d(name, request.name + ": " + results.size + " içerik, sayfa=" + page)
        return newHomePageResponse(request.name, results, hasNext = hasNextPage(document, page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()
        val encoded = URLEncoder.encode(value, "UTF-8")
        val candidates = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?query=$encoded",
            "$mainUrl/arama?search=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/ara?q=$encoded",
        )

        for (address in candidates) {
            val document = getDocument(address) ?: continue
            val results = parseCatalog(document)
                .filter { norm(it.name).contains(norm(value)) }
                .distinctBy { canonical(it.url) }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseCatalog(document: Document): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        document.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']").forEach { anchor ->
            val url = absoluteUrl(anchor.attr("href"), document.location())
            if (!isContentUrl(url)) return@forEach
            val key = canonical(url)
            if (key.isBlank() || found.containsKey(key)) return@forEach

            val card = findCard(anchor)
            val title = cardTitle(anchor, card, url)
            if (title.isBlank() || isGenericTitle(title)) return@forEach
            val poster = posterFromElement(card, url)
                ?: posterFromElement(anchor, url)
                ?: posterFromDocument(document, url)

            found[key] = when (kindOf(url)) {
                Kind.MOVIE -> newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                }
                Kind.ANIME -> newTvSeriesSearchResponse(title, url, TvType.Anime) {
                    posterUrl = poster
                }
                else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }
        }
        return found.values.toList()
    }

    private fun findCard(anchor: Element): Element =
        anchor.parents().firstOrNull { element ->
            val classes = element.classNames().joinToString(" ").lowercase()
            listOf("card", "item", "movie", "series", "content", "poster")
                .any { classes.contains(it) }
        } ?: anchor.parent() ?: anchor

    private fun cardTitle(anchor: Element, card: Element, url: String): String {
        val titleElement = card.selectFirst("h2, h3, h4, .card-title, .movie-title, .series-title, .title, [data-title]")
        val image = card.selectFirst("img[alt]") ?: anchor.selectFirst("img[alt]")
        val raw = titleElement?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: titleElement?.attr("data-title")?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: anchor.attr("aria-label").takeIf { it.isNotBlank() }
            ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: anchor.text().takeIf { it.isNotBlank() }
            ?: pathOf(url).substringAfterLast('/').replace('-', ' ')
        return cleanTitle(raw)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("""(?i)^\s*(?:dizi|film|anime)\s*"""), "")
        .replace(Regex("""(?i)\b(?:hd|full hd)\b"""), "")
        .replace(Regex("""^\s*\d+(?:[.,]\d+)?\s+"""), "")
        .replace(Regex("""\s+(?:19|20)\d{2}\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '·', '|', '•')

    private fun isGenericTitle(value: String): Boolean =
        norm(value) in setOf("dizi", "film", "anime", "detaylar", "izle", "fragmani izle", "tumunu gor")

    private fun posterFromElement(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val image = if (element.tagName() == "img") element else element.selectFirst("img")
        if (image == null) return null
        val raw = sequenceOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-lazy"),
            image.attr("src"),
            image.attr("srcset").substringBefore(',').substringBefore(' '),
        ).firstOrNull { it.isNotBlank() } ?: return null
        val result = absoluteUrl(raw, baseUrl)
        return result.takeIf { isImageUrl(it) }
    }

    private fun posterFromDocument(document: Document, baseUrl: String): String? {
        val image = document.selectFirst(
            ".poster img, .detail-poster img, .content-poster img, img.poster, img[alt]"
        )
        val fromElement = posterFromElement(image, baseUrl)
        if (fromElement != null) return fromElement
        val raw = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        val result = raw?.let { absoluteUrl(it, baseUrl) } ?: return null
        return result.takeIf { isImageUrl(it) }
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (url.isBlank() || lower.contains("placeholder") ||
            lower.contains("no-image") || lower.contains("default-avatar") ||
            lower.endsWith("/blank.png")
        ) return false
        return lower.startsWith("http") &&
            (lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".png") || lower.contains(".webp") || lower.contains("image"))
    }

    private fun pageTitle(document: Document, url: String): String {
        val heading = document.selectFirst("h1")?.text()?.trim()
        if (!heading.isNullOrBlank()) {
            return heading.replace(
                Regex("""\s*[—–-]\s*\d+\.\s*Sezon.*$""", RegexOption.IGNORE_CASE),
                "",
            ).trim()
        }
        val og = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        if (og.isNotBlank()) return cleanTitle(og.substringBefore("|").substringBefore(" izle"))
        return pathOf(url).trim('/').substringAfterLast('/').replace('-', ' ')
    }

    private fun pagePlot(document: Document): String? {
        val meta = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim().orEmpty()
        val body = document.selectFirst(
            ".overview, .synopsis, .plot, .description, .content-description, .movie-description, .series-description"
        )?.text()?.trim().orEmpty()
        val candidate = when {
            body.length > 30 -> body
            meta.length > 30 -> meta
            else -> document.select("main p, article p").firstOrNull { it.text().length > 40 }?.text().orEmpty()
        }
        return candidate.trim().takeIf { it.isNotBlank() }
    }

    private fun pageYear(document: Document, title: String): Int? {
        val explicit = Regex(
            """(?i)(?:Yapım Yılı|Yayın Yılı|Çıkış Yılı)\s*:?\s*(19\d{2}|20\d{2})"""
        ).find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        return explicit ?: Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""")
                .find(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? {
        val text = document.text()
        val match = Regex(
            """(?is)IMDB\s*Puan[ıi]\s*:?\s*(?:★\s*)?(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(text) ?: Regex(
            """(?i)IMDb\s*[: ]\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(text)
        return match?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }
    }

    private fun pageGenres(document: Document): List<String> {
        val linked = document.select("a[href*='/tur/']")
            .map { cleanTitle(it.text()) }
            .filter { it.isNotBlank() && it.length <= 40 && !isGenericTitle(it) }
            .distinct()
        if (linked.isNotEmpty()) return linked

        val raw = Regex(
            """(?is)Tür\s*:?\s*(.+?)(?:\s+Yapım Yılı|\s+IMDB|\s+Oyuncu Kadrosu|\s+Durum|$)"""
        ).find(document.text())?.groupValues?.getOrNull(1).orEmpty()
        return raw.split(',', '·').map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 40 }.distinct()
    }

    private fun pageActors(document: Document): List<String> {
        val names = LinkedHashSet<String>()
        document.select(
            "a[href*='/oyuncu/'], a[href*='/actor/'], a[href*='/person/'], " +
                ".cast a, .actors a, .celebrities a, [class*=cast] a, .actor-card a, .cast-list a"
        ).forEach {
            cleanActorName(it.text()).takeIf { value -> value.isNotBlank() }?.let(names::add)
        }

        val heading = document.select("h2, h3, h4").firstOrNull {
            norm(it.text()).contains("oyuncu kadrosu")
        }
        val section = heading?.parent()?.parent() ?: heading?.parent()
        section?.select("a[href]")?.forEach {
            cleanActorName(it.text()).takeIf { value -> value.isNotBlank() }?.let(names::add)
        }

        return names.take(50).toList()
    }

    private fun cleanActorName(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim(' ', '-', '·', '|', '•')
            .takeIf {
                val clean = norm(it)
                clean.isNotBlank() && clean !in setOf("tumunu gor", "oyuncu kadrosu", "giris yap", "detaylar")
            }.orEmpty()

    private fun pageTrailer(document: Document): String? {
        val candidates = mutableListOf<String>()
        document.select("iframe[src], iframe[data-src], a[href], [data-trailer], [data-video]")
            .forEach { element ->
                val raw = sequenceOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("href"),
                    element.attr("data-trailer"),
                    element.attr("data-video"),
                ).firstOrNull {
                    it.contains("youtube.com", true) || it.contains("youtu.be", true) ||
                        it.contains("vimeo.com", true)
                }
                if (!raw.isNullOrBlank()) candidates.add(absoluteUrl(raw, document.location()))
            }

        val html = document.html().replace("\\/", "/")
        Regex(
            """https?://(?:www\.)?(?:youtube\.com/(?:embed/|watch\?v=)|youtu\.be/)[A-Za-z0-9_-]+""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value?.let(candidates::add)

        return candidates.firstOrNull {
            it.contains("youtube.com", true) || it.contains("youtu.be", true) ||
                it.contains("vimeo.com", true)
        }
    }

    private fun pageStatus(document: Document): String? =
        Regex(
            """(?is)Durum\s*:?\s*(Devam Ediyor|Final Yaptı|Final Yapti|Yakında|İptal Edildi|Canceled|Ended)"""
        ).find(document.text())?.groupValues?.getOrNull(1)?.trim()

    private fun pagePlatform(document: Document): String? =
        Regex(
            """(?is)Platform\s*:?\s*([^\n]{1,50}?)(?:\s+Eklenme Tarihi|\s+Oyuncu Kadrosu|\s+Puanlama|$)"""
        ).find(document.text())?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && it.length < 60 }

    private fun pageRuntime(document: Document): String? =
        Regex("""(?i)Süre\s*:?\s*((?:\d+\s*sa\s*)?\d+\s*dk|\d+\s*dakika)""")
            .find(document.text())?.groupValues?.getOrNull(1)
            ?.replace(Regex("""\s+"""), " ")?.trim()

    private fun pageViewCount(document: Document): String? =
        Regex("""(?is)İzlenme Sayısı\s*:?\s*([\d.,]+)""")
            .find(document.text())?.groupValues?.getOrNull(1)

    private fun pageAddedDate(document: Document): String? =
        Regex("""(?is)Eklenme Tarihi\s*:?\s*(\d{2}\.\d{2}\.\d{4}|\d{2}/\d{2}/\d{4})""")
            .find(document.text())?.groupValues?.getOrNull(1)

    private fun buildPlot(
        plot: String?,
        runtime: String?,
        platform: String?,
        status: String?,
        views: String?,
        added: String?,
    ): String? {
        val extras = buildList {
            runtime?.let { add("Süre: " + it) }
            platform?.let { add("Platform: " + it) }
            status?.let { add("Durum: " + it) }
            views?.let { add("İzlenme sayısı: " + it) }
            added?.let { add("Eklenme tarihi: " + it) }
        }
        val base = plot.orEmpty().trim()
        if (extras.isEmpty()) return base.takeIf { it.isNotBlank() }
        return listOf(base, extras.joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun seasonNumber(url: String, label: String = ""): Int? {
        val source = url + " " + label
        val match = Regex(
            """(?i)[?&]sezon=(\d+)|/sezon-(\d+)|season[=/-](\d+)|(\d+)\s*\.?\s*sezon"""
        ).find(source) ?: return null
        return (1..4).asSequence()
            .mapNotNull { match.groups[it]?.value?.toIntOrNull() }
            .firstOrNull()
    }

    private fun episodeNumber(url: String, label: String = ""): EpisodeNumber {
        val source = url + " " + label
        val season = Regex("""(?i)(?:/sezon-|[?&]sezon=)(\d+)""").find(source)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(\d+)\s*\.?\s*sezon""").find(source)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = Regex("""(?i)/(?:bolum-|episode-)(\d+)""").find(source)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(\d+)\s*\.?\s*bölüm""").find(source)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return EpisodeNumber(season, episode)
    }

    private fun isEpisodeLink(url: String): Boolean = isEpisodeUrl(url)

    private fun seasonLinks(document: Document): List<Pair<String, Int>> {
        val links = LinkedHashMap<String, Pair<String, Int>>()
        document.select("a[href]").forEach { anchor ->
            val raw = anchor.attr("href").trim()
            if (raw.isBlank()) return@forEach
            val target = absoluteUrl(raw, document.location())
            if (target.isBlank() || isEpisodeLink(target)) return@forEach
            val label = anchor.text().ifBlank { anchor.attr("title") }
            val number = seasonNumber(target, label) ?: return@forEach
            val hasMarker = target.contains("sezon=", true) ||
                Regex("""(?i)/sezon-\d+""").containsMatchIn(pathOf(target)) ||
                norm(label).contains("sezon")
            if (!hasMarker || !target.startsWith(mainUrl, true)) return@forEach
            val key = target.substringBefore('#').lowercase()
            links.putIfAbsent(key, target to number)
        }
        return links.values.sortedBy { it.second }
    }

    private fun cleanEpisodeTitle(
        anchor: Element,
        seriesTitle: String,
        number: EpisodeNumber,
    ): String {
        val raw = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            anchor.selectFirst(".episode-title, .title, h3, h4")?.text().orEmpty(),
            anchor.text(),
            anchor.selectFirst("img[alt]")?.attr("alt").orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        var title = cleanTitle(raw)
        val escapedSeries = Regex.escape(seriesTitle)
        title = title.replace(
            Regex("""(?i)^$escapedSeries\s*[-–—:|·]*\s*"""),
            "",
        )
        title = title.replace(
            Regex("""(?i)\s*\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bölüm.*$"""),
            "",
        ).replace(
            Regex("""(?i)\s*\d+\s*\.?\s*bölüm.*$"""),
            "",
        ).trim(' ', '-', '·', '|', '•')

        return title.ifBlank {
            "Sezon " + (number.season ?: 1) + " Bölüm " + (number.episode ?: 1)
        }
    }

    private fun posterNearLink(anchor: Element, baseUrl: String): String? =
        posterFromElement(anchor, baseUrl) ?: posterFromElement(anchor.parent(), baseUrl)

    private suspend fun loadAllEpisodes(
        detail: Document,
        seriesUrl: String,
        fallbackPoster: String?,
    ): List<Episode> {
        val entries = LinkedHashMap<String, Episode>()

        fun addEpisodes(page: Document, baseUrl: String, fallbackSeason: Int?) {
            page.select("a[href]").forEach { anchor ->
                val target = absoluteUrl(anchor.attr("href"), baseUrl)
                if (!target.startsWith(mainUrl, true) || !isEpisodeLink(target)) return@forEach

                val rawNumber = episodeNumber(target, anchor.text())
                val number = EpisodeNumber(rawNumber.season ?: fallbackSeason, rawNumber.episode)
                val title = cleanEpisodeTitle(anchor, pageTitle(detail, seriesUrl), number)
                val poster = posterNearLink(anchor, baseUrl) ?: fallbackPoster
                val key = canonical(target)

                if (!entries.containsKey(key)) {
                    entries[key] = newEpisode(target) {
                        name = title
                        season = number.season
                        episode = number.episode
                        posterUrl = poster
                    }
                }
            }
        }

        addEpisodes(detail, seriesUrl, null)

        for ((seasonUrl, season) in seasonLinks(detail).take(60)) {
            val seasonDocument = getDocument(seasonUrl, seriesUrl) ?: continue
            addEpisodes(seasonDocument, seasonUrl, season)
        }

        return entries.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name.orEmpty() }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestedUrl = absoluteUrl(url)
        if (requestedUrl.isBlank()) return null
        val detailUrl = if (isEpisodeUrl(requestedUrl)) parentSeriesUrl(requestedUrl) else requestedUrl
        if (!isContentUrl(detailUrl)) return null

        val document = getDocument(detailUrl) ?: return null
        val title = pageTitle(document, detailUrl).ifBlank { return null }
        val poster = posterFromDocument(document, detailUrl)
        val genres = pageGenres(document)
        val actors = pageActors(document)
        val rating = pageRating(document)
        val plot = buildPlot(
            pagePlot(document),
            pageRuntime(document),
            pagePlatform(document),
            pageStatus(document),
            pageViewCount(document),
            pageAddedDate(document),
        )
        val year = pageYear(document, title)
        val trailer = pageTrailer(document)

        return when (kindOf(detailUrl)) {
            Kind.MOVIE -> newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
                posterUrl = poster
                posterHeaders = headers
                this.plot = plot
                this.year = year
                this.tags = genres
                rating?.let { score = Score.from10(it) }
                if (actors.isNotEmpty()) addActors(actors)
                addTrailer(trailer)
            }

            Kind.SERIES, Kind.ANIME -> {
                val type = if (kindOf(detailUrl) == Kind.ANIME) TvType.Anime else TvType.TvSeries
                val episodes = loadAllEpisodes(document, detailUrl, poster)
                Log.d(
                    name,
                    "Katalog: " + title + " / sezon=" +
                        episodes.mapNotNull { it.season }.distinct().size +
                        " / bölüm=" + episodes.size,
                )
                newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                    posterUrl = poster
                    posterHeaders = headers
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                    rating?.let { score = Score.from10(it) }
                    if (actors.isNotEmpty()) addActors(actors)
                    addTrailer(trailer)
                }
            }

            null -> null
        }
    }

    // Katalog sağlayıcısı: üçüncü taraf video akışlarını çözümlemez.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name, "Katalog modu: bu sağlayıcıda oynatma bağlantısı tanımlı değil.")
        return false
    }

    private fun canonical(url: String): String =
        absoluteUrl(url).substringBefore('#').substringBefore('?').trimEnd('/').lowercase()

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (page >= 100) return false
        if (document.select(
                "a[rel=next], .pagination a.next, a[aria-label*=next i], a:contains(Sonraki)"
            ).any { it.attr("href").isNotBlank() }
        ) return true

        return document.select(".pagination a, nav[aria-label*=pagination i] a, .page-numbers a")
            .any { it.text().trim().toIntOrNull() == page + 1 }
    }

    private fun findPageUrl(document: Document, page: Int): String? {
        val link = document.select("a[href]").firstOrNull { anchor ->
            anchor.text().trim() == page.toString() ||
                anchor.attr("aria-label").trim() == page.toString() ||
                anchor.attr("data-page").trim() == page.toString()
        } ?: return null
        return absoluteUrl(link.attr("href"), document.location()).takeIf { it.isNotBlank() }
    }

    private fun makePageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val separator = if (base.contains('?')) "&" else "?"
        return base + separator + "page=" + page
    }
}
