package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * DiziKorea provider
 *
 * Site:
 * https://dizikorea3.com/
 *
 * Destek:
 * - Dizi
 * - Film
 * - Sezon / Bölüm
 * - Vidmoly
 * - Filemoon
 * - Vmbox
 * - Dplayer
 * - M3U8
 * - DASH
 * - Altyazı
 */
class DiziKorea : MainAPI() {

    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    /**
     * Medya adayı.
     *
     * url:
     * gerçek video / player URL
     *
     * referer:
     * bu URL'nin bulunduğu sayfa
     */
    private data class Candidate(
        val url: String,
        val referer: String,
    )

    // =========================================================
    // ANA SAYFA
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/kore-dizileri-izle-dq1/sayfa/1" to "Kore Dizileri",
        "$mainUrl/cin-dizileri/sayfa/1" to "Çin Dizileri",
        "$mainUrl/japon-dizileri/sayfa/1" to "Japon Dizileri",
        "$mainUrl/tayland-dizileri/sayfa/1" to "Tayland Dizileri",
        "$mainUrl/tayvan-dizileri/sayfa/1" to "Tayvan Dizileri",
        "$mainUrl/filipin-dizileri/sayfa/1" to "Filipin Dizileri",
        "$mainUrl/filmler/sayfa/1" to "Filmler",
        "$mainUrl/dizi-arsivi/sayfa/1" to "Dizi Arşivi",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {

        val url = withPage(
            request.data,
            page
        )

        val document = app.get(
            url,
            headers = requestHeaders,
        ).document

        val results = document
            .select("a[href]")
            .mapNotNull {
                it.toSearchResponse()
            }
            .distinctBy {
                it.url
            }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty(),
        )
    }

    // =========================================================
    // ARAMA
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val candidates = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?query=$encoded",
            "$mainUrl/ara?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
        )

        for (url in candidates) {

            val document = runCatching {
                app.get(
                    url,
                    headers = requestHeaders,
                ).document
            }.getOrNull() ?: continue

            val results = document
                .select("a[href]")
                .mapNotNull {
                    it.toSearchResponse()
                }
                .distinctBy {
                    it.url
                }

            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            headers = requestHeaders,
        ).document

        val normalized = url.lowercase()

        // -----------------------------------------------------
        // BAŞLIK
        // -----------------------------------------------------

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()
            ?: return null

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        /**
         * DİZİ:
         *
         * En güvenilir yöntem doğrudan:
         *
         * /assets/uploads/series/{slug}.webp
         * /assets/uploads/series/{slug}.jpg
         *
         * şeklindeki gerçek dizi posterini bulmak.
         *
         * Böylece:
         *
         * Haftanın Trendleri
         * ilk img
         * sidebar img
         * footer img
         *
         * kesinlikle kullanılmıyor.
         */
        val poster = when {

            "/dizi/" in normalized -> {
                findSeriesPoster(url)
            }

            else -> {
                findMoviePoster(
                    document = document,
                    title = title,
                )
            }
        }

        // -----------------------------------------------------
        // KONUSU
        // -----------------------------------------------------

        val plot =
            document
                .selectFirst(
                    "meta[name='description']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        ".description, .plot, article p, main p"
                    )
                    ?.text()
                    ?.trim()

        // -----------------------------------------------------
        // YIL
        // -----------------------------------------------------

        val text = document.text()

        val year = Regex(
            "\\b(?:19|20)\\d{2}\\b"
        )
            .find(text)
            ?.value
            ?.toIntOrNull()

        // -----------------------------------------------------
        // PUAN
        // -----------------------------------------------------

        val score = Regex(
            "(?i)(?:IMDb|IMDB|puan)\\s*[★:]?\\s*([0-9]+(?:[.,][0-9]+)?)"
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        // =====================================================
        // FILM
        // =====================================================

        if ("/film/" in normalized) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url,
            ) {

                posterUrl = poster

                this.plot = plot

                this.year = year

                score?.let {
                    this.score = Score.from10(it)
                }
            }
        }

        // =====================================================
        // DİZİ
        // =====================================================

        if ("/dizi/" in normalized) {

            /**
             * ÇOK ÖNEMLİ:
             *
             * parseEpisodes içine yalnızca gerçek
             * dizi posterini gönderiyoruz.
             *
             * Bölümün kendi img'sine bakılmıyor.
             *
             * Böylece:
             *
             * Bölüm 1 -> DİZİ POSTERİ
             * Bölüm 2 -> DİZİ POSTERİ
             * Bölüm 3 -> DİZİ POSTERİ
             * ...
             */
            val episodes = parseEpisodes(
                document = document,
                seriesPoster = poster,
            )

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
            ) {

                posterUrl = poster

                this.plot = plot

                this.year = year

                score?.let {
                    this.score = Score.from10(it)
                }
            }
        }

        return null
    }

    // =========================================================
    // DİZİ POSTERİ
    // =========================================================

    /**
     * DiziKorea'daki DİZİ POSTERİNİ doğrudan
     * assets/uploads/series/ klasöründen bulur.
     *
     * Örnek:
     *
     * /dizi/the-hidden-shadow-izle
     *
     * ->
     *
     * /assets/uploads/series/the-hidden-shadow.webp
     *
     *
     * Örnek:
     *
     * /dizi/my-bias-my-boss-izle-dq6
     *
     * ->
     *
     * /assets/uploads/series/my-bias-my-boss.jpg
     *
     * Bu yöntem Trendler bölümünü tamamen devre dışı bırakır.
     */
    private suspend fun findSeriesPoster(
        pageUrl: String,
    ): String? {

        // -----------------------------------------------------
        // /dizi/ sonrasını al
        // -----------------------------------------------------

        var slug = pageUrl
            .substringAfter(
                "/dizi/",
                ""
            )
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .lowercase()

        if (slug.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // -izle-dq6
        // -izle-dq2
        // -izle
        //
        // gibi son ekleri kaldır.
        // -----------------------------------------------------

        slug = slug.replace(
            Regex(
                "(?i)-izle(?:-dq\\d+)?$"
            ),
            ""
        )

        if (slug.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // Muhtemel uzantılar
        // -----------------------------------------------------

        val extensions = listOf(
            "webp",
            "jpg",
            "jpeg",
            "png",
        )

        // -----------------------------------------------------
        // Gerçek dosyanın var olup olmadığını kontrol et.
        //
        // app.get() 404 / başarısız olduğunda sonraki
        // uzantıya geçiyoruz.
        // -----------------------------------------------------

        for (extension in extensions) {

            val posterUrl =
                "$mainUrl/assets/uploads/series/$slug.$extension"

            val found = runCatching {

                val response = app.get(
                    posterUrl,
                    headers = requestHeaders,
                )

                response.code in 200..299

            }.getOrDefault(false)

            if (found) {

                return posterUrl
            }
        }

        /**
         * Direkt poster bulunamazsa:
         *
         * Sadece başlıkla eşleşen bir img'yi dene.
         *
         * ASLA document.selectFirst("img")
         * kullanılmıyor.
         */
        return null
    }

    // =========================================================
    // FİLM POSTERİ
    // =========================================================

    /**
     * Film için de ilk resmi almıyoruz.
     *
     * Öncelik:
     *
     * 1. alt = başlık
     * 2. og:image
     * 3. title = başlık
     * 4. detay alanındaki img
     */
    private fun findMoviePoster(
        document: Document,
        title: String,
    ): String? {

        val normalizedTitle =
            normalizePosterText(title)

        // -----------------------------------------------------
        // 1. ALT eşleşmesi
        // -----------------------------------------------------

        val altPoster = document
            .select("img[alt]")
            .asSequence()
            .firstOrNull { img ->

                val alt =
                    normalizePosterText(
                        img.attr("alt")
                    )

                alt.isNotBlank() &&
                    (
                        alt == normalizedTitle ||
                            alt.contains(normalizedTitle) ||
                            normalizedTitle.contains(alt)
                        )
            }
            ?.let {
                posterSource(it)
            }
            ?.takeIf {
                isValidPosterUrl(it)
            }

        if (altPoster != null) {

            return fixUrl(
                altPoster
            )
        }

        // -----------------------------------------------------
        // 2. OG IMAGE
        // -----------------------------------------------------

        val ogImage = document
            .selectFirst(
                "meta[property='og:image']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                isValidPosterUrl(it)
            }

        if (ogImage != null) {

            return fixUrl(
                ogImage
            )
        }

        // -----------------------------------------------------
        // 3. TITLE attribute
        // -----------------------------------------------------

        val titlePoster = document
            .select("img[title]")
            .asSequence()
            .firstOrNull { img ->

                val imageTitle =
                    normalizePosterText(
                        img.attr("title")
                    )

                imageTitle.isNotBlank() &&
                    (
                        imageTitle == normalizedTitle ||
                            imageTitle.contains(normalizedTitle) ||
                            normalizedTitle.contains(imageTitle)
                        )
            }
            ?.let {
                posterSource(it)
            }
            ?.takeIf {
                isValidPosterUrl(it)
            }

        if (titlePoster != null) {

            return fixUrl(
                titlePoster
            )
        }

        // -----------------------------------------------------
        // 4. Detay alanları
        // -----------------------------------------------------

        val selectors = listOf(
            "main img",
            "article img",
            ".detail img",
            ".details img",
            ".movie-detail img",
            ".poster img",
            ".cover img",
        )

        for (selector in selectors) {

            val poster = document
                .select(selector)
                .asSequence()
                .mapNotNull {
                    posterSource(it)
                }
                .firstOrNull {
                    isValidPosterUrl(it)
                }

            if (poster != null) {

                return fixUrl(
                    poster
                )
            }
        }

        // -----------------------------------------------------
        // BULAMAZSA NULL
        //
        // Yanlışlıkla Trendler afişi vermiyoruz.
        // -----------------------------------------------------

        return null
    }

    // =========================================================
    // LINKLER
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        val candidates =
            LinkedHashMap<String, Candidate>()

        val scannedPages =
            HashSet<String>()

        // -----------------------------------------------------
        // Aday URL ekle
        // -----------------------------------------------------

        fun addCandidate(
            rawUrl: String?,
            referer: String,
        ) {

            if (rawUrl.isNullOrBlank()) {
                return
            }

            var url = decodeEmbeddedText(
                rawUrl.trim()
            )

            // -------------------------------------------------
            // JS escape
            // -------------------------------------------------

            url = url
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\u002F",
                    "/",
                    ignoreCase = true
                )
                .replace(
                    "\\u003A",
                    ":",
                    ignoreCase = true
                )
                .replace(
                    "\\u0026",
                    "&",
                    ignoreCase = true
                )

            // -------------------------------------------------
            // //example.com
            // -------------------------------------------------

            if (url.startsWith("//")) {

                url = "https:$url"

            } else if (
                url.startsWith("/")
            ) {

                url = fixUrl(url)
            }

            // -------------------------------------------------
            // HTTP kontrol
            // -------------------------------------------------

            if (
                !url.startsWith(
                    "http://"
                ) &&
                !url.startsWith(
                    "https://"
                )
            ) {
                return
            }

            // -------------------------------------------------
            // Son karakter temizliği
            // -------------------------------------------------

            url = url.trimEnd(
                ')',
                ']',
                ';',
                ',',
                '"',
                '\''
            )

            if (url.isBlank()) {
                return
            }

            // -------------------------------------------------
            // Tekrar kontrolü
            // -------------------------------------------------

            candidates.putIfAbsent(
                url,
                Candidate(
                    url = url,
                    referer = referer,
                )
            )
        }

        // -----------------------------------------------------
        // HTML tara
        // -----------------------------------------------------

        fun scanHtml(
            html: String,
            referer: String,
        ) {

            var decoded =
                decodeEmbeddedText(html)

            // -------------------------------------------------
            // \uXXXX
            // -------------------------------------------------

            decoded = decoded.replace(
                Regex(
                    """\\u([0-9a-fA-F]{4})"""
                )
            ) { match ->

                runCatching {

                    match
                        .groupValues[1]
                        .toInt(16)
                        .toChar()
                        .toString()

                }.getOrDefault(
                    match.value
                )
            }

            // -------------------------------------------------
            // \xXX
            // -------------------------------------------------

            decoded = decoded.replace(
                Regex(
                    """\\x([0-9a-fA-F]{2})"""
                )
            ) { match ->

                runCatching {

                    match
                        .groupValues[1]
                        .toInt(16)
                        .toChar()
                        .toString()

                }.getOrDefault(
                    match.value
                )
            }

            // -------------------------------------------------
            // Genel HTTP
            // -------------------------------------------------

            Regex(
                """https?://[^\s"'<>\\]+"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            // -------------------------------------------------
            // Vmbox
            // -------------------------------------------------

            Regex(
                """(?i)https?://[a-z0-9.-]+\.vmbox\.space[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            // -------------------------------------------------
            // Dplayer
            // -------------------------------------------------

            Regex(
                """(?i)https?://[a-z0-9.-]*dplayer82\.site[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            // -------------------------------------------------
            // M3U8
            // -------------------------------------------------

            Regex(
                """(?i)https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }

            // -------------------------------------------------
            // master.m3u8
            // -------------------------------------------------

            Regex(
                """(?i)https?://[^\s"'<>\\]+/master\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {

                    addCandidate(
                        it.value,
                        referer
                    )
                }
        }

        // -----------------------------------------------------
        // Element tara
        // -----------------------------------------------------

        fun scanElement(
            element: Element,
            referer: String,
        ) {

            val attributes = listOf(
                "href",
                "src",
                "data-src",
                "data-url",
                "data-href",
                "data-link",
                "data-video",
                "data-iframe",
                "data-embed",
                "data-player",
                "data-file",
                "data-stream",
                "data-source",
                "onclick",
            )

            for (attribute in attributes) {

                val value = element
                    .attr(attribute)
                    .trim()

                if (value.isBlank()) {
                    continue
                }

                addCandidate(
                    value,
                    referer
                )

                Regex(
                    """https?://[^\s"'<>\\]+"""
                )
                    .findAll(
                        decodeEmbeddedText(value)
                    )
                    .forEach {

                        addCandidate(
                            it.value,
                            referer
                        )
                    }
            }
        }

        // =====================================================
        // 1. BÖLÜM SAYFASI
        // =====================================================

        val response = runCatching {

            app.get(
                data,
                headers = requestHeaders + mapOf(
                    "Referer" to mainUrl
                )
            )

        }.getOrNull()
            ?: return false

        val document =
            response.document

        // -----------------------------------------------------
        // HTML
        // -----------------------------------------------------

        scanHtml(
            response.text,
            data
        )

        // -----------------------------------------------------
        // Elementler
        // -----------------------------------------------------

        document
            .select(
                "iframe, a, button, video, source, script, " +
                    "template, noscript, " +
                    "[data-url], [data-href], [data-src], " +
                    "[data-video], [data-player], [data-iframe], " +
                    "[data-embed], [data-link], [onclick]"
            )
            .forEach {

                scanElement(
                    it,
                    data
                )
            }

        // -----------------------------------------------------
        // Script
        // -----------------------------------------------------

        document
            .select(
                "script, template, noscript"
            )
            .forEach {

                scanHtml(
                    it.data(),
                    data
                )

                scanHtml(
                    it.html(),
                    data
                )
            }

        // =====================================================
        // 2. PLAYER SAYFALARI
        // =====================================================

        val initialCandidates =
            candidates.values.toList()

        for (candidate in initialCandidates) {

            val url = candidate.url

            val lower =
                url.lowercase()

            // -------------------------------------------------
            // Direkt medya ise açma
            // -------------------------------------------------

            if (isMediaUrl(url)) {
                continue
            }

            // -------------------------------------------------
            // Provider kontrol
            // -------------------------------------------------

            val isPlayer =
                lower.contains(
                    "vidmoly"
                ) ||
                    lower.contains(
                        "filemoon"
                    ) ||
                    lower.contains(
                        "dplayer82.site"
                    ) ||
                    lower.contains(
                        "vmbox.space"
                    )

            if (!isPlayer) {
                continue
            }

            if (!scannedPages.add(url)) {
                continue
            }

            // -------------------------------------------------
            // Player aç
            // -------------------------------------------------

            val playerResponse =
                runCatching {

                    app.get(
                        url,
                        headers = requestHeaders + mapOf(
                            "Referer" to candidate.referer
                        )
                    )

                }.getOrNull()
                    ?: continue

            // -------------------------------------------------
            // Player HTML
            // -------------------------------------------------

            scanHtml(
                playerResponse.text,
                url
            )

            val playerDocument =
                playerResponse.document

            // -------------------------------------------------
            // Player elementleri
            // -------------------------------------------------

            playerDocument
                .select(
                    "iframe, video, source, script, " +
                        "[src], [href], " +
                        "[data-src], [data-url], " +
                        "[data-video], " +
                        "[data-player], " +
                        "[data-iframe], " +
                        "[data-embed]"
                )
                .forEach {

                    scanElement(
                        it,
                        url
                    )
                }

            // -------------------------------------------------
            // Player script
            // -------------------------------------------------

            playerDocument
                .select(
                    "script, template, noscript"
                )
                .forEach {

                    scanHtml(
                        it.data(),
                        url
                    )

                    scanHtml(
                        it.html(),
                        url
                    )
                }
        }

        // =====================================================
        // 3. DOĞRUDAN MEDYA
        // =====================================================

        var found = false

        for (candidate in candidates.values) {

            val url =
                candidate.url

            if (!isMediaUrl(url)) {
                continue
            }

            val lower =
                url.lowercase()

            val type = when {

                lower.contains(
                    ".m3u8"
                ) ||
                    lower.contains(
                        "/master.m3u8"
                    ) ||
                    lower.contains(
                        "/hls/"
                    ) -> {

                    ExtractorLinkType.M3U8
                }

                lower.contains(
                    ".mpd"
                ) -> {

                    ExtractorLinkType.DASH
                }

                else -> {

                    ExtractorLinkType.VIDEO
                }
            }

            val mediaReferer =
                candidate.referer
                    .ifBlank {
                        data
                    }

            callback(
                newExtractorLink(
                    source = name,
                    name = hostLabel(url),
                    url = url,
                    type = type,
                ) {

                    referer =
                        mediaReferer

                    quality = when {

                        lower.contains(
                            "1080"
                        ) -> {
                            Qualities.P1080.value
                        }

                        lower.contains(
                            "720"
                        ) -> {
                            Qualities.P720.value
                        }

                        lower.contains(
                            "480"
                        ) -> {
                            Qualities.P480.value
                        }

                        lower.contains(
                            "360"
                        ) -> {
                            Qualities.P360.value
                        }

                        else -> {
                            Qualities.Unknown.value
                        }
                    }

                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mediaReferer,
                        "Accept" to "*/*",
                        "Accept-Language" to
                            "tr-TR,tr;q=0.9,en;q=0.8",
                    )
                }
            )

            found = true
        }

        // =====================================================
        // 4. VIDMOLY / FILEMOON
        // =====================================================

        for (candidate in candidates.values) {

            val url =
                candidate.url

            val lower =
                url.lowercase()

            if (isMediaUrl(url)) {
                continue
            }

            val supportedExtractor =
                lower.contains(
                    "vidmoly"
                ) ||
                    lower.contains(
                        "vidmoly.net"
                    ) ||
                    lower.contains(
                        "vidmoly.to"
                    ) ||
                    lower.contains(
                        "vidmoly.me"
                    ) ||
                    lower.contains(
                        "filemoon"
                    ) ||
                    lower.contains(
                        "filemoon.sx"
                    )

            if (!supportedExtractor) {
                continue
            }

            val ok = runCatching {

                loadExtractor(
                    url,
                    candidate.referer,
                    subtitleCallback,
                    callback,
                )

            }.getOrDefault(false)

            if (ok) {
                found = true
            }
        }

        // =====================================================
        // 5. ALTYAZILAR
        // =====================================================

        document
            .select(
                "track[src], track[data-src]"
            )
            .forEach { track ->

                val subtitleUrl =
                    track
                        .attr("src")
                        .ifBlank {
                            track.attr(
                                "data-src"
                            )
                        }
                        .trim()

                if (
                    subtitleUrl.isNotBlank()
                ) {

                    subtitleCallback(
                        newSubtitleFile(
                            track.attr(
                                "label"
                            ).ifBlank {
                                "Türkçe"
                            },
                            fixUrl(
                                subtitleUrl
                            ),
                        )
                    )
                }
            }

        return found
    }

    // =========================================================
    // EPISODE PARSER
    // =========================================================

    /**
     * Bölümleri alır.
     *
     * KRİTİK:
     *
     * Bölüm içindeki img'leri BURADA KULLANMIYORUZ.
     *
     * posterUrl doğrudan seriesPoster.
     *
     * Yani:
     *
     * Episode 1 -> gerçek dizi posteri
     * Episode 2 -> gerçek dizi posteri
     * Episode 3 -> gerçek dizi posteri
     *
     * Trendler bölümüyle hiçbir bağlantı kalmıyor.
     */
    private fun parseEpisodes(
        document: Document,
        seriesPoster: String?,
    ): List<Episode> {

        return document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()

                val absolute =
                    fixUrlNull(href)
                        ?: return@mapNotNull null

                val match = Regex(
                    "(?i)/sezon-(\\d+)/bolum-(\\d+)"
                )
                    .find(absolute)
                    ?: return@mapNotNull null

                val season =
                    match
                        .groupValues[1]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val episode =
                    match
                        .groupValues[2]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val label =
                    element
                        .text()
                        .trim()
                        .ifBlank {
                            "$episode. Bölüm"
                        }

                newEpisode(
                    absolute
                ) {

                    name = label

                    this.season =
                        season

                    this.episode =
                        episode

                    /**
                     * SADECE gerçek dizi posteri.
                     *
                     * Fallback yok.
                     *
                     * Bu çok önemli.
                     */
                    posterUrl =
                        seriesPoster
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 0
                }.thenBy {
                    it.episode ?: 0
                }
            )
    }

    // =========================================================
    // SEARCH RESPONSE
    // =========================================================

    private fun Element.toSearchResponse():
        SearchResponse? {

        val href =
            attr("href")
                .trim()

        val absolute =
            fixUrlNull(href)
                ?: return null

        val path =
            absolute.lowercase()

        // -----------------------------------------------------
        // Episode linklerini dışarıda bırak
        // -----------------------------------------------------

        if (
            path == mainUrl ||
            absolute.contains(
                "/sezon-",
                ignoreCase = true
            ) ||
            absolute.contains(
                "/bolum-",
                ignoreCase = true
            )
        ) {
            return null
        }

        // -----------------------------------------------------
        // Tip
        // -----------------------------------------------------

        val type = when {

            "/film/" in path -> {
                TvType.Movie
            }

            "/dizi/" in path -> {
                TvType.TvSeries
            }

            else -> {
                return null
            }
        }

        // -----------------------------------------------------
        // Başlık
        // -----------------------------------------------------

        val title =
            text()
                .trim()
                .ifBlank {

                    selectFirst("img")
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

        if (title.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // Poster
        //
        // SADECE BU A TAG'ININ İÇİNDEKİ IMG.
        //
        // Global document img araması yok.
        // -----------------------------------------------------

        val poster =
            selectFirst("img")
                ?.let {
                    posterOf(it)
                }

        // -----------------------------------------------------
        // Search response
        // -----------------------------------------------------

        return when (type) {

            TvType.Movie -> {

                newMovieSearchResponse(
                    title,
                    absolute,
                    TvType.Movie,
                ) {

                    posterUrl =
                        poster
                }
            }

            else -> {

                newTvSeriesSearchResponse(
                    title,
                    absolute,
                    TvType.TvSeries,
                ) {

                    posterUrl =
                        poster
                }
            }
        }
    }

    // =========================================================
    // POSTER HELPERS
    // =========================================================

    /**
     * Element içindeki gerçek img kaynağını bulur.
     */
    private fun posterOf(
        element: Element
    ): String? {

        val img =
            if (
                element.tagName()
                    .equals(
                        "img",
                        ignoreCase = true
                    )
            ) {
                element
            } else {
                element.selectFirst("img")
                    ?: return null
            }

        return posterSource(img)
            ?.let(::fixUrl)
    }

    /**
     * Lazy-loading poster alanları.
     *
     * Önemli:
     *
     * İlk global img yok.
     *
     * Bu fonksiyon sadece kendisine verilen
     * img elementinde çalışır.
     */
    private fun posterSource(
        element: Element
    ): String? {

        val values = listOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("data-poster"),
            element.attr("data-fallback"),
            element.attr("src"),
        )

        return values
            .asSequence()
            .map {
                it.trim()
            }
            .firstOrNull {
                isValidPosterUrl(it)
            }
    }

    /**
     * Poster geçerliliği.
     */
    private fun isValidPosterUrl(
        value: String?
    ): Boolean {

        if (value.isNullOrBlank()) {
            return false
        }

        val url =
            value.trim()

        if (
            url == "#" ||
            url.equals(
                "about:blank",
                ignoreCase = true
            )
        ) {
            return false
        }

        val lower =
            url.lowercase()

        val forbidden = listOf(
            "placeholder",
            "blank.gif",
            "transparent.gif",
            "spacer.gif",
            "pixel.gif",
            "data:image/gif;base64",
        )

        if (
            forbidden.any {
                lower.contains(it)
            }
        ) {
            return false
        }

        return true
    }

    /**
     * Başlık normalizasyonu.
     */
    private fun normalizePosterText(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex(
                    """[^a-z0-9çğıöşü]+"""
                ),
                ""
            )
    }

    // =========================================================
    // MEDIA URL
    // =========================================================

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return Regex(
            """(?i)\.(m3u8|mpd|mp4)(?:$|[?#])"""
        )
            .containsMatchIn(
                value
            ) ||

            value.contains(
                ".urlset/master.m3u8"
            ) ||

            value.contains(
                "/master.m3u8"
            ) ||

            (
                value.contains(
                    "/hls/"
                ) &&
                    (
                        value.contains(
                            ".m3u8"
                        ) ||
                            value.contains(
                                "master"
                            )
                        )
                )
    }

    // =========================================================
    // EXTERNAL PLAYER
    // =========================================================

    private fun isExternalPlayer(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(
            "vidmoly"
        ) ||
            value.contains(
                "filemoon"
            ) ||
            value.contains(
                "vidmolyme"
            ) ||
            value.contains(
                "vidmolyto"
            ) ||
            value.contains(
                "vidmolybiz"
            ) ||
            value.contains(
                "vidmoly.net"
            ) ||
            value.contains(
                "dplayer82.site"
            ) ||
            value.contains(
                "vmbox.space"
            )
    }

    // =========================================================
    // EMBEDDED TEXT
    // =========================================================

    private fun decodeEmbeddedText(
        value: String
    ): String {

        return value
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "\\\"",
                "\""
            )
            .replace(
                "\\'",
                "'"
            )
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true
            )
            .replace(
                "\\u003A",
                ":",
                ignoreCase = true
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true
            )
            .replace(
                "&quot;",
                "\"",
                ignoreCase = true
            )
            .replace(
                "&#x2F;",
                "/",
                ignoreCase = true
            )
            .replace(
                "&#47;",
                "/",
                ignoreCase = true
            )
            .replace(
                "\n",
                " "
            )
            .replace(
                "\r",
                " "
            )
    }

    // =========================================================
    // HOST
    // =========================================================

    private fun hostLabel(
        url: String
    ): String {

        return runCatching {

            URI(url).host

        }
            .getOrNull()
            ?.ifBlank {
                "DiziKorea"
            }
            ?: "DiziKorea"
    }

    // =========================================================
    // PAGINATION
    // =========================================================

    private fun withPage(
        url: String,
        page: Int
    ): String {

        if (page <= 1) {
            return url
        }

        return if (
            url.contains("?")
        ) {

            "$url&page=$page"

        } else {

            "$url?page=$page"
        }
    }
}
