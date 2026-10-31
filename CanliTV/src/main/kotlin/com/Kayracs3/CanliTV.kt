package Kayracs3

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Orijinaldeki uygulama kilidi (decompile çıktısından aynen aktarıldı).
 *
 * Yalnızca PKGS içindeki paketlerde, ve APK imzasının SHA-256'sı SHAS içindeyse true döner.
 * Başka bir pakette (ör. standart CloudStream) sonuç false'tur ve eklenti boş döner.
 */
internal object ParsGuard {
    private val PKGS = setOf("dev.akash.skystream", "com.vip.burda.vip")
    private val SHAS = setOf(
        "55d84f02b8e77dda7e5db3d4cf797795ffd916e2d9d817ba0a96df1b583fbd20",
        "fc7fd6dea039880cc22579c4bbc9682848bfbe23c16ed2a9059b8c958be9eedc",
    )

    @Volatile
    private var cached: Boolean? = null

    val ok: Boolean
        get() = cached ?: compute().also { cached = it }

    @Suppress("DEPRECATION")
    private fun compute(): Boolean = runCatching {
        val ctx = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
            ?: return@runCatching true
        if (ctx.packageName !in PKGS) return@runCatching false

        val pm = ctx.packageManager
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        if (sigs == null) return@runCatching true

        val md = MessageDigest.getInstance("SHA-256")
        sigs.any { sig ->
            md.reset()
            md.digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .lowercase(Locale.ROOT) in SHAS
        }
    }.getOrDefault(true)
}

class PuuBeles : MainAPI() {
    override var mainUrl = "https://core.belestivi.workers.dev"
    override var name = "PuuBeles"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val workerUrl = "https://core.belestivi.workers.dev"
    private val appToken = "bt_16b88c4e9b323b9c792a8b642f2f4237d8a297ba4f5ab7ea"
    private val plusUrl =
        "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"
    private val vlcUa = "VLC/3.0.16 LibVLC/3.0.16"
    private val includePlus = true

    @Volatile
    private var cache: Pair<Long, List<Channel>>? = null

    private data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val headers: Map<String, String>,
    )

    // ------------------------------------------------------- kanal listesi

    private suspend fun channels(): List<Channel> {
        val now = System.currentTimeMillis()
        cache?.let { (t, list) ->
            if (now - t < CACHE_MS && list.isNotEmpty()) return list
        }

        // 1) Worker'dan ana liste
        val body = runCatching {
            app.get(
                workerUrl,
                headers = mapOf(
                    "X-App-Token" to appToken,
                    "User-Agent" to vlcUa,
                    "Accept-Encoding" to "identity",
                ),
                timeout = 30L,
            ).text
        }.onFailure { Log.e(TAG, "WORKER_FAIL ${it.message}") }.getOrNull() ?: ""

        val list = parseM3u(body, "").toMutableList()
        Log.i(
            TAG,
            "WORKER kanal=${list.size} kategori=${list.map { it.group }.distinct().size} bayt=${body.length}"
        )
        if (list.isEmpty()) Log.w(TAG, "WORKER_BODY ${body.take(200)}")

        if (!includePlus) {
            if (list.isNotEmpty()) cache = now to list
            return list
        }

        // 2) GitHub'daki "plus" listesi (YouTube grupları hariç)
        val plusBody = runCatching {
            app.get(plusUrl, headers = mapOf("User-Agent" to vlcUa), timeout = 20L).text
        }.onFailure { Log.e(TAG, "PLUS_FAIL ${it.message}") }.getOrNull() ?: ""

        val plus = parseM3u(plusBody, "PLUS • ")
            .filter { !it.group.contains("youtube", ignoreCase = true) }
        Log.i(TAG, "PLUS kanal=${plus.size}")
        list.addAll(plus)

        if (list.isNotEmpty()) cache = now to list
        return list
    }

    /** #EXTINF / #EXTVLCOPT / url|Header=Value&... biçimli M3U ayrıştırıcı. */
    private fun parseM3u(text: String, prefix: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var group = "Diğer"
        var opts = LinkedHashMap<String, String>()
        var name = ""
        var logo = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title").ifBlank { "Diğer" }
                name = line.substringAfterLast(',', "").trim().ifBlank { attr(line, "tvg-name") }
                opts = LinkedHashMap()
                attr(line, "user-agent").ifBlank { null }?.let { opts["User-Agent"] = it }
                attr(line, "http-referrer").ifBlank { null }?.let { opts["Referer"] = it }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val kv = line.substringAfter(':')
                val k = kv.substringBefore('=').trim().lowercase(Locale.ROOT)
                val v = kv.substringAfter('=', "").trim()
                when (k) {
                    "http-user-agent" -> opts["User-Agent"] = v
                    "http-referrer", "http-referer" -> opts["Referer"] = v
                    "http-origin" -> opts["Origin"] = v
                }
            } else if (!line.startsWith("#")) {
                if (line.startsWith("http", ignoreCase = true) && name.isNotBlank()) {
                    val url = line.substringBefore('|').trim()
                    val hdr = LinkedHashMap(opts)
                    if (line.contains('|')) {
                        line.substringAfter('|').split('&').forEach { p ->
                            val k = p.substringBefore('=').trim()
                            val v = p.substringAfter('=', "").trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) hdr[normHeader(k)] = v
                        }
                    }
                    out.add(Channel(name, url, logo, prefix + group.trim(), hdr))
                    name = ""
                }
            }
        }
        return out
    }

    private fun normHeader(k: String): String = when (k.lowercase(Locale.ROOT)) {
        "user-agent", "useragent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        else -> k
    }

    private fun attr(line: String, key: String): String =
        Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim() ?: ""

    /** Arama için: küçült, Türkçe karakterleri sadeleştir, sadece a-z0-9 bırak. */
    private fun fold(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace("i̇", "i")
            .replace('ı', 'i')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .filter { it in 'a'..'z' || it in '0'..'9' }

    private fun toResult(c: Channel): SearchResponse {
        val data = JSONObject()
            .put("type", "movie")
            .put("title", c.name)
            .put("url", c.url)
            .put("logo", c.logo)
            .put("group", c.group)
            .put("headers", JSONObject(c.headers as Map<*, *>))
            .toString()
        return newLiveSearchResponse(c.name, data, TvType.Live) {
            posterUrl = c.logo.takeIf { it.startsWith("http") }
        }
    }

    // ------------------------------------------------------------ ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (!ParsGuard.ok || page > 1) return newHomePageResponse(emptyList(), false)

        val all = channels()
        if (all.isEmpty()) throw Exception("Beleş TiVi listesine ulaşılamadı")

        val lists = all.groupBy { it.group }
            .map { (g, items) -> HomePageList(g, items.map { toResult(it) }, false) }
        return newHomePageResponse(lists, false)
    }

    // ---------------------------------------------------------------- arama

    override suspend fun search(query: String): List<SearchResponse> {
        if (!ParsGuard.ok) return emptyList()
        val q = fold(query)
        if (q.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return channels()
            .filter { fold(it.name).contains(q) && seen.add(it.url) }
            .map { toResult(it) }
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        if (!ParsGuard.ok) return null
        val o = runCatching { JSONObject(url) }.getOrNull() ?: return null
        return newLiveStreamLoadResponse(o.optString("title").trim(), url, url) {
            posterUrl = o.optString("logo").takeIf { it.startsWith("http") }
            plot = o.optString("group").ifBlank { null }?.let { "Beleş TiVi • $it" }
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!ParsGuard.ok) return false
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val title = o.optString("title").trim()
        val url = o.optString("url").trim()
        if (!url.startsWith("http", ignoreCase = true)) return false

        val hdr = LinkedHashMap<String, String>()
        o.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k -> hdr[k] = h.optString(k) }
        }
        if (hdr.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            hdr["User-Agent"] = vlcUa
        }

        // Referer ayrı alana taşınır, header'dan çıkarılır
        val ref = hdr.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: ""
        hdr.keys.filter { it.equals("Referer", ignoreCase = true) }.forEach { hdr.remove(it) }

        val l = url.lowercase(Locale.ROOT)
        val isHls = l.contains(".m3u8") || l.contains("/hls/") || l.contains(".m3u") || l.contains("m3u8")
        // Yazarın uygulamasının tanıdığı özel işaret
        if (isHls) hdr["X-PARS-BROWSER-HLS"] = "1"

        callback(
            newExtractorLink(
                name, title, url,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.headers = hdr
            }
        )
        Log.i(TAG, "LINK $title hls=$isHls $url")
        return true
    }

    companion object {
        private const val CACHE_MS = 600_000L
        private const val TAG = "PUUBELES"
    }
}
