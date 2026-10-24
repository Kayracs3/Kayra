package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class PstronSH : MainAPI() {
    override var mainUrl = "https://patronsaha1.cfd"
    override var name = "PatronSaha"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val channelsUrl = "https://patronsports2.cfd/channels.php"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    // ==================== ANA SAYFA (JSON endpoint) ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(
            channelsUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept" to "application/json"
            )
        )
        val json = response.text
        Log.d("Patron", "channels.php yanıtı alındı, uzunluk=${json.length}")

        val items = parseChannelsJson(json)
        return newHomePageResponse("Kanallar", items)
    }

    /**
     * channels.php JSON yapısını ayrıştırır.
     * Yapıyı bilmediğim için yaygın anahtarları esnek şekilde kontrol eder.
     * Eğer JSON'un gerçek yapısı farklıysa sadece bu fonksiyonu güncelle.
     */
    private fun parseChannelsJson(json: String): List<SearchResponse> {
        val result = mutableListOf<SearchResponse>()
        val array = runCatching { JSONArray(json) }.getOrNull()
        val root = if (array != null) array else {
            // JSON bir nesne ise ve içinde dizi varsa (ör. {"channels":[...]})
            runCatching { JSONObject(json) }.getOrNull()?.let { obj ->
                obj.keys().asSequence()
                    .mapNotNull { key -> runCatching { obj.getJSONArray(key) }.getOrNull() }
                    .firstOrNull()
            }
        } ?: return emptyList()

        for (i in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(i) }.getOrNull() ?: continue

            // Olası alan adları
            val name = firstNonBlank(item,
                "name", "title", "channel", "kanal", "adi", "ad"
            ) ?: continue

            val logo = firstNonBlank(item,
                "logo", "image", "img", "icon", "resim", "poster"
            )

            // Kanalın hedef URL'si: stream, url, link, m3u8 vb.
            val streamUrl = firstNonBlank(item,
                "url", "stream", "link", "m3u8", "yayin", "adres"
            )

            // Eğer doğrudan stream URL'si yoksa, kanal adına göre bir ID oluşturup
            // loadLinks aşamasında çözmeyi deneyeceğiz.
            val data = streamUrl ?: "$channelsUrl#${item.optString("id", name)}"

            result += newMovieSearchResponse(name, data, TvType.NSFW) {
                this.posterUrl = logo
            }
        }

        Log.d("Patron", "JSON'dan ${result.size} kanal ayrıştırıldı")
        return result
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val v = obj.optString(key, "").trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    // ==================== ARAMA ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val all = getMainPage(1, MainPageRequest("Kanallar", null)).items
        return all.filter { it.name.contains(query, ignoreCase = true) }
    }

    // ==================== YÜKLEME ====================
    override suspend fun load(url: String): LoadResponse {
        // url zaten kanalın stream adresi veya channels.php#id formatında
        val title = url.substringAfterLast("#").ifBlank { "Kanal" }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = null
        }
    }

    // ==================== LİNKLER (DÜZELTİLMİŞ) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Eğer data doğrudan bir m3u8/mp4 ise, sayfa çekmeye gerek yok
        if (data.contains(".m3u8") || data.contains(".mp4")) {
            return emitIfPlayable(data, data, callback)
        }

        // channels.php#id formatındaysa veya sayfa URL'siyse, sayfayı çek
        val pageUrl = data.substringBefore("#")
        val doc = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent)).document
        val html = doc.html()

        val candidates = mutableListOf<Pair<String, String>>()

        // 1) baseurl + videoId -> {baseurl}/patron/{videoId}.m3u8
        val baseUrl = Regex("""baseurl\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val videoId = Regex("""(?:videoId|vid|slug|video|file|id)\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""/([^/]+)/?$""").find(pageUrl.trimEnd('/'))?.groupValues?.get(1)

        if (baseUrl != null && videoId != null) {
            candidates += "$baseUrl/patron/$videoId.m3u8" to pageUrl
        }

        // 2) HTML içindeki m3u8 URL'leri
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(html).forEach { candidates += it.value to pageUrl }

        // 3) <video> / <source> / <iframe>
        doc.select("video, video source, iframe[src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }
            if (raw.isNotBlank()) {
                val full = fixUrl(raw)
                if (full.contains(".m3u8") || full.contains(".mp4")) {
                    candidates += full to pageUrl
                }
            }
        }

        // 4) Reklamları (bsky dahil) çıkar, tekrarları sil
        val filtered = candidates.filter { !isAdUrl(it.first) }.distinctBy { it.first }
        Log.d("Patron", "toplam=${candidates.size} gecerli=${filtered.size}")

        // 5) Her adayı test et; yalnızca gerçekten açılanı kullanıcıya göster
        for ((url, referer) in filtered) {
            if (emitIfPlayable(url, referer, callback)) return true
        }

        Log.w("Patron", "Gecerli kaynak bulunamadi")
        return false
    }

    private suspend fun emitIfPlayable(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains(".m3u8")) {
            callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.headers = mapOf("User-Agent" to userAgent)
            })
            return true
        }

        if (!isPlayableHls(url, referer)) {
            Log.w("Patron", "HLS testi basarisiz: $url")
            return false
        }

        callback(newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.headers = mapOf("User-Agent" to userAgent)
        })
        return true
    }

    // ==================== REKLAM FİLTRESİ ====================
    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("bsky") || u.contains("video.bsky")) return true
        return Regex(
            "(sayac|ads?|banner|reklam|analytics|googletagmanager|beacon|chat\\.js|/cdn-cgi/|" +
            "preroll|pre-roll|mid-roll|post-roll|vast|vpaid|ima|doubleclick|" +
            "googlesyndication|adserver|advertisement|commercial|promo|sponsor|" +
            "popunder|popads|trafficjunky|exoclick|juicyads|/ad/|/ads/)"
        ).containsMatchIn(u)
    }

    // ==================== HLS TESTİ ====================
    private suspend fun isPlayableHls(url: String, referer: String): Boolean {
        val attempts = listOf(
            mapOf("User-Agent" to userAgent),
            mapOf("User-Agent" to userAgent, "Referer" to referer),
            mapOf("User-Agent" to userAgent, "Referer" to referer, "Origin" to mainUrl)
        )
        for (h in attempts) {
            val r = runCatching {
                app.get(url, headers = h, allowRedirects = true)
            }.getOrNull() ?: continue
            if (r.code !in 200..299) continue
            val body = r.text.trim()
            if (body.startsWith("#EXTM3U") &&
                (body.contains(".ts") || body.contains(".m4s") || body.contains("EXT-X-STREAM-INF"))
            ) {
                Log.d("Patron", "HLS OK: $url")
                return true
            }
        }
        return false
    }
}
