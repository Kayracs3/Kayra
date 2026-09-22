package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

data class Results(
    @JsonProperty("results")
    val results: List<String>
)

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 " +
            "(Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 " +
            "(KHTML, like Gecko) " +
            "Chrome/120.0.0.0"

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-5" to "Yeni Eklenen Diziler",
        "$mainUrl/category/tavsiye-filmler-izle3" to "Tavsiye Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmle-2r" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle-4" to "En Çok Beğenilenler",
        "$mainUrl/tur/aile-filmleri-izleyin-7" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-8" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmlerini-izleyin-5" to "Animasyon",
        "$mainUrl/tur/belgesel-filmlerini-izle-2" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-5" to "Bilim Kurgu",
        "$mainUrl/tur/komedi-filmlerini-izleyin-2" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmlerini-izle-9/" to "Korku Filmleri",
        "$mainUrl/tur/romantik-filmleri-izle-3" to "Romantik Filmleri"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val home = document
            .select("div.section-content a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this
            .selectFirst("strong.poster-title")?.text()
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?q=$query",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "User-Agent" to userAgent
            )
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val titleText = document.selectFirst("h4.title")?.text()
                ?: return@mapNotNull null

            val hrefText = fixUrlNull(
                document.selectFirst("a")?.attr("href")
            ) ?: return@mapNotNull null

            val imgEl = document.selectFirst("img")
            val srcText = fixUrlNull(imgEl?.attr("src"))
                ?: fixUrlNull(imgEl?.attr("data-src"))

            newMovieSearchResponse(titleText, hrefText, TvType.Movie) {
                this.posterUrl = srcText?.replace("/thumb/", "/list/")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val isTvSeries = !document.select("div.seasons").isEmpty()

        return if (isTvSeries) {
            newTvSeriesLoadResponse(
                name = document.selectFirst("h1.section-title")?.text()
                    ?.substringBefore(" izle") ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = document.select("div.seasons-tab-content a")
                    .mapNotNull {
                        val epName = it.selectFirst("h4")?.text()?.trim()
                            ?: return@mapNotNull null

                        newEpisode(fixUrlNull(it.attr("href")) ?: "") {
                            this.name = epName

                            this.season = Regex("""(\d+)\. ?Sezon""")
                                .find(epName)?.groupValues?.get(1)
                                ?.toIntOrNull() ?: 1

                            this.episode = Regex("""(\d+)\. ?Bölüm""")
                                .find(epName)?.groupValues?.get(1)
                                ?.toIntOrNull()
                        }
                    }
            ) {
                this.posterUrl = fixUrlNull(
                    document
                        .select("aside.post-info-poster img.lazyload")
                        .lastOrNull()
                        ?.attr("data-src")
                )

                this.year = document.selectFirst(
                    "div.post-info-year-country a"
                )?.text()?.trim()?.toIntOrNull()

                this.plot = document.selectFirst(
                    "article.post-info-content > p"
                )?.text()?.trim()

                this.tags = document
                    .select("div.post-info-genres a")
                    .map { it.text() }

                this.actors = document
                    .select("div.post-info-cast a")
                    .mapNotNull {
                        ActorData(
                            Actor(
                                it.selectFirst("strong")?.text()
                                    ?: return@mapNotNull null,
                                it.selectFirst("img")?.attr("data-src")
                            ),
                            null,
                            null
                        )
                    }

                this.recommendations = document
                    .select("div.section-slider-container div.slider-slide")
                    .mapNotNull {
                        newTvSeriesSearchResponse(
                            it.selectFirst("a")?.attr("title")
                                ?: return@mapNotNull null,
                            fixUrlNull(
                                it.selectFirst("a")?.attr("href")
                            ) ?: return@mapNotNull null,
                            TvType.TvSeries
                        ) {
                            this.posterUrl =
                                fixUrlNull(
                                    it.selectFirst("img")?.attr("data-src")
                                ) ?: fixUrlNull(
                                    it.selectFirst("img")?.attr("src")
                                )
                        }
                    }
            }
        } else {
            newMovieLoadResponse(
                name = document.selectFirst("h1.section-title")?.text()
                    ?.substringBefore(" izle") ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = fixUrlNull(
                    document
                        .select("aside.post-info-poster img.lazyload")
                        .lastOrNull()
                        ?.attr("data-src")
                )

                this.year = document.selectFirst(
                    "div.post-info-year-country a"
                )?.text()?.trim()?.toIntOrNull()

                this.plot = document.selectFirst(
                    "article.post-info-content > p"
                )?.text()?.trim()

                this.tags = document
                    .select("div.post-info-genres a")
                    .map { it.text() }

                this.actors = document
                    .select("div.post-info-cast a")
                    .mapNotNull {
                        ActorData(
                            Actor(
                                it.selectFirst("strong")?.text()
                                    ?: return@mapNotNull null,
                                it.selectFirst("img")?.attr("data-src")
                            ),
                            null,
                            null
                        )
                    }

                this.recommendations = document
                    .select("div.section-slider-container div.slider-slide")
                    .mapNotNull {
                        newTvSeriesSearchResponse(
                            it.selectFirst("a")?.attr("title")
                                ?: return@mapNotNull null,
                            fixUrlNull(
                                it.selectFirst("a")?.attr("href")
                            ) ?: return@mapNotNull null,
                            TvType.TvSeries
                        ) {
                            this.posterUrl =
                                fixUrlNull(
                                    it.selectFirst("img")?.attr("data-src")
                                ) ?: fixUrlNull(
                                    it.selectFirst("img")?.attr("src")
                                )
                        }
                    }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        var linkFound = false

        val elements = document.select("iframe")

        Log.d(
            "HDFilmCehennemi",
            "Bulunan iframe sayisi: ${elements.size}"
        )

        for (element in elements) {
            val rawSrc = sequenceOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-frame")
            ).firstOrNull { it.isNotBlank() }

            if (rawSrc.isNullOrBlank()) {
                Log.d("HDFilmCehennemi", "Iframe src bulunamadi")
                continue
            }

            val fixedUrl = fixUrl(rawSrc)

            Log.d(
                "HDFilmCehennemi",
                "Player URL: $fixedUrl"
            )

            try {
                val handled = if (
                    fixedUrl.contains("hdfilmcehennemi.mobi/video/embed", ignoreCase = true) ||
                    fixedUrl.contains("rapidrame_id=", ignoreCase = true)
                ) {
                    extractRapidrame(
                        playerUrl = fixedUrl,
                        siteReferer = data,
                        callback = callback
                    )
                } else {
                    loadExtractor(
                        fixedUrl,
                        data,
                        subtitleCallback,
                        callback
                    )
                }

                if (handled) {
                    linkFound = true
                    Log.d(
                        "HDFilmCehennemi",
                        "Kaynak bulundu: $fixedUrl"
                    )
                } else {
                    Log.d(
                        "HDFilmCehennemi",
                        "Kaynak bulunamadi: $fixedUrl"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HDFilmCehennemi",
                    "Link hatasi ($fixedUrl): ${e.message}",
                    e
                )
            }
        }

        Log.d(
            "HDFilmCehennemi",
            "loadLinks tamamlandi. linkFound=$linkFound"
        )

        return linkFound
    }

    private fun candidatesFirstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun rot13(input: String): String {
        return buildString(input.length) {
            input.forEach { c ->
                when {
                    c in 'a'..'z' -> append(('a'.code + (c.code - 'a'.code + 13) % 26).toChar())
                    c in 'A'..'Z' -> append(('A'.code + (c.code - 'A'.code + 13) % 26).toChar())
                    else -> append(c)
                }
            }
        }
    }

    private fun characterUnmix(input: String): String {
        return buildString(input.length) {
            input.forEachIndexed { index, c ->
                val code = (c.code - (399756995L % (index + 5)) + 256) % 256
                append(code.toChar())
            }
        }
    }

    private fun decodeBase64Latin1(input: String): String {
        val clean = input
            .replace("\\n", "")
            .replace("\\r", "")
            .trim()
        return String(Base64.decode(clean, Base64.DEFAULT), Charsets.ISO_8859_1)
    }

    private fun isValidVideoUrl(url: String): Boolean {
        return url.startsWith("https://") &&
                (url.contains(".m3u8", ignoreCase = true) ||
                 url.contains("/hls/", ignoreCase = true) ||
                 url.contains(".mp4", ignoreCase = true))
    }

    private fun decodeVideoVariant1(value: String): String {
        val reversed = value.reversed()
        val step1 = rot13(reversed)
        val step2 = decodeBase64Latin1(step1)
        return characterUnmix(step2)
    }

    private fun decodeVideoVariant2(value: String): String {
        val reversed = value.reversed()
        val step1 = decodeBase64Latin1(reversed)
        val step2 = rot13(step1)
        return characterUnmix(step2)
    }

    private fun decodeVideoVariant3(value: String): String {
        val step1 = decodeBase64Latin1(value)
        val step2 = step1.reversed()
        val step3 = rot13(step2)
        return characterUnmix(step3)
    }

    private fun readJsQuotedString(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length) return null
        val quote = text[start]
        if (quote != '\'' && quote != '"') return null

        val out = StringBuilder()
        var i = start + 1

        while (i < text.length) {
            val c = text[i]
            if (c == quote) return out.toString() to (i + 1)

            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                when (n) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'v' -> out.append('\u000B')
                    '0' -> out.append('\u0000')
                    '\\' -> out.append('\\')
                    '\'' -> out.append('\'')
                    '"' -> out.append('"')
                    'x' -> {
                        if (i + 3 < text.length) {
                            val hex = text.substring(i + 2, i + 4)
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 4
                            continue
                        }
                        out.append(n)
                    }
                    'u' -> {
                        if (i + 5 < text.length) {
                            val hex = text.substring(i + 2, i + 6)
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 6
                            continue
                        }
                        out.append(n)
                    }
                    else -> out.append(n)
                }
                i += 2
                continue
            }

            out.append(c)
            i++
        }

        return null
    }

    private fun skipJsSpace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun readJsNumber(text: String, start: Int): Pair<Int, Int>? {
        var i = skipJsSpace(text, start)
        val begin = i
        while (i < text.length && text[i].isDigit()) i++
        if (i == begin) return null
        return text.substring(begin, i).toIntOrNull()?.let { it to i }
    }

    private data class PackerArgs(
        val p: String,
        val a: Int,
        val c: Int,
        val k: List<String>
    )

    private fun findPackerArgs(text: String): PackerArgs? {
        val markerMatch = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)"""
        ).find(text) ?: return null

        val markerIndex = markerMatch.range.first

        // P.A.C.K.E.R'ın gövdesinden sonra gelen `(` açılışını bul.
        val bodyCall = Regex("""\}\s*\(""")
            .find(text, markerMatch.range.last + 1) ?: return null
        val callStart = bodyCall.range.last
        var i = skipJsSpace(text, callStart + 1)

        val pArg = readJsQuotedString(text, i) ?: return null
        val p = pArg.first
        i = skipJsSpace(text, pArg.second)
        if (i >= text.length || text[i] != ',') return null
        i = skipJsSpace(text, i + 1)

        val aArg = readJsNumber(text, i) ?: return null
        val a = aArg.first
        i = skipJsSpace(text, aArg.second)
        if (i >= text.length || text[i] != ',') return null
        i = skipJsSpace(text, i + 1)

        val cArg = readJsNumber(text, i) ?: return null
        val c = cArg.first
        i = skipJsSpace(text, cArg.second)
        if (i >= text.length || text[i] != ',') return null
        i = skipJsSpace(text, i + 1)

        val kArg = readJsQuotedString(text, i) ?: return null
        val kRaw = kArg.first

        // Normal Packer biçimi: "...".split("|") veya '...'.split('|')
        i = skipJsSpace(text, kArg.second)
        if (!text.startsWith(".split", i)) return null
        val pipeIndex = text.indexOf('|', i)
        if (pipeIndex < 0) return null

        return PackerArgs(
            p = p,
            a = a,
            c = c,
            k = kRaw.split('|')
        )
    }

    private fun packerEncode(value: Int, radix: Int): String {
        if (value == 0) return "0"
        if (radix < 2) return ""

        fun digitToString(digit: Int): String {
            return if (digit > 35) {
                (digit + 29).toChar().toString()
            } else {
                digit.toString(36)
            }
        }

        var n = value
        val out = StringBuilder()
        while (n > 0) {
            val digit = n % radix
            out.append(digitToString(digit))
            n /= radix
        }
        return out.reverse().toString()
    }

    private fun unpackHdfcJs(packed: String): String? {
        val args = findPackerArgs(packed) ?: return null

        Log.d(
            "HDFilmCehennemi",
            "Rapidrame packer parametreleri: a=${args.a} c=${args.c} k=${args.k.size} p=${args.p.length}"
        )

        if (args.a < 2 || args.a > 62 || args.c <= 0 || args.k.isEmpty()) return null

        // Dean Edwards P.A.C.K.E.R: token'ları radix'e göre üret, sözlükten değiştir.
        val wordRegex = Regex("""\b\w+\b""")
        var unpacked = args.p

        unpacked = wordRegex.replace(unpacked) { match ->
            val token = match.value
            var value = 0

            for (ch in token) {
                val digit = when {
                    ch in '0'..'9' -> ch - '0'
                    ch in 'a'..'z' -> ch.code - 'a'.code + 10
                    ch in 'A'..'Z' -> ch.code - 'A'.code + 36
                    else -> -1
                }

                if (digit < 0 || digit >= args.a) {
                    return@replace token
                }
                value = value * args.a + digit
            }

            if (value >= args.c || value >= args.k.size) {
                token
            } else {
                args.k[value].ifEmpty { token }
            }
        }

        return unpacked
    }

    private fun tryDecodeParts(
        parts: List<String>,
        sourceLabel: String
    ): String? {
        if (parts.isEmpty()) return null

        val value = parts.joinToString("")
        if (value.length < 16) return null

        val compact = value.replace(Regex("\\s+"), "")
        val looksBase64ish = compact.length >= 20 &&
                compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '_' || it == '-' }

        if (!looksBase64ish) return null

        val decoders = listOf(
            "v3" to ::decodeVideoVariant3,
            "v1" to ::decodeVideoVariant1,
            "v2" to ::decodeVideoVariant2
        )

        for ((name, decoder) in decoders) {
            try {
                val decoded = decoder(value)
                if (isValidVideoUrl(decoded)) {
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame encoded URL bulundu ($sourceLabel/$name): $decoded"
                    )
                    return decoded
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun decodePackedVideoUrl(text: String): String? {
        // 1) Public scraper'daki ana biçim: dc_xxx(["...","..."])
        val direct = Regex(
            """dc_[A-Za-z0-9_$]+\s*\(\s*\[([^\]]+)\]\s*\)"""
        ).find(text)

        if (direct != null) {
            val parts = Regex("""["']([^"']+)["']""")
                .findAll(direct.groupValues[1])
                .map { it.groupValues[1] }
                .toList()

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame packed parca sayisi (dc): ${parts.size}"
            )

            val decoded = tryDecodeParts(parts, "dc")
            if (decoded != null) return decoded
        }

        // Tanı: unpack edilmiş JS'de dc_ gerçekten var mı?
        val dcNames = Regex("""dc_[A-Za-z0-9_$]+""")
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()

        Log.d(
            "HDFilmCehennemi",
            "Rapidrame unpack dc_ isimleri: ${if (dcNames.isEmpty()) "YOK" else dcNames.joinToString(",")}")
        if (dcNames.isNotEmpty()) {
            val name = dcNames.first()
            val idx = text.indexOf(name)
            if (idx >= 0) {
                val from = (idx - 160).coerceAtLeast(0)
                val to = (idx + 700).coerceAtMost(text.length)
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame dc snippet: ${text.substring(from, to)}"
                )
            }
        }

        // 2) Fonksiyon adı değişmişse genel function([parts]) biçimini dene.
        val genericFunctions = Regex(
            """[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*\[([^\]]+)\]\s*\)"""
        ).findAll(text)

        for ((index, match) in genericFunctions.withIndex()) {
            val parts = Regex("""["']([^"']+)["']""")
                .findAll(match.groupValues[1])
                .map { it.groupValues[1] }
                .toList()

            if (parts.size >= 2) {
                val decoded = tryDecodeParts(parts, "generic#$index")
                if (decoded != null) return decoded
            }
        }

        // 3) Son sağlam fallback: JS içindeki string-array'leri tara.
        // Kaynağın ana URL'si parçalı bir base64 değeri olduğunda function adı bilinmese bile
        // bu tarama onu yakalayabilir. URL/track gibi açık metin dizileri base64ish filtreden geçmez.
        val arrayRegex = Regex(
            """\[((?:\s*["'][^"']+["']\s*,?){2,})\s*\]"""
        )

        for ((index, match) in arrayRegex.findAll(text).withIndex()) {
            val parts = Regex("""["']([^"']+)["']""")
                .findAll(match.groupValues[1])
                .map { it.groupValues[1] }
                .toList()

            if (parts.size < 2) continue

            val value = parts.joinToString("").replace(Regex("\\s+"), "")
            val looksBase64ish = value.length >= 20 &&
                    value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '_' || it == '-' }

            if (!looksBase64ish) continue

            val decoded = tryDecodeParts(parts, "array#$index")
            if (decoded != null) return decoded
        }

        return null
    }

    private suspend fun extractRapidrame(
        playerUrl: String,
        siteReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val rawHtml = app.get(
                playerUrl,
                referer = siteReferer,
                headers = mapOf("User-Agent" to userAgent)
            ).text

            val normalized = rawHtml
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame HTML uzunlugu: ${normalized.length}"
            )

            val document = Jsoup.parse(normalized)

            val scriptUrls = document
                .select("script[src]")
                .mapNotNull { it.attr("src").takeIf(String::isNotBlank) }
                .distinct()
                .take(15)

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame script sayisi: ${scriptUrls.size}"
            )

            scriptUrls.forEachIndexed { index, script ->
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame script[$index]: $script"
                )
            }

            val mediaElements = document.select("video, source, iframe")
            Log.d(
                "HDFilmCehennemi",
                "Rapidrame medya elementleri: ${mediaElements.size}"
            )

            mediaElements.take(20).forEachIndexed { index, element ->
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame media[$index]: tag=${element.tagName()} src=${element.attr("src")} data-src=${element.attr("data-src")} data-file=${element.attr("data-file")}"
                )
            }

            val keywords = listOf(
                "m3u8",
                "hls",
                "playlist",
                "jwplayer",
                "file",
                "source",
                "stream",
                "rapidrame"
            )

            keywords.forEach { keyword ->
                val lower = normalized.lowercase()
                val index = lower.indexOf(keyword.lowercase())

                if (index >= 0) {
                    val from = (index - 220).coerceAtLeast(0)
                    val to = (index + 420).coerceAtMost(normalized.length)
                    val snippet = normalized
                        .substring(from, to)
                        .replace("\n", " ")
                        .replace("\r", " ")

                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame keyword[$keyword]: $snippet"
                    )
                } else {
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame keyword[$keyword]: YOK"
                    )
                }
            }

            val packerMarker = normalized.indexOf("eval(function(p,a,c,k,e,d)")
            Log.d(
                "HDFilmCehennemi",
                "Rapidrame Packer marker: ${if (packerMarker >= 0) "VAR ($packerMarker)" else "YOK"}"
            )
            if (packerMarker >= 0) {
                val to = (packerMarker + 1400).coerceAtMost(normalized.length)
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame Packer snippet: ${normalized.substring(packerMarker, to).replace("\n", " ").replace("\r", " ")}"
                )
            }

            val customUnpacked = try {
                unpackHdfcJs(normalized)
            } catch (e: Exception) {
                Log.d("HDFilmCehennemi", "Rapidrame kendi unpack hatasi: ${e.message}")
                null
            }

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame kendi packer sonucu: ${if (customUnpacked != null) "BULUNDU" else "YOK"}"
            )

            if (customUnpacked != null) {
                val unpackSourceIdx = customUnpacked.indexOf("sources:")
                if (unpackSourceIdx >= 0) {
                    val from = (unpackSourceIdx - 250).coerceAtLeast(0)
                    val to = (unpackSourceIdx + 900).coerceAtMost(customUnpacked.length)
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame unpack sources snippet: ${customUnpacked.substring(from, to).replace("\n", " ").replace("\r", " ")}"
                    )
                }
            }

            val unpacked = customUnpacked ?: try {
                getAndUnpack(normalized)
            } catch (_: Exception) {
                normalized
            }

            if (unpacked != normalized) {
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame JS unpack sonrasi uzunluk: ${unpacked.length}"
                )

                val unpackedLower = unpacked.lowercase()
                val unpackedM3u8Index = unpackedLower.indexOf("m3u8")

                if (unpackedM3u8Index >= 0) {
                    val from = (unpackedM3u8Index - 300).coerceAtLeast(0)
                    val to = (unpackedM3u8Index + 600).coerceAtMost(unpacked.length)
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame unpack m3u8 snippet: ${unpacked.substring(from, to)}"
                    )
                } else {
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame unpack sonrasi da m3u8 yok"
                    )
                }
            }

            // Ana yöntem: Rapidrame'in packed JS içindeki dc_...( [parçalar] ) kaynağını çöz.
            val variableUrlRegex = Regex(
                """(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*[\"'](https://[^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            )

            for (text in listOf(normalized, unpacked).distinct()) {
                variableUrlRegex.findAll(text).forEach { m ->
                    val name = m.groupValues[1]
                    val url = m.groupValues[2]
                    if (isValidVideoUrl(url)) {
                        Log.d("HDFilmCehennemi", "Rapidrame acik degisken URL bulundu: $name=$url")
                        callback.invoke(
                            newExtractorLink(
                                source = "HDFilmCehennemi",
                                name = "Rapidrame",
                                url = url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = siteReferer
                                quality = Qualities.P1080.value
                                headers = mapOf("User-Agent" to userAgent)
                            }
                        )
                        return true
                    }
                }
            }

            // Kaynak değişkeni çoğu zaman şu yapıda geliyor:
            // var i35q = dc_xxx(["...", "..."])
            // Fonksiyon adı değişse bile atamayı yakala.
            val variablePackedRegex = Regex(
                """(?:(?:var|let|const)\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(\s*\[((?:[^\]"']|"[^"']*"|'[^']*')*)\]\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            )

            for (text in listOf(normalized, unpacked).distinct()) {
                variablePackedRegex.findAll(text).forEach { match ->
                    val variableName = match.groupValues[1]
                    val functionName = match.groupValues[2]
                    val parts = Regex("""[\"']([^\"']+)[\"']""")
                        .findAll(match.groupValues[3])
                        .map { it.groupValues[1] }
                        .toList()

                    if (parts.size >= 2) {
                        Log.d(
                            "HDFilmCehennemi",
                            "Rapidrame degisken packed: $variableName=$functionName(parts=${parts.size})"
                        )

                        val decoded = tryDecodeParts(parts, "assign/$variableName/$functionName")
                        if (decoded != null) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "HDFilmCehennemi",
                                    name = "Rapidrame",
                                    url = decoded,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = siteReferer
                                    quality = Qualities.P1080.value
                                    headers = mapOf("User-Agent" to userAgent)
                                }
                            )
                            return true
                        }
                    }
                }
            }

            val packedCandidates = listOf(normalized, unpacked)
            for (packedText in packedCandidates.distinct()) {
                val packedUrl = decodePackedVideoUrl(packedText)
                if (!packedUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "HDFilmCehennemi",
                            name = "Rapidrame",
                            url = packedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = siteReferer
                            quality = Qualities.P1080.value
                            headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                    return true
                }
            }

            // Ek tanı/fallback: fonksiyon adı dc_ ile değişmişse bile array içeriğini yakala.
            val genericPacked = Regex(
                """[A-Za-z_$][\w$]*\(\[([^\]]+)\]\)"""
            ).find(unpacked)

            if (genericPacked != null) {
                val parts = Regex("""["']([^"']+)["']""")
                    .findAll(genericPacked.groupValues[1])
                    .map { it.groupValues[1] }
                    .toList()

                if (parts.isNotEmpty()) {
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame generic packed parca sayisi: ${parts.size}"
                    )

                    val value = parts.joinToString("")
                    val decoders = listOf(
                        "v3" to ::decodeVideoVariant3,
                        "v1" to ::decodeVideoVariant1,
                        "v2" to ::decodeVideoVariant2
                    )

                    for ((name, decoder) in decoders) {
                        try {
                            val decoded = decoder(value)
                            if (isValidVideoUrl(decoded)) {
                                Log.d(
                                    "HDFilmCehennemi",
                                    "Rapidrame generic URL bulundu ($name): $decoded"
                                )
                                callback.invoke(
                                    newExtractorLink(
                                        source = "HDFilmCehennemi",
                                        name = "Rapidrame",
                                        url = decoded,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer = siteReferer
                                        quality = Qualities.P1080.value
                                        headers = mapOf("User-Agent" to userAgent)
                                    }
                                )
                                return true
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            // Son fallback: JSON-LD contentUrl. Bu değer bazı sayfalarda genel/stale olabilir,
            // dolayısıyla 404 alırsa bunu gerçek source olarak kabul etmiyoruz.
            val contentUrlRegex = Regex(
                """[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            )

            val contentUrl = contentUrlRegex.find(normalized)?.groupValues?.getOrNull(1)

            if (!contentUrl.isNullOrBlank()) {
                val finalContentUrl = contentUrl
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame contentUrl fallback: $finalContentUrl"
                )

                // contentUrl'i önce HEAD/GET ile doğrula; 404 ise yanlış fallback'i yayınlama.
                try {
                    val probe = app.get(
                        finalContentUrl,
                        referer = playerUrl,
                        headers = mapOf("User-Agent" to userAgent)
                    )

                    if (probe.code in 200..299) {
                        callback.invoke(
                            newExtractorLink(
                                source = "HDFilmCehennemi",
                                name = "Rapidrame HLS",
                                url = finalContentUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = playerUrl
                                quality = Qualities.P1080.value
                                headers = mapOf("User-Agent" to userAgent)
                            }
                        )
                        return true
                    }

                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame contentUrl gecersiz HTTP ${probe.code}"
                    )
                } catch (e: Exception) {
                    Log.d(
                        "HDFilmCehennemi",
                        "Rapidrame contentUrl probe hatasi: ${e.message}"
                    )
                }
            }

            val candidates = listOf(normalized, unpacked)

            val absoluteRegex = Regex(
                """(?:(?:https?:)?//)[^\"'\s<>]+(?:master\.m3u8|index\.m3u8|\.m3u8)(?:\?[^\"'\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

            val relativeRegex = Regex(
                """(?:^|[\"'])((?:/|\./|\.\./)?[^\"'\s<>]*(?:master\.m3u8|index\.m3u8|\.m3u8)(?:\?[^\"'\s<>]*)?)""",
                RegexOption.IGNORE_CASE
            )

            var streamUrl: String? = null

            for (text in candidates) {
                streamUrl = absoluteRegex.find(text)?.value
                    ?: relativeRegex.find(text)?.groupValues?.getOrNull(1)

                if (!streamUrl.isNullOrBlank()) break
            }

            if (streamUrl.isNullOrBlank()) {
                Log.d(
                    "HDFilmCehennemi",
                    "Rapidrame tanisi tamamlandi: dogrudan M3U8 bulunamadi"
                )
                return false
            }

            val finalUrl = when {
                streamUrl!!.startsWith("//") -> "https:$streamUrl"
                streamUrl.startsWith("http://") || streamUrl.startsWith("https://") -> streamUrl
                else -> URI(playerUrl).resolve(streamUrl).toString()
            }

            Log.d(
                "HDFilmCehennemi",
                "Rapidrame M3U8 bulundu: $finalUrl"
            )

            callback.invoke(
                newExtractorLink(
                    source = "HDFilmCehennemi",
                    name = "Rapidrame",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = siteReferer
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to userAgent)
                }
            )

            true
        } catch (e: Exception) {
            Log.e(
                "HDFilmCehennemi",
                "Rapidrame extractor hatasi: ${e.message}",
                e
            )
            false
        }
    }

}
