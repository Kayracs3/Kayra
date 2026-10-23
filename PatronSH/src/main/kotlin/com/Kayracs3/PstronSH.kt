package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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

    // ---------------------------------------------------------------------
    //  SABİTLER & HEADER'LAR
    // ---------------------------------------------------------------------
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    // HLS akışı için ZORUNLU başlıklar (cURL'den alındı)
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

    // ---------------------------------------------------------------------
    //  LOGO HARİTASI (TV-Logos + ui-avatars fallback)
    // ---------------------------------------------------------------------
    private val channelLogos: Map<String, String> = mapOf(
        "trt 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png",
        "trt1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png",
        "trt 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-2-tr.png",
        "trt 3" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-3-tr.png",
        "trt haber" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-haber-tr.png",
        "trt spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png",
        "trt spor yildiz" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png",
        "trt belgesel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-belgesel-tr.png",
        "trt muzik" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-muzik-tr.png",
        "trt cocuk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-cocuk-tr.png",
        "trt avaz" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-avaz-tr.png",
        "trt kurdi" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-kurdi-tr.png",
        "trt arabi" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-arabi-tr.png",
        "trt world" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-world-tr.png",
        "atv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/atv-tr.png",
        "a haber" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-haber-tr.png",
        "a para" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-para-tr.png",
        "a spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-spor-tr.png",
        "show tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/show-tv-tr.png",
        "showtv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/show-tv-tr.png",
        "showmax" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/showmax-tr.png",
        "star tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/star-tv-tr.png",
        "startv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/star-tv-tr.png",
        "now tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/now-tv-tr.png",
        "now" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/now-tv-tr.png",
        "fox" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/fox-tv-tr.png",
        "fox tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/fox-tv-tr.png",
        "tv8" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-tr.png",
        "tv8,5" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-5-tr.png",
        "kanal d" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kanal-d-tr.png",
        "kanald" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kanal-d-tr.png",
        "cnn turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cnn-turk-tr.png",
        "cnnturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cnn-turk-tr.png",
        "ntv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ntv-tr.png",
        "ntv spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ntv-spor-tr.png",
        "haberturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/haberturk-tr.png",
        "beyaz tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/beyaz-tv-tr.png",
        "beyaztv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/beyaz-tv-tr.png",
        "tv 100" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv-100-tr.png",
        "tv100" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv-100-tr.png",
        "teve2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/teve2-tr.png",
        "360" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/360-tr.png",
        "360 tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/360-tr.png",
        "ulke tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ulke-tv-tr.png",
        "tvnet" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tvnet-tr.png",
        "haber global" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/haber-global-tr.png",
        "haberglobal" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/haber-global-tr.png",
        "halk tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/halk-tv-tr.png",
        "halktv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/halk-tv-tr.png",
        "tele 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tele1-tr.png",
        "tele1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tele1-tr.png",
        "sozcu tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/sozcu-tv-tr.png",
        "sozcutv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/sozcu-tv-tr.png",
        "krt" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/krt-tr.png",
        "benguturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/benguturk-tr.png",
        "ulusal kanal" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ulusal-kanal-tr.png",
        "s sport" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png",
        "ssport" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png",
        "s sport 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png",
        "ssport2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png",
        "tivibu spor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-tr.png",
        "tivibuspor" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-tr.png",
        "tivibu spor 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tivibu-spor-2-tr.png",
        "bein sports 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-1-tr.png",
        "beinsports 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-1-tr.png",
        "beinsports1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-1-tr.png",
        "bein sports 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-2-tr.png",
        "beinsports 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-2-tr.png",
        "beinsports2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-2-tr.png",
        "bein sports 3" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-3-tr.png",
        "beinsports 3" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-3-tr.png",
        "bein sports 4" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-4-tr.png",
        "bein sports max 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-max-1-tr.png",
        "bein sports max 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bein-sports-max-2-tr.png",
        "eurosport 1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/eurosport-1-tr.png",
        "eurosport 2" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/eurosport-2-tr.png",
        "minika cocuk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-cocuk-tr.png",
        "minikacocuk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-cocuk-tr.png",
        "minika go" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-go-tr.png",
        "minikago" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/minika-go-tr.png",
        "disney channel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/disney-channel-tr.png",
        "cartoon network" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cartoon-network-tr.png",
        "nickelodeon" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nickelodeon-tr.png",
        "nat geo" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-tr.png",
        "national geographic" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-tr.png",
        "nat geo wild" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/nat-geo-wild-tr.png",
        "discovery channel" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/discovery-channel-tr.png",
        "discovery" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/discovery-channel-tr.png",
        "history" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/history-tr.png",
        "animal planet" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/animal-planet-tr.png",
        "tlc" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tlc-tr.png",
        "dmax" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/dmax-tr.png",
        "bloomberg ht" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/bloomberg-ht-tr.png",
        "cnbc-e" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/cnbc-e-tr.png",
        "ekoturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/ekoturk-tr.png",
        "powerturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/powerturk-tr.png",
        "power turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/powerturk-tr.png",
        "number one tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/number-one-tv-tr.png",
        "nr1" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/number-one-tv-tr.png",
        "dream turk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/dream-turk-tr.png",
        "dreamturk" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/dream-turk-tr.png",
        "kral tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kral-tv-tr.png",
        "kral pop" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/kral-pop-tv-tr.png",
        "tv 24" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png",
        "24 tv" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png",
        "24" to "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/24-tv-tr.png"
    )

    // ---------------------------------------------------------------------
    //  YARDIMCI FONKSİYONLAR
    // ---------------------------------------------------------------------
    private fun normalizeChannelName(raw: String): String = raw.lowercase()
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace('İ', 'i').replace('Ğ', 'g').replace('Ü', 'u')
        .replace('Ş', 's').replace('Ö', 'o').replace('Ç', 'c')
        .replace(Regex("[^a-z0-9]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun liveLogoFor(title: String): String {
        val norm = normalizeChannelName(title)
        if (norm.isNotEmpty()) {
            channelLogos[norm]?.let { return it }
            for ((key, url) in channelLogos) {
                if (norm == key || norm.startsWith("$key ") || norm.endsWith(" $key") ||
                    norm.contains(" $key ") || norm.contains(key)
                ) return url
            }
        }
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
    //  SAYFA AYRIŞTIRMA (Kanal Kartı → SearchResponse)
    // ---------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName().equals("a", true)) this else select("a[href]").firstOrNull()
            ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!sameSite(url) || url.trimEnd('/') == mainUrl.trimEnd('/')) return null
        if (Regex("(?i)/(privacy|terms|contact|about|login|register|category|tag)(/|$)").containsMatchIn(url)) return null

        val title = sequenceOf(
            selectFirst("h1, h2, h3, .title, .channel-title, .kanal-adi")?.text(),
            link.attr("title"),
            selectFirst("img")?.attr("alt"),
            link.text()
        ).mapNotNull { it?.trim() }.firstOrNull { it.length >= 2 }
            ?.replace(Regex("\\s+"), " ")?.trim() ?: return null

        return newMovieSearchResponse(title, url, TvType.Live) {
            posterUrl = liveLogoFor(title)
        }
    }

    // ---------------------------------------------------------------------
    //  ANA SAYFA
    // ---------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get(mainUrl, headers = pageHeaders, allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        var items = document.select(
            "article, .channel-item, .kanal-item, .card, .kanal, .channel, .match-item, .list-item, .post"
        ).mapNotNull { it.toSearchResult() }

        if (items.isEmpty()) {
            items = document.select("a[href]").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }.take(100))
    }

    // ---------------------------------------------------------------------
    //  ARAMA
    // ---------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val document = runCatching {
            app.get(mainUrl, headers = pageHeaders, allowRedirects = true).document
        }.getOrNull() ?: return emptyList()

        var items = document.select(
            "article, .channel-item, .kanal-item, .card, .kanal, .channel, .match-item, .list-item, .post"
        ).mapNotNull { it.toSearchResult() }

        if (items.isEmpty()) {
            items = document.select("a[href]").mapNotNull { it.toSearchResult() }
        }

        return items.distinctBy { it.url }
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(100)
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
            ?.substringBefore(" | ")?.trim() ?: return null

        // Kanalın gerçek oynatıcı sayfası: iframe varsa oradan al, yoksa kendisi
        val liveDataUrl = document.selectFirst("iframe[src]")?.let {
            fixUrl(it.attr("src"), pageUrl)
        } ?: pageUrl

        return newMovieLoadResponse(title, pageUrl, TvType.Live, liveDataUrl) {
            posterUrl = liveLogoFor(title)
            plot = "Canlı yayın — PatronSaha"
        }
    }

    // ---------------------------------------------------------------------
    //  HLS OYNATILABİLİRLİK TESTİ (Origin + Referer zorunlu)
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
    //  CHANNEL.HTML → CONFIG.domainUrl → baseurl → mono.m3u8
    // ---------------------------------------------------------------------
    private suspend fun extractStreamFromChannelPage(body: String, pageUrl: String): String? {
        val domainUrl = Regex("""domainUrl\s*:\s*['"]([^'"]+)['"]""")
            .find(body)?.groupValues?.getOrNull(1) ?: return null

        val id = runCatching {
            URI(pageUrl).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
        }.getOrNull() ?: Regex("""[?&]id=([^&"']+)""").find(pageUrl)?.groupValues?.getOrNull(1)
        ?: return null

        Log.d("Patron", "domainUrl=$domainUrl id=$id")

        val baseUrl = runCatching {
            val response = app.get(
                domainUrl,
                headers = pageHeaders + ("Referer" to pageUrl),
                allowRedirects = true
            ).text
            Regex("""["']?baseurl["']?\s*:\s*["']([^"']+)["']""")
                .find(response)?.groupValues?.getOrNull(1)
        }.getOrNull() ?: return null

        Log.d("Patron", "baseurl=$baseUrl")

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

            // --- 1) CHANNEL SAYFASI: CONFIG.domainUrl ile akışı kur ---
            if (Regex("(?i)(channel|player|watch|canli|live)").containsMatchIn(pageUrl) ||
                body.contains("domainUrl", ignoreCase = true)
            ) {
                val streamUrl = extractStreamFromChannelPage(body, pageUrl)
                if (streamUrl != null) {
                    val ok = isPlayableHls(streamUrl, "$mainUrl/", mainUrl)
                    Log.d("Patron", "HLS testi: $ok → $streamUrl")
                    if (ok && emitted.add(streamUrl)) {
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
                    } else if (!ok) {
                        Log.w("Patron", "HLS oynatılamadı (403/CF olabilir): $streamUrl")
                    }
                }
            }

            // --- 2) ALTYAZILAR ---
            document.select("track[src], track[data-src]").forEach { track ->
                val s = fixUrl(track.attr("src").ifBlank { track.attr("data-src") }, pageUrl) ?: return@forEach
                if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(s)) {
                    val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                    subtitleCallback(SubtitleFile(lang, s))
                }
            }

            // --- 3) DİĞER ADAYLAR (iframe, video, m3u8/mp4) ---
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
                .findAll(html).forEach { m ->
                    fixUrl(m.value, pageUrl)?.let { candidates.add(it) }
                }

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
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = cand,
                                type = if (path.endsWith(".mp4")) ExtractorLinkType.VIDEO
                                else ExtractorLinkType.M3U8
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