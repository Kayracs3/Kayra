package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


private object InatBoxYedekHlsProxy {

    private data class Mapping(
        val refreshUrl: String,
        val initialUrl: String,
        val headers: Map<String, String>,
        val name: String,
        val type: String,
        val image: String,
        val signature: String,
        val createdAt: Long,
        val targets: MutableMap<String, String> = ConcurrentHashMap(),
        @Volatile var currentUrl: String = initialUrl
    )

    private const val MAX_MAPPING_AGE_MS = 6 * 60 * 60 * 1000L
    private const val MAX_REQUEST_HEADER_BYTES = 32 * 1024

    private val mappings = ConcurrentHashMap<String, Mapping>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "InatBoxYedekHlsProxy").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var serverPort: Int = -1

    private val lock = Any()

    fun createLink(
        refreshUrl: String,
        initialUrl: String,
        headers: Map<String, String>,
        name: String,
        type: String,
        image: String,
        signature: String
    ): String? {
        if (refreshUrl.isBlank() || initialUrl.isBlank()) return null

        return runCatching {
            ensureServer()
            cleanupMappings()

            val id = UUID.randomUUID().toString().replace("-", "")
            mappings[id] = Mapping(
                refreshUrl = refreshUrl,
                initialUrl = initialUrl,
                headers = headers.toMap(),
                name = name,
                type = type,
                image = image,
                signature = signature,
                createdAt = System.currentTimeMillis()
            )

            "http://127.0.0.1:$serverPort/hls/$id/index.m3u8"
        }.getOrElse {
            Log.e("InatBoxYedekHlsProxy", "Could not create local HLS proxy: ${it.message}", it)
            null
        }
    }

    private fun ensureServer() {
        if (serverSocket?.isClosed == false && serverPort > 0) return

        synchronized(lock) {
            if (serverSocket?.isClosed == false && serverPort > 0) return

            val socket = ServerSocket(
                0,
                32,
                InetAddress.getByName("127.0.0.1")
            )
            socket.reuseAddress = true
            serverSocket = socket
            serverPort = socket.localPort

            executor.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            Log.w("InatBoxYedekHlsProxy", "Accept failed: ${e.message}")
                        }
                    }
                }
            }

            Log.d("InatBoxYedekHlsProxy", "Started on 127.0.0.1:$serverPort")
        }
    }

    private fun cleanupMappings() {
        val now = System.currentTimeMillis()
        mappings.entries.removeIf { now - it.value.createdAt > MAX_MAPPING_AGE_MS }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())

                val requestHeaders = readRequestHeaders(input) ?: return
                val requestLine = requestHeaders.firstOrNull() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) {
                    writeResponse(output, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                    return
                }

                val requestTarget = parts[1]
                val path = requestTarget.substringBefore('?')

                val match = Regex("^/hls/([^/]+)/(.+)$").matchEntire(path)
                if (match == null) {
                    writeResponse(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                    return
                }

                val mappingId = match.groupValues[1]
                val resourcePath = match.groupValues[2]
                val mapping = mappings[mappingId]
                if (mapping == null) {
                    writeResponse(output, 404, "text/plain; charset=utf-8", "Mapping expired".toByteArray())
                    return
                }

                if (resourcePath == "index.m3u8") {
                    serveFreshManifest(output, mapping, mappingId)
                } else {
                    val token = resourcePath.removePrefix("resource/")
                    val target = mapping.targets[token]
                    if (target.isNullOrBlank()) {
                        writeResponse(output, 404, "text/plain; charset=utf-8", "Resource not found".toByteArray())
                        return
                    }
                    serveTarget(output, mapping, mappingId, target)
                }
            } catch (e: Exception) {
                Log.w("InatBoxYedekHlsProxy", "Client failed: ${e.message}")
            }
        }
    }

    private fun readRequestHeaders(input: BufferedInputStream): List<String>? {
        val buffer = ByteArray(MAX_REQUEST_HEADER_BYTES)
        var size = 0

        while (size < buffer.size) {
            val b = input.read()
            if (b == -1) return null
            buffer[size++] = b.toByte()
            if (size >= 4 &&
                buffer[size - 4] == '\r'.code.toByte() &&
                buffer[size - 3] == '\n'.code.toByte() &&
                buffer[size - 2] == '\r'.code.toByte() &&
                buffer[size - 1] == '\n'.code.toByte()
            ) {
                return String(buffer, 0, size, StandardCharsets.ISO_8859_1)
                    .trim()
                    .split("\\r?\\n".toRegex())
            }
        }
        return null
    }

    private fun serveFreshManifest(
        output: BufferedOutputStream,
        mapping: Mapping,
        mappingId: String
    ) {
        val result = fetchManifestBlocking(mapping)

        if (result == null) {
            writeResponse(
                output,
                502,
                "text/plain; charset=utf-8",
                "Upstream manifest unavailable".toByteArray()
            )
            return
        }

        val rewritten = rewriteManifest(
            body = result.first,
            baseUrl = result.second,
            mapping = mapping,
            mappingId = mappingId
        )

        writeResponse(
            output,
            200,
            "application/vnd.apple.mpegurl",
            rewritten.toByteArray(StandardCharsets.UTF_8),
            extraHeaders = mapOf(
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Pragma" to "no-cache"
            )
        )
    }

    private fun fetchManifestBlocking(mapping: Mapping): Pair<String, String>? {
        val currentUrl = mapping.currentUrl.trim()

        // İlk manifest isteğinde mevcut yayın URL'sini kullan.
        // Böylece player'ın her manifest yenilemesinde API'den yeni bir akış
        // alınmaz ve aynı yayın oturumu korunur.
        fetchManifestFromUrl(mapping, currentUrl)?.let { return it }

        // Mevcut URL gerçekten bozulmuş/süresi dolmuşsa yalnızca o zaman
        // kaynak API'den yeni URL iste.
        Log.d(
            "InatBoxYedekHlsProxy",
            "Current HLS URL failed for ${mapping.name}; refreshing source"
        )

        return runCatching {
            val jsonResponse = InatBoxYedek.makeProxyRequestBlocking(mapping.refreshUrl)
                ?: return null

            val freshUrl = selectFreshChannelUrl(jsonResponse, mapping)
                ?.trim()
                .orEmpty()

            if (freshUrl.isBlank()) return null

            fetchManifestFromUrl(mapping, freshUrl)?.also {
                mapping.currentUrl = freshUrl
                Log.d(
                    "InatBoxYedekHlsProxy",
                    "HLS source refreshed for ${mapping.name}"
                )
            }
        }.getOrElse {
            Log.w("InatBoxYedekHlsProxy", "Manifest refresh failed: ${it.message}")
            null
        }
    }

    private fun fetchManifestFromUrl(
        mapping: Mapping,
        url: String
    ): Pair<String, String>? {
        if (url.isBlank()) return null

        return runCatching {
            val response = InatBoxYedek.httpGetBlocking(
                url = url,
                headers = mapping.headers,
                referer = mapping.headers["Referer"]
            ) ?: return null

            if (response.first !in 200..299) {
                Log.w(
                    "InatBoxYedekHlsProxy",
                    "Upstream manifest HTTP ${response.first} for ${mapping.name}"
                )
                return null
            }

            val body = response.second
            if (!body.trimStart().startsWith("#EXTM3U")) {
                Log.w(
                    "InatBoxYedekHlsProxy",
                    "Upstream response is not HLS for ${mapping.name}"
                )
                return null
            }

            Pair(body, url)
        }.getOrElse {
            Log.w(
                "InatBoxYedekHlsProxy",
                "Manifest request failed for ${mapping.name}: ${it.message}"
            )
            null
        }
    }

    private fun selectFreshChannelUrl(
        rawJson: String,
        mapping: Mapping
    ): String? {
        return runCatching {
            val candidates = mutableListOf<JSONObject>()
            val trimmed = rawJson.trim()

            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let(candidates::add)
                    }
                }
                trimmed.startsWith("{") -> {
                    candidates += JSONObject(trimmed)
                }
            }

            val exact = candidates.firstOrNull { item ->
                mapping.signature.isNotBlank() &&
                    proxyItemSignature(item) == mapping.signature
            }

            val named = candidates.firstOrNull { item ->
                item.optString("chName").equals(mapping.name, ignoreCase = true) &&
                    (mapping.type.isBlank() ||
                        item.optString("chType").equals(mapping.type, ignoreCase = true)) &&
                    (mapping.image.isBlank() ||
                        item.optString("chImg").equals(mapping.image))
            }

            val fallback = candidates.firstOrNull { item ->
                item.optString("chName").equals(mapping.name, ignoreCase = true)
            }

            (exact ?: named ?: fallback)?.optString("chUrl")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    if (it.startsWith("act", ignoreCase = true)) {
                        "https://vk.com/al_video.php?$it"
                    } else {
                        it
                    }
                }
        }.getOrNull()
    }

    private fun proxyItemSignature(item: JSONObject): String {
        val stableId = sequenceOf(
            "id",
            "chId",
            "channelId",
            "streamId",
            "contentId",
            "videoId"
        ).mapNotNull { key ->
            item.optString(key).takeIf { it.isNotBlank() }
        }.firstOrNull().orEmpty()

        val base = if (stableId.isNotBlank()) {
            "id:$stableId"
        } else {
            listOf(
                item.optString("chName"),
                item.optString("chType"),
                item.optString("chImg")
            ).joinToString("|")
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(base.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun rewriteManifest(
        body: String,
        baseUrl: String,
        mapping: Mapping,
        mappingId: String
    ): String {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()

        fun registerTarget(raw: String): String {
            val target = raw.trim().trim('"')
            if (target.isBlank()) return raw

            val absolute = runCatching {
                if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                    target
                } else {
                    baseUri?.resolve(target)?.toString() ?: target
                }
            }.getOrDefault(target)

            val token = UUID.randomUUID().toString().replace("-", "")
            mapping.targets[token] = absolute
            return "/hls/$mappingId/resource/$token"
        }

        val lines = body.lines().toMutableList()
        for (index in lines.indices) {
            val line = lines[index]
            if (line.isBlank()) continue

            if (!line.startsWith("#")) {
                lines[index] = registerTarget(line)
                continue
            }

            if (line.contains("URI=\"", ignoreCase = true)) {
                val regex = Regex("URI=\"([^\"]+)\"")
                lines[index] = regex.replace(line) { match ->
                    "URI=\"${registerTarget(match.groupValues[1])}\""
                }
            }
        }

        return lines.joinToString("\n")
    }

    private fun serveTarget(
        output: BufferedOutputStream,
        mapping: Mapping,
        mappingId: String,
        target: String
    ) {
        val isPlaylist = target.contains(".m3u8", ignoreCase = true) ||
            target.contains("/hls/", ignoreCase = true) ||
            target.contains("/playlist", ignoreCase = true)

        val response = InatBoxYedek.httpGetBlocking(
            url = target,
            headers = mapping.headers,
            referer = mapping.headers["Referer"]
        )

        if (response == null || response.first !in 200..299) {
            val code = response?.first ?: 502
            writeResponse(
                output,
                code,
                "text/plain; charset=utf-8",
                "Upstream resource failed".toByteArray()
            )
            return
        }

        if (isPlaylist) {
            val rewritten = rewriteManifest(
                body = response.second,
                baseUrl = target,
                mapping = mapping,
                mappingId = mappingId
            )
            writeResponse(
                output,
                200,
                "application/vnd.apple.mpegurl",
                rewritten.toByteArray(StandardCharsets.UTF_8),
                extraHeaders = mapOf(
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Pragma" to "no-cache"
                )
            )
            return
        }

        val bodyBytes = response.third ?: response.second.toByteArray(StandardCharsets.ISO_8859_1)
        val contentType = when {
            target.contains(".aac", true) -> "audio/aac"
            target.contains(".m4s", true) -> "video/iso.segment"
            target.contains(".ts", true) -> "video/mp2t"
            else -> "application/octet-stream"
        }

        writeResponse(
            output,
            200,
            contentType,
            bodyBytes,
            extraHeaders = mapOf("Cache-Control" to "no-store")
        )
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            502 -> "Bad Gateway"
            else -> "Error"
        }

        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            extraHeaders.forEach { (key, value) ->
                append(key).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }

        output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

}

class InatBoxYedek : MainAPI() {

    override var name = "İnatBoxYedek"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L
    override val getMainPageTimeoutMs = 30_000L

    private val tabCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
    private val urlToSearchResponse = ConcurrentHashMap<String, SearchResponse>()

    companion object {
        private const val CACHE_TTL_MS = 60 * 1000L
        private const val HMK = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"
        private const val LEGACY_AES_KEY = "ywevqtjrurkwtqgz"
        private val SECURE_RANDOM = SecureRandom()
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun generateRandomKey(length: Int = 16): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val sb = StringBuilder(length)
            repeat(length) {
                sb.append(chars[SECURE_RANDOM.nextInt(chars.length)])
            }
            return sb.toString()
        }

        fun bytesToHex(bytes: ByteArray): String {
            val hex = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX_CHARS[b ushr 4]
                hex[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return String(hex)
        }

        fun sha256Hex(data: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return bytesToHex(md.digest(data.toByteArray(StandardCharsets.UTF_8)))
        }

        fun hmacSha256(data: String, key: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return bytesToHex(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
        }

        private val proxyHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        internal fun makeProxyRequestBlocking(
            url: String,
            retryCount: Int = 2
        ): String? {
            val hostName = try {
                URI(url).host ?: "speedrestapi.com"
            } catch (_: Exception) {
                "speedrestapi.com"
            }

            repeat(retryCount) { attempt ->
                try {
                    val dynamicKey = generateRandomKey(16)
                    val requestBody = "1=$dynamicKey&0=$dynamicKey"
                    val headers = mutableMapOf(
                        "User-Agent" to "speedrestapi",
                        "X-Requested-With" to "com.bp.box",
                        "Referer" to "https://speedrestapi.com/",
                        "Cache-Control" to "no-cache",
                        "Host" to hostName
                    )
                    headers.putAll(signRequest("POST", url, requestBody))

                    val request = Request.Builder()
                        .url(url)
                        .post(
                            requestBody.toRequestBody(
                                "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                            )
                        )
                        .apply {
                            headers.forEach { (key, value) ->
                                addHeader(key, value)
                            }
                        }
                        .build()

                    proxyHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.bytes() ?: return@use
                        if (body.isEmpty()) return@use

                        val decrypted = decryptDoubleAes(
                            String(body, StandardCharsets.UTF_8),
                            dynamicKey
                        )

                        if (!decrypted.isNullOrBlank()) {
                            throw ProxyResult(decrypted)
                        }
                    }
                } catch (result: ProxyResult) {
                    return result.value
                } catch (e: Exception) {
                    if (attempt == retryCount - 1) {
                        Log.w(
                            "InatBoxYedek",
                            "Proxy refresh request failed: ${e.message}"
                        )
                    }
                }
            }

            return null
        }

        internal fun httpGetBlocking(
            url: String,
            headers: Map<String, String>,
            referer: String?
        ): Triple<Int, String, ByteArray>? {
            return try {
                val requestBuilder = Request.Builder().url(url).get()

                headers.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        requestBuilder.addHeader(key, value)
                    }
                }

                if (!referer.isNullOrBlank() && headers["Referer"].isNullOrBlank()) {
                    requestBuilder.addHeader("Referer", referer)
                }

                proxyHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val text = String(bytes, StandardCharsets.UTF_8)
                    Triple(response.code, text, bytes)
                }
            } catch (e: Exception) {
                Log.w("InatBoxYedek", "Proxy GET failed: ${e.message}")
                null
            }
        }

        private class ProxyResult(val value: String) : Exception()

        fun signRequest(method: String, url: String, body: String): Map<String, String> {
            val uriPath = try {
                URI(url).rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            } catch (_: Exception) {
                "/"
            }

            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonceBytes = ByteArray(16)
            SECURE_RANDOM.nextBytes(nonceBytes)
            val nonce = bytesToHex(nonceBytes)
            val bodyHash = sha256Hex(body)
            val signString = "${method.uppercase(Locale.ROOT)}\n$uriPath\n$timestamp\n$nonce\n$bodyHash"
            val signature = hmacSha256(signString, HMK)

            return mapOf(
                "X-Ts" to timestamp,
                "X-Nc" to nonce,
                "X-Sg" to signature
            )
        }

        fun decryptAesLayer(encryptedTextWithIv: String, key: String): String? {
            return runCatching {
                val parts = encryptedTextWithIv.split(":")
                if (parts.size < 2) return null

                val cipherBytes = Base64.decode(parts[0].trim(), Base64.DEFAULT)
                val ivBytes = Base64.decode(parts[1].trim(), Base64.DEFAULT)

                val keyStr = when {
                    key.length < 16 -> key.padEnd(16, '0')
                    key.length > 16 -> key.substring(0, 16)
                    else -> key
                }

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(
                        keyStr.toByteArray(StandardCharsets.ISO_8859_1),
                        "AES"
                    ),
                    IvParameterSpec(ivBytes)
                )

                String(
                    cipher.doFinal(cipherBytes),
                    StandardCharsets.ISO_8859_1
                )
            }.getOrNull()
        }

        fun decryptDoubleAes(encryptedResponse: String, key: String): String? {
            return runCatching {
                val layer1 = decryptAesLayer(encryptedResponse, key) ?: return null
                val layer2 = decryptAesLayer(layer1, key) ?: return null
                val text = String(
                    layer2.toByteArray(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8
                ).trim()

                val lastBracket = text.lastIndexOf(']')
                val lastBrace = text.lastIndexOf('}')
                val endIdx = maxOf(lastBracket, lastBrace)

                if (endIdx != -1 && endIdx < text.length - 1) {
                    text.substring(0, endIdx + 1)
                } else {
                    text
                }
            }.getOrNull()
        }

        fun verifyAndStripHmacSuffix(text: String): String? {
            if (text.length <= 64) return null

            val payload = text.substring(0, text.length - 64)
            val signature = text.substring(text.length - 64)
            val expected = hmacSha256(payload, HMK)

            return if (signature.equals(expected, ignoreCase = true)) {
                payload
            } else {
                payload
            }
        }
    }

    override val mainPage = mainPageOf(
        "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php" to "Spor",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php" to "Ulusal Kanallar",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/haber.php" to "Haber Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/cocuk.php" to "Çocuk Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/sinema.php" to "Sinema Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/belgesel.php" to "Belgesel Kanalları",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ex/index.php" to "Exxen",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php" to "Netflix",
        "https://sprboxs.bar/CDN/001/SPR/v2/ccc/a/index.php" to "TOD",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/hb/index.php" to "BluTV & HBO",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/dsny/index.php" to "Disney+",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/ga/index.php" to "Gain",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/amz/index.php" to "Amazon Prime",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/tbi/index.php" to "Tabii",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php" to "Yerli Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php" to "Yabancı Diziler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php" to "Yerli Filmler",
        "https://diziboxen.help/CDN/001/002/dizibox/v2/film/mubi.php" to "Mubi",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val now = System.currentTimeMillis()
        val cached = tabCache[url]

        val allResults = if (
            cached != null &&
            now - cached.first < CACHE_TTL_MS &&
            cached.second.isNotEmpty()
        ) {
            cached.second
        } else {
            val jsonResponse = loadCatalogResponse(url)
            val parsedResults = if (jsonResponse.isNullOrBlank()) {
                emptyList()
            } else {
                getSearchResponseList(
                    jsonResponse,
                    sourceUrl = url,
                    allowGeneric4k = is4kCatalogUrl(url)
                )
            }

            if (parsedResults.isNotEmpty()) {
                // Only replace the last known-good catalog with a non-empty response.
                // Some live-TV endpoints intermittently answer with an empty/unknown
                // payload while channels are still available on the source app.
                tabCache[url] = Pair(System.currentTimeMillis(), parsedResults)
                parsedResults.forEach { urlToSearchResponse.putIfAbsent(it.url, it) }
                parsedResults
            } else {
                val previous = cached?.second.orEmpty()
                if (previous.isNotEmpty()) {
                    Log.w(
                        "InatBoxYedek",
                        "Catalog returned no parsed items; keeping ${previous.size} cached items for ${request.name}"
                    )
                    previous
                } else {
                    emptyList()
                }
            }
        }

        if (allResults.isEmpty()) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val isLiveCategory = request.name in listOf(
            "Spor",
            "Ulusal Kanallar",
            "Haber Kanalları",
            "Çocuk Kanalları",
            "Sinema Kanalları",
            "Belgesel Kanalları"
        )

        if (allResults.size <= 150) {
            return if (page == 1) {
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            allResults,
                            isHorizontalImages = isLiveCategory
                        )
                    ),
                    hasNext = false
                )
            } else {
                newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        }

        val pageSize = 50
        val startIndex = (page - 1) * pageSize

        if (startIndex >= allResults.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val endIndex = minOf(startIndex + pageSize, allResults.size)
        val pagedList = allResults.subList(startIndex, endIndex)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    pagedList,
                    isHorizontalImages = isLiveCategory
                )
            ),
            hasNext = endIndex < allResults.size
        )
    }

    private fun is4kCatalogUrl(url: String): Boolean =
        url.contains("4k.filmizleeeee.cfd", ignoreCase = true) &&
            url.contains("catalog-exo.php", ignoreCase = true)

    private suspend fun loadCatalogResponse(url: String): String? {
        // Normal InatBoxYedek API path.
        makeInatPostRequest(url)?.let { response ->
            if (response.isNotBlank()) return response
        }

        if (!is4kCatalogUrl(url)) return null

        // 4K catalog is hosted separately and may expose a plain JSON GET endpoint
        // instead of the encrypted InatBoxYedek POST protocol. Keep the existing POST
        // path first, then fall back to a normal GET without changing other tabs.
        return runCatching {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36",
                    "Accept" to "application/json,text/plain,*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Referer" to "https://4k.filmizleeeee.cfd/"
                )
            )

            if (!response.isSuccessful) {
                Log.w("InatBoxYedek", "4K catalog GET HTTP ${response.code}")
                return@runCatching null
            }

            extractJsonPayload(response.text)
        }.getOrElse {
            Log.w("InatBoxYedek", "4K catalog GET failed: ${it.message}")
            null
        }
    }

    private fun extractJsonPayload(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        if (text.startsWith("[") || text.startsWith("{")) return text

        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1)
        }

        val objectStart = text.indexOf('{')
        val objectEnd = text.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1)
        }

        return null
    }

    private fun matchesSearchQuery(query: String, title: String): Boolean {
        fun normalize(value: String): String {
            val lowered = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        val ignored = setOf("the", "a", "an", "of", "and", "or", "ve")
        val year = Regex("(?:18|19|20|21)\\d{2}")
        val words = q.split(' ').filterNot { it in ignored || year.matches(it) }
        if (words.isEmpty()) return q == t
        val titleWords = t.split(' ').toSet()
        val numberWords = mapOf("0" to "zero", "1" to "one", "2" to "two", "3" to "three",
            "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight",
            "9" to "nine", "10" to "ten")
        return words.all { word ->
            val mapped = numberWords[word]
            titleWords.any { candidate ->
                candidate == word || (mapped != null && candidate == mapped) ||
                    (candidate.length >= 4 && word.length >= 4 &&
                        kotlin.math.abs(candidate.length - word.length) <= 2 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(Locale.forLanguageTag("tr"))
        if (q.isBlank()) return emptyList()

        val keyUrls = listOf(
            "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php",
            "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php",
            "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php",
            "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php",
            "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php",
            "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php"
        )
        val now = System.currentTimeMillis()
        val cacheDuration = 15 * 60 * 1000L
        for (url in keyUrls) {
            val cached = tabCache[url]
            if (cached != null && now - cached.first < cacheDuration && cached.second.isNotEmpty()) {
                cached.second.forEach { urlToSearchResponse.putIfAbsent(it.url, it) }
                continue
            }
            try {
                val res = makeInatPostRequest(url) ?: continue
                val items = getSearchResponseList(res, url)
                if (items.isNotEmpty()) {
                    tabCache[url] = Pair(now, items)
                    items.forEach { urlToSearchResponse.putIfAbsent(it.url, it) }
                }
            } catch (e: Exception) {
                Log.e("InatBoxYedek", "Search preload error for $url: ${e.message}")
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()
        val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)

        for ((_, searchResponse) in urlToSearchResponse) {
            if (
                regex.containsMatchIn(searchResponse.name) ||
                searchResponse.name
                    .lowercase(Locale.forLanguageTag("tr"))
                    .contains(q)
            ) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }.filter { matchesSearchQuery(query, it.name) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val item = JSONObject(url)

            if (!inatContentAllowed(item)) return null

            if (item.has("diziType")) {
                val type = item.optString("diziType")

                when {
                    type.contains("dizi", ignoreCase = true) -> parseTvSeriesResponse(item)
                    type.contains("film", ignoreCase = true) -> parseMovieResponse(item)
                    else -> null
                }
            } else if (
                item.has("chName") &&
                item.has("chUrl") &&
                item.has("chImg")
            ) {
                val chType = item.optString("chType")
                val chUrl = item.optString("chUrl")

                when {
                    chType.contains("SsprDrm", ignoreCase = true) -> parseSSportResponse(item)
                    chType.contains("live", ignoreCase = true) ||
                        chType.contains("cable", ignoreCase = true) ->
                        parseLiveStreamLoadResponse(item)
                    chType.contains("tekli", ignoreCase = true) &&
                        !chUrl.contains("filmizleeeee", ignoreCase = true) ->
                        parseLiveSportsStreamLoadResponse(item)
                    else -> parseMovieResponse(item)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Load error: ${e.message}", e)
            null
        }
    }

    private data class InatBoxYedekPlaybackTraversalState(
        val visitedEndpoints: MutableSet<String> = HashSet(),
        var apiRequests: Int = 0
    )

    /**
     * Resolve InatBoxYedek catalogue/series JSON into actual chUrl playback entries.
     * Some catalog items are sent to the player as JSON containing diziUrl; that
     * endpoint must be POSTed and its seasons/episodes followed before a link exists.
     */
    private suspend fun loadInatPlaybackPayload(
        raw: String,
        sourceUrl: String?,
        depth: Int,
        state: InatBoxYedekPlaybackTraversalState,
        fallbackName: String,
        fallbackImage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (raw.isBlank() || depth > 4) return false
        val trimmed = raw.trim()

        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return false
            var loaded = false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val found = loadInatPlaybackItem(
                    item, sourceUrl, depth, state, fallbackName, fallbackImage,
                    subtitleCallback, callback
                )
                if (found) loaded = true
            }
            return loaded
        }

        if (trimmed.startsWith("{")) {
            val item = runCatching { JSONObject(trimmed) }.getOrNull() ?: return false
            return loadInatPlaybackItem(
                item, sourceUrl, depth, state, fallbackName, fallbackImage,
                subtitleCallback, callback
            )
        }

        // Some source endpoints return a plain media URL rather than a JSON payload.
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            if (!isDirectStream(trimmed)) return false
            return emitDirectOrRawStream(
                name = fallbackName.ifBlank { "InatBoxYedek" },
                url = trimmed,
                headers = emptyMap(),
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        // Tolerate a short prefix/suffix around a JSON response without treating
        // arbitrary page text as a playback URL.
        val extracted = extractJsonPayload(trimmed)
        if (!extracted.isNullOrBlank() && extracted != trimmed &&
            (extracted.startsWith("[") || extracted.startsWith("{"))
        ) {
            return loadInatPlaybackPayload(
                extracted, sourceUrl, depth + 1, state, fallbackName, fallbackImage,
                subtitleCallback, callback
            )
        }
        return false
    }

    private suspend fun loadInatPlaybackItem(
        item: JSONObject,
        sourceUrl: String?,
        depth: Int,
        state: InatBoxYedekPlaybackTraversalState,
        fallbackName: String,
        fallbackImage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Actual stream records use chUrl. Preserve source metadata so temporary URLs
        // can still be refreshed by the existing InatBoxYedekHlsProxy logic.
        val streamUrl = item.optString("chUrl").trim()
        if (streamUrl.isNotBlank() && !streamUrl.equals("null", true)) {
            val enriched = runCatching {
                JSONObject(item.toString()).apply {
                    if (optString("chName").isBlank() && fallbackName.isNotBlank()) {
                        put("chName", fallbackName)
                    }
                    if (optString("chImg").isBlank() && fallbackImage.isNotBlank()) {
                        put("chImg", fallbackImage)
                    }
                }
            }.getOrElse { item }

            val withSource = if (!sourceUrl.isNullOrBlank()) {
                enrichInatItem(enriched, sourceUrl)
            } else {
                enriched
            }
            return loadInatBoxYedekChContentLinks(
                parseToInatBoxYedekChContent(withSource),
                subtitleCallback,
                callback
            )
        }

        val endpoint = item.optString("diziUrl").trim()
            .takeIf { it.isNotBlank() && !it.equals("null", true) }

        if (endpoint != null && depth < 4 && state.apiRequests < 24 &&
            state.visitedEndpoints.add(endpoint)
        ) {
            state.apiRequests++
            val response = makeInatPostRequest(endpoint)
            if (!response.isNullOrBlank()) {
                val name = item.optString("diziName").ifBlank { fallbackName }
                val image = item.optString("diziImg").ifBlank { fallbackImage }
                if (loadInatPlaybackPayload(
                        response, endpoint, depth + 1, state, name, image,
                        subtitleCallback, callback
                    )
                ) return true
            }
        }

        // Handle API wrappers such as {"result":[...]} without mistaking the wrapper
        // itself for a playable item.
        val arrayKeys = listOf("result", "data", "results", "items", "sources", "episodes", "seasons")
        for (key in arrayKeys) {
            val array = item.optJSONArray(key) ?: continue
            if (loadInatPlaybackPayload(
                    array.toString(), sourceUrl, depth + 1, state,
                    fallbackName, fallbackImage, subtitleCallback, callback
                )
            ) return true
        }
        val objectKeys = listOf("result", "data", "item", "source", "stream")
        for (key in objectKeys) {
            val nested = item.optJSONObject(key) ?: continue
            if (loadInatPlaybackItem(
                    nested, sourceUrl, depth + 1, state,
                    fallbackName, fallbackImage, subtitleCallback, callback
                )
            ) return true
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val trimmed = data.trim()
            when {
                trimmed.startsWith("[") || trimmed.startsWith("{") -> {
                    loadInatPlaybackPayload(
                        raw = trimmed,
                        sourceUrl = null,
                        depth = 0,
                        state = InatBoxYedekPlaybackTraversalState(),
                        fallbackName = "InatBoxYedek",
                        fallbackImage = "",
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> {
                    emitDirectOrRawStream(
                        name = "InatBoxYedek",
                        url = trimmed,
                        headers = emptyMap(),
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                else -> false
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Error on loadLinks: ${e::class.simpleName} - ${e.message}", e)
            false
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.optString("diziName")
        val url = item.optString("diziUrl")
        val plot = item.optString("diziDetay", "")
        val catalogSourceUrl = item.optString("__inat_source_url").takeIf { it.isNotBlank() } ?: url
        val seriesPosterUrl = posterUrlFrom(
            item,
            catalogSourceUrl,
            listOf("diziImg", "diziImage", "poster", "posterUrl", "image")
        ).orEmpty()

        if (name.isBlank() || url.isBlank()) return null

        val jsonResponse = makeInatPostRequest(url) ?: return null
        val jsonArray = runCatching { JSONArray(jsonResponse) }.getOrNull() ?: return null

        return try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.optString("diziName", "Sezon ${i + 1}")

                seasonDataList.add(
                    SeasonData(
                        season = i + 1,
                        name = seasonName
                    )
                )

                val seasonUrl = seasonItem.optString("diziUrl")
                val seasonPosterUrl = posterUrlFrom(
                    seasonItem,
                    seasonUrl.ifBlank { url },
                    listOf("diziImg", "diziImage", "chImg", "chImage", "poster", "posterUrl", "image")
                ) ?: seriesPosterUrl
                if (seasonUrl.isBlank()) continue

                val episodeResponse = makeInatPostRequest(seasonUrl) ?: continue
                val episodeArray = runCatching { JSONArray(episodeResponse) }.getOrNull() ?: continue

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.optString("chName", "Bölüm ${j + 1}")
                        // Prefer episode-specific artwork, then inherit season/show artwork.
                        // Many InatBoxYedek season endpoints return names and playback URLs only.
                        val episodePoster = posterUrlFrom(
                            episodeItem,
                            seasonUrl,
                            listOf("chImg", "chImage", "ch_image", "image", "imageUrl", "poster", "posterUrl")
                        ) ?: posterUrlFrom(
                            episodeItem,
                            url,
                            listOf("chImg", "chImage", "image", "poster", "posterUrl")
                        ) ?: seasonPosterUrl

                        episodes
                            .getOrPut(DubStatus.None) { mutableListOf() }
                            .add(
                                newEpisode(
                                    buildFreshLoadData(enrichInatItem(episodeItem, seasonUrl))
                                ) {
                                    this.name = episodeName
                                    this.posterUrl = episodePoster
                                    this.season = i + 1
                                    this.episode = j + 1
                                }
                            )
                    } catch (_: JSONException) {
                    }
                }
            }

            val posterUrl = (0 until jsonArray.length())
                .asSequence()
                .mapNotNull { index ->
                    posterUrlFrom(
                        jsonArray.optJSONObject(index),
                        url,
                        listOf("diziImg", "diziImage", "poster", "posterUrl", "image")
                    )
                }
                .firstOrNull()
                ?: seriesPosterUrl

            newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Failed to parse TV response: ${e.message}")
            null
        }
    }

    private suspend fun parseSSportResponse(item: JSONObject): LoadResponse? {
        return try {
            val name = item.optString("chName", "S Sport Plus")
            val posterUrl = item.optString("chImg", "")

            val rawResponse = app.get(
                "https://sprspr.help/CDN/SSP/bir-p-no-cron.php",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "https://google.com/"
                )
            ).text

            val jsonResponse = JSONObject(rawResponse)
            val categories = jsonResponse.optJSONArray("Categories") ?: return null
            val firstCategory = categories.optJSONObject(0) ?: return null
            val contents = firstCategory.optJSONArray("Contents") ?: return null
            val episodes = mutableListOf<Episode>()

            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                val epName = content.optString("Title", "")
                val description = content.optString("Description", "")
                val medias = content.optJSONArray("Medias")

                val mediaUrl = if (medias != null && medias.length() > 0) {
                    medias.optJSONObject(0)?.optString("URL", "") ?: ""
                } else {
                    ""
                }

                if (mediaUrl.isNotEmpty()) {
                    episodes.add(
                        newEpisode(mediaUrl) {
                            this.name = epName
                            this.description = description
                            this.episode = i + 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.TvSeries
            ) {
                this.episodes = mutableMapOf(DubStatus.None to episodes)
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "S Sport error: ${e.message}")
            null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        return try {
            if (item.has("diziType")) {
                val name = item.optString("diziName")
                val url = item.optString("diziUrl")
                val posterUrl = posterUrlFrom(
                    item,
                    item.optString("__inat_source_url").takeIf { it.isNotBlank() } ?: url,
                    listOf("diziImg", "diziImage", "poster", "posterUrl", "image")
                ).orEmpty()
                val plot = item.optString("diziDetay", "")

                if (name.isBlank() || url.isBlank()) return null

                val jsonResponse = makeInatPostRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.optString("chName")
                if (name.isBlank()) return null

                val posterUrl = posterUrlFrom(
                    item,
                    item.optString("__inat_source_url").takeIf { it.isNotBlank() },
                    listOf("chImg", "chImage", "poster", "posterUrl", "image")
                ).orEmpty()

                newMovieLoadResponse(
                    name,
                    item.toString(),
                    TvType.Movie,
                    item.toString()
                ) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Movie error: ${e.message}")
            null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToInatBoxYedekChContent(item)

            newLiveStreamLoadResponse(
                chContent.chName,
                item.toString(),
                buildFreshLoadData(item)
            ) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Live sports error: ${e.message}")
            null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(
        item: JSONObject
    ): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToInatBoxYedekChContent(item)

            newLiveStreamLoadResponse(
                chContent.chName,
                item.toString(),
                buildFreshLoadData(item)
            ) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e("InatBoxYedek", "Live stream error: ${e.message}")
            null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type = if (item.has("diziType")) {
            item.optString("diziType")
        } else {
            item.optString("chType")
        }

        return type !in setOf(
            "link",
            "web",
            "link_mode",
            "web_mode",
            "destek",
            "destek_mode"
        )
    }

    private fun String.vkSourceFix(): String {
        return if (startsWith("act")) {
            "https://vk.com/al_video.php?$this"
        } else {
            this
        }
    }

    private fun parseToInatBoxYedekChContent(item: JSONObject): InatBoxYedekChContent {
        return InatBoxYedekChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.opt("chHeaders")?.toString() ?: "null",
            chReg = item.opt("chReg")?.toString() ?: "null",
            chType = item.optString("chType"),
            refreshUrl = item.optString("__inat_source_url", ""),
            refreshSignature = item.optString("__inat_source_signature", "")
        )
    }

    private suspend fun loadInatBoxYedekChContentLinks(
        chContent: InatBoxYedekChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var sourceUrl = chContent.chUrl.trim()
        if (sourceUrl.isBlank()) return false

        if (sourceUrl.startsWith("//")) {
            sourceUrl = "https:$sourceUrl"
        }

        if (sourceUrl.startsWith("act", ignoreCase = true)) {
            sourceUrl = sourceUrl.vkSourceFix()
        }

        val headers = mutableMapOf<String, String>()
        parseFirstJsonObject(chContent.chHeaders)?.let { jsonHeaders ->
            val keys = jsonHeaders.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonHeaders.optString(key)
                if (value.isBlank()) continue

                val normalizedKey = when (key.lowercase(Locale.ROOT)) {
                    "useragent", "user_agent" -> "User-Agent"
                    "xrequestedwith", "x_requested_with" -> "X-Requested-With"
                    "referrer" -> "Referer"
                    else -> key
                }
                headers[normalizedKey] = value
            }
        }

        parseFirstJsonObject(chContent.chReg)?.let { jsonReg ->
            jsonReg.optString("playSH2").takeIf { it.isNotBlank() }?.let {
                headers["Cookie"] = it
            }
        }

        if (headers["User-Agent"].isNullOrBlank()) {
            headers["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) " +
                    "Gecko/20100101 Firefox/134.0"
        }

        // Kısa ömürlü imzalı HLS bağlantılarını (ör. expires=...)
        // doğrudan eski URL ile oynatmak yerine, kayıtlı kaynak API'den
        // mümkünse yeni URL al. Bu sayede CloudStream eski token'ı
        // tekrar kullanmaz.
        if (chContent.refreshUrl.isNotBlank() && isExpiringDirectStream(sourceUrl)) {
            val refreshedUrl = refreshInatDirectUrl(
                refreshUrl = chContent.refreshUrl,
                signature = chContent.refreshSignature,
                name = chContent.chName,
                type = chContent.chType,
                image = chContent.chImg
            )

            if (!refreshedUrl.isNullOrBlank()) {
                Log.d(
                    "InatBoxYedek",
                    "Refreshed expiring stream URL for ${chContent.chName}"
                )
                sourceUrl = refreshedUrl.trim()
            } else {
                Log.w(
                    "InatBoxYedek",
                    "Could not refresh expiring stream URL for ${chContent.chName}; using existing URL"
                )
            }
        }

        // CLF2 kaynakları da çözücü API'leri kullanır, ancak katalogdaki chType
        // her zaman "tekli" olmayabilir. Bu yüzden yalnızca tipe güvenme.
        val isClf2ResolverEndpoint = runCatching {
            val sourceUri = URI(sourceUrl)
            sourceUri.host.equals("str.sprboxs.bar", ignoreCase = true) &&
                sourceUri.path.trimEnd('/').equals("/CLF2", ignoreCase = true)
        }.getOrDefault(false)

        if ((chContent.chType.contains("tekli", ignoreCase = true) ||
                isClf2ResolverEndpoint) && !isDirectStream(sourceUrl)
        ) {
            val resolved = resolveTekliRegexStream(
                sourceUrl,
                headers,
                parseFirstJsonObject(chContent.chReg)?.optString("Regex1"),
                parseFirstJsonObject(chContent.chReg)?.optString("Regex2"),
                parseFirstJsonObject(chContent.chReg)?.optString("Regex2p")
            )

            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved.trim()
                if (sourceUrl.startsWith("//")) {
                    sourceUrl = "https:$sourceUrl"
                }
                Log.d("InatBoxYedek", "Resolved stream endpoint for ${chContent.chName}")
            } else if (isClf2ResolverEndpoint) {
                Log.w("InatBoxYedek", "CLF2 endpoint did not return a playable chUrl for ${chContent.chName}")
                return false
            }
        }

        if (sourceUrl.contains("filmizleeeee", ignoreCase = true) && !isDirectStream(sourceUrl)) {
            val resolved = resolveFilmizleStream(sourceUrl, headers)
            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved.trim()
                if (sourceUrl.startsWith("//")) {
                    sourceUrl = "https:$sourceUrl"
                }
            }
        }

        var playbackUrl = sourceUrl
        var playbackHeaders: Map<String, String> = headers

        if (
            chContent.refreshUrl.isNotBlank() &&
            isExpiringDirectStream(sourceUrl) &&
            sourceUrl.contains(".m3u8", ignoreCase = true)
        ) {
            val proxyUrl = InatBoxYedekHlsProxy.createLink(
                refreshUrl = chContent.refreshUrl,
                initialUrl = sourceUrl,
                headers = headers,
                name = chContent.chName,
                type = chContent.chType,
                image = chContent.chImg,
                signature = chContent.refreshSignature
            )

            if (!proxyUrl.isNullOrBlank()) {
                Log.d(
                    "InatBoxYedek",
                    "Using renewable local HLS proxy for ${chContent.chName}"
                )
                playbackUrl = proxyUrl
                playbackHeaders = emptyMap<String, String>()
            }
        }

        return emitDirectOrRawStream(
            name = chContent.chName.ifBlank { "InatBoxYedek" },
            url = playbackUrl,
            headers = playbackHeaders,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private fun parseFirstJsonObject(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null

        return runCatching {
            when {
                text.startsWith("[") -> JSONArray(text).optJSONObject(0)
                text.startsWith("{") -> JSONObject(text)
                else -> null
            }
        }.getOrNull()
    }

    private fun buildDefaultReferer(url: String): String {
        return runCatching {
            val uri = URI(url)
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.scheme}://${uri.host}/"
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private suspend fun emitDirectOrRawStream(
        name: String,
        url: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.isBlank()) return false

        val cleanUrl = url.trim()
        val direct = isDirectStream(cleanUrl)

        if (direct) {
            val linkType = when {
                cleanUrl.contains(".m3u8", ignoreCase = true) ||
                    cleanUrl.contains("/hls/", ignoreCase = true) -> ExtractorLinkType.M3U8
                cleanUrl.contains(".mpd", ignoreCase = true) ||
                    cleanUrl.contains("/dash/", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            val candidateHeaders = mutableListOf<Map<String, String>>()
            val baseHeaders = headers.toMutableMap()
            if (baseHeaders["Referer"].isNullOrBlank()) {
                buildDefaultReferer(cleanUrl).takeIf { it.isNotBlank() }?.let {
                    baseHeaders["Referer"] = it
                }
            }
            candidateHeaders += baseHeaders.toMap()

            val host = runCatching { URI(cleanUrl).host.orEmpty() }.getOrDefault("")
            if (host.equals("statusas.xyz", ignoreCase = true)) {
                val noReferer = baseHeaders.toMutableMap()
                noReferer.remove("Referer")
                candidateHeaders += noReferer

                val selfReferer = baseHeaders.toMutableMap()
                selfReferer["Referer"] = "https://$host/"
                candidateHeaders += selfReferer
            }

            var workingHeaders: Map<String, String>? = null
            val isLocalProxy = cleanUrl.startsWith("http://127.0.0.1:", ignoreCase = true)

            if (isLocalProxy) {
                workingHeaders = emptyMap()
            } else if (linkType == ExtractorLinkType.M3U8) {
                for (candidate in candidateHeaders.distinct()) {
                    if (probeM3u8(cleanUrl, candidate)) {
                        workingHeaders = candidate
                        break
                    }
                }

                if (workingHeaders == null) {
                    Log.w(
                        "InatBoxYedek",
                        "M3U8 source rejected by server: $cleanUrl"
                    )
                    return false
                }
            } else {
                workingHeaders = candidateHeaders.firstOrNull() ?: emptyMap()
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = name,
                    url = cleanUrl,
                    type = linkType
                ) {
                    this.referer = workingHeaders?.get("Referer").orEmpty()
                    this.headers = workingHeaders ?: emptyMap()
                    this.quality = guessQuality(cleanUrl)
                }
            )
            return true
        }

        val referer = headers["Referer"].orEmpty()
        val extractorFound = try {
            loadExtractor(
                cleanUrl,
                referer,
                subtitleCallback
            ) {
                callback.invoke(it)
            }
        } catch (e: Exception) {
            Log.w("InatBoxYedek", "Extractor failed for $cleanUrl: ${e.message}")
            false
        }

        if (extractorFound) return true

        Log.w(
            "InatBoxYedek",
            "No extractor/direct stream found: $cleanUrl"
        )
        return false
    }

    private suspend fun probeM3u8(
        url: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            val response = app.get(
                url = url,
                headers = headers,
                referer = headers["Referer"]
            )

            if (!response.isSuccessful) {
                Log.w(
                    "InatBoxYedek",
                    "M3U8 probe HTTP ${response.code}: $url"
                )
                false
            } else {
                val body = response.text.trimStart()
                body.startsWith("#EXTM3U") ||
                    body.contains("#EXT-X-STREAM-INF") ||
                    body.contains("#EXTINF:")
            }
        } catch (e: Exception) {
            Log.w(
                "InatBoxYedek",
                "M3U8 probe failed: ${e.message} | $url"
            )
            false
        }
    }

    private fun guessQuality(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            Regex("(?:^|[^0-9])1080(?:p)?(?:[^0-9]|$)").containsMatchIn(lower) -> Qualities.P1080.value
            Regex("(?:^|[^0-9])720(?:p)?(?:[^0-9]|$)").containsMatchIn(lower) -> Qualities.P720.value
            Regex("(?:^|[^0-9])480(?:p)?(?:[^0-9]|$)").containsMatchIn(lower) -> Qualities.P480.value
            Regex("(?:^|[^0-9])360(?:p)?(?:[^0-9]|$)").containsMatchIn(lower) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun resolveTekliRegexStream(
        url: String,
        headers: Map<String, String>,
        regex1: String?,
        regex2: String?,
        regex2p: String?
    ): String? {
        // 1) Güncel API akışı: imzalı POST
        runCatching {
            val modernJson = makeInatPostRequest(url)
            extractChUrl(modernJson)?.let {
                Log.d("InatBoxYedek", "tekli_regex resolved via signed POST")
                return it
            }
        }

        // 2) Eski InatBoxYedek akışı: sabit AES anahtarlı POST
        runCatching {
            val legacyJson = makeLegacyInatRequest(url)
            extractChUrl(legacyJson)?.let {
                Log.d("InatBoxYedek", "tekli_regex resolved via legacy POST")
                return it
            }
        }

        // 3) Bazı kaynaklar doğrudan GET ile şifreli gövde döndürüyor
        runCatching {
            val response = app.get(url, headers = headers).text
            val decrypted = decryptLegacyInatResponse(response)
            extractChUrl(decrypted)?.let {
                Log.d("InatBoxYedek", "tekli_regex resolved via legacy GET decrypt")
                return it
            }
        }

        // 4) Regex1/Regex2 tabanlı özel çözüm
        val r1 = regex1?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            val reqHeaders = headers.toMutableMap()
            reqHeaders.putAll(signRequest("GET", url, ""))
            reqHeaders["Cache-Control"] = "no-cache"

            val response = app.get(url, headers = reqHeaders)
            if (!response.isSuccessful) return@runCatching null

            val raw = response.text.trim()
            val p1 = decryptAesLayer(raw, r1) ?: return@runCatching null

            val keys = buildList {
                regex2?.takeIf { it.isNotBlank() }?.let(::add)
                regex2p?.takeIf { it.isNotBlank() }?.let(::add)
                if (isEmpty()) add(r1)
            }.distinct()

            for (key in keys) {
                val p2 = decryptAesLayer(p1, key) ?: continue
                val stripped = verifyAndStripHmacSuffix(p2) ?: p2
                extractChUrl(stripped)?.let { return@runCatching it }
            }

            null
        }.getOrNull()
    }

    private fun extractChUrl(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null

        return runCatching {
            val trimmed = rawJson.trim()
            val payload = when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> trimmed
                else -> extractJsonPayload(trimmed) ?: return@runCatching null
            }

            val root: Any = when {
                payload.startsWith("{") -> JSONObject(payload)
                payload.startsWith("[") -> JSONArray(payload)
                else -> return@runCatching null
            }

            fun findChUrl(value: Any?, depth: Int): String? {
                if (value == null || depth > 5) return null

                when (value) {
                    is JSONObject -> {
                        val direct = value.optString("chUrl").trim()
                        if (direct.isNotBlank() && !direct.equals("null", ignoreCase = true)) {
                            return direct
                        }

                        // Modern responses may wrap stream records several levels deep.
                        val nestedKeys = listOf(
                            "result", "data", "results", "items", "sources",
                            "source", "stream", "episodes", "seasons", "media"
                        )
                        for (key in nestedKeys) {
                            val nested = value.opt(key) ?: continue
                            findChUrl(nested, depth + 1)?.let { return it }
                        }
                    }

                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            findChUrl(value.opt(index), depth + 1)?.let { return it }
                        }
                    }
                }
                return null
            }

            findChUrl(root, 0)
        }.getOrNull()
    }
    private fun isDirectStream(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv") ||
            lower.contains("/master.m3u8") ||
            lower.contains("/index.m3u8") ||
            lower.contains("/playlist.m3u8") ||
            lower.contains("/manifest.mpd") ||
            lower.contains("/hls/") ||
            lower.contains("/dash/")
    }

    private suspend fun resolveFilmizleStream(
        url: String,
        headers: Map<String, String>
    ): String? {
        var response = try {
            app.get(
                url,
                headers = headers,
                referer = headers["Referer"]
            ).text
        } catch (_: Exception) {
            return null
        }

        for (round in 0 until 3) {
            val separator = response.lastIndexOf(':')
            if (separator < 1) break

            val encrypted = response.substring(0, separator).trim()
            val encodedKey = response.substring(separator + 1).trim()

            val key = try {
                String(Base64.decode(encodedKey, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                break
            }

            val decrypted = decryptAesLayer(encrypted, key) ?: break
            response = decrypted

            val extractedUrl = extractChUrl(response)
            if (!extractedUrl.isNullOrBlank()) return extractedUrl
        }

        return null
    }

    private suspend fun makeLegacyInatRequest(url: String): String? {
        return runCatching {
            val hostName = URI(url).host ?: return@runCatching null
            val requestBody = "1=$LEGACY_AES_KEY&0=$LEGACY_AES_KEY"
            val headers = mapOf(
                "Cache-Control" to "no-cache",
                "Content-Length" to requestBody.length.toString(),
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Host" to hostName,
                "Referer" to "https://speedrestapi.com/",
                "User-Agent" to "speedrestapi",
                "X-Requested-With" to "com.bp.box"
            )

            val response = app.post(
                url = url,
                headers = headers,
                requestBody = requestBody.toRequestBody(
                    "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                )
            )

            if (!response.isSuccessful) return@runCatching null
            decryptLegacyInatResponse(response.text)
        }.getOrNull()
    }

    private fun decryptLegacyInatResponse(response: String): String? {
        return runCatching {
            val firstPart = response.substringBefore(":")
            val keySpec = SecretKeySpec(
                LEGACY_AES_KEY.toByteArray(StandardCharsets.UTF_8),
                "AES"
            )
            val iv = IvParameterSpec(
                LEGACY_AES_KEY.toByteArray(StandardCharsets.UTF_8)
            )

            val cipher1 = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher1.init(Cipher.DECRYPT_MODE, keySpec, iv)
            val layer1 = cipher1.doFinal(Base64.decode(firstPart, Base64.DEFAULT))

            val nested = String(layer1, StandardCharsets.UTF_8).substringBefore(":")
            val cipher2 = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, iv)
            String(
                cipher2.doFinal(Base64.decode(nested, Base64.DEFAULT)),
                StandardCharsets.UTF_8
            ).trim()
        }.getOrNull()
    }

    private suspend fun makeInatPostRequest(
        url: String,
        retryCount: Int = 2
    ): String? {
        val hostName = try {
            URI(url).host ?: "speedrestapi.com"
        } catch (_: Exception) {
            "speedrestapi.com"
        }

        repeat(retryCount) { attempt ->
            try {
                val dynamicKey = generateRandomKey(16)
                val requestBody = "1=$dynamicKey&0=$dynamicKey"

                val headers = mutableMapOf(
                    "User-Agent" to "speedrestapi",
                    "X-Requested-With" to "com.bp.box",
                    "Referer" to "https://speedrestapi.com/",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Cache-Control" to "no-cache",
                    "Host" to hostName
                )

                headers.putAll(
                    signRequest(
                        "POST",
                        url,
                        requestBody
                    )
                )

                val response = app.post(
                    url = url,
                    headers = headers,
                    requestBody = requestBody.toRequestBody(
                        "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                    )
                )

                if (
                    response.isSuccessful &&
                    response.text.isNotBlank()
                ) {
                    val decrypted = decryptDoubleAes(
                        response.text,
                        dynamicKey
                    )

                    if (!decrypted.isNullOrBlank()) {
                        return decrypted
                    }
                }
            } catch (e: Exception) {
                if (attempt == retryCount - 1) {
                    Log.e(
                        "InatBoxYedek",
                        "Post request failed for $url: ${e.message}",
                        e
                    )
                }
            }
        }

        return null
    }

    private fun buildFreshLoadData(item: JSONObject): String {
        return runCatching {
            val copy = JSONObject(item.toString())
            copy.put(
                "__inat_load_nonce",
                "${System.currentTimeMillis()}_${SECURE_RANDOM.nextInt(Int.MAX_VALUE)}"
            )
            copy.toString()
        }.getOrElse { item.toString() }
    }

    /**
     * InatBoxYedek catalogue versions use different image keys and sometimes return "null"
     * as literal text. Treat those as missing, preserve valid absolute CDN URLs, and
     * resolve relative paths against the endpoint that supplied the record.
     */
    /**
     * CloudStream/Coil on some devices cannot load raw R2 URLs from this catalog
     * (the runtime log showed TLS packet parsing errors). Convert those URLs to
     * WebP explicitly, avoiding a potentially incompatible optimizer response.
     */
    private fun makeArtworkCompatible(url: String): String {
        val host = runCatching { URI(url).host.orEmpty() }
            .getOrDefault("")
            .lowercase(Locale.ROOT)

        if (!host.endsWith(".r2.dev")) return url

        val encoded = URLEncoder.encode(url, "UTF-8")
        return "https://wsrv.nl/?url=$encoded&w=384&output=webp&q=80"
    }

    private fun normalizePosterUrl(raw: String?, sourceUrl: String? = null): String? {
        var value = raw?.trim().orEmpty()
        if (value.isBlank() || value.equals("null", true) || value == "0") return null

        value = value
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'')

        if (value.isBlank() || value.equals("null", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("javascript:", true) ||
            value.startsWith("about:", true)
        ) return null

        if (value.startsWith("//")) return makeArtworkCompatible("https:$value")
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            return makeArtworkCompatible(value)
        }

        if (Regex("""(?i)^[a-z0-9.-]+\.[a-z]{2,}(?::\d+)?/""").containsMatchIn(value)) {
            return makeArtworkCompatible("https://$value")
        }

        val base = runCatching { URI(sourceUrl.orEmpty()) }.getOrNull() ?: return null
        if (base.scheme.isNullOrBlank() || base.host.isNullOrBlank()) return null
        val resolved = runCatching { base.resolve(value).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: return null
        return makeArtworkCompatible(resolved)
    }
    private fun isArtworkField(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return normalized in setOf(
            "diziimg", "diziimage", "diziimageurl", "diziposter", "diziposterurl",
            "chimg", "chimage", "chimageurl", "poster", "posterurl", "posterpath",
            "posterimage", "image", "imageurl", "imagepath", "img", "imgurl",
            "thumbnail", "thumbnailurl", "thumb", "thumburl", "cover", "coverurl",
            "coverimage", "afis", "afisurl", "picture", "pictureurl", "photourl"
        ) || listOf("poster", "thumbnail", "cover", "afis").any { normalized.contains(it) }
    }

    private fun posterCandidate(value: Any?, sourceUrl: String?, depth: Int = 0): String? {
        if (value == null || depth > 4) return null

        when (value) {
            is String -> return normalizePosterUrl(value, sourceUrl)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    posterCandidate(value.opt(index), sourceUrl, depth + 1)?.let { return it }
                }
            }
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                val preferred = listOf("url", "src", "href", "path", "original", "large", "full", "image", "poster")
                for (name in preferred) {
                    val key = keys.firstOrNull { it.equals(name, true) } ?: continue
                    posterCandidate(value.opt(key), sourceUrl, depth + 1)?.let { return it }
                }
                for (key in keys) {
                    val child = value.opt(key) ?: continue
                    if (isArtworkField(key) || key.equals("url", true) ||
                        key.equals("src", true) || key.equals("path", true)
                    ) {
                        posterCandidate(child, sourceUrl, depth + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun posterUrlFrom(
        item: JSONObject?,
        sourceUrl: String? = null,
        preferredKeys: List<String> = emptyList()
    ): String? {
        if (item == null) return null
        val source = sourceUrl?.takeIf { it.isNotBlank() }
            ?: item.optString("__inat_source_url").takeIf { it.isNotBlank() }
        val knownKeys = (preferredKeys + listOf(
            "diziImg", "diziImage", "dizi_image", "dizi_image_url", "diziPoster", "diziPosterUrl",
            "chImg", "chImage", "ch_image", "ch_image_url", "poster", "posterUrl", "poster_url",
            "posterPath", "poster_path", "posterImage", "poster_image",
            "image", "imageUrl", "image_url", "imagePath", "image_path",
            "img", "imgUrl", "thumbnail", "thumbnailUrl", "thumbnail_url",
            "thumb", "thumbUrl", "cover", "coverUrl", "cover_image", "afis", "afisUrl"
        )).distinct()

        for (knownKey in knownKeys) {
            val key = item.keys().asSequence().firstOrNull { it.equals(knownKey, true) } ?: continue
            posterCandidate(item.opt(key), source)?.let { return it }
        }

        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isArtworkField(key)) continue
            posterCandidate(item.opt(key), source)?.let { return it }
        }
        return null
    }

    private fun enrichInatItem(
        rawItem: JSONObject,
        sourceUrl: String?
    ): JSONObject {
        return runCatching {
            val copy = JSONObject(rawItem.toString())
            val source = sourceUrl?.trim().orEmpty()
            if (source.isNotBlank()) {
                copy.put("__inat_source_url", source)
                copy.put("__inat_source_signature", inatItemSignature(rawItem))
            }
            copy
        }.getOrElse { rawItem }
    }

    private fun inatItemSignature(item: JSONObject): String {
        val stableId = sequenceOf(
            "id",
            "chId",
            "channelId",
            "streamId",
            "contentId",
            "videoId"
        ).mapNotNull { key ->
            item.optString(key).takeIf { it.isNotBlank() }
        }.firstOrNull().orEmpty()

        val base = if (stableId.isNotBlank()) {
            "id:$stableId"
        } else {
            listOf(
                item.optString("chName"),
                item.optString("chType"),
                item.optString("chImg")
            ).joinToString("|")
        }

        return sha256Hex(base)
    }

    private fun isExpiringDirectStream(url: String): Boolean {
        if (!isDirectStream(url)) return false

        return try {
            val uri = URI(url)
            val query = uri.rawQuery.orEmpty().lowercase(Locale.ROOT)
            query.contains("expires=") ||
                query.contains("expire=") ||
                query.contains("token=") ||
                query.contains("md5=") && (query.contains("exp") || query.contains("expires"))
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun refreshInatDirectUrl(
        refreshUrl: String,
        signature: String,
        name: String,
        type: String,
        image: String
    ): String? {
        val jsonResponse = makeInatPostRequest(refreshUrl, retryCount = 2) ?: return null

        return runCatching {
            val candidates = mutableListOf<JSONObject>()
            val trimmed = jsonResponse.trim()

            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let(candidates::add)
                    }
                }
                trimmed.startsWith("{") -> {
                    candidates += JSONObject(trimmed)
                }
            }

            val targetSignature = signature.takeIf { it.isNotBlank() }
            val exactSignatureMatch = candidates.firstOrNull {
                targetSignature != null && inatItemSignature(it) == targetSignature
            }

            val fallback = candidates.firstOrNull { item ->
                item.optString("chName").equals(name, ignoreCase = true) &&
                    (type.isBlank() || item.optString("chType").equals(type, ignoreCase = true)) &&
                    (image.isBlank() || item.optString("chImg").equals(image))
            } ?: candidates.firstOrNull { item ->
                item.optString("chName").equals(name, ignoreCase = true)
            }

            (exactSignatureMatch ?: fallback)?.optString("chUrl")
                ?.takeIf { it.isNotBlank() }
                ?.vkSourceFix()
        }.getOrNull()
    }

    private fun hasStandardInatFields(item: JSONObject): Boolean =
        item.has("diziType") || (item.has("chName") && item.has("chUrl"))

    private fun normalizeGeneric4kItem(item: JSONObject): JSONObject? {
        fun firstNonBlank(vararg keys: String): String =
            keys.asSequence()
                .map { key -> item.optString(key).trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

        val name = firstNonBlank(
            "diziName", "name", "title", "filmName", "movieName",
            "film_title", "movie_title", "originalTitle"
        )
        val url = firstNonBlank(
            "diziUrl", "url", "link", "href", "filmUrl", "movieUrl",
            "playUrl", "playerUrl", "streamUrl"
        )
        val image = firstNonBlank(
            "diziImg", "poster", "posterUrl", "image", "imageUrl",
            "img", "thumbnail", "thumb"
        )

        if (name.isBlank() || url.isBlank()) return null

        return JSONObject().apply {
            put("chName", name)
            put("chUrl", url)
            put("chImg", image)
            put("chType", "4k")
            put("__inat_4k", true)
            item.optString("description").takeIf { it.isNotBlank() }?.let { put("chDescription", it) }
            item.optString("plot").takeIf { it.isNotBlank() }?.let { put("chPlot", it) }
        }
    }

    private fun getSearchResponseList(
        jsonResponse: String,
        sourceUrl: String? = null,
        allowGeneric4k: Boolean = false
    ): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()

        try {
            val trimmedJson = jsonResponse.trimStart()
            val itemsArray = if (trimmedJson.startsWith("[")) {
                JSONArray(jsonResponse)
            } else if (trimmedJson.startsWith("{")) {
                val root = JSONObject(jsonResponse)
                val arrayKeys = listOf(
                    "data", "results", "items", "movies", "films", "catalog",
                    "channels", "channelList", "live", "streams", "vod", "list", "rows"
                )
                val objectKeys = listOf("data", "result", "response", "payload")

                fun findCatalogArray(node: JSONObject, depth: Int): JSONArray? {
                    if (depth > 3) return null
                    arrayKeys.forEach { key ->
                        node.optJSONArray(key)?.let { return it }
                    }
                    objectKeys.forEach { key ->
                        val nested = node.optJSONObject(key) ?: return@forEach
                        findCatalogArray(nested, depth + 1)?.let { return it }
                    }
                    return null
                }

                findCatalogArray(root, 0)
                    ?: JSONArray().apply {
                        if (
                            hasStandardInatFields(root) ||
                            (allowGeneric4k && (root.has("name") || root.has("title")))
                        ) {
                            put(root)
                        }
                    }
            } else {
                JSONArray()
            }

            for (i in 0 until itemsArray.length()) {
                val rawItem = itemsArray.optJSONObject(i) ?: continue
                if (!inatContentAllowed(rawItem)) continue

                val item = if (allowGeneric4k && !hasStandardInatFields(rawItem)) {
                    normalizeGeneric4kItem(rawItem) ?: continue
                } else {
                    rawItem
                }.let { enrichInatItem(it, sourceUrl) }

                if (item.has("diziType")) {
                    val name = item.optString("diziName", "İsimsiz")
                    val type = item.optString("diziType")
                    val posterUrl = posterUrlFrom(
                        item,
                        sourceUrl,
                        listOf("diziImg", "diziImage", "poster", "posterUrl", "image")
                    ).orEmpty()

                    when {
                        type.contains("dizi", ignoreCase = true) -> {
                            searchResults.add(
                                newTvSeriesSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        type.contains("film", ignoreCase = true) -> {
                            searchResults.add(
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }

                    continue
                }

                if (
                    item.has("chName") &&
                    item.has("chUrl") &&
                    item.has("chImg")
                ) {
                    val name = item.optString("chName", "İsimsiz")
                    val posterUrl = posterUrlFrom(
                        item,
                        sourceUrl,
                        listOf("chImg", "chImage", "poster", "posterUrl", "image")
                    ).orEmpty()
                    val chType = item.optString("chType")
                    val chUrl = item.optString("chUrl")

                    when {
                        chType.contains("live", ignoreCase = true) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        chType.contains("cable", ignoreCase = true) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        chType.contains("tekli", ignoreCase = true) &&
                            !chUrl.contains("filmizleeeee", ignoreCase = true) -> {
                            searchResults.add(
                                newLiveSearchResponse(
                                    name,
                                    item.toString(),
                                    TvType.Live
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }

                        else -> {
                            searchResults.add(
                                newMovieSearchResponse(
                                    name,
                                    item.toString()
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(
                "InatBoxYedek",
                "Failed to parse JSON response: ${e.message}",
                e
            )
        }

        return searchResults
    }
}


private data class InatBoxYedekChContent(
    val chName: String,
    val chUrl: String,
    val chImg: String,
    val chHeaders: String,
    val chReg: String,
    val chType: String,
    val refreshUrl: String = "",
    val refreshSignature: String = ""
)
