package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val dembedOrigin = "https://dembed.st"
    private val edgeHost = "https://edge.wedd48c.top"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/24-7-channels.php").document
        val channels = document.select("a[href*='/watch.php?id=']").mapNotNull { it.toLiveSearchResponse() }
        return newHomePageResponse(request.name, channels)
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse? {
        val title = text().trim(); val href = attr("href")
        if (title.isBlank() || href.isBlank()) return null
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        return newLiveSearchResponse(title, fullUrl, TvType.Live)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document
        return newLiveStreamLoadResponse(url = url, name = doc.title(), dataUrl = url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        val id = watchUrl.substringAfter("id=", "").substringBefore("&")
        if (id.isBlank()) return false

        // 1. Token + AES key al
        val tokenData = fetchToken(id) ?: return false
        val token = tokenData.first
        val aesKeyB64 = tokenData.second ?: return false

        // 2. m3u8 URL oluştur
        val m3u8Url = "$edgeHost/premium$id/index.m3u8?t=$token&_=${System.currentTimeMillis()}"

        try {
            // 3. Şifreli m3u8'i indir
            val encryptedM3u8 = app.get(
                m3u8Url,
                headers = mapOf(
                    "Referer" to "$dembedOrigin/",
                    "Origin" to dembedOrigin,
                    "User-Agent" to ua
                )
            ).text

            // 4. AES-GCM decrypt
            val baseUrl = m3u8Url.substringBefore("?")
            val decryptedM3u8 = decryptM3u8(encryptedM3u8, aesKeyB64, baseUrl)
            if (decryptedM3u8 == null) return false

            // 5. Decrypted m3u8'i data: URL olarak ver
            val base64Data = Base64.encodeToString(decryptedM3u8.toByteArray(), Base64.NO_WRAP)
            val dataUrl = "data:application/vnd.apple.mpegurl;base64,$base64Data"

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - decrypted",
                    url = dataUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$dembedOrigin/"
                    this.headers = mapOf(
                        "Referer" to "$dembedOrigin/",
                        "Origin" to dembedOrigin,
                        "User-Agent" to ua
                    )
                }
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** Token + AES key fetch */
    private suspend fun fetchToken(channelId: String): Pair<String, String?>? {
        val url = "$edgeHost/api/playlist-token?slug=premium$channelId"
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to "$dembedOrigin/",
                    "Origin" to dembedOrigin,
                    "User-Agent" to ua
                )
            ).text
            val json = JSONObject(response)
            val token = json.optString("token", "")
            val key = json.optString("k", "").ifBlank { null }
            if (token.isNotBlank()) Pair(token, key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * AES-GCM decrypt. Her segment satırı ayrı decrypt edilir.
     * IV = [8 byte 0][mediaSeq üst 32][mediaSeq alt 32] big endian
     * Tag length: 64 bit
     */
    private fun decryptM3u8(encrypted: String, aesKeyB64: String, baseUrl: String): String? {
        return try {
            val keyBytes = base64UrlDecode(aesKeyB64)
            if (keyBytes.size != 16 && keyBytes.size != 32) return null
            val keySpec = SecretKeySpec(keyBytes, "AES")

            val output = StringBuilder()
            var mediaSequence = 0L

            for (line in encrypted.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) { output.append("\n"); continue }
                if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                        mediaSequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                    output.append(trimmed).append("\n")
                    continue
                }
                // Şifreli segment satırı
                val cipherBytes = base64UrlDecode(trimmed)
                val iv = createIv(mediaSequence)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(64, iv))
                val plaintext = cipher.doFinal(cipherBytes)
                val plaintextStr = String(plaintext).trim()

                // Relative URL'yi absolute yap
                val finalUrl = if (plaintextStr.startsWith("http")) plaintextStr
                    else baseUrl.substringBeforeLast("/") + "/" + plaintextStr

                output.append(finalUrl).append("\n")
                mediaSequence++
            }
            output.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** IV: [8 byte 0][mediaSeq üst 32][mediaSeq alt 32] big endian */
    private fun createIv(mediaSequence: Long): ByteArray {
        val iv = ByteArray(16)
        val buf = ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(0L)
        buf.putInt((mediaSequence ushr 32).toInt())
        buf.putInt(mediaSequence.toInt())
        return iv
    }

    private fun base64UrlDecode(input: String): ByteArray {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return Base64.decode(s, Base64.DEFAULT)
    }
}
