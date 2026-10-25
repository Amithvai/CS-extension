package com.nomat

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NomatPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nomat())
        // Extractor aktif (auditor 2026-10-25): filelions hidup via VidHidePro,
        // filemoon.sx butuh FileMoonByse khusus (captcha POW + AES-GCM),
        // streamwish/streamhide muncul sporadis di server list.
        // Registrasi mati (Dingtezuni/Earnvids/Ryderjet/Hydrax) dibuang.
        registerExtractorAPI(FileMoonByse())
        registerExtractorAPI(FilelionsTo())
        registerExtractorAPI(StreamWishTo())
        registerExtractorAPI(StreamHideTo())
    }
}
