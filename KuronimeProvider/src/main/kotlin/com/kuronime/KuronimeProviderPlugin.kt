package com.kuronime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*
import android.content.Context

@CloudstreamPlugin
class KuronimeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KuronimeProvider())
        // Host mirror aktif dari API animeku (cek 2026-10): embed = pixeldrain,
        // mp4upload, krakenfiles, filelions (vidhidevip.com), doodstream;
        // download = gofile + yang sama dengan embed.
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(VidHidePro5())
        // Cadangan domain filelions kalau situs berpindah host
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        // Doodstream domain berputar; core tidak mencakup domain di bawah
        registerExtractorAPI(Dooood())
        registerExtractorAPI(D000d())
        registerExtractorAPI(Ds2Play())
    }
}
