package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble embed (rumble.com/embed/<id>).
 *
 * PERUBAHAN 2026-10-23: struktur JSON halaman embed berubah — dulu berisi
 * blok {"mp4":{...}} dengan kumpulan URL per kualitas, sekarang field HLS
 * ada di "hls":{"url":"https://rumble.com/hls-vod/<hash>/playlist.m3u8"}.
 * Regex lama ("{\"mp4") tidak menemukan apa-apa lagi → extractor dead.
 *
 * Regexp baru mencari field "hls" lalu meng-unescape "\/".
 */
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private val HLS_URL_REGEX = Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/", timeout = 15_000L)
        val script = response.document.selectFirst("script:containsData(mp4):containsData(hls)")?.data()
            ?: response.text

        val m3u8 = HLS_URL_REGEX.find(script)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
}
