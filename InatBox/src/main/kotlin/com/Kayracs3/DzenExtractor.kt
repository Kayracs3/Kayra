package com.Kayracs3

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Dzen : ExtractorApi() {
    override val name = "Dzen"
    override val mainUrl = "https://dzen.ru/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        try {
            if (isDirectMedia(url)) {
                emitMedia(url, url, callback)
                return
            }

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            var page = app.get(url, headers = headers).text
            var normalized = normalize(page)

            val firstDirect = findMediaUrl(normalized)
            if (!firstDirect.isNullOrBlank()) {
                emitMedia(firstDirect, url, callback)
                return
            }

            val twitterStream = findTwitterPlayerStream(normalized)
            if (!twitterStream.isNullOrBlank() && twitterStream != url) {
                page = runCatching {
                    app.get(twitterStream, headers = headers, referer = url).text
                }.getOrNull().orEmpty()

                normalized = normalize(page)

                val secondDirect = findMediaUrl(normalized)
                if (!secondDirect.isNullOrBlank()) {
                    emitMedia(secondDirect, twitterStream, callback)
                    return
                }
            }

            val optionUrl = findOptionUrl(normalized)
            if (!optionUrl.isNullOrBlank()) {
                if (isDirectMedia(optionUrl)) {
                    emitMedia(optionUrl, url, callback)
                    return
                }

                val optionPage = runCatching {
                    app.get(optionUrl, headers = headers, referer = url).text
                }.getOrNull().orEmpty()

                val optionDirect = findMediaUrl(normalize(optionPage))
                if (!optionDirect.isNullOrBlank()) {
                    emitMedia(optionDirect, optionUrl, callback)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun normalize(input: String): String {
        return input
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u003F", "?")
            .replace("\\u003D", "=")
    }

    private fun isDirectMedia(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true)
    }

    private fun findMediaUrl(text: String): String? {
        val regex = Regex(
            "https?://[^\\\"'<>\\s]+\\.(?:m3u8|mpd)(?:\\?[^\\\"'<>\\s]*)?",
            RegexOption.IGNORE_CASE
        )

        return regex.findAll(text)
            .map { it.value.trimEnd('\\', '"', '\'', ')', ']', '}') }
            .firstOrNull()
    }

    private fun findTwitterPlayerStream(text: String): String? {
        val regexes = listOf(
            Regex(
                "<meta[^>]+property=[\\\"']twitter:player:stream[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+property=[\\\"']twitter:player:stream[\\\"']",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in regexes) {
            val value = regex.find(text)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) {
                return value
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
            }
        }

        return null
    }

    private fun findOptionUrl(text: String): String? {
        val regexes = listOf(
            Regex(
                "\\\"options\\\"\\s*:\\s*\\[\\s*\\],\\s*\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in regexes) {
            val value = regex.find(text)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) {
                return value
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("\\u003A", ":")
                    .replace("\\u0026", "&")
            }
        }

        return null
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        sourcePage: String,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        val type = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            else -> return
        }

        val quality = Regex("(?i)(2160|1440|1080|720|480|360|240)p?")
            .find(mediaUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = mediaUrl,
                type = type
            ) {
                this.referer = if (sourcePage.startsWith("http")) sourcePage else ""
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0 Safari/537.36"
                )
            }
        )
    }
}
