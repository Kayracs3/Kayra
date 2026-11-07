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
        Log.e(TAG, "========== loadLinks START ==========")
        Log.e(TAG, "data = $data")
        
        val watchUrl = data
        val id = watchUrl.substringAfter("id=", "").substringBefore("&")
        Log.e(TAG, "extracted id = '$id'")
        
        if (id.isBlank()) {
            Log.e(TAG, "FAIL: id is blank")
            return false
        }

        // 1. Token fetch
        Log.e(TAG, "Calling fetchToken...")
        val tokenData = fetchToken(id)
        if (tokenData == null) {
            Log.e(TAG, "FAIL: fetchToken returned null")
            return false
        }
        val token = tokenData.first
        val aesKeyB64 = tokenData.second
        Log.e(TAG, "SUCCESS: token=${token.take(20)}..., key=${aesKeyB64?.take(10)}")

        // 2. m3u8 URL
        val m3u8Url = "$edgeHost/premium$id/index.m3u8?t=$token&_=${System.currentTimeMillis()}"
        Log.e(TAG, "m3u8 URL: $m3u8Url")

        try {
            Log.e(TAG, "Downloading encrypted m3u8...")
            val encryptedM3u8 = app.get(
                m3u8Url,
                headers = mapOf(
                    "Referer" to "$dembedOrigin/",
                    "Origin" to dembedOrigin,
                    "User-Agent" to ua
                )
            ).text
            Log.e(TAG, "Downloaded m3u8 length=${encryptedM3u8.length}")
            Log.e(TAG, "First 200 chars: ${encryptedM3u8.take(200)}")

            // 3. Decrypt
            val baseUrl = m3u8Url.substringBefore("?")
            Log.e(TAG, "Starting decrypt with key=$aesKeyB64")
            val decryptedM3u8 = if (aesKeyB64 != null) {
                decryptM3u8(encryptedM3u8, aesKeyB64, baseUrl)
            } else {
                Log.e(TAG, "No AES key, using raw")
                encryptedM3u8
            }
            
            if (decryptedM3u8 == null) {
                Log.e(TAG, "FAIL: decryptM3u8 returned null")
                return false
            }
            Log.e(TAG, "Decrypted length=${decryptedM3u8.length}")
            Log.e(TAG, "Decrypted first 300: ${decryptedM3u8.take(300)}")

            // 4. data: URL
            val base64Data = Base64.encodeToString(decryptedM3u8.toByteArray(), Base64.NO_WRAP)
            val dataUrl = "data:application/vnd.apple.mpegurl;base64,$base64Data"
            Log.e(TAG, "data: URL length=${dataUrl.length}")

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
            Log.e(TAG, "========== SUCCESS ==========")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION in loadLinks: ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        }
    }

    private suspend fun fetchToken(channelId: String): Pair<String, String?>? {
        // Deneme 1: Headers ile
        val url = "$edgeHost/api/playlist-token?slug=premium$channelId"
        Log.e(TAG, "fetchToken URL: $url")
        
        // Önce headers'sız dene
        try {
            Log.e(TAG, "Attempt 1: no headers")
            val response = app.get(url).text
            Log.e(TAG, "Attempt 1 response (${response.length} chars): ${response.take(200)}")
            val json = JSONObject(response)
            val token = json.optString("token", "")
            val key = json.optString("k", "").ifBlank { null }
            if (token.isNotBlank()) {
                Log.e(TAG, "Attempt 1 SUCCESS")
                return Pair(token, key)
            }
            Log.e(TAG, "Attempt 1: token empty")
        } catch (e: Exception) {
            Log.e(TAG, "Attempt 1 EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        // Deneme 2: Headers ile
        try {
            Log.e(TAG, "Attempt 2: with headers")
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to "$dembedOrigin/",
                    "Origin" to dembedOrigin,
                    "User-Agent" to ua
                )
            ).text
            Log.e(TAG, "Attempt 2 response: ${response.take(200)}")
            val json = JSONObject(response)
            val token = json.optString("token", "")
            val key = json.optString("k", "").ifBlank { null }
            if (token.isNotBlank()) {
                Log.e(TAG, "Attempt 2 SUCCESS")
                return Pair(token, key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Attempt 2 EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        // Deneme 3: Farklı slug formatı
        try {
            val url2 = "$edgeHost/api/playlist-token?slug=$channelId"
            Log.e(TAG, "Attempt 3: $url2")
            val response = app.get(url2).text
            Log.e(TAG, "Attempt 3 response: ${response.take(200)}")
            val json = JSONObject(response)
            val token = json.optString("token", "")
            val key = json.optString("k", "").ifBlank { null }
            if (token.isNotBlank()) return Pair(token, key)
        } catch (e: Exception) {
            Log.e(TAG, "Attempt 3 EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        return null
    }

    private fun decryptM3u8(encrypted: String, aesKeyB64: String, baseUrl: String): String? {
        return try {
            val keyBytes = base64UrlDecode(aesKeyB64)
            Log.e(TAG, "Key decoded: ${keyBytes.size} bytes")
            if (keyBytes.size != 16 && keyBytes.size != 32) {
                Log.e(TAG, "FAIL: Invalid key size ${keyBytes.size}")
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
                        Log.e(TAG, "mediaSeq=$mediaSequence")
                    }
                    output.append(trimmed).append("\n")
                    continue
                }
                try {
                    val cipherBytes = base64UrlDecode(trimmed)
                    val iv = createIv(mediaSequence)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(64, iv))
                    val plaintext = cipher.doFinal(cipherBytes)
                    val plaintextStr = String(plaintext).trim()
                    val finalUrl = if (plaintextStr.startsWith("http")) plaintextStr
                        else baseUrl.substringBeforeLast("/") + "/" + plaintextStr
                    output.append(finalUrl).append("\n")
                    mediaSequence++
                    decryptedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Segment decrypt error #$mediaSequence: ${e.javaClass.simpleName}: ${e.message}")
                    return null
                }
            }
            Log.e(TAG, "Decrypted $decryptedCount segments total")
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt FATAL: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
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
