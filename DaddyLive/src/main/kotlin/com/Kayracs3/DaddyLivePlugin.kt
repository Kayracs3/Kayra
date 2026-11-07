package com.Kayracs3

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DaddyLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DaddyLiveProvider())

        openSettings = { settingsContext ->
            val input = EditText(settingsContext).apply {
                hint = "API anahtarı"
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            val hasKey = DaddyLiveSettings.getApiKey() != null
            val info = TextView(settingsContext).apply {
                text = if (hasKey) {
                    "Bir API anahtarı kayıtlı. Yeni bir anahtar girerek değiştirebilirsin."
                } else {
                    "Kanal listesini almak için site yönetiminden verilen API anahtarını gir."
                }
            }

            val padding = (20 * settingsContext.resources.displayMetrics.density).toInt()
            val content = LinearLayout(settingsContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, 0)
                addView(info)
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            AlertDialog.Builder(settingsContext)
                .setTitle("DaddyLive ayarları")
                .setMessage(
                    "API anahtarı cihazında saklanır; kaynak koduna veya derleme dosyasına eklenmez."
                )
                .setView(content)
                .setPositiveButton("Kaydet") { _, _ ->
                    val value = input.text?.toString()?.trim().orEmpty()
                    if (value.isNotBlank()) {
                        DaddyLiveSettings.saveApiKey(value)
                        MainActivity.reloadHomeEvent.invoke(true)
                    }
                }
                .setNeutralButton("Anahtarı sil") { _, _ ->
                    DaddyLiveSettings.clearApiKey()
                    MainActivity.reloadHomeEvent.invoke(true)
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }
}
