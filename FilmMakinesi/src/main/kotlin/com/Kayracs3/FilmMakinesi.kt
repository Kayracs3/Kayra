package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class FilmMakinesi : MainAPI() {

    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler-1/" to "Son Filmler",
        "$mainUrl/yabanci-dizi-izle-1/" to "Son Diziler",
        "$mainUrl/kesfet/" to "Keşfet",

        "$mainUrl/tur/aksiyon-fm1/film/" to "Aksiyon Filmleri",
        "$mainUrl/tur/aile-fm2/film/" to "Aile Filmleri",
        "$mainUrl/tur/animasyon-fm2/film/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel/film/" to "Belgesel Filmleri",
        "$mainUrl/tur/biyografi/film/" to "Biyografi Filmleri",
        "$mainUrl/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/dram-fm1/film/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-fm1/film/" to "Fantastik Filmleri",
        "$mainUrl/tur/gerilim-fm1/film/" to "Gerilim Filmleri",
        "$mainUrl/tur/gizem/film/" to "Gizem Filmleri",
        "$mainUrl/tur/komedi-fm1/film/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-fm2/film/" to "Korku Filmleri",
        "$mainUrl/tur/macera-fm1/film/" to "Macera Filmleri",
        "$mainUrl/tur/muzik/film/" to "Müzik Filmleri",
        "$mainUrl/tur/polisiye/film/" to "Polisiye Filmleri",
        "$mainUrl/tur/romantik-fm1/film/" to "Romantik Filmleri",
        "$mainUrl/tur/savas-fm1/film/" to "Savaş Filmleri",
        "$mainUrl/tur/spor/film/" to "Spor Filmleri",
        "$mainUrl/tur/tarih-fm1/film/" to "Tarih Filmleri",
        "$mainUrl/tur/western-fm1/film/" to "Western Filmleri",

        "$mainUrl/tur/aksiyon-fm1/dizi/" to "Aksiyon Dizileri",
        "$mainUrl/tur/animasyon-fm7/dizi/" to "Animasyon Dizileri",
        "$mainUrl/tur/bilim-kurgu-fm3/dizi/" to "Bilim Kurgu Dizileri",
        "$mainUrl/tur/dram-fm1/dizi/" to "Dram Dizileri",
        "$mainUrl/tur/korku-fm2/dizi/" to "Korku Dizileri",
        "$mainUrl/tur/macera-fm1/dizi/" to "Macera Dizileri",
        "$mainUrl/tur/polisiye/dizi/" to "Polisiye / Suç Dizileri",
        "$mainUrl/tur/romantik-fm1/dizi/" to "Romantik Dizileri",

        "$mainUrl/yil/2026-fmfbkb/film/" to "2026 Filmleri",
        "$mainUrl/yil/2026-fmfbkb/dizi/" to "2026 Dizileri",
        "$mainUrl/yil/2025-fm4/film/" to "2025 Filmleri",
        "$mainUrl/yil/2025-fm4/dizi/" to "2025 Dizileri",

        "$mainUrl/ulke/turkiye-fm4/" to "Yerli İçerikler",
        "$mainUrl/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "$mainUrl/seri-filmler-izle-1/" to "Seri Filmler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val baseUrl = request.data.ifBlank {
            "$mainUrl/filmler-1/"
        }

        val url = pageUrl(baseUrl, page)

        val document = runCatching {
            app.get(
                url,
                headers = requestHeaders,
            ).document
        }.getOrElse {
            Log.e(
                "FILMMAKINESI",
                "Liste açılamadı: $url",
                it,
            )

            return newHomePageResponse(
                request.name,
                emptyList(),
                false,
            )
        }

        var results = parseListPage(document)

        if (
            results.isEmpty() &&
            page > 1
        ) {
            val alternate = alternatePageUrl(
                baseUrl,
                page,
            )

            if (
                alternate != null &&
                alternate != url
            ) {
                results = runCatching {
                    app.get(
                        alternate,
                        headers = requestHeaders,
                    ).document
                }
                    .getOrNull()
                    ?.let(::parseListPage)
                    .orEmpty()
            }
        }

        val hasNext =
            hasNextPage(
                document,
                page,
            ) ||
                (
                    results.isNotEmpty() &&
                        results.size >= 15
                    )

        Log.d(
            "FILMMAKINESI",
            "${request.name} / sayfa $page -> ${results.size} sonuç",
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNext,
        )
    }

    private fun pageUrl(
        baseUrl: String,
        page: Int,
    ): String {
        if (page <= 1) {
            return baseUrl
        }

        return "${baseUrl.trimEnd('/')}/sayfa/$page/"
    }

    private fun alternatePageUrl(
        baseUrl: String,
        page: Int,
    ): String? {
        val value = baseUrl.trimEnd('/')

        if (
            value.matches(
                Regex(
                    ".*/(?:filmler|yabanci-dizi-izle)-\\d+$"
                )
            )
        ) {
            return value.replace(
                Regex("-(\\d+)$"),
                "-$page",
            ) + "/"
        }

        return null
    }

    private fun parseListPage(
        document: Document,
    ): List<SearchResponse> {
        return document
            .select("a[href]")
            .mapNotNull { anchor ->

                val href = fixUrlNull(
                    firstNonBlank(
                        anchor.attr("href"),
                        anchor.attr("data-href"),
                        anchor.attr("data-url"),
                    )
                ) ?: return@mapNotNull null

                if (
                    !isContentDetailUrl(href)
                ) {
                    return@mapNotNull null
                }

                val card = findCard(anchor)
                val image = findPosterImage(
                    anchor,
                    card,
                )

                val cardText = (
                    anchor.text() + " " +
                        card?.text().orEmpty()
                    )
                    .replace(
                        Regex("\\s+"),
                        " ",
                    )
                    .trim()

                val title = firstNonBlank(
                    anchor.attr("title"),
                    image?.attr("alt"),
                    card?.selectFirst(
                        "h2,h3,h4,.title,.film-title,.dizi-title"
                    )?.text(),
                    cleanCardTitle(anchor.text()),
                    slugToTitle(href),
                ) ?: return@mapNotNull null

                val durationMinutes =
                    extractDurationMinutes(
                        cardText
                    )

                if (
                    isTrailerCandidate(
                        href,
                        title,
                        cardText,
                        durationMinutes,
                    )
                ) {
                    return@mapNotNull null
                }

                val score =
                    extractRating(cardText)

                if (
                    href.contains(
                        "/dizi/",
                        ignoreCase = true,
                    )
                ) {
                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries,
                    ) {
                        posterUrl =
                            image?.let(
                                ::posterUrlOf
                            )

                        score?.let {
                            this.score =
                                Score.from10(it)
                        }
                    }
                } else {
                    newMovieSearchResponse(
                        title,
                        href,
                        TvType.Movie,
                    ) {
                        posterUrl =
                            image?.let(
                                ::posterUrlOf
                            )

                        score?.let {
                            this.score =
                                Score.from10(it)
                        }
                    }
                }
            }
            .distinctBy {
                it.url
            }
    }

    private fun findCard(
        anchor: Element,
    ): Element? {
        if (
            anchor.selectFirst("img") != null
        ) {
            return anchor
        }

        var current: Element? = anchor

        repeat(5) {
            current = current?.parent()

            val parent =
                current ?: return null

            if (
                parent.selectFirst("img") != null &&
                parent.selectFirst("a[href]") != null
            ) {
                return parent
            }
        }

        return null
    }

    private fun findPosterImage(
        anchor: Element,
        card: Element?,
    ): Element? {
        anchor.selectFirst("img")
            ?.let {
                return it
            }

        card?.select("img")
            ?.firstOrNull { image ->
                val src = firstNonBlank(
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("src"),
                ).orEmpty()

                !isBadImage(src)
            }
            ?.let {
                return it
            }

        return null
    }

    private fun posterUrlOf(
        image: Element,
    ): String? {
        val src = firstNonBlank(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src"),
        ) ?: return null

        return fixUrlNull(src)
    }

    private fun isBadImage(
        url: String,
    ): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("data:") ||
            value.contains("logo") ||
            value.contains("avatar") ||
            value.contains("placeholder") ||
            value.contains("default")
    }

    override suspend fun search(
        query: String,
    ): List<SearchResponse> {
        val q = query.trim()

        if (q.length < 2) {
            return emptyList()
        }

        val encoded = URLEncoder.encode(
            q,
            "UTF-8",
        )

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
        )

        for (url in urls) {
            val results = runCatching {
                app.get(
                    url,
                    headers = requestHeaders,
                ).document
            }
                .getOrNull()
                ?.let(::parseListPage)
                .orEmpty()

            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    override suspend fun quickSearch(
        query: String,
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(
        url: String,
    ): LoadResponse? {
        val normalized = fixUrl(url)

        val document = runCatching {
            app.get(
                normalized,
                headers = requestHeaders,
            ).document
        }.getOrNull() ?: return null

        val pageText = document.text()
            .replace(
                Regex("\\s+"),
                " ",
            )
            .trim()

        val isSeries =
            normalized.contains(
                "/dizi/",
                ignoreCase = true,
            )

        val title = firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst(
                "meta[property='og:title']"
            )?.attr("content"),
            document.title(),
        )
            ?.cleanDetailTitle()
            ?: return null

        val poster = firstNonBlank(
            document.selectFirst(
                "meta[property='og:image']"
            )?.attr("content"),
            document.selectFirst(
                "meta[name='twitter:image']"
            )?.attr("content"),
            document.select("img[alt]")
                .firstOrNull {
                    it.attr("alt").contains(
                        title,
                        ignoreCase = true,
                    )
                }
                ?.let {
                    posterUrlOf(it)
                },
            document.selectFirst("img")
                ?.let {
                    posterUrlOf(it)
                },
        )?.let(::fixUrlNull)

        val originalTitle =
            document.selectFirst("h2,h3")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals(
                            title,
                            true,
                        )
                }

        val description = firstNonBlank(
            document.selectFirst(
                "meta[name='description']"
            )?.attr("content"),
            document.selectFirst(
                ".description"
            )?.text(),
            document.selectFirst(
                ".aciklama"
            )?.text(),
            document.selectFirst(
                ".plot"
            )?.text(),
        )?.trim()

        val year =
            extractYear(pageText)

        val score =
            extractRating(pageText)

        val genres =
            extractGenres(document)

        val actors =
            extractActors(document)

        val trailer =
            findTrailer(document)

        if (isSeries) {
            val episodes =
                extractEpisodes(
                    document,
                    poster,
                )

            return newTvSeriesLoadResponse(
                title,
                normalized,
                TvType.TvSeries,
                episodes,
            ) {
                posterUrl = poster

                plot = buildPlot(
                    originalTitle,
                    description,
                )

                this.year = year
                this.tags = genres

                score?.let {
                    this.score =
                        Score.from10(it)
                }

                addActors(actors)

                trailer?.let {
                    addTrailer(it)
                }
            }
        }

        return newMovieLoadResponse(
            title,
            normalized,
            TvType.Movie,
            normalized,
        ) {
            posterUrl = poster

            plot = buildPlot(
                originalTitle,
                description,
            )

            this.year = year
            this.tags = genres

            score?.let {
                this.score =
                    Score.from10(it)
            }

            addActors(actors)

            trailer?.let {
                addTrailer(it)
            }
        }
    }

    private fun buildPlot(
        originalTitle: String?,
        description: String?,
    ): String? {
        return if (
            !originalTitle.isNullOrBlank() &&
            !description.isNullOrBlank()
        ) {
            "$originalTitle\n\n$description"
        } else {
            originalTitle ?: description
        }
    }

    private fun extractGenres(
        document: Document,
    ): List<String> {
        val known = setOf(
            "Aksiyon",
            "Aile",
            "Animasyon",
            "Belgesel",
            "Biyografi",
            "Bilim Kurgu",
            "Dram",
            "Fantastik",
            "Gerilim",
            "Gizem",
            "Komedi",
            "Korku",
            "Macera",
            "Müzik",
            "Polisiye",
            "Romantik",
            "Savaş",
            "Spor",
            "Tarih",
            "Western",
        )

        return document.select("a[href]")
            .map {
                it.text().trim()
            }
            .filter {
                it in known
            }
            .distinct()
    }

    private fun extractActors(
        document: Document,
    ): List<Actor>? {
        val linked =
            document.select(
                "a[href*='/oyuncu/'], a[href*='/oyuncular/']"
            )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinctBy {
                    it.lowercase()
                }
                .map {
                    Actor(it)
                }

        if (
            linked.isNotEmpty()
        ) {
            return linked
        }

        val heading =
            document.select(
                "h2,h3,h4,strong,span,div"
            )
                .firstOrNull {
                    it.text().trim()
                        .equals(
                            "Oyuncular",
                            ignoreCase = true,
                        )
                }
                ?: return null

        val parentText =
            heading.parent()
                ?.text()
                .orEmpty()

        if (
            parentText.isBlank()
        ) {
            return null
        }

        return parentText
            .substringAfter(
                "Oyuncular",
                "",
            )
            .split(
                ",",
                "•",
                "|",
            )
            .map {
                it.trim()
            }
            .filter {
                it.length in 2..60
            }
            .filterNot {
                it.contains(
                    "Tüm Kadroyu",
                    true,
                )
            }
            .distinctBy {
                it.lowercase()
            }
            .map {
                Actor(it)
            }
            .takeIf {
                it.isNotEmpty()
            }
    }

    private fun extractEpisodes(
        document: Document,
        poster: String?,
    ): List<Episode> {
        val result =
            LinkedHashMap<String, Episode>()

        document
            .select(
                "a[href], a[data-href], a[data-url]"
            )
            .forEach { anchor ->

                val href =
                    fixUrlNull(
                        firstNonBlank(
                            anchor.attr("href"),
                            anchor.attr("data-href"),
                            anchor.attr("data-url"),
                        )
                    ) ?: return@forEach

                val text =
                    listOf(
                        anchor.text(),
                        anchor.attr("title"),
                        anchor.attr("aria-label"),
                        anchor.parent()
                            ?.text()
                            .orEmpty(),
                    ).joinToString(" ")
                        .replace(
                            Regex("\\s+"),
                            " ",
                        )
                        .trim()

                val season =
                    extractSeason(
                        text,
                        href,
                    ) ?: return@forEach

                val episode =
                    extractEpisodeNumber(
                        text,
                        href,
                    ) ?: return@forEach

                if (
                    !text.contains(
                        "Bölüm",
                        true,
                    ) &&
                    !text.contains(
                        "Episode",
                        true,
                    ) &&
                    !href.contains(
                        "bolum",
                        true,
                    ) &&
                    !href.contains(
                        "episode",
                        true,
                    )
                ) {
                    return@forEach
                }

                if (
                    !href.startsWith("$mainUrl/")
                ) {
                    return@forEach
                }

                if (
                    isContentDetailUrl(href) &&
                    !href.contains(
                        "bolum",
                        true,
                    ) &&
                    !href.contains(
                        "episode",
                        true,
                    )
                ) {
                    return@forEach
                }

                val episodeName =
                    cleanEpisodeName(
                        text,
                        season,
                        episode,
                    )

                result[
                    href.trimEnd('/')
                ] = newEpisode(href) {
                    name = episodeName
                    this.season = season
                    this.episode = episode
                    posterUrl = poster
                }
            }

        return result.values.sortedWith(
            compareBy<Episode> {
                it.season ?: Int.MAX_VALUE
            }.thenBy {
                it.episode ?: Int.MAX_VALUE
            }
        )
    }

    private fun extractSeason(
        text: String,
        url: String,
    ): Int? {
        val patterns = listOf(
            Regex(
                "(\\d+)\\s*[.]?\\s*Sezon",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "sezon[- ]?(\\d+)",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "/(?:s|season)[-_]?(\\d+)",
                RegexOption.IGNORE_CASE,
            ),
        )

        for (pattern in patterns) {
            pattern.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    return it
                }

            pattern.find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    return it
                }
        }

        return null
    }

    private fun extractEpisodeNumber(
        text: String,
        url: String,
    ): Int? {
        val patterns = listOf(
            Regex(
                "(\\d+)\\s*[.]?\\s*Bölüm",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "Episode\\s*(\\d+)",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "bolum[- ]?(\\d+)",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "/(?:e|episode)[-_]?(\\d+)",
                RegexOption.IGNORE_CASE,
            ),
        )

        for (pattern in patterns) {
            pattern.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    return it
                }

            pattern.find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    return it
                }
        }

        return null
    }

    private fun cleanEpisodeName(
        text: String,
        season: Int,
        episode: Int,
    ): String {
        val cleaned =
            text
                .replace(
                    Regex(
                        "\\b(?:Yabancı|Yerli)\\s+Dizi\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .replace(
                    Regex(
                        "\\b(?:İzle|Izle)\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .replace(
                    Regex("\\s+"),
                    " ",
                )
                .trim(
                    ' ',
                    '-',
                    '|',
                    ':',
                )

        return if (
            cleaned.length > 2
        ) {
            cleaned.take(180)
        } else {
            "$season. Sezon $episode. Bölüm"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        Log.d(
            "FILMMAKINESI",
            "loadLinks -> $data",
        )

        val response =
            runCatching {
                app.get(
                    data,
                    headers = requestHeaders,
                )
            }.getOrNull()
                ?: return false

        val document =
            response.document

        val html =
            normalizeText(response.text)

        val candidates =
            LinkedHashMap<String, String>()

        fun addCandidate(
            raw: String?,
            label: String,
        ) {
            val value =
                raw
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: return

            val fixed =
                runCatching {
                    fixUrlNull(value)
                }.getOrNull()
                    ?: return

            if (
                fixed.isBlank() ||
                sameUrl(fixed, data)
            ) {
                return
            }

            if (
                isTrailerCandidateUrl(fixed)
            ) {
                return
            }

            if (
                !isUsefulPlayerUrl(fixed)
            ) {
                return
            }

            candidates[
                fixed.substringBefore('#')
            ] = label
        }

        /*
         * Tüm iframe'ler.
         */
        document
            .select(
                "iframe, embed"
            )
            .forEach { element ->

                if (
                    isTrailerElement(element)
                ) {
                    return@forEach
                }

                val marker =
                    (
                        element.attr("title") + " " +
                            element.attr("aria-label") + " " +
                            element.attr("id") + " " +
                            element.attr("name") + " " +
                            element.classNames()
                                .joinToString(" ")
                        )
                        .lowercase()

                val label =
                    when {
                        "rapid" in marker ->
                            "FilmMakinesi • Rapid"

                        "dublaj" in marker ->
                            "FilmMakinesi • Dublaj"

                        "altyaz" in marker ->
                            "FilmMakinesi • Altyazı"

                        else ->
                            "FilmMakinesi • Player"
                    }

                listOf(
                    "src",
                    "data-src",
                    "data-url",
                    "data-iframe",
                    "data-player",
                    "data-embed",
                    "data-video",
                    "data-video-url",
                    "data-embed-url",
                ).forEach { attr ->

                    val raw =
                        element.attr(attr)

                    if (
                        raw.isBlank()
                    ) {
                        return@forEach
                    }

                    val decoded =
                        normalizeText(raw)

                    Regex(
                        "https?://[^\\s\\\"'<>]+",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(decoded)
                        .forEach { match ->
                            addCandidate(
                                match.value.trimEnd(
                                    ')',
                                    ']',
                                    '}',
                                    ';',
                                    ',',
                                ),
                                label,
                            )
                        }

                    if (
                        decoded.startsWith("/")
                    ) {
                        addCandidate(
                            decoded,
                            label,
                        )
                    }
                }
            }

        /*
         * Scriptlerde player/embed URL'leri.
         */
        document
            .select("script")
            .forEach { element ->

                val script =
                    element.data()
                        .ifBlank {
                            element.html()
                        }

                if (
                    script.isBlank()
                ) {
                    return@forEach
                }

                val decoded =
                    normalizeText(script)

                Regex(
                    "https?://[^\\s\\\"'<>]+",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(decoded)
                    .forEach { match ->
                        addCandidate(
                            match.value.trimEnd(
                                ')',
                                ']',
                                '}',
                                ';',
                                ',',
                            ),
                            "FilmMakinesi • Script Player",
                        )
                    }

                val unpacked =
                    runCatching {
                        getAndUnpack(decoded)
                    }.getOrNull()

                if (
                    !unpacked.isNullOrBlank() &&
                    unpacked != decoded
                ) {
                    Regex(
                        "https?://[^\\s\\\"'<>]+",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(unpacked)
                        .forEach { match ->
                            addCandidate(
                                match.value.trimEnd(
                                    ')',
                                    ']',
                                    '}',
                                    ';',
                                    ',',
                                ),
                                "FilmMakinesi • Unpacked Player",
                            )
                        }
                }
            }

        /*
         * Data-* / onclick player bağlantıları.
         */
        document
            .select(
                "a[href], button, [onclick], " +
                    "[data-src], [data-url], [data-link], " +
                    "[data-href], [data-iframe], [data-embed], " +
                    "[data-player], [data-video], [data-file], [data-play]"
            )
            .forEach { element ->

                val marker =
                    (
                        element.text() + " " +
                            element.attr("title") + " " +
                            element.attr("aria-label") + " " +
                            element.id() + " " +
                            element.classNames()
                                .joinToString(" ") +
                            " " +
                            element.attr("data-provider")
                        )
                        .lowercase()

                val playerLike =
                    "player" in marker ||
                        "rapid" in marker ||
                        "dublaj" in marker ||
                        "altyaz" in marker ||
                        "stream" in marker ||
                        "flm" in marker ||
                        "izle" in marker

                if (
                    !playerLike &&
                    element.attr("onclick").isBlank()
                ) {
                    return@forEach
                }

                val label =
                    when {
                        "rapid" in marker ->
                            "FilmMakinesi • Rapid"

                        "dublaj" in marker ->
                            "FilmMakinesi • Dublaj"

                        "altyaz" in marker ->
                            "FilmMakinesi • Altyazı"

                        else ->
                            "FilmMakinesi • Player"
                    }

                listOf(
                    "href",
                    "src",
                    "data-src",
                    "data-url",
                    "data-link",
                    "data-href",
                    "data-video",
                    "data-iframe",
                    "data-embed",
                    "data-player",
                    "data-player-src",
                    "data-embed-url",
                    "data-video-url",
                    "data-file",
                    "data-play",
                    "onclick",
                ).forEach { attr ->

                    val raw =
                        element.attr(attr)

                    if (
                        raw.isBlank()
                    ) {
                        return@forEach
                    }

                    val decoded =
                        normalizeText(raw)

                    Regex(
                        "https?://[^\\s\\\"'<>]+",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(decoded)
                        .forEach { match ->

                            addCandidate(
                                match.value.trimEnd(
                                    ')',
                                    ']',
                                    '}',
                                    ';',
                                    ',',
                                ),
                                label,
                            )
                        }

                    Regex(
                        """(?:^|[=:"'`()\s])((?:/)+(?:player|embed|watch|stream|video|source|play|load)[^\s"'<>]*)""",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(decoded)
                        .forEach { match ->
                            addCandidate(
                                match.groupValues[1],
                                label,
                            )
                        }
                }
            }

        /*
         * Doğrudan m3u8/mp4.
         */
        extractDirectMedia(
            html
        ).forEach {
            addCandidate(
                it,
                "FilmMakinesi • Direct Media",
            )
        }

        Log.d(
            "FILMMAKINESI",
            "iframe sayısı = ${document.select("iframe").size}",
        )

        Log.d(
            "FILMMAKINESI",
            "script sayısı = ${document.select("script").size}",
        )

        Log.d(
            "FILMMAKINESI",
            "player aday sayısı = ${candidates.size}",
        )

        if (
            candidates.isEmpty()
        ) {
            Log.w(
                "FILMMAKINESI",
                "Player adayı bulunamadı: $data",
            )
            return false
        }

        Log.d(
            "FILMMAKINESI",
            "Player adayları = " +
                candidates.entries.joinToString(
                    " | "
                ) {
                    "${it.value}: ${it.key}"
                },
        )

        /*
         * CloseLoad.
         */
        val closeLoadCandidates =
            candidates.entries.filter {
                it.key.contains(
                    "closeload.filmmakinesi",
                    true,
                )
            }

        for (
            (playerUrl, _) in closeLoadCandidates
        ) {
            var emitted =
                false

            Log.d(
                "FILMMAKINESI",
                "CloseLoad deneniyor: $playerUrl",
            )

            runCatching {
                CloseLoadExtractor()
                    .getUrl(
                        url = playerUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = {
                            emitted = true
                            callback(it)
                        },
                    )
            }.onFailure {
                Log.e(
                    "FILMMAKINESI",
                    "CloseLoad hatası: $playerUrl",
                    it,
                )
            }

            if (emitted) {
                return true
            }
        }

        /*
         * Diğer extractor'lar.
         */
        for (
            (playerUrl, playerLabel) in candidates
        ) {
            if (
                playerUrl.contains(
                    "closeload.filmmakinesi",
                    true,
                )
            ) {
                continue
            }

            Log.d(
                "FILMMAKINESI",
                "Extractor deneniyor: $playerUrl",
            )

            val loaded =
                runCatching {
                    loadExtractor(
                        playerUrl,
                        data,
                        subtitleCallback,
                        callback,
                    )
                }.getOrDefault(false)

            if (loaded) {
                return true
            }

            /*
             * Player sayfasını aç.
             */
            val playerResponse =
                runCatching {
                    app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to data,
                            "Accept-Language" to
                                "tr-TR,tr;q=0.9,en;q=0.8",
                        ),
                    )
                }.getOrNull()
                    ?: continue

            val playerDocument =
                playerResponse.document

            val playerHtml =
                normalizeText(
                    playerResponse.text
                )

            /*
             * İç iframe.
             */
            playerDocument
                .select(
                    "iframe, embed, [data-iframe], " +
                        "[data-embed], [data-player], [data-video]"
                )
                .forEach { nestedElement ->

                    if (
                        isTrailerElement(
                            nestedElement
                        )
                    ) {
                        return@forEach
                    }

                    firstNonBlank(
                        nestedElement.attr("src"),
                        nestedElement.attr("data-src"),
                        nestedElement.attr("data-url"),
                        nestedElement.attr("data-iframe"),
                        nestedElement.attr("data-embed"),
                        nestedElement.attr("data-player"),
                        nestedElement.attr("data-video"),
                    )?.let { nested ->
                        fixUrlNull(nested)
                            ?.takeIf {
                                !isTrailerCandidateUrl(it)
                            }
                            ?.let {
                                candidates.putIfAbsent(
                                    it,
                                    playerLabel,
                                )
                            }
                    }
                }

            val mediaUrls =
                LinkedHashSet<String>()

            mediaUrls.addAll(
                extractDirectMedia(
                    playerHtml
                )
            )

            playerDocument
                .select("script")
                .forEach { scriptElement ->

                    val script =
                        scriptElement.data()
                            .ifBlank {
                                scriptElement.html()
                            }

                    if (
                        script.isBlank()
                    ) {
                        return@forEach
                    }

                    val unpacked =
                        runCatching {
                            getAndUnpack(
                                script
                            )
                        }.getOrDefault(
                            script
                        )

                    mediaUrls.addAll(
                        extractDirectMedia(
                            normalizeText(
                                unpacked
                            )
                        )
                    )
                }

            var found = false

            for (
                mediaUrl in mediaUrls.distinct()
            ) {
                if (
                    isTrailerCandidateUrl(
                        mediaUrl
                    )
                ) {
                    continue
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = playerLabel,
                        url = mediaUrl,
                        type = INFER_TYPE,
                    ) {
                        quality =
                            detectQuality(
                                mediaUrl
                            )

                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to playerUrl,
                            "Origin" to originFromUrl(
                                playerUrl
                            ),
                        )
                    }
                )

                found = true
            }

            if (found) {
                return true
            }
        }

        Log.w(
            "FILMMAKINESI",
            "Gerçek video kaynağı çözülemedi: $data",
        )

        return false
    }

    private fun isUsefulPlayerUrl(
        url: String,
    ): Boolean {
        val lower =
            url.lowercase()

        if (
            lower.contains(
                ".m3u8"
            ) ||
            lower.contains(
                ".mp4"
            ) ||
            lower.contains(
                "closeload.filmmakinesi"
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "/uploads/"
            ) ||
            lower.contains(
                "/oyuncular/"
            ) ||
            lower.contains(
                "/yil/"
            ) ||
            lower.contains(
                "schema.org"
            )
        ) {
            return false
        }

        if (
            lower.contains(
                "youtube.com"
            ) ||
            lower.contains(
                "youtu.be"
            ) ||
            lower.contains(
                "vimeo.com"
            )
        ) {
            return false
        }

        return lower.contains("/embed") ||
            lower.contains("/player") ||
            lower.contains("/video") ||
            lower.contains("/stream") ||
            lower.contains("/play") ||
            lower.contains("/load") ||
            lower.contains("/source") ||
            lower.contains("vidmoly") ||
            lower.contains("filemoon") ||
            lower.contains("streamwish") ||
            lower.contains("dood") ||
            lower.contains("voe") ||
            lower.contains("vudeo") ||
            lower.contains("mixdrop") ||
            lower.contains("streamtape") ||
            lower.contains("vidhide") ||
            lower.contains("lulustream") ||
            lower.contains("ok.ru")
    }

    private fun isKnownProviderHost(
        url: String,
    ): Boolean {
        return isUsefulPlayerUrl(url)
    }

    private fun isTrailerCandidateUrl(
        url: String,
    ): Boolean {
        val value =
            url.lowercase()

        return value.contains("fragman") ||
            value.contains("trailer") ||
            value.contains("teaser") ||
            value.contains("preview") ||
            value.contains(
                "youtube.com/watch"
            ) ||
            value.contains(
                "youtube.com/embed"
            ) ||
            value.contains(
                "youtu.be/"
            ) ||
            value.contains(
                "youtube-nocookie"
            ) ||
            value.contains(
                "vimeo.com/"
            )
    }

    private fun isTrailerElement(
        element: Element,
    ): Boolean {
        val marker =
            buildString {

                var current:
                    Element? = element

                repeat(5) {
                    val item =
                        current
                            ?: return@repeat

                    append(' ')
                    append(
                        item.id()
                    )

                    append(' ')
                    append(
                        item.classNames()
                            .joinToString(" ")
                    )

                    append(' ')
                    append(
                        item.attr("title")
                    )

                    append(' ')
                    append(
                        item.attr("aria-label")
                    )

                    append(' ')
                    append(
                        item.attr("data-type")
                    )

                    append(' ')
                    append(
                        item.attr("data-player")
                    )

                    current =
                        item.parent()
                }
            }

        return Regex(
            "\\b(fragman|trailer|teaser|preview|tanıtım|tanitim)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(
            marker
        )
    }

    private fun extractDirectMedia(
        html: String,
    ): List<String> {
        val patterns =
            listOf(
                Regex(
                    """https?://[^"'<>\s]+\.m3u8(?:\?[^"'<>\s]*)?""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex(
                    """https?://[^"'<>\s]+\.mp4(?:\?[^"'<>\s]*)?""",
                    RegexOption.IGNORE_CASE,
                ),
            )

        val result =
            LinkedHashSet<String>()

        for (pattern in patterns) {
            pattern.findAll(html)
                .forEach {
                    val url =
                        it.value.replace(
                            "\\/",
                            "/",
                        )

                    if (
                        !isTrailerCandidateUrl(
                            url
                        )
                    ) {
                        result.add(url)
                    }
                }
        }

        return result.toList()
    }

    private fun normalizeText(
        text: String,
    ): String {
        return text
            .replace(
                "\\/",
                "/",
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true,
            )
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true,
            )
            .replace(
                "\\u003D",
                "=",
                ignoreCase = true,
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true,
            )
    }

    private fun originFromUrl(
        url: String,
    ): String {
        return runCatching {
            val uri =
                URI(url)

            val scheme =
                uri.scheme ?: "https"

            val host =
                uri.host
                    ?: return@runCatching "$mainUrl/"

            "$scheme://$host"
        }.getOrDefault(
            "$mainUrl/"
        )
    }

    private fun sameUrl(
        a: String,
        b: String,
    ): Boolean {
        return a.trimEnd('/') ==
            b.trimEnd('/')
    }

    private fun detectQuality(
        url: String,
    ): Int {
        val lower =
            url.lowercase()

        return when {
            "2160" in lower ||
                "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "480" in lower ->
                Qualities.P480.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun findTrailer(
        document: Document,
    ): String? {
        val candidates =
            LinkedHashSet<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], a[href], source[src]"
        ).forEach { element ->

            firstNonBlank(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("href"),
            )?.let { value ->

                if (
                    element.text()
                        .contains(
                            "Fragman",
                            true,
                        ) ||
                    value.contains(
                        "youtube",
                        true,
                    ) ||
                    value.contains(
                        "youtu.be",
                        true,
                    ) ||
                    value.contains(
                        "vimeo",
                        true,
                    )
                ) {
                    fixUrlNull(value)
                        ?.let(
                            candidates::add
                        )
                }
            }
        }

        return candidates.firstOrNull {
            it.contains(
                "youtube",
                true,
            ) ||
                it.contains(
                    "youtu.be",
                    true,
                ) ||
                it.contains(
                    "vimeo",
                    true,
                )
        }
    }

    private fun extractDurationMinutes(
        text: String,
    ): Int? {

        Regex(
            "(\\d+)\\s*Saat(?:\\s*(\\d+)\\s*Dakika)?",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.let { match ->

                val hours =
                    match.groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0

                val minutes =
                    match.groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()
                        ?: 0

                return hours * 60 +
                    minutes
            }

        Regex(
            "(\\d{1,3})\\s*Dakika",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return null
    }

    private fun isTrailerCandidate(
        url: String,
        title: String,
        cardText: String,
        durationMinutes: Int?,
    ): Boolean {
        val combined =
            "$url $title $cardText"
                .lowercase()

        if (
            combined.contains(
                "fragman"
            ) ||
            combined.contains(
                "trailer"
            )
        ) {
            return true
        }

        if (
            url.contains(
                "/dizi/",
                true,
            )
        ) {
            return false
        }

        return durationMinutes != null &&
            durationMinutes <= 15
    }

    private fun extractYear(
        text: String,
    ): Int? {
        return Regex(
            "(?:19|20)\\d{2}"
        )
            .find(text)
            ?.value
            ?.toIntOrNull()
    }

    private fun extractRating(
        text: String,
    ): Float? {

        val explicit =
            Regex(
                "(?:IMDb|IMDB|Puan|Rating)\\s*[:：]?\\s*([0-9]+(?:[.,][0-9]+)?)",
                RegexOption.IGNORE_CASE,
            )
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    ',',
                    '.',
                )
                ?.toFloatOrNull()

        if (
            explicit != null &&
            explicit in 0f..10f
        ) {
            return explicit
        }

        return Regex(
            "(?<!\\d)([0-9](?:[.,][0-9])?)(?!\\d)"
        )
            .findAll(text)
            .mapNotNull {
                it.groupValues
                    .getOrNull(1)
                    ?.replace(
                        ',',
                        '.',
                    )
                    ?.toFloatOrNull()
            }
            .firstOrNull {
                it in 0f..10f
            }
    }

    private fun hasNextPage(
        document: Document,
        page: Int,
    ): Boolean {
        val next =
            page + 1

        val regex =
            Regex(
                "(?:/sayfa/$next/|-$next(?:/|$)|[?&]page=$next)"
            )

        return document
            .select("a[href]")
            .any { anchor ->

                val href =
                    fixUrlNull(
                        anchor.attr("href")
                    ).orEmpty()

                regex.containsMatchIn(
                    href
                ) ||
                    anchor.text()
                        .trim()
                        .equals(
                            next.toString(),
                            true,
                        )
            }
    }

    private fun isContentDetailUrl(
        url: String,
    ): Boolean {
        val uri =
            runCatching {
                URI(url)
            }.getOrNull()
                ?: return false

        if (
            !uri.host.orEmpty().contains(
                "filmmakinesi.to",
                true,
            )
        ) {
            return false
        }

        val path =
            uri.path.trimEnd('/')

        return path.matches(
            Regex("/film/[^/]+")
        ) ||
            path.matches(
                Regex("/dizi/[^/]+")
            )
    }

    private fun cleanCardTitle(
        text: String,
    ): String? {
        var value =
            text
                .replace(
                    Regex("\\s+"),
                    " ",
                )
                .trim()

        if (
            value.isBlank()
        ) {
            return null
        }

        value =
            value
                .replace(
                    Regex(
                        "^(?:HD|CAM|HD Dual|HD Altyazılı|HD Altyazili|HD Dublaj|Yabancı Dizi|Yerli Dizi|Yerli Film|Dual|Altyazılı|Altyazili|Dublaj)\\s*",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .replace(
                    Regex(
                        "^\\d+(?:[.,]\\d+)?\\s*"
                    ),
                    "",
                )
                .replace(
                    Regex(
                        "\\b(?:19|20)\\d{2}\\b"
                    ),
                    "",
                )
                .replace(
                    Regex(
                        "\\b\\d+\\s+Dakika\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .replace(
                    Regex(
                        "\\bİzle\\b|\\bIzle\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .replace(
                    Regex("\\s+"),
                    " ",
                )
                .trim(
                    ' ',
                    '-',
                    '|',
                    ':',
                )

        return value.takeIf {
            it.isNotBlank()
        }
    }

    private fun slugToTitle(
        url: String,
    ): String? {
        val slug =
            runCatching {
                URI(url)
                    .path
                    .trimEnd('/')
                    .substringAfterLast('/')
            }.getOrNull()
                ?: return null

        return slug
            .replace(
                Regex("[-_]+"),
                " ",
            )
            .replace(
                Regex(
                    "\\b(?:19|20)\\d{2}\\b"
                ),
                "",
            )
            .replace(
                Regex("\\s+"),
                " ",
            )
            .trim()
            .replaceFirstChar {
                it.uppercase()
            }
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun String.cleanDetailTitle(): String {
        return this
            .replace(
                Regex("\\s+"),
                " ",
            )
            .replace(
                Regex(
                    "\\s*[-|]\\s*Film Makinesi.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex(
                    "\\s*1080p.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()
    }

    private fun firstNonBlank(
        vararg values: String?,
    ): String? {
        return values
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Safari/537.36"
    }
}

/*
 * CloseLoad
 *
 * Buradaki çözüm, CloseLoad'un güncel dinamik JS şemasına göre:
 * 1. İki anahtar değişkenini alır.
 * 2. Array içindeki string parçalarını birleştirir.
 * 3. key2 işlemlerini tersten uygular:
 *    - Base64
 *    - reverse
 *    - Caesar benzeri harf kaydırması
 * 4. Sonraki karakter shuffle işlemini uygular.
 * 5. XOR tabanlı son çözme aşamasını uygular.
 */
private class CloseLoadExtractor : ExtractorApi() {

    override val name =
        "CloseLoad"

    override val mainUrl =
        "https://closeload.filmmakinesi.to"

    override val requiresReferer =
        true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        val userAgent =
            USER_AGENT

        val headers =
            mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Referer" to (
                    referer
                        ?: "https://filmmakinesi.to/"
                    ),
                "Origin" to mainUrl,
            )

        val html =
            runCatching {
                app.get(
                    url,
                    headers = headers,
                ).text
            }.getOrNull()

                ?: throw ErrorLoadingException(
                    "CloseLoad sayfası alınamadı"
                )

        Log.d(
            "FILMMAKINESI",
            "CloseLoad HTML length = ${html.length}",
        )

        /*
         * Önce güncel native decryption.
         */
        val decoded =
            decodeNative(html)

        if (!decoded.isNullOrBlank()) {
            Log.d(
                "FILMMAKINESI",
                "CloseLoad decoded = " + decoded,
            )

            val urls =
                Regex(
                    "https?://[^\\s\\\"'<>|]+",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(decoded)
                    .map {
                        it.value.trimEnd(
                            ')',
                            ']',
                            '}',
                            ';',
                            ',',
                        )
                    }
                    .filter {
                        it.contains(".m3u8", true) ||
                            it.contains(".mp4", true) ||
                            it.contains("master.txt", true) ||
                            it.contains("/hls/", true) ||
                            it.contains("/hls2/", true)
                    }
                    .distinct()
                    .toList()

            var emitted = false

            for (candidate in urls) {
                val mediaUrl =
                    if (isHlsMediaUrl(candidate)) {
                        prepareHlsUrl(
                            candidate,
                            "$mainUrl/",
                            mainUrl,
                            userAgent,
                        )
                    } else {
                        candidate
                    } ?: continue

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = mediaUrl,
                        type = mediaTypeForUrl(mediaUrl),
                    ) {
                        quality =
                            detectQuality(
                                mediaUrl
                            )

                        this.referer =
                            "$mainUrl/"

                        this.headers =
                            mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "*/*",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                            )
                    }
                )

                emitted = true
            }

            processSubtitles(
                html,
                subtitleCallback,
            )

            if (emitted) {
                return
            }
        }

        /*
         * Native decoder bazı yeni sayfalarda değişirse,
         * açık m3u8/mp4 fallback'i.
         */
        val directUrls =
            extractDirectMedia(html)

        if (
            directUrls.isNotEmpty()
        ) {
            var emittedDirect = false

            for (candidate in directUrls) {
                val mediaUrl =
                    if (isHlsMediaUrl(candidate)) {
                        prepareHlsUrl(
                            candidate,
                            "$mainUrl/",
                            mainUrl,
                            userAgent,
                        )
                    } else {
                        candidate
                    } ?: continue

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = mediaUrl,
                        type = mediaTypeForUrl(mediaUrl),
                    ) {
                        quality =
                            detectQuality(
                                mediaUrl
                            )

                        this.referer =
                            "$mainUrl/"

                        this.headers =
                            mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "*/*",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                            )
                    }
                )

                emittedDirect = true
            }

            processSubtitles(
                html,
                subtitleCallback,
            )

            if (emittedDirect) {
                return
            }
        }

        throw ErrorLoadingException(
            "CloseLoad video adresi çözülemedi"
        )
    }

    private fun isHlsMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.endsWith("/master.txt")
    }

    private fun mediaTypeForUrl(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") ||
                lower.contains("/hls/") ||
                lower.contains("/hls2/") ||
                lower.endsWith("/master.txt") ->
                ExtractorLinkType.M3U8

            lower.contains(".mp4") ->
                ExtractorLinkType.VIDEO

            else ->
                ExtractorLinkType.VIDEO
        }
    }

    private suspend fun prepareHlsUrl(
        url: String,
        referer: String,
        origin: String,
        userAgent: String,
    ): String? {
        val clean = url
            .replace("\\/", "/")
            .trim()

        if (!isHlsMediaUrl(clean)) {
            return clean
        }

        return runCatching {
            val body = app.get(
                clean,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to referer,
                    "Origin" to origin,
                ),
                referer = referer,
                allowRedirects = true,
            ).text

            if (!body.contains("#EXTM3U")) {
                Log.e(
                    "FILMMAKINESI",
                    "MEDIA PREFLIGHT NOT HLS=" + clean,
                )
                return@runCatching null
            }

            Log.d(
                "FILMMAKINESI",
                "MEDIA PREFLIGHT OK=" + clean,
            )

            clean
        }.getOrElse {
            Log.e(
                "FILMMAKINESI",
                "MEDIA PREFLIGHT FAIL=" + clean + " ERROR=" + it.message,
            )
            null
        }
    }

    private fun decodeNative(
        html: String,
    ): String? {
        /*
         * CloseLoad obfuscasyonu sayfadan sayfaya küçük JS biçim
         * farklılıkları gösterebiliyor. Eski yaklaşım doğrudan
         * "([..]) + iki ardışık var" düzenine bağımlıydı.
         *
         * Şimdi:
         * - doğrudan [...] array literal'larını buluyoruz,
         * - var/let/const ile tanımlanan string değerleri topluyoruz,
         * - makul anahtar çiftlerini native decoder'a veriyoruz,
         * - yalnızca gerçek medya URL'si üreten sonucu kabul ediyoruz.
         */
        val arrayRegex =
            Regex(
                """(?s)\[((?:\s*(?:["'][^"'\\]*(?:\\.[^"'\\]*)*["'])\s*,?)+)\s*\]"""
            )

        val stringVarRegex =
            Regex(
                """(?s)\b(?:var|let|const)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*(["'])(.*?)\1\s*;?"""
            )

        val arrays =
            arrayRegex
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()

        val stringVars =
            stringVarRegex
                .findAll(html)
                .map {
                    it.groupValues[2]
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()
                .toList()

        Log.d(
            "FILMMAKINESI",
            "CloseLoad decoder adayları: arrays=" +
                arrays.size +
                ", stringVars=" +
                stringVars.size,
        )

        if (arrays.isEmpty() || stringVars.size < 2) {
            return null
        }

        val mediaRegex =
            Regex(
                """https?://[^\s"'<>|]+""",
                RegexOption.IGNORE_CASE,
            )

        val orderedKeys =
            stringVars.sortedWith(
                compareByDescending<String> {
                    it.length in 15..40 &&
                        it.all(Char::isLetterOrDigit)
                }.thenByDescending {
                    it.length
                }
            )

        val attempted =
            HashSet<String>()

        for (array in arrays) {
            for (i in orderedKeys.indices) {
                val key1 =
                    orderedKeys[i]

                if (
                    key1.length !in 8..64 ||
                    key1.contains("\n") ||
                    key1.contains("\r")
                ) {
                    continue
                }

                for (j in orderedKeys.indices) {
                    if (i == j) continue

                    val key2 =
                        orderedKeys[j]

                    if (
                        key2.length !in 2..20 ||
                        key2.contains("\n") ||
                        key2.contains("\r")
                    ) {
                        continue
                    }

                    val synthetic =
                        buildString {
                            append("(")
                            append(array)
                            append(");var a='")
                            append(
                                key1
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                            )
                            append("';var b='")
                            append(
                                key2
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                            )
                            append("';")
                        }

                    if (!attempted.add(synthetic)) {
                        continue
                    }

                    val decoded =
                        decodeNativeRaw(synthetic)
                            ?: continue

                    val media =
                        mediaRegex
                            .findAll(decoded)
                            .map {
                                it.value.trimEnd(
                                    ')',
                                    ']',
                                    '}',
                                    ';',
                                    ',',
                                )
                            }
                            .firstOrNull {
                                val lower =
                                    it.lowercase()

                                lower.contains(".m3u8") ||
                                    lower.contains(".mp4") ||
                                    lower.contains("master.txt") ||
                                    lower.contains("/hls/") ||
                                    lower.contains("/hls2/")
                            }

                    if (!media.isNullOrBlank()) {
                        Log.d(
                            "FILMMAKINESI",
                            "CloseLoad decoder medya adayı bulundu=" +
                                media,
                        )
                        return decoded
                    }
                }
            }
        }

        Log.e(
            "FILMMAKINESI",
            "CloseLoad decoder hiçbir medya adayı üretemedi",
        )

        return null
    }

    private fun decodeNativeRaw(
        html: String,
    ): String? {

        return runCatching {

            /*
             * Array içindeki string parçalarını birleştir.
             *
             * Örnek:
             * (["abc","def","ghi"])
             */
            val arrayMatch =
                Regex(
                    """\(\[((?:["'][^"']+["'],?\s*)+)\]\)""",
                    setOf(
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                ).find(html)
                    ?: return@runCatching null

            val arrayText =
                arrayMatch.groupValues
                    .getOrNull(1)
                    ?: return@runCatching null

            var data =
                Regex(
                    """["']([^"']+)["']"""
                )
                    .findAll(arrayText)
                    .joinToString("") {
                        it.groupValues[1]
                    }

            if (
                data.isBlank()
            ) {
                return@runCatching null
            }

            /*
             * İki anahtar değişkeni.
             */
            val keysMatch =
                Regex(
                    """var\s+[A-Za-z0-9_]+\s*=\s*["']([^"']+)["'];\s*var\s+[A-Za-z0-9_]+\s*=\s*["']([^"']+)["'];""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(html)

            var key1 =
                keysMatch
                    ?.groupValues
                    ?.getOrNull(1)

            var key2 =
                keysMatch
                    ?.groupValues
                    ?.getOrNull(2)

            /*
             * Daha esnek fallback:
             * İlk uzun alfanümerik var + hemen sonraki kısa var.
             */
            if (
                key1.isNullOrBlank() ||
                key2.isNullOrBlank()
            ) {
                val vars =
                    Regex(
                        """var\s+[A-Za-z0-9_]+\s*=\s*["']([^"']+)["'];?"""
                    )
                        .findAll(html)
                        .map {
                            it.groupValues[1]
                        }
                        .toList()

                val firstKey =
                    vars.firstOrNull {
                        it.length in 15..35 &&
                            it.all { c ->
                                c.isLetterOrDigit()
                            }
                    }

                val secondKey =
                    vars.firstOrNull {
                        it.length in 2..8 &&
                            it.all { c ->
                                c.isLetter()
                            }
                    }

                key1 = firstKey
                key2 = secondKey
            }

            val k1 =
                key1
                    ?: return@runCatching null

            val k2 =
                key2
                    ?: return@runCatching null

            /*
             * Key1 hash.
             */
            var hashA =
                0

            var hashB =
                0

            for (
                i in k1.indices
            ) {
                val code =
                    k1[i].code

                hashA =
                    (
                        hashA * 31 +
                            code
                        ) % 251

                hashB =
                    (
                        hashB xor (
                            code + i
                            )
                        ) and 255
            }

            val xorStart =
                (
                    hashA +
                        hashB
                    ) % 256

            val step =
                (
                    hashA % 13
                    ) + 3

            var seed =
                (
                    (
                        hashA * 256 +
                            hashB
                        ) % 65521
                    ) + 1

            /*
             * key2 işlemlerini tersten uygula.
             */
            for (
                i in k2.length - 1 downTo 0
            ) {

                when (
                    val op =
                        k2[i]
                ) {

                    'b' -> {
                        var padded =
                            data

                        val missing =
                            padded.length % 4

                        if (
                            missing != 0
                        ) {
                            padded +=
                                "=".repeat(
                                    4 - missing
                                )
                        }

                        val bytes =
                            try {
                                Base64.decode(
                                    padded,
                                    Base64.DEFAULT,
                                )
                            } catch (
                                _: Throwable
                            ) {
                                java.util.Base64
                                    .getDecoder()
                                    .decode(
                                        padded
                                    )
                            }

                        data =
                            String(
                                bytes,
                                Charsets.ISO_8859_1,
                            )
                    }

                    'v' -> {
                        data =
                            data.reversed()
                    }

                    else -> {
                        val shift =
                            (
                                26 -
                                    (
                                        (
                                            op.code -
                                                64
                                            ) % 26
                                        )
                                ) % 26

                        val builder =
                            StringBuilder(
                                data.length
                            )

                        for (
                            char in data
                        ) {

                            val code =
                                char.code

                            if (
                                code in 65..90
                            ) {
                                builder.append(
                                    (
                                        (
                                            code -
                                                65 +
                                                shift
                                            ) % 26 +
                                            65
                                        ).toChar()
                                )
                            } else if (
                                code in 97..122
                            ) {
                                builder.append(
                                    (
                                        (
                                            code -
                                                97 +
                                                shift
                                            ) % 26 +
                                            97
                                        ).toChar()
                                )
                            } else {
                                builder.append(
                                    char
                                )
                            }
                        }

                        data =
                            builder.toString()
                    }
                }
            }

            /*
             * Shuffle indexleri.
             */
            val length =
                data.length

            val indexes =
                IntArray(length)

            for (
                i in length - 1 downTo 1
            ) {
                seed =
                    (
                        seed * 75 +
                            74
                        ) % 65537

                indexes[i] =
                    seed %
                        (i + 1)
            }

            val chars =
                data.toCharArray()

            for (
                i in 1 until length
            ) {
                val target =
                    indexes[i]

                val temp =
                    chars[i]

                chars[i] =
                    chars[target]

                chars[target] =
                    temp
            }

            data =
                String(chars)

            /*
             * Final XOR.
             */
            var state =
                xorStart

            val output =
                StringBuilder(
                    data.length
                )

            for (
                i in data.indices
            ) {

                val code =
                    data[i].code

                state =
                    (
                        state +
                            step
                        ) % 256

                output.append(
                    (
                        code xor state
                        ).toChar()
                )

                state =
                    (
                        state +
                            code
                        ) % 256
            }

            output.toString()

        }.getOrNull()
    }

    private fun processSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {

        val direct =
            Regex(
                """["']file["']\s*:\s*["'](https?:[^"']+\.vtt[^"']*)["'][^}]*["']label["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )

        direct.findAll(
            html
        ).forEach {

            val url =
                it.groupValues[1]
                    .replace(
                        "\\/",
                        "/",
                    )

            val label =
                it.groupValues[2]
                    .ifBlank {
                        "Altyazı"
                    }

            if (
                url.startsWith(
                    "http",
                    true,
                )
            ) {
                subtitleCallback(
                    SubtitleFile(
                        lang = label,
                        url = url,
                    )
                )
            }
        }

        /*
         * Daha basit VTT fallback.
         */
        Regex(
            """https?://[^"'<>\\s]+\.vtt(?:\?[^"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(html)
            .map {
                it.value.replace(
                    "\\/",
                    "/",
                )
            }
            .distinct()
            .forEach { url ->

                subtitleCallback(
                    SubtitleFile(
                        lang = when {
                            url.contains(
                                "/tr",
                                true,
                            ) ||
                                url.contains(
                                    "tur",
                                    true,
                                ) ->
                                "Turkish"

                            url.contains(
                                "/en",
                                true,
                            ) ||
                                url.contains(
                                    "eng",
                                    true,
                                ) ->
                                "English"

                            else ->
                                "Subtitle"
                        },
                        url = url,
                    )
                )
            }
    }

    private fun extractDirectMedia(
        html: String,
    ): List<String> {

        val result =
            LinkedHashSet<String>()

        Regex(
            """https?://[^"'<>\s]+(?:\.m3u8|/master\.txt)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(html)
            .forEach {
                result.add(
                    it.value.replace(
                        "\\/",
                        "/",
                    )
                )
            }

        Regex(
            """https?://[^"'<>\s]+\.mp4(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(html)
            .forEach {
                result.add(
                    it.value.replace(
                        "\\/",
                        "/",
                    )
                )
            }

        return result
            .filterNot {
                it.contains(
                    "fragman",
                    true,
                ) ||
                    it.contains(
                        "trailer",
                        true,
                    )
            }
            .toList()
    }

    private fun detectQuality(
        url: String,
    ): Int {
        val lower =
            url.lowercase()

        return when {
            "2160" in lower ||
                "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "480" in lower ->
                Qualities.P480.value

            else ->
                Qualities.Unknown.value
        }
    }
}
