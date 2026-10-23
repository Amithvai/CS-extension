package com.donghub

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Morencius (engine VidHide) — embed page berisi script packed yang
 * menyimpan link HLS langsung: {sub}.dramiyos-cdn.com/hls2/.../master.m3u8
 * Token bertanda tangan & kedaluwarsa cepat, jadi harus selalu live.
 */
class Morencius : ExtractorApi() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    private companion object {
        val M3U8_REGEX = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl, timeout = 15_000L).text
        val unpacked = getAndUnpack(html)
        val sources = M3U8_REGEX.findAll(
            if (unpacked.isNotBlank()) unpacked else html
        ).map { it.value }.distinct().toList()

        if (sources.isEmpty()) {
            throw ErrorLoadingException("Morencius: link HLS tidak ditemukan")
        }

        for (source in sources) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = source,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    quality = Qualities.Unknown.value
                }
            )
        }
    }
}
