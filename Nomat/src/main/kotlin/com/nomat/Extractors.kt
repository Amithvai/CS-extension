package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.extractors.VidHidePro

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    companion object {
        private val M3U8_SRC_REGEX = Regex("\"(https?://[^\"]+\\.m3u8[^\"]*)\"")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        try {
            val response = app.get(getEmbedUrl(url), referer = referer, timeout = 15_000L)
            val script = if (!getPacked(response.text).isNullOrEmpty()) {
                var result = getAndUnpack(response.text)
                if (result.isNullOrBlank()) {
                    logError(Exception("$name: unpack returned empty for $url"))
                    return
                }
                if (result.contains("var links")) result = result.substringAfter("var links")
                result
            } else {
                response.document.selectFirst("script:containsData(sources:)")?.data()
            } ?: return

            M3U8_SRC_REGEX.findAll(script).forEach { match ->
                M3u8Helper.generateM3u8(
                    name,
                    fixUrl(match.groupValues[1]),
                    referer = "$mainUrl/",
                    headers = headers
                ).forEach(callback)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}

class Hydrax : VidHidePro() {
    override var name = "Hydrax"
    override var mainUrl = "https://playhydrax.com"
}

class FilelionsTo : VidHidePro() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.to"
}
