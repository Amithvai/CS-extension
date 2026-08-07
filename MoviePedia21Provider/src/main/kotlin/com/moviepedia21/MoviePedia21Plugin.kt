package com.moviepedia21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviePedia21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviePedia21Provider())
    }
}
