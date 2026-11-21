package com.Kayracs3

import android.content.SharedPreferences
import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class KoalaTVProvider(private val prefs: SharedPreferences) : MainAPI() {
    override var mainUrl = API_ORIGIN
    override var name = "KoalaTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private data class Session(
        val domain: String,
        val username: String,
        val password: String,
        val expiresAt: Long
    )

    private data class Channel(
        val id: String,
        val name: String,
        val logo: String,
        val ext: String
    )

    private val sessionMutex = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var cacheTime = 0L

    private val channelCache = ConcurrentHashMap<String, List<Channel>>()

    companion object {
        private const val API_ORIGIN = "https://easyapi.sh"
        private const val ACQUIRE_URL = "https://easyapi.sh/live-tv/api/v1/acquire"
        private const val PREF_TOKEN = "install_token"
        private const val PREF_COUNTRIES = "enabled_countries"
        private const val USER_AGENT = "okhttp/4.12.0"
        private const val CACHE_TTL = 300_000L
        private const val DEFAULT_LOGO =
            "https://raw.githubusercontent.com/Wiojelt/test/main/assets/koalatv.png"

        private val IPV4 = Regex("""^\d+\.\d+\.\d+\.\d+$""")

        val COUNTRY_OPTIONS = listOf(
            "TR" to "🇹🇷 Türkiye (TR)",
            "AZ" to "🇦🇿 Azerbaycan (AZ)",
            "DE" to "🇩🇪 Almanya & Avusturya (DE)",
            "UK" to "🇬🇧 Birleşik Krallık (UK)",
            "US" to "🇺🇸 Amerika Birleşik Devletleri (US)",
            "FR" to "🇫🇷 Fransa (FR)",
            "IT" to "🇮🇹 İtalya (IT)",
            "ES" to "🇪🇸 İspanya (ES)",
            "NL" to "🇳🇱 Hollanda (NL)",
            "AR" to "🇸🇦 Arap Dünyası (AR)",
            "ALL" to "🌍 Tüm Dünya Kanalları (ALL)"
        )

        private val TR_SECTIONS = listOf(
            "sp_bein" to "⚽ beIN Sports",
            "sp_ssport" to "⚽ S Sport",
            "sp_tivibu" to "⚽ Tivibu Spor",
            "sp_exxen" to "⚽ Exxen Spor",
            "sp_tabii" to "⚽ tabii Spor",
            "sp_eurosport" to "⚽ Eurosport & Smart Spor",
            "sp_other" to "⚽ Diğer Spor Kanalları",
            "141" to "🔴 Ulusal Kanallar",
            "145" to "📰 Haber Kanalları",
            "147" to "🎬 Sinema Kanalları",
            "143" to "🌍 Belgesel Kanalları",
            "146" to "👶 Çocuk Kanalları",
            "148" to "🎵 Müzik Kanalları",
            "263" to "🎞️ 24/7 Dizi & Film",
            "144_149" to "📺 Diğer & Dini Kanallar"
        )

        private val SPORT_TERMS = listOf(
            "SMART", "EUROSPORT", "TABII", "EXXEN", "TIVIBU", "S SPORT", "BEIN"
        )

        private fun cleanName(raw: String): String {
            return raw
                .replace(Regex("""^TR\|\s*"""), "")
                .replace(Regex("""^✦●✦.*?✦●✦"""), "")
                .trim()
                .ifBlank { "Canlı Yayın" }
        }

        private fun encode(value: String): String = Uri.encode(value)
    }

    fun enabledCountries(): Set<String> {
        val saved = prefs.getStringSet(PREF_COUNTRIES, setOf("TR"))
            ?.filter { code -> COUNTRY_OPTIONS.any { it.first == code } }
            ?.toSet()
            .orEmpty()
        return saved.ifEmpty { setOf("TR") }
    }

    fun saveCountries(codes: Set<String>) {
        val valid = codes
            .filter { code -> COUNTRY_OPTIONS.any { it.first == code } }
            .toMutableSet()
        if (valid.isEmpty()) valid.add("TR")
        prefs.edit().putStringSet(PREF_COUNTRIES, valid).apply()
        channelCache.clear()
        cacheTime = 0L
    }

    override val mainPage: List<MainPageData>
        get() {
            val sections = enabledCountries()
                .flatMap { sectionsForCountry(it) }
                .distinctBy { it.first }
            return mainPageOf(*sections.toTypedArray())
        }

    private fun sectionsForCountry(country: String): List<Pair<String, String>> {
        return when (country) {
            "TR" -> TR_SECTIONS
            "AZ" -> listOf("152" to "🇦🇿 Azerbaycan Kanalları")
            "DE" -> listOf(
                "313" to "🇩🇪 Almanya Spor",
                "311" to "🇩🇪 Almanya Genel"
            )
            "UK" -> listOf(
                "73" to "🇬🇧 Birleşik Krallık Spor",
                "71" to "🇬🇧 Birleşik Krallık Genel",
                "76" to "🇬🇧 Birleşik Krallık Sinema"
            )
            "US" -> listOf(
                "269" to "🇺🇸 ABD Peacock",
                "273" to "🇺🇸 ABD DSTV Spor",
                "177" to "🇺🇸 ABD Streamed Canlı"
            )
            "FR" -> listOf(
                "277" to "🇫🇷 Fransa Canal+ Spor",
                "281" to "🇫🇷 Fransa Eğlence"
            )
            "IT" -> listOf("66" to "🇮🇹 İtalya Genel")
            "ES" -> listOf(
                "289" to "🇪🇸 İspanya Çocuk",
                "290" to "🇪🇸 İspanya Müzik"
            )
            "NL" -> listOf("87" to "🇳🇱 Hollanda Kanalları")
            "AR" -> listOf(
                "3" to "🇸🇦 Arap Spor",
                "5" to "🇸🇦 beIN Eğlence",
                "7" to "🇸🇦 OSN Kanalları"
            )
            "ALL" -> listOf("all_world" to "🌍 Tüm Canlı Yayınlar")
            else -> emptyList()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = streamsFor(request.data).map { it.asSearch() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private suspend fun streamsFor(section: String): List<Channel> {
        if (section == "144_149") {
            return (rawStreams("144") + rawStreams("149")).distinctBy { it.id }
        }

        if (!section.startsWith("sp_")) return rawStreams(section)

        val channels = (
            rawStreams("142") + rawStreams("144") + rawStreams("149")
        ).distinctBy { it.id }

        return channels.filter { channel ->
            val title = channel.name.uppercase(Locale.ROOT)
            when (section) {
                "sp_bein" -> "BEIN" in title
                "sp_ssport" -> "S SPORT" in title || "SSPORT" in title
                "sp_tivibu" -> "TIVIBU" in title
                "sp_exxen" -> "EXXEN" in title
                "sp_tabii" -> "TABII" in title
                "sp_eurosport" -> "EUROSPORT" in title || "SMART" in title
                "sp_other" -> SPORT_TERMS.none { it in title }
                else -> false
            }
        }
    }

    private suspend fun allChannels(): List<Channel> {
        val result = LinkedHashMap<String, Channel>()
        getMainPage().forEach { page ->
            streamsFor(page.data).forEach { channel ->
                result.putIfAbsent(channel.id, channel)
            }
        }
        return result.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        return allChannels()
            .filter { it.name.contains(term, ignoreCase = true) }
            .map { it.asSearch() }
    }

    private fun Channel.asSearch(): SearchResponse {
        return newLiveSearchResponse(name, "koalatv://$id", TvType.Live, fix = false) {
            posterUrl = logo.ifBlank { DEFAULT_LOGO }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("koalatv://")) return null
        val id = url.substringAfter("koalatv://")
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (id.isBlank()) return null

        var channel = channelCache.values.asSequence()
            .flatten()
            .firstOrNull { it.id == id }

        if (channel == null) {
            channel = allChannels().firstOrNull { it.id == id }
        }

        val selected = channel
            ?: throw ErrorLoadingException("KoalaTV kanalı bulunamadı: $id")

        val data = JSONObject()
            .put("stream_id", selected.id)
            .put("name", selected.name)
            .put("container_ext", selected.ext)
            .put("icon", selected.logo)
            .toString()

        return newLiveStreamLoadResponse(
            selected.name,
            "$API_ORIGIN/live/" + selected.id,
            data
        ) {
            posterUrl = selected.logo.ifBlank { DEFAULT_LOGO }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val id = json.optString("stream_id").trim()
        if (id.isBlank()) return false

        val current = getSession()
        var domain = current.domain
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        if (domain.isBlank()) return false

        val useHttp = IPV4.matches(domain.substringBefore(':')) || domain.endsWith(":80")
        if (domain.endsWith(":80")) domain = domain.removeSuffix(":80")
        val origin = (if (useHttp) "http://" else "https://") + domain
        val user = encode(current.username)
        val pass = encode(current.password)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/"
        )
        val title = json.optString("name").ifBlank { "Canlı Yayın" }

        val primary = "$origin/live/$user/$pass/" + encode(id) + ".ts"
        callback(
            newExtractorLink(
                source = name,
                name = "$title (Canlı HD)",
                url = primary,
                type = ExtractorLinkType.VIDEO
            ) {
                this.headers = headers
                this.referer = "$origin/"
            }
        )

        val extension = json.optString("container_ext", "ts")
            .trim()
            .removePrefix(".")
            .ifBlank { "ts" }

        if (!extension.equals("ts", ignoreCase = true)) {
            val alternative = "$origin/live/$user/$pass/" + encode(id) + "." + encode(extension)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$title (Alternatif)",
                    url = alternative,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = headers
                    this.referer = "$origin/"
                }
            )
        }
        return true
    }

    private suspend fun getSession(): Session = sessionMutex.withLock {
        session?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis() + 30_000L) {
                return@withLock cached
            }
        }
        acquireSession().also { session = it }
    }

    private fun installationToken(): String {
        val old = prefs.getString(PREF_TOKEN, null)
        if (!old.isNullOrBlank() && Regex("^[0-9a-f]{64}$").matches(old)) return old

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_TOKEN, token).apply()
        return token
    }

    private fun deviceId(token: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("easyplay-live-tv-fallback-v1:$token".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private suspend fun acquireSession(): Session {
        val token = installationToken()
        val headers = mapOf(
            "X-Installation-Token" to token,
            "X-Device-Id" to deviceId(token),
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT
        )
        val body = JSONObject()
            .put("takeover", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = app.post(
            ACQUIRE_URL,
            headers = headers,
            requestBody = body
        )
        if (!response.isSuccessful) {
            throw ErrorLoadingException("KoalaTV oturumu alınamadı (HTTP hatası).")
        }

        val json = runCatching { JSONObject(response.text) }.getOrElse {
            throw ErrorLoadingException("KoalaTV oturum yanıtı okunamadı.")
        }
        val playlist = json.optString("playlist_url").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("KoalaTV yayın adresi alınamadı.")
        val uri = Uri.parse(playlist)

        var domain = json.optString("playlist_domain").trim()
        if (domain.isBlank()) domain = uri.host.orEmpty()
        if (domain.isBlank()) {
            domain = runCatching { java.net.URI(playlist).host.orEmpty() }.getOrDefault("")
        }
        domain = domain.removePrefix("https://").removePrefix("http://").trimEnd('/')

        val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("KoalaTV kullanıcı adı alınamadı.")
        val password = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("KoalaTV oturum şifresi alınamadı.")
        if (domain.isBlank()) {
            throw ErrorLoadingException("KoalaTV yayın sunucusu alınamadı.")
        }

        val expiry = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        return Session(
            domain,
            username,
            password,
            System.currentTimeMillis() + expiry * 1000L
        )
    }

    private suspend fun rawStreams(category: String): List<Channel> {
        val now = System.currentTimeMillis()
        if (now - cacheTime > CACHE_TTL) {
            channelCache.clear()
            cacheTime = now
        }
        channelCache[category]?.let { return it }

        val current = getSession()
        val origin = "https://" + current.domain
        val base = "$origin/player_api.php?username=" + encode(current.username) +
            "&password=" + encode(current.password) + "&action=get_live_streams"
        val url = if (category == "all_world") {
            base
        } else {
            "$base&category_id=" + encode(category)
        }

        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "$origin/"
            ),
            referer = "$origin/"
        )
        if (!response.isSuccessful) {
            throw ErrorLoadingException("KoalaTV kanal listesi alınamadı (HTTP hatası).")
        }

        val array = runCatching { JSONArray(response.text) }.getOrElse {
            throw ErrorLoadingException("KoalaTV kanal listesi JSON olarak okunamadı.")
        }
        val channels = ArrayList<Channel>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rawName = item.optString("name").trim()
            if (!rawName.startsWith("✦") && !rawName.startsWith("●")) continue
            val id = item.optString("stream_id").trim()
            if (id.isBlank()) continue

            val logo = item.optString("stream_icon")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("icon")
                    .takeIf { it.isNotBlank() && it != "null" }
                ?: DEFAULT_LOGO
            val extension = item.optString("container_extension", "ts")
                .trim()
                .removePrefix(".")
                .ifBlank { "ts" }

            channels += Channel(id, cleanName(rawName), logo, extension)
        }

        channelCache[category] = channels
        return channels
    }
}
