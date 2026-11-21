package com.Kayracs3

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

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
        private const val BUILTIN_SINEMA2_URL = "http://65.108.239.207/sinema2/index.m3u8"
        private val DEFAULT_DIRECT_CHANNELS = listOf(
            Channel("Sinema 2", BUILTIN_SINEMA2_URL, "Sinema", null)
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

        // HTTP is permitted only for this explicitly configured built-in stream.
        // User-supplied playlist sources remain HTTPS-only.
        private fun safeStreamUrl(raw: String): String? {
            val value = raw.trim()
            safeHttpsUrl(value)?.let { return it }
            return runCatching {
                val uri = URI(value)
                value.takeIf {
                    uri.scheme.equals("http", ignoreCase = true) &&
                        uri.host == "65.108.239.207" &&
                        uri.port == -1 &&
                        uri.path == "/sinema2/index.m3u8" &&
                        uri.rawQuery.isNullOrBlank() &&
                        uri.rawFragment.isNullOrBlank() &&
                        uri.rawUserInfo.isNullOrBlank()
                }
            }.getOrNull()
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
            mergedChannels.putIfAbsent(channel.streamUrl, channel)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceErrors += "Liste ${index + 1}: ${e.message ?: "yüklenemedi"}"
                emptyList()
            }

            sourceChannels.forEach { channel ->
                mergedChannels.putIfAbsent(channel.streamUrl, channel)
            }
        }

        val parsed = mergedChannels.values.toList()
        if (parsed.isEmpty()) {
            val detail = sourceErrors.joinToString("; ")
            throw ErrorLoadingException(
                "Hiçbir kanal listesi yüklenemedi. ${detail.ifBlank { "Listelerde geçerli HTTPS kanal bulunamadı." }}"
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

                    result.putIfAbsent(
                        streamUrl,
                        Channel(channelName, streamUrl, group, logo)
                    )
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
                            channel.streamUrl,
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
                    channel.streamUrl,
                    TvType.Live
                ) {
                    this.posterUrl = channel.logo
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val channel = channels().firstOrNull { it.streamUrl == url } ?: return null
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
