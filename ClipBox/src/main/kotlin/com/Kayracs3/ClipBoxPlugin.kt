package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ClipBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ClipBox())
    }
}
