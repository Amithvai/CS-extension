package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URI

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = app.get(metaDataUrl, referer = embedUrl, timeout = 15_000L).text

        val urls = QUALITY_URL_REGEX.findAll(response)
            .map { it.groupValues[1] }
            .toList().filter { it.contains(".m3u8") }
        urls.forEach { videoUrl ->
            getStream(videoUrl, this.name, callback)
        }

        extractSubtitles(response, subtitleCallback)
    }

    private suspend fun extractSubtitles(
        response: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        SUBTITLES_REGEX.find(response)?.let { match ->
            val subtitlesJson = match.groupValues[1]
                .replace(URLS_SINGLE_REGEX) { urlsMatch ->
                    val url = URL_BRACKET_REGEX.find(urlsMatch.value)?.groupValues?.get(1) ?: return@replace urlsMatch.value
                    """"urls":[{"url":"$url"}]"""
                }
                .replace(URLS_MIXED_REGEX) { arrMatch ->
                    val items = arrMatch.groupValues[1].split(",").map { it.trim().trim('"') }
                    val objects = items.joinToString(",") { """{"url":"$it"}""" }
                    """"urls":[$objects]"""
                }
            val subsData = tryParseJson<Map<String, List<DmSubtitleEntry>>>(subtitlesJson)
            subsData?.forEach { (_, entries) ->
                entries.forEach { entry ->
                    entry.urls?.firstOrNull()?.url?.let { url ->
                        entry.label?.let { label ->
                            subtitleCallback(newSubtitleFile(label, url))
                        }
                    }
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    private suspend fun getStream(
        streamLink: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        return generateM3u8(name, streamLink, "").forEach(callback)
    }

    data class DmSubtitleEntry(
        val label: String? = null,
        val urls: List<DmSubUrl>? = null,
    )

    data class DmSubUrl(
        val url: String? = null,
    )

    companion object {
        private val QUALITY_URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
        private val SUBTITLES_REGEX = Regex(""""subtitles"\s*:\s*(\{(?:[^{}]|"[^"]*")*?\})""")
        private val URLS_SINGLE_REGEX = Regex(""""urls"\s*:\s*\["[^"]*"\]""")
        private val URL_BRACKET_REGEX = Regex("""\["([^"]+)"\]""")
        private val URLS_MIXED_REGEX = Regex(""""urls"\s*:\s*\[([^\]]+)\]""")
    }
}
