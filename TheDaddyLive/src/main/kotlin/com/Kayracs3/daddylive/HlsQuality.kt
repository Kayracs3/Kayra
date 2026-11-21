package com.Kayracs3.daddylive

import com.lagradost.cloudstream3.app
import java.net.URI

internal data class HlsVariant(
    val url: String,
    val height: Int,
    val bandwidth: Long
) {
    val label: String
        get() = when {
            height > 0 -> height.toString() + "p"
            bandwidth > 0L -> (bandwidth / 1000L).toString() + " kbps"
            else -> "Auto"
        }
}

/**
 * Reads an HLS master playlist and extracts its advertised variants.
 * No quality is fabricated: if the URL is a media playlist or the master
 * cannot be read, the caller receives an empty list and can keep the
 * original playlist URL.
 */
internal object HlsQuality {
    private val bandwidthRegex = Regex("""(?:^|,)(?:AVERAGE-)?BANDWIDTH=([0-9]+)""")
    private val resolutionRegex = Regex("""RESOLUTION=([0-9]+)x([0-9]+)""")
    private val namedQualityRegex = Regex(
        """(?i)\b(2160|1440|1080|720|576|480|360)p?\b"""
    )

    suspend fun variants(
        playlistUrl: String,
        headers: Map<String, String>
    ): List<HlsVariant> = runCatching {
        val response = app.get(playlistUrl, headers = headers, timeout = 15)
        if (response.code !in 200..299) {
            emptyList()
        } else {
            parse(playlistUrl, response.text)
        }
    }.getOrDefault(emptyList())

    internal fun parse(playlistUrl: String, playlist: String): List<HlsVariant> {
        val lines = playlist.replace("\r", "").lines()
        val found = mutableListOf<HlsVariant>()

        for (index in lines.indices) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue

            val attributes = line.substringAfter(':')
            val bandwidth = bandwidthRegex.find(attributes)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

            val resolution = resolutionRegex.find(attributes)
            val heightFromResolution = resolution
                ?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

            val variantLine = lines.drop(index + 1)
                .firstOrNull { candidate ->
                    candidate.isNotBlank() && !candidate.trim().startsWith("#")
                }?.trim() ?: continue

            val absolute = runCatching {
                URI(playlistUrl).resolve(variantLine).toString()
            }.getOrNull() ?: continue

            val height = if (heightFromResolution > 0) {
                heightFromResolution
            } else {
                qualityFromText(attributes + " " + variantLine)
            }

            found.add(
                HlsVariant(
                    url = absolute,
                    height = height,
                    bandwidth = bandwidth
                )
            )
        }

        return found
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<HlsVariant> { it.height }
                    .thenByDescending { it.bandwidth }
            )
    }

    private fun qualityFromText(value: String): Int {
        val normalized = value.lowercase()
        val tagged = namedQualityRegex.find(normalized)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (tagged != null) return tagged

        return when {
            normalized.contains("4k") || normalized.contains("uhd") -> 2160
            normalized.contains("full hd") || normalized.contains("fhd") -> 1080
            normalized.contains("hd") -> 720
            else -> 0
        }
    }
}
