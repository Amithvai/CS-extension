package com.dutamovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DutaMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DutaMovie())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Embedpyrox())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Luluvdoo())
        registerExtractorAPI(Veev())
        registerExtractorAPI(P2PPlay())
        registerExtractorAPI(Video4Me())
        registerExtractorAPI(Hanerix())
        registerExtractorAPI(Masukestin())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dm21upns())
        registerExtractorAPI(Embed4meVip())
        registerExtractorAPI(LivePlayerP2P())
        registerExtractorAPI(Gofile())
    }
}
