package com.klikxxi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson


class Hexload : ExtractorApi() {
    override val name = "Hexload"
    override val mainUrl = "https://hexload.com"
    override val requiresReferer = true

    private data class HexloadResponse(
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("result") val result: HexloadResult? = null,
        @JsonProperty("status") val status: Int? = null
    )

    private data class HexloadResult(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("md5") val md5: String? = null,
        @JsonProperty("size") val size: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("content_type") val contentType: String? = null,
        @JsonProperty("file_name") val fileName: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("embed-(\\w+)").find(url)?.groupValues?.get(1) ?: return

        val response = app.post(
            "$mainUrl/download",
            data = mapOf(
                "op" to "download3",
                "id" to fileId,
                "ajax" to "1",
                "method_free" to "1"
            ),
            headers = mapOf(
                "Referer" to "$mainUrl/embed-$fileId",
                "Origin" to mainUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            timeout = 15_000L
        ).text

        val data = tryParseJson<HexloadResponse>(response) ?: return
        val videoUrl = data.result?.url ?: return

        val quality = data.result.fileName?.let { fileName ->
            when {
                fileName.contains("-FHD", ignoreCase = true) -> "1080p"
                fileName.contains("-HD", ignoreCase = true) -> "720p"
                else -> "720p"
            }
        } ?: "720p"

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl,
            ) {
                this.referer = referer ?: url
                this.quality = getQualityFromName(quality)
            }
        )
    }
}


class Klixxistrp2p : VidStack() {
    override var name = "Klixxistrp2p"
    override var mainUrl = "https://klikxxi.strp2p.site"
    override var requiresReferer = true
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Klixxiupns : VidStack() {
    override var name = "Klixxiupns"
    override var mainUrl = "https://klikxxi.upns.one"
    override var requiresReferer = true
}

