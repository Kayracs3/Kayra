package com.Kayracs3.daddylive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DaddyLivePlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)
        registerMainAPI(DaddyLive())
        registerExtractorAPI(DaddyLivePlayerExtractor())
    }
}
