package com.pencurimovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PencurimoviePluginProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PencurimovieProvider())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(MixDropTop())
        registerExtractorAPI(VoeSx())
        registerExtractorAPI(StreamTapeCom())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Hgcloud())
    }
}
