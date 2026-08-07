package com.donghub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class Odnoklassniki : ExtractorApi() {
    override val name = "Odnoklassniki"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val embedUrl = url.replace("/video/", "/videoembed/")
        val videoReq = try {
            app.get(embedUrl, headers = headers, timeout = 15_000L).text
        } catch (_: Exception) {
            throw ErrorLoadingException("Failed to fetch OK.ru embed page")
        }
        val decoded = videoReq
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(UNICODE_ESC_REGEX) { matchResult ->
                val code = matchResult.groupValues[1].toInt(16)
                if (code in 0xD800..0xDFFF) matchResult.value
                else code.toChar().toString()
            }

        val videosStr = VIDEOS_JSON_REGEX.find(decoded)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video not found")
        val videos = AppUtils.tryParseJson<List<OkRuVideo>>(videosStr)
            ?: throw ErrorLoadingException("Video not found")

        for (video in videos) {
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url

            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "4k")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    companion object {
        private val UNICODE_ESC_REGEX = Regex("\\\\u([0-9A-Fa-f]{4})")
        private val VIDEOS_JSON_REGEX = Regex(""""videos":(\[[^]]*])""")
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
    )
}
