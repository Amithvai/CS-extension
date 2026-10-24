package com.dutamovie

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ExtractorUtils {
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
        val headers = ExtractorUtils.commonHeaders(mainUrl)

        val response = app.get(ExtractorUtils.getEmbedUrl(url), referer = referer, timeout = 15_000L)
        val script = ExtractorUtils.extractSources(response.text)
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

    // movearnpre.com sudah NXDOMAIN (mati permanen).
    // Earnvids-family rotation mirror — diarahkan ke host Earnvids yang masih hidup
    // (endpoint /v/<id> & unpack script sources: sama seperti Dingtezuni).
    override var mainUrl = "https://earnvids.xyz"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"

    // mivalyo.com sudah NXDOMAIN (mati permanen).
    // Diarahkan ke host utama Earnvids yang masih hidup.
    override var mainUrl = "https://earnvids.com"
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
    companion object {
        private val GOFILE_ID_REGEX = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)")
        private val GOFILE_WT_REGEX = Regex("fetchData.wt\\s*=\\s*\"([^\"]+)")
        private val GOFILE_QUALITY_REGEX = Regex("(\\d{3,4})[pP]")
    }

    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    private var cachedToken: Pair<String, Long>? = null
    private var cachedWebsiteToken: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = GOFILE_ID_REGEX.find(url)?.groupValues?.get(1) ?: return

        val now = System.currentTimeMillis()
        val token = cachedToken?.takeIf { now - it.second < 600_000 }?.first
            ?: run {
                val newToken = app.get("$mainApi/createAccount", timeout = 15_000L).text
                    .let { tryParseJson<Account>(it) }?.data?.get("token")
                if (newToken != null) cachedToken = newToken to now
                newToken
            } ?: return

        val websiteToken = cachedWebsiteToken
            ?: run {
                val newWt = app.get("$mainUrl/dist/js/alljs.js", timeout = 15_000L).text.let {
                    GOFILE_WT_REGEX.find(it)?.groupValues?.get(1)
                }
                if (newWt != null) cachedWebsiteToken = newWt
                newWt
            } ?: return

        val content = app.get("$mainApi/getContent?contentId=$id&token=$token&wt=$websiteToken", timeout = 15_000L)
            .text.let { tryParseJson<Source>(it) }?.data?.contents ?: return

        content.forEach {
            val link = it.value["link"] ?: return@forEach
            callback(
                newExtractorLink(name, name, link) {
                    this.quality = getQuality(it.value["name"])
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun getQuality(name: String?): Int {
        return GOFILE_QUALITY_REGEX.find(name ?: "")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Account(@JsonProperty("data") val data: HashMap<String, String>? = null)
    data class Data(@JsonProperty("contents") val contents: HashMap<String, HashMap<String, String>>? = null)
    data class Source(@JsonProperty("data") val data: Data? = null)
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
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
        val embedUrl = ExtractorUtils.getEmbedUrl(url)
        if (embedUrl.isEmpty()) return

        val response = app.get(embedUrl, referer = referer ?: this.mainUrl, timeout = 15_000L)

        val script = ExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        ExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!ExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = ExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }

    protected open fun getEmbedUrl(url: String): String = ExtractorUtils.getEmbedUrl(url)
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

        val script = ExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        ExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!ExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = ExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }
}

class Luluvdoo : Lulustream() {
    override var name = "Luluvdoo"
    override var mainUrl = "https://luluvdoo.com"
}

class Embed4meVip : P2PPlay() {
    override var name = "Embed4meVip"
    override var mainUrl = "https://dm21.embed4me.vip"
}

class LivePlayerP2P : P2PPlay() {
    override var name = "LivePlayerP2P"
    override var mainUrl = "https://live.playerp2p.online"
}

class Veev : Lulustream() {
    override var name = "Veev"
    override var mainUrl = "https://veev.to"
}

class Video4Me : ExtractorApi() {
    companion object {
        private val SOURCE_M3U8_REGEX = Regex("""<source[^>]+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    }

    override val name = "Video4Me"
    override val mainUrl = "https://video.4meplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer, timeout = 15_000L)

        SOURCE_M3U8_REGEX.find(response.text)?.groupValues?.getOrNull(1)?.let { m3u8Url ->
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = referer ?: mainUrl,
                headers = mapOf(
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "User-Agent" to USER_AGENT
                )
            ).forEach(callback)
        }
    }
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
        val embedUrl = ExtractorUtils.getEmbedUrl(url)
        if (embedUrl.isEmpty()) return

        val response = app.get(embedUrl, referer = referer ?: this.mainUrl, timeout = 15_000L)

        val script = ExtractorUtils.extractSources(response.text)
            ?: response.document.selectFirst("script:containsData(sources:)")?.data()
            ?: return

        ExtractorUtils.m3u8Regex.findAll(script).forEach { match ->
            val m3u8Url = match.value
            if (!ExtractorUtils.isValidStreamingUrl(m3u8Url)) return@forEach
            val actualReferer = referer ?: mainUrl
            generateM3u8(
                name,
                fixUrl(m3u8Url),
                referer = actualReferer,
                headers = ExtractorUtils.commonHeaders(actualReferer)
            ).forEach(callback)
        }
    }

    protected open fun getEmbedUrl(url: String): String = ExtractorUtils.getEmbedUrl(url)
}

class Hanerix : StreamHG() {
    override var name = "Hanerix"
    override var mainUrl = "https://hanerix.com"
}

class Masukestin : StreamHG() {
    override var name = "Masukestin"
    override var mainUrl = "https://masukestin.com"
}

/** JSON pada field `datas` (base64) di halaman embed Abyss */
@JsonIgnoreProperties(ignoreUnknown = true)
class AbyssDatas(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("md5_id") val md5Id: Long? = null,
    @JsonProperty("user_id") val userId: Long? = null,
    @JsonProperty("media") val media: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
class AbyssVideo(@JsonProperty("mp4") val mp4: AbyssMp4? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
class AbyssMp4(
    @JsonProperty("sources") val sources: List<AbyssSource>? = null,
    @JsonProperty("domains") val domains: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
class AbyssSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("res_id") val resId: Int? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("status") val status: Boolean? = null,
    @JsonProperty("sub") val sub: String? = null,
)

/**
 * Player Abyss (player.abyssplayer.com / abyss.to, platform Hydrax).
 *
 * Halaman embed memuat `const datas = "<base64>"`; setelah di-decode (ISO-8859-1)
 * berisi slug, md5_id, user_id, dan blob `media` terenkripsi AES-256-CTR dengan
 * key = MD5 hex dari "$user_id:$slug:$md5_id" (IV = 16 byte pertama key).
 * Hasil dekripsi berupa JSON daftar sumber MP4 per kualitas (label/res_id/size/sub).
 *
 * URL putar valid dibangun dengan token permintaan:
 *   {sub}.{tld}/sora/{size}/{token}
 * token = base64(base64(AES-CTR("/mp4/{md5_id}/{res_id}/{size}/{size}/0")))
 * — partSize = ukuran penuh + index 0 membuat server menyajikan file utuh
 * (mendukung HTTP Range), dengan key = MD5 hex dari *nilai digit* ukuran
 * (Long, bukan string ASCII). Referer https://abysscdn.com/ wajib (403 tanpa itu).
 */
open class Abyssplayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://player.abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private val DATAS_REGEX = Regex("const\\s+datas\\s*=\\s*\"([^\"]+)\"")
        private const val STREAM_REFERER = "https://abysscdn.com/"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/", timeout = 15_000L)
        val datas = DATAS_REGEX.find(response.text)?.groupValues?.get(1) ?: return

        val meta = runCatching {
            tryParseJson<AbyssDatas>(String(Base64.getDecoder().decode(datas), Charsets.ISO_8859_1))
        }.getOrNull() ?: return
        val slug = meta.slug ?: return
        val media = meta.media ?: return
        val md5Id = meta.md5Id ?: return

        val mediaKey = md5Hex("${meta.userId}:${slug}:${md5Id}".toByteArray(Charsets.UTF_8))
        val decrypted = runCatching {
            aesCtr(media.toByteArray(Charsets.ISO_8859_1), mediaKey, encrypt = false)
        }.getOrNull() ?: return
        val video = runCatching {
            tryParseJson<AbyssVideo>(String(decrypted, Charsets.UTF_8))
        }.getOrNull() ?: return

        val tld = video.mp4?.domains?.firstOrNull()?.substringAfter('.') ?: return
        video.mp4.sources.orEmpty().forEach { source ->
            if (source?.status != true) return@forEach
            val size = source.size ?: return@forEach
            val resId = source.resId ?: return@forEach
            val sub = source.sub ?: return@forEach

            val token = doubleBase64(
                aesCtr("/mp4/$md5Id/$resId/$size/$size/0".toByteArray(), sizeKeyHex(size), encrypt = true)
            )
            callback(
                newExtractorLink(name, name, "https://$sub.$tld/sora/$size/$token") {
                    this.quality = getQualityFromName(source.label)
                    this.headers = mapOf("Referer" to STREAM_REFERER)
                    this.type = ExtractorLinkType.VIDEO
                }
            )
        }
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** AES-256-CTR, key = string hex 32 karakter, IV = 16 byte pertama key */
    private fun aesCtr(data: ByteArray, keyHex: String, encrypt: Boolean): ByteArray {
        val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes.copyOfRange(0, 16))
        )
        return cipher.doFinal(data)
    }

    /** Key per-sumber: MD5 hex dari byte nilai-digit ukuran (bukan string ASCII) */
    private fun sizeKeyHex(size: Long): String =
        md5Hex(size.toString().map { it.digitToInt().toByte() }.toByteArray())

    private fun doubleBase64(data: ByteArray): String {
        val first = Base64.getEncoder().encodeToString(data).replace("=", "")
        return Base64.getEncoder().encodeToString(first.toByteArray(Charsets.ISO_8859_1)).replace("=", "")
    }
}
