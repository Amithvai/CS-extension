package com.onetouchtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OneTouchTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OneTouchTV())
    }
}
