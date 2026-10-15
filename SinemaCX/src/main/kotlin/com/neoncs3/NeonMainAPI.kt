package com.neoncs3

import android.util.Base64
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Local compatibility layer for SinemaCX.
 *
 * The uploaded provider relied on NeonCore helpers that are not part of this
 * repository. Keep the provider self-contained instead of depending on code
 * from another plugin module (Gradle modules do not share Kotlin sources).
 */
abstract class NeonMainAPI : MainAPI() {
    protected val neonCacheTtlMs: Long = 90_000L

    private data class CachedDocument(
        val fetchedAt: Long,
        val document: Document
    )

    private val documentCache = ConcurrentHashMap<String, CachedDocument>()

    protected suspend fun neonCachedDocument(
        url: String,
        referer: String,
        ttlMs: Long = neonCacheTtlMs
    ): Document? {
        val cacheKey = "$url|$referer"
        val now = System.currentTimeMillis()
        val cached = documentCache[cacheKey]
        if (cached != null && now - cached.fetchedAt < ttlMs.coerceAtLeast(0L)) {
            return cached.document
        }

        return runCatching {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to NEON_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                referer = referer,
                allowRedirects = true
            )
            if (!response.isSuccessful) {
                documentCache.remove(cacheKey)
                null
            } else {
                response.document.also {
                    documentCache[cacheKey] = CachedDocument(
                        fetchedAt = System.currentTimeMillis(),
                        document = it
                    )
                }
            }
        }.getOrNull()
    }

    protected fun fixUrlNull(raw: String?, baseUrl: String = mainUrl): String? {
        val value = raw
            ?.trim()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (
            value == "#" ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("mailto:", true)
        ) return null

        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(baseUrl).resolve(value).toString()
            }
        }.getOrNull()?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
    }

    protected fun neonExtractPlayerCandidates(
        document: Document,
        baseUrl: String
    ): List<String> {
        val candidates = linkedSetOf<String>()

        fun add(raw: String?, force: Boolean = false) {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return

            val decoded = if (
                !value.startsWith("http://", true) &&
                !value.startsWith("https://", true) &&
                !value.startsWith("//")
            ) {
                runCatching {
                    String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
                }.getOrNull()?.takeIf {
                    it.startsWith("http://", true) ||
                    it.startsWith("https://", true) ||
                    it.startsWith("//")
                } ?: value
            } else value

            val url = fixUrlNull(decoded, baseUrl) ?: return
            val lower = url.lowercase()
            val playerLike = listOf(
                "player", "embed", "/video/", "/stream", ".m3u8", ".mp4",
                "videasy", "playback", "source"
            ).any { lower.contains(it) }

            if (
                (force || playerLike) &&
                !lower.contains("youtube.com") &&
                !lower.contains("youtu.be") &&
                !lower.contains("trailer") &&
                !lower.contains("fragman") &&
                !Regex("""\.(?:jpe?g|png|webp|gif|svg)(?:[?#]|$)""").containsMatchIn(lower)
            ) {
                candidates.add(url)
            }
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-vsrc], iframe[data-url], " +
                "video[src], video[data-src], source[src], source[data-src]"
        ).forEach { element ->
            listOf("data-vsrc", "data-src", "data-url", "src").forEach { attribute ->
                if (element.hasAttr(attribute)) add(element.attr(attribute), force = true)
            }
        }

        val html = document.html().replace("\\/", "/").replace("&amp;", "&")
        Regex("""(?i)(?:https?:)?//[^"'<>\\s]+""")
            .findAll(html)
            .forEach { match -> add(match.value) }

        return candidates.toList()
    }

    protected suspend fun neonResolveLinkCandidates(
        candidates: List<String>,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val visited = linkedSetOf<String>()

        suspend fun resolve(url: String, referer: String) {
            if (!visited.add(url)) return

            val lower = url.lowercase()
            val directType = when {
                Regex("""\.m3u8(?:[?#]|$)""").containsMatchIn(lower) -> ExtractorLinkType.M3U8
                Regex("""\.mp4(?:[?#]|$)""").containsMatchIn(lower) -> ExtractorLinkType.VIDEO
                else -> null
            }

            if (directType != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = sourceName,
                        url = url,
                        type = directType
                    ) {
                        this.referer = referer
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to NEON_UA
                        )
                    }
                )
                foundAny = true
                return
            }

            val page = neonCachedDocument(url, referer, neonCacheTtlMs)
            if (page != null) {
                for (playerUrl in neonExtractPlayerCandidates(page, url)) {
                    val playerLower = playerUrl.lowercase()
                    if (
                        Regex("""\.m3u8(?:[?#]|$)""").containsMatchIn(playerLower) ||
                        Regex("""\.mp4(?:[?#]|$)""").containsMatchIn(playerLower)
                    ) {
                        resolve(playerUrl, url)
                    } else {
                        runCatching {
                            loadExtractor(playerUrl, url, subtitleCallback) { link ->
                                callback(link)
                                foundAny = true
                            }
                        }
                    }
                }
            }

            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    callback(link)
                    foundAny = true
                }
            }
        }

        for (candidate in candidates) {
            val url = fixUrlNull(candidate) ?: continue
            runCatching { resolve(url, mainUrl) }
        }

        return foundAny
    }

    private companion object {
        const val NEON_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
    }
}
