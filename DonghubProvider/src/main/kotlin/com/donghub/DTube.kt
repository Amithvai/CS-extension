package com.donghub

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * play.d.tube — player DTube (terverifikasi via https://api.d.tube/videos/{id})
 * Response JSON berisi video_url master.m3u8 di nas1/nas2.d.tube (CORS *).
 */
class DTube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    private companion object {
        val ID_REGEX = Regex("""[?&]v=([^&]+)""")
        val API = "https://api.d.tube/videos"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = ID_REGEX.find(url)?.groupValues?.get(1)?.ifBlank { null }
            ?: throw ErrorLoadingException("DTube: id video tidak ditemukan")

        val response = app.get("$API/$id", timeout = 15_000L).text
        val meta = tryParseJson<DTubeMeta>(response)
            ?: throw ErrorLoadingException("DTube: gagal parse metadata")

        val videoUrl = meta.videoUrl
            ?: throw ErrorLoadingException("DTube: video tidak tersedia (status ${meta.status})")

        var linkFound = false
        try {
            generateM3u8(name, videoUrl, mainUrl).forEach {
                callback(it)
                linkFound = true
            }
        } catch (_: Exception) {}

        if (!linkFound) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    quality = Qualities.Unknown.value
                }
            )
        }
    }

    data class DTubeMeta(
        val videoUrl: String? = null,
        val title: String? = null,
        val status: String? = null
    )
}
