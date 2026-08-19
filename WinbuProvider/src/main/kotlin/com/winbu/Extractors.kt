package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

/** Player P2P Winbu (winbu.strp2p.com) — diproses oleh core extractor VidStack. */
class Winbustrp2p : VidStack() {
    override var name = "WinbuP2P"
    override var mainUrl = "https://winbu.strp2p.com"
    override var requiresReferer = true
}

/**
 * Player Filedon — halaman embed menyimpan URL file (presigned R2) di props
 * Inertia `#app[data-page]` yang sudah HTML-unescape oleh Jsoup.
 */
class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = true

    private val hlsUrlRegex = Regex("""\"hls_url\":\"(https:\\/\\/[^"]+\.m3u8[^"]*)""")
    private val videoUrlRegex = Regex("""\"url\":\"(https:\\/\\/[^"]+\.mp4[^"]*)""")
    private val nameRegex = Regex("""\"name\":\"([^"]+\.mp4)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: "$mainUrl/", timeout = 30_000L).document
        val json = document.selectFirst("#app")?.attr("data-page") ?: return

        hlsUrlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/")?.let { hlsUrl ->
            generateM3u8(
                name,
                fixUrl(hlsUrl),
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).forEach(callback)
            return
        }

        val rawUrl = videoUrlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        val fileName = nameRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        val quality = fileName?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

        callback.invoke(
            newExtractorLink(name, name, rawUrl) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )
    }
}