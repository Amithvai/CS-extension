package com.mwk.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Player Samehadaku (server wibufile):
 *
 *  - Server embed : https://api.wibufile.com/embed/<id>
 *    Halaman embed (≈39 KB) menyimpan video di JSON `file":"https:\/\/s0.wibufile.com\/...mp4"`.
 *    Ekstrak `file":"<url>"` lalu unescape `\/` -> `/`.
 *
 *  - Server CDN langsung : https://s0.wibufile.com/video01/<nama>.mp4
 *    Ini sudah link mp4 langsung (MP4HD = 480p, FULLHD = 1080p). Langsung dikembalikan.
 *
 * Kedua kelas di bawah meng-override extractor core CloudStream `Wibufile` (mainUrl
 * https://wibufile.com) karena loadExtractor iterasi terbalik -> extractor extension
 * (didaftarkan terakhir) menang.
 */

/** Embed api.wibufile.com -> ekstrak JSON `file":"..."`. */
class Wibufile : ExtractorApi() {
    override val name = "Wibufile"
    override val mainUrl = "https://api.wibufile.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer, timeout = 30_000L).textLarge

        val raw = Regex("""file"\s*:\s*"(.*?)"""").find(res)?.groupValues?.get(1)
            ?: Regex("""src:\s*['"](.*?)['"]""").find(res)?.groupValues?.get(1)
            ?: return

        val video = raw.replace("\\/", "/")

        callback.invoke(
            newExtractorLink(name, name, video) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

/** CDN langsung s0.wibufile.com/video01/NAMA.mp4 -> kembalikan apa adanya. */
class WibufileCdn : ExtractorApi() {
    override val name = "Wibufile"
    override val mainUrl = "https://s0.wibufile.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = when {
            url.contains("FULLHD", true) -> 1080
            url.contains("MP4HD", true) || url.contains("480", true) -> 480
            url.contains("720", true) -> 720
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(name, name, url) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )
    }
}
