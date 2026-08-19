package com.winbu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WinbuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WinbuProvider())
        registerExtractorAPI(Winbustrp2p())
        registerExtractorAPI(Filedon())
    }
}