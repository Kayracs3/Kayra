package com.Kayracs3

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
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

        val response = runCatching {
            app.get(
                url,
                headers = requestHeaders,
            )
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

        val document = response.document
        val results = parseListPage(document)

        val finalResults =
            if (results.isEmpty() && page > 1) {
                val alternate = alternatePageUrl(
                    baseUrl,
                    page,
                )

                if (
                    alternate != null &&
                    alternate != url
                ) {
                    runCatching {
                        app.get(
                            alternate,
                            headers = requestHeaders,
                        ).document
                    }
                        .getOrNull()
                        ?.let(::parseListPage)
                        .orEmpty()
                } else {
                    emptyList()
                }
            } else {
                results
            }

        val hasNext =
            hasNextPage(
                document,
                page,
            ) ||
                (
                    finalResults.isNotEmpty() &&
                        finalResults.size >= 15
                    )

        Log.d(
            "FILMMAKINESI",
            "${request.name} / sayfa $page -> ${finalResults.size} sonuç",
        )

        return newHomePageResponse(
            request.name,
            finalResults,
            hasNext = hasNext,
        )
    }

    private fun pageUrl(
        baseUrl: String,
        page: Int,
    ): String {
        if (page <= 1) return baseUrl

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
        return document.select("a[href]")
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
                    cleanCardTitle(
                        anchor.text()
                    ),
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
                        title.trim(),
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
                        title.trim(),
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
            val results =
                runCatching {
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

        val document =
            runCatching {
                app.get(
                    normalized,
                    headers = requestHeaders,
                )
            }
                .getOrNull()
                ?.document
                ?: return null

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
                    it.attr("alt")
                        .contains(
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

        if (linked.isNotEmpty()) {
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

        if (parentText.isBlank()) {
            return null
        }

        val names =
            parentText
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

        return names
            .takeIf {
                it.isNotEmpty()
            }
            ?.map {
                Actor(it)
            }
    }

    private fun extractEpisodes(
        document: Document,
        poster: String?,
    ): List<Episode> {
        val result =
            LinkedHashMap<String, Episode>()

        document.select(
            "a[href], a[data-href], a[data-url]"
        ).forEach { anchor ->

            val href = fixUrlNull(
                firstNonBlank(
                    anchor.attr("href"),
                    anchor.attr("data-href"),
                    anchor.attr("data-url"),
                )
            ) ?: return@forEach

            val anchorText =
                anchor.text()
                    .replace(
                        Regex("\\s+"),
                        " ",
                    )
                    .trim()

            val dataText =
                listOf(
                    anchorText,
                    anchor.attr("title"),
                    anchor.attr("aria-label"),
                    anchor.parent()
                        ?.text()
                        .orEmpty(),
                ).joinToString(" ")

            val season =
                extractSeason(
                    dataText,
                    href,
                ) ?: return@forEach

            val episode =
                extractEpisodeNumber(
                    dataText,
                    href,
                ) ?: return@forEach

            if (
                !dataText.contains(
                    "Bölüm",
                    true,
                ) &&
                !dataText.contains(
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

            if (!href.startsWith("$mainUrl/")) {
                return@forEach
            }

            if (
                isContentDetailUrl(href) &&
                !href.contains("bolum", true) &&
                !href.contains("episode", true)
            ) {
                return@forEach
            }

            val name =
                cleanEpisodeName(
                    dataText,
                    season,
                    episode,
                )

            result[
                href.trimEnd('/')
            ] = newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                posterUrl = poster
            }
        }

        return result.values
            .sortedWith(
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

    /*
     * ASIL DÜZELTME BURADA.
     *
     * Eski kod yalnızca:
     * div.player-div iframe
     *
     * arıyordu. Güncel FilmMakinesi sayfasında player farklı bir container
     * altında bulunabiliyor. Bu nedenle artık tüm iframe/data-* yapılarını
     * tarıyoruz.
     */
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

        val pageResponse =
            runCatching {
                app.get(
                    data,
                    headers = requestHeaders,
                )
            }.getOrNull()
                ?: return false

        val document = pageResponse.document
        val rawHtml =
            normalizeEmbeddedText(
                pageResponse.text
            )

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

            if (fixed.isBlank()) {
                return
            }

            if (sameUrl(fixed, data)) {
                return
            }

            if (
                isTrailerCandidateUrl(
                    fixed
                )
            ) {
                return
            }

            candidates[
                fixed.substringBefore('#')
            ] = label
        }

        /*
         * 1) EN ÖNEMLİ KISIM:
         * Sayfadaki TÜM iframe'leri tara.
         *
         * div.player-div iframe
         * iframe[src]
         * iframe[data-src]
         * iframe[data-url]
         *
         * hepsi burada yakalanır.
         */
        document.select(
            "iframe, embed"
        ).forEach { element ->

            val marker =
                (
                    element.text() + " " +
                        element.attr("title") + " " +
                        element.attr("aria-label") + " " +
                        element.id() + " " +
                        element.attr("name") + " " +
                        element.classNames()
                            .joinToString(" ") + " " +
                        element.parent()
                            ?.text()
                            .orEmpty()
                    )
                    .replace(
                        Regex("\\s+"),
                        " ",
                    )
                    .trim()

            if (
                isTrailerElement(element)
            ) {
                return@forEach
            }

            val label =
                when {
                    marker.contains(
                        "rapid",
                        true,
                    ) ->
                        "FilmMakinesi • Rapid"

                    marker.contains(
                        "dublaj",
                        true,
                    ) ->
                        "FilmMakinesi • Dublaj"

                    marker.contains(
                        "altyaz",
                        true,
                    ) ->
                        "FilmMakinesi • Altyazı"

                    else ->
                        "FilmMakinesi • Player"
                }

            val attributes =
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
                )

            for (attributeName in attributes) {
                val raw =
                    element
                        .attr(attributeName)
                        .trim()

                if (raw.isBlank()) {
                    continue
                }

                val decoded =
                    normalizeEmbeddedText(
                        raw
                    )

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
         * 2) Eski FilmMakinesi yapısı:
         * div.player-div iframe
         *
         * Bu doğrudan fallback olarak da tutuluyor.
         */
        document.select(
            "div.player-div iframe, " +
                "div.player-div embed, " +
                "div.player-div video, " +
                "div.player-div source, " +
                "div.player-div [data-src], " +
                "div.player-div [data-url], " +
                "div.player-div [data-player], " +
                "div.player-div [data-embed]"
        ).forEach { element ->

            if (
                isTrailerElement(element)
            ) {
                return@forEach
            }

            val marker =
                (
                    element.text() + " " +
                        element.attr("title") + " " +
                        element.attr("aria-label") + " " +
                        element.classNames()
                            .joinToString(" ")
                    )
                    .lowercase()

            val label =
                when {
                    marker.contains("rapid") ->
                        "FilmMakinesi • Rapid"

                    marker.contains("dublaj") ->
                        "FilmMakinesi • Dublaj"

                    else ->
                        "FilmMakinesi • Player"
                }

            addCandidate(
                firstNonBlank(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-iframe"),
                    element.attr("data-player"),
                    element.attr("data-embed"),
                ),
                label,
            )
        }

        /*
         * 3) Player butonları ve data-* bağlantıları.
         */
        document.select(
            "a[href], " +
                "button, " +
                "[role='button'], " +
                "[onclick], " +
                "[data-src], " +
                "[data-url], " +
                "[data-link], " +
                "[data-href], " +
                "[data-video], " +
                "[data-iframe], " +
                "[data-embed], " +
                "[data-player], " +
                "[data-player-src], " +
                "[data-embed-url], " +
                "[data-video-url], " +
                "[data-file], " +
                "[data-play]"
        ).forEach { element ->

            val marker =
                (
                    element.text() + " " +
                        element.attr("title") + " " +
                        element.attr("aria-label") + " " +
                        element.attr("id") + " " +
                        element.classNames()
                            .joinToString(" ") + " " +
                        element.attr("data-type") + " " +
                        element.attr("data-provider")
                    )
                    .replace(
                        Regex("\\s+"),
                        " ",
                    )
                    .trim()

            val likelyPlayer =
                marker.contains("flm", true) ||
                    marker.contains("rapid", true) ||
                    marker.contains("dublaj", true) ||
                    marker.contains("altyaz", true) ||
                    marker.contains("player", true) ||
                    marker.contains("izle", true) ||
                    marker.contains("stream", true) ||
                    marker.contains("1080", true) ||
                    marker.contains("720", true) ||
                    marker.contains("film", true)

            if (
                !likelyPlayer &&
                element.attr("onclick").isBlank()
            ) {
                return@forEach
            }

            val label =
                when {
                    marker.contains(
                        "rapid",
                        true,
                    ) ->
                        "FilmMakinesi • Rapid"

                    marker.contains(
                        "dublaj",
                        true,
                    ) ->
                        "FilmMakinesi • Dublaj"

                    marker.contains(
                        "altyaz",
                        true,
                    ) ->
                        "FilmMakinesi • Altyazı"

                    else ->
                        "FilmMakinesi • Player"
                }

            val attributes =
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
                )

            for (
                attributeName in attributes
            ) {
                val rawValue =
                    element
                        .attr(attributeName)
                        .trim()

                if (
                    rawValue.isBlank()
                ) {
                    continue
                }

                val decoded =
                    normalizeEmbeddedText(
                        rawValue
                    )

                Regex(
                    "https?://[^\\s\\\"'<>]+",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(decoded)
                    .forEach { match ->

                        val candidateUrl =
                            match.value.trimEnd(
                                ')',
                                ']',
                                '}',
                                ';',
                                ',',
                            )

                        val lower =
                            candidateUrl.lowercase()

                        if (
                            lower.contains("/player") ||
                            lower.contains("/embed") ||
                            lower.contains("/watch") ||
                            lower.contains("/stream") ||
                            lower.contains("/video") ||
                            lower.contains("filmmakinesi.to/ajax") ||
                            lower.contains("filmmakinesi.to/source") ||
                            lower.contains("filmmakinesi.to/play") ||
                            lower.contains("filmmakinesi.to/load") ||
                            isKnownProviderHost(
                                candidateUrl
                            )
                        ) {
                            addCandidate(
                                candidateUrl,
                                label,
                            )
                        }
                    }

                Regex(
                    """(?:^|[=:"'`()\s])((?:/)+(?:player|embed|watch|stream|video|ajax/player|ajax/embed|ajax/video|source|play|load)[^\s"'<>]*)""",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(decoded)
                    .map {
                        it.groupValues[1]
                    }
                    .forEach { relative ->
                        addCandidate(
                            relative,
                            label,
                        )
                    }
            }
        }

        /*
         * 4) Scriptlerde iframe/player URL'si ara.
         *
         * Önce keyword'e yakın pencere,
         * sonra tüm script içinde doğrudan iframe/player/embed URL'si.
         */
        val scriptBlocks =
            document.select("script")
                .map {
                    it.data()
                        .ifBlank {
                            it.html()
                        }
                }
                .filter {
                    it.isNotBlank()
                }

        for (script in scriptBlocks) {
            val decodedScript =
                normalizeEmbeddedText(
                    script
                )

            /*
             * Tüm script içindeki mutlak player URL'leri.
             */
            Regex(
                "https?://[^\\s\\\"'<>]+",
                RegexOption.IGNORE_CASE,
            )
                .findAll(decodedScript)
                .forEach { match ->

                    val candidateUrl =
                        match.value.trimEnd(
                            ')',
                            ']',
                            '}',
                            ';',
                            ',',
                        )

                    val lower =
                        candidateUrl.lowercase()

                    if (
                        lower.contains(
                            "/embed"
                        ) ||
                        lower.contains(
                            "/player"
                        ) ||
                        lower.contains(
                            "/video"
                        ) ||
                        lower.contains(
                            "/stream"
                        ) ||
                        lower.contains(
                            "closeload"
                        ) ||
                        isKnownProviderHost(
                            candidateUrl
                        )
                    ) {
                        addCandidate(
                            candidateUrl,
                            "FilmMakinesi • Script Player",
                        )
                    }
                }

            /*
             * Keyword yakınındaki URL'ler.
             */
            val keywords =
                listOf(
                    "iframe",
                    "player",
                    "rapid",
                    "dublaj",
                    "altyaz",
                    "embed",
                    "stream",
                    "source",
                )

            for (keyword in keywords) {
                var cursor = 0

                while (true) {
                    val index =
                        decodedScript.indexOf(
                            keyword,
                            cursor,
                            ignoreCase = true,
                        )

                    if (index < 0) {
                        break
                    }

                    val start =
                        maxOf(
                            0,
                            index - 2200,
                        )

                    val end =
                        minOf(
                            decodedScript.length,
                            index + 3200,
                        )

                    val window =
                        decodedScript.substring(
                            start,
                            end,
                        )

                    Regex(
                        "https?://[^\\s\\\"'<>]+",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(window)
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

                    Regex(
                        """["'](?:src|href|url|file|source|embed|player|iframe)["']?\s*[:=]\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(window)
                        .forEach { match ->
                            addCandidate(
                                match.groupValues[1],
                                "FilmMakinesi • Script Player",
                            )
                        }

                    cursor =
                        index + keyword.length
                }
            }

            /*
             * Packed JavaScript.
             */
            val unpacked =
                runCatching {
                    getAndUnpack(
                        decodedScript
                    )
                }.getOrNull()

            if (
                !unpacked.isNullOrBlank() &&
                unpacked != decodedScript
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

                extractDirectMediaWithContext(
                    normalizeEmbeddedText(
                        unpacked
                    )
                ).forEach { mediaUrl ->
                    addCandidate(
                        mediaUrl,
                        "FilmMakinesi • Direct Media",
                    )
                }
            }
        }

        /*
         * 5) Doğrudan m3u8 / mp4.
         */
        extractDirectMediaWithContext(
            rawHtml
        ).forEach { mediaUrl ->
            addCandidate(
                mediaUrl,
                "FilmMakinesi • Direct Media",
            )
        }

        /*
         * 6) Hiç aday bulunamadıysa son bir global HTML regex taraması.
         */
        if (candidates.isEmpty()) {
            val allUrls =
                Regex(
                    """https?://[^"'<>\s]+""",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(rawHtml)
                    .map {
                        it.value.trimEnd(
                            ')',
                            ']',
                            '}',
                            ';',
                            ',',
                        )
                    }
                    .distinct()

            for (candidateUrl in allUrls) {
                val lower =
                    candidateUrl.lowercase()

                if (
                    lower.contains("/embed") ||
                    lower.contains("/player") ||
                    lower.contains("/video") ||
                    lower.contains("/stream") ||
                    lower.contains(".m3u8") ||
                    lower.contains(".mp4") ||
                    isKnownProviderHost(
                        candidateUrl
                    )
                ) {
                    addCandidate(
                        candidateUrl,
                        "FilmMakinesi • Global Fallback",
                    )
                }
            }
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

        if (candidates.isEmpty()) {
            Log.w(
                "FILMMAKINESI",
                "Hiç player bulunamadı: $data",
            )
            return false
        }

        Log.d(
            "FILMMAKINESI",
            "Bulunan adaylar = " +
                candidates.entries.joinToString(
                    " | "
                ) {
                    "${it.value}: ${it.key}"
                }
        )

        /*
         * 7) CloseLoad önce.
         */
        val closeLoadCandidates =
            candidates.entries.filter {
                it.key.contains(
                    "closeload",
                    true,
                )
            }

        for (
            (playerUrl, _) in closeLoadCandidates
        ) {
            val closeLoaded =
                runCatching {
                    CloseLoadExtractor().getUrl(
                        url = playerUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )

                    true
                }.getOrElse { error ->
                    Log.e(
                        "FILMMAKINESI",
                        "CloseLoad başarısız: $playerUrl",
                        error,
                    )

                    false
                }

            if (closeLoaded) {
                return true
            }
        }

        /*
         * 8) CloudStream extractor'ları.
         */
        for (
            (playerUrl, playerLabel) in candidates
        ) {
            if (
                playerUrl.contains(
                    "closeload",
                    true,
                )
            ) {
                continue
            }

            Log.d(
                "FILMMAKINESI",
                "Extractor deneniyor: $playerUrl",
            )

            val externalLoaded =
                runCatching {
                    loadExtractor(
                        playerUrl,
                        data,
                        subtitleCallback,
                        callback,
                    )
                }.getOrDefault(false)

            if (externalLoaded) {
                Log.d(
                    "FILMMAKINESI",
                    "Extractor başarılı: $playerUrl",
                )

                return true
            }

            /*
             * Extractor çözemedi ise player sayfasını aç.
             */
            val playerResponse =
                runCatching {
                    app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to data,
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        ),
                    )
                }.getOrNull()
                    ?: continue

            val playerDocument =
                playerResponse.document

            val playerHtml =
                normalizeEmbeddedText(
                    playerResponse.text
                )

            /*
             * İç içe iframe.
             */
            val nestedCandidates =
                LinkedHashSet<String>()

            playerDocument.select(
                "iframe, embed, " +
                    "[data-iframe], " +
                    "[data-embed], " +
                    "[data-player], " +
                    "[data-video]"
            ).forEach { element ->

                if (
                    isTrailerElement(
                        element
                    )
                ) {
                    return@forEach
                }

                firstNonBlank(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-iframe"),
                    element.attr("data-embed"),
                    element.attr("data-player"),
                    element.attr("data-video"),
                )?.let { raw ->
                    fixUrlNull(raw)
                        ?.let { nested ->
                            if (
                                !isTrailerCandidateUrl(
                                    nested
                                )
                            ) {
                                nestedCandidates.add(
                                    nested
                                )
                            }
                        }
                }
            }

            for (
                nested in nestedCandidates
            ) {
                val nestedLoaded =
                    runCatching {
                        loadExtractor(
                            nested,
                            playerUrl,
                            subtitleCallback,
                            callback,
                        )
                    }.getOrDefault(false)

                if (nestedLoaded) {
                    return true
                }
            }

            /*
             * Player sayfasında doğrudan medya.
             */
            val mediaUrls =
                LinkedHashSet<String>()

            mediaUrls.addAll(
                extractDirectMediaWithContext(
                    playerHtml
                )
            )

            playerDocument.select(
                "script"
            ).forEach { scriptElement ->

                val script =
                    scriptElement.data()
                        .ifBlank {
                            scriptElement.html()
                        }

                if (script.isBlank()) {
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
                    extractDirectMediaWithContext(
                        normalizeEmbeddedText(
                            unpacked
                        )
                    )
                )
            }

            var found = false

            for (
                mediaUrl in mediaUrls
                    .distinct()
                    .filterNot {
                        isTrailerCandidateUrl(it)
                    }
            ) {
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

    private fun originFromUrl(
        url: String,
    ): String {
        return runCatching {
            val uri = URI(url)

            val scheme =
                uri.scheme ?: "https"

            val host =
                uri.host
                    ?: return@runCatching "$mainUrl/"

            "$scheme://$host"
        }.getOrDefault("$mainUrl/")
    }

    private fun isKnownProviderHost(
        url: String,
    ): Boolean {
        val lower =
            url.lowercase()

        return lower.contains("vidmoly") ||
            lower.contains("filemoon") ||
            lower.contains("streamwish") ||
            lower.contains("dood") ||
            lower.contains("voe") ||
            lower.contains("vudeo") ||
            lower.contains("mixdrop") ||
            lower.contains("streamtape") ||
            lower.contains("vidhide") ||
            lower.contains("lulustream") ||
            lower.contains("ok.ru") ||
            lower.contains("closeload") ||
            lower.contains("filmmakinesi")
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
            value.contains("youtu.be/") ||
            value.contains(
                "youtube-nocookie.com"
            ) ||
            value.contains("vimeo.com/")
    }

    private fun isTrailerElement(
        element: Element,
    ): Boolean {
        val parts =
            ArrayList<String>()

        var current:
            Element? = element

        repeat(5) {
            val item =
                current
                    ?: return@repeat

            parts.add(
                item.id()
            )

            parts.add(
                item.classNames()
                    .joinToString(" ")
            )

            parts.add(
                item.attr("title")
            )

            parts.add(
                item.attr("aria-label")
            )

            parts.add(
                item.attr("data-name")
            )

            parts.add(
                item.attr("data-type")
            )

            parts.add(
                item.attr("data-player")
            )

            current =
                item.parent()
        }

        val marker =
            parts.joinToString(" ")
                .lowercase()

        return Regex(
            "\\b(fragman|trailer|teaser|preview|tanıtım|tanitim)\\b",
            RegexOption.IGNORE_CASE,
        )
            .containsMatchIn(marker)
    }

    private fun sameUrl(
        a: String,
        b: String,
    ): Boolean {
        return a.trimEnd('/') ==
            b.trimEnd('/')
    }

    private fun extractDirectMediaWithContext(
        html: String,
    ): List<String> {
        val patterns =
            listOf(
                Regex(
                    "https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?",
                    RegexOption.IGNORE_CASE,
                ),
                Regex(
                    "https?://[^\\\"'<>\\s]+\\.mp4(?:\\?[^\\\"'<>\\s]*)?",
                    RegexOption.IGNORE_CASE,
                ),
            )

        val foundUrls =
            LinkedHashSet<String>()

        for (pattern in patterns) {
            for (
                match in pattern.findAll(html)
            ) {
                val url =
                    match.value.replace(
                        "\\/",
                        "/",
                    )

                if (
                    isTrailerCandidateUrl(
                        url
                    )
                ) {
                    continue
                }

                val start =
                    maxOf(
                        0,
                        match.range.first - 700,
                    )

                val end =
                    minOf(
                        html.length,
                        match.range.last + 700,
                    )

                val context =
                    html.substring(
                        start,
                        end,
                    ).lowercase()

                if (
                    Regex(
                        "\\b(fragman|trailer|teaser|preview|tanıtım|tanitim)\\b",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(
                        context
                    )
                ) {
                    continue
                }

                val shortDuration =
                    Regex(
                        "(?:duration|length|seconds)\\D{0,15}(\\d+(?:\\.\\d+)?)",
                        RegexOption.IGNORE_CASE,
                    )
                        .find(context)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toDoubleOrNull()
                        ?.let {
                            it in 1.0..900.0
                        } == true

                if (shortDuration) {
                    continue
                }

                foundUrls.add(url)
            }
        }

        return foundUrls.toList()
    }

    private fun normalizeEmbeddedText(
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
                "\\u003D",
                "=",
                ignoreCase = true,
            )
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true,
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true,
            )
    }

    private fun extractMediaUrls(
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
                Regex(
                    """["'](?:file|source|src|contentUrl)["']?\s*[:=]\s*["'](https?://[^"']+)["']""",
                    RegexOption.IGNORE_CASE,
                ),
            )

        return patterns
            .flatMap {
                it.findAll(html)
                    .map {
                        match ->
                        match.groupValues.last()
                    }
                    .toList()
            }
            .map {
                it.replace(
                    "\\/",
                    "/",
                )
            }
            .distinct()
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true)
            }
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
                ignoreCase = true,
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
        val match =
            Regex(
                "(?:19|20)\\d{2}"
            ).find(text)
                ?: return null

        return match.value.toIntOrNull()
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
                    '.'
                )
                ?.toFloatOrNull()

        if (
            explicit != null &&
            explicit in 0f..10f
        ) {
            return explicit
        }

        val cardRating =
            Regex(
                "(?:HD|CAM|Dual|Dublaj|Altyazılı|Altyazili|Yabancı Dizi|Yerli Dizi|Yerli Film)\\s+([0-9](?:[.,][0-9])?)(?:\\s|$)",
                RegexOption.IGNORE_CASE,
            )
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    ',',
                    '.'
                )
                ?.toFloatOrNull()

        if (
            cardRating != null &&
            cardRating in 0f..10f
        ) {
            return cardRating
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
                        '.'
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
        val nextPage = page + 1

        val nextPattern =
            Regex(
                "(?:/sayfa/$nextPage/|-$nextPage(?:/|$)|[?&]page=$nextPage)"
            )

        return document.select(
            "a[href]"
        ).any { anchor ->

            val href =
                fixUrlNull(
                    anchor.attr("href")
                ).orEmpty()

            nextPattern.containsMatchIn(
                href
            ) ||
                anchor.text()
                    .trim()
                    .equals(
                        "$nextPage",
                        true,
                    )
        }
    }

    private fun isContentDetailUrl(
        url: String,
    ): Boolean {
        val clean =
            runCatching {
                URI(url)
            }.getOrNull()
                ?: return false

        if (
            !clean.host.orEmpty()
                .contains(
                    "filmmakinesi.to",
                    true,
                )
        ) {
            return false
        }

        val path =
            clean.path.trimEnd('/')

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
            text.replace(
                Regex("\\s+"),
                " ",
            ).trim()

        if (value.isBlank()) {
            return null
        }

        value = value
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
                    "^\\d+\\s+"
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
            .trim()
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
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "Chrome/154.0 Safari/537.36"
    }
}

/*
 * CloseLoad extractor.
 */
private class CloseLoadExtractor : ExtractorApi() {

    override val name =
        "CloseLoad"

    override val mainUrl =
        "https://closeload.filmmakinesi.de"

    override val requiresReferer =
        true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val extRef =
            referer
                ?: "https://filmmakinesi.to/"

        Log.d(
            "FILMMAKINESI",
            "CloseLoad URL -> $url",
        )

        val response =
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to extRef,
                    "Accept-Language" to
                        "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                ),
            )

        response.document
            .select("track[src]")
            .forEach { track ->

                val src =
                    track.attr("src")
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let { raw ->
                            runCatching {
                                URI(url)
                                    .resolve(raw)
                                    .toString()
                            }.getOrNull()
                        }

                if (
                    !src.isNullOrBlank()
                ) {
                    subtitleCallback(
                        SubtitleFile(
                            lang = track.attr("label")
                                .ifBlank {
                                    track.attr(
                                        "srclang"
                                    ).ifBlank {
                                        "Turkish"
                                    }
                                },
                            url = src,
                        )
                    )
                }
            }

        val scripts =
            response.document
                .select("script")
                .map {
                    it.data()
                        .ifBlank {
                            it.html()
                        }
                        .trim()
                }
                .filter {
                    it.isNotBlank()
                }

        val unpackedScripts =
            buildList {

                for (script in scripts) {
                    add(script)

                    runCatching {
                        getAndUnpack(script)
                    }
                        .getOrNull()
                        ?.takeIf {
                            it.isNotBlank() &&
                                it != script
                        }
                        ?.let(::add)
                }
            }

        val encodedCandidates =
            LinkedHashSet<String>()

        for (
            script in unpackedScripts
        ) {
            Regex(
                "return\\s+result\\s*}var\\s+.*?=\\s*\\(\\\"([^\\\"]+)\\\"\\)",
                RegexOption.IGNORE_CASE,
            )
                .find(script)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(
                    encodedCandidates::add
                )

            Regex(
                "(?:=|\\()\\s*\\\"([A-Za-z0-9+/=_-]{80,})\\\"\\s*\\)?",
                RegexOption.IGNORE_CASE,
            )
                .findAll(script)
                .forEach { match ->
                    match.groupValues
                        .getOrNull(1)
                        ?.let(
                            encodedCandidates::add
                        )
                }
        }

        fun normalizeBase64(
            value: String,
        ): String {
            var v =
                value
                    .trim()
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "\\u003d",
                        "=",
                        ignoreCase = true,
                    )
                    .replace(
                        "\\u002b",
                        "+",
                        ignoreCase = true,
                    )
                    .replace(
                        "\\u002f",
                        "/",
                        ignoreCase = true,
                    )

            v = v.filter {
                !it.isWhitespace()
            }

            val pad =
                (
                    4 -
                        (
                            v.length % 4
                            )
                    ) % 4

            return v +
                "=".repeat(pad)
        }

        fun decodeCloseLoad(
            value: String,
        ): List<String> {
            val results =
                LinkedHashSet<String>()

            val original =
                value
                    .trim()
                    .removeSurrounding("\"")

            val normalized =
                normalizeBase64(
                    original
                )

            runCatching {
                val first =
                    Base64.decode(
                        normalized,
                        Base64.DEFAULT,
                    ).reversedArray()

                val second =
                    Base64.decode(
                        first,
                        Base64.DEFAULT,
                    )

                val text =
                    second.toString(
                        Charsets.UTF_8
                    )

                text.split("|")
                    .map {
                        it.trim()
                    }
                    .filter {
                        it.startsWith(
                            "http",
                            true,
                        ) &&
                            (
                                it.contains(
                                    ".m3u8",
                                    true,
                                ) ||
                                    it.contains(
                                        ".mp4",
                                        true,
                                    )
                                )
                    }
                    .forEach(
                        results::add
                    )

                if (
                    text.contains(
                        "http",
                        true,
                    )
                ) {
                    Regex(
                        "https?://[^\\s|\\\"'<>]+",
                        RegexOption.IGNORE_CASE,
                    )
                        .findAll(text)
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
                            it.contains(
                                ".m3u8",
                                true,
                            ) ||
                                it.contains(
                                    ".mp4",
                                    true,
                                )
                        }
                        .forEach(
                            results::add
                        )
                }
            }

            runCatching {
                val firstDecoded =
                    Base64.decode(
                        normalized,
                        Base64.DEFAULT,
                    )
                        .reversedArray()
                        .toString(
                            Charsets.UTF_8
                        )

                val secondNormalized =
                    normalizeBase64(
                        firstDecoded
                    )

                val secondDecoded =
                    Base64.decode(
                        secondNormalized,
                        Base64.DEFAULT,
                    )
                        .toString(
                            Charsets.UTF_8
                        )

                Regex(
                    "https?://[^\\s|\\\"'<>]+",
                    RegexOption.IGNORE_CASE,
                )
                    .findAll(secondDecoded)
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
                        it.contains(
                            ".m3u8",
                            true,
                        ) ||
                            it.contains(
                                ".mp4",
                                true,
                            )
                    }
                    .forEach(
                        results::add
                    )
            }

            return results.toList()
        }

        val mediaUrls =
            LinkedHashSet<String>()

        for (
            encoded in encodedCandidates
        ) {
            decodeCloseLoad(
                encoded
            ).forEach(
                mediaUrls::add
            )
        }

        for (
            script in unpackedScripts
        ) {
            Regex(
                "https?://[^\\s\\\"'<>]+\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>]*)?",
                RegexOption.IGNORE_CASE,
            )
                .findAll(script)
                .map {
                    it.value.trimEnd(
                        ')',
                        ']',
                        '}',
                        ';',
                        ',',
                    )
                }
                .forEach(
                    mediaUrls::add
                )
        }

        val cleanMedia =
            mediaUrls
                .map {
                    it.replace(
                        "\\/",
                        "/",
                    )
                }
                .filter {
                    !it.contains(
                        "fragman",
                        true,
                    ) &&
                        !it.contains(
                            "trailer",
                            true,
                        )
                }
                .distinct()

        if (
            cleanMedia.isEmpty()
        ) {
            throw ErrorLoadingException(
                "CloseLoad m3u8 bulunamadı"
            )
        }

        cleanMedia.forEach { mediaUrl ->

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mediaUrl,
                    type = INFER_TYPE,
                ) {
                    quality =
                        if (
                            mediaUrl.contains(
                                "1080",
                                true,
                            )
                        ) {
                            Qualities.P1080.value
                        } else {
                            Qualities.Unknown.value
                        }

                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to url,
                        "Origin" to mainUrl,
                    )
                }
            )
        }
    }
}
