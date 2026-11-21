package com.Kayracs3

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KoalaTVPlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)
        val prefs = context.getSharedPreferences("koalatv_prefs", Context.MODE_PRIVATE)
        val provider = KoalaTVProvider(prefs)
        registerMainAPI(provider)

        openSettings = { settingsContext ->
            val choices = KoalaTVProvider.COUNTRY_OPTIONS
            val selected = provider.enabledCountries().toMutableSet()
            val labels = choices.map { it.second }.toTypedArray()
            val checked = BooleanArray(choices.size) { choices[it].first in selected }

            AlertDialog.Builder(settingsContext)
                .setTitle("🐨 KoalaTV - Ülke & Bölge Seçimi")
                .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                    val code = choices[index].first
                    if (isChecked) selected.add(code) else selected.remove(code)
                }
                .setPositiveButton("💾 Kaydet") { _, _ ->
                    provider.saveCountries(selected)
                    Toast.makeText(
                        settingsContext,
                        "KoalaTV ülke tercihleri kaydedildi.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }
}
