// ... (önceki kodlar aynı) ...

            if (pageUrl.contains("/kanal/") && depth == 0) {
                val slug = runCatching { URI(pageUrl).path.substringAfterLast("/") }.getOrNull() ?: ""
                Log.d("Beyazelma", "Slug=$slug")

                // 1) Embed HTML sayfasını doğru başlıklarla indir
                var embedSlug: String? = null
                val embedUrls = listOf(
                    "https://embed.beyazelma78.com/?slug=$slug",
                    "https://embed.beyazelma78.com/?id=$slug",
                    "https://embed.beyazelma78.com/?channel=$slug",
                    "https://embed.beyazelma78.com/$slug"
                )
                for (url in embedUrls) {
                    val resp = runCatching { 
                        app.get(url, headers = pageHeaders + mapOf(
                            "Referer" to "https://beyazelma78.com/",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        )).text 
                    }.getOrNull() ?: continue
                    
                    Log.d("Beyazelma", "EMBED denemesi: $url → len=${resp.length}")
                    if (resp.contains("StreamPlayer.mount")) {
                        Regex("""StreamPlayer\.mount\s*\(\s*['"][^'"]+['"]\s*,\s*\{.*?"slug"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(resp)?.let { m ->
                            embedSlug = m.groupValues[1]
                            Log.d("Beyazelma", "Embed slug bulundu ($url): $embedSlug")
                            break
                        }
                    }
                }

                // 2) Eğer embed HTML'den bulunamazsa, player.js içindeki mount parametrelerini ara
                if (embedSlug == null) {
                    val playerJsUrl = "https://embed.beyazelma78.com/player.js"
                    val playerJs = runCatching {
                        app.get(playerJsUrl, headers = pageHeaders + mapOf("Referer" to "https://beyazelma78.com/"), allowRedirects = true).text
                    }.getOrNull()
                    
                    if (playerJs != null) {
                        Log.d("Beyazelma", "player.js indirildi, boyut: ${playerJs.length}")
                        // player.js içinde StreamPlayer.mount çağrısını ara (genellikle minified olur ama yine de deneyelim)
                        Regex("""StreamPlayer\.mount\s*\(\s*['"][^'"]+['"]\s*,\s*\{.*?"slug"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                            .find(playerJs)?.let { m ->
                                embedSlug = m.groupValues[1]
                                Log.d("Beyazelma", "Embed slug player.js'den bulundu: $embedSlug")
                            }
                    }
                }

                // 3) embedSlug bulunduysa /api/play/{embedSlug} çağır
                if (embedSlug != null) {
                    val apiPlayUrl = "https://embed.beyazelma78.com/api/play/$embedSlug"
                    Log.d("Beyazelma", "API Play URL: $apiPlayUrl")
                    val apiResp = runCatching {
                        app.get(apiPlayUrl, headers = pageHeaders + mapOf(
                            "Referer" to "https://embed.beyazelma78.com/",
                            "Origin" to "https://embed.beyazelma78.com"
                        )).text
                    }.getOrNull()

                    if (apiResp != null) {
                        Log.d("Beyazelma", "API yanıtı: ${apiResp.take(500)}")
                        try {
                            val json = JSONObject(apiResp)
                            val primary = json.optString("primary", "")
                            if (primary.isNotBlank()) {
                                Log.d("Beyazelma", "Primary akış bulundu: $primary")
                                candidates.add(primary)
                            }
                            val fallbacks = json.optJSONArray("fallbacks")
                            if (fallbacks != null) {
                                for (i in 0 until fallbacks.length()) {
                                    val fallback = fallbacks.optString(i, "")
                                    if (fallback.isNotBlank()) {
                                        Log.d("Beyazelma", "Fallback akış bulundu: $fallback")
                                        candidates.add(fallback)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Beyazelma", "JSON parse hatası: ${e.message}")
                        }
                    }
                }
            }

// ... (kalan kodlar aynı) ...
