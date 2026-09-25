package com.Kayracs3

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.lang.Math.floorMod

open class HCCloseLoadExtractor : ExtractorApi() {

    override val name =
        "CloseLoad"

    override val mainUrl =
        "https://hdfilmcehennemi.mobi"

    override val requiresReferer =
        true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val extRef =
            referer ?: ""

        Log.d(
            "HDCH",
            "CloseLoad URL » $url"
        )

        val response =
            app.get(
                url,
                referer = extRef
            )

        // -----------------------------------------------------
        // NORMAL TRACK ALTYAZILARI
        // -----------------------------------------------------

        response.document
            .select("track")
            .forEach { track ->

                val subtitlePath =
                    track.attr("src")

                if (
                    subtitlePath.isNotBlank()
                ) {

                    val subtitleUrl =
                        if (
                            subtitlePath.startsWith(
                                "http://"
                            ) ||
                            subtitlePath.startsWith(
                                "https://"
                            )
                        ) {

                            subtitlePath

                        } else {

                            "$mainUrl$subtitlePath"
                        }

                    subtitleCallback(
                        SubtitleFile(
                            lang =
                                track.attr("label")
                                    .ifBlank {
                                        "Türkçe"
                                    },
                            url =
                                subtitleUrl
                        )
                    )
                }
            }

        // -----------------------------------------------------
        // PACKED SCRIPT
        // -----------------------------------------------------

        val packedScript =
            response.document
                .select("script")
                .find {
                    it.data().contains(
                        "eval(function(p,a,c,k,e",
                        ignoreCase = true
                    )
                }
                ?.data()
                ?.trim()

        if (
            packedScript.isNullOrBlank()
        ) {

            Log.d(
                "HDCH",
                "CloseLoad packed script bulunamadı"
            )

            return
        }

        try {

            val rawScript =
                getAndUnpack(
                    packedScript
                )

            Log.d(
                "HDCH",
                "CloseLoad unpacked length » ${rawScript.length}"
            )

            // =================================================
            // DC_HELLO
            // =================================================

            if (
                rawScript.contains(
                    "dc_hello",
                    ignoreCase = true
                )
            ) {

                val regex =
                    Regex(
                        """dc_hello\("([^"]*)"\)""",
                        RegexOption.IGNORE_CASE
                    )

                val match =
                    regex.find(
                        rawScript
                    )

                if (
                    match != null
                ) {

                    val encoded =
                        match
                            .groupValues[1]

                    val decoded =
                        dcHello(
                            encoded
                        )

                    val mediaUrl =
                        decoded
                            .substringAfter(
                                "http",
                                ""
                            )
                            .let {
                                if (it.isBlank()) {
                                    decoded
                                } else {
                                    "http$it"
                                }
                            }

                    Log.d(
                        "HDCH",
                        "dc_hello media » $mediaUrl"
                    )

                    if (
                        mediaUrl.startsWith(
                            "http://"
                        ) ||
                        mediaUrl.startsWith(
                            "https://"
                        )
                    ) {

                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = mediaUrl,
                                type = ExtractorLinkType.M3U8
                            ) {

                                this.referer =
                                    mainUrl

                                this.quality =
                                    Qualities.Unknown.value
                            }
                        )

                        return
                    }
                }
            }

            // =================================================
            // DC_* YENİ SİSTEM
            // =================================================

            val dcRegex =
                Regex(
                    """dc_\w+\(\[(.*?)\]\)""",
                    RegexOption.DOT_MATCHES_ALL
                )

            val dcMatch =
                dcRegex.find(
                    rawScript
                )

            if (
                dcMatch != null
            ) {

                val group =
                    dcMatch
                        .groupValues[1]

                val parts =
                    group
                        .split(",")
                        .map {
                            it
                                .trim()
                                .removeSurrounding(
                                    "\""
                                )
                        }

                Log.d(
                    "HDCH",
                    "dc parts » $parts"
                )

                val mediaUrl =
                    dcNew(
                        parts
                    )

                Log.d(
                    "HDCH",
                    "dc_new media » $mediaUrl"
                )

                if (
                    mediaUrl.startsWith(
                        "http://"
                    ) ||
                    mediaUrl.startsWith(
                        "https://"
                    )
                ) {

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = mediaUrl,
                            type = ExtractorLinkType.M3U8
                        ) {

                            this.referer =
                                mainUrl

                            this.quality =
                                Qualities.Unknown.value
                        }
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                "HDCH",
                "CloseLoad extraction error",
                e
            )
        }
    }

    // =========================================================
    // DC HELLO
    // =========================================================

    private fun dcHello(
        base64Input: String
    ): String {

        val decodedOnce =
            base64Decode(
                base64Input
            )

        val reversedString =
            decodedOnce.reversed()

        val decodedTwice =
            base64Decode(
                reversedString
            )

        return when {

            decodedTwice.contains("+") ->
                decodedTwice.substringAfterLast(
                    "+"
                )

            decodedTwice.contains(" ") ->
                decodedTwice.substringAfterLast(
                    " "
                )

            decodedTwice.contains("|") ->
                decodedTwice.substringAfterLast(
                    "|"
                )

            else ->
                decodedTwice
        }
    }

    // =========================================================
    // DC NEW
    // =========================================================

    private fun dcNew(
        parts: List<String>
    ): String {

        val value =
            parts.joinToString("")

        val decodedBytes =
            base64DecodeArray(
                value
            )

        var result =
            String(
                decodedBytes,
                Charsets.ISO_8859_1
            )
                .reversed()

        val rot13 =
            StringBuilder()

        for (char in result) {

            when (char) {

                in 'a'..'z' -> {

                    val newChar =
                        char + 13

                    rot13.append(
                        if (
                            newChar > 'z'
                        ) {

                            newChar - 26

                        } else {

                            newChar
                        }
                    )
                }

                in 'A'..'Z' -> {

                    val newChar =
                        char + 13

                    rot13.append(
                        if (
                            newChar > 'Z'
                        ) {

                            newChar - 26

                        } else {

                            newChar
                        }
                    )
                }

                else -> {

                    rot13.append(
                        char
                    )
                }
            }
        }

        result =
            rot13.toString()

        val unmix =
            StringBuilder()

        for (
            (index, char)
            in result.withIndex()
        ) {

            val charCode =
                char.code

            val offset =
                399756995 % (
                    index + 5
                )

            val newCharCode =
                floorMod(
                    charCode - offset,
                    256
                )

            unmix.append(
                newCharCode.toChar()
            )
        }

        return unmix.toString()
    }
}
