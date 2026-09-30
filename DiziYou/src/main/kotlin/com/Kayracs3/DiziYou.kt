package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziYou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    /**
     * Diziyou arşivindeki bölümlerin sırası.
     * "Son Eklenen Bölümler" özellikle yoktur.
     */
    private val archiveSections = listOf(
        "Yeni Eklenen Diziler" to "filtrele=tarih&sirala=DESC",
        "IMDb 7+ Diziler" to "filtrele=imdb&sirala=DESC&yil=&imdb=7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val home = ArrayList<HomePageList>()

        // Önce gerçek ana sayfadaki hazır vitrinleri alıyoruz.
        // Böylece CloudStream'de DiziYou'nun kendi ana sayfasına yakın bir düzen oluşur.
        if (page <= 1) {
            runCatching {
                val document = app.get(mainUrl, headers = requestHeaders).document

                val latest = document.select("div.dsmobil2 div#list-series-main")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (latest.isNotEmpty()) {
                    home.add(HomePageList("Son Eklenen Diziler", latest))
                }

                val featured = document.select("div.incontentyeni div#list-series-main")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (featured.isNotEmpty()) {
                    home.add(HomePageList("Dikkat Çeken Diziler", featured))
                }

                val classics = document.select("div.incontent div#list-series-main")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (classics.isNotEmpty()) {
                    home.add(HomePageList("Efsane Diziler", classics))
                }
            }.onFailure {
                Log.e("DIZIYOU", "Ana sayfa vitrinleri yüklenemedi", it)
            }
        }

        // Coroutine kullanılmadan arşiv bölümlerini sırayla alıyoruz.
        // Böylece plugin içinde ayrıca kotlinx-coroutines bağımlılığı gerekmiyor.
        for ((sectionName, query) in archiveSections) {
            val url = archiveUrl(query, page)

            val items = runCatching {
                app.get(url, headers = requestHeaders).document
                    .selectSeriesCards()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }.onFailure { error ->
                Log.e(
                    "DIZIYOU",
                    "$sectionName yüklenemedi: $url",
                    error,
                )
            }.getOrDefault(emptyList())

            Log.d(
                "DIZIYOU",
                "$sectionName / sayfa $page -> ${items.size} dizi",
            )

            if (items.isNotEmpty()) {
                home.add(
                    HomePageList(
                        sectionName,
                        items,
                    )
                )
            }
        }

        return newHomePageResponse(
            home,
            hasNext = home.isNotEmpty(),
        )
    }

    private fun archiveUrl(query: String, page: Int): String {
        return if (page <= 1) {
            "$mainUrl/dizi-arsivi/?$query"
        } else {
            "$mainUrl/dizi-arsivi/page/$page/?$query"
        }
    }

    /**
     * DiziYou kartları aynı HTML ID'sini birden fazla kartta kullanabiliyor.
     * Jsoup tüm eşleşmeleri döndürdüğü için bunları tek tek kart olarak işleriz.
     */
    private fun Document.selectSeriesCards(): List<Element> {
        val cards = select(
            "div.incontent div#list-series, " +
                "div.incontent div#list-series-main, " +
                "div.dsmobil2 div#list-series, " +
                "div.dsmobil2 div#list-series-main, " +
                "div.incontentyeni div#list-series, " +
                "div.incontentyeni div#list-series-main"
        ).filter { card ->
            val anchor = card.selectFirst(
                "div#categorytitle a[href], div.cat-title-main a[href], a[href]"
            ) ?: return@filter false

            val href = fixUrlNull(anchor.attr("href")) ?: return@filter false
            isSeriesUrl(href) && card.selectFirst("img") != null
        }

        if (cards.isNotEmpty()) {
            return cards
                .distinctBy {
                    val anchor = it.selectFirst(
                        "div#categorytitle a[href], div.cat-title-main a[href], a[href]"
                    )
                    fixUrlNull(anchor?.attr("href"))
                        ?.trimEnd('/')
                        ?: it.outerHtml()
                }
        }

        // Tema değişmişse sadece posterli ve root-level gerçek dizi bağlantılarını
        // tek tek al. Artık ancestorHasImage kullanılmıyor; bu kritik düzeltmedir.
        return select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                if (!isSeriesUrl(href)) return@mapNotNull null
                if (anchor.selectFirst("img") == null) return@mapNotNull null

                anchor
            }
            .distinctBy { it.attr("href").trimEnd('/') }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("div#categorytitle a[href]")
            ?: selectFirst("div.cat-title-main a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!isSeriesUrl(href)) return null

        // Afişi yalnızca BU kartın içinden al.
        // Ebeveynlerdeki başka kartın posterini kesinlikle kullanma.
        val posterElement = selectFirst("img, picture img")
            ?: anchor.selectFirst("img")

        val poster = posterElement?.let { image ->
            firstNonBlank(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("data-lazy"),
                image.attr("src"),
            )?.let(::fixUrlNull)
        }

        val title = firstNonBlank(
            selectFirst("div.cat-title-main span")?.text(),
            selectFirst("div.cat-title-main")?.text(),
            selectFirst("div#categorytitle a")?.text(),
            anchor.attr("title"),
            anchor.text(),
            posterElement?.attr("alt"),
        )
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Menü metni yanlışlıkla kart içine sızmışsa bunu kabul etme.
        if (isInvalidCardTitle(title)) return null

        val scoreText = buildString {
            append(text())
            append(' ')
            append(selectFirst("[data-imdb]")?.attr("data-imdb").orEmpty())
            append(' ')
            append(selectFirst("[data-score]")?.attr("data-score").orEmpty())
            append(' ')
            append(selectFirst(".imdb, .imdb-score, .rating")?.text().orEmpty())
        }

        val score = extractImdbScore(scoreText)

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries,
        ) {
            posterUrl = poster
            score?.let { this.score = Score.from10(it) }
        }
    }

    private fun isInvalidCardTitle(title: String): Boolean {
        val value = title
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

        return value in setOf(
            "diziyou",
            "üye ol",
            "üye girişi",
            "üye girişi yap",
            "anasayfa",
            "menü",
            "dizi arşivi",
            "son eklenen diziler",
            "son eklenen bölümler",
            "imdb 7+",
            "imdb puanı",
            "giriş yap",
            "kayıt ol",
            "parolamı unuttum"
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    // Arama kodu için geriye dönük uyumluluk.
    private fun Document.selectSeriesAnchors(): List<Element> = selectSeriesCards()

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=${encoded.replace("+", "%20")}",
        )

        for (url in urls) {
            val results = runCatching {
                app.get(url).document
                    .selectSeriesAnchors()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }.getOrElse {
                emptyList()
            }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val pageText = document.text()

        val title = document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            firstNonBlank(
                document.selectFirst("div.category_image img")?.attr("data-src"),
                document.selectFirst("div.category_image img")?.attr("src"),
                document.selectFirst("meta[property='og:image']")?.attr("content"),
            )
        )

        val description = firstNonBlank(
            document.selectFirst("div.diziyou_desc")?.ownText()?.trim(),
            document.selectFirst("div.diziyou_desc")?.text()?.trim(),
            document.selectFirst("meta[name='description']")?.attr("content")?.trim(),
        )

        val year = document.selectFirst(
            "span.dizimeta:contains(Yapım Yılı)"
        )?.nextSibling()
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex(
                "(?:Yapım Yılı|Yıl)\\s*[:]?\\s*(19|20)\\d{2}",
                RegexOption.IGNORE_CASE,
            )
                .find(pageText)
                ?.value
                ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val tags = document.select("div.genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val imdbScore = document.selectFirst(
            "span.dizimeta:contains(IMDB)"
        )?.nextSibling()
            ?.toString()
            ?.trim()
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: extractImdbScore(pageText)

        val actors = extractActors(document, pageText)

        val trailer = document.selectFirst("iframe.trailer-video")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrlNull)

        val episodes = extractEpisodes(document)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes,
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            addActors(actors)

            imdbScore?.takeIf { it in 0f..10f }?.let {
                this.score = Score.from10(it)
            }

            // addTrailer() suspend olduğu için doğrudan TrailerData ekliyoruz.
            trailer?.let {
                trailers.add(
                    TrailerData(
                        extractorUrl = it,
                        referer = null,
                        raw = false,
                    )
                )
            }
        }
    }

    /**
     * Diziyou sayfasındaki gerçek oyuncu alanı:
     * span.dizimeta:contains(Oyuncular) + text node
     */
    private fun extractActors(
        document: Document,
        pageText: String,
    ): List<Actor>? {
        val raw = document.selectFirst(
            "span.dizimeta:contains(Oyuncular)"
        )?.nextSibling()
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Regex(
                "Oyuncular\\s*[:：]?\\s*(.*?)(?=\\s+(?:Tür|Tur|Yapım Yılı|IMDB|Bölümler)\\b|$)",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL,
                ),
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val cleaned = raw
            .replace("Oyuncular:", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim(' ', ':', '-')

        if (cleaned.isBlank()) return null

        return cleaned.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { Actor(it) }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Diziyou'nun gerçek bölüm HTML yapısı div.bolumust.
     * Link div'in üstündeki/çevresindeki <a> elementinden alınır.
     */
    private fun extractEpisodes(document: Document): List<Episode> {
        val exact = document.select("div.bolumust")
            .mapNotNull { block ->
                val anchor = block.closest("a")
                    ?: block.parent()?.selectFirst("a[href]")
                    ?: return@mapNotNull null

                val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                val episodeInfo = block.selectFirst("div.baslik")
                    ?.ownText()
                    ?.trim()
                    ?: block.text().trim()

                val season = Regex(
                    "(\\d+)\\.\\s*Sezon",
                    RegexOption.IGNORE_CASE,
                ).find(episodeInfo)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex(
                        "-(\\d+)-sezon-",
                        RegexOption.IGNORE_CASE,
                    ).find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: 1

                val episode = Regex(
                    "(\\d+)\\.\\s*Bölüm",
                    RegexOption.IGNORE_CASE,
                ).find(episodeInfo)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex(
                        "-(\\d+)-bolum(?:/|$)",
                        RegexOption.IGNORE_CASE,
                    ).find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null

                val episodeName = block.selectFirst("div.bolumismi")
                    ?.text()
                    ?.replace(Regex("[()]"), "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: episodeInfo.ifBlank { "$episode. Bölüm" }

                newEpisode(href) {
                    name = episodeName
                    this.season = season
                    this.episode = episode
                    posterUrl = block.selectFirst("img")?.let { image ->
                        firstNonBlank(
                            image.attr("data-src"),
                            image.attr("src"),
                        )?.let(::fixUrlNull)
                    }
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )

        if (exact.isNotEmpty()) return exact

        // Fallback: doğrudan URL'den gerçek bölüm linklerini yakala.
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                val match = Regex(
                    "-(\\d+)-sezon-(\\d+)-bolum(?:/|$)",
                    RegexOption.IGNORE_CASE,
                ).find(href) ?: return@mapNotNull null

                val season = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null

                val episode = match.groupValues[2].toIntOrNull()
                    ?: return@mapNotNull null

                val label = anchor.text().trim()
                    .ifBlank { "$episode. Bölüm" }

                newEpisode(href) {
                    name = label
                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    /**
     * Diziyou player yapısı:
     * iframe#diziyouPlayer -> /.../<itemId>.html
     * span.diziyouOption -> dil / ses seçenekleri
     */
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("DIZIYOU", "episode data = $data")

        val document = runCatching {
            app.get(data).document
        }.getOrElse {
            Log.e("DIZIYOU", "Bölüm sayfası açılamadı: $data", it)
            return false
        }

        val player = document.selectFirst("iframe#diziyouPlayer")
            ?: document.selectFirst("iframe[src*='diziyou']")
            ?: document.selectFirst("iframe")
            ?: return false

        val playerSrc = fixUrlNull(
            firstNonBlank(
                player.attr("src"),
                player.attr("data-src"),
            )
        ) ?: return false

        val itemId = playerSrc
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBefore(".html")
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: return false

        Log.d("DIZIYOU", "itemId = $itemId")

        val storage = mainUrl.replace("www.", "storage.")
        val streams = LinkedHashMap<String, String>()

        // Diziyou'da seçenekler span.diziyouOption + id üzerinden geliyor.
        document.select("span.diziyouOption").forEach { option ->
            when (option.attr("id")) {
                "turkceAltyazili" -> {
                    subtitleCallback(
                        SubtitleFile(
                            lang = "Turkish",
                            url = fixUrl("$storage/subtitles/$itemId/tr.vtt"),
                        )
                    )

                    streams["Orijinal Dil"] =
                        "$storage/episodes/$itemId/play.m3u8"
                }

                "ingilizceAltyazili" -> {
                    subtitleCallback(
                        SubtitleFile(
                            lang = "English",
                            url = fixUrl("$storage/subtitles/$itemId/en.vtt"),
                        )
                    )

                    streams["Orijinal Dil"] =
                        "$storage/episodes/$itemId/play.m3u8"
                }

                "turkceDublaj" -> {
                    streams["Türkçe Dublaj"] =
                        "$storage/episodes/${itemId}_tr/play.m3u8"
                }
            }
        }

        // Eski sayfalarda seçenek görünmezse orijinal HLS'i yine dene.
        if (streams.isEmpty()) {
            streams["Orijinal Dil"] =
                "$storage/episodes/$itemId/play.m3u8"
        }

        for ((streamName, streamUrl) in streams) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = streamName,
                    url = streamUrl,
                    type = INFER_TYPE,
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
        }

        return streams.isNotEmpty()
    }

    private fun extractImdbScore(text: String): Float? {
        val score = Regex(
            "(?:IMDb|IMDB)\\s*[:★]?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        return score?.takeIf { it in 0f..10f }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return url.contains("diziyou.one", ignoreCase = true) &&
            Regex(
                "-[0-9]+-sezon-[0-9]+-bolum(?:/|$)",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(url)
    }

    private fun isSeriesUrl(url: String): Boolean {
        val normalized = url
            .trim()
            .lowercase()
            .substringBefore('#')
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

        val base = mainUrl
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

        if (!(normalized == base || normalized.startsWith("$base/"))) {
            return false
        }

        if (normalized == base) return false

        val path = normalized.substringAfter(base, "")

        // Diziyou dizi sayfaları root-level slug kullanıyor.
        if (!path.startsWith("/") || path.count { it == '/' } > 1) {
            return false
        }

        if (isEpisodeUrl(url)) return false

        val excluded = setOf(
            "dizi-arsivi",
            "film-arsivi",
            "imdb-7",
            "son-eklenen-diziler",
            "son-eklenen-bolumler",
            "uye-ol",
            "uye-girisi",
            "giris-yap",
            "kayit-ol",
            "iletisim",
            "gizlilik",
            "gizlilik-politikasi",
            "hakkimizda",
            "dmca",
            "wp-login.php",
            "wp-admin",
            "feed",
            "sitemap.xml",
            "robots.txt"
        )

        val slug = path
            .trim('/')
            .substringBefore('?')

        if (slug in excluded) return false
        if (slug.isBlank()) return false

        return !slug.contains(".xml") &&
            !slug.contains(".jpg") &&
            !slug.contains(".png") &&
            !slug.contains(".css") &&
            !slug.contains(".js")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "Chrome/154.0 Safari/537.36"
    }
}
