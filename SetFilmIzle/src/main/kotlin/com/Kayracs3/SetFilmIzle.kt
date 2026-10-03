package com.Kayracs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class SetFilmIzle : MainAPI() {

    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tag = "SetFilmIzle"

    private fun pageHeaders(referer: String = mainUrl + "/"): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
        )

    private fun ajaxHeaders(referer: String): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
        )

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Son Filmler",
        mainUrl + "/dizi/" to "Son Diziler",
        mainUrl + "/film/" to "Filmler",
        mainUrl + "/trend/" to "Trendler",
        mainUrl + "/imdb-en-iyiler/" to "IMDb Top 250",
        mainUrl + "/ag/netflix/" to "Netflix",
        mainUrl + "/ag/apple-tv/" to "Apple TV+",
        mainUrl + "/ag/prime-video/" to "Prime Video",
        mainUrl + "/ag/amazon/" to "Amazon",
        mainUrl + "/ag/disney/" to "Disney+",
        mainUrl + "/yerli-filmler/" to "Yerli Filmler",
        mainUrl + "/yerli-diziler-izle/" to "Yerli Diziler",
        mainUrl + "/tur/aksiyon/" to "Aksiyon",
        mainUrl + "/tur/komedi/" to "Komedi",
        mainUrl + "/tur/bilim-kurgu/" to "Bilim-Kurgu",
        mainUrl + "/tur/korku/" to "Korku",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = runCatching {
            app.get(
                url,
                headers = pageHeaders(),
                referer = mainUrl + "/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        return newHomePageResponse(
            request.name,
            parseCards(document),
            hasNext = page < 100 && hasNextPage(document, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val homeDocument = runCatching {
            app.get(
                mainUrl + "/",
                headers = pageHeaders(),
                referer = mainUrl + "/",
                allowRedirects = true,
            ).document
        }.getOrNull()

        val homeHtml = homeDocument?.html().orEmpty()
        val nonces = LinkedHashSet<String>()

        Regex("""STF_AJAX\s*=\s*\{[\s\S]*?video\s*:\s*["']([^"']+)["']""")
            .find(homeHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(nonces::add)

        Regex("""video\s*:\s*["']([^"']+)["']""")
            .find(homeHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(nonces::add)

        Regex("""data-nonce=["']([^"']+)["']""")
            .findAll(homeHtml)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.isNotBlank() }
            .forEach(nonces::add)

        for (nonce in nonces) {
            val response = runCatching {
                app.post(
                    mainUrl + "/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders(mainUrl + "/"),
                    data = mapOf(
                        "action" to "ajax_search",
                        "nonce" to nonce,
                        "search" to q,
                    ),
                    allowRedirects = true,
                )
            }.getOrNull() ?: continue

            val body = response.text
            if (body.isBlank()) continue

            val html = runCatching {
                JSONObject(body).optString("html")
                    .takeIf { it.isNotBlank() }
            }.getOrNull() ?: body

            val results = parseCards(Jsoup.parse(html))
                .filterNot { it.url.contains("/bolum/") }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        val encoded = URLEncoder.encode(q, "UTF-8")
        for (url in listOf(
            mainUrl + "/?s=" + encoded,
            mainUrl + "/arama/?s=" + encoded,
        )) {
            val document = runCatching {
                app.get(
                    url,
                    headers = pageHeaders(mainUrl + "/"),
                    referer = mainUrl + "/",
                    allowRedirects = true,
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .filterNot { it.url.contains("/bolum/") }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = normalizeUrl(url, mainUrl)
        if (pageUrl.isBlank()) return null

        val response = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders(mainUrl + "/"),
                referer = mainUrl + "/",
                allowRedirects = true,
            )
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document
        val title = pageTitle(document, pageUrl) ?: return null
        val poster = posterOf(document)
        val plot = pagePlot(document)
        val year = pageYear(document)
        val rating = pageRating(document)
        val genres = pageGenres(document)
        val actors = pageActors(document)
        val trailer = pageTrailer(document)

        if (isEpisodeUrl(pageUrl)) {
            return loadEpisodePage(
                pageUrl,
                document,
                poster,
                genres,
                actors,
                trailer,
            )
        }

        val isSeries = pageUrl.contains("/dizi/", true) ||
            document.select(".season-panel, #episodes, ul.episodios, .episodios").isNotEmpty()

        if (isSeries) {
            val episodes = parseEpisodes(document, poster)
            return newTvSeriesLoadResponse(
                title,
                pageUrl,
                TvType.TvSeries,
                episodes,
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                addActors(actors)
                trailer?.let { this.trailers.add(TrailerData(it, null, false)) }
                rating?.let { score = Score.from10(it) }
            }
        }

        return newMovieLoadResponse(
            title,
            pageUrl,
            TvType.Movie,
            buildLinkData(document, pageUrl),
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            addActors(actors)
            trailer?.let { this.trailers.add(TrailerData(it, null, false)) }
            rating?.let { score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tokens = data.split("|")
        val pageUrl = tokens.getOrNull(1)
            ?.takeIf { it.startsWith("http", true) }
            ?: data.takeIf { it.startsWith("http", true) }
            ?: return false

        var fixedPostId = tokens.getOrNull(0)
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }

        val fixedPlayer = tokens.getOrNull(3).orEmpty()
        val fixedPartKey = tokens.getOrNull(4).orEmpty()
        var fixedNonce = tokens.getOrNull(2).orEmpty()

        Log.d(tag, "loadLinks page=" + pageUrl)

        val document = if (
            fixedPostId.isNullOrBlank() ||
            fixedNonce.isBlank() ||
            fixedPlayer.isBlank()
        ) {
            runCatching {
                app.get(
                    pageUrl,
                    headers = pageHeaders(mainUrl + "/"),
                    referer = mainUrl + "/",
                    allowRedirects = true,
                ).document
            }.getOrNull()
        } else {
            null
        }

        val html = document?.html().orEmpty()

        if (fixedPostId.isNullOrBlank() && document != null) {
            fixedPostId = document.select("[data-post-id]")
                .asSequence()
                .map { it.attr("data-post-id") }
                .firstOrNull { it.isNotBlank() && it.all(Char::isDigit) }
        }

        if (fixedNonce.isBlank() && html.isNotBlank()) {
            fixedNonce = Regex(
                """(?is)window\s*\.\s*STF_AJAX\s*=\s*\{.*?nonces\s*:\s*\{.*?video\s*:\s*["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        }

        if (fixedNonce.isBlank() && html.isNotBlank()) {
            fixedNonce = Regex(
                """(?is)STF_AJAX\s*=\s*\{.*?video\s*:\s*["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        }

        if (fixedNonce.isBlank() && html.isNotBlank()) {
            fixedNonce = Regex(
                """(?is)\bvideo\s*:\s*["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        }

        if (fixedNonce.isBlank() && document != null) {
            fixedNonce = document.select("[data-nonce]")
                .asSequence()
                .map { it.attr("data-nonce") }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }

        data class Player(
            val postId: String,
            val name: String,
            val partKey: String,
        )

        val players = LinkedHashMap<String, Player>()

        fun addPlayer(postId: String?, name: String?, partKey: String?) {
            val pid = postId.orEmpty().trim()
            val pname = name.orEmpty().trim()
            val pkey = partKey.orEmpty().trim()

            if (pid.isBlank() || !pid.all(Char::isDigit) || pname.isBlank()) return
            if (pid.contains("event", true)) return

            val key = pid + "|" + pname + "|" + pkey
            players[key] = Player(pid, pname, pkey)
        }

        if (fixedPostId != null && fixedPlayer.isNotBlank()) {
            addPlayer(fixedPostId, fixedPlayer, fixedPartKey)
        }

        if (html.isNotBlank()) {
            val combinedForward = Regex(
                """data-post-id\s*=\s*["']([^"']+)["'][^>]{0,500}?data-player-name\s*=\s*["']([^"']+)["'][^>]{0,300}?data-part-key\s*=\s*["']([^"']*)["']"""
            )
            for (m in combinedForward.findAll(html)) {
                addPlayer(
                    m.groupValues.getOrNull(1),
                    m.groupValues.getOrNull(2),
                    m.groupValues.getOrNull(3)
                )
            }

            val combinedReverse = Regex(
                """data-player-name\s*=\s*["']([^"']+)["'][^>]{0,300}?data-part-key\s*=\s*["']([^"']*)["'][^>]{0,500}?data-post-id\s*=\s*["']([^"']+)["']"""
            )
            for (m in combinedReverse.findAll(html)) {
                addPlayer(
                    m.groupValues.getOrNull(3),
                    m.groupValues.getOrNull(1),
                    m.groupValues.getOrNull(2)
                )
            }

            val postIds = Regex(
                """data-post-id\s*=\s*["']([^"']+)["']"""
            ).findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .filter {
                    it.isNotBlank() &&
                        it.all(Char::isDigit) &&
                        !it.contains("event", true)
                }
                .distinct()
                .toList()

            val buttons = LinkedHashMap<String, String>()

            val buttonForward = Regex(
                """data-player-name\s*=\s*["']([^"']+)["'][^>]{0,250}?data-part-key\s*=\s*["']([^"']*)["']"""
            )
            for (m in buttonForward.findAll(html)) {
                buttons[m.groupValues[1].trim()] = m.groupValues[2].trim()
            }

            val buttonReverse = Regex(
                """data-part-key\s*=\s*["']([^"']*)["'][^>]{0,250}?data-player-name\s*=\s*["']([^"']+)["']"""
            )
            for (m in buttonReverse.findAll(html)) {
                buttons[m.groupValues[2].trim()] = m.groupValues[1].trim()
            }

            if (buttons.isEmpty()) {
                Regex(
                    """data-player-name\s*=\s*["']([^"']+)["']"""
                ).findAll(html)
                    .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { buttons.putIfAbsent(it, "") }
            }

            for (pid in postIds) {
                for ((pname, pkey) in buttons) {
                    addPlayer(pid, pname, pkey)
                }
            }

            if (fixedPostId != null && buttons.isNotEmpty()) {
                for ((pname, pkey) in buttons) {
                    addPlayer(fixedPostId, pname, pkey)
                }
            }
        }

        if (players.isEmpty() && fixedPostId != null) {
            addPlayer(
                fixedPostId,
                fixedPlayer.ifBlank { "SetPlay" },
                fixedPartKey
            )
        }

        if (players.isEmpty() || fixedNonce.isBlank()) {
            Log.d(
                tag,
                "Oynatıcı verisi eksik: postId=" +
                    fixedPostId +
                    " nonceLen=" +
                    fixedNonce.length +
                    " players=" +
                    players.size
            )
            return false
        }

        val ajaxUrl = Regex(
            """(?is)window\s*\.\s*STF_AJAX\s*=\s*\{.*?url\s*:\s*["']([^"']+)["']"""
        ).find(html)?.groupValues?.getOrNull(1)?.let {
            normalizeUrl(it, pageUrl)
        } ?: (mainUrl + "/wp-admin/admin-ajax.php")

        val seenManifest = HashSet<String>()
        var processed = false

        for (player in players.values.take(8)) {
            val response = runCatching {
                app.post(
                    ajaxUrl,
                    headers = ajaxHeaders(pageUrl),
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to fixedNonce,
                        "post_id" to player.postId,
                        "player_name" to player.name,
                        "part_key" to player.partKey,
                    ),
                    allowRedirects = true,
                )
            }.getOrNull() ?: continue

            if (!response.isSuccessful || response.text.isBlank()) {
                Log.d(
                    tag,
                    "get_video_url başarısız: " +
                        player.name +
                        " code=" +
                        response.code
                )
                continue
            }

            val json = runCatching {
                JSONObject(response.text)
            }.getOrNull()

            if (json?.has("success") == true && !json.optBoolean("success", true)) {
                Log.d(tag, "get_video_url success=false: " + player.name)
                continue
            }

            var bridgeUrl = json
                ?.optJSONObject("data")
                ?.optJSONObject("stream")
                ?.optString("url")
                .orEmpty()

            if (bridgeUrl.isBlank()) {
                bridgeUrl = json
                    ?.optJSONObject("data")
                    ?.optString("url")
                    .orEmpty()
            }

            if (bridgeUrl.isBlank()) {
                bridgeUrl = Regex("""https?://[^"'<>\\s]+""")
                    .find(response.text)
                    ?.value
                    .orEmpty()
            }

            bridgeUrl = normalizeUrl(bridgeUrl, pageUrl)
            if (!bridgeUrl.startsWith("http", true)) continue

            val bridgeResponse = runCatching {
                app.get(
                    bridgeUrl,
                    headers = pageHeaders(pageUrl),
                    referer = pageUrl,
                    allowRedirects = true,
                )
            }.getOrNull() ?: continue

            if (!bridgeResponse.isSuccessful) continue

            val bridgeHtml = bridgeResponse.text.decodeJsEscapes()

            val cerceve = Regex(
                """SPG\.cerceve\s*\(\s*["'][^"']+["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""",
                RegexOption.IGNORE_CASE,
            ).find(bridgeHtml)

            var fastplayUrl = if (cerceve != null) {
                xorBase64(
                    cerceve.groupValues[1],
                    cerceve.groupValues[2]
                )
            } else {
                val twoArg = Regex(
                    """SPG\.cerceve\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""",
                    RegexOption.IGNORE_CASE,
                ).find(bridgeHtml)

                if (twoArg != null) {
                    xorBase64(
                        twoArg.groupValues[1],
                        twoArg.groupValues[2]
                    )
                } else {
                    null
                }
            }

            if (fastplayUrl.isNullOrBlank()) {
                fastplayUrl = Regex(
                    """https?://[^"'<>\\s]+/(?:stfplay|video)(?:\.php)?(?:\?[^"'<>\\s]+)?""",
                    RegexOption.IGNORE_CASE,
                ).find(bridgeHtml)?.value
            }

            if (fastplayUrl.isNullOrBlank()) continue

            fastplayUrl = normalizeUrl(
                fastplayUrl,
                bridgeResponse.url.ifBlank { bridgeUrl },
            )

            val fastplayResponse = runCatching {
                app.get(
                    fastplayUrl,
                    headers = pageHeaders(
                        bridgeResponse.url.ifBlank { bridgeUrl }
                    ),
                    referer = bridgeResponse.url.ifBlank { bridgeUrl },
                    allowRedirects = true,
                )
            }.getOrNull() ?: continue

            if (!fastplayResponse.isSuccessful) continue

            val fastplayHtml = fastplayResponse.text.decodeJsEscapes()

            val streamPath = Regex(
                """["']?(?:stream|src)["']?\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ).find(fastplayHtml)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            if (streamPath.isBlank()) continue

            val fastplayFinalUrl = fastplayResponse.url.ifBlank { fastplayUrl }
            var manifestUrl = normalizeUrl(
                streamPath.replace("&amp;", "&"),
                originOf(fastplayFinalUrl) ?: fastplayFinalUrl,
            )

            if (!manifestUrl.startsWith("http", true)) continue

            if (!manifestUrl.contains(".m3u8", true) &&
                !manifestUrl.contains("/manifests/", true)
            ) {
                val maybeM3u8 = normalizeUrl(
                    streamPath.substringBefore("#").substringBefore("?") + ".m3u8",
                    originOf(fastplayFinalUrl) ?: fastplayFinalUrl,
                )
                if (maybeM3u8.contains(".m3u8", true)) {
                    manifestUrl = maybeM3u8
                } else {
                    continue
                }
            }

            if (!seenManifest.add(manifestUrl)) continue

            val sp = Regex(
                """"sp"\s*:\s*"([^"]*)"""",
                RegexOption.IGNORE_CASE,
            ).find(fastplayHtml)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            val spT = Regex(
                """"spT"\s*:\s*(\d+)""",
                RegexOption.IGNORE_CASE,
            ).find(fastplayHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: (System.currentTimeMillis() / 1000L)

            val xSp = makeXSp(sp, spT)

            callback(
                newExtractorLink(
                    source = name,
                    name = name + " " + player.name + " HD",
                    url = manifestUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    quality = Qualities.P1080.value
                    referer = fastplayFinalUrl
                    headers = buildMap {
                        put("User-Agent", "ExoPlayerLib/2.19.1")
                        put("Referer", fastplayFinalUrl)
                        put("Accept", "*/*")
                        if (xSp.isNotBlank()) put("X-Sp", xSp)
                    }
                },
            )

            var subtitleCount = 0
            for ((url, label) in extractSubtitleCandidates(
                fastplayHtml,
                fastplayFinalUrl
            )) {
                if (emitSubtitle(
                    url,
                    label,
                    fastplayFinalUrl,
                    subtitleCallback
                )) {
                    subtitleCount++
                }
            }

            if (subtitleCount == 0) {
                val bridgeRef = bridgeResponse.url.ifBlank { bridgeUrl }
                for ((url, label) in extractSubtitleCandidates(
                    bridgeHtml,
                    bridgeRef
                )) {
                    if (emitSubtitle(
                        url,
                        label,
                        bridgeRef,
                        subtitleCallback
                    )) {
                        subtitleCount++
                    }
                }
            }

            Log.d(
                tag,
                "Stream hazır: player=" +
                    player.name +
                    ", manifest=" +
                    manifestUrl +
                    ", altyazı=" +
                    subtitleCount
            )

            processed = true
        }

        return processed
    }

    private fun buildLinkData(document: Document, pageUrl: String): String {
        val postId = document.selectFirst("#stfPlayer[data-post-id], [data-post-id]")
            ?.attr("data-post-id")
            .orEmpty()

        val html = document.html()

        var nonce = Regex(
            """STF_AJAX\s*=\s*\{[\s\S]*?video\s*:\s*["']([^"']+)["']""",
        ).find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (nonce.isBlank()) {
            nonce = Regex("""video\s*:\s*["']([^"']+)["']""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }

        val tab = document.selectFirst(".src-tab.selected, .src-tab")
        val player = tab?.attr("data-player-name").orEmpty().ifBlank { "SetPlay" }
        val part = tab?.attr("data-part-key").orEmpty()

        if (postId.isBlank() || nonce.isBlank()) return pageUrl

        return postId + "|" + pageUrl + "|" + nonce + "|" + player + "|" + part
    }

    private suspend fun loadEpisodePage(
        url: String,
        document: Document,
        poster: String?,
        genres: List<String>,
        actors: List<Actor>,
        trailer: String?,
    ): LoadResponse {
        val title = pageTitle(document, url) ?: "SetFilmIzle Bölüm"
        val numbers = episodeNumbersFrom(title + " " + url + " " + document.text())
        val seriesTitle = cleanSeriesTitle(title)

        val episode = newEpisode(url) {
            name = if (numbers != null) "Bölüm " + numbers.second else title

            if (numbers != null) {
                season = numbers.first
                episode = numbers.second
            }

            posterUrl = poster
        }

        return newTvSeriesLoadResponse(
            seriesTitle,
            url,
            TvType.TvSeries,
            listOf(episode),
        ) {
            posterUrl = poster
            plot = pagePlot(document)
            year = pageYear(document)
            tags = genres
            addActors(actors)
            trailer?.let { this.trailers.add(TrailerData(it, null, false)) }
            pageRating(document)?.let { score = Score.from10(it) }
        }
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
    ): List<Episode> {
        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()

        val panels = document.select(".season-panel[data-season], .season-panel")

        if (panels.isNotEmpty()) {
            for (panel in panels) {
                val defaultSeason = panel.attr("data-season").toIntOrNull() ?: 1

                for (link in panel.select("a.fep[href], a[href*='/bolum/']")) {
                    addEpisode(episodes, seen, link, poster, defaultSeason)
                }
            }
        }

        if (episodes.isEmpty()) {
            for (link in document.select(
                "#episodes a[href], ul.episodios a[href], " +
                    ".episodios a[href], a[href*='/bolum/']"
            )) {
                addEpisode(episodes, seen, link, poster, 1)
            }
        }

        return episodes
            .distinctBy { (it.season ?: 0).toString() + "-" + (it.episode ?: 0) + "-" + it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun addEpisode(
        target: MutableList<Episode>,
        seen: MutableSet<String>,
        link: Element,
        poster: String?,
        defaultSeason: Int,
    ) {
        val href = normalizeUrl(link.attr("href"), mainUrl)
        if (!isEpisodeUrl(href) || !seen.add(href)) return

        val context = listOf(
            link.text(),
            link.attr("title"),
            link.attr("aria-label"),
            href,
        ).joinToString(" ")

        val numbers = episodeNumbersFrom(context) ?: return

        target += newEpisode(href) {
            name = "Bölüm " + numbers.second
            season = numbers.first.takeIf { it > 0 } ?: defaultSeason
            episode = numbers.second
            posterUrl = poster
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        val cards = document.select(
            "a.card-link, article.card, article.item, " +
                "article.item.movies, div.items article, a:has(article.card)"
        )

        for (card in cards) {
            val href = if (card.tagName().equals("a", true)) {
                card.attr("href")
            } else {
                card.selectFirst("a[href]")?.attr("href").orEmpty()
            }.let { normalizeUrl(it, mainUrl) }

            if (href.isBlank() || !seen.add(href)) continue

            val lower = href.lowercase()
            val isSeries = lower.contains("/dizi/")
            val isMovie = lower.contains("/film/")

            if (!isSeries && !isMovie || lower.contains("/bolum/")) continue

            val title = listOf(
                card.selectFirst("h2.card-ad")?.text(),
                card.selectFirst("h2,h3")?.text(),
                card.selectFirst("img")?.attr("alt"),
                card.attr("data-title"),
                card.text(),
            )
                .firstOrNull { !it.isNullOrBlank() }
                ?.cleanTitle()
                .orEmpty()

            if (title.isBlank()) continue

            val poster = posterFromElement(card.selectFirst("img"))
            val rating = extractRating(card.text())

            if (isSeries) {
                results += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    rating?.let { score = Score.from10(it) }
                }
            } else {
                results += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    rating?.let { score = Score.from10(it) }
                }
            }
        }

        return results.distinctBy { it.url }
    }


    private fun detailContainer(document: Document): Element? {
        var current: Element? = document.selectFirst("h1")

        repeat(8) {
            val node = current ?: return null

            val hasActorLinks = node.select("a[href*='/oyuncu/']").isNotEmpty()
            val hasGenreLinks = node.select("a[href*='/tur/']").isNotEmpty()

            if (hasActorLinks || hasGenreLinks) {
                return node
            }

            current = node.parent()
        }

        return null
    }

    private fun pageGenres(document: Document): List<String> {
        val container = detailContainer(document) ?: document

        return container
            .select("a[href*='/tur/']")
            .map { it.text().trim() }
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { text ->
                text.isNotBlank() &&
                    !text.equals("Filmler", true) &&
                    !text.equals("Diziler", true) &&
                    !text.equals("Türler", true)
            }
            .distinct()
    }

    private fun pageActors(document: Document): List<Actor> {
        val container = detailContainer(document) ?: document

        return container
            .select("a[href*='/oyuncu/']")
            .mapNotNull { link ->
                val href = normalizeUrl(link.attr("href"), mainUrl)
                if (href.isBlank()) return@mapNotNull null

                val slug = runCatching {
                    URI(href)
                        .path
                        ?.trimEnd('/')
                        ?.substringAfterLast('/')
                        .orEmpty()
                }.getOrDefault("")

                val rawName = link.attr("title")
                    .ifBlank { link.attr("aria-label") }
                    .ifBlank { slug }

                val actorName = rawName
                    .replace('-', ' ')
                    .replace('_', ' ')
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .cleanTitle()

                if (actorName.isBlank()) return@mapNotNull null

                Actor(
                    name = actorName,
                    image = posterFromElement(link.selectFirst("img")),
                )
            }
            .distinctBy { it.name.lowercase() }
    }


    private fun pageTrailer(document: Document): String? {
        val selectors = listOf(
            "[data-trailer]",
            "[data-trailer-url]",
            "[data-youtube]",
            "[data-youtube-url]",
            "[data-video-url]",
            "[data-video]",
            "a[href*='youtube.com/watch']",
            "a[href*='youtu.be/']",
            "a[href*='youtube.com/embed/']",
            "a[href*='vimeo.com/']",
            "iframe[src*='youtube.com/embed/']",
            "iframe[src*='youtube-nocookie.com/embed/']",
            "iframe[src*='youtu.be/']",
            "iframe[src*='vimeo.com/']",
        )

        for (selector in selectors) {
            for (element in document.select(selector)) {
                val candidate = listOf(
                    element.attr("data-trailer"),
                    element.attr("data-trailer-url"),
                    element.attr("data-youtube"),
                    element.attr("data-youtube-url"),
                    element.attr("data-video-url"),
                    element.attr("data-video"),
                    element.attr("href"),
                    element.attr("src"),
                ).firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.replace("\\/", "/")
                    ?.replace("&amp;", "&")
                    ?.trim('"', '\'')

                val normalized = extractTrailerUrl(candidate)
                if (!normalized.isNullOrBlank()) return normalized
            }
        }

        val html = document.html()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val trailerSection = Regex(
            """(?is)(?:fragman|trailer).{0,2500}"""
        ).find(html)?.value

        return extractTrailerUrl(trailerSection ?: html)
    }

    private fun extractTrailerUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null

        val source = value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val patterns = listOf(
            Regex(
                """https?://(?:www\.)?youtube\.com/(?:watch\?[^"'<>\\s]*v=|embed/)[A-Za-z0-9_-]+(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://(?:www\.)?youtu\.be/[A-Za-z0-9_-]+(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://(?:www\.)?youtube-nocookie\.com/embed/[A-Za-z0-9_-]+(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """https?://(?:www\.)?vimeo\.com/[0-9]+(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """(?:https?:)?//(?:www\.)?youtube\.com/(?:embed/|watch\?[^"'<>\\s]*v=)[A-Za-z0-9_-]+(?:\?[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE,
            ),
        )

        for (pattern in patterns) {
            val match = pattern.find(source)?.value ?: continue
            return if (match.startsWith("//")) "https:$match" else match
        }

        return null
    }

    private fun pageTitle(document: Document, url: String): String? =
        listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("title")?.text(),
            url.substringAfterLast('/').replace('-', ' '),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()

    private fun pagePlot(document: Document): String? =
        listOf(
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("div.wp-content")?.text(),
            document.selectFirst("div#info")?.text(),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()

    private fun posterOf(document: Document): String? {
        document.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalizeUrl(it, mainUrl) }

        return posterFromElement(
            document.selectFirst("div.poster img, .poster img, main img, article img, img"),
        )
    }

    private fun posterFromElement(image: Element?): String? {
        if (image == null) return null

        val raw = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-srcset").split(" ").firstOrNull { it.startsWith("http") }.orEmpty(),
            image.attr("srcset").split(" ").firstOrNull { it.startsWith("http") }.orEmpty(),
            image.attr("src"),
        ).firstOrNull {
            it.isNotBlank() && !it.startsWith("data:")
        }.orEmpty()

        return normalizeUrl(raw, mainUrl)
    }

    private fun pageYear(document: Document): Int? {
        val focused = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content"),
            document.selectFirst(".year")?.text(),
        )
            .filterNotNull()
            .joinToString(" ")

        return Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(focused)
            ?.value
            ?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? =
        extractRating(document.text())

    private fun extractRating(text: String?): Double? {
        if (text.isNullOrBlank()) return null

        val imdb = Regex(
            """(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

        return imdb ?: Regex(
            """(?<!\d)(?:19|20)\d{2}\s+([0-9](?:[.,][0-9])?)(?!\d)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }
    }

    private fun episodeNumbersFrom(text: String): Pair<Int, Int>? {
        val source = text
            .replace("\\/", "/")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val patterns = listOf(
            Regex("""(?ix)(?:sezon|season)\s*[-._ ]?\s*(\d+)\D{0,30}?(?:bölüm|bolum|episode)\s*[-._ ]?\s*(\d+)"""),
            Regex("""(?ix)(?:bölüm|bolum|episode)\s*[-._ ]?\s*(\d+)\D{0,30}?(?:sezon|season)\s*[-._ ]?\s*(\d+)"""),
            Regex("""(?ix)\b(\d+)\s*[xX]\s*(\d+)\b"""),
            Regex("""(?ix)[-/](\d+)-sezon-(\d+)-bolum(?:[/\-?]|$)"""),
            Regex("""(?ix)\b(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm\b"""),
        )

        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(source) ?: continue

            val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val second = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue

            return if (index == 1) second to first else first to second
        }

        return null
    }

    private fun cleanSeriesTitle(title: String): String =
        title
            .replace(Regex("""(?i)\s+\d+\.\s*Sezon.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\s*[xX]\s*\d+.*$"""), "")
            .trim()

    private fun String.cleanTitle(): String =
        replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)^(?:DUAL|DUBLAJ|ALTYAZI)\s*"""), "")
            .trim()

    private fun isEpisodeUrl(url: String): Boolean =
        runCatching {
            URI(url).path?.lowercase()?.startsWith("/bolum/") == true
        }.getOrDefault(false)

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (document.select("a[href]").any { link ->
                link.attr("rel").equals("next", true) ||
                    link.text().trim().equals("Sonraki", true) ||
                    link.text().trim().equals("Next", true)
            }
        ) return true

        val next = page + 1
        return document.select("a[href]").any { link ->
            val href = link.attr("href")
            href.contains("/page/" + next + "/") ||
                href.contains("page=" + next) ||
                href.contains("paged=" + next)
        }
    }

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.endsWith("/")) {
            base + "page/" + page + "/"
        } else {
            base + "/page/" + page + "/"
        }
    }

    private fun extractSubtitleCandidates(
        html: String,
        referer: String,
    ): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()

        fun add(rawUrl: String?, rawLabel: String?, rawLang: String? = null) {
            var url = rawUrl.orEmpty()
                .trim()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim('"', '\'')

            if (url.isBlank()) return

            url = normalizeUrl(url, referer)
            if (!url.startsWith("http", true)) return

            val lower = url.lowercase()
            val isSubtitle =
                lower.contains(".vtt") ||
                    lower.contains(".srt") ||
                    lower.contains(".ass") ||
                    lower.contains(".ssa") ||
                    lower.contains("subtitle") ||
                    lower.contains("subtitles") ||
                    lower.contains("caption") ||
                    lower.contains("captions") ||
                    lower.contains("/subs/") ||
                    lower.contains("sub.php")

            if (!isSubtitle) return

            val label = decodeLabel(
                rawLabel.orEmpty().ifBlank { rawLang.orEmpty() }
            ).ifBlank {
                when {
                    lower.contains("tur") ||
                        Regex("""(?:^|[^a-z])tr(?:[^a-z]|$)""").containsMatchIn(lower) ->
                        "Türkçe"

                    lower.contains("eng") ||
                        Regex("""(?:^|[^a-z])en(?:[^a-z]|$)""").containsMatchIn(lower) ->
                        "English"

                    else -> "Altyazı"
                }
            }

            found[url] = label
        }

        val fileLabelLang = Regex(
            """"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*,\s*"lang"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        for (match in fileLabelLang.findAll(html)) {
            add(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }

        val fileLabel = Regex(
            """"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        for (match in fileLabel.findAll(html)) {
            add(match.groupValues[1], match.groupValues[2])
        }

        val labelFile = Regex(
            """"label"\s*:\s*"([^"]+)"\s*,\s*"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        for (match in labelFile.findAll(html)) {
            add(match.groupValues[2], match.groupValues[1])
        }

        val trackBlock = Regex(
            """(?is)(?:subtitle|subtitles?|caption|captions?|tracks?)\s*:\s*(?:\[[^\]]*\]|\{[^}]*\})"""
        )

        val trackUrl = Regex(
            """(?i)"(?:file|src|url|source)"\s*:\s*"([^"]+)""""
        )

        val trackLabel = Regex(
            """(?i)"(?:label|lang|language|name)"\s*:\s*"([^"]+)""""
        )

        for (block in trackBlock.findAll(html)) {
            val value = block.value
            val label = trackLabel.find(value)?.groupValues?.getOrNull(1)

            for (urlMatch in trackUrl.findAll(value)) {
                add(urlMatch.groupValues[1], label)
            }
        }

        val scalar = Regex(
            """(?i)"(?:subtitle|subtitles?|caption|captions?|subtitle_url|subtitleUrl|caption_url|captionUrl|sub_url|subUrl)"\s*:\s*"([^"]+)""""
        )

        for (match in scalar.findAll(html)) {
            add(match.groupValues[1], null)
        }

        val bare = Regex(
            """(?i)(?:(?:https?:)?//|/)[^"'<>\\s]+(?:\.vtt|\.srt|\.ass|\.ssa)(?:\?[^"'<>\\s]*)?"""
        )

        for (match in bare.findAll(html)) {
            add(match.value, null)
        }

        return found.map { it.key to it.value }
    }

    private suspend fun emitSubtitle(
        url: String,
        lang: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return runCatching {
            subtitleCallback(
                newSubtitleFile(
                    lang = lang,
                    url = url,
                ) {
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                    )
                }
            )
            true
        }.getOrDefault(false)
    }

    private fun decodeLabel(value: String): String =
        value
            .replace("\\u00fc", "ü")
            .replace("\\u00e7", "ç")
            .replace("\\u011f", "ğ")
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u015f", "ş")
            .replace("\\/", "/")
            .trim()

    private fun xorBase64(
        cipherText: String,
        keyText: String,
    ): String? {
        return runCatching {
            val cipher = Base64.decode(cleanBase64(cipherText), Base64.DEFAULT)
            val key = Base64.decode(cleanBase64(keyText), Base64.DEFAULT)

            if (cipher.isEmpty() || key.isEmpty()) return@runCatching null

            val output = ByteArray(cipher.size)

            for (i in cipher.indices) {
                output[i] =
                    (cipher[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            String(output, Charsets.UTF_8)
                .split("|")
                .firstOrNull()
                ?.trim()
        }.getOrNull()
    }

    private fun cleanBase64(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\\\", "")
            .replace("\\", "")
            .replace("\"", "")
            .replace("'", "")
            .replace(Regex("""\s+"""), "")
            .replace('-', '+')
            .replace('_', '/')
            .filter {
                it.isLetterOrDigit() ||
                    it == '+' ||
                    it == '/' ||
                    it == '='
            }

    private fun makeXSp(sp: String, spT: Long): String {
        val rnd = java.lang.Long.toString(
            (Math.random() * 2176782336L).toLong(),
            36,
        )

        val input = sp + "|" + spT + "|" + rnd

        var hash = 2166136261L

        for (ch in input) {
            hash = (hash xor ch.code.toLong()) and 0xFFFFFFFFL
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }

        return spT.toString() + "." + rnd + "." + java.lang.Long.toHexString(hash)
    }

    private fun normalizeUrl(raw: String?, base: String): String {
        var value = raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim('"', '\'')

        return when {
            value.startsWith("//") -> "https:" + value

            value.startsWith("http://", true) ||
                value.startsWith("https://", true) -> value

            value.startsWith("/") ->
                (originOf(base) ?: mainUrl).trimEnd('/') + value

            value.isBlank() -> ""

            else -> runCatching {
                URI(base).resolve(value).toString()
            }.getOrElse {
                mainUrl.trimEnd('/') + "/" + value.removePrefix("./")
            }
        }
    }

    private fun originOf(url: String): String? =
        runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            scheme + "://" + host
        }.getOrNull()

    private fun String.decodeJsEscapes(): String =
        replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
}
