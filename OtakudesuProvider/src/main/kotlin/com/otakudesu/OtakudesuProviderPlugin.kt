package com.otakudesu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*
import android.content.Context

@CloudstreamPlugin
class OtakuDesuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OtakudesuProvider())
        // Player milik OtakuDesu (desustream.net / desustream.com)
        registerExtractorAPI(DesuStreamPlayer())
        registerExtractorAPI(DesuStreamCom())
        // Host mirror aktif saat ini
        registerExtractorAPI(Filedon())
        registerExtractorAPI(OdVidHide())
        registerExtractorAPI(VidHideFast())
        // (VidhidePacked adalah base class OdVidHide/VidHideFast — jangan didaftarkan)
        // Host download yang masih hidup
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Acefile())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Mediafire())
        registerExtractorAPI(Krakenfiles())
    }
}
