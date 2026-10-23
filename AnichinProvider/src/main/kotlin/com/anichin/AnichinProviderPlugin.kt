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

        // PERUBAHAN 2026-10-23: mirror option anichin.moe kini memuat server
        // baru → extractor lama yang tidak pernah diregistrasi kini aktif,
        // plus extractor baru untuk host baru:
        //  - player.nunadrama.sbs  -> wrapper geo.dailymotion.com
        //  - playmogo.com          -> DoodStream family
        //  - player.abyssplayer.com-> player Abyss (AES-CTR, port dari NgeFilm21)
        //  - rumble.com/embed      -> HLS playlist (format JSON halaman baru)
        registerExtractorAPI(Rumble())
        registerExtractorAPI(DoodAnichin())
        registerExtractorAPI(NunaDrama())
        registerExtractorAPI(AbyssPlayerAnichin())

        // Family StreamRuby & VidGuard: belum muncul di mirror saat ini,
        // tapi diset lama di provider ini — aktifkan saja bila muncul kembali.
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(svanila())
        registerExtractorAPI(svilla())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3())
    }
}
