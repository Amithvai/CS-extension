package com.mwk.shared.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

object SharedExtractorUtils {
    val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")

    fun extractSources(text: String): String? {
        val packed = getPacked(text)
        if (!packed.isNullOrEmpty()) {
            var result = getAndUnpack(text)
            if (result.contains("var links")) result = result.substringAfter("var links")
            return result
        }
        return null
    }

    fun isValidStreamingUrl(url: String): Boolean {
        val queryStart = url.indexOf('?')
        if (queryStart == -1) return true
        val query = url.substring(queryStart)
        if (query.startsWith("?=")) return false
        val hasT = query.contains("?t=") || query.contains("&t=")
        val hasS = query.contains("&s=")
        val hasE = query.contains("&e=")
        val hasF = query.contains("&f=")
        return hasT && hasS && hasE && hasF
    }

    fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        url.contains("/f/") -> url.replace("/f/", "/v/")
        else -> url
    }

    fun commonHeaders(referer: String) = mapOf(
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Origin" to referer,
        "User-Agent" to USER_AGENT
    )
}

open class Dingtezuni : ExtractorApi() {
    companion object {
        private val M3U8_FIND_REGEX = Regex(":\\s*\"(.*?m3u8.*?)\"")
    }

    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = SharedExtractorUtils.commonHeaders(mainUrl)

        val response = app.get(SharedExtractorUtils.getEmbedUrl(url), referer = referer, timeout = 15_000L)
        val script = SharedExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        M3U8_FIND_REGEX.findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
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

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    companion object {
        private const val TOKEN_TTL = 5 * 60 * 1000L
        private var tokenCache: String? = null
        private var websiteTokenCache: String? = null
        private var tokenTimestamp: Long = 0L
        private val QUALITY_REGEX = Regex("(\\d{3,4})[pP]")
        private val ID_REGEX = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)")
        private val WT_REGEX = Regex("fetchData.wt\\s*=\\s*\"([^\"]+)")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = ID_REGEX.find(url)?.groupValues?.get(1) ?: return
        val token = getToken() ?: return
        val websiteToken = getWebsiteToken() ?: return
        app.get("$mainApi/getContent?contentId=$id&token=$token&wt=$websiteToken", timeout = 15_000L)
            .text.let { AppUtils.tryParseJson<com.mwk.shared.data.GofileSource>(it) }?.data?.contents?.forEach {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        it.value["link"] ?: return,
                    ) {
                        this.quality = getQuality(it.value["name"])
                        this.headers = mapOf("Cookie" to "accountToken=$token")
                    }
                )
            }
    }

    private suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (tokenCache != null && now - tokenTimestamp < TOKEN_TTL) return tokenCache
        tokenCache = app.get("$mainApi/createAccount", timeout = 15_000L).text.let { AppUtils.tryParseJson<com.mwk.shared.data.GofileAccount>(it) }?.data?.get("token")
        tokenTimestamp = now
        return tokenCache
    }

    private suspend fun getWebsiteToken(): String? {
        if (websiteTokenCache != null) return websiteTokenCache
        websiteTokenCache = app.get("$mainUrl/dist/js/alljs.js", timeout = 15_000L).text.let {
            WT_REGEX.find(it)?.groupValues?.get(1)
        }
        return websiteTokenCache
    }

    private fun getQuality(str: String?): Int {
        return QUALITY_REGEX.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

open class Odnoklassniki : ExtractorApi() {
    companion object {
        private val UNICODE_ESC_REGEX = Regex("\\\\u([0-9A-Fa-f]{4})")
        private val VIDEOS_JSON_REGEX = Regex(""""videos":(\[[^]]*])""")
    }

    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val embedUrl = url.replace("/video/","/videoembed/")
        val videoReq  = app.get(embedUrl, headers=headers, timeout = 15_000L).text.replace("\\&quot;", "\"").replace("\\\\", "\\")
            .replace(UNICODE_ESC_REGEX) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }

        val videosStr = VIDEOS_JSON_REGEX.find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("Video not found")
        val videos    = AppUtils.tryParseJson<List<com.mwk.shared.data.OkRuVideo>>(videosStr) ?: throw ErrorLoadingException("Video not found")

        for (video in videos) {

            val videoUrl  = if (video.url.startsWith("//")) "https:${video.url}" else video.url

            val quality   = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW",    "360p")
                .replace("SD",     "480p")
                .replace("HD",     "720p")
                .replace("FULL",   "1080p")
                .replace("QUAD",   "1440p")
                .replace("ULTRA",  "4k")

            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = videoUrl,
                    type    = INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }
}

class OkRuSSL : Odnoklassniki() {
    override var name    = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name    = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

open class Lulustream : ExtractorApi() {
    override val name = "Lulustream"
    override val mainUrl = "https://placeholder.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = SharedExtractorUtils.getEmbedUrl(url)
        if (embedUrl.isEmpty()) return

        val response = app.get(embedUrl, referer = referer ?: this.mainUrl, timeout = 15_000L)

        val script = SharedExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        SharedExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!SharedExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = SharedExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }

    protected open fun getEmbedUrl(url: String): String = SharedExtractorUtils.getEmbedUrl(url)
}

class Luluvid : Lulustream() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
}

class Embedpyrox : Lulustream() {
    override var name = "Embedpyrox"
    override var mainUrl = "https://embedpyrox.xyz"
}

class Hgcloud : Lulustream() {
    override var name = "Hgcloud"
    override var mainUrl = "https://hgcloud.to"
}

class Luluvdoo : Lulustream() {
    override var name = "Luluvdoo"
    override var mainUrl = "https://luluvdoo.com"
}

class Veev : Lulustream() {
    override var name = "Veev"
    override var mainUrl = "https://veev.to"
}

open class P2PPlay : ExtractorApi() {
    override val name = "P2PPlay"
    override val mainUrl = "https://pm21.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("#").substringBefore("&")
        if (id.isEmpty()) return

        val embedUrl = "$mainUrl/#$id"
        val response = app.get(embedUrl, referer = referer ?: this.mainUrl, timeout = 15_000L)

        val script = SharedExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        SharedExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!SharedExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = SharedExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }
}

class Embed4meVip : P2PPlay() {
    override var name = "Embed4meVip"
    override var mainUrl = "https://dm21.embed4me.vip"
}

class LivePlayerP2P : P2PPlay() {
    override var name = "LivePlayerP2P"
    override var mainUrl = "https://live.playerp2p.online"
}

open class StreamHG : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://placeholder.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = SharedExtractorUtils.getEmbedUrl(url)
        if (embedUrl.isEmpty()) return

        val response = app.get(embedUrl, referer = referer ?: this.mainUrl, timeout = 15_000L)

        val script = SharedExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        SharedExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!SharedExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = SharedExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }

    protected open fun getEmbedUrl(url: String): String = SharedExtractorUtils.getEmbedUrl(url)
}

class Hanerix : StreamHG() {
    override var name = "Hanerix"
    override var mainUrl = "https://hanerix.com"
}

class Masukestin : StreamHG() {
    override var name = "Masukestin"
    override var mainUrl = "https://masukestin.com"
}
