package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatBoxYedekPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(InatBoxYedek())
        registerExtractorAPI(InatBoxYedekDiskYandexComTr())
        registerExtractorAPI(InatBoxYedekVk())
        registerExtractorAPI(InatBoxYedekDzen())
        registerExtractorAPI(InatBoxYedekCDNJWPlayer())
    }
}
