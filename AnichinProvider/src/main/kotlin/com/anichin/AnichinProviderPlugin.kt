package com.anichin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnichinProvider())

        // Extractor khusus provider Anichin.
        // Sebelumnya class-class ini ADA tapi TIDAK PERNAH diregistrasi,
        // sehingga loadExtractor() jatuh ke extractor core:
        //  - Dailymotion core  -> video tanpa suara (audio track terpisah)
        //  - Odnoklassniki core -> gagal total (parser "videos" sudah usang)
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
    }
}
