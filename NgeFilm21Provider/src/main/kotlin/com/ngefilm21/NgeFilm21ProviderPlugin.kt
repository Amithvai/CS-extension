package com.ngefilm21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Ngefilm21ProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Ngefilm21Provider())
        // Player Abyss (abyssplayer.com) — dipakai server 2
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssPlayerExtractor())
    }
}
