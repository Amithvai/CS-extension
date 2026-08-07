package com.mwk.shared

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SharedModulePlugin : Plugin() {
    override fun load(context: Context) {
        // Library module — no providers to register
    }
}