package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.ArrayDeque

class TrDiziIzle : MainAPI() {
    override var mainUrl = "https://www.trdiziizle.tv"
    override var name = "TRDiziIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val tag = "TRDiziIzle"
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    private val posterHeaders = headers + ("Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    private val browserUserAgent = USER_AGENT

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/" to "Son Eklenen Diziler",
        "$mainUrl/dizi-arsivi-01/" to "Dizi Arşivi"
    )

    private val episodeRegex = Regex("""(?i)(?:bolum|episode)""")
    private val seasonRegex = Regex("""(?i)(?:sezon|season)""")
    // Capture the entire URL first; file extensions can occur inside filename=...mp4.
    private val mediaRegex = Regex("""(?i)https?://[^\s"'<>\\]+""")
    private val mediaExtensionRegex = Regex("""(?i)\.(?:m3u8|mp4|mpd|m4v|webm)(?:$|[?#&])""")
    private val subtitleRegex = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:vtt|srt|ass|ssa)(?:\?[^\s"'<>\\]*)?""")

    private fun abs(rawValue: String?, base: String = mainUrl): String? {
        var raw = rawValue?.trim().orEmpty()
        if (raw.isBlank() || raw.startsWith("javascript:", true) || raw.startsWith("data:", true) ||
            raw.startsWith("blob:", true) || raw.startsWith("mailto:", true) || raw.startsWith("about:", true)
        ) return null
        raw = raw.replace("&amp;", "&").replace("\\/", "/")
            .replace("\\u003A", ":").replace("\\u003a", ":")
            .replace("\\x3A", ":").replace("\\x3a", ":")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\x2F", "/").replace("\\x2f", "/")
            .replace("\\u0026", "&").replace("\\x26", "&")
            .trim('"', '\'')
        return runCatching {
            // Player pages sometimes omit the scheme on an iframe hostname (www.example.com/path).
            // Resolve that as a host, not as a path relative to /player/oynat/<id>/.
            val bareHost = Regex(
                """(?i)^(?:www\.)?[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9-]+)+(?:\:\d+)?(?:/|$)"""
            ).containsMatchIn(raw)
            val uri = when {
                raw.startsWith("//") -> URI("https:$raw")
                bareHost && !raw.contains("://") -> URI("https://$raw")
                else -> URI(base).resolve(raw)
            }
            uri.normalize().toString().substringBefore('#')
                .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun path(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault(url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT))
    private fun host(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
    private fun onSite(url: String) = host(url).isNotBlank() && host(url) == host(mainUrl)
    private fun isTrailerUrl(url: String): Boolean {
        if (!onSite(url)) return false
        val route = path(url).trim('/').lowercase(Locale.ROOT)
        if (Regex("""(^|/)(?:fragman(?:i)?|trailer|teaser|tanitim|preview)(?:/|$)""").containsMatchIn(route)) {
            return true
        }
        val slug = route.substringAfterLast('/')
        return Regex("""(?:^|[-_])(?:fragman(?:i)?|trailer|teaser|tanitim|preview)(?:[-_](?:izle|watch))?(?:[-_]\d+)?$""")
            .containsMatchIn(slug)
    }

    private fun isTrailerLabel(raw: String): Boolean {
        val value = norm(raw)
        return Regex("""(?:^|\s)(?:fragman|fragmani|trailer|teaser|tanitim|preview)(?:\s+(?:izle|watch))?$""")
            .containsMatchIn(value)
    }

    private fun isSeries(url: String): Boolean {
        if (!onSite(url) || isTrailerUrl(url)) return false
        val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size != 2) return false
        // The site uses /diziler/<slug>/ for series detail pages and may use
        // /bolumler/<slug>/ (or /episode/<slug>/) for individual episode pages.
        return parts[0] == "diziler" ||
            parts[0] == "bolum" || parts[0] == "bolumler" ||
            parts[0] == "episode" || parts[0] == "episodes"
    }

    private fun isMovie(url: String): Boolean {
        if (!onSite(url) || isTrailerUrl(url)) return false
        val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
        return parts.size == 2 && (parts[0] == "filmler" || parts[0] == "film")
    }

    private fun isEpisode(url: String) = onSite(url) &&
        (path(url).contains("bolum", true) || path(url).contains("episode", true))

    private fun isSeason(url: String) = onSite(url) &&
        (path(url).contains("sezon", true) || path(url).contains("season", true))

    private fun isItem(url: String) = isSeries(url) || isMovie(url)

    // The /tum-bolumler/ list links directly to episode pages, sometimes at the
    // site root (for example: /tuzlu-kahve-7-bolum-izle-full-tek/).
    private fun isLatestEpisodeUrl(url: String): Boolean {
        if (!onSite(url) || isTrailerUrl(url) || !isEpisode(url)) return false
        val routeText = path(url).trim('/').replace('-', ' ').replace('_', ' ')
        return episodeInfo(routeText) != null
    }

    private fun episodeSeriesTitleFromUrl(url: String): String {
        val slug = path(url).trim('/').substringAfterLast('/')
        val stem = slug.replace(
            Regex("""(?i)(?:[-_]\d{1,4})?[-_](?:bolum|episode).*$"""),
            ""
        ).replace('-', ' ').replace('_', ' ').trim()
        return stem.split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }

    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    private fun cleanTitle(raw: String?): String = raw.orEmpty()
        .replace(Regex("""(?i)\s*[\-|–]\s*Trdiziizle.*$"""), "")
        .replace(Regex("""(?i)\s*\|\s*Trdiziizle.*$"""), "")
        .replace(Regex("""(?i)\s+(?:son\s+bölüm|son\s+bolum)\s+izle.*$"""), "")
        .replace(Regex("""(?i)\s+(?:fragmanı\s+)?izle\s*$"""), "")
        .replace(Regex("""(?i)\s*(?:[-|:]\s*)?(?:fragman(?:ı|i)?|trailer|teaser|tanıtım|tanitim|preview)(?:\s+(?:izle|watch))?\s*$"""), "")
        .replace(Regex("""(?i)\s+\d{1,4}\s*\.?\s*bölüm(?:\s+.*)?$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':', '•')
        .take(150)
        .trim()

    private fun looksLikeTechnicalTitle(title: String): Boolean {
        val value = norm(title)
        if (Regex("""^(?:icerik|content)\s+[a-z0-9]{4,}$""").matches(value)) return true
        if (Regex("""^(?:icerik|content)[a-z0-9]{4,}$""").matches(value.replace(" ", ""))) return true

        // Avoid exposing compact player/database IDs as if they were titles.
        // Genuine Han/Hangul/Kana names are not tested against this Latin-ID rule.
        val hasAsianScript = title.any { c ->
            c in '\u3040'..'\u30ff' || c in '\u3400'..'\u9fff' || c in '\uac00'..'\ud7af'
        }
        if (hasAsianScript) return false

        val compact = value.replace(" ", "")
        val latinLetters = title.count {
            it in 'a'..'z' || it in 'A'..'Z'
        }
        val digits = title.count { it.isDigit() }
        return value.split(' ').size == 1 &&
            compact.length >= 7 &&
            digits >= 2 &&
            latinLetters <= 4
    }

    private fun validTitle(title: String): Boolean {
        val cleaned = cleanTitle(title)
        val value = norm(cleaned)
        return value.length >= 2 &&
            value !in setOf(
                "ana sayfa", "tum diziler", "tum filmler", "diziler", "filmler",
                "son eklenenler", "son eklenen diziler", "son eklenen bolumler", "tum bolumler",
                "dizi arsivi", "tumunu gor", "trdiziizle", "tr dizi izle",
                "film izle", "dizi izle", "fragman", "fragmani", "fragmani izle",
                "trailer", "teaser", "preview", "oynat", "indir", "poster",
                "bolum adi", "yayinlanma tarihi", "uye ol", "uye girisi",
                "icerik", "content", "unknown", "null"
            ) &&
            !looksLikeTechnicalTitle(cleaned)
    }

    private fun slugTitle(url: String): String =
        path(url).trimEnd('/').substringAfterLast('/').replace('-', ' ').replace('_', ' ')
            .split(Regex("""\s+""")).joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

    private fun scoreFrom(text: String): Float? =
        Regex("""(?i)(?:IMDb|IMDB|star|★)\s*[:：]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""")
            .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0f..10f }

    private fun pageCandidates(base: String, page: Int): List<String> {
        if (page <= 1) return listOf(base)
        val archive = Regex("""(?i)dizi-arsivi-\d+""").find(base)
        if (archive != null) return listOf(base.replace(
            archive.value, "dizi-arsivi-" + page.toString().padStart(2, '0')
        ))
        val clean = base.trimEnd('/')
        return listOf(
            "$clean/page/$page/",
            if (base.contains('?')) "$base&page=$page" else "$base?page=$page"
        ).distinct()
    }

    private fun cardFor(anchor: Element): Element {
        // Some archive cards put the title link and poster image in sibling elements.
        // Keep walking past generic wrappers; returning the anchor at the first large
        // parent caused every card to lose its poster on the archive pages.
        var node: Element? = anchor
        repeat(10) {
            val element = node ?: return anchor
            val marker = (element.id() + " " + element.className()).lowercase(Locale.ROOT)
            val looksLikeCard = Regex(
                """(^|[\s_-])(card|item|entry|post|video|movie|film|dizi|episode|bolum|box|thumb|thumbnail|poster|cover|photo|picture|tile|frame|image)([\s_-]|$)"""
            ).containsMatchIn(marker)
            val imageSelector =
                "img, picture img, source[srcset], [data-poster], [data-poster-url], [data-poster-src], " +
                    "[data-image], [data-image-url], [data-image-src], [data-srcset], [data-lazy-src], " +
                    "[data-lazy-load], [data-lazyload], [data-original], [data-original-src], [data-cover], " +
                    "[data-bg], [data-bg-url], [data-bg-image], [data-background], [data-background-image], " +
                    "[style*=background]"
            val hasImage = element.selectFirst(imageSelector) != null
            val descendantLinks = element.select("a[href]").size

            if (hasImage && descendantLinks in 1..4) {
                if (element == anchor || element.tagName() == "a") return element
                if (looksLikeCard || element.tagName() in setOf("article", "li", "figure")) {
                    return element
                }
                // Only accept unmarked compact wrappers that correspond to a single card.
                if (element.childrenSize() <= 8) return element
            }
            // Large listing wrappers may contain hundreds of images. Keep walking until
            // a small item wrapper is found instead of returning an empty anchor too early.
            node = element.parent()
        }
        return anchor
    }

    private fun isUsablePosterUrl(raw: String?): Boolean {
        val url = abs(raw, mainUrl) ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val pathValue = uri.path.orEmpty().lowercase(Locale.ROOT)
        val file = pathValue.substringAfterLast('/')

        // LiteSpeed's none.png is an empty placeholder, not a poster.
        // Reject the URL even when it is present in data-src so fallback can
        // continue to the real image in /wp-content/uploads/.
        if (file.equals("none.png", ignoreCase = true) ||
            pathValue.endsWith("/wp-content/themes/diziplus/images/none.png")
        ) return false

        if (Regex(
                """(?i)(logo|favicon|avatar|placeholder|no[-_]?image|noimage|blank|spacer|pixel|transparent|default[-_/]?poster)"""
            ).containsMatchIn(file)
        ) return false

        if (listOf(".svg", ".gif", ".ico", ".html", ".htm", ".css", ".js").any { file.endsWith(it) }) {
            return false
        }
        return uri.host?.isNotBlank() == true && pathValue.isNotBlank() &&
            !pathValue.endsWith("/favicon.ico")
    }

    private fun posterImageProxyUrl(imageUrl: String): String =
        "https://wsrv.nl/?url=" + URLEncoder.encode(imageUrl, "UTF-8") +
            "&w=384&output=webp&q=80"

    private fun normalizePosterUrl(rawValue: String?, base: String = mainUrl): String? {
        var url = abs(rawValue, base) ?: return null
        var uri = runCatching { URI(url) }.getOrNull() ?: return null

        // Unwrap image optimizers before handing URLs to CloudStream/Coil.
        if (uri.path.orEmpty().endsWith("/_next/image", ignoreCase = true)) {
            val encodedImage = uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.substringBefore('=').equals("url", true) }
                ?.substringAfter('=', "")
                ?.takeIf { it.isNotBlank() } ?: return null
            val original = runCatching { URLDecoder.decode(encodedImage, "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            url = abs(original, base) ?: return null
            uri = runCatching { URI(url) }.getOrNull() ?: return null
        }

        if (!isUsablePosterUrl(url)) return null

        val imageHost = uri.host.orEmpty().lowercase(Locale.ROOT)
        val imagePath = uri.path.orEmpty().lowercase(Locale.ROOT)
        val isPublicR2 = imageHost == "r2.dev" || imageHost.endsWith(".r2.dev")
        val isPotentiallyUnsupportedFormat =
            imagePath.endsWith(".avif") || imagePath.endsWith(".heic") || imagePath.endsWith(".heif")

        // Public R2 URLs and AVIF/HEIC files have previously failed in Coil.
        // Convert only these cases; keep ordinary WordPress JPG/PNG/WebP URLs direct.
        return if (isPublicR2 || isPotentiallyUnsupportedFormat) {
            posterImageProxyUrl(url)
        } else {
            url
        }
    }

    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null

        val imageAttributes = listOf(
            "data-poster-url", "data-poster-src", "data-poster", "data-cover",
            "data-image-url", "data-image-src", "data-image", "data-original-url",
            "data-original-src", "data-original", "data-lazy-srcset", "data-lazy-src",
            "data-fallback-src", "data-src-url", "data-echo",
            "data-lazy-load", "data-lazyload", "data-img", "data-thumb-url",
            "data-bg-url", "data-background-image", "data-srcset", "srcset",
            "data-thumbnail", "data-thumb", "data-bg-image", "data-background",
            "data-bg", "data-src", "src"
        )
        for (attr in imageAttributes) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:", true) ||
                raw.lowercase(Locale.ROOT) in setOf(
                    "true", "false", "yes", "no", "lazy", "loading", "loaded",
                    "1", "0", "null", "undefined"
                )
            ) continue

            // Choose the largest srcset candidate instead of always taking the first.
            val candidate = if (attr.contains("srcset", true)) {
                raw.split(',')
                    .mapNotNull { entry ->
                        val pieces = entry.trim().split(Regex("""\s+"""), limit = 2)
                        val candidateUrl = pieces.firstOrNull().orEmpty()
                        val descriptor = pieces.getOrNull(1).orEmpty()
                        val width = Regex("""^(\d+)w$""").matchEntire(descriptor)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        candidateUrl.takeIf { it.isNotBlank() }?.let { it to width }
                    }
                    .maxByOrNull { it.second }?.first.orEmpty()
            } else {
                raw
            }
            if (candidate.isBlank()) continue

            normalizePosterUrl(candidate, base)?.let { return it }
        }

        val bg = Regex("""(?i)url\(\s*['"]?([^)'\"]+)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)
        return normalizePosterUrl(bg, base)
    }

    private fun posterOf(anchor: Element, card: Element, base: String): String? =
        imageUrl(anchor, base)
            ?: imageUrl(
                anchor.selectFirst(
                    "img, picture img, source[srcset], [data-poster], [data-poster-url], [data-poster-src], [data-srcset], [data-lazy-src], [data-image-url], [data-image-src], [data-bg], [data-bg-image], [data-background], [style*=background-image]"
                ),
                base
            )
            ?: imageUrl(card, base)
            ?: imageUrl(
                card.selectFirst(
                    "img, picture img, source[srcset], [data-poster], [data-poster-url], [data-poster-src], [data-cover], [data-srcset], [data-lazy-src], [data-image-url], [data-image-src], [data-bg], [data-bg-image], [data-background], [style*=background-image]"
                ),
                base
            )

    private fun titleOf(anchor: Element, card: Element, poster: String?): String {
        val image = anchor.selectFirst("img") ?: card.selectFirst("img")
        val candidates = listOf(
            anchor.selectFirst("h1,h2,h3,h4,[class*=title],[class*=name]")?.text(),
            card.selectFirst("h2,h3,h4,[class*=title],[class*=name]")?.text(),
            image?.attr("alt"), image?.attr("title"), anchor.attr("title"), anchor.attr("aria-label"),
            card.attr("data-title"), anchor.ownText(), anchor.text(), card.text()
        )
        candidates.forEach { raw ->
            val title = cleanTitle(raw)
            if (validTitle(title)) return title
        }
        return cleanTitle(poster?.let { path(it).substringAfterLast('/').substringBeforeLast('.').replace('-', ' ') })
            .takeIf { validTitle(it) }.orEmpty()
    }

    private fun excludedNavigation(anchor: Element): Boolean {
        // Do not cap the ancestor walk: the site's A-Z block may be nested more
        // deeply than expected, and its series links must never become page items.
        var node: Element? = anchor
        while (node != null) {
            val element = node
            if (element.tagName().lowercase(Locale.ROOT) in setOf("header", "nav", "footer")) return true
            // The site's alphabetical index contains series-detail links but is not a content listing.
            if (element.hasClass("alphabetical-category-index")) return true
            val marker = (element.id() + " " + element.className()).lowercase(Locale.ROOT)
            if (Regex("""(^|[\s_-])(navbar|navigation|nav-menu|menu-primary|topmenu|top-menu|quick-menu|alphabet|alfabe|sidebar|widget|footer|header-menu|letter-list)([\s_-]|$)""")
                    .containsMatchIn(marker)
            ) return true
            node = element.parent()
        }
        return false
    }

    private fun parseItems(document: Document): List<SearchResponse> {
        // The current site puts a very large A-Z text index before its real poster cards.
        // Collect the whole page and prefer entries for which an actual poster was found.
        // An early "first 80 links" return incorrectly filled the result with posterless A-Z entries.
        val posterItems = LinkedHashMap<String, SearchResponse>()
        val fallbackItems = LinkedHashMap<String, SearchResponse>()

        document.select("a[href]").forEach { anchor ->
            if (excludedNavigation(anchor)) return@forEach

            // Resolve relative URLs against the page containing the card, not the item's detail URL.
            val listingBase = anchor.baseUri().ifBlank { mainUrl }
            val url = abs(anchor.attr("href"), listingBase) ?: return@forEach
            val anchorLabel = listOfNotNull(
                anchor.ownText(), anchor.attr("title"), anchor.attr("aria-label"),
                anchor.selectFirst("img")?.attr("alt"), anchor.selectFirst("img")?.attr("title")
            ).filter { it.isNotBlank() }.joinToString(" ")
            if (isTrailerUrl(url) || isTrailerLabel(anchorLabel)) return@forEach
            if (!isItem(url)) return@forEach

            val card = cardFor(anchor)
            val poster = posterOf(anchor, card, listingBase)
            val title = titleOf(anchor, card, poster)
            if (!validTitle(title)) return@forEach

            val score = scoreFrom(card.text() + " " + anchor.attr("aria-label"))
            val item = if (isSeries(url)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = this@TrDiziIzle.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = this@TrDiziIzle.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }
            }

            val target = if (!poster.isNullOrBlank()) posterItems else fallbackItems
            val old = target[url]
            if (old == null || (old.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) {
                target[url] = item
            }
        }

        Log.d(tag, "İçerik kartları: afişli=${posterItems.size}, afişsiz=${fallbackItems.size}")
        return (if (posterItems.isNotEmpty()) posterItems.values else fallbackItems.values).take(80)
    }

    private fun latestEpisodePoster(anchor: Element, base: String): String? {
        // Some TRDiziIzle cards render the poster as CSS background:url(...), not <img>.
        // Include lazy-load data attributes too; the card wrapper can vary between items.
        val imageSelector =
            "img, picture img, source[srcset], [data-poster], [data-poster-url], " +
                "[data-poster-src], [data-src], [data-lazy-src], [data-lazy-load], [data-lazyload], " +
                "[data-original], [data-original-src], [data-image], [data-image-url], " +
                "[data-image-src], [data-img], [data-bg], [data-bg-url], [data-bg-image], " +
                "[data-background], [data-background-image], [style*=background], [style*=url]"

        anchor.selectFirst(imageSelector)?.let { image ->
            imageUrl(image, base)?.let { return it }
        }
        imageUrl(anchor, base)?.let { return it }

        var node: Element? = anchor.parent()
        var depth = 0
        while (node != null && depth < 8) {
            val parent = node
            if (parent.id() == "listehizala") break

            val links = parent.select("a[href]").size
            val images = parent.select(imageSelector)
            val marker = (parent.id() + " " + parent.className()).lowercase(Locale.ROOT)
            val cardLike = Regex(
                """(^|[\s_-])(episode|episodes|bolum|bolumler|item|entry|post|card|video|dizi|box|thumb|thumbnail|poster|cover|listepisodes)([\s_-]|$)"""
            ).containsMatchIn(marker)
            val compactCard = parent.childrenSize() <= 14 &&
                links in 1..4 && images.size in 1..8

            if (images.isNotEmpty() &&
                (compactCard || (cardLike && links in 1..5 && images.size in 1..10))
            ) {
                images.forEach { image ->
                    imageUrl(image, base)?.let { return it }
                }
                // Do not climb into a neighbouring card after reaching the actual one.
                return null
            }

            if (parent.hasClass("listepisodes") && (links > 4 || images.size > 8)) break
            node = parent.parent()
            depth++
        }
        return null
    }

    private fun latestEpisodeTitle(anchor: Element, card: Element, url: String): String? {
        val image = anchor.selectFirst("img") ?: card.selectFirst("img")

        // A card may contain a separate link to /diziler/<slug>/ alongside its
        // episode link. Prefer that explicit series title over the date label.
        val relatedSeriesTitle = card.select("a[href]").asSequence()
            .filter { it != anchor }
            .mapNotNull { related ->
                val base = related.baseUri().ifBlank { mainUrl }
                val relatedUrl = abs(related.attr("href"), base) ?: return@mapNotNull null
                val parts = path(relatedUrl).trim('/').split('/').filter { it.isNotBlank() }
                if (parts.size != 2 || parts[0] != "diziler") return@mapNotNull null
                firstNonBlank(
                    related.selectFirst("h1,h2,h3,h4,[class*=title],[class*=name]")?.text(),
                    related.selectFirst("img")?.attr("alt"),
                    related.attr("title"),
                    related.attr("aria-label"),
                    related.text()
                )?.let(::cleanTitle)?.takeIf(::validTitle)
            }.firstOrNull()

        val candidates = listOfNotNull(
            relatedSeriesTitle,
            anchor.selectFirst("h1,h2,h3,h4,[class*=title],[class*=name]")?.text(),
            card.selectFirst("h2,h3,h4,[class*=title],[class*=name]")?.text(),
            image?.attr("alt"), image?.attr("title"),
            anchor.attr("title"), anchor.attr("aria-label"),
            card.attr("data-title"), anchor.ownText(), anchor.text(), card.text()
        ).map { it.trim() }.filter { it.isNotBlank() }

        val routeText = path(url).trim('/').replace('-', ' ').replace('_', ' ')
        val info = episodeInfo(routeText)
            ?: candidates.asSequence().mapNotNull { episodeInfo(it) }.firstOrNull()
            ?: return episodeSeriesTitleFromUrl(url).takeIf { validTitle(it) }
        val number = info.second ?: return null

        // Dates such as "25 Eki" were being accepted as valid show names. Remove
        // both numeric dates and Turkish month abbreviations before choosing a title.
        val numericDatePrefix = Regex(
            """(?i)^\s*\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\s*[-|:•]?\s*"""
        )
        val namedDatePrefix = Regex(
            """(?i)^\s*\d{1,2}\s+(?:oca(?:k)?|şub(?:at)?|mar(?:t)?|nis(?:an)?|may(?:ıs)?|haz(?:iran)?|tem(?:muz)?|ağu(?:stos)?|eyl(?:ül)?|eki(?:m)?|kas(?:ım)?|ara(?:lık)?)(?:\s+\d{4})?\s*[-|:•]?\s*"""
        )
        val dateOnly = Regex(
            """(?i)^\s*\d{1,2}(?:[./-]\d{1,2}[./-]\d{2,4}|\s+(?:oca(?:k)?|şub(?:at)?|mar(?:t)?|nis(?:an)?|may(?:ıs)?|haz(?:iran)?|tem(?:muz)?|ağu(?:stos)?|eyl(?:ül)?|eki(?:m)?|kas(?:ım)?|ara(?:lık)?))(?:\s+\d{4})?\s*$"""
        )

        val baseTitle = candidates.firstNotNullOfOrNull { raw ->
            val cleaned = cleanTitle(raw)
                .replace(
                    Regex("""(?i)\s*(?:[-|:]?\s*)?(?:son\s+bölüm|son\s+bolum|\d{1,4}\s*\.?\s*(?:bölüm|bolum|episode))(?:\s+.*)?$"""),
                    ""
                )
                .replace(numericDatePrefix, "")
                .replace(namedDatePrefix, "")
                .trim(' ', '-', '|', ':', '•')
            val normalized = norm(cleaned)
            cleaned.takeIf {
                validTitle(it) &&
                    !dateOnly.matches(it) &&
                    normalized !in setOf("son bolum", "bolum", "episode", "son episode") &&
                    !Regex("""^\d{1,2}\s+\d{1,2}\s+\d{2,4}$""").matches(normalized)
            }
        } ?: episodeSeriesTitleFromUrl(url)

        if (!validTitle(baseTitle)) return null
        val season = info.first
        return if (season != null && season > 1) {
            "$baseTitle $season. Sezon $number. Bölüm"
        } else {
            "$baseTitle $number. Bölüm"
        }
    }

    private fun parseLatestEpisodes(document: Document): List<SearchResponse> {
        // The site can render .listepisodes either as the list wrapper or as
        // repeated episode-card elements. Process EVERY matching element rather
        // than selectFirst(), which silently kept only the first card when the
        // class was repeated.
        val containers = document.select("#listehizala .listepisodes")
        if (containers.isEmpty()) {
            Log.w(tag, "Son bölüm alanı bulunamadı: #listehizala .listepisodes")
            return emptyList()
        }

        val items = LinkedHashMap<String, SearchResponse>()
        var anchorsSeen = 0

        containers.forEach { container ->
            container.select("a[href]").forEach anchorLoop@ { anchor ->
                anchorsSeen++
                val listingBase = anchor.baseUri().ifBlank { mainUrl }
                val url = abs(anchor.attr("href"), listingBase) ?: return@anchorLoop
                if (!isLatestEpisodeUrl(url)) return@anchorLoop

                val card = cardFor(anchor)
                val title = latestEpisodeTitle(anchor, card, url) ?: return@anchorLoop
                val relatedSeriesAnchor = card.select("a[href]").firstOrNull { related ->
                    if (related == anchor) return@firstOrNull false
                    val relatedUrl = abs(related.attr("href"), related.baseUri().ifBlank { listingBase }) ?: return@firstOrNull false
                    val parts = path(relatedUrl).trim('/').split('/').filter { it.isNotBlank() }
                    parts.size == 2 && parts[0] == "diziler"
                }
                val poster = latestEpisodePoster(anchor, listingBase)
                    ?: relatedSeriesAnchor?.let { posterOf(it, card, listingBase) }
                    ?: posterOf(anchor, card, listingBase)
                val score = scoreFrom(card.text() + " " + anchor.attr("aria-label"))
                val item = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = this@TrDiziIzle.posterHeaders
                    score?.let { this.score = Score.from10(it) }
                }

                val old = items[url]
                if (old == null || (old.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank())) {
                    items[url] = item
                }
            }
        }

        Log.d(
            tag,
            "Son bölüm alanı #listehizala .listepisodes: kapsayıcı=" +
                containers.size + ", bağlantı=" + anchorsSeen + ", benzersiz bölüm=" + items.size
        )
        return items.values.take(80)
    }

    private fun latestEpisodeSeriesDetailCandidates(
        item: SearchResponse,
        episodeDocument: Document?,
        listingDocument: Document?
    ): List<String> {
        val out = LinkedHashSet<String>()
        val expectedTitle = norm(cleanTitle(item.name))

        // The /tum-bolumler/ page includes an A-Z index whose real detail links
        // often look like /diziler/halka-son-bolum-izle-6/, not /diziler/halka/.
        // Match the visible series name to that actual href before guessing any URL.
        listingDocument?.select("a[href*='/diziler/']")?.forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { mainUrl }) ?: return@forEach
            val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
            if (!onSite(url) || parts.size != 2 || parts[0] != "diziler") return@forEach

            val label = firstNonBlank(
                anchor.selectFirst("h1,h2,h3,h4,[class*=title],[class*=name]")?.text(),
                anchor.selectFirst("img")?.attr("alt"),
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.text(),
                anchor.ownText()
            )?.let(::cleanTitle).orEmpty()
            val normalizedLabel = norm(label)
            if (normalizedLabel.isNotBlank() && expectedTitle.isNotBlank() &&
                (normalizedLabel == expectedTitle ||
                    normalizedLabel.contains(expectedTitle) ||
                    expectedTitle.contains(normalizedLabel))
            ) {
                out.add(url)
            }
        }

        // If the episode page itself links to the series detail page, prefer that too.
        episodeDocument?.select("a[href*='/diziler/']")?.forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { item.url }) ?: return@forEach
            val parts = path(url).trim('/').split('/').filter { it.isNotBlank() }
            if (onSite(url) && parts.size == 2 && parts[0] == "diziler") {
                val label = firstNonBlank(
                    anchor.selectFirst("img")?.attr("alt"),
                    anchor.attr("title"),
                    anchor.attr("aria-label"),
                    anchor.text()
                )?.let(::cleanTitle).orEmpty()
                val normalizedLabel = norm(label)
                if (normalizedLabel.isNotBlank() &&
                    (normalizedLabel == expectedTitle ||
                        normalizedLabel.contains(expectedTitle) ||
                        expectedTitle.contains(normalizedLabel))
                ) {
                    out.add(url)
                }
            }
        }

        // Fallback for episode URLs named /series-name-7-bolum-izle/.
        val routeSlug = path(item.url).trim('/').substringAfterLast('/')
        val seriesSlug = routeSlug.replace(
            Regex("""(?i)(?:[-_]\d{1,4})?[-_](?:bolum|episode).*$"""),
            ""
        ).trim('-', '_')
        if (seriesSlug.isNotBlank() && seriesSlug != routeSlug) {
            out.add("$mainUrl/diziler/$seriesSlug/")
        }

        // Last fallback for dated pages without an episode number in the URL.
        val titleSlug = expectedTitle.replace(' ', '-')
        if (titleSlug.isNotBlank()) out.add("$mainUrl/diziler/$titleSlug/")

        return out.filter { isSeries(it) }.take(5)
    }

    private suspend fun fillMissingLatestPosters(
        items: List<SearchResponse>,
        listingDocument: Document
    ): List<SearchResponse> =
        coroutineScope {
            val result = ArrayList<SearchResponse>(items.size)
            for (batch in items.chunked(5)) {
                val resolvedBatch = batch.map { item ->
                    async {
                        if (isUsablePosterUrl(item.posterUrl)) return@async item

                        var poster: String? = null
                        var episodeDocument: Document? = null

                        runCatching {
                            val response = app.get(
                                item.url,
                                headers = headers + ("Referer" to "$mainUrl/"),
                                referer = "$mainUrl/",
                                allowRedirects = true,
                                timeout = 6000
                            )
                            if (response.isSuccessful) {
                                episodeDocument = response.document
                                poster = posterFromPage(response.document, item.url)
                                    ?.takeIf(::isUsablePosterUrl)
                            }
                        }.onFailure {
                            Log.d(tag, "Bölüm sayfasından afiş alınamadı: " + item.url, it)
                        }

                        // The listing page's A-Z index gives the real series URL. Fetch
                        // that exact route, because the site's series URLs have suffixes.
                        if (!isUsablePosterUrl(poster)) {
                            val candidates = latestEpisodeSeriesDetailCandidates(
                                item, episodeDocument, listingDocument
                            )
                            Log.d(tag, "Afiş için dizi sayfası adayları: " + item.name + " -> " + candidates.joinToString())
                            for (seriesUrl in candidates) {
                                val candidatePoster = runCatching {
                                    app.get(
                                        seriesUrl,
                                        headers = headers + ("Referer" to "$mainUrl/"),
                                        referer = "$mainUrl/",
                                        allowRedirects = true,
                                        timeout = 6000
                                    ).takeIf { it.isSuccessful }?.document?.let { detail ->
                                        posterFromPage(detail, seriesUrl)
                                            ?.takeIf(::isUsablePosterUrl)
                                    }
                                }.onFailure {
                                    Log.d(tag, "Dizi afiş sayfası alınamadı: $seriesUrl", it)
                                }.getOrNull()

                                if (isUsablePosterUrl(candidatePoster)) {
                                    poster = candidatePoster
                                    break
                                }
                            }
                        }

                        if (!isUsablePosterUrl(poster)) {
                            Log.d(tag, "Son bölüm afişi bulunamadı: " + item.name + " / " + item.url)
                            item
                        } else {
                            Log.d(tag, "Son bölüm afişi bulundu: " + item.name + " -> " + poster)
                            newTvSeriesSearchResponse(item.name, item.url, TvType.TvSeries) {
                                posterUrl = poster
                                posterHeaders = this@TrDiziIzle.posterHeaders
                            }
                        }
                    }
                }.awaitAll()
                result.addAll(resolvedBatch)
            }
            result
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isLatestEpisodesPage = path(request.data).trimEnd('/') == "/tum-bolumler"
        for (url in pageCandidates(request.data, page)) {
            val document = runCatching {
                app.get(url, headers = headers, referer = "$mainUrl/", allowRedirects = true, timeout = 15000).document
            }.onFailure { Log.w(tag, "Ana sayfa alınamadı: " + url, it) }.getOrNull() ?: continue
            val parsedItems = if (isLatestEpisodesPage) parseLatestEpisodes(document) else parseItems(document)
            val items = if (isLatestEpisodesPage && parsedItems.isNotEmpty()) {
                fillMissingLatestPosters(parsedItems, document)
            } else {
                parsedItems
            }
            if (items.isNotEmpty()) {
                Log.d(tag, "Ana sayfa " + request.name + ", sayfa " + page + ": " + items.size + " içerik")
                return newHomePageResponse(request.name, items, page < 100)
            }
        }
        return newHomePageResponse(request.name, emptyList(), false)
    }

    private fun matchesQuery(query: String, item: SearchResponse): Boolean {
        val q = norm(query)
        val title = norm(item.name)
        val slug = norm(path(item.url).substringAfterLast('/').replace('-', ' '))
        if (q.isBlank() || title.isBlank()) return false
        if (title == q || title.contains(q) || slug.contains(q)) return true
        val stop = setOf("the", "a", "an", "of", "to", "and", "ve", "bir", "ile", "in", "on")
        val parts = q.split(' ').filter { it.length >= 2 && it !in stop }
        val tokens = (title + " " + slug).split(' ').toSet()
        return parts.isNotEmpty() && parts.all { term ->
            tokens.any { it == term || (term.length >= 4 && it.length >= 4 && (it.startsWith(term) || term.startsWith(it))) }
        }
    }

    private fun relevance(query: String, item: SearchResponse): Int {
        val q = norm(query)
        val title = norm(item.name)
        val slug = norm(path(item.url).substringAfterLast('/').replace('-', ' '))
        return when {
            title == q -> 1000
            title.startsWith(q) -> 850
            title.contains(q) -> 750
            slug == q -> 700
            slug.contains(q) -> 600
            else -> q.split(' ').count { title.contains(it) || slug.contains(it) } * 10
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/tr2/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/?search=$encoded"
        )
        for (url in searchUrls) {
            val document = runCatching { app.get(url, headers = headers, referer = "$mainUrl/", allowRedirects = true, timeout = 10000).document }
                .onFailure { Log.d(tag, "Arama isteği başarısız: $url") }.getOrNull() ?: continue
            val results = parseItems(document).filter { matchesQuery(q, it) }
            Log.d(tag, "Arama " + url.substringAfter(mainUrl) + ": " + results.size + " eşleşme")
            if (results.isNotEmpty()) return results.sortedByDescending { relevance(q, it) }.take(100)
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun detailTitle(document: Document, pageUrl: String): String? {
        val candidates = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("[itemprop=name]")?.text(),
            document.title()
        )

        candidates.forEach { raw ->
            val title = cleanTitle(raw)
            if (validTitle(title)) return title
        }

        // A route such as /dizi/icerik-wpgovb is an internal ID, not a title.
        // Only use a slug fallback when it really contains a human-readable name.
        val slugFallback = if (isLatestEpisodeUrl(pageUrl)) episodeSeriesTitleFromUrl(pageUrl) else slugTitle(pageUrl)
        return slugFallback.takeIf { validTitle(it) }
    }

    private fun extractActors(document: Document, text: String): List<Actor>? {
        val linked = document.select("a[href*='/oyuncu/'],a[href*='/actor/'],a[href*='/person/'],[class*=cast] a,[class*=actors] a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val names = if (linked.isNotEmpty()) linked else {
            val raw = Regex("""(?i)Oyuncular\s*:\s*(.*?)(?=\s+(?:Yapım\s*Yılı|Türler?|Yönetmen|Yayında|Sezonlar|Fragman|IMDb)\s*:?\s|$)""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            raw.split(',').map { it.trim().trim('.', '•', '|') }.filter { it.isNotBlank() && it.length < 70 }.distinct()
        }
        return names.take(40).map { Actor(it) }.takeIf { it.isNotEmpty() }
    }

    private fun extractGenres(document: Document, text: String): List<String> {
        val found = LinkedHashSet<String>()
        document.select("[class*=genre] a,[class*=genres] a,[class*=tag] a,a[href*='/tur/'],a[href*='/turler/'],a[href*='/genre/']")
            .forEach { el -> cleanTitle(el.text()).takeIf { validTitle(it) }?.let(found::add) }
        if (found.isEmpty()) {
            val raw = Regex("""(?i)Tür\s*:\s*(.*?)(?=\s+(?:Yapım\s*Yılı|Oyuncular|Yönetmen|Yayında|Sezonlar|Bölümler|Fragman|IMDb)\s*:?\s|$)""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            raw.split(',', '•', '|').map { it.trim() }.filter { it.isNotBlank() && it.length < 40 }.forEach(found::add)
        }
        return found.toList()
    }

    private fun seasonUrls(document: Document, pageUrl: String): Set<String> {
        val out = LinkedHashSet<String>()
        document.select("a[href]").forEach { anchor ->
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { pageUrl }) ?: return@forEach
            if (onSite(url) && isSeason(url)) out.add(url)
        }
        return out
    }

    private fun posterFromPage(document: Document, base: String): String? {
        // Prefer the actual LiteSpeed poster markup (alt="afis") first.
        // imageUrl validates src/data-src independently, so none.png is skipped
        // and the same element's real /wp-content/uploads/... image can be used.
        document.select("img[alt*=afis]").forEach { image ->
            imageUrl(image, base)?.let { return it }
        }

        // The site's true poster is commonly an uploaded JPG/WebP/PNG.
        // Check both src and lazy-loading attributes rather than accepting a placeholder.
        document.select(
            "img[src*='/wp-content/uploads/'], " +
                "img[data-src*='/wp-content/uploads/'], " +
                "img[data-lazy-src*='/wp-content/uploads/'], " +
                "img[data-original*='/wp-content/uploads/'], " +
                "img[data-original-src*='/wp-content/uploads/']"
        ).forEach { image ->
            imageUrl(image, base)?.let { return it }
        }

        // Open Graph/Twitter is a fallback, normalized by the same helper.
        document.select(
            "meta[property=og:image], meta[property=og:image:url], " +
                "meta[property=og:image:secure_url], meta[name=og:image], " +
                "meta[name=twitter:image], meta[property=twitter:image], " +
                "meta[itemprop=image], link[rel=image_src]"
        ).forEach { element ->
            val raw = if (element.tagName().equals("link", true)) {
                element.attr("href")
            } else {
                element.attr("content")
            }
            normalizePosterUrl(raw, base)?.let { return it }
        }

        document.select(
            "main img,article img,[class*=poster] img,[class*=afis] img," +
                "[data-poster],[data-poster-url],[data-poster-src],[data-image-url],[data-image-src]," +
                "[data-bg],[data-bg-image],[data-background],[data-background-image]," +
                "[style*=background],[style*=url]"
        ).forEach { image ->
            imageUrl(image, base)?.let { return it }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = abs(url, mainUrl) ?: return null
        if (!isItem(pageUrl) && !isLatestEpisodeUrl(pageUrl)) {
            Log.w(tag, "İçerik bağlantısı beklenen film/dizi/bölüm biçiminde değil: $pageUrl")
            return null
        }

        var response = runCatching {
            app.get(pageUrl, headers = headers, referer = "$mainUrl/", allowRedirects = true, timeout = 15000)
        }.onFailure {
            Log.w(tag, "İçerik sayfası ilk istek başarısız: $pageUrl", it)
        }.getOrNull()

        // Some detail pages are visible in the browser but reject the default
        // in-app user agent. Retry once with a normal browser request profile.
        if (response == null || !response.isSuccessful) {
            response = runCatching {
                app.get(
                    pageUrl,
                    headers = headers + mapOf(
                        "User-Agent" to browserUserAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                    referer = "$mainUrl/",
                    allowRedirects = true,
                    timeout = 15000
                )
            }.onFailure {
                Log.w(tag, "İçerik sayfası tarayıcı profiliyle de başarısız: $pageUrl", it)
            }.getOrNull()
        }

        if (response == null) return null
        if (!response.isSuccessful) {
            Log.w(tag, "İçerik sayfası HTTP " + response.code + ": " + pageUrl)
            return null
        }

        val document = response.document
        val text = document.text()
        val title = detailTitle(document, pageUrl) ?: run {
            Log.w(tag, "Okunabilir içerik başlığı bulunamadı: $pageUrl")
            return null
        }
        val poster = posterFromPage(document, pageUrl)
        val description = firstNonBlank(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("[class*=overview],[class*=description],[class*=synopsis]")?.text()
        )
        val year = Regex("""(?<!\d)(?:18|19|20|21)\d{2}(?!\d)""").find(text)?.value?.toIntOrNull()
        val score = scoreFrom(text)
        val genres = extractGenres(document, text)
        val actors = extractActors(document, text)

        if (isMovie(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = genres
                actors?.let { addActors(it) }
                score?.let { this.score = Score.from10(it) }
            }
        }

        val episodeMap = LinkedHashMap<String, Episode>()
        extractEpisodes(document, pageUrl, title, poster).forEach { episode ->
            episodeMap.putIfAbsent(episode.data, episode)
        }

        // Some series pages expose each season through a separate URL/tab. Fetch those
        // season pages too, then merge and sort their episodes instead of returning only
        // the subset visible on the first detail page.
        seasonUrls(document, pageUrl).filter { it.trimEnd('/') != pageUrl.trimEnd('/') }
            .take(20).forEach seasonLoop@ { seasonUrl ->
                val seasonResponse = runCatching {
                    app.get(
                        seasonUrl,
                        headers = headers + ("Referer" to pageUrl),
                        referer = pageUrl,
                        allowRedirects = true,
                        timeout = 10000
                    )
                }.onFailure {
                    Log.d(tag, "Sezon sayfası alınamadı: $seasonUrl", it)
                }.getOrNull() ?: return@seasonLoop
                if (!seasonResponse.isSuccessful) return@seasonLoop

                extractEpisodes(
                    seasonResponse.document, seasonUrl, title, poster,
                    includeCurrentPageEpisode = false
                ).forEach { episode ->
                    episodeMap.putIfAbsent(episode.data, episode)
                }
            }

        val episodes = episodeMap.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = genres
            actors?.let { addActors(it) }
            score?.let { this.score = Score.from10(it) }
        }
    }

    private fun episodeInfo(raw: String): Pair<Int?, Int?>? {
        val normalized = norm(raw)

        // Supports common forms: S03E08, Season 3 Episode 8, 3. Sezon 8. Bölüm,
        // 189.Bölüm and the reversed "Bölüm 189" label.
        val seasonEpisodePatterns = listOf(
            Regex("""\b(?:s|season)\s*(\d{1,2})\s*[-._ ]*\s*(?:e|ep|episode)\s*(\d{1,4})\b"""),
            Regex("""\b(\d{1,2})\s*(?:sezon|season)\s*(\d{1,4})\s*(?:bolum|episode)\b"""),
            Regex("""\b(\d{1,2})\s*(?:sezon|season)\s*(?:bolum|episode)\s*(\d{1,4})\b""")
        )
        for (pattern in seasonEpisodePatterns) {
            val match = pattern.find(normalized) ?: continue
            val season = match.groupValues[1].toIntOrNull()
            val number = match.groupValues[2].toIntOrNull()
            if (number != null && number in 1..10000) return season to number
        }

        val episodeNumber = Regex("""\b(\d{1,4})\s*(?:bolum|episode)\b""").find(normalized)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(?:bolum|episode)\s*(\d{1,4})\b""").find(normalized)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (episodeNumber != null && episodeNumber in 1..10000) {
            val season = Regex("""\b(\d{1,2})\s*(?:sezon|season)\b""")
                .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return season to episodeNumber
        }
        return null
    }

    private fun extractEpisodes(
        document: Document,
        pageUrl: String,
        seriesTitle: String,
        seriesPoster: String?,
        includeCurrentPageEpisode: Boolean = true
    ): List<Episode> {
        val output = LinkedHashMap<String, Episode>()
        val titleTerms = norm(seriesTitle).split(' ')
            .filter { it.length >= 3 }
            .filterNot { it in setOf("son", "bolum", "izle", "dizi", "hd") }

        // The site lists seasons/episodes in #genel .bolumust2, with the actual
        // episode link as a direct child; a separate .wpfp-link is only a favorite toggle.
        val scopedEpisodeAnchors = document.select("#genel .bolumust2 > a[href]")
        val anchors = if (scopedEpisodeAnchors.isNotEmpty()) {
            scopedEpisodeAnchors
        } else {
            document.select("a[href]")
        }

        for (anchor in anchors) {
            if (anchor.hasClass("wpfp-link") || excludedNavigation(anchor)) continue
            val url = abs(anchor.attr("href"), anchor.baseUri().ifBlank { pageUrl }) ?: continue
            if (!onSite(url)) continue
            val route = path(url).lowercase(Locale.ROOT)
            if (!(route.contains("/diziler/") || route.contains("/bolum/") ||
                    route.contains("/bolumler/") || route.contains("/episode/") ||
                    route.contains("/episodes/") || isLatestEpisodeUrl(url))
            ) continue
            val rowText = anchor.parent()?.text().orEmpty().take(240)
            val labelRaw = listOf(
                anchor.text(), anchor.attr("title"), anchor.attr("aria-label"),
                anchor.attr("data-title"), route.replace('-', ' '), rowText
            ).filter { it.isNotBlank() }.joinToString(" ")
            if (isTrailerLabel(labelRaw)) continue
            val info = episodeInfo(labelRaw) ?: continue
            val number = info.second ?: continue
            val normalizedText = norm(labelRaw)
            val normalizedRoute = norm(route.replace('-', ' '))
            if (titleTerms.isNotEmpty() &&
                titleTerms.none { normalizedText.contains(it) || normalizedRoute.contains(it) }
            ) continue

            val context = labelRaw + " " + anchor.parent()?.text().orEmpty().take(160)
            val season = info.first
                ?: Regex("""(\d{1,2})\s*(?:sezon|season)""").find(norm(context))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            val label = "$number. Bölüm" +
                if (norm(context).contains("sezon finali")) " Sezon Finali" else ""
            val card = cardFor(anchor)
            val poster = posterOf(anchor, card, url) ?: seriesPoster
            val description = anchor.parent()?.text()?.takeIf { it.length in 50..800 }
            output.putIfAbsent(url, newEpisode(url) {
                name = label
                this.season = season.coerceIn(1, 100)
                episode = number
                posterUrl = poster
                this.description = description
            })
        }

        val hasPlayer = document.select(
            "iframe[src],iframe[data-src],iframe[data-iframe],video[src],video source[src],[data-player],[data-embed],[data-video]"
        ).isNotEmpty() || mediaRegex.findAll(decodeEmbedded(document.html()))
            .any { isMedia(it.value.trimEnd(')', ']', '}', ';', ',')) }
        if (includeCurrentPageEpisode && (hasPlayer || isLatestEpisodeUrl(pageUrl)) &&
            output.keys.none { it.trimEnd('/') == pageUrl.trimEnd('/') }
        ) {
            val routeInfo = episodeInfo(path(pageUrl).replace('-', ' ').replace('_', ' '))
            val currentInfo = routeInfo ?: episodeInfo(
                document.selectFirst("h1")?.text().orEmpty() + " " + document.text().take(2500)
            )
            val number = currentInfo?.second
            output[pageUrl] = newEpisode(pageUrl) {
                name = number?.let { "$it. Bölüm" } ?: "Son Bölüm"
                season = currentInfo?.first ?: 1
                episode = number
                posterUrl = seriesPoster
            }
        }
        return output.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    // ---------------------------------------------------------------------
    // Playback: iframe chains, embedded media, JS strings and subtitles
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = abs(data, mainUrl) ?: return false
        val first = runCatching { app.get(episodeUrl, headers = headers, referer = "$mainUrl/", timeout = 15000) }
            .onFailure { Log.w(tag, "Bölüm/video sayfası açılamadı: $episodeUrl", it) }.getOrNull() ?: return false
        if (!first.isSuccessful) {
            Log.w(tag, "Bölüm/video HTTP " + first.code + ": " + episodeUrl)
            return false
        }
        val seenPages = LinkedHashSet<String>()
        val seenCandidates = LinkedHashSet<String>()
        val seenScripts = LinkedHashSet<String>()
        val seenSubtitleUrls = LinkedHashSet<String>()
        val pages = ArrayDeque<Pair<String, Document>>()
        pages.add(episodeUrl to first.document)
        var found = false

        while (pages.isNotEmpty() && seenPages.size < 6) {
            val (pageUrl, document) = pages.removeFirst()
            if (!seenPages.add(pageUrl)) continue
            collectSubtitles(document, pageUrl, subtitleCallback)
            val rawText = decodeEmbedded(document.html())
            scanMedia(rawText, pageUrl).forEach { media ->
                if (seenCandidates.add(media) && emitMedia(media, pageUrl, callback)) found = true
            }

            // Önce oynatıcıyla ilişkili dosyaları ara; genel JS dosyalarını sınırlı tut.
            // Böylece sayfa büyük reklam/tema dosyalarına takılıp dakikalarca beklemez.
            val pageCandidates = playerCandidates(document, rawText, pageUrl)
            val scriptCandidates = LinkedHashSet<String>()
            val allScripts = scriptUrls(document, pageUrl)
            val playerScripts = allScripts.filter {
                Regex("""(?i)(player|video|embed|hls|jwplayer|videojs|media|stream|source)""")
                    .containsMatchIn(it)
            }
            val hasExplicitIframe = document.select(
                "iframe[src],iframe[data-src],iframe[data-embed],iframe[data-player],iframe[data-iframe]"
            ).isNotEmpty()
            // The site's /player/oynat/ page loads the JW Player wrapper and its source
            // configuration separately. Do not let scripts from the outer episode page
            // consume the entire inspection budget before this nested page is reached.
            val currentPath = path(pageUrl)
            val nestedPlayerPage = currentPath.contains("/player/oynat/") ||
                currentPath.contains("/jwplayer")
            val scriptLimit = when {
                nestedPlayerPage -> 10
                hasExplicitIframe -> 3
                else -> 4
            }
            val scriptsToInspect = (playerScripts + allScripts.filterNot { it in playerScripts })
                .distinct().take(scriptLimit)
            scriptsToInspect.forEach scriptLoop@ { scriptUrl ->
                if (seenScripts.size >= 18 || !seenScripts.add(scriptUrl)) return@scriptLoop
                val scriptResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = headers + ("Referer" to pageUrl),
                        referer = pageUrl,
                        allowRedirects = true,
                        timeout = 8000
                    )
                }.getOrNull() ?: return@scriptLoop
                if (!scriptResponse.isSuccessful || scriptResponse.text.isBlank()) return@scriptLoop

                val scriptText = decodeEmbedded(scriptResponse.text)
                scanMedia(scriptText, scriptUrl).forEach { media ->
                    if (seenCandidates.add(media) && emitMedia(media, pageUrl, callback)) found = true
                }
                textPlayerCandidates(scriptText, scriptUrl).forEach(scriptCandidates::add)
            }

            val candidates = (pageCandidates + scriptCandidates).distinct()
                .sortedBy { candidate ->
                    when {
                        isMedia(candidate) -> 0
                        onSite(candidate) && path(candidate).contains("/player/oynat/") -> 1
                        !onSite(candidate) && Regex("""(?i)(player|embed|video|stream|watch|m3u8|playlist|source)""")
                            .containsMatchIn(path(candidate)) -> 2
                        !onSite(candidate) -> 3
                        else -> 4
                    }
                }
                .take(20)
            candidates.forEach candidateLoop@ { candidate ->
                if (!seenCandidates.add(candidate)) return@candidateLoop

                if (isMedia(candidate)) {
                    if (emitMedia(candidate, pageUrl, callback)) found = true
                    return@candidateLoop
                }
                if (onSite(candidate) && isItem(candidate)) return@candidateLoop

                var emitted = false
                runCatching {
                    val proxy: (ExtractorLink) -> Unit = { link ->
                        callback(link)
                        emitted = true
                    }
                    loadExtractor(candidate, pageUrl, subtitleCallback, proxy)
                }.onFailure {
                    Log.d(tag, "Extractor başarısız: " + host(candidate) + " / " + it.javaClass.simpleName)
                }
                if (emitted) {
                    found = true
                    return@candidateLoop
                }
                if (seenPages.size < 6 && pages.size < 5) {
                    // Follow redirects manually with a small limit. Some /player/oynat/
                    // endpoints loop through redirects until NiceHttp aborts after 20+ hops.
                    var currentUrl = candidate
                    var requestReferer = pageUrl
                    val redirectHistory = LinkedHashSet<String>()
                    var redirectCount = 0
                    var nested = runCatching {
                        app.get(
                            currentUrl,
                            headers = headers + ("Referer" to requestReferer),
                            referer = requestReferer,
                            allowRedirects = false,
                            timeout = 8000
                        )
                    }.getOrNull()

                    while (nested != null && nested.code in 300..399 && redirectCount < 5) {
                        if (!redirectHistory.add(currentUrl)) {
                            nested = null
                            break
                        }
                        val location = nested.headers["Location"] ?: nested.headers["location"]
                        val nextUrl = abs(location, currentUrl)
                        if (nextUrl.isNullOrBlank() || nextUrl in redirectHistory) {
                            nested = null
                            break
                        }
                        requestReferer = currentUrl
                        currentUrl = nextUrl
                        redirectCount++
                        nested = runCatching {
                            app.get(
                                currentUrl,
                                headers = headers + ("Referer" to requestReferer),
                                referer = requestReferer,
                                allowRedirects = false,
                                timeout = 8000
                            )
                        }.getOrNull()
                    }

                    if (nested != null && nested.isSuccessful && nested.text.isNotBlank()) {
                        pages.add(currentUrl to nested.document)
                    } else if (redirectCount >= 5) {
                        Log.d(tag, "Oynatıcı yönlendirme sınırı: " + candidate.substringAfter(mainUrl))
                    }
                }
            }
            if (!found) {
                Log.d(
                    tag,
                    "Oynatıcı adayları " + pageUrl.substringAfter(mainUrl) + ": " +
                        candidates.take(8).joinToString(" | ") { host(it) + path(it) }
                )
                Log.d(
                    tag,
                    "İncelenen oynatıcı betikleri " + pageUrl.substringAfter(mainUrl) + ": " +
                        scriptsToInspect.take(10).joinToString(" | ") { host(it) + path(it) }
                )
            }
            Log.d(tag, "Oynatıcı taraması " + pageUrl.substringAfter(mainUrl) +
                ": aday=" + candidates.size + ", bulunan=" + found)
        }
        return found
    }

    private fun playerCandidates(document: Document, rawText: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val attrs = listOf(
            "src", "data-src", "data-lazy-src", "data-url", "data-embed",
            "data-embed-url", "data-iframe", "data-file", "data-player",
            "data-player-url", "data-video", "data-video-url", "data-source",
            "data-hls", "data-src-hls", "data-stream"
        )
        document.select(
            "iframe,video,source,[data-src],[data-url],[data-embed],[data-embed-url]," +
                "[data-iframe],[data-file],[data-player],[data-player-url],[data-video]," +
                "[data-video-url],[data-source],[data-hls],[data-src-hls],[data-stream],meta[property=og:video]"
        ).forEach { element ->
            val context = element.tagName() + " " + element.text() + " " +
                element.attr("id") + " " + element.className() + " " +
                element.attr("title") + " " + element.attr("aria-label")
            attrs.forEach attrLoop@ { attr ->
                val url = abs(element.attr(attr), base) ?: return@attrLoop
                // A real iframe may use an opaque ID-only URL with no "player/embed" keyword.
                // Do not discard it merely because its path is not descriptive.
                val genericIframe = element.tagName() == "iframe" &&
                    !isIgnoredResource(url) &&
                    !Regex("""(?i)\.(?:css|mjs?|png|jpe?g|webp|svg|woff2?)(?:$|[?#])""")
                        .containsMatchIn(url)
                if (playerCandidate(url, context) || genericIframe) out.add(url)
            }
            if (element.tagName() == "meta") abs(element.attr("content"), base)?.let {
                if (playerCandidate(it, "video")) out.add(it)
            }
        }

        document.select("a[href]").filter {
            Regex("""(?i)(izle|oynat|player|video|kaynak|indir|stream)""")
                .containsMatchIn(it.text() + " " + it.attr("class") + " " + it.attr("aria-label"))
        }.forEach { anchor ->
            val url = abs(anchor.attr("href"), base) ?: return@forEach
            if (!onSite(url) && playerCandidate(url, anchor.text())) out.add(url)
        }

        scanMedia(rawText, base).forEach(out::add)
        Regex("""(?i)(?:src|file|source|url|embed|player|hls|playlist|videoUrl)["']?\s*[:=]\s*["']([^"']{6,1000})["']""")
            .findAll(rawText).forEach { match ->
                abs(match.groupValues.getOrNull(1), base)?.let { if (playerCandidate(it, "script")) out.add(it) }
            }
        return out.toList()
    }

    private fun scriptUrls(document: Document, base: String): List<String> {
        val out = LinkedHashSet<String>()
        document.select("script[src],link[rel=preload][as=script][href]").forEach { element ->
            val raw = if (element.tagName() == "script") element.attr("src") else element.attr("href")
            val url = abs(raw, base) ?: return@forEach
            val lower = url.lowercase(Locale.ROOT)
            if (isIgnoredResource(url)) return@forEach
            if (lower.contains(".js") || lower.contains(".mjs") || lower.contains("script")) out.add(url)
        }
        return out.toList()
    }

    private fun textPlayerCandidates(raw: String, base: String): List<String> {
        val text = decodeEmbedded(raw)
        val out = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""(?i)(?:src|file|source|url|embed|player|hls|playlist|videoUrl|streamUrl|manifest|mediaUrl|apiUrl|endpoint|requestUrl)["']?\s*[:=]\s*["']([^"'<>]{4,1800})["']"""),
            Regex("""(?i)(?:fetch|axios\.(?:get|post)|\.open|\.get|\.post)\s*\(\s*["']([^"'<>]{4,1800})["']"""),
            Regex("""(?i)["']((?:https?:)?//[^"'\\\s<>]{8,1800}|/(?:api|ajax|player|video|stream|source|playlist|embed|resolve|v[0-9]+)/[^"'\\\s<>]{1,1800})["']""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach matchLoop@ { match ->
                val rawUrl = match.groupValues.getOrNull(1).orEmpty()
                    .trimEnd(')', ']', '}', ';', ',')
                val url = abs(rawUrl, base) ?: return@matchLoop
                if (playerCandidate(url, match.value.take(160))) out.add(url)
            }
        }
        return out.toList()
    }

    private fun isIgnoredResource(url: String): Boolean {
        val lowerHost = host(url)
        val lower = url.lowercase(Locale.ROOT)
        return lowerHost in setOf(
            "static.cloudflareinsights.com",
            "google-analytics.com",
            "www.google-analytics.com",
            "googletagmanager.com",
            "connect.facebook.net",
            "doubleclick.net"
        ) || lower.contains("/beacon.min.js") || lower.contains("/analytics.")
    }

    private fun playerCandidate(url: String, context: String): Boolean {
        if (!url.startsWith("http", true) || isIgnoredResource(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        // Ignore static assets even when the URL includes a cache-busting query, e.g. jwplayer.js?v=4.
        val resourcePath = lower.substringBefore('?').substringBefore('#')
        if (listOf(".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".woff", ".woff2").any { resourcePath.endsWith(it) } ||
            listOf("favicon", "logo", "avatar", "placeholder").any { lower.contains(it) }
        ) return false
        if (isMedia(url)) return true
        if (onSite(url)) {
            val p = path(url)
            if (isItem(url) || p == "/" || p == "/diziler" || p == "/filmler" || p == "/son-eklenenler") return false
            return Regex("""(?i)(play|player|video|embed|watch|stream|source|izle|bolum|episode|media|api|resolve|manifest|playlist|hls)""").containsMatchIn(p) ||
                Regex("""(?i)(player|video|embed|stream|watch|source|api|playlist|hls)""").containsMatchIn(context)
        }
        return Regex("""(?i)(player|embed|video|stream|source|hls|m3u8|playlist|manifest|resolve|watch|api|media|file)""")
            .containsMatchIn(path(url)) ||
            Regex("""(?i)(player|video|embed|stream|source|playlist|manifest)""").containsMatchIn(context)
    }

    private fun isMedia(url: String): Boolean =
        mediaExtensionRegex.containsMatchIn(url) ||
            url.contains("/hls/", true) || url.contains("master.m3u8", true)

    private fun scanMedia(raw: String, base: String): List<String> {
        val text = decodeEmbedded(raw)
        val found = LinkedHashSet<String>()

        // Match complete URL tokens. Do not stop at ".mp4" if that substring belongs
        // to a filename query parameter and signed parameters continue after it.
        mediaRegex.findAll(text).forEach { match ->
            val rawUrl = match.value.trimEnd(')', ']', '}', ';', ',')
            val absoluteUrl = abs(rawUrl, base) ?: return@forEach
            if (isMedia(absoluteUrl)) found.add(absoluteUrl)
        }

        // Preserve protocol-relative links with their full query string too.
        Regex("""(?i)//[^\s"'<>\\]+""").findAll(text).forEach { match ->
            val rawUrl = match.value.trimEnd(')', ']', '}', ';', ',')
            val absoluteUrl = abs(rawUrl, base) ?: return@forEach
            if (isMedia(absoluteUrl)) found.add(absoluteUrl)
        }

        if (text.contains("%3A%2F%2F", true) || text.contains("%2F", true)) {
            val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault("")
            if (decoded.isNotBlank()) mediaRegex.findAll(decoded).forEach { match ->
                val rawUrl = match.value.trimEnd(')', ']', '}', ';', ',')
                val absoluteUrl = abs(rawUrl, base) ?: return@forEach
                if (isMedia(absoluteUrl)) found.add(absoluteUrl)
            }
        }
        return found.toList()
    }

    private fun decodeEmbedded(raw: String): String {
        var text = raw.replace("\\/", "/")
            .replace("\\u003A", ":").replace("\\u003a", ":")
            .replace("\\x3A", ":").replace("\\x3a", ":")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\x2F", "/").replace("\\x2f", "/")
            .replace("\\u0026", "&").replace("\\x26", "&")
            .replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\x3d", "=").replace("\\x3D", "=")
            .replace("&#x3a;", ":").replace("&#58;", ":")
            .replace("&#x2f;", "/").replace("&#47;", "/")
            .replace("&amp;", "&")
        val regex = Regex("""(?i)atob\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""")
        val decoded = regex.findAll(text).mapNotNull { match ->
            runCatching { String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }.joinToString("\n")
        if (decoded.isNotBlank()) text += "\n" + decoded
        if (text.contains("%3A%2F%2F", true)) {
            val decodedUrl = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault("")
            if (decodedUrl.isNotBlank()) text += "\n" + decodedUrl
        }
        return text
    }

    private fun collectSubtitles(document: Document, base: String, callback: (SubtitleFile) -> Unit) {
        val sent = LinkedHashSet<String>()
        document.select("track[src],track[data-src],a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("href") } }
            val url = abs(raw, base) ?: return@forEach
            if (!Regex("""(?i)\.(?:vtt|srt|ass|ssa)(?:$|[?#])""").containsMatchIn(url) || !sent.add(url)) return@forEach
            val lang = firstNonBlank(
                element.attr("srclang"), element.attr("label"),
                Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""").find(url)?.groupValues?.getOrNull(1)
            ) ?: "Unknown"
            callback(SubtitleFile(lang = lang, url = url))
        }
        subtitleRegex.findAll(decodeEmbedded(document.html())).forEach { match ->
            val url = abs(match.value.trimEnd(')', ']', '}', ';', ','), base) ?: return@forEach
            if (!sent.add(url)) return@forEach
            val lang = Regex("""(?i)(?:^|[/_.-])(tr|tur|turkish|en|eng|english|ar|es|fr|de)(?:[/_.?-]|$)""").find(url)?.groupValues?.getOrNull(1) ?: "Unknown"
            callback(SubtitleFile(lang = lang, url = url))
        }
    }

    private suspend fun resolveYandexDownloadUrl(url: String): String? {
        // /preview/ produces a preview resource, not the original video stream.
        if (path(url).contains("/preview/")) {
            Log.d(tag, "Yandex /preview adresi oynatılabilir video değil; kaynak atlanıyor")
            return null
        }

        if (!path(url).contains("/disk/")) return null

        val diskHomeHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        // Initialize Yandex's normal browser session/cookies before resolving its signed URL.
        runCatching {
            app.get(
                "https://disk.yandex.ru/",
                headers = diskHomeHeaders,
                allowRedirects = true,
                timeout = 6000
            )
        }

        val navigationHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://disk.yandex.ru/",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-site",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        )

        // Yandex's downloader endpoint requires a disposition query parameter.
        // The website-provided URL omits it, causing HTTP 422: No parameter: disposition.
        fun withQueryParameter(sourceUrl: String, parameter: String, value: String): String {
            if (Regex("""(?i)[?&]$parameter=""").containsMatchIn(sourceUrl)) return sourceUrl
            return sourceUrl + (if (sourceUrl.contains("?")) "&" else "?") + parameter + "=" + value
        }
        // Yandex returns HTTP 422 one required query parameter at a time on incomplete URLs.
        // Preserve existing signed parameters and add the two required download hints only when absent.
        val downloadUrl = withQueryParameter(
            withQueryParameter(url, "disposition", "attachment"),
            "content_type",
            "video%2Fmp4"
        )
        Log.d(tag, "Yandex indirme bağlantısına disposition ve content_type parametreleri uygulandı")

        val response = runCatching {
            app.get(
                downloadUrl,
                headers = navigationHeaders,
                allowRedirects = false,
                timeout = 8000
            )
        }.onFailure {
            Log.w(tag, "Yandex bağlantı yönlendirmesi isteği başarısız", it)
        }.getOrNull() ?: return null

        val location = response.headers["Location"] ?: response.headers["location"]
        if (response.code in 300..399 && !location.isNullOrBlank()) {
            val destination = abs(location, downloadUrl) ?: return null
            val destinationHost = host(destination)
            if (destinationHost.endsWith(".storage.yandex.net") ||
                destinationHost.endsWith(".storage.yandex.ru")
            ) {
                Log.d(tag, "Yandex bağlantısı gerçek depolama sunucusuna yönlendirildi")
                return destination
            }

            Log.w(tag, "Yandex beklenmeyen yönlendirme hedefi döndürdü: " + destinationHost)
            return null
        }

        val errorBody = response.text.replace(Regex("""\s+"""), " ").take(220)
        Log.w(tag, "Yandex /disk yönlendirmesi alınamadı: HTTP " + response.code + "; yanıt=" + errorBody)
        return null
    }

    private suspend fun emitMedia(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaHost = host(url)
        val isYandexDownloader = mediaHost == "downloader.disk.yandex.ru" ||
            mediaHost == "downloader.disk.yandex.net"
        val isTwitterVideoCdn = mediaHost == "video.twimg.com" ||
            mediaHost == "video-s.twimg.com"

        // Do not emit Yandex preview URLs as video links. Resolve /disk/ URLs through
        // Yandex's browser-style redirect first; the final storage URL is the actual media.
        val mediaUrl = if (isYandexDownloader) {
            resolveYandexDownloadUrl(url) ?: return false
        } else {
            url
        }

        // X's video CDN returns 403 when the Referer is the embedding third-party site.
        // Use the platform's own origin for media requests; this also lets ExoPlayer
        // request the HLS manifest and its nested fMP4 segments with matching headers.
        val mediaReferer = when {
            isYandexDownloader -> "https://disk.yandex.ru/"
            isTwitterVideoCdn -> "https://x.com/"
            else -> referer
        }
        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mediaReferer,
            "Accept" to "*/*"
        )
        if (isTwitterVideoCdn) {
            mediaHeaders["Origin"] = "https://x.com"
        }
        if (isYandexDownloader) {
            Log.d(tag, "Yandex depolama bağlantısı yönlendirme sonrası oynatmaya veriliyor")
        }
        if (isTwitterVideoCdn) {
            Log.d(tag, "X video CDN için x.com Referer/Origin başlıkları uygulandı")
        }
        when {
            mediaUrl.contains(".m3u8", true) || mediaUrl.contains("/hls/", true) -> {
                // master.m3u8 birden fazla varyant ve ayrı bir AUDIO grubu içerebilir.
                // M3u8Helper kalite varyantlarını ayrı linklere böldüğünde bazı varyantlar
                // harici ses playlist'ini kaybedip sessiz oynayabilir. Ana HLS manifestini
                // olduğu gibi vermek, ExoPlayer'ın video ve ses parçalarını beraber seçmesini sağlar.
                val keepMasterPlaylist = mediaUrl.contains("master.m3u8", ignoreCase = true)
                if (keepMasterPlaylist) {
                    callback(newExtractorLink(name, "TRDiziIzle HLS", mediaUrl, ExtractorLinkType.M3U8) {
                        this.referer = mediaReferer
                        this.headers = mediaHeaders
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }

                val generated = runCatching {
                    M3u8Helper.generateM3u8(name, mediaUrl, mediaReferer, null, mediaHeaders)
                }.getOrDefault(emptyList())
                if (generated.isNotEmpty()) generated.forEach(callback)
                else callback(newExtractorLink(name, "TRDiziIzle HLS", mediaUrl, ExtractorLinkType.M3U8) {
                    this.referer = mediaReferer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            mediaUrl.contains(".mpd", true) -> {
                callback(newExtractorLink(name, "TRDiziIzle DASH", mediaUrl, ExtractorLinkType.DASH) {
                    this.referer = mediaReferer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            isYandexDownloader -> {
                // The storage redirect URL typically has no .mp4 suffix; the signed
                // redirect itself is still the video resource and preserves Content-Type.
                callback(newExtractorLink(name, "TRDiziIzle Video", mediaUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mediaReferer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            Regex("""(?i)\.(?:mp4|m4v|webm)(?:$|[?#])""").containsMatchIn(mediaUrl) -> {
                callback(newExtractorLink(name, "TRDiziIzle Video", mediaUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mediaReferer
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        }
        return false
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    }
}
