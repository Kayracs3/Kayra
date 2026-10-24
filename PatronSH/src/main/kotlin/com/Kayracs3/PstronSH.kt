package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    // -----------------------------------------------------------------
    //  YARDIMCILAR
    // -----------------------------------------------------------------
    private fun liveLogoFor(title: String): String {
        val encoded = runCatching { URLEncoder.encode(title, "UTF-8") }.getOrDefault(title)
        return "https://ui-avatars.com/api/?name=$encoded&size=400&background=1e3a8a&color=ffffff&bold=true&length=2"
    }

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'')
            ?: return null
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

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    /**
     * Reklam / fragman / promo URL'lerini tespit eder.
     * Ana akış (mono.m3u8) dışındaki m3u8'lerin çoğu bu kalıplardan birini içerir.
     */
    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Regex(
            "(sayac|ads?|banner|reklam|analytics|googletagmanager|beacon|chat\\.js|/cdn-cgi/|" +
            "preroll|pre-roll|mid-roll|post-roll|vast|vpaid|ima|doubleclick|" +
            "googlesyndication|adserver|advertisement|commercial|promo|sponsor|" +
            "popunder|popads|trafficjunky|exoclick|juicyads|/ad/|/ads/)"
        ).containsMatchIn(lower)
    }

    // -----------------------------------------------------------------
    //  KANAL LİSTESİ
    // -----------------------------------------------------------------
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

        Log.d("Patron", "channels.php uzunluk: ${json.length}")

        val macs = Regex(""""Mac"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        val logos = Regex(""""Logo"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        val urls = Regex(""""URL"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()

        Log.d("Patron", "API: ${macs.size} kanal")

        if (macs.isEmpty()) return emptyList()

        return macs.mapIndexedNotNull { i, name ->
            val cleanName = name.trim()
            if (cleanName.isBlank()) return@mapIndexedNotNull null

            val rawUrl = urls.getOrNull(i)?.replace("\\/", "/")
                ?: "/ch.html?id=${cleanName.lowercase()}"
            val channelUrl = fixUrl(rawUrl, mainUrl) ?: return@mapIndexedNotNull null

            val logoPath = logos.getOrNull(i)?.replace("\\/", "/")
            val logo = if (!logoPath.isNullOrBlank()) {
                fixUrl(logoPath, "https://patronsports2.cfd") ?: liveLogoFor(cleanName)
            } else {
                liveLogoFor(cleanName)
            }

            newMovieSearchResponse(cleanName, channelUrl, TvType.Live) {
                posterUrl = logo
            }
        }
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        var items = fetchChannelsFromApi()

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
                    if (!sameSite(url) || isAdUrl(url)) return@mapNotNull null

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

        return items.distinctBy { it.url }.take(200)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetchChannels()
        Log.d("Patron", "Toplam kanal: ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return fetchChannels().filter { it.name.contains(query, ignoreCase = true) }
    }

    // -----------------------------------------------------------------
    //  KANAL DETAYI
    // -----------------------------------------------------------------
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
            ?.substringBefore(" | ")?.trim() ?: return null

        // ch.html zaten oynatıcı sayfasıdır. Reklam iframe'lerini atla.
        var liveDataUrl = pageUrl
        for (iframe in document.select("iframe[src]")) {
            val iframeUrl = fixUrl(iframe.attr("src"), pageUrl) ?: continue
            if (isAdUrl(iframeUrl)) {
                Log.d("Patron", "Reklam iframe atlandı: $iframeUrl")
                continue
            }
            liveDataUrl = iframeUrl
            Log.d("Patron", "Oynatıcı iframe seçildi: $iframeUrl")
            break
        }

        Log.d("Patron", "load() → data=$liveDataUrl")
        return newMovieLoadResponse(title, pageUrl, TvType.Live, liveDataUrl) {
            posterUrl = liveLogoFor(title)
            plot = "Canlı yayın — PatronSaha"
        }
    }

    // -----------------------------------------------------------------
    //  HLS TESTİ
    // -----------------------------------------------------------------
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

    // -----------------------------------------------------------------
    //  CH.HTML → baseurl → mono.m3u8
    // -----------------------------------------------------------------
    private suspend fun extractStreamFromChannelPage(body: String, pageUrl: String): String? {
        val id = runCatching {
            URI(pageUrl).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
        }.getOrNull() ?: Regex("""[?&]id=([^&"']+)""").find(pageUrl)?.groupValues?.getOrNull(1)
            ?: return null

        Log.d("Patron", "extract: id=$id bodyLen=${body.length}")
        Log.d("Patron", "extract body[0..1500]: ${body.take(1500).replace("\n", " ")}")

        val domainUrl = Regex("""domainUrl\s*:\s*['"]([^'"]+)['"]""")
            .find(body)?.groupValues?.getOrNull(1)

        val inlineBase = Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
            .find(body)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

        val baseUrl: String = run {
            if (domainUrl != null) {
                Log.d("Patron", "domainUrl bulundu: $domainUrl")
                val r = runCatching {
                    app.get(domainUrl, headers = pageHeaders + ("Referer" to pageUrl), allowRedirects = true).text
                }.getOrNull()
                val b = r?.let {
                    Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
                        .find(it)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                }
                if (b != null) {
                    Log.d("Patron", "baseurl (domainUrl): $b")
                    return@run b
                }
            }
            if (inlineBase != null) {
                Log.d("Patron", "baseurl (inline): $inlineBase")
                return@run inlineBase
            }

            val endpoints = linkedSetOf<String>()
            Regex("""(?:fetch|\$\.get|\$\.ajax|axios\.get)\s*\(\s*['"]([^'"]+)['"]""")
                .findAll(body).forEach { m ->
                    fixUrl(m.groupValues[1], pageUrl)?.let { endpoints.add(it) }
                }
            Regex("""\.open\s*\(\s*['"]GET['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(body).forEach { m ->
                    fixUrl(m.groupValues[1], pageUrl)?.let { endpoints.add(it) }
                }
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
                if (b != null) {
                    Log.d("Patron", "baseurl endpoint=$ep → $b")
                    return@run b
                }
            }
            null
        } ?: run {
            Log.w("Patron", "baseurl BULUNAMADI")
            return null
        }

        return "${baseUrl.trimEnd('/')}/$id/mono.m3u8"
    }

    // -----------------------------------------------------------------
    //  LİNK ÇIKARMA  — REKLAMSIZ, TEK AKIŞ
    // -----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // -------------------------------------------------------------
        // 1) Önce doğrudan ch.html sayfasını çek
        // -------------------------------------------------------------
        val response = runCatching {
            app.get(
                data,
                headers = pageHeaders + ("Referer" to "$mainUrl/"),
                allowRedirects = true
            )
        }.getOrNull() ?: return false

        val body = response.text
        val document = response.document

        Log.d("Patron", "loadLinks: data=$data")

        // -------------------------------------------------------------
        // 2) ch.html → CONFIG.domainUrl → baseurl → mono.m3u8
        // -------------------------------------------------------------
        if (Regex("(?i)(channel|player|watch|canli|live|ch\\.html)").containsMatchIn(data) ||
            body.contains("domainUrl", ignoreCase = true) ||
            body.contains("baseurl", ignoreCase = true)
        ) {
            val streamUrl = extractStreamFromChannelPage(body, data)
            if (streamUrl != null) {
                val ok = isPlayableHls(streamUrl, "$mainUrl/", mainUrl)
                Log.d("Patron", "Ana akış testi: $ok → $streamUrl")
                if (ok) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
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
                    // Ana akış başarılı → başka aday arama, reklam gösterme!
                    return true
                }
            }
        }

        // -------------------------------------------------------------
        // 3) Ana akış bulunamadı → yedek olarak iframe/video adayları
        //    (Reklam filtresinden geçen, sadece ilk çalışan emit edilir)
        // -------------------------------------------------------------

        // Altyazılar
        document.select("track[src], track[data-src]").forEach { track ->
            val s = fixUrl(track.attr("src").ifBlank { track.attr("data-src") }, data)
                ?: return@forEach
            if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(s)) {
                val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                subtitleCallback(SubtitleFile(lang, s))
            }
        }

        val candidates = linkedSetOf<String>()

        // iframe / video / source / data-* etiketleri
        document.select(
            "iframe, video, source, [data-embed], [data-video], [data-player], [data-stream], " +
            "a[href*='.m3u8'], a[href*='.mp4']"
        ).forEach { el ->
            val raw = sequenceOf(
                el.attr("src"), el.attr("data-src"), el.attr("data-url"),
                el.attr("data-embed"), el.attr("data-video"), el.attr("data-player"),
                el.attr("data-stream"), el.attr("href")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val cand = fixUrl(raw, data) ?: return@forEach
            if (cand == data || isAdUrl(cand)) return@forEach
            candidates.add(cand)
        }

        // Ham HTML içindeki m3u8/mp4 URL'leri (reklam filtresiyle)
        val html = body.replace("""\/""", "/")
            .replace("&quot;", "\"")
        Regex("""(?i)(?:https?:)?//[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
            .findAll(html).forEach { m ->
                val cand = fixUrl(m.value, data)
                if (cand != null && !isAdUrl(cand)) candidates.add(cand)
            }

        // Sadece ilk çalışanı emit et
        for (cand in candidates.take(10)) {
            val path = runCatching { URI(cand).path.orEmpty().lowercase() }.getOrDefault("")

            if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
                if (path.endsWith(".m3u8") && !isPlayableHls(cand, "$mainUrl/", mainUrl)) {
                    Log.w("Patron", "Yedek HLS oynatılamadı: $cand")
                    continue
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cand,
                        type = if (path.endsWith(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
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
                break // Tek akış yeterli
            } else {
                var extracted = false
                runCatching {
                    loadExtractor(cand, data, subtitleCallback) { link ->
                        if (!extracted) {
                            callback(link)
                            extracted = true
                            found = true
                        }
                    }
                }
                if (extracted) break // İlk çalışan yeterli
            }
        }

        if (!found) {
            Log.w("Patron", "Bağlantı bulunamadı. Denenen aday=${candidates.size}")
        }
        return found
    }
}
