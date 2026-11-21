package com.Kayracs3

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KayraLivePlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)

        val provider = KayraLive(context)
        registerMainAPI(provider)

        openSettings = { settingsContext ->
            val input = EditText(settingsContext).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSingleLine(false)
                maxLines = 6
                hint = "Her satıra bir HTTPS M3U adresi"
                setText(provider.configuredPlaylistUrl)
            }

            AlertDialog.Builder(settingsContext)
                .setTitle("KayraLive – Kanal Listesi")
                .setMessage(
                    "Erişim ve yayın kullanım hakkına sahip olduğun bir veya daha fazla HTTPS M3U/M3U8 adresi gir; her satıra bir adres yaz (en fazla 10). " +
                        "18+ / yetişkin olarak etiketlenen kanallar otomatik olarak gizlenir."
                )
                .setView(input)
                .setPositiveButton("Kaydet") { _, _ ->
                    val saved = provider.savePlaylistUrl(input.text?.toString().orEmpty())
                    Toast.makeText(
                        settingsContext,
                        if (saved) "Kanal listeleri kaydedildi." else "Geçerli HTTPS M3U adresleri gir; her satıra bir tane (en fazla 10).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .setNeutralButton("Temizle") { _, _ ->
                    provider.clearPlaylistUrl()
                    Toast.makeText(settingsContext, "Kanal listesi silindi.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }
}
