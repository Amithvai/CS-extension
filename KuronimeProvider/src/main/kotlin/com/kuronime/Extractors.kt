package com.kuronime

import com.lagradost.cloudstream3.extractors.DoodLaExtractor

/**
 * Mirror doodstream dari API animeku memakai domain yang berputar:
 * dsvplay.com (mati 2026-10) -> dooood.com / d000d.com. Core hanya punya
 * dood.la/.wf/.to/dll, jadi domain ini perlu extractor sendiri.
 */
class Dooood : DoodLaExtractor() {
    override var name = "Doodstream"
    override var mainUrl = "https://dooood.com"
}

class D000d : DoodLaExtractor() {
    override var name = "Doodstream"
    override var mainUrl = "https://d000d.com"
}

/** Cadangan domain dood yang historis dipakai situs keluarga animeku. */
class Ds2Play : DoodLaExtractor() {
    override var name = "Doodstream"
    override var mainUrl = "https://ds2play.com"
}
