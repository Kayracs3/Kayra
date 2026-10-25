package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Flix : MainAPI() {
    override var mainUrl = "https://fixbettv84.com"
    override var name = "Flix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/" to "Ana Sayfa")

    private val streamHost = "https://zirvedesin247.cfd"
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6",
        "Referer" to "$streamHost/"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to streamHost,
        "Referer" to "$streamHost/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
    )

    private fun liveLogoFor(title: String): String {
        val encoded = runCatching { URLEncoder.encode(title, "UTF-8") }.getOrDefault(title)
        return "https://ui-avatars.com/api/?name=$encoded&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
    }

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'') ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("mailto:", true)
        ) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    // ---------------------------------------------------------------------
    // ANA SAYFA / ARAMA (basitleştirilmiş)
    // ---------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get(mainUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseResults(document)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val document = runCatching {
            app.get("$mainUrl/arama?q=$query", headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return emptyList()
        return parseResults(document, query)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseResults(document: org.jsoup.nodes.Document, query: String? = null): List<SearchResponse> {
        return document.select("div.movie-item, div.series-item, div.card").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { return@mapNotNull null }
            if (query != null && !title.contains(query, ignoreCase = true)) return@mapNotNull null
            val href = fixUrl(a.attr("href")) ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it, href) } ?: liveLogoFor(title)
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------------
    // DETAY SAYFASI
    // ---------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url) ?: return null
        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return null
        val title = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title()
        ).mapNotNull { it?.trim() }.firstOrNull { it.length > 1 }?.substringBefore(" | ")?.trim() ?: return null

        val isLive = document.selectFirst("iframe[src*=channel\\.html]") != null ||
                Regex("(?i)(channel\\.html|/live|/channel|/kanal|canlı|canli)").containsMatchIn(pageUrl)

        return if (isLive) {
            val liveDataUrl = if (pageUrl.contains("channel.html", true)) {
                pageUrl
            } else {
                document.selectFirst("iframe[src*=channel\\.html]")?.let { iframe ->
                    fixUrl(iframe.attr("src"), pageUrl) ?: pageUrl
                } ?: pageUrl
            }
            newMovieLoadResponse(title, pageUrl, TvType.Live, liveDataUrl) {
                posterUrl = liveLogoFor(title)
                plot = "Canlı yayın — Flix"
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = liveLogoFor(title)
                plot = "Film / Dizi — Flix"
            }
        }
    }

    // ---------------------------------------------------------------------
    // HLS OYNATILABİLİRLİK TESTİ
    // ---------------------------------------------------------------------
    private suspend fun isPlayableHls(url: String, referer: String, cookies: String? = null): Boolean {
        val headers = streamHeaders + mapOf("Referer" to referer) +
                if (!cookies.isNullOrBlank()) mapOf("Cookie" to cookies) else emptyMap()

        val response = runCatching { app.get(url, headers = headers, allowRedirects = true) }.getOrNull()
            ?: return false

        if (response.code !in 200..299) {
            Log.w("Flix", "HLS HTTP ${response.code}: $url")
            return false
        }
        val body = response.text.trim()
            .replace("""\/""", "/")
            .replace("""\:""", ":")
            .replace("""\u002F""", "/", true)
            .replace("""\u0026""", "&", true)

        if (!body.startsWith("#EXTM3U")) return false

        val hasVariantPlaylist = Regex("""(?im)^\s*#EXT-X-STREAM-INF\s*:""").containsMatchIn(body)
        val hasVideoMap = Regex("""(?i)#EXT-X-MAP:.*URI=["']?[^"']+\.(?:mp4|m4s)(?:[?#][^"']*)?""").containsMatchIn(body)
        val hasLiveSegments = Regex("""(?im)^\s*#EXTINF\s*:""").containsMatchIn(body)

        val uriLines = body.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        if (uriLines.isEmpty()) return hasVariantPlaylist || hasVideoMap || hasLiveSegments

        val imageOnly = uriLines.all { line ->
            Regex("""(?i)\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$""").containsMatchIn(line)
        }
        return !imageOnly || hasVariantPlaylist || hasVideoMap || hasLiveSegments
    }

    // ---------------------------------------------------------------------
    // CHANNEL.HTML → mono.m3u8
    // ---------------------------------------------------------------------
    private suspend fun extractStreamFromChannelPage(body: String, pageUrl: String): String? {
        val domainUrl = Regex("""domainUrl\s*:\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val id = runCatching {
            URI(pageUrl).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
        }.getOrNull() ?: Regex("""[?&]id=([^&"']+)""").find(pageUrl)?.groupValues?.getOrNull(1)
        ?: return null

        val baseUrl = runCatching {
            val response = app.get(domainUrl, headers = pageHeaders, allowRedirects = true).text
            Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""").find(response)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
        }.getOrNull() ?: return null

        return "${baseUrl.trimEnd('/')}/$id/mono.m3u8"
    }

    // ---------------------------------------------------------------------
    // LİNK ÇIKARMA (ASIL DÜZELTME)
    // ---------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visitedPages = linkedSetOf<String>()
        val emittedLinks = linkedSetOf<String>()
        var found = false

        suspend fun emit(streamUrl: String, cookies: String?) {
            // 1) URL'deki kaçış karakterlerini temizle
            val cleanUrl = streamUrl.replace("\\/", "/").trim()
            if (!emittedLinks.add(cleanUrl)) return

            // 2) Tüm başlıkları tek yerde topla
            val headers = buildMap {
                putAll(streamHeaders)
                if (!cookies.isNullOrBlank()) put("Cookie", cookies)
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$streamHost/"   // null DEĞİL
                    this.headers = headers
                    quality = Qualities.Unknown.value
                }
            )
            found = true
            Log.d("Flix", "Akış eklendi: ${cleanUrl.substringBefore('?')}")
        }

        suspend fun scanPage(pageRaw: String, referer: String, depth: Int) {
            if (depth > 2 || visitedPages.size >= 8) return
            val pageUrl = fixUrl(pageRaw) ?: return
            if (!visitedPages.add(pageUrl)) return

            val response = runCatching {
                app.get(pageUrl, headers = pageHeaders + ("Referer" to referer), allowRedirects = true)
            }.getOrNull() ?: return

            // 3) Set-Cookie başlığından çerezleri al
            val pageCookies = runCatching {
                response.headers.values("Set-Cookie")
                    .joinToString("; ") { it.substringBefore(";").trim() }
                    .takeIf { it.isNotBlank() }
            }.getOrNull()

            val body = response.text
            val document = response.document
            Log.d("Flix", "Tarama: ${pageUrl.substringBefore('?')} (d=$depth)")

            // channel.html sayfasından doğrudan m3u8 çıkar
            if (pageUrl.contains("channel.html", ignoreCase = true)) {
                val streamUrl = extractStreamFromChannelPage(body, pageUrl)
                if (streamUrl != null) {
                    val ok = isPlayableHls(streamUrl.replace("\\/", "/"), pageUrl, pageCookies)
                    Log.d("Flix", "HLS testi: $ok → $streamUrl")
                    if (ok) emit(streamUrl, pageCookies)
                }
            }

            // Altyazılar
            for (track in document.select("track[src], track[data-src], track[data-url]")) {
                val raw = sequenceOf(track.attr("src"), track.attr("data-src"), track.attr("data-url"))
                    .firstOrNull { it.isNotBlank() } ?: continue
                val subtitleUrl = fixUrl(raw, pageUrl) ?: continue
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                    val language = track.attr("srclang").ifBlank { track.attr("label") }
                        .ifBlank { track.attr("lang") }.ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(language, subtitleUrl))
                }
            }

            // Diğer adaylar
            val candidates = linkedSetOf<String>()
            val selector = listOf(
                "iframe", "video", "video source", "source",
                "[data-embed]", "[data-embed-url]", "[data-video]", "[data-player]",
                "[data-player-url]", "[data-stream]", "a[href*='.m3u8']", "a[href*='.mp4']"
            ).joinToString(", ")

            for (element in document.select(selector)) {
                val raw = sequenceOf(
                    element.attr("src"), element.attr("data-src"), element.attr("data-lazy-src"),
                    element.attr("data-url"), element.attr("data-embed"), element.attr("data-embed-url"),
                    element.attr("data-video"), element.attr("data-player"), element.attr("data-player-url"),
                    element.attr("data-stream"), element.attr("href")
                ).firstOrNull { it.isNotBlank() } ?: continue

                fixUrl(raw, pageUrl)?.takeIf { it != pageUrl }?.let { candidates.add(it) }
            }

            // HTML içindeki doğrudan m3u8/mp4 linkleri
            val html = body.replace("""\/""", "/").replace("&quot;", "\"")
            Regex("""(?i)(?:https?:)?//[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
                .findAll(html).forEach { m -> fixUrl(m.value, pageUrl)?.let { candidates.add(it) } }

            for (cand in candidates.take(30)) {
                val path = runCatching { URI(cand).path.orEmpty().lowercase() }.getOrDefault("")
                val isM3u8 = path.endsWith(".m3u8")
                val isMp4 = path.endsWith(".mp4")

                if (isM3u8 || isMp4) {
                    if (isM3u8 && !isPlayableHls(cand, "$streamHost/", pageCookies)) continue
                    emit(cand, pageCookies)
                } else {
                    if (depth < 2) scanPage(cand, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$streamHost/", 0)
        if (!found) Log.w("Flix", "Bağlantı bulunamadı. Denenen sayfa=${visitedPages.size}")
        return found
    }
}
