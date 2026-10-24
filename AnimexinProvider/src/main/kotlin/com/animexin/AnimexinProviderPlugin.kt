package com.animexin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimexinProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimexinProvider())

        // Host yang benar-benar muncul di halaman episode animexin.dev
        // (diverifikasi 2026-10-24): geo.dailymotion.com, odysee.com, ok.ru,
        // play.d.tube, rumble.com/embed, playmogo.com.
        //
        // Dailymotion & Odnoklassniki memakai versi perbaikan lokal
        // (audio fMP4 terpisah / parser data-options), bukan versi core.
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Odysee())
        registerExtractorAPI(DTube())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(DoodAnimexin())

        // Extractor cadangan dari repo referensi AnimeX (bisa muncul
        // di episode lama / mirror lain).
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(waaw())
        registerExtractorAPI(FileMoonSx())
    }
}
