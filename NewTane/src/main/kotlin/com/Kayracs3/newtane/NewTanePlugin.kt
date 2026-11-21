package com.Kayracs3.newtane

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NewTanePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NewTaneProvider())
    }
}
