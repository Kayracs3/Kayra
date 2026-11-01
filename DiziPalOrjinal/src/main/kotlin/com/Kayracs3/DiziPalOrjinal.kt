package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOrjinal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal18.com"
    override var name = "DiziPalOrjinal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        // DiziPal script'inin kullandığı AES anahtarları
        private const val AES_KEY = "T8r9Wq3mZ1kL5vYb"
        private const val AES_IV = "N2pR7sX4cV6bM1qF"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/populer" to "Popüler",
    )

    // ================= ANA SAYFA / ARAMA =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val address = if (page <= 1) request.data else request.data.trimEnd('/') + "?page=$page"
        val document = getDocument(address) ?: return newHomePageResponse(request.name, emptyList())
        val results = parseCatalog(document, address)
        return newHomePageResponse(request.name, results, hasNext = page < 30)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()
        val encoded = URLEncoder.encode(value, "UTF-8")
        for (address in listOf("$mainUrl/?s=$encoded", "$mainUrl/arama?q=$encoded")) {
            val document = getDocument(address) ?: continue
            val results = parseCatalog(document, address)
                .filter { it.name.contains(value, true) }
                .distinctBy { canonical(it.url) }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ================= DETAY =================
    override suspend fun load(url: String): LoadResponse? {
        val address = absolute(url, mainUrl)
        val document = getDocument(address) ?: return null
        val title = pageTitle(document, address) ?: return null
        val poster = pagePoster(document, address)
        val plot = pagePlot(document)
        val year = pageYear(document, title)
        val genres = pageGenres(document)
        val actors = pageActors(document, address)

        if (isMovie(address) || document.selectFirst("meta[property=og:type][content*=movie]") != null) {
            return newMovieLoadResponse(title, address, TvType.Movie, address) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        val episodes = parseAllSeasonEpisodes(document, address, poster)
        return newTvSeriesLoadResponse(title, address, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    private suspend fun parseAllSeasonEpisodes(detail: Document, seriesUrl: String, poster: String?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        parseEpisodeLinks(detail, seriesUrl, poster, null).forEach { byUrl[canonical(it.data)] = it }

        val seasons = parseSeasonLinks(detail, seriesUrl)
        for ((index, season) in seasons.withIndex()) {
            if (index >= 40) break
            val seasonUrl = season.first
            val seasonNumber = season.second ?: (index + 1)
            val seasonDoc = getDocument(seasonUrl, seriesUrl) ?: continue
            parseEpisodeLinks(seasonDoc, seasonUrl, poster, seasonNumber).forEach { ep ->
                val key = canonical(ep.data)
                val old = byUrl[key]
                if (old == null || (old.season == null && ep.season != null)) byUrl[key] = ep
            }
        }
        return byUrl.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name.orEmpty() }
        )
    }

    private fun parseSeasonLinks(document: Document, baseUrl: String): List<Pair<String, Int?>> {
        val found = LinkedHashMap<String, Int?>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue
            val url = absolute(href, baseUrl).substringBefore("#")
            val path = pathOf(url).lowercase()
            val label = link.text() + " " + link.attr("title")
            val isSeasonPath = path.contains("/sezon/") || Regex("""(?i)/(?:season|sezon)[-_]?\d+(?:/|$)""").containsMatchIn(path)
            if (!isSeasonPath || canonical(url) == canonical(baseUrl)) continue
            if (!url.startsWith(mainUrl, true)) continue
            found.putIfAbsent(url, seasonNumber(label + " " + url))
        }
        return found.map { it.key to it.value }
    }

    private fun parseEpisodeLinks(document: Document, baseUrl: String, poster: String?, defaultSeason: Int?): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue
            val url = absolute(href, baseUrl).substringBefore("#")
            if (!url.startsWith(mainUrl, true)) continue
            val label = listOf(link.text(), link.attr("title"), link.attr("aria-label"), link.selectFirst("img")?.attr("alt").orEmpty())
                .filter { it.isNotBlank() }.joinToString(" ")
            val numbers = readEpisodeNumbers(label) ?: readEpisodeNumbers(url)
            val path = pathOf(url).lowercase()
            val routeLooksLikeEpisode = path.startsWith("/bolum/") || path.startsWith("/episode/") ||
                    Regex("""(?i)(?:^|[-/])\d+-sezon-\d+-bolum(?:[-/]|$)""").containsMatchIn(path) ||
                    Regex("""(?i)(?:^|[-/])s\d{1,2}e\d{1,3}(?:[-/]|$)""").containsMatchIn(path)
            val labelLooksLikeEpisode = Regex("""(?i)\d+\s*\.?\s*sezon.{0,80}\d+\s*\.?\s*bölüm""").containsMatchIn(label)
            if (!routeLooksLikeEpisode && !labelLooksLikeEpisode) continue
            if (canonical(url) == canonical(baseUrl)) continue

            val season = numbers?.first ?: defaultSeason
            val episodeNumber = numbers?.second ?: readEpisodeNumber(label + " " + url)
            val rawName = listOf(link.attr("title"), link.text(), link.selectFirst("img")?.attr("alt").orEmpty())
                .firstOrNull { it.isNotBlank() } ?: pathOf(url).substringAfterLast("/")
            val episodeName = cleanEpisodeName(rawName).ifBlank { if (episodeNumber != null) "Bölüm $episodeNumber" else "Bölüm" }
            val episodePoster = imageFrom(link, baseUrl) ?: poster

            val episode = newEpisode(url) {
                name = episodeName
                this.season = season
                this.episode = episodeNumber
                posterUrl = episodePoster
            }
            found.putIfAbsent(canonical(url), episode)
        }
        return found.values.toList()
    }

    // ================= OYNATICI (ŞİFRELİ CONFIG MANTIĞI) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = absolute(data, mainUrl)
        if (sourceUrl.isBlank()) return false
        Log.d(name, "loadLinks başladı: $sourceUrl")

        var found = false
        val visited = mutableSetOf<String>()

        suspend fun emitMedia(raw: String?, referer: String) {
            val media = raw?.trim() ?: return
            val mediaUrl = absolute(media.replace("\\/", "/"), referer)
            if (mediaUrl.isBlank() || !mediaUrl.startsWith("http", true)) return
            if (!visited.add(mediaUrl)) return
            val lower = mediaUrl.lowercase()
            val isPlayable = lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") || lower.contains(".m3u") || lower.contains(".webm")
            if (!isPlayable) return
            val linkType = when {
                lower.contains(".m3u8") || lower.contains(".m3u") -> ExtractorLinkType.M3U8
                lower.contains(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback.invoke(newExtractorLink(source = name, name = name, url = mediaUrl, type = linkType) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Accept" to "*/*")
            })
            found = true
            Log.d(name, "★ Video linki eklendi: $mediaUrl")
        }

        // 1) Sayfayı çek
        val page = runCatching {
            app.get(sourceUrl, headers = headers, referer = "$mainUrl/")
        }.getOrNull() ?: run {
            Log.e(name, "Sayfa çekilemedi: $sourceUrl")
            return false
        }
        if (!page.isSuccessful) {
            Log.e(name, "Sayfa başarısız: ${page.code}")
            return false
        }
        val pageHtml = page.text.replace("\\/", "/").replace("\\\"", "\"")

        // 2) Şifreli config ara ve çöz
        val encrypted = extractEncryptedConfig(pageHtml)
        if (encrypted != null) {
            Log.d(name, "Şifreli config bulundu: ${encrypted.take(60)}...")
            val player = decryptDizipalConfig(encrypted)
            Log.d(name, "Çözülen player: ${player.take(200)}")
            if (player.isNotBlank()) {
                val playerWithScheme = when {
                    player.startsWith("//") -> "https:$player"
                    !player.startsWith("http") -> "https://$player"
                    else -> player
                }
                val iframeUrl = absolute(playerWithScheme, sourceUrl)
                Log.d(name, "Iframe URL: $iframeUrl")

                // 3) Iframe URL'sindeki v parametresini çıkar
                val vParam = Regex("""[?&]v=([^&]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
                Log.d(name, "v parametresi: $vParam")

                // 4) Iframe'i çek (altyazılar için)
                val iframePage = runCatching {
                    app.get(iframeUrl, headers = headers + mapOf("Referer" to sourceUrl, "User-Agent" to USER_AGENT), referer = sourceUrl)
                }.getOrNull()

                if (iframePage != null && iframePage.isSuccessful) {
                    val iframeHtml = iframePage.text.replace("\\/", "/").replace("\\\"", "\"")
                    Log.d(name, "Iframe çekildi: ${iframePage.code}")

                    // Altyazılar
                    Regex(""""subtitle"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(iframeHtml).forEach { m ->
                        val subUrl = absolute(m.groupValues[1].replace("\\/", "/"), iframeUrl)
                        if (subUrl.isNotBlank()) runCatching {
                            subtitleCallback(newSubtitleFile(lang = "Türkçe", url = subUrl))
                            Log.d(name, "Altyazı: $subUrl")
                        }
                    }

                    // 5) v parametresi ile source2.php çağır
                    if (!vParam.isNullOrBlank()) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val source2Url = origin.trimEnd('/') + "/source2.php?v=" + URLEncoder.encode(vParam, "UTF-8")
                        Log.d(name, "★ Source2 URL: $source2Url")

                        val s2 = runCatching {
                            app.get(
                                source2Url,
                                headers = headers + mapOf(
                                    "Referer" to iframeUrl,
                                    "User-Agent" to USER_AGENT,
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Accept" to "application/json,text/plain,*/*",
                                ),
                                referer = iframeUrl,
                            )
                        }.getOrNull()

                        if (s2 != null && s2.isSuccessful) {
                            val s2Text = s2.text.replace("\\/", "/").replace("\\\"", "\"")
                            Log.d(name, "★ Source2 yanıtı: ${s2Text.take(500)}")

                            // m3u8 / mp4 arama
                            Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
                                .findAll(s2Text).forEach { emitMedia(it.value, iframeUrl) }

                            // JSON "file":"..." deseni
                            Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(s2Text).forEach {
                                emitMedia(it.groupValues[1], iframeUrl)
                            }
                        } else {
                            Log.e(name, "Source2 başarısız: ${s2?.code}")
                        }
                    }
                } else {
                    Log.e(name, "Iframe başarısız: ${iframePage?.code}")
                }
            }
        } else {
            Log.d(name, "Şifreli config bulunamadı, genel tarama yapılıyor.")
        }

        // 6) Yedek: sayfadaki tüm medya URL'lerini tara
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|m3u)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml).forEach { emitMedia(it.value, sourceUrl) }

        // 7) Yedek: iframe'leri tara
        Document.parse(pageHtml).select("iframe[src]").forEach { iframe ->
            val iframeUrl = absolute(iframe.attr("src"), sourceUrl)
            if (iframeUrl.isNotBlank()) {
                val ip = runCatching { app.get(iframeUrl, headers = headers, referer = sourceUrl) }.getOrNull()
                if (ip != null && ip.isSuccessful) {
                    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|m3u)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
                        .findAll(ip.text.replace("\\/", "/")).forEach { emitMedia(it.value, iframeUrl) }
                }
            }
        }

        Log.d(name, "loadLinks bitti. Bulunan link: $found")
        return found
    }

    // ================= ŞİFRELİ CONFIG YARDIMCILARI =================
    private fun extractEncryptedConfig(text: String): String? {
        val patterns = listOf(
            Regex("""(?:const|let|var)\s+_?config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:const|let|var)\s+_?enc\w*\s*=\s*['"]([^'"]{20,})['"]""", RegexOption.IGNORE_CASE),
            Regex(""""config"\s*:\s*"([^"]{20,})"""", RegexOption.IGNORE_CASE),
            Regex("""_config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val value = m.groupValues[1]
            if (value.startsWith("enc:") || value.length > 40) return value
        }
        return null
    }

    private fun decryptDizipalConfig(encrypted: String): String {
        val clean = encrypted.removePrefix("enc:")
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
            String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)))
        }.getOrElse {
            runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }.getOrDefault("")
        }
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    } catch (_: Exception) { null }

    // ================= YARDIMCI FONKSİYONLAR =================
    private fun parseCatalog(document: Document, baseUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in document.select("a[href]")) {
            val url = absolute(link.attr("href"), baseUrl).substringBefore("#")
            if (!isDetailUrl(url)) continue
            val poster = imageFrom(link, baseUrl)
            val title = listOf(link.attr("title"), link.selectFirst("h2,h3,h4,.title")?.text().orEmpty(), link.selectFirst("img")?.attr("alt").orEmpty(), link.text())
                .firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: continue
            if (title.length < 2) continue
            val item = if (isMovie(url)) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            }
            found.putIfAbsent(canonical(url), item)
        }
        return found.values.toList()
    }

    private fun isDetailUrl(url: String): Boolean {
        val parts = pathOf(url).trimEnd('/').lowercase().split("/").filter { it.isNotBlank() }
        return parts.size >= 2 && parts.first() in setOf("diziler", "filmler", "dizi", "film")
    }

    private fun isMovie(url: String): Boolean {
        val path = pathOf(url).lowercase()
        return path.startsWith("/filmler/") || path.startsWith("/film/")
    }

    private fun pageTitle(document: Document, url: String): String? {
        val value = listOf(document.selectFirst("h1")?.text(), document.selectFirst("meta[property=og:title]")?.attr("content"), document.title())
            .firstOrNull { !it.isNullOrBlank() } ?: pathOf(url).substringAfterLast("/")
        return cleanTitle(value).takeIf { it.isNotBlank() }
    }

    private fun pagePoster(document: Document, baseUrl: String): String? {
        return listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst(".poster img")?.let(::imageAttribute),
        ).asSequence().filterNotNull().map { absolute(it, baseUrl) }.firstOrNull { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".description")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun pageYear(document: Document, title: String): Int? {
        val text = title + " " + document.selectFirst("main")?.text().orEmpty()
        return Regex("""(?<!\d)(19|20)\d{2}(?!\d)""").find(text)?.value?.toIntOrNull()
    }

    private fun pageGenres(document: Document): List<String> {
        val result = LinkedHashSet<String>()
        document.select("a[href*='/tur/']").forEach { result += cleanTitle(it.text()) }
        return result.filter { it.isNotBlank() }.take(20)
    }

    private fun pageActors(document: Document, baseUrl: String): List<Actor> {
        val result = LinkedHashMap<String, Actor>()
        document.select("a[href*='/oyuncu/']").forEach { item ->
            val name = cleanTitle(item.text())
            if (name.isNotBlank()) result.putIfAbsent(name, Actor(name, imageFrom(item, baseUrl)))
        }
        return result.values.take(40)
    }

    private fun cleanTitle(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    private fun seasonNumber(value: String): Int? {
        return listOf(
            Regex("""(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\b"""),
            Regex("""(?i)/(?:season|sezon)[-_]?(\d{1,2})(?:/|$)"""),
        ).asSequence().mapNotNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }.firstOrNull()
    }

    private fun readEpisodeNumbers(value: String): Pair<Int, Int>? {
        val text = value.replace("_", " ").replace("-", " ")
        Regex("""(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season)\s*(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode)\b""").find(text)?.let {
            val s = it.groupValues[1].toIntOrNull() ?: return null
            val e = it.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""").find(text)?.let {
            val s = it.groupValues[1].toIntOrNull() ?: return null
            val e = it.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        return null
    }

    private fun readEpisodeNumber(value: String): Int? {
        return listOf(
            Regex("""(?i)\b(?:bölüm|bolum|episode)\s*[#.:_-]*\s*(\d{1,3})\b"""),
            Regex("""(?i)\bS\d{1,2}\s*E(\d{1,3})\b"""),
        ).asSequence().mapNotNull { it.find(value)?.groupValues?.lastOrNull()?.toIntOrNull() }.firstOrNull()
    }

    private fun cleanEpisodeName(value: String): String = value
        .replace(Regex("""(?i)^\s*\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bölüm\s*"""), "")
        .replace(Regex("""(?i)^\s*S\d{1,2}\s*E\d{1,3}\s*[-:–—]*\s*"""), "")
        .trim(' ', '-', ':', '|')

    private fun imageFrom(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", true)) element else element.selectFirst("img") ?: return null
        return absolute(imageAttribute(img), baseUrl).takeIf { it.isNotBlank() }
    }

    private fun imageAttribute(image: Element): String {
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v = image.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:", true)) return v
        }
        return ""
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/").trim('"', '\'')
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun canonical(url: String): String = absolute(url, mainUrl).substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun pathOf(url: String): String = runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }.getOrDefault("")

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            val r = app.get(url, headers = headers + ("Referer" to referer), referer = referer)
            if (r.isSuccessful) r.document else null
        } catch (e: Exception) {
            Log.d(name, "Sayfa hatası: $url; ${e.message}")
            null
        }
    }
}
