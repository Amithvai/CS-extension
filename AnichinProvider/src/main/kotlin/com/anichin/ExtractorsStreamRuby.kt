package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink

open class StreamRuby : ExtractorApi() {
    companion object {
        private val EMBED_ID_REGEX = "embed-([a-zA-Z0-9]+)\\.html".toRegex()
        private val M3U8_FILE_REGEX = Regex("""file:\s*"([^"]*\.m3u8[^"]*)""")
    }

    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = EMBED_ID_REGEX.find(url)?.groupValues?.get(1) ?: return
        val response = app.post(
            "$mainUrl/dl", data = mapOf(
                "op" to "embed",
                "file_code" to id,
                "auto" to "1",
                "referer" to "",
            ), referer = referer, timeout = 15_000L
        )
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = M3U8_FILE_REGEX.find(script ?: return)?.groupValues?.getOrNull(1) ?: return
        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url  = m3u8,
            type = ExtractorLinkType.M3U8,
            {
                quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        ))
    }
}

class svanila : StreamRuby() {
    override var name = "svanila"
    override var mainUrl = "https://streamruby.net"
}

class svilla : StreamRuby() {
    override var name = "svilla"
    override var mainUrl = "https://streamruby.com"
}
