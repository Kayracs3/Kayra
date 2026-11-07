package com.Kayracs3

import android.util.Log
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
    override var name = "DaddyLive v2"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val dembedOrigin = "https://dembed.st"
    private val edgeHost = "https://edge.wedd48c.top"
    private val TAG = "DADDYLIVE_FIX"

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

        Log.e(TAG, "loadLinks id=$id")

        val tokenData = fetchToken(id) ?: run {
            Log.e(TAG, "FAIL: no token")
            return false
        }
        val token = tokenData.first
        val aesKeyB64 = tokenData.second

        if (aesKeyB64 == null) {
            Log.e(TAG, "FAIL: no AES key")
            return false
        }

        val m3u8Url = "$edgeHost/premium$id/index.m3u8?t=$token&_=${System.currentTimeMillis()}"
        Log.e(TAG, "m3u8 URL: $m3u8Url")

        try {
            val encryptedM3u8 = app.get(
                m3u8Url,
                headers = mapOf(
                    "Referer" to "$dembedOrigin/",
                    "Origin" to dembedOrigin,
                    "User-Agent" to ua
                )
            ).text
            Log.e(TAG, "Downloaded m3u8 length=${encryptedM3u8.length}")

            val baseUrl = m3u8Url.substringBefore("?")
            val decryptedM3u8 = decryptM3u8(encryptedM3u8, aesKeyB64, baseUrl)
            if (decryptedM3u8 == null) {
                Log.e(TAG, "FAIL: decrypt")
                return false
            }
            Log.e(TAG, "Decrypted length=${decryptedM3u8.length}, first 100: ${decryptedM3u8.take(100)}")

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
            Log.e(TAG, "SUCCESS - callback called")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION: ${e.message}", e)
            return false
        }
    }

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
        } catch (e: Exception) {
            Log.e(TAG, "fetchToken exception: ${e.message}")
            null
        }
    }

    /**
     * AES-GCM decrypt. Tag length ve IV byte order için fallback'li.
     */
    private fun decryptM3u8(encrypted: String, aesKeyB64: String, baseUrl: String): String? {
        return try {
            val keyBytes = base64UrlDecode(aesKeyB64)
            if (keyBytes.size != 16 && keyBytes.size != 32) {
                Log.e(TAG, "Invalid key size: ${keyBytes.size}")
                return null
            }
            val keySpec = SecretKeySpec(keyBytes, "AES")

            val output = StringBuilder()
            var mediaSequence = 0L
            var decryptedCount = 0

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

                val cipherBytes = base64UrlDecode(trimmed)
                Log.e(TAG, "Segment #$mediaSequence: ${cipherBytes.size} bytes")

                val iv = createIv(mediaSequence)
                val plaintext = tryDecrypt(cipherBytes, keySpec, iv)

                if (plaintext == null) {
                    Log.e(TAG, "FAIL: segment #$mediaSequence could not be decrypted")
                    return null
                }

                val plaintextStr = String(plaintext).trim()
                val finalUrl = if (plaintextStr.startsWith("http")) plaintextStr
                    else baseUrl.substringBeforeLast("/") + "/" + plaintextStr

                output.append(finalUrl).append("\n")
                mediaSequence++
                decryptedCount++
            }
            Log.e(TAG, "Decrypted $decryptedCount segments OK")
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt FATAL: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * AES-GCM decrypt denemesi. Sırayla dener:
     * 1. 128-bit tag (Web Crypto varsayılanı) — EN OLASI
     * 2. 64-bit tag (JS kodu)
     * 3. 128-bit tag + Big Endian IV
     * 4. 64-bit tag + Big Endian IV
     */
    private fun tryDecrypt(cipherBytes: ByteArray, keySpec: SecretKeySpec, iv: ByteArray): ByteArray? {
        val tagBitsOptions = listOf(128, 64, 120, 112, 104, 96)
        
        for (tagBits in tagBitsOptions) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(tagBits, iv))
                val plaintext = cipher.doFinal(cipherBytes)
                Log.e(TAG, "Decrypt OK with tagBits=$tagBits")
                return plaintext
            } catch (_: Exception) {
                // sıradakini dene
            }
        }
        
        // Son çare: son 8 byte'ı tag olarak ayır, kalanı ciphertext
        try {
            val tagLen = 8
            if (cipherBytes.size > tagLen) {
                val ciphertext = cipherBytes.copyOfRange(0, cipherBytes.size - tagLen)
                val tag = cipherBytes.copyOfRange(cipherBytes.size - tagLen, cipherBytes.size)
                val combined = ciphertext + tag
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(64, iv))
                return cipher.doFinal(combined)
            }
        } catch (_: Exception) { }

        return null
    }

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
