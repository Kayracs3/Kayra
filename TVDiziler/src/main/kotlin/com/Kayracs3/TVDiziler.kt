package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.ArrayDeque

/**
 * TvDiziler.tv provider.
 *
 * Series pages (/dizi/<slug>), episode pages (usually /<series>-<number>-bolum-izle)
 * and movie pages (/film/<slug>) are parsed independently.
 */
class TVDiziler : MainAPI() {
    override var mainUrl = "https://tvdiziler.tv"
    override var name = "TVDiziler"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val tag = "TVDiziler"
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    private val posterHeaders = headers + ("Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/takvim" to "Dizi Takvimi",
        "$mainUrl/dizi-izle" to "Tüm Diziler",
        "$mainUrl/film-izle" to "Sinema Filmleri",
        "$mainUrl/film-izle/son-eklenen" to "Son Eklenen Filmler",
        "$mainUrl/film-izle/ilk-eklenen" to "İlk Eklenen Filmler",
        "$mainUrl/film-izle/imdb-yuksek" to "IMDb Puanı Yüksek Filmler",
        "$mainUrl/film-izle/imdb-dusuk" to "IMDb Puanı Düşük Filmler",
        "$mainUrl/film-izle/yeni-filmler" to "Yeni Yıl Filmleri",
        "$mainUrl/film-izle/eski-filmler" to "Eski Yıl Filmleri",
        "$mainUrl/film/tur/aile" to "Aile Filmleri",
        "$mainUrl/film/tur/aksiyon" to "Aksiyon Filmleri",
        "$mainUrl/film/tur/aksiyon-macera" to "Aksiyon & Macera Filmleri",
        "$mainUrl/film/tur/animasyon" to "Animasyon Filmleri",
        "$mainUrl/film/tur/belgesel" to "Belgesel Filmleri",
        "$mainUrl/film/tur/bilim-kurgu" to "Bilim Kurgu Filmleri",
        "$mainUrl/film/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi Filmleri",
        "$mainUrl/film/tur/cocuklar" to "Çocuk Filmleri",
        "$mainUrl/film/tur/dram" to "Dram Filmleri",
        "$mainUrl/film/tur/fantastik" to "Fantastik Filmler",
        "$mainUrl/film/tur/gerceklik" to "Gerçeklik Filmleri",
        "$mainUrl/film/tur/gerilim" to "Gerilim Filmleri",
        "$mainUrl/film/tur/gizem" to "Gizem Filmleri",
        "$mainUrl/film/tur/haber" to "Haber Filmleri",
        "$mainUrl/film/tur/komedi" to "Komedi Filmleri",
        "$mainUrl/film/tur/korku" to "Korku Filmleri",
        "$mainUrl/film/tur/macera" to "Macera Filmleri",
        "$mainUrl/film/tur/muzik" to "Müzik Filmleri",
        "$mainUrl/film/tur/pembe-dizi" to "Pembe Dizi Filmleri",
        "$mainUrl/film/tur/romantik" to "Romantik Filmler",
        "$mainUrl/film/tur/savas" to "Savaş Filmleri",
        "$mainUrl/film/tur/savas-politik" to "Savaş & Politik Filmleri",
        "$mainUrl/film/tur/suc" to "Suç Filmleri",
        "$mainUrl/film/tur/talk" to "Talk Filmleri",
        "$mainUrl/film/tur/tarih" to "Tarih Filmleri",
        "$mainUrl/film/tur/tv-film" to "TV Filmleri",
        "$mainUrl/film/tur/vahsi-bati" to "Vahşi Batı Filmleri",
        "$mainUrl/film/tur/yarisma" to "Yarışma Filmleri"
    )

    private val episodeNumberRegex = Regex("""(?i)(?:^|[-_/])(?:\d{1,4}[-_. ]*bolum|bolum[-_. ]*\d{1,4}|s\d{1,2}e\d{1,4})(?:[-_/]|$)""")
    private val mediaUrlRegex = Regex("""(?i)https?://[^\s"'<>\\]+""")
    private val subtitleUrlRegex = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:vtt|srt|ass|ssa)(?:\?[^\s"'<>\\]*)?""")

    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
        // Locale.ROOT lowercases capital Turkish İ as i + combining dot; strip the mark
        // before tokenizing so searches like "İstanbul" and "istanbul" normalize equally.
        .replace('\u0307', '')
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim().replace(Regex("""\s+"""), " ")

    private fun abs(rawValue: String?, base: String = mainUrl): String? {
        var raw = rawValue?.trim() ?: return null
        raw = raw.replace("\\/", "/")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")
            .trim('"', '\'')
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("mailto:", true) ||
            raw.startsWith("blob:", true)
        ) return null
        return runCatching {
            val resolved = when {
                raw.startsWith("//") -> URI("https:$raw")
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> URI(raw)
                else -> URI(base).resolve(raw.replace(" ", "%20"))
            }
            resolved.normalize().toString().substringBefore('#')
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun path(url: String): String = runCatching {
        URI(url).path.orEmpty().lowercase(Locale.ROOT)
    }.getOrDefault(url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT))

    private fun host(url: String): String = runCatching {
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    private fun sameSite(url: String): Boolean =
        host(url) == host(mainUrl) || host(url).endsWith("." + host(mainUrl))

    private fun isEpisodeUrl(url: String): Boolean =
        sameSite(url) && episodeNumberRegex.containsMatchIn(path(url))

    private fun isSeriesPage(url: String): Boolean {
        if (!sameSite(url) || isEpisodeUrl(url)) return false
        val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size != 2 || parts[0] !in setOf("dizi", "series", "tv")) return false
        if (parts[1] in setOf("tur", "oyuncu", "oyuncular", "takvim", "arama")) return false
        return !parts[1].startsWith("tur-") && !parts[1].startsWith("oyuncu-")
    }

    private fun isMoviePage(url: String): Boolean {
        if (!sameSite(url) || isEpisodeUrl(url)) return false
        val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || parts.firstOrNull().orEmpty() !in setOf("film", "movie", "sinema")) return false
        if (parts.getOrNull(1).orEmpty() in setOf("tur", "yil", "kategori", "oyuncu")) return false
        return parts.size == 2
    }

    private fun isContentUrl(url: String): Boolean =
        isEpisodeUrl(url) || isSeriesPage(url) || isMoviePage(url)

    private fun isNavigationElement(element: Element): Boolean {
        var node: Element? = element
        var depth = 0
        while (node != null && depth < 12) {
            val current = node
            val cls = (current.id() + " " + current.className()).lowercase(Locale.ROOT)
            if (current.tagName() in setOf("header", "nav", "footer")) return true
            if (Regex("""(^|[\s_-])(sidebar|widget|footer|header|navbar|navigation|login|register|comments|comment-form|user-panel)([\s_-]|$)""")
                    .containsMatchIn(cls)
            ) return true
            node = current.parent()
            depth++
        }
        return false
    }

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("""(?i)\s*[\-|–]\s*Tvdiziler(?:\.tv)?.*$"""), "")
        .replace(Regex("""(?i)\s*\|\s*Tvdiziler(?:\.tv)?.*$"""), "")
        .replace(Regex("""(?i)\s+\d{1,4}\s*\.?\s*bölüm(?:\s+.*)?$"""), "")
        .replace(Regex("""(?i)\s+(?:bölüm|episode)\s*\d{1,4}(?:\s+.*)?$"""), "")
        .replace(Regex("""(?i)\s+(?:son\s+bölüm|son\s+bolum)(?:\s+izle)?.*$"""), "")
        .replace(Regex("""(?i)\s+(?:full\s*)?izle(?:\s+hd)?\s*$"""), "")
        .replace(Regex("""(?i)\s*\(?((?:18|19|20|21)\d{2})\)?\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':', '–', '•')
        .take(160).trim()

    private fun validTitle(raw: String?): Boolean {
        val title = cleanTitle(raw)
        val value = norm(title)
        if (value.length < 2) return false
        if (value in setOf(
                "ana sayfa", "tv dizileri", "dizi dizileri", "sinema filmleri", "film izle",
                "dizi izle", "oyuncular", "arama", "giris yap", "kayit ol", "dikkate deger diziler",
                "son bolumler", "bolumler", "diziler", "filmler", "fragman", "partlar",
                "tartisma forumlari", "genel bakis", "yorumlar"
            )
        ) return false
        if (Regex("""(?i)^(?:null|undefined|unknown|poster|afiş|afis|image|resim|\d+)$""").matches(value)) return false
        return true
    }

    private fun titleFromSlug(url: String): String {
        var slug = path(url).trim('/').substringAfterLast('/')
        slug = slug
            .replace(Regex("""(?i)-\d{1,4}-bolum.*$"""), "")
            .replace(Regex("""(?i)-bolum-\d{1,4}.*$"""), "")
            .replace(Regex("""(?i)-s\d{1,2}e\d{1,4}.*$"""), "")
            .replace(Regex("""(?i)-(?:son-)?bolum-izle.*$"""), "")
            .replace(Regex("""(?i)-izle(?:-hd)?$"""), "")
            .replace('-', ' ').replace('_', ' ').trim()
        return slug.split(Regex("""\s+""")).filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }

    private fun cardFor(anchor: Element): Element {
        var node: Element? = anchor
        var depth = 0
        while (node != null && depth < 8) {
            val current = node
            val images = current.select("img, picture img, [data-src], [data-poster], [data-image]")
            val links = current.select("a[href]").size
            val marker = (current.id() + " " + current.className()).lowercase(Locale.ROOT)
            val cardLike = Regex("""(^|[\s_-])(card|item|movie|film|dizi|episode|bolum|poster|cover|thumbnail|tile|series)([\s_-]|$)""")
                .containsMatchIn(marker)
            if (images.isNotEmpty() && links in 1..5 &&
                (cardLike || current.tagName() in setOf("article", "li", "figure") || current.childrenSize() <= 7)
            ) return current
            node = current.parent()
            depth++
        }
        return anchor
    }

    private fun usablePoster(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val url = abs(raw) ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val imagePath = uri.path.orEmpty().lowercase(Locale.ROOT)
        val file = imagePath.substringAfterLast('/')
        if (file.isBlank() || file == "none.png" || file == "no-image.png") return false
        if (Regex("""(?i)(logo|favicon|placeholder|no[-_]?image|blank|spacer|transparent|pixel|avatar|gravatar|default[-_]?poster)""")
                .containsMatchIn(file)
        ) return false
        if (listOf(".svg", ".ico", ".css", ".js", ".html", ".htm").any { file.endsWith(it) }) return false
        return uri.host?.isNotBlank() == true && imagePath.isNotBlank()
    }

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        val attrs = listOf(
            "data-poster", "data-poster-url", "data-poster-src", "data-cover",
            "data-image", "data-image-url", "data-image-src", "data-original",
            "data-original-src", "data-lazy-src", "data-src", "data-bg",
            "data-bg-image", "data-background", "data-background-image", "src",
            "data-srcset", "srcset"
        )
        for (attr in attrs) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:", true) ||
                raw.lowercase(Locale.ROOT) in setOf("null", "undefined", "0", "false", "lazy")
            ) continue
            val candidate = if (attr.contains("srcset", true)) {
                raw.split(',').mapNotNull { entry ->
                    val parts = entry.trim().split(Regex("""\s+"""), limit = 2)
                    val width = Regex("""^(\d+)w$""").find(parts.getOrElse(1) { "" })
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { it to width }
                }.maxByOrNull { it.second }?.first.orEmpty()
            } else raw
            val normalized = abs(candidate, base)
            if (usablePoster(normalized)) return normalized
        }
        val background = Regex("""(?i)url\(\s*['"]?([^)'" ]+)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)
        return abs(background, base)?.takeIf(::usablePoster)
    }

    private fun posterOf(anchor: Element, card: Element, base: String): String? {
        imageUrl(anchor, base)?.let { return it }
        val title = cleanTitle(anchor.attr("title").ifBlank { anchor.text() })
        val titleNorm = norm(title)
        card.select("img").forEach { image ->
            val alt = norm(image.attr("alt") + " " + image.attr("title"))
            if (titleNorm.isNotBlank() && (alt.contains(titleNorm) || titleNorm.contains(alt)) && alt.isNotBlank()) {
                imageUrl(image, base)?.let { return it }
            }
        }
        card.select("img, picture img, [data-poster], [data-poster-url], [data-image-url], [data-src], [data-lazy-src], [style*=background]")
            .forEach { imageUrl(it, base)?.let { poster -> return poster } }
        imageUrl(card, base)?.let { return it }
        return null
    }

    private fun scoreFrom(text: String): Float? {
        val raw = Regex("""(?i)(?:IMDb\s*(?:Puanı|Rating)?|IMDB|★)\s*[:：]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""")
            .find(text)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it <= 10f }
    }

    private fun episodeInfo(raw: String): Pair<Int?, Int?>? {
        val value = norm(raw)
        val patterns = listOf(
            Regex("""\b(?:s|season)\s*(\d{1,2})\s*[-_. ]*\s*(?:e|ep|episode)\s*(\d{1,4})\b"""),
            Regex("""\b(\d{1,2})\s*sezon\s*(\d{1,4})\s*bolum\b"""),
            Regex("""\b(\d{1,2})\s*sezon\s*bolum\s*(\d{1,4})\b""")
        )
        for (pattern in patterns) {
            val match = pattern.find(value) ?: continue
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null && episode in 1..10000) return season to episode
        }
        val number = Regex("""\b(\d{1,4})\s*\.?\s*bolum\b""").find(value)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\bbolum\s*(\d{1,4})\b""").find(value)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (number != null && number in 1..10000) {
            val season = Regex("""\b(\d{1,2})\s*\.?\s*sezon\b""").find(value)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            return season to number
        }
        return null
    }

    private suspend fun fetchDocument(url: String, referer: String = "$mainUrl/"): Document? {
        val response = runCatching {
            app.get(
                url,
                headers = headers + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 12000
            )
        }.onFailure {
            Log.d(tag, "Sayfa isteği başarısız: " + url, it)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) {
            Log.d(tag, "Sayfa HTTP " + response.code + ": " + url)
            return null
        }
        return response.document
    }

    private fun posterFromPage(document: Document, pageUrl: String, title: String): String? {
        val titleNorm = norm(title)
        document.select("img[alt], img[title]").forEach { image ->
            val alt = norm(image.attr("alt") + " " + image.attr("title"))
            if (titleNorm.isNotBlank() && (alt.contains(titleNorm) || titleNorm.contains(alt)) && alt.isNotBlank()) {
                imageUrl(image, pageUrl)?.let { return it }
            }
        }
        document.select(
            "main img, article img, [class*=poster] img, [class*=afis] img, " +
                "[class*=cover] img, [class*=detail] img, img[itemprop=image]"
        ).forEach { imageUrl(it, pageUrl)?.let { poster -> return poster } }

        document.select(
            "meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image], " +
                "meta[property=twitter:image], meta[itemprop=image], link[rel=image_src]"
        ).forEach { element ->
            val raw = if (element.tagName().equals("link", true)) element.attr("href") else element.attr("content")
            val url = abs(raw, pageUrl)
            if (usablePoster(url)) return url
        }

        document.select(
            "img, [data-poster], [data-poster-url], [data-image-url], [data-src], [data-lazy-src], [style*=background]"
        ).forEach { imageUrl(it, pageUrl)?.let { poster -> return poster } }
        return null
    }

    private fun descriptionFromPage(document: Document): String? {
        val meta = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.length > 25 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.length > 25 }
        if (!meta.isNullOrBlank()) return meta

        val heading = document.select("h2,h3,h4").firstOrNull {
            val txt = norm(it.text())
            txt == "genel bakis" || txt == "konu" || txt == "dizi konusu" || txt == "film konusu"
        } ?: return null
        var sibling = heading.nextElementSibling()
        var count = 0
        while (sibling != null && count < 6) {
            val text = sibling.text().trim()
            if (sibling.tagName() in setOf("h1", "h2", "h3", "h4")) break
            if (text.length in 30..2500) {
                val trimmed = text.substringBefore("Bu dizi özeti")
                    .substringBefore("Bu film özeti")
                    .trim()
                if (trimmed.length >= 25 && !norm(trimmed).contains("yorum yapabilmek icin lutfen giris")) {
                    return trimmed
                }
            }
            sibling = sibling.nextElementSibling()
            count++
        }
        return document.selectFirst("[class*=overview], [class*=synopsis], [class*=description], [itemprop=description]")
            ?.text()?.trim()?.takeIf { it.length > 30 && it.length < 2500 }
    }

    private fun genresFromPage(document: Document): List<String> {
        val found = LinkedHashSet<String>()
        val fullText = document.text()
        val raw = Regex(
            """(?is)(?:Türü|Türler|Tür)\s*:?\s*(.*?)(?=\s+(?:Ülke|Süre|Takipçiler|IMDb|Yapım\s*yılı|Öne\s*Çıkan\s*Oyuncular)\b|$)"""
        ).find(fullText)?.groupValues?.getOrNull(1).orEmpty()
        raw.split(',', '•', '|').map { it.trim().trim(':', '-', ' ') }
            .filter { it.length in 2..45 && !norm(it).contains("yok") }
            .forEach { found.add(it) }
        if (found.isEmpty()) {
            document.select("a[href*='/tur/'], a[href*='/genre/'], [class*=genre] a, [class*=genres] a")
                .forEach { link ->
                    val text = link.text().trim()
                    if (text.length in 2..40 && validTitle(text)) found.add(text)
                }
        }
        return found.toList()
    }

    private fun actorsFromPage(document: Document): List<Actor>? {
        val actors = LinkedHashMap<String, Actor>()
        document.select("a[href*='/oyuncu/'], a[href*='/actor/'], a[href*='/person/']").forEach { link ->
            val name = cleanTitle(link.text().ifBlank { link.attr("title") })
            if (!validTitle(name) || norm(name) in setOf("admin", "oyuncular")) return@forEach
            val image = link.selectFirst("img")?.let { imageUrl(it, link.baseUri().ifBlank { mainUrl }) }
            actors.putIfAbsent(norm(name), Actor(name, image))
        }

        if (actors.isEmpty()) {
            val heading = document.select("h2,h3,h4").firstOrNull { norm(it.text()).contains("öne cikan oyuncular") }
            var section: Element? = heading?.parent()
            var depth = 0
            while (section != null && depth < 4 && actors.isEmpty()) {
                section.select("a[href]").forEach { link ->
                    val name = cleanTitle(link.text().ifBlank { link.attr("title") })
                    if (validTitle(name) && norm(name) !in setOf("oyuncular", "admin")) {
                        val image = link.selectFirst("img")?.let { imageUrl(it, link.baseUri().ifBlank { mainUrl }) }
                        actors.putIfAbsent(norm(name), Actor(name, image))
                    }
                }
                section = section.parent()
                depth++
            }
        }
        return actors.values.take(60).takeIf { it.isNotEmpty() }
    }

    private fun yearFromPage(document: Document): Int? {
        val titleText = document.selectFirst("h1")?.text().orEmpty()
        Regex("""(?<!\d)(?:18|19|20|21)\d{2}(?!\d)""").find(titleText)?.value?.toIntOrNull()?.let { return it }
        val text = document.text()
        Regex("""(?i)Yapım\s*yılı\s*:?\s*((?:18|19|20|21)\d{2})""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun durationFromPage(document: Document): Int? {
        val match = Regex("""(?i)\bSüre\s*:?\s*(\d{1,3})\s*(?:dk|dakika|min|minutes?)?\b""")
            .find(document.text()) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..1000 }
    }

    private fun findSeriesUrl(document: Document, episodeUrl: String): String? {
        val candidates = document.select("a[href*='/dizi/'], a[href*='/series/'], a[href*='/tv/']")
        val ranked = candidates.mapNotNull { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { episodeUrl }) ?: return@mapNotNull null
            if (!isSeriesPage(url)) return@mapNotNull null
            val label = norm(anchor.text() + " " + anchor.attr("title") + " " + anchor.attr("aria-label"))
            val score = (if (label.contains("dizi sayfasina git")) 100 else 0) +
                (if (label.contains("genel bakis")) 20 else 0) +
                (if (path(url).contains("son-bolum")) 10 else 0)
            url to score
        }
        return ranked.maxByOrNull { it.second }?.first
    }

    private fun extractEpisodes(
        document: Document,
        pageUrl: String,
        seriesTitle: String,
        seriesPoster: String?,
        includePageEpisode: Boolean = false
    ): List<Episode> {
        val output = LinkedHashMap<String, Episode>()
        val titleWords = norm(seriesTitle).split(' ').filter { it.length >= 3 }
            .filterNot { it in setOf("son", "bolum", "izle", "dizi", "hd") }

        document.select("a[href]").forEach { anchor ->
            if (isNavigationElement(anchor)) return@forEach
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { pageUrl }) ?: return@forEach
            if (!sameSite(url) || !isEpisodeUrl(url)) return@forEach
            val parentText = anchor.parent()?.text().orEmpty().take(240)
            val label = anchor.text() + " " + anchor.attr("title") + " " + anchor.attr("aria-label") +
                " " + anchor.attr("data-title") + " " + path(url).replace('-', ' ') + " " + parentText
            val info = episodeInfo(label) ?: episodeInfo(url) ?: return@forEach
            val number = info.second ?: return@forEach
            val normalized = norm(label + " " + url)
            if (titleWords.isNotEmpty() && titleWords.none { normalized.contains(it) }) {
                if (!includePageEpisode && path(url).contains("/dizi/")) {
                    val slug = norm(path(url).substringAfterLast('/').replace('-', ' '))
                    if (titleWords.none { slug.contains(it) }) return@forEach
                } else if (includePageEpisode) {
                    return@forEach
                }
            }

            val season = info.first
                ?: Regex("""(?i)(\d{1,2})\s*\.?\s*sezon""").find(norm(label)
                    )?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            val episodeLabel = "$number. Bölüm" +
                if (norm(label).contains("sezon finali")) " Sezon Finali" else ""
            val poster = posterOf(anchor, cardFor(anchor), url) ?: seriesPoster
            val description = parentText.takeIf { it.length in 40..700 }
            output.putIfAbsent(url, newEpisode(url) {
                name = episodeLabel
                this.season = season.coerceIn(1, 100)
                episode = number
                posterUrl = poster
                this.description = description
            })
        }

        if (includePageEpisode && isEpisodeUrl(pageUrl) && !output.containsKey(pageUrl)) {
            val info = episodeInfo(document.selectFirst("h1")?.text().orEmpty() + " " + path(pageUrl).replace('-', ' '))
            output[pageUrl] = newEpisode(pageUrl) {
                name = info?.second?.let { "$it. Bölüm" } ?: "Son Bölüm"
                season = info?.first ?: 1
                episode = info?.second
                posterUrl = seriesPoster
            }
        }
        return output.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    private suspend fun buildMovieLoad(url: String, document: Document): LoadResponse {
        val text = document.text()
        val title = cleanTitle(document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()).ifBlank { titleFromSlug(url) }
        val poster = posterFromPage(document, url, title)
        val actors = actorsFromPage(document)
        val genres = genresFromPage(document)
        val plot = descriptionFromPage(document)
        val year = yearFromPage(document)
        val duration = durationFromPage(document)
        val score = scoreFrom(text)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            posterHeaders = this@TVDiziler.posterHeaders
            this.plot = plot
            this.year = year
            this.duration = duration
            this.tags = genres
            actors?.let { addActors(it) }
            score?.let { this.score = Score.from10(it) }
        }
    }

    private suspend fun buildSeriesLoad(url: String, document: Document): LoadResponse {
        val title = cleanTitle(document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()).ifBlank { titleFromSlug(url) }
        val poster = posterFromPage(document, url, title)
        val actors = actorsFromPage(document)
        val genres = genresFromPage(document)
        val plot = descriptionFromPage(document)
        val year = yearFromPage(document)
        val duration = durationFromPage(document)
        val score = scoreFrom(document.text())
        val uniqueEpisodes = LinkedHashMap<String, Episode>()

        extractEpisodes(document, url, title, poster).forEach { uniqueEpisodes.putIfAbsent(it.data, it) }

        val seasonPages = document.select("a[href]").mapNotNull { anchor ->
            val candidate = abs(anchor.attr("href"), anchor.baseUri().ifBlank { url }) ?: return@mapNotNull null
            val label = norm(anchor.text() + " " + anchor.attr("title") + " " + path(candidate))
            if (sameSite(candidate) && !isEpisodeUrl(candidate) &&
                (label.contains("sezon") || path(candidate).contains("season"))
            ) candidate else null
        }.distinct().filter { it.trimEnd('/') != url.trimEnd('/') }.take(15)

        seasonPages.forEach { seasonUrl ->
            val seasonDoc = fetchDocument(seasonUrl, url) ?: return@forEach
            extractEpisodes(seasonDoc, seasonUrl, title, poster).forEach { uniqueEpisodes.putIfAbsent(it.data, it) }
        }

        val episodes = uniqueEpisodes.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )
        Log.d(tag, "Dizi ayrıştırıldı: " + title + ", sezon/bölüm bağlantısı=" + episodes.size)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            posterHeaders = this@TVDiziler.posterHeaders
            this.plot = plot
            this.year = year
            this.duration = duration
            this.tags = genres
            actors?.let { addActors(it) }
            score?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = abs(url, mainUrl) ?: return null
        val document = fetchDocument(pageUrl) ?: return null

        if (isEpisodeUrl(pageUrl)) {
            val seriesUrl = findSeriesUrl(document, pageUrl)
            if (!seriesUrl.isNullOrBlank()) {
                val seriesDocument = if (seriesUrl.trimEnd('/') == pageUrl.trimEnd('/')) document
                    else fetchDocument(seriesUrl, pageUrl)
                if (seriesDocument != null) return buildSeriesLoad(seriesUrl, seriesDocument)
            }

            val info = episodeInfo(document.selectFirst("h1")?.text().orEmpty() + " " + path(pageUrl).replace('-', ' '))
            val seriesTitle = cleanTitle(document.selectFirst("h1")?.text() ?: titleFromSlug(pageUrl))
                .ifBlank { titleFromSlug(pageUrl) }
            val title = cleanTitle(seriesTitle).ifBlank { "Dizi" }
            val poster = posterFromPage(document, pageUrl, title)
            val currentEpisode = newEpisode(pageUrl) {
                name = info?.second?.let { "$it. Bölüm" } ?: "Son Bölüm"
                season = info?.first ?: 1
                episode = info?.second
                posterUrl = poster
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, listOf(currentEpisode)) {
                posterUrl = poster
                posterHeaders = this@TVDiziler.posterHeaders
                this.plot = descriptionFromPage(document)
                this.year = yearFromPage(document)
                this.duration = durationFromPage(document)
                this.tags = genresFromPage(document)
                actorsFromPage(document)?.let { addActors(it) }
                scoreFrom(document.text())?.let { this.score = Score.from10(it) }
            }
        }

        return when {
            isMoviePage(pageUrl) -> buildMovieLoad(pageUrl, document)
            isSeriesPage(pageUrl) -> buildSeriesLoad(pageUrl, document)
            else -> {
                Log.d(tag, "Desteklenmeyen içerik rotası: " + pageUrl)
                null
            }
        }
    }

    /**
     * Parse actual movie/series/episode cards from catalog and search pages.
     * Navigation, genre, actor and utility links are filtered by isContentUrl().
     */
    private fun parseItems(document: Document, base: String, maxItems: Int = 100): List<SearchResponse> {
        val items = LinkedHashMap<String, SearchResponse>()
        val titleSelector = "h1,h2,h3,h4,h5,[itemprop=name],[class*=title],[class*=name]"

        document.select("a[href]").forEach { anchor ->
            if (isNavigationElement(anchor)) return@forEach

            val listingBase = anchor.baseUri().ifBlank { base.ifBlank { mainUrl } }
            val url = abs(anchor.attr("href"), listingBase) ?: return@forEach
            if (!isContentUrl(url)) return@forEach

            val card = cardFor(anchor)
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val rawTitleCandidates = listOf(
                anchor.selectFirst(titleSelector)?.text(),
                card.selectFirst(titleSelector)?.text(),
                anchor.attr("data-title"),
                card.attr("data-title"),
                image?.attr("alt"),
                image?.attr("title"),
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.ownText(),
                anchor.text(),
                titleFromSlug(url)
            )

            val title = rawTitleCandidates
                .map { cleanTitle(it) }
                .firstOrNull { validTitle(it) }
                ?: return@forEach

            val poster = posterOf(anchor, card, listingBase)?.takeIf { usablePoster(it) }
            val score = scoreFrom(card.text() + " " + anchor.attr("aria-label"))
            val response = if (isMoviePage(url)) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = this@TVDiziler.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = this@TVDiziler.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            }

            val previous = items[url]
            if (previous == null || (previous.posterUrl.isNullOrBlank() && !response.posterUrl.isNullOrBlank())) {
                items[url] = response
            }
        }

        return items.values
            .sortedByDescending { usablePoster(it.posterUrl) }
            .take(maxItems)
    }

    private fun matchesQuery(query: String, item: SearchResponse): Boolean {
        val q = norm(query)
        val title = norm(cleanTitle(item.name))
        val slug = norm(path(item.url).trim('/').substringAfterLast('/').replace('-', ' ').replace('_', ' '))
        if (q.isBlank() || title.isBlank()) return false
        if (title == q || title.contains(q) || slug.contains(q)) return true

        val ignored = setOf("the", "a", "an", "of", "to", "and", "ve", "bir", "ile", "in", "on", "izle", "full")
        val parts = q.split(' ').filter { it.length >= 2 && it !in ignored }
        if (parts.isEmpty()) return title.contains(q) || slug.contains(q)

        val words = (title + " " + slug).split(' ').filter { it.isNotBlank() }
        return parts.all { term ->
            words.any { word ->
                word == term ||
                    (term.length >= 4 && word.length >= 4 &&
                        (word.startsWith(term) || term.startsWith(word)))
            }
        }
    }

    private fun relevance(query: String, item: SearchResponse): Int {
        val q = norm(query)
        val title = norm(cleanTitle(item.name))
        val slug = norm(path(item.url).trim('/').substringAfterLast('/').replace('-', ' ').replace('_', ' '))
        return when {
            title == q -> 1000
            title.startsWith(q) -> 850
            title.contains(q) -> 750
            slug == q -> 700
            slug.contains(q) -> 600
            else -> q.split(' ').count { it.length >= 2 && (title.contains(it) || slug.contains(it)) } * 10
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?arama=$encoded",
            "$mainUrl/?query=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?query=$encoded",
            "$mainUrl/arama/$encoded/",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?arama=$encoded",
            "$mainUrl/arama/?arama=$encoded",
            "$mainUrl/ara/$encoded/",
            "$mainUrl/ara?ara=$encoded",
            "$mainUrl/ara?arama=$encoded"
        ).distinct()

        val seen = LinkedHashMap<String, SearchResponse>()
        urls.forEach { searchUrl ->
            val document = fetchDocument(searchUrl) ?: return@forEach
            parseItems(document, searchUrl, maxItems = 400).filter { matchesQuery(q, it) }.forEach { item ->
                val current = seen[item.url]
                if (current == null || (current.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) {
                    seen[item.url] = item
                }
            }
            if (seen.values.any { relevance(q, it) >= 850 }) {
                return fillMissingPosters(seen.values.sortedByDescending { relevance(q, it) }.take(80))
            }
        }

        // Some versions return the ordinary archive for unsupported search parameters.
        listOf("$mainUrl/dizi-izle", "$mainUrl/film-izle/son-eklenen", "$mainUrl/").forEach { fallback ->
            val document = fetchDocument(fallback) ?: return@forEach
            parseItems(document, fallback, maxItems = 400).filter { matchesQuery(q, it) }.forEach { item ->
                val current = seen[item.url]
                if (current == null || (current.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) {
                    seen[item.url] = item
                }
            }
        }

        return fillMissingPosters(seen.values.sortedByDescending { relevance(q, it) }.take(80))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun fillMissingPosters(items: List<SearchResponse>): List<SearchResponse> {
        val output = items.toMutableList()
        val indices = items.indices.filter { !usablePoster(items[it].posterUrl) }.take(16)
        for (batch in indices.chunked(4)) {
            val resolved = batch.map { index ->
                val item = items[index]
                val doc = fetchDocument(item.url)
                val title = cleanTitle(doc?.selectFirst("h1")?.text()
                    ?: doc?.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: item.name)
                val poster = doc?.let { posterFromPage(it, item.url, title) }
                if (!usablePoster(poster)) null else {
                    if (isMoviePage(item.url)) {
                        newMovieSearchResponse(item.name, item.url, TvType.Movie) {
                            posterUrl = poster
                            posterHeaders = this@TVDiziler.posterHeaders
                        }
                    } else {
                        newTvSeriesSearchResponse(item.name, item.url, TvType.TvSeries) {
                            posterUrl = poster
                            posterHeaders = this@TVDiziler.posterHeaders
                        }
                    }
                }
            }
            batch.zip(resolved).forEach { (index, replacement) ->
                if (replacement != null) output[index] = replacement
            }
        }
        return output
    }

    private fun pageCandidates(base: String, page: Int): List<String> {
        if (page <= 1) return listOf(base)
        val clean = base.trimEnd('/')
        val separator = if (base.contains("?")) "&" else "?"
        return listOf(
            base + separator + "page=" + page,
            base + separator + "sayfa=" + page,
            base + separator + "p=" + page,
            clean + "/page/" + page + "/"
        ).distinct()
    }

    private fun hasNextPage(document: Document): Boolean =
        document.select(
            "a[rel=next], .pagination a[href], .pager a[href], .page-numbers[href], " +
                "a[href*='page='], a[href*='/page/'], a.next[href]"
        ).any { anchor ->
            val label = norm(anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.className())
            label.contains("next") || label.contains("sonraki") || label.contains("ileri") ||
                anchor.attr("rel").equals("next", true) ||
                anchor.hasClass("next") || anchor.attr("href").contains("page=") ||
                anchor.attr("href").contains("/page/")
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        for (candidate in pageCandidates(request.data, page)) {
            val document = fetchDocument(candidate) ?: continue
            val items = parseItems(document, candidate)
            if (items.isNotEmpty()) {
                Log.d(tag, "Liste: " + request.name + ", sayfa=" + page + ", içerik=" + items.size)
                return newHomePageResponse(request.name, items.take(100), page < 100 && hasNextPage(document))
            }
        }
        return newHomePageResponse(request.name, emptyList(), false)
    }

    // ---- Player/source extraction -------------------------------------------------

    private fun decodeEmbedded(raw: String): String {
        var text = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u003A", ":").replace("\\u003a", ":")
            .replace("\\u0026", "&").replace("\\u003d", "=")
            .replace("\\x2F", "/").replace("\\x3A", ":")
            .replace("&amp;", "&").replace("&#x3a;", ":").replace("&#x2f;", "/")
        if (text.contains("%3A%2F%2F", true) || text.contains("%2F", true)) {
            text += "\n" + runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault("")
        }
        return text
    }

    private fun isMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val p = uri.path.orEmpty().lowercase(Locale.ROOT)
        return listOf(".m3u8", ".mp4", ".mpd", ".m4v", ".webm", ".mov").any { p.endsWith(it) } ||
            (p.contains("/hls/") && p.contains("playlist"))
    }

    private fun mediaCandidates(raw: String, base: String): List<String> {
        val text = decodeEmbedded(raw)
        val output = LinkedHashSet<String>()
        mediaUrlRegex.findAll(text).forEach { match ->
            val candidate = match.value.trimEnd(')', ']', '}', ';', ',')
            val url = abs(candidate, base) ?: return@forEach
            if (isMediaUrl(url)) output.add(url)
        }
        Regex("""(?i)//[^\s"'<>\\]+""").findAll(text).forEach { match ->
            val url = abs(match.value.trimEnd(')', ']', '}', ';', ','), base) ?: return@forEach
            if (isMediaUrl(url)) output.add(url)
        }
        val quotedSourceRegex = Regex("""(?i)(?:file|src|source|hls|url)\s*[:=]\s*["']([^"']{8,})["']""")
        quotedSourceRegex.findAll(text).forEach { match ->
            val url = abs(match.groupValues[1], base) ?: return@forEach
            if (isMediaUrl(url)) output.add(url)
        }
        return output.toList()
    }

    private fun playerCandidates(document: Document, base: String): List<String> {
        val output = LinkedHashSet<String>()
        document.select(
            "iframe[src],iframe[data-src],iframe[data-embed],iframe[data-url],iframe[data-player], " +
                "video[src],video source[src],source[src], [data-file], [data-video], [data-video-url], " +
                "[data-embed], [data-embed-url], [data-player], [data-player-url], [data-src]"
        ).forEach { element ->
            val tag = element.tagName()
            val context = (element.attr("id") + " " + element.className() + " " +
                element.attr("title") + " " + element.attr("aria-label")).lowercase(Locale.ROOT)
            val attrs = listOf(
                "src", "data-src", "data-file", "data-video", "data-video-url",
                "data-embed", "data-embed-url", "data-player", "data-player-url", "data-url"
            )
            attrs.forEach attrLoop@ { attr ->
                val raw = element.attr(attr).trim()
                val url = abs(raw, base) ?: return@attrLoop
                if (isMediaUrl(url) || tag in setOf("iframe", "video", "source") ||
                    Regex("""(?i)(player|embed|watch|video|stream|play|source|file|hls)""")
                        .containsMatchIn(context + " " + path(url))
                ) output.add(url)
            }
        }
        document.select("script[src]").forEach { script ->
            val url = abs(script.attr("src"), base) ?: return@forEach
            val p = path(url)
            if (p.endsWith(".js") && Regex("""(?i)(player|video|embed|hls|jw|stream|source)""").containsMatchIn(p)) {
                output.add(url)
            }
        }
        return output.toList().take(30)
    }

    private fun subtitleFromDocument(document: Document, base: String, callback: (SubtitleFile) -> Unit) {
        val seen = LinkedHashSet<String>()
        document.select("track[src], track[data-src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("href") } }
            val url = abs(raw, base) ?: return@forEach
            if (!Regex("""(?i)\.(?:vtt|srt|ass|ssa)(?:$|[?#])""").containsMatchIn(url) || !seen.add(url)) return@forEach
            val lang = Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""")
                .find(url)?.groupValues?.getOrNull(1) ?: element.attr("label").ifBlank { "Unknown" }
            callback(SubtitleFile(lang, url))
        }
        subtitleUrlRegex.findAll(decodeEmbedded(document.html())).forEach { match ->
            val url = abs(match.value.trimEnd(')', ']', '}', ';', ','), base) ?: return@forEach
            if (!seen.add(url)) return@forEach
            val lang = Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""")
                .find(url)?.groupValues?.getOrNull(1) ?: "Unknown"
            callback(SubtitleFile(lang, url))
        }
    }

    private suspend fun emitMedia(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaPath = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val mediaHost = host(url)
        val isTwitterVideoCdn = mediaHost == "video.twimg.com" || mediaHost == "video-s.twimg.com"
        // X's CDN may load a separate audio playlist beneath the HLS manifest. Every request
        // in that playlist needs the platform's Referer/Origin rather than TVDiziler's page.
        val mediaReferer = if (isTwitterVideoCdn) "https://x.com/" else referer
        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mediaReferer,
            "Accept" to "*/*"
        )
        val origin = runCatching { URI(mediaReferer).let { it.scheme + "://" + it.host } }.getOrNull()
        if (!origin.isNullOrBlank()) mediaHeaders["Origin"] = origin
        val type = when {
            mediaPath.endsWith(".m3u8") || mediaPath.contains("/hls/") -> ExtractorLinkType.M3U8
            mediaPath.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val label = when (type) {
            ExtractorLinkType.M3U8 -> "HLS"
            ExtractorLinkType.DASH -> "DASH"
            else -> "Video"
        }
        if (isTwitterVideoCdn && type == ExtractorLinkType.M3U8) {
            Log.d(tag, "X HLS manifesti doğrudan korunuyor; ses playlist'i için x.com başlıkları kullanılacak")
        }
        callback(newExtractorLink(name, "TVDiziler " + label, url, type) {
            this.referer = mediaReferer
            this.headers = mediaHeaders
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = abs(data, mainUrl) ?: return false
        val first = fetchDocument(startUrl, "$mainUrl/") ?: return false
        val queue = ArrayDeque<Pair<String, Document>>()
        queue.add(startUrl to first)
        val seenPages = LinkedHashSet<String>()
        val seenCandidates = LinkedHashSet<String>()
        val seenScripts = LinkedHashSet<String>()
        var found = false

        while (queue.isNotEmpty() && seenPages.size < 8) {
            val (pageUrl, document) = queue.removeFirst()
            if (!seenPages.add(pageUrl)) continue
            subtitleFromDocument(document, pageUrl, subtitleCallback)

            mediaCandidates(document.html(), pageUrl).forEach { media ->
                if (seenCandidates.add(media)) {
                    runCatching { emitMedia(media, pageUrl, callback) }
                        .onSuccess { found = found || it }
                }
            }

            val candidates = playerCandidates(document, pageUrl)
            candidates.forEach candidateLoop@ { candidate ->
                if (candidate == pageUrl || !seenCandidates.add(candidate)) return@candidateLoop
                if (isMediaUrl(candidate)) {
                    if (emitMedia(candidate, pageUrl, callback)) found = true
                    return@candidateLoop
                }

                if (!sameSite(candidate)) {
                    var extractorFound = false
                    runCatching {
                        val extractorCallback: (ExtractorLink) -> Unit = { link ->
                            callback(link)
                            extractorFound = true
                        }
                        loadExtractor(candidate, pageUrl, subtitleCallback, extractorCallback)
                    }.onFailure {
                        Log.d(tag, "Dış oynatıcı çözülemedi: " + host(candidate) + " / " + it.javaClass.simpleName)
                    }
                    if (extractorFound) {
                        found = true
                        return@candidateLoop
                    }
                }

                if (path(candidate).endsWith(".js")) {
                    if (seenScripts.size >= 12 || !seenScripts.add(candidate)) return@candidateLoop
                    val scriptResponse = runCatching {
                        app.get(candidate, headers = headers + ("Referer" to pageUrl), referer = pageUrl, timeout = 8000)
                    }.getOrNull()
                    if (scriptResponse != null && scriptResponse.isSuccessful && scriptResponse.text.isNotBlank()) {
                        mediaCandidates(scriptResponse.text, candidate).forEach { media ->
                            if (seenCandidates.add(media) && emitMedia(media, pageUrl, callback)) found = true
                        }
                    }
                    return@candidateLoop
                }

                if (seenPages.size + queue.size < 8) {
                    val nested = fetchDocument(candidate, pageUrl)
                    if (nested != null) queue.add(candidate to nested)
                }
            }
            Log.d(tag, "Oynatıcı taraması " + pageUrl + ": bulunan=" + found + ", aday=" + candidates.size)
        }
        return found
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    }
}
