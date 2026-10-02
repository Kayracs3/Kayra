package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebDramaTurkeyPlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)
        registerMainAPI(WebDramaTurkey())
        registerExtractorAPI(WebDramaTurkeyExtractor())
    }
}
