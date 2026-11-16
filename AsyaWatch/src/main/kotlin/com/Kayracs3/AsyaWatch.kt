private suspend fun extractFourPichive(iframeUrl: String, referer: String,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    if (isRootOrigin(iframeUrl)) return false
    Log.d("AsyaWatch", "🔥 FourPichive: ${iframeUrl.take(80)}")

    val resp = runCatching { app.get(iframeUrl, headers = browserHeaders(iframeUrl), referer = referer, allowRedirects = true) }.getOrNull() ?: return false
    if (!resp.isSuccessful) { Log.e("AsyaWatch", "iframe HTTP ${resp.code}"); return false }

    val html = resp.text
    if (html.isBlank()) return false

    val npConfig = parseNpConfig(html) ?: return false

    // ⭐ NP_CONFIG TAM JSON logla — gate alanı var mı gör
    val fullJson = runCatching { npConfig.toString(2) }.getOrNull() ?: npConfig.toString()
    Log.d("AsyaWatch", "═════════════════════════════════════")
    Log.d("AsyaWatch", "📋 NP_CONFIG FULL (${fullJson.length}ch):")
    Log.d("AsyaWatch", fullJson)
    Log.d("AsyaWatch", "═════════════════════════════════════")

    // ⭐ TÜM keys logla
    val keys = npConfig.keys()
    while (keys.hasNext()) Log.d("AsyaWatch", "  KEY: ${keys.next()}")

    // ⭐ gate alanı var mı?
    val gateObj = npConfig.optJSONObject("gate")
    if (gateObj != null) {
        Log.d("AsyaWatch", "🎯🎯🎯 GATE BULUNDU! ${gateObj.toString()}")
        val gateN = gateObj.optString("n")
        val gateB = gateObj.optInt("b", 12)
        Log.d("AsyaWatch", "  gate.n=${gateN.take(100)} (${gateN.length}ch), gate.b=$gateB")

        if (gateN.isNotBlank()) {
            // PoW çöz
            val pow = solvePow(gateN) ?: run { Log.e("AsyaWatch", "PoW çözülemedi"); return false }
            val masterUrl = npConfig.optJSONArray("sources")?.optJSONObject(0)?.optString("master") ?: return false
            val vMatch = Regex("""[?&]v=([^&]+)""").find(masterUrl)
            val vToken = vMatch?.groupValues?.getOrNull(1) ?: return false
            val baseUrl = masterUrl.substringBefore('?')
            val finalUrl = "$baseUrl?v=$vToken&p=$pow"

            Log.d("AsyaWatch", "🎯 gate.n üzerinden PoW çözüldü: p=$pow")
            Log.d("AsyaWatch", "🎯 URL: $finalUrl")

            val test = runCatching { app.get(finalUrl, headers = browserHeaders(iframeUrl), referer = iframeUrl) }.getOrNull()
            Log.d("AsyaWatch", "  Test: HTTP ${test?.code}, bodyLen=${test?.text?.length ?: 0}")
            if (test != null && test.code == 200 && test.text.startsWith("#EXTM3U")) {
                // Altyazılar
                npConfig.optJSONArray("subtitles")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val sub = arr.optJSONObject(i) ?: continue
                        val s = SubtitleFile(lang = sub.optString("label", "Altyazı"), url = sub.optString("url"))
                        s.headers = siteHeaders + mapOf("Referer" to iframeUrl, "Origin" to "https://four.pichive.online", "Accept" to "text/vtt, text/plain, */*")
                        subtitleCallback(s)
                    }
                }
                callback(newExtractorLink(source = name, name = "$name FourPichive", url = finalUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = iframeUrl
                    this.headers = browserHeaders(iframeUrl)
                    this.quality = Qualities.Unknown.value
                })
                Log.d("AsyaWatch", "✔✔✔ gate.n BAŞARILI!")
                return true
            }
        }
    } else {
        Log.w("AsyaWatch", "⚠ NP_CONFIG'de 'gate' alanı YOK — PoW gerekmiyor")
    }

    // gate yoksa veya başarısızsa → altyazıları ver + fallback
    npConfig.optJSONArray("subtitles")?.let { arr ->
        for (i in 0 until arr.length()) {
            val sub = arr.optJSONObject(i) ?: continue
            val s = SubtitleFile(lang = sub.optString("label", "Altyazı"), url = sub.optString("url"))
            s.headers = siteHeaders + mapOf("Referer" to iframeUrl, "Origin" to "https://four.pichive.online", "Accept" to "text/vtt, text/plain, */*")
            subtitleCallback(s)
        }
    }
    return false
}
