package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

private const val WDT_EXT_TAG = "WDT2_EXT"
private const val WDT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class WebDramaTurkeyExtractor : ExtractorApi() {
    override val name = "WebDramaTurkey"
    override val mainUrl = "https://dtpasn.asia"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(WDT_EXT_TAG, "Extractor URL: $url")

        runCatching {
            val pageReferer = referer ?: "https://webdramaturkey2.com/"

            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to WDT_USER_AGENT,
                    "Referer" to pageReferer,
                ),
                referer = pageReferer,
                allowRedirects = true,
            )

            if (!response.isSuccessful) return@runCatching

            val html = response.text
            val cookie = Regex(
                """cookie\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val apiResponse = app.post(
                "$mainUrl/api/source",
                headers = buildMap {
                    put("User-Agent", WDT_USER_AGENT)
                    put("Referer", url)
                    put("Content-Type", "application/x-www-form-urlencoded")
                    if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                    put("X-Requested-With", "XMLHttpRequest")
                },
                referer = url,
                data = mapOf(
                    "r" to pageReferer,
                    "d" to mainUrl,
                ),
            )

            if (!apiResponse.isSuccessful) return@runCatching

            val apiText = apiResponse.text
            Log.d(WDT_EXT_TAG, "api/source HTTP=" + apiResponse.code)

            Regex(
                """["'](?:subtitle|subtitles|captions?)["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(apiText).forEach { match ->
                val sub = normalizeAbsolute(match.groupValues[1], url)
                if (isSubtitleUrl(sub)) {
                    runCatching {
                        subtitleCallback(SubtitleFile("Türkçe", sub))
                    }
                }
            }

            val finalUrl = Regex(
                """["']?(?:securedLink|videoSource)["']?\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(apiText)?.groupValues?.getOrNull(1)

            if (!finalUrl.isNullOrBlank()) {
                emit(finalUrl, callback)
                return@runCatching
            }

            Regex(
                """(?:file|src|url)\s*[:=]\s*["']([^"']+(?:\.m3u8|\.mp4|\.mpd)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(apiText).forEach { match ->
                emit(match.groupValues[1], callback)
            }
        }.onFailure {
            Log.e(WDT_EXT_TAG, "Extractor hatası: " + it.message)
        }
    }

    private suspend fun emit(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val streamUrl = normalizeAbsolute(rawUrl, mainUrl)
        if (!streamUrl.startsWith("http", true)) return

        val type = when {
            streamUrl.contains(".m3u8", true) -> INFER_TYPE
            streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = type,
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to WDT_USER_AGENT,
                    "Referer" to mainUrl,
                )
                referer = mainUrl
            }
        )
    }

    private fun normalizeAbsolute(raw: String, base: String): String {
        val value = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }

        if (value.startsWith("//")) return "https:$value"

        if (value.startsWith("/")) {
            val origin = if (base.startsWith("http", true)) {
                base.substringBefore("://") + "://" +
                    base.substringAfter("://").substringBefore("/")
            } else {
                mainUrl
            }
            return origin.trimEnd('/') + value
        }

        return mainUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun isSubtitleUrl(url: String): Boolean {
        return url.startsWith("http", true) &&
            (url.contains(".vtt", true) || url.contains(".srt", true))
    }
}


private const val WDT_VK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class WebDramaTurkeyVkExtractor : ExtractorApi() {
    override val name = "WebDramaTurkey VK"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to WDT_VK_UA,
                "Referer" to (referer ?: mainUrl),
            )

            val html = app.get(url, headers = headers, referer = referer ?: mainUrl).text

            val directRegex = Regex(
                """https?://[^"\\s<>]+\\.(?:m3u8|mp4|mpd)(?:\\?[^"\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

            directRegex.findAll(html)
                .map { it.value.replace("\\\\/", "/") }
                .distinct()
                .forEach { stream ->
                    val type = when {
                        stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                        stream.contains(".m3u8", true) -> INFER_TYPE
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = stream,
                            type = type,
                        ) {
                            quality = Qualities.Unknown.value
                            this.referer = "https://vkvideo.ru/"
                        }
                    )
                }

            val jsonRegex = Regex(
                """["'](?:url|src)["']\\s*:\\s*["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            jsonRegex.findAll(html)
                .map { it.groupValues[1].replace("\\\\/", "/") }
                .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".mpd", true) }
                .distinct()
                .forEach { stream ->
                    val type = when {
                        stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                        stream.contains(".m3u8", true) -> INFER_TYPE
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = stream,
                            type = type,
                        ) {
                            quality = Qualities.Unknown.value
                            this.referer = "https://vkvideo.ru/"
                        }
                    )
                }
        }.onFailure {
            Log.e("WDT2_VK", "VK extractor: " + it.message)
        }
    }
}

class WebDramaTurkeyAbstreamExtractor : ExtractorApi() {
    override val name = "WebDramaTurkey Abstream"
    override val mainUrl = "https://abstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to WDT_VK_UA,
                    "Referer" to (referer ?: mainUrl),
                ),
                referer = referer ?: mainUrl,
            )

            val html = response.text
            Regex(
                """["'](https?://[^"']+\\.m3u8(?:\\?[^"']*)?)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
                .map { it.groupValues[1].replace("\\\\/", "/") }
                .distinct()
                .forEach { stream ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = stream,
                            type = INFER_TYPE,
                        ) {
                            quality = Qualities.Unknown.value
                            this.referer = mainUrl
                        }
                    )
                }
        }.onFailure {
            Log.e("WDT2_AB", "Abstream extractor: " + it.message)
        }
    }
}
