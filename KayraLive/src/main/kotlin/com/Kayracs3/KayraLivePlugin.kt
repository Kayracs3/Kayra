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
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                maxLines = 1
                hint = "https://ornek.site/kanallar.m3u"
                setText(provider.configuredPlaylistUrl)
            }

            AlertDialog.Builder(settingsContext)
                .setTitle("KayraLive – Kanal Listesi")
                .setMessage(
                    "Yalnızca erişim ve yayın kullanım hakkına sahip olduğun HTTPS M3U/M3U8 listesini ekle. " +
                        "18+ / yetişkin olarak etiketlenen kanallar otomatik olarak gizlenir."
                )
                .setView(input)
                .setPositiveButton("Kaydet") { _, _ ->
                    val saved = provider.savePlaylistUrl(input.text?.toString().orEmpty())
                    Toast.makeText(
                        settingsContext,
                        if (saved) "Kanal listesi kaydedildi." else "Geçerli bir HTTPS M3U adresi gir.",
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
