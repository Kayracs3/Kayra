package com.neoncs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class FilmModu : MainAPI() {

    override var mainUrl = "https://www.filmmodu.one"
    override var name = "FilmModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val tag = "FilmModu"

    private fun headers(referer: String = mainUrl + "/"): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
        )

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Son Eklenen Filmler",
        mainUrl + "/hd-populer-filmler" to "Popüler Filmler",
        mainUrl + "/boxset-seri-filmler" to "Seri Filmler",
        mainUrl + "/arsiv-filmler" to "Film Arşivi",
        mainUrl + "/hd-film-kategori/4k-film-izle" to "4K",
        mainUrl + "/hd-film-kategori/aile-filmleri" to "Aile",
        mainUrl + "/hd-film-kategori/aksiyon" to "Aksiyon",
        mainUrl + "/hd-film-kategori/animasyon" to "Animasyon",
        mainUrl + "/hd-film-kategori/belgeseller" to "Belgesel",
        mainUrl + "/hd-film-kategori/bilim-kurgu-filmleri" to "Bilim-Kurgu",
        mainUrl + "/hd-film-kategori/dram-filmleri" to "Dram",
        mainUrl + "/hd-film-kategori/fantastik-filmler" to "Fantastik",
        mainUrl + "/hd-film-kategori/gerilim" to "Gerilim",
        mainUrl + "/hd-film-kategori/gizem-filmleri" to "Gizem",
        mainUrl + "/hd-film-kategori/komedi-filmleri" to "Komedi",
        mainUrl + "/hd-film-kategori/korku-filmleri" to "Korku",
        mainUrl + "/hd-film-kategori/kult-filmler-izle" to "Kült Filmler",
        mainUrl + "/hd-film-kategori/macera-filmleri" to "Macera",
        mainUrl + "/hd-film-kategori/romantik-filmler" to "Romantik",
        mainUrl + "/hd-film-kategori/savas-filmleri" to "Savaş",
        mainUrl + "/hd-film-kategori/suc-filmleri" to "Suç",
        mainUrl + "/hd-film-kategori/tarih" to "Tarih",
        mainUrl + "/hd-film-kategori/tavsiye-filmler" to "Tavsiye Filmler",
        mainUrl + "/hd-film-kategori/vahsi-bati-filmleri" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else base + "/?page=" + page

        val document = runCatching {
            app.get(url, headers = headers()).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = document.select("a[href]")
            .filter { isFilmDetailLink(it.attr("href")) }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && page < 100
        )
    }

    private fun isFilmDetailLink(raw: String): Boolean {
        val href = normalizeUrl(raw, mainUrl)
        if (!href.startsWith("http", true)) return false

        val path = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
        if (path.isBlank()) return false

        // Real FilmModu film pages end with "-film-izle".
        if (!path.endsWith("-film-izle")) return false

        // Exclude navigation/category/archive pages that also contain "-film-izle".
        val blocked = listOf(
            "/hd-film-kategori/",
            "/arsiv-filmler",
            "/hd-populer-filmler",
            "/boxset-seri-filmler",
            "/turkce-dublaj-hd-film-izle",
            "/turkce-altyazili-hd-filmler-izle",
            "/film-tur/",
            "/aktor/",
            "/yil/"
        )

        return blocked.none { path.contains(it) }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href]") ?: return null

        val href = normalizeUrl(anchor.attr("href"), mainUrl)
        if (!href.startsWith("http", true)) return null

        if (!isFilmDetailLink(href)) return null

        val image = sequence {
            yield(anchor.selectFirst("img"))
            var parent = anchor.parent()
            repeat(6) {
                if (parent == null) return@repeat
                yield(parent.selectFirst("img"))
                parent = parent.parent()
            }
        }.filterNotNull().firstOrNull()

        val title = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            anchor.selectFirst("h3, h2, .turkish-name, .original-name, .title, .movie-title")?.text(),
            image?.attr("alt"),
            anchor.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

        if (title.isBlank()) return null

        val rawPoster = sequenceOf(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-poster"),
            image?.attr("data-image"),
            image?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) }.orEmpty()

        val poster = normalizeUrl(rawPoster, href)
            .takeIf { it.startsWith("http", true) }

        val ratingText = generateSequence(anchor as Element?) { it.parent() }
            .take(7)
            .map { it.text() }
            .firstOrNull { Regex("""(?i)IMDb|rating|puan""").containsMatchIn(it) }
            .orEmpty()

        val rating = extractRating(ratingText.ifBlank { anchor.text() })

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl + "/",
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            )
            rating?.let { score = Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/film-ara?term=$encoded",
            "$mainUrl/film-ara?query=$encoded",
            "$mainUrl/film-ara?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/arama/$encoded",
        ).distinct()

        for (url in urls) {
            val document = runCatching {
                app.get(url, headers = headers(), referer = "$mainUrl/", allowRedirects = true).document
            }.onFailure {
                android.util.Log.w("FilmModu", "Arama isteği başarısız: $url", it)
            }.getOrNull() ?: continue

            val results = document.select("a[href]")
                .filter { isFilmDetailLink(it.attr("href")) }
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            android.util.Log.d("FilmModu", "Arama: $url -> ${results.size} sonuç")
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching {
            app.get(url, headers = headers()).document
        }.getOrNull() ?: return null

        val originalTitle = document.selectFirst("div.titles h1, h1")?.text()?.trim()
            ?: return null

        val alternateTitle = document.selectFirst("div.titles h2")?.text()?.trim().orEmpty()
        val title = if (alternateTitle.isNotBlank()) {
            originalTitle + " - " + alternateTitle
        } else {
            originalTitle
        }

        val poster = normalizeUrl(
            listOf(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("img.img-responsive")?.attr("src"),
                document.selectFirst("div.poster img")?.attr("src"),
                document.selectFirst("img")?.attr("data-src"),
                document.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            url
        )

        val plot = listOf(
            document.selectFirst("p[itemprop='description']")?.text(),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("div.description p")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val year = document.selectFirst("span[itemprop='dateCreated']")?.text()
            ?.trim()?.toIntOrNull()
            ?: Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
                .find(document.text())?.value?.toIntOrNull()

        val tags = document.select("div.description a[href*='-kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = document.select(
            "div.description a[href*='-oyuncu-'], " +
                "div.description a[href*='/oyuncu/'], " +
                ".actors a[href], .cast a[href]"
        ).mapNotNull {
                val actor = it.selectFirst("span")?.text()?.trim() ?: it.text().trim()
                actor.takeIf { value -> value.isNotBlank() }?.let(::Actor)
            }

        val rating = listOf(
            document.selectFirst("div.description p")?.ownText(),
            document.selectFirst(".imdb-score, .imdb-rating, .rating, .score")?.text(),
            document.text()
        ).asSequence()
            .filterNotNull()
            .mapNotNull(::extractRating)
            .firstOrNull()
        val trailer = document.select("iframe[src], iframe[data-src]")
            .mapNotNull {
                val raw = it.attr("data-src").ifBlank { it.attr("src") }
                normalizeUrl(raw, url).takeIf { value ->
                    value.contains("youtube.com", true) ||
                        value.contains("youtu.be", true)
                }
            }
            .firstOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl + "/",
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            this.plot = plot
            this.year = year
            this.tags = tags
            rating?.let { score = Score.from10(it) }
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(tag, "FilmModu loadLinks başladı: $data")

        val baseDocument = runCatching {
            app.get(
                data,
                headers = headers(mainUrl + "/"),
                referer = mainUrl + "/",
                allowRedirects = true
            ).document
        }.getOrNull() ?: return false

        data class ProbePage(
            val url: String,
            val name: String,
            val document: org.jsoup.nodes.Document?
        )

        val pages = ArrayList<ProbePage>()
        pages += ProbePage(data, "Ana", baseDocument)

        baseDocument.select("div.alternates a[href], .alternates a[href]").forEach { a ->
            val href = normalizeUrl(a.attr("href"), data)
            val pageName = a.text().trim()
            if (
                href.startsWith("http", true) &&
                href != data &&
                !pageName.contains("fragman", true)
            ) {
                pages += ProbePage(
                    href,
                    pageName.ifBlank { "Alternatif" },
                    null
                )
            }
        }

        val seenStreams = HashSet<String>()
        val seenSubtitles = HashSet<String>()
        var linksFound = false

        for (page in pages.distinctBy { it.url }.take(12)) {
            val doc = page.document ?: runCatching {
                app.get(
                    page.url,
                    headers = headers(data),
                    referer = data,
                    allowRedirects = true
                ).document
            }.getOrNull() ?: continue

            val html = doc.html()

            val videoId = sequenceOf(
                Regex("""var\s+videoId\s*=\s*['"]([0-9]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1),
                Regex("""videoId\s*[:=]\s*['"]([0-9]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
            ).firstOrNull { !it.isNullOrBlank() }

            val videoType = sequenceOf(
                Regex("""var\s+videoType\s*=\s*['"]([a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1),
                Regex("""videoType\s*[:=]\s*['"]([a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
            ).firstOrNull { !it.isNullOrBlank() }

            if (videoId.isNullOrBlank() || videoType.isNullOrBlank()) {
                Log.d(tag, "videoId/videoType bulunamadı: \${page.url}")
                continue
            }

            val sourceUrl = mainUrl +
                "/get-source?movie_id=" +
                URLEncoder.encode(videoId, "UTF-8") +
                "&type=" +
                URLEncoder.encode(videoType, "UTF-8")

            val response = runCatching {
                app.get(
                    sourceUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*;q=0.01",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Referer" to page.url,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = page.url,
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            Log.d(tag, "get-source \${page.name}: HTTP=\${response.code}")
            if (!response.isSuccessful || response.text.isBlank()) continue

            val json = runCatching { JSONObject(response.text) }.getOrNull() ?: continue
            val sources = findSources(json)

            if (sources.isEmpty()) {
                Log.d(tag, "sources boş: \${page.name}")
                continue
            }

            val subtitleRaw = sequenceOf(
                json.optString("subtitle").takeIf { it.isNotBlank() },
                json.optJSONObject("data")?.optString("subtitle")
                    ?.takeIf { it.isNotBlank() }
            ).firstOrNull { !it.isNullOrBlank() }

            subtitleRaw?.let { raw ->
                val subUrl = normalizeUrl(raw, page.url)
                if (
                    subUrl.startsWith("http", true) &&
                    seenSubtitles.add(subUrl)
                ) {
                    runCatching {
                        subtitleCallback(
                            SubtitleFile(
                                lang = "Türkçe",
                                url = subUrl
                            )
                        )
                    }
                }
            }

            for (source in sources) {
                val raw = sequenceOf(
                    source.optString("src"),
                    source.optString("url"),
                    source.optString("file")
                ).firstOrNull { it.isNotBlank() } ?: continue

                var streamUrl = normalizeUrl(raw, mainUrl + "/")
                if (!streamUrl.startsWith("http", true)) continue

                if (!streamUrl.contains(".m3u8", true)) {
                    val question = streamUrl.indexOf("?")
                    streamUrl = if (question >= 0) {
                        streamUrl.substring(0, question) +
                            ".m3u8" +
                            streamUrl.substring(question)
                    } else {
                        streamUrl + ".m3u8"
                    }
                }

                if (!seenStreams.add(streamUrl)) continue

                val label = sequenceOf(
                    source.optString("label"),
                    source.optString("quality"),
                    source.optString("name")
                ).firstOrNull { it.isNotBlank() }.orEmpty()

                val resolution = source.optString("res").toIntOrNull()
                val quality = resolution?.takeIf { it > 0 }
                    ?: getQualityFromName(label).takeIf { it > 0 }
                    ?: Qualities.Unknown.value

                callback(
                    newExtractorLink(
                        source = "$name - \${page.name}",
                        name = "$name - \${page.name}" +
                            label.takeIf { it.isNotBlank() }
                                ?.let { " $it" }
                                .orEmpty(),
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                    }
                )

                linksFound = true
                Log.d(
                    tag,
                    "HLS bulundu: $streamUrl | \${page.name} | $label"
                )
            }
        }

        Log.d(tag, "FilmModu loadLinks tamamlandı: linksFound=$linksFound")
        return linksFound
    }

    private fun findSources(json: JSONObject): List<JSONObject> {
        val result = ArrayList<JSONObject>()

        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (
                    item.has("src") ||
                    item.has("url") ||
                    item.has("file")
                ) {
                    result.add(item)
                }
            }
        }

        addArray(json.optJSONArray("sources"))

        val data = json.optJSONObject("data")
        addArray(data?.optJSONArray("sources"))

        val resultObject = json.optJSONObject("result")
        addArray(resultObject?.optJSONArray("sources"))

        return result.distinctBy {
            it.optString("src") + "|" +
                it.optString("url") + "|" +
                it.optString("file")
        }
    }

    private suspend fun emitSubtitlesFromJson(
        json: JSONObject,
        referer: String,
        defaultLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val arrays = listOfNotNull(
            json.optJSONArray("tracks"),
            json.optJSONArray("subtitles"),
            json.optJSONObject("data")?.optJSONArray("tracks"),
            json.optJSONObject("data")?.optJSONArray("subtitles")
        )

        for (array in arrays) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = listOf(
                    item.optString("file"),
                    item.optString("src"),
                    item.optString("url")
                ).firstOrNull { it.isNotBlank() } ?: continue

                val label = listOf(
                    item.optString("label"),
                    item.optString("language"),
                    item.optString("lang")
                ).firstOrNull { it.isNotBlank() } ?: defaultLabel

                val full = normalizeUrl(url, referer)
                if (!full.startsWith("http", true)) continue

                runCatching {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = label,
                            url = full
                        ) {
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        }
                    )
                }
            }
        }

        val scalar = listOf(
            json.optString("subtitle"),
            json.optString("subtitle_url"),
            json.optString("subtitleUrl")
        ).firstOrNull { it.isNotBlank() }

        if (!scalar.isNullOrBlank()) {
            val full = normalizeUrl(scalar, referer)
            if (full.startsWith("http", true)) {
                runCatching {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = defaultLabel,
                            url = full
                        ) {
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        }
                    )
                }
            }
        }
    }

    private suspend fun emitMedia(
        rawUrl: String,
        referer: String,
        sourceName: String,
        label: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val mediaUrl = normalizeUrl(rawUrl, referer)
        if (!mediaUrl.startsWith("http", true)) return false

        val lower = mediaUrl.lowercase()
        val type = if (
            lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            sourceName.contains("FilmModu -", true)
        ) {
            ExtractorLinkType.M3U8
        } else {
            INFER_TYPE
        }

        val quality = getQualityFromName(label.orEmpty()).takeIf { it > 0 }
            ?: if (lower.contains("2160") || lower.contains("4k")) {
                Qualities.P2160.value
            } else if (lower.contains("1080")) {
                Qualities.P1080.value
            } else if (lower.contains("720")) {
                Qualities.P720.value
            } else {
                Qualities.Unknown.value
            }

        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = mediaUrl,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
                headers = mapOf(
                    "Referer" to "$mainUrl/"
                )
            }
        )

        Log.d(tag, "Video eklendi: " + mediaUrl)
        return true
    }

    private fun decodeBase64Html(value: String): String? {
        return runCatching {
            val cleaned = value
                .replace("\\/", "/")
                .replace("\\", "")
                .replace(Regex("""\s+"""), "")
                .replace("-", "+")
                .replace("_", "/")

            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
                .takeIf { it.contains("<iframe", true) || it.contains("<video", true) || it.contains("<source", true) }
        }.getOrNull()
    }

    private fun posterFrom(element: Element): String? {
        fun firstUsable(values: List<String>): String {
            return values
                .asSequence()
                .map { it.trim() }
                .map { it.substringBefore(" ").trim() }
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.equals("about:blank", true) &&
                        !it.startsWith("data:", true) &&
                        !it.startsWith("javascript:", true)
                }
                .orEmpty()
        }

        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[data-original], img[srcset], img[src]"
        )

        if (image != null) {
            val raw = firstUsable(
                listOf(
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("data-image"),
                    image.attr("data-poster"),
                    image.attr("data-original-src"),
                    image.attr("data-srcset"),
                    image.attr("srcset"),
                    image.attr("src"),
                )
            )

            if (raw.isNotBlank()) {
                return normalizeUrl(raw, mainUrl)
            }
        }

        val source = element.selectFirst("picture source[srcset], source[srcset]")
        if (source != null) {
            val raw = firstUsable(listOf(source.attr("srcset")))
            if (raw.isNotBlank()) {
                return normalizeUrl(raw, mainUrl)
            }
        }

        val dataImage = firstUsable(
            listOf(
                element.attr("data-poster"),
                element.attr("data-image"),
                element.attr("data-bg"),
                element.attr("data-background"),
                element.attr("data-thumb"),
                element.attr("data-src"),
            )
        )
        if (dataImage.isNotBlank()) {
            return normalizeUrl(dataImage, mainUrl)
        }

        val style = element.attr("style")
        val background = Regex(
            """(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)"""
        ).find(style)?.groupValues?.getOrNull(2).orEmpty()

        if (background.isNotBlank()) {
            return normalizeUrl(background, mainUrl)
        }

        val nestedStyle = element.selectFirst("[style*='background-image']")
            ?.attr("style")
            .orEmpty()

        val nestedBackground = Regex(
            """(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)"""
        ).find(nestedStyle)?.groupValues?.getOrNull(2).orEmpty()

        if (nestedBackground.isNotBlank()) {
            return normalizeUrl(nestedBackground, mainUrl)
        }

        return null
    }

    private fun extractRating(text: String): Double? {
        if (text.isBlank()) return null

        val value = Regex(
            """(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()

        return value?.takeIf { it in 0.0..10.0 }
            ?: Regex("""(?<!\d)(?:19|20)\d{2}\s+([0-9](?:[.,][0-9])?)(?!\d)""")
                .find(text)
                ?.groupValues?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..10.0 }
    }

    private fun normalizeUrl(raw: String, base: String): String {
        var value = raw
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim('"', '\'')

        if (value.isBlank()) return ""

        if (value.startsWith("//")) {
            value = "https:" + value
        }

        if (value.startsWith("/")) {
            val origin = runCatching {
                val uri = URI(base)
                uri.scheme + "://" + uri.host +
                    (uri.port.takeIf { it > 0 }?.let { ":" + it } ?: "")
            }.getOrNull()

            value = (origin ?: mainUrl) + value
        } else if (!value.startsWith("http", true)) {
            value = runCatching {
                URI(base).resolve(value).toString()
            }.getOrDefault(value)
        }

        return value.replace(" ", "%20")
    }
}
