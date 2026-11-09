package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

open class ContentX : ExtractorApi() {

    override val name = "ContentX"
    override val mainUrl = "https://contentx.me"
    override val requiresReferer = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/json,text/plain,*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private data class MediaCandidate(
        val url: String,
        val label: String,
        val type: ExtractorLinkType
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframeUrl = if (url.startsWith("//")) "https:$url" else url
        val base = originOf(iframeUrl) ?: mainUrl
        val extRef = referer?.takeIf { it.isNotBlank() } ?: "$base/"

        Log.d("Kekik_${name}", "url » $iframeUrl")

        val iframeResponse = app.get(
            iframeUrl,
            headers = browserHeaders,
            referer = extRef
        )
        val iframeHtml = normalizeEscapes(iframeResponse.text)

        Log.d(
            "Kekik_${name}",
            "iframe response: HTTP ${iframeResponse.code}; chars=${iframeHtml.length}; " +
                "openPlayer=${containsOpenPlayer(iframeHtml)}; NP_CONFIG=${iframeHtml.contains("NP_CONFIG")}"
        )

        val emittedSubtitles = HashSet<String>()
        emitSubtitleTracks(iframeHtml, iframeUrl, emittedSubtitles, subtitleCallback)

        // Newer player builds expose their sources directly through NP_CONFIG.
        val config = jsonObjectAfterKey(iframeHtml, "NP_CONFIG")
        if (config != null) {
            val configSubtitleRows = config.optJSONArray("subtitles")
            if (configSubtitleRows != null) {
                for (index in 0 until configSubtitleRows.length()) {
                    val item = configSubtitleRows.optJSONObject(index) ?: continue
                    val raw = item.optString("url").ifBlank { item.optString("file") }
                    val label = item.optString("label")
                        .ifBlank { item.optString("title") }
                        .ifBlank { "Altyazı" }
                    emitSubtitle(raw, label, iframeUrl, emittedSubtitles, subtitleCallback)
                }
            }

            val configMedia = LinkedHashMap<String, MediaCandidate>()
            collectMedia(config, base, "Orijinal", configMedia)
            configMedia.values.forEach { emitMedia(it, iframeUrl, base, callback) }
            if (configMedia.isNotEmpty()) {
                Log.d("Kekik_${name}", "NP_CONFIG media count=${configMedia.size}")
                return
            }
        }

        // Also support the older direct-file player when openPlayer is absent.
        val directMedia = LinkedHashMap<String, MediaCandidate>()
        collectMediaFromText(iframeHtml, base, directMedia)
        if (directMedia.isNotEmpty()) {
            directMedia.values.forEach { emitMedia(it, iframeUrl, base, callback) }
            Log.d("Kekik_${name}", "Direct iframe media count=${directMedia.size}")
            return
        }

        // openPlayer may use single or double quotes and may contain spaces around tokens.
        val tokens = LinkedHashMap<String, String>()
        Regex("""(?is)(?:window\\s*\\.\\s*)?openPlayer\\s*\\(\\s*['"]([^'"]+)['"]""")
            .findAll(iframeHtml)
            .forEach { match ->
                val token = match.groupValues[1].trim()
                if (token.isNotBlank()) tokens.putIfAbsent(token, "Orijinal / Altyazılı")
            }

        // Optional Turkish dub token used by older ContentX player pages.
        Regex("""(?is),\\s*['"]([^'"]{8,})['"]\\s*,\\s*['"]Türkçe(?:\\s+Dublaj)?['"]""")
            .findAll(iframeHtml)
            .forEach { match ->
                val token = match.groupValues[1].trim()
                if (token.isNotBlank()) tokens[token] = "Türkçe Dublaj"
            }

        if (tokens.isEmpty()) {
            Log.w(
                "Kekik_${name}",
                "Player token/media bulunamadı: HTTP ${iframeResponse.code}; " +
                    "chars=${iframeHtml.length}; challenge=${looksLikeAccessChallenge(iframeHtml)}"
            )
            throw ErrorLoadingException(
                "ContentX oynatıcı kaynağı bulunamadı (HTTP ${iframeResponse.code})"
            )
        }

        val emittedMedia = HashSet<String>()
        var found = false
        for ((token, tokenLabel) in tokens) {
            val sourceUrl = "$base/source2.php?v=${URLEncoder.encode(token, "UTF-8")}"
            val sourceResponse = runCatching {
                app.get(
                    sourceUrl,
                    headers = browserHeaders + mapOf(
                        "Accept" to "application/json,text/plain,*/*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to base
                    ),
                    referer = iframeUrl
                )
            }.onFailure {
                Log.w("Kekik_${name}", "source2 request failed: ${it.javaClass.simpleName}")
            }.getOrNull() ?: continue

            val sourceText = normalizeEscapes(sourceResponse.text)
            val media = LinkedHashMap<String, MediaCandidate>()
            collectMediaFromText(sourceText, base, media)

            media.values.forEach { candidate ->
                if (emittedMedia.add(candidate.url)) {
                    val labelled = candidate.copy(
                        label = if (tokenLabel == "Orijinal / Altyazılı") {
                            candidate.label
                        } else tokenLabel
                    )
                    emitMedia(labelled, iframeUrl, base, callback)
                    found = true
                }
            }

            Log.d(
                "Kekik_${name}",
                "source2 response: HTTP ${sourceResponse.code}; chars=${sourceText.length}; " +
                    "media=${media.size}; track=${tokenLabel}"
            )
        }

        if (!found) {
            throw ErrorLoadingException("ContentX source2 yanıtında oynatılabilir medya bulunamadı")
        }
    }

    private fun containsOpenPlayer(source: String): Boolean =
        Regex("""(?is)(?:window\\s*\\.\\s*)?openPlayer\\s*\\(""").containsMatchIn(source)

    private fun normalizeEscapes(raw: String): String =
        raw.replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\\"", "\"")
            .replace("\\'", "'")

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.authority.isNullOrBlank()) null
        else "${uri.scheme}://${uri.authority}"
    }.getOrNull()

    private fun resolveUrl(raw: String, base: String): String? {
        val value = normalizeEscapes(raw.trim()).trim('"', '\'')
        if (value.isBlank() ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("blob:", true)
        ) return null

        return runCatching {
            val uri = URI(value)
            val resolved = if (uri.isAbsolute) uri else URI(base).resolve(uri)
            resolved.toASCIIString()
        }.getOrNull()?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
    }

    private fun mediaType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.substringBefore('?').lowercase())
        return when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || path.endsWith(".mkv") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private fun isSubtitleUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.substringBefore('?').lowercase())
        return listOf(".vtt", ".srt", ".ass", ".ssa", ".ttml", ".dfxp")
            .any { path.endsWith(it) } ||
            path.contains("/subtitle") || path.contains("/caption")
    }

    private fun jsonObjectAfterKey(source: String, key: String): JSONObject? {
        val keyIndex = source.indexOf(key)
        if (keyIndex < 0) return null
        val start = source.indexOf('{', keyIndex + key.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return runCatching {
                                JSONObject(source.substring(start, index + 1))
                            }.getOrNull()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun collectMedia(
        value: Any?,
        base: String,
        inheritedLabel: String,
        output: MutableMap<String, MediaCandidate>,
        depth: Int = 0
    ) {
        if (value == null || depth > 12) return
        when (value) {
            is JSONObject -> {
                val ownLabel = value.optString("title")
                    .takeIf { it.isNotBlank() && !it.equals("null", true) }
                    ?: inheritedLabel

                val mediaKeys = listOf("master", "file", "url", "src", "hls", "m3u8")
                for (key in mediaKeys) {
                    val raw = value.opt(key) as? String ?: continue
                    val resolved = resolveUrl(raw, base) ?: continue
                    if (isSubtitleUrl(resolved)) continue
                    val type = mediaType(resolved)
                        ?: if (key == "master" || key == "hls" || key == "m3u8") {
                            ExtractorLinkType.M3U8
                        } else continue
                    output.putIfAbsent(
                        resolved,
                        MediaCandidate(resolved, ownLabel, type)
                    )
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val child = value.opt(keys.next()) ?: continue
                    if (child is JSONObject || child is JSONArray) {
                        collectMedia(child, base, ownLabel, output, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectMedia(value.opt(index), base, inheritedLabel, output, depth + 1)
                }
            }
        }
    }

    private fun collectMediaFromText(
        source: String,
        base: String,
        output: MutableMap<String, MediaCandidate>
    ) {
        val json = runCatching { JSONObject(source) }.getOrNull()
        if (json != null) collectMedia(json, base, "Orijinal", output)

        val patterns = listOf(
            Regex("""(?is)["'](?:file|master|hls|m3u8|src|url)["']\\s*:\\s*["']([^"']+)["']"""),
            Regex("""(?is)\\bfile\\s*:\\s*["']([^"']+)["']""")
        )
        for (pattern in patterns) {
            pattern.findAll(source).forEach { match ->
                val raw = match.groupValues[1]
                val resolved = resolveUrl(raw, base) ?: return@forEach
                if (isSubtitleUrl(resolved)) return@forEach
                val type = mediaType(resolved) ?: return@forEach
                output.putIfAbsent(
                    resolved,
                    MediaCandidate(resolved, "Orijinal", type)
                )
            }
        }
    }

    private fun emitSubtitleTracks(
        source: String,
        base: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val pattern = Regex(
            """(?is)["']file["']\\s*:\\s*["']([^"']+)["']\\s*,\\s*["']label["']\\s*:\\s*["']([^"']+)["']"""
        )
        pattern.findAll(source).forEach { match ->
            emitSubtitle(
                match.groupValues[1],
                decodeLabel(match.groupValues[2]),
                base,
                seen,
                callback
            )
        }

        val document = org.jsoup.Jsoup.parse(source, base)
        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val label = track.attr("label")
                .ifBlank { track.attr("srclang") }
                .ifBlank { "Altyazı" }
            emitSubtitle(raw, label, base, seen, callback)
        }
    }

    private fun emitSubtitle(
        raw: String,
        label: String,
        base: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val resolved = resolveUrl(raw, base) ?: return
        if (!isSubtitleUrl(resolved) || !seen.add(resolved)) return
        callback(
            newSubtitleFile(
                lang = label.ifBlank { "Altyazı" },
                url = resolved
            )
        )
    }

    private fun decodeLabel(value: String): String =
        Regex("""\\\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\", "")

    private fun emitMedia(
        candidate: MediaCandidate,
        iframeUrl: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = candidate.label.takeIf { it.isNotBlank() && it != "Orijinal" }
            ?.let { "${name} $it" }
            ?: name
        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = candidate.url,
                type = candidate.type
            ) {
                this.referer = iframeUrl
                this.headers = browserHeaders + mapOf(
                    "Referer" to iframeUrl,
                    "Origin" to base
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun looksLikeAccessChallenge(source: String): Boolean {
        val lower = source.lowercase()
        return lower.contains("cf-chl-") ||
            lower.contains("just a moment") ||
            lower.contains("attention required") ||
            lower.contains("verify you are human")
    }
}
