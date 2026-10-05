package com.Kayracs3

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DizigecesiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dizigecesi())
    }
}
