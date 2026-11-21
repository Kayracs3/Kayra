package com.Kayracs3

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.util.concurrent.CancellationException

/**
 * KayraLive is a general M3U/M3U8 playlist provider.
 * It only reads a playlist URL explicitly configured by the user.
 * Use only streams you are authorized to access.
 */
class KayraLive(private val context: Context) : MainAPI() {
    override var mainUrl = "https://github.com/Kayracs3/Kayra"
    override var name = "KayraLive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private data class Channel(
        val name: String,
        val streamUrl: String,
        val group: String,
        val logo: String?
    )

    @Volatile private var cachedChannels: List<Channel> = emptyList()
    @Volatile private var cacheTimestamp = 0L

    companion object {
        private const val PREFS_NAME = "kayra_live_playlist"
        private const val PLAYLIST_KEY = "playlist_url"
        // Varsayılan listeler birleştirilir; her satır ayrı bir M3U kaynağıdır.
        private val DEFAULT_PLAYLIST_URLS = listOf(
            "https://iptv-org.github.io/iptv/languages/tur.m3u",
            "https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u"
        )
        private const val MAX_PLAYLIST_SOURCES = 10
        // Kullanıcının verdiği 21 M3U kaydı. Aynı URL farklı adla gelirse ikisi de korunur.
        private val DEFAULT_DIRECT_CHANNELS = listOf(
            Channel("Sinema Tv", "http://65.108.239.207/sinema1001/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/0/0/Vod/HLS/22c5fe05-d70b-4c68-98fb-03bae4870e81.png"),
            Channel("Sinema Tv 2", "http://65.108.239.207/sinema2/index.m3u8", "7/24 Dizi/Film", "https://itv224226.tmp.tivibu.com.tr:6430/images/poster/20250801839032.png"),
            Channel("Sinema Aksiyon", "http://65.108.239.207/sinemaaksiyon/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/500/500/channels/logos/bd434731-0e47-4a37-997e-49f71340fcc6.png"),
            Channel("Sinema Aksiyon 2", "http://65.108.239.207/sinemaaksiyon2/index.m3u8", "7/24 Dizi/Film", "https://img.dsmartgo.com.tr/content/M/RG/22895/Thumbnail.png"),
            Channel("Sinema Yerli", "http://65.108.239.207/sinemayerli/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/500/500/channels/logos/ae3d90a0-37ee-4e48-a6bc-1375e5f1329a.png"),
            Channel("Sinema Aile", "http://65.108.239.207/sinemaaile/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/500/500/channels/logos/c8283b71-81f8-4fc6-8e48-6bb8f7f49ad7.png"),
            Channel("Sinema 1001", "http://65.108.239.207/sinema1001/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/500/500/channels/logos/8d9fbebd-cade-492d-9cb4-6522cb558794.png"),
            Channel("Sinema 1002", "http://65.108.239.207/sinema1002/index.m3u8", "7/24 Dizi/Film", "https://itv224226.tmp.tivibu.com.tr:6430/images/poster/20250801838773.png"),
            Channel("Sinema Komedi", "http://65.108.239.207/sinemakomedi/index.m3u8", "7/24 Dizi/Film", "https://feo.kablowebtv.com/resize/168A635D265A4328C2883FB4CD8FF/500/500/channels/logos/4145ea10-96ad-400d-8870-f22a075f4add.png"),
            Channel("Bein Movies Action HD", "http://65.108.239.207/bsaction1/index.m3u8", "7/24 Dizi/Film", "https://mediacms01.digiturkplay.com/channel_logo/Bein_Movies_Action_HD_yeni.png"),
            Channel("Bein Movies Stars HD", "http://65.108.239.207/bsstars/index.m3u8?token=WbvG4CpE9S4KI8vmXLtsQw&tms=1771162595", "7/24 Dizi/Film", "https://mediacms01.digiturkplay.com/channel_logo/Bein_Movies_Stars_HD_yeni.png"),
            Channel("Bein Movies Family HD", "http://65.108.239.207/bsfamily/index.m3u8", "7/24 Dizi/Film", "https://mediacms01.digiturkplay.com/channel_logo/Bein_Movies_Family_HD_yeni.png"),
            Channel("Bein Movies Premiere HD", "http://65.108.239.207/bspremier1/index.m3u8", "7/24 Dizi/Film", "https://mediacms01.digiturkplay.com/channel_logo/Bein_Movies_Premiere_HD_yeni.png"),
            Channel("Bein Movies Turk HD", "http://65.108.239.207/bsturk/index.m3u8", "7/24 Dizi/Film", "https://mediacms01.digiturkplay.com/channel_logo/Bein_Movies_Turk_HD_yeni.png"),
            Channel("Bein Series 1 HD", "http://65.108.239.207/bsseries1/index.m3u8", "7/24 Dizi/Film", "https://w1.pngwing.com/pngs/910/513/png-transparent-network-bein-series-logo-bein-channels-network-bein-media-group-bein-sports-1-lyngsat-organization.png"),
            Channel("Bein Series 2 HD", "http://65.108.239.207/bsseries2/index.m3u8", "7/24 Dizi/Film", "https://w1.pngwing.com/pngs/910/513/png-transparent-network-bein-series-logo-bein-channels-network-bein-media-group-bein-sports-1-lyngsat-organization.png"),
            Channel("Bein Series 3 HD", "http://65.108.239.207/bsseries3/index.m3u8", "7/24 Dizi/Film", "https://w1.pngwing.com/pngs/910/513/png-transparent-network-bein-series-logo-bein-channels-network-bein-media-group-bein-sports-1-lyngsat-organization.png"),
            Channel("Bein Series 4 HD", "http://65.108.239.207/bsseries4/index.m3u8", "7/24 Dizi/Film", "https://w1.pngwing.com/pngs/910/513/png-transparent-network-bein-series-logo-bein-channels-network-bein-media-group-bein-sports-1-lyngsat-organization.png"),
            Channel("BS BOX OFFİCE 1", "http://65.108.239.207/box1/index.m3u8", "7/24 Dizi/Film", "https://www.digiturkburada.com.tr/kanal3/kanal-buyuk/bein-box-office-1-hd-buyuk.png"),
            Channel("BS BOX OFFİCE 2", "http://65.108.239.207/box2/index.m3u8", "7/24 Dizi/Film", "https://www.digiturkburada.com.tr/kanal3/kanal-buyuk/bein-box-office-2-hd-buyuk.png"),
            Channel("BS BOX OFFİCE 3", "http://65.108.239.207/box3/index.m3u8", "7/24 Dizi/Film", "https://www.digiturkburada.com.tr/kanal3/kanal-buyuk/bein-box-office-3-hd-buyuk.png")
        )
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_PLAYLIST_CHARS = 5 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

        // Conservative metadata filter. Adult-labelled entries are never shown.
        private val RESTRICTED_LABEL = Regex(
            """(?i)(18\s*\+|xxx|adult|adults|porn(?:o)?|erotic|sex|playboy|hustler|brazzers|penthouse)"""
        )

        private val ATTRIBUTE = Regex(
            """([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
        )

        private fun attribute(line: String, key: String): String? =
            ATTRIBUTE.findAll(line).firstOrNull {
                it.groupValues[1].equals(key, ignoreCase = true)
            }?.let { it.groupValues[2].ifBlank { it.groupValues[3] } }

        private fun safeHttpsUrl(raw: String): String? {
            val value = raw.trim()
            if (value.isBlank()) return null
            return runCatching {
                val uri = URI(value)
                value.takeIf {
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank()
                }
            }.getOrNull()
        }

        // Playlist entries may use HTTP only on the single host supplied by the user.
        // User-configured playlist source URLs themselves remain HTTPS-only.
        private fun safeStreamUrl(raw: String): String? {
            val value = raw.trim()
            safeHttpsUrl(value)?.let { return it }
            return runCatching {
                val uri = URI(value)
                value.takeIf {
                    uri.scheme.equals("http", ignoreCase = true) &&
                        uri.host == "65.108.239.207" &&
                        uri.port == -1 &&
                        uri.path.endsWith(".m3u8", ignoreCase = true) &&
                        uri.rawFragment.isNullOrBlank() &&
                        uri.rawUserInfo.isNullOrBlank()
                }
            }.getOrNull()
        }

        private fun channelKey(channel: Channel): String =
            "${channel.name.lowercase()}|${channel.streamUrl}"

        private fun responseUrl(channel: Channel): String {
            val raw = listOf(
                channel.name,
                channel.group,
                channel.logo.orEmpty(),
                channel.streamUrl
            ).joinToString("\n")
            val encoded = Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return "kayralive://channel/$encoded"
        }

        private fun channelFromResponseUrl(url: String): Channel? {
            val prefix = "kayralive://channel/"
            if (!url.startsWith(prefix)) return null
            val raw = runCatching {
                String(
                    Base64.decode(
                        url.removePrefix(prefix),
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ),
                    Charsets.UTF_8
                )
            }.getOrNull() ?: return null
            val fields = raw.split("\n", limit = 4)
            if (fields.size != 4) return null
            val streamUrl = safeStreamUrl(fields[3]) ?: return null
            return Channel(fields[0], streamUrl, fields[1], fields[2].ifBlank { null })
        }

        private fun isRestricted(name: String, group: String, tvgName: String): Boolean {
            val value = "$name $group $tvgName"
            return RESTRICTED_LABEL.containsMatchIn(value)
        }
    }

    val configuredPlaylistUrl: String
        get() = preferences.getString(PLAYLIST_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PLAYLIST_URLS.joinToString("\n")

    fun savePlaylistUrl(raw: String): Boolean {
        val urls = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()

        if (urls.isEmpty() || urls.size > MAX_PLAYLIST_SOURCES) return false

        val normalized = urls.mapNotNull { safeHttpsUrl(it) }
        if (normalized.size != urls.size) return false

        preferences.edit()
            .putString(PLAYLIST_KEY, normalized.distinct().joinToString("\n"))
            .apply()
        cachedChannels = emptyList()
        cacheTimestamp = 0L
        return true
    }

    fun clearPlaylistUrl() {
        preferences.edit().remove(PLAYLIST_KEY).apply()
        cachedChannels = emptyList()
        cacheTimestamp = 0L
    }

    override val mainPage = mainPageOf("playlist" to "Yetkili Canlı TV Listesi")

    private suspend fun channels(): List<Channel> {
        val now = System.currentTimeMillis()
        if (cachedChannels.isNotEmpty() && now - cacheTimestamp < CACHE_TTL_MS) {
            return cachedChannels
        }

        val playlistUrls = configuredPlaylistUrl.lineSequence()
            .mapNotNull { safeHttpsUrl(it) }
            .distinct()
            .take(MAX_PLAYLIST_SOURCES)
            .toList()

        if (playlistUrls.isEmpty()) {
            throw ErrorLoadingException(
                "En az bir HTTPS M3U/M3U8 liste adresi eklemelisin."
            )
        }

        val mergedChannels = linkedMapOf<String, Channel>()
        DEFAULT_DIRECT_CHANNELS.forEach { channel ->
            mergedChannels.putIfAbsent(channelKey(channel), channel)
        }
        val sourceErrors = mutableListOf<String>()

        for ((index, playlistUrl) in playlistUrls.withIndex()) {
            val sourceChannels = try {
                val response = app.get(
                    playlistUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*"
                    ),
                    timeout = 20
                )

                if (!response.isSuccessful) {
                    throw ErrorLoadingException("HTTP ${response.code}")
                }

                val body = response.text
                if (body.length > MAX_PLAYLIST_CHARS) {
                    throw ErrorLoadingException("M3U dosyası 5 MB sınırını aşıyor")
                }
                if (!body.trimStart().startsWith("#EXTM3U")) {
                    throw ErrorLoadingException("Yanıt geçerli bir M3U listesi değil")
                }

                parsePlaylist(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceErrors += "Liste ${index + 1}: ${e.message ?: "yüklenemedi"}"
                emptyList()
            }

            sourceChannels.forEach { channel ->
                mergedChannels.putIfAbsent(channelKey(channel), channel)
            }
        }

        val parsed = mergedChannels.values.toList()
        if (parsed.isEmpty()) {
            val detail = sourceErrors.joinToString("; ")
            throw ErrorLoadingException(
                "Hiçbir kanal listesi yüklenemedi. ${detail.ifBlank { "Listelerde geçerli kanal bulunamadı." }}"
            )
        }

        cachedChannels = parsed
        cacheTimestamp = System.currentTimeMillis()
        return parsed
    }

    private fun parsePlaylist(body: String): List<Channel> {
        val result = linkedMapOf<String, Channel>()
        var currentInfo: String? = null
        var currentGroup = ""

        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentInfo = line
                    currentGroup = attribute(line, "group-title").orEmpty().ifBlank { "Diğer" }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    currentGroup = line.substringAfter(':').trim().ifBlank { "Diğer" }
                }

                line.startsWith("#") -> continue

                else -> {
                    val info = currentInfo ?: continue
                    val streamUrl = safeStreamUrl(line)
                    currentInfo = null
                    if (streamUrl == null) continue

                    val comma = info.indexOf(',')
                    val displayName = if (comma >= 0) {
                        info.substring(comma + 1).trim()
                    } else {
                        ""
                    }
                    val tvgName = attribute(info, "tvg-name").orEmpty()
                    val channelName = displayName.ifBlank { tvgName }.ifBlank { "Canlı Kanal" }
                    val group = currentGroup.ifBlank { "Diğer" }

                    if (isRestricted(channelName, group, tvgName)) continue

                    val logo = attribute(info, "tvg-logo")
                        ?.let { safeHttpsUrl(it) }

                    val channel = Channel(channelName, streamUrl, group, logo)
                    result.putIfAbsent(channelKey(channel), channel)
                    currentGroup = ""
                }
            }
        }

        return result.values.sortedWith(
            compareBy<Channel> { it.group.lowercase() }
                .thenBy { it.name.lowercase() }
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)

        val allChannels = channels()
        val lists = allChannels
            .groupBy { it.group }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (group, entries) ->
                HomePageList(
                    group,
                    entries.map { channel ->
                        newLiveSearchResponse(
                            channel.name,
                            responseUrl(channel),
                            TvType.Live
                        ) {
                            this.posterUrl = channel.logo
                        }
                    },
                    false
                )
            }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.length < 2) return emptyList()

        return channels()
            .filter { it.name.contains(term, ignoreCase = true) }
            .map { channel ->
                newLiveSearchResponse(
                    channel.name,
                    responseUrl(channel),
                    TvType.Live
                ) {
                    this.posterUrl = channel.logo
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val channel = channelFromResponseUrl(url)
            ?: channels().firstOrNull { it.streamUrl == url }
            ?: return null
        val payload = Base64.encodeToString(
            channel.streamUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return newLiveStreamLoadResponse(
            channel.name,
            channel.streamUrl,
            payload
        ) {
            this.posterUrl = channel.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decoded = runCatching {
            String(
                Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return false

        val streamUrl = safeStreamUrl(decoded) ?: return false
        val type = when {
            streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                ExtractorLinkType.M3U8
            streamUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) ->
                ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = type
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }
}
