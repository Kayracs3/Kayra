package com.Kayracs3

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BeyazelmaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Beyazelma())
    }
}
