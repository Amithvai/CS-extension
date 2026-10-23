package com.donghub

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

/**
 * Odysee via LBRY proxy — embed URL dari donghive sudah menyertakan
 * `signature` + `signature_ts` yang wajib untuk m=get.
 * POST https://api.na-backend.odysee.com/api/v1/proxy?m=get ->
 * { result: { streaming_url } } (mp4 signed di secure.odycdn.com)
 */
class Odysee : ExtractorApi() {
    override val name = "Odysee"
    override val mainUrl = "https://odysee.com"
    override val requiresReferer = false

    private companion object {
        val API = "https://api.na-backend.odysee.com/api/v1/proxy?m=get"
        val PATH_REGEX = Regex("""/(?:\$|%24)embed/([^?]*)""")
        val SIG_REGEX = Regex("""(?:\?|&)signature=([^&]+)""")
        val SIG_TS_REGEX = Regex("""(?:\?|&)signature_ts=([^&]+)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val claimPath = PATH_REGEX.find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw ErrorLoadingException("Odysee: claim tidak ditemukan di URL")
        val signature = SIG_REGEX.find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Odysee: signature hilang di URL")
        val signatureTs = SIG_TS_REGEX.find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Odysee: signature_ts hilang di URL")

        val body = mapOf(
            "jsonrpc" to "2.0",
            "method" to "get",
            "id" to 1,
            "params" to mapOf(
                "uri" to "lbry://$claimPath",
                "save_file" to false,
                "signature" to signature,
                "signature_ts" to signatureTs,
            ),
        )

        val response = app.post(
            API,
            json = body,
            timeout = 30_000L
        )
        if (!response.isSuccessful) {
            throw ErrorLoadingException("Odysee: HTTP ${response.code}")
        }

        val parsed = tryParseJson<OdyseeRpcResponse>(response.text)
            ?: throw ErrorLoadingException("Odysee: gagal parse response LBRY")
        val streamingUrl = parsed.result?.streamingUrl
            ?: throw ErrorLoadingException("Odysee: streaming_url tidak tersedia")

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamingUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                quality = Qualities.Unknown.value
            }
        )
    }

    data class OdyseeRpcResponse(val result: OdyseeGetResult? = null)
    data class OdyseeGetResult(@com.fasterxml.jackson.annotation.JsonProperty("streaming_url") val streamingUrl: String? = null)
}
