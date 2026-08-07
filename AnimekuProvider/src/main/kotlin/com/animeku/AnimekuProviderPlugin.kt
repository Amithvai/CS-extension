package com.animeku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimekuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimekuProvider())
    }
}
