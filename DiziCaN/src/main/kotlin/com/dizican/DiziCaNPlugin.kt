package com.dizican

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziCaNPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziCaNProvider())
    }
}
