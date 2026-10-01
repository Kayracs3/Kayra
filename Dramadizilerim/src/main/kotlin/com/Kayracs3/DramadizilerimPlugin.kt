package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramadizilerimPlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)
        registerMainAPI(Dramadizilerim())
    }
}
