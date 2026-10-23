package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class PatronSaha : MainAPI() {
    override var mainUrl = "https://patronsaha1.cfd"
    override var name = "PatronSaha"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/" to "Canlı Kanallar")

    // Kanal listesini döndüren API (logolarla birlikte)
    private val channelsApi = "https://patronsports2.cfd/channels.php"

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://patronsaha1.cfd",
        "Referer" to "https://patronsaha1.cfd/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
    )

    private fun liveLogoFor(title: String): String {
        val encoded = runCatching { URLEncoder.encode(title, "UTF-8") }.getOrDefault(title)
        return "https://ui-avatars.com/api/?name=$encoded&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
    }

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'')
            ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("mailto:", true)) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    // ---------------------------------------------------------------------
    //  KANAL LİSTESİ → channels.php API
    // ---------------------------------------------------------------------
    private suspend fun fetchChannelsFromApi(): List<SearchResponse> {
        val json = runCatching {
            app.get(
                channelsApi,
                headers = pageHeaders + ("Referer" to "$mainUrl/"),
                allowRedirects = true
            ).text
        }.getOrNull() ?: run {
            Log.w("Patron", "channels.php isteği başarısız")
            return emptyList()
        }

        Log.d("Patron", "channels.php yanıt uzunluğu: ${json.length}")
        Log.d("Patron", "channels.php ilk 200: ${json.take(200).replace("\n", " ")}")

        // JSON'dan Mac (kanal adı), Logo (logo yolu), URL (kanal linki) üçlüsünü çıkar
        val macRegex = Regex(""""Mac"\s*:\s*"([^"]+)"""")
        val logoRegex = Regex(""""Logo"\s*:\s*"([^"]+)"""")
        val urlRegex = Regex(""""URL"\s*:\s*"([^"]+)"""")

        val macs = macRegex.findAll(json).map { it.groupValues[1] }.toList()
        val logos = logoRegex.findAll(json).map { it.groupValues[1] }.toList()
        val urls = urlRegex.findAll(json).map { it.groupValues[1] }.toList()

        Log.d("Patron", "API: ${macs.size} kanal, ${logos.size} logo, ${urls.size} url")

        if (macs.isEmpty()) return emptyList()

        val channels = mutableListOf<SearchResponse>()
        for (i in macs.indices) {
            val name = macs[i].trim()
            if (name.isBlank()) continue

            val rawUrl = urls.getOrNull(i)?.replace("\\/", "/") ?: "/ch.html?id=${name.lowercase()}"
            val channelUrl = fixUrl(rawUrl, mainUrl) ?: continue

            val logoPath = logos.getOrNull(i)?.replace("\\/", "/")
            val logo = if (!logoPath.isNullOrBlank()) {
                fixUrl(logoPath, "https://patronsports2.cfd") ?: liveLogoFor(name)
            } else {
                liveLogoFor(name)
            }

            channels.add(
                newMovieSearchResponse(name, channelUrl, TvType.Live) {
                    posterUrl = logo
                }
            )
        }

        Log.d("Patron", "API'den ${channels.size} kanal ayrıştırıldı")
        return channels
    }

    // ---------------------------------------------------------------------
    //  ANA SAYFA (API tabanlı)
    // ---------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Önce API'den dene
        var items = fetchChannelsFromApi()

        // API başarısızsa statik HTML'e düş
        if (items.isEmpty()) {
            val document = runCatching {
                app.get(mainUrl, headers = pageHeaders, allowRedirects = true).document
            }.getOrNull()

            if (document != null) {
                items = document.select("div.channel-item").mapNotNull { el ->
                    if (el.hasClass("channel-ad-item")) return@mapNotNull null
                    val dataSrc = el.attr("data-src").trim()
                    if (dataSrc.isBlank()) return@mapNotNull null
                    val url = fixUrl(dataSrc) ?: return@mapNotNull null
                    if (!sameSite(url)) return@mapNotNull null
                    val name = el.selectFirst("span.channel-name-text")?.text()?.trim()
                        ?: el.selectFirst("img")?.attr("alt")?.trim()
                        ?: return@mapNotNull null
                    if (name.length < 2) return@mapNotNull null
                    val logo = el.selectFirst("img.channel-logo-right")?.attr("src")
                        ?.let { fixUrl(it, url) } ?: liveLogoFor(name)
                    newMovieSearchResponse(name, url, TvType.Live) { posterUrl = logo }
                }
            }
        }

        Log.d("Patron", "Toplam kanal: ${items.size}")
        return newHomePageResponse(request.name, items.distinctBy { it.url }.take(200))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getMainPage(1, mainPage.first()).items
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    // ---------------------------------------------------------------------
    //  KANAL DETAYI
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
        ).mapNotNull { it?.trim() }.firstOrNull { it.length > 1 }
            ?.substringBefore(" | ")?.trim()
            ?: return null

        val liveDataUrl = document.selectFirst("iframe[src]")?.let {
            fixUrl(it.attr("src"), pageUrl)
        } ?: pageUrl

        return newMovieLoadResponse(title, pageUrl, TvType.Live, liveDataUrl) {
            posterUrl = liveLogoFor(title)
            plot = "Canlı yayın — PatronSaha"
        }
    }

    // ---------------------------------------------------------------------
    //  HLS OYNATILABİLİRLİK TESTİ
    // ---------------------------------------------------------------------
    private suspend fun isPlayableHls(url: String, referer: String, origin: String): Boolean {
        val response = runCatching {
            app.get(
                url,
                headers = streamHeaders + mapOf("Referer" to referer, "Origin" to origin),
                allowRedirects = true
            )
        }.getOrNull() ?: return false
        if (response.code !in 200..299) {
            Log.w("Patron", "HLS HTTP ${response.code}: $url")
            return false
        }
        val body = response.text.trim()
        if (!body.startsWith("#EXTM3U")) {
            Log.w("Patron", "HLS EXTM3U değil (kod=${response.code}): ${body.take(80)}")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------
    //  CH.HTML → baseurl → mono.m3u8
    // ---------------------------------------------------------------------
    private suspend fun extractStreamFromChannelPage(body: String, pageUrl: String): String? {
        val id = runCatching {
            URI(pageUrl).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
        }.getOrNull() ?: Regex("""[?&]id=([^&"']+)""").find(pageUrl)?.groupValues?.getOrNull(1)
            ?: return null

        Log.d("Patron", "extract: id=$id bodyLen=${body.length}")
        Log.d("Patron", "extract body[0..800]: ${body.take(800).replace("\n", " ")}")

        val domainUrl = Regex("""domainUrl\s*:\s*['"]([^'"]+)['"]""")
            .find(body)?.groupValues?.getOrNull(1)

        val inlineBase = Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
            .find(body)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

        val baseUrl: String? = run {
            if (domainUrl != null) {
                Log.d("Patron", "domainUrl bulundu: $domainUrl")
                val r = runCatching {
                    app.get(domainUrl, headers = pageHeaders + ("Referer" to pageUrl), allowRedirects = true).text
                }.getOrNull()
                val b = r?.let {
                    Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
                        .find(it)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                }
                if (b != null) { Log.d("Patron", "baseurl (domainUrl üzerinden): $b"); return@run b }
            }
            if (inlineBase != null) { Log.d("Patron", "baseurl (inline): $inlineBase"); return@run inlineBase }

            val endpoints = linkedSetOf<String>()
            Regex("""(?:fetch|\$\.get|\$\.ajax|axios\.get)\s*\(\s*['"]([^'"]+)['"]""")
                .findAll(body).forEach { m -> fixUrl(m.groupValues[1], pageUrl)?.let { endpoints.add(it) } }
            Regex("""\.open\s*\(\s*['"]GET['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(body).forEach { m -> fixUrl(m.groupValues[1], pageUrl)?.let { endpoints.add(it) } }
            listOf(
                "/domain.php", "/api/domain", "/api/domain.php",
                "/getdomain", "/getdomain.php", "/domain",
                "/api/baseurl", "/baseurl", "/api/baseurl.php",
                "/get-baseurl", "/config.php", "/api/config",
                "/domain.json", "/api/domain.json", "/ch.json"
            ).forEach { p -> fixUrl(p, mainUrl)?.let { endpoints.add(it) } }

            Log.d("Patron", "Denenen endpoint: ${endpoints.size}")
            for (ep in endpoints) {
                val r = runCatching {
                    app.get(ep, headers = pageHeaders + ("Referer" to pageUrl), allowRedirects = true).text
                }.getOrNull() ?: continue
                val b = Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
                    .find(r)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                if (b != null) { Log.d("Patron", "baseurl endpoint=$ep → $b"); return@run b }
            }
            null
        } ?: run { Log.w("Patron", "baseurl BULUNAMADI"); return null }

        return "${baseUrl.trimEnd('/')}/$id/mono.m3u8"
    }

    // ---------------------------------------------------------------------
    //  LİNK ÇIKARMA
    // ---------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun scanPage(pageRaw: String, referer: String, depth: Int) {
            if (depth > 2 || visited.size >= 8) return
            val pageUrl = fixUrl(pageRaw) ?: return
            if (!visited.add(pageUrl)) return

            val response = runCatching {
                app.get(pageUrl, headers = pageHeaders + ("Referer" to referer), allowRedirects = true)
            }.getOrNull() ?: return
            val body = response.text
            val document = response.document
            Log.d("Patron", "Tarama: ${pageUrl.substringBefore('?')} (d=$depth)")

            if (Regex("(?i)(channel|player|watch|canli|live|ch\\.html)").containsMatchIn(pageUrl) ||
                body.contains("domainUrl", ignoreCase = true) ||
                body.contains("baseurl", ignoreCase = true)
            ) {
                val streamUrl = extractStreamFromChannelPage(body, pageUrl)
                if (streamUrl != null) {
                    val ok = isPlayableHls(streamUrl, "$mainUrl/", mainUrl)
                    Log.d("Patron", "HLS testi: $ok → $streamUrl")
                    if (ok && emitted.add(streamUrl)) {
                        callback(
                            newExtractorLink(source = name, name = name, url = streamUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = "https://patronsaha1.cfd/"
                                this.headers = mapOf(
                                    "Origin" to "https://patronsaha1.cfd",
                                    "Referer" to "https://patronsaha1.cfd/",
                                    "User-Agent" to browserUserAgent
                                )
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    } else if (!ok) Log.w("Patron", "HLS oynatılamadı: $streamUrl")
                }
            }

            document.select("track[src], track[data-src]").forEach { track ->
                val s = fixUrl(track.attr("src").ifBlank { track.attr("data-src") }, pageUrl) ?: return@forEach
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(s)) {
                    val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(lang, s))
                }
            }

            val candidates = linkedSetOf<String>()
            document.select(
                "iframe, video, source, [data-embed], [data-video], [data-player], [data-stream], a[href*='.m3u8'], a[href*='.mp4']"
            ).forEach { el ->
                val raw = sequenceOf(
                    el.attr("src"), el.attr("data-src"), el.attr("data-url"),
                    el.attr("data-embed"), el.attr("data-video"), el.attr("data-player"),
                    el.attr("data-stream"), el.attr("href")
                ).firstOrNull { it.isNotBlank() } ?: return@forEach
                fixUrl(raw, pageUrl)?.takeIf { it != pageUrl }?.let { candidates.add(it) }
            }

            val html = body.replace("""\/""", "/").replace("&quot;", "\"")
            Regex("""(?i)(?:https?:)?//[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
                .findAll(html).forEach { m -> fixUrl(m.value, pageUrl)?.let { candidates.add(it) } }

            for (cand in candidates.take(30)) {
                val path = runCatching { URI(cand).path.orEmpty().lowercase() }.getOrDefault("")
                val isDirect = path.endsWith(".m3u8") || path.endsWith(".mp4")
                if (isDirect) {
                    if (path.endsWith(".m3u8")) {
                        val ok = isPlayableHls(cand, "$mainUrl/", mainUrl)
                        if (!ok) continue
                    }
                    if (emitted.add(cand)) {
                        callback(
                            newExtractorLink(source = name, name = name, url = cand,
                                type = if (path.endsWith(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                                this.referer = "https://patronsaha1.cfd/"
                                this.headers = mapOf(
                                    "Origin" to "https://patronsaha1.cfd",
                                    "Referer" to "https://patronsaha1.cfd/",
                                    "User-Agent" to browserUserAgent
                                )
                                quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                } else {
                    runCatching {
                        loadExtractor(cand, pageUrl, subtitleCallback) { link ->
                            if (emitted.add(link.url)) { callback(link); found = true }
                        }
                    }
                    if (depth < 2) scanPage(cand, pageUrl, depth + 1)
                }
            }
        }

        scanPage(data, "$mainUrl/", 0)
        if (!found) Log.w("Patron", "Bağlantı bulunamadı. Denenen sayfa=${visited.size}")
        return found
    }
}
