package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class ContentX : ExtractorApi() {

    override val name = "ContentX"
    override val mainUrl = "https://contentx.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""

        Log.d("Kekik_${this.name}", "url » $url")

        // Ana oynatıcı sayfası
        val iSource = app.get(
            url,
            referer = extRef
        ).text

        // Video ID
        val iExtract = Regex(
            """window\.openPlayer\('([^']+)'"""
        ).find(iSource)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("iExtract is null")

        // ---------------------------------------------------------
        // ALTYAZILAR
        // ---------------------------------------------------------

        val subUrls = mutableSetOf<String>()

        Regex(
            """"file":"([^"]+)","label":"([^"]+)""""
        ).findAll(iSource).forEach { match ->

            val subUrl = match.groupValues.getOrNull(1)
                ?: return@forEach

            val subLang = match.groupValues.getOrNull(2)
                ?: return@forEach

            if (!subUrls.add(subUrl)) {
                return@forEach
            }

            val cleanSubUrl = subUrl.replace("\\", "")

            val language = subLang
                .replace("\\u0131", "ı")
                .replace("\\u0130", "İ")
                .replace("\\u00fc", "ü")
                .replace("\\u00e7", "ç")
                .replace("\\u00f6", "ö")
                .replace("\\u011f", "ğ")
                .replace("\\u015f", "ş")
                .replace("\\u00dc", "Ü")
                .replace("\\u00d6", "Ö")
                .replace("\\u00c7", "Ç")
                .replace("\\u011e", "Ğ")
                .replace("\\u015e", "Ş")

            Log.d(
                "Kekik_${this.name}",
                "subtitle » $language -> $cleanSubUrl"
            )

            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = language,
                    url = fixUrl(cleanSubUrl)
                )
            )
        }

        // ---------------------------------------------------------
        // ANA VİDEO
        // ---------------------------------------------------------

        val vidSource = app.get(
            "${mainUrl}/source2.php?v=${iExtract}",
            referer = extRef
        ).text

        val vidExtract = Regex(
            """"file":"([^"]+)""""
        ).find(vidSource)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("vidExtract is null")

        val m3uLink = vidExtract.replace("\\", "")

        Log.d(
            "Kekik_${this.name}",
            "video » $m3uLink"
        )

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )

        // ---------------------------------------------------------
        // TÜRKÇE DUBLAJ
        // ---------------------------------------------------------

        val iDublaj = Regex(
            ""","([^']+)","Türkçe"""
        ).find(iSource)?.groupValues?.getOrNull(1)

        if (!iDublaj.isNullOrBlank()) {

            val dublajSource = app.get(
                "${mainUrl}/source2.php?v=${iDublaj}",
                referer = extRef
            ).text

            val dublajExtract = Regex(
                """"file":"([^"]+)""""
            ).find(dublajSource)?.groupValues?.getOrNull(1)
                ?: throw ErrorLoadingException("dublajExtract is null")

            val dublajLink = dublajExtract.replace("\\", "")

            Log.d(
                "Kekik_${this.name}",
                "dublaj » $dublajLink"
            )

            callback.invoke(
                newExtractorLink(
                    source = "${this.name} Türkçe Dublaj",
                    name = "${this.name} Türkçe Dublaj",
                    url = dublajLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
