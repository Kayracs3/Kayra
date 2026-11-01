package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1592.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private const val AES_KEY = "T8r9Wq3mZ1kL5vYb"
        private const val AES_IV = "N2pR7sX4cV6bM1qF"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    // ================= ANA SAYFA =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/", headers = headers).document
        val home = mutableListOf<HomePageList>()

        val popular = document.select("div.movie-box, div.detail, article.item, .result-item, .ml-item")
            .mapNotNull { it.toSearchResult() }
        if (popular.isNotEmpty()) home.add(newHomePageList("Popüler", popular))

        val latest = document.select("div.last-episodes div.item, div.weekly div.item, .movies-list .ml-item, div.item")
            .mapNotNull { it.toSearchResult() }
        if (latest.isNotEmpty()) home.add(newHomePageList("Son Eklenenler", latest))

        return newHomePageResponse(home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, h4, .title, .movie-name, strong")?.text()?.trim() ?: return null
        val href = (this.selectFirst("a[href]") ?: this).attr("href")
        if (href.isBlank()) return null
        val poster = this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val url = normalizeUrl(href, mainUrl)
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
        }
    }

    // ================= ARAMA =================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val document = app.get(url, headers = headers).document
        return document.select("div.movie-box, div.detail, article.item, .result-item, .ml-item")
            .mapNotNull { it.toSearchResult() }
    }

    // ================= DETAY =================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, .title, .entry-title")?.text()?.trim() ?: "Bilinmeyen"
        val poster = document.selectFirst("div.poster img, .movie-poster img, meta[property=og:image]")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("content") }
        }
        val description = document.selectFirst("div.description, .entry-content, .contenido p")?.text()?.trim()
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val seasons = document.select("div.seasons a, .seasons-list a, ul.seasons a")
        if (seasons.isNotEmpty()) {
            seasons.forEach { seasonEl ->
                val seasonName = seasonEl.text().trim()
                val seasonHref = seasonEl.attr("href")
                if (seasonHref.isBlank()) return@forEach
                val seasonUrl = normalizeUrl(seasonHref, url)
                val seasonDoc = runCatching { app.get(seasonUrl, headers = headers, referer = url).document }.getOrNull() ?: return@forEach
                val seasonNum = Regex("""(\d+)""").find(seasonName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epLinks = seasonDoc.select("div.episodes a, ul.episode-list li a, .episode-list a, .bolumler a")
                epLinks.forEach { epEl ->
                    val epName = epEl.text().trim()
                    val epHref = epEl.attr("href")
                    if (epHref.isBlank()) return@forEach
                    val epUrl = normalizeUrl(epHref, seasonUrl)
                    val epPoster = epEl.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = Regex("""(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        this.posterUrl = epPoster?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, seasonUrl) }
                    })
                }
            }
        } else {
            val singleLink = document.selectFirst("div.episodes a, .play-button a, a.video, .bolumler a")
            val epUrl = if (singleLink != null) normalizeUrl(singleLink.attr("href"), url) else url
            episodes.add(newEpisode(epUrl) { this.name = "İzle" })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
            this.plot = description
            this.year = year
        }
    }

    // ================= OYNATICI (KRİTİK DÜZELTME) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = normalizeUrl(data, mainUrl)
        if (sourceUrl.isBlank()) return false

        val response = runCatching {
            app.get(sourceUrl, headers = headers, referer = "$mainUrl/", timeout = 15000, allowRedirects = true)
        }.getOrNull() ?: return false
        if (!response.isSuccessful) return false

        val document = response.document
        val raw = buildString {
            append(response.text); append("\n"); append(document.html())
            document.select("script, noscript, template").forEach {
                append("\n"); append(it.data()); append("\n"); append(it.html())
            }
        }.decodeEscapes()

        var found = false

        // 1) Kaynak sayfada doğrudan m3u8
        for (media in findMediaUrls(raw)) {
            if (emitMediaLink(media, sourceUrl, callback)) found = true
        }

        // 2) Şifreli config -> player URL
        val encrypted = extractEncryptedConfig(raw)
        if (encrypted != null) {
            val player = decryptDizipalConfig(encrypted)
            if (player.isNotBlank()) {
                Log.d("DiziPal", "Player URL: " + player.take(200))

                val playerWithScheme = when {
                    player.startsWith("//") -> "https:$player"
                    !player.startsWith("http") -> "https://$player"
                    else -> player
                }
                val iframeUrl = normalizeUrl(playerWithScheme, sourceUrl)
                Log.d("DiziPal", "Iframe URL: $iframeUrl")

                // ★★★ KRİTİK: İframe URL'sindeki ?v= parametresini çıkar ★★★
                // Örnek: https://four.dplayer82.site/iframe.php?v=0ec9a3... -> "0ec9a3..."
                val vParam = Regex("""[?&]v=([^&]+)""").find(iframeUrl)?.groupValues?.getOrNull(1)
                Log.d("DiziPal", "v param: $vParam")

                val iframeHeaders = headers + mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to sourceUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                )

                val iframeResponse = runCatching {
                    app.get(iframeUrl, headers = iframeHeaders, referer = sourceUrl, timeout = 15000, allowRedirects = true)
                }.getOrNull()

                if (iframeResponse != null && iframeResponse.isSuccessful) {
                    Log.d("DiziPal", "Iframe başarılı: ${iframeResponse.code}")
                    val iframeHtml = iframeResponse.text.decodeEscapes()

                    // Altyazılar
                    if (extractSubtitles(iframeHtml, iframeUrl, subtitleCallback)) found = true

                    // ★★★ ASIL ÇÖZÜM: v param ile doğrudan source2.php çağır ★★★
                    if (!vParam.isNullOrBlank()) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val source2Url = origin.trimEnd('/') + "/source2.php?v=" + URLEncoder.encode(vParam, "UTF-8")
                        Log.d("DiziPal", "Source2 URL (v param): $source2Url")

                        val source2Response = runCatching {
                            app.get(
                                source2Url,
                                headers = iframeHeaders + mapOf(
                                    "Accept" to "application/json,text/plain,*/*",
                                    "X-Requested-With" to "XMLHttpRequest",
                                ),
                                referer = iframeUrl,
                                timeout = 15000,
                                allowRedirects = true,
                            )
                        }.getOrNull()

                        if (source2Response != null && source2Response.isSuccessful) {
                            val source2Text = source2Response.text.decodeEscapes()
                            Log.d("DiziPal", "Source2 yanıtı: ${source2Text.take(500)}")

                            // m3u8 / mp4
                            for (media in extractFileUrls(source2Text)) {
                                if (emitMediaLink(media, iframeUrl, callback)) found = true
                            }
                            // Altyazı
                            if (extractSubtitles(source2Text, iframeUrl, subtitleCallback)) found = true
                        } else {
                            Log.e("DiziPal", "Source2 başarısız: ${source2Response?.code}")
                        }
                    }

                    // Alternatif: window.openPlayer (eski desen) - v param yoksa veya farklıysa
                    val playlistId = Regex("""window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                        .find(iframeHtml)?.groupValues?.getOrNull(1)
                    if (!playlistId.isNullOrBlank() && playlistId != vParam) {
                        val origin = originOf(iframeUrl) ?: iframeUrl
                        val s2Url = origin.trimEnd('/') + "/source2.php?v=" + URLEncoder.encode(playlistId, "UTF-8")
                        Log.d("DiziPal", "Source2 URL (openPlayer): $s2Url")
                        val s2 = runCatching {
                            app.get(s2Url, headers = iframeHeaders, referer = iframeUrl, timeout = 15000, allowRedirects = true)
                        }.getOrNull()
                        if (s2 != null && s2.isSuccessful) {
                            for (media in extractFileUrls(s2.text.decodeEscapes())) {
                                if (emitMediaLink(media, iframeUrl, callback)) found = true
                            }
                        }
                    }

                    // İframe HTML'inde doğrudan m3u8
                    for (media in findMediaUrls(iframeHtml)) {
                        if (emitMediaLink(media, iframeUrl, callback)) found = true
                    }

                    // JSON'daki "file":"...", "url":"...", "src":"..." desenleri
                    val patterns = listOf(
                        Regex(""""file"\s*:\s*"([^"]+)""""),
                        Regex(""""url"\s*:\s*"([^"]+)""""),
                        Regex(""""source"\s*:\s*"([^"]+)""""),
                        Regex(""""src"\s*:\s*"([^"]+)""""),
                        Regex("""file\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""url\s*:\s*['"]([^'"]+)['"]"""),
                    )
                    for (p in patterns) {
                        p.findAll(iframeHtml).forEach { m ->
                            val candidate = m.groupValues[1].decodeEscapes()
                            if (candidate.contains(".m3u8") || candidate.contains(".mp4")) {
                                if (emitMediaLink(candidate, iframeUrl, callback)) found = true
                            }
                        }
                    }
                } else {
                    Log.e("DiziPal", "Iframe başarısız: ${iframeResponse?.code}")
                }
            } else {
                Log.e("DiziPal", "Şifre çözme başarısız, player URL boş.")
            }
        } else {
            Log.e("DiziPal", "Şifreli config bulunamadı.")
        }

        // 3) Kaynak sayfada iframe/video etiketleri
        document.select("iframe[src], iframe[data-src], video[src], video source[src], source[src], [data-video-url], [data-stream]").forEach { el ->
            val rawUrl = el.attr("src").ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-video-url") }.ifBlank { el.attr("data-stream") }
            if (rawUrl.isBlank()) return@forEach
            val target = normalizeUrl(rawUrl, sourceUrl)
            if (isMediaUrl(target) && emitMediaLink(target, sourceUrl, callback)) found = true
        }

        // 4) Track tag'leri
        document.select("track[src], track[data-src]").forEach { track ->
            val rawUrl = track.attr("src").ifBlank { track.attr("data-src") }
            if (rawUrl.isBlank()) return@forEach
            runCatching {
                subtitleCallback(newSubtitleFile(
                    lang = track.attr("label").ifBlank { "Türkçe" },
                    url = normalizeUrl(rawUrl, sourceUrl)
                ))
            }
        }

        if (!found) Log.e("DiziPal", "Hiçbir oynatma linki bulunamadı.")
        return found
    }

    // ================= YARDIMCI FONKSİYONLAR =================
    private fun normalizeUrl(url: String, base: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { java.net.URI(base).resolve(url).toString() }
        catch (_: Exception) { base.trimEnd('/') + "/" + url.trimStart('/') }
    }

    private fun String.decodeEscapes(): String = this
        .replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")

    private fun originOf(url: String): String? = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    } catch (_: Exception) { null }

    private fun isMediaUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".m3u8") || u.endsWith(".mp4") || u.endsWith(".mpd") ||
                u.contains(".m3u8?") || u.contains(".mp4?") || u.contains(".mpd?") ||
                u.contains("/master.m3u8") || u.contains("/playlist.m3u8")
    }

    private fun findMediaUrls(text: String): List<String> {
        val regex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.distinct().toList()
    }

    private fun extractFileUrls(text: String): List<String> {
        val results = mutableListOf<String>()
        results.addAll(findMediaUrls(text))
        Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes(); if (raw.isNotBlank()) results.add(raw)
        }
        Regex("""src\s*=\s*["']([^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            results.add(it.groupValues[1].decodeEscapes())
        }
        return results.distinct()
    }

    private fun extractEncryptedConfig(text: String): String? {
        val patterns = listOf(
            Regex("""(?:const|let|var)\s+_?config\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:const|let|var)\s+_?enc\w*\s*=\s*['"]([^'"]{20,})['"]""", RegexOption.IGNORE_CASE),
            Regex(""""config"\s*:\s*"([^"]{20,})"""", RegexOption.IGNORE_CASE)
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
        }.getOrElse { runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }.getOrDefault(clean) }
    }

    private fun emitMediaLink(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val finalUrl = normalizeUrl(url, referer)
        if (!isMediaUrl(finalUrl)) return false
        val type = when {
            finalUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            finalUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url = finalUrl,
            type = type
        ) {
            this.referer = referer
            this.headers = mediaHeaders(referer)
            this.quality = Qualities.P1080.value
        })
        Log.d("DiziPal", "Link eklendi: $finalUrl")
        return true
    }

    private fun mediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to referer,
        "Origin" to (originOf(referer) ?: referer),
        "Accept" to "*/*"
    )

    private fun extractSubtitles(text: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit): Boolean {
        var any = false
        Regex(""""subtitle"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes()
            if (raw.isBlank()) return@forEach
            runCatching { subtitleCallback(newSubtitleFile(lang = "Türkçe", url = normalizeUrl(raw, baseUrl))); any = true }
        }
        Regex("""<track[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val raw = it.groupValues[1].decodeEscapes()
            if (raw.isBlank()) return@forEach
            runCatching { subtitleCallback(newSubtitleFile(lang = "Türkçe", url = normalizeUrl(raw, baseUrl))); any = true }
        }
        return any
    }
}
