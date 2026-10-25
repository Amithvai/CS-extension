package com.ngefilm21

import com.lagradost.cloudstream3.extractors.DoodLaExtractor

/**
 * playmogo.com adalah rebranding baru keluarga DoodStream (terlihat di
 * Server 4 halaman film terbaru). Alur standar dood (/e/ -> /pass_md5/ ->
 * suffix token+expiry) — core hanya punya dood.la/.watch/dll, jadi butuh
 * extractor sendiri.
 */
class Playmogo : DoodLaExtractor() {
    override var name = "Doodstream"
    override var mainUrl = "https://playmogo.com"
}
