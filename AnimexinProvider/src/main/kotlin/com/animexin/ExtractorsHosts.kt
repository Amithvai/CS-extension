package com.animexin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * Kumpulan extractor untuk host yang muncul di halaman episode animexin.dev.
 * Sebagian besar diporting dari AnichinProvider / DonghubProvider (repo ini)
 * karena sudah terverifikasi terhadap host yang sama.
 */

/** UA browser — playmogo menolak UA default OkHttp */
private const val EXTRACTOR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

// ---------------------------------------------------------------------------
// Odysee (LBRY)
// ---------------------------------------------------------------------------

/**
 * Odysee via LBRY proxy — embed URL dari animexin sudah menyertakan
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

        val response = app.post(API, json = body, timeout = 30_000L)
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
    data class OdyseeGetResult(
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}

// ---------------------------------------------------------------------------
// DTube
// ---------------------------------------------------------------------------

/**
 * play.d.tube — player DTube (via https://api.d.tube/videos/{id})
 * Response JSON berisi video_url master.m3u8 di nas1/nas2.d.tube.
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
        } catch (_: Exception) {
        }

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

// ---------------------------------------------------------------------------
// OK.ru (Odnoklassniki)
// ---------------------------------------------------------------------------

class OkRuSSL : Odnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

/**
 * Extractor OK.ru yang membaca data video dari atribut
 * [data-module=OKVideo][data-options] (player HTML5), bukan regex mentah
 * ke "videos":[...] — pola lama tidak pernah match karena HTML-escape.
 *
 * Menghasilkan progressive MP4 per kualitas (audio muxed) + master HLS
 * sebagai alternatif.
 */
open class Odnoklassniki : ExtractorApi() {
    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = toEmbedUrl(url)

        val html = runCatching {
            app.get(
                embedUrl,
                headers = REQUEST_HEADERS + mapOf("Origin" to mainUrl),
                timeout = 15_000L
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("Gagal membuka halaman OK.ru")

        val metadata = parseMetadata(html)
            ?: throw ErrorLoadingException("Data video OK.ru tidak ditemukan")

        var found = false

        // 1) Progressive MP4 — kualitas eksplisit, audio menyatu.
        metadata.videos?.forEach { video ->
            val videoUrl = video.url
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: return@forEach

            val quality = mapQuality(video.name)
            callback(
                newExtractorLink(this.name, "${this.name} $quality", videoUrl) {
                    this.referer = "$mainUrl/"
                    this.headers = REQUEST_HEADERS
                    this.quality = getQualityFromName(quality)
                }
            )
            found = true
        }

        // 2) Master HLS — adaptif, player pilih kualitas otomatis.
        metadata.hlsManifestUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { hls ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} (Auto)",
                        url = hls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = REQUEST_HEADERS
                    }
                )
                found = true
            }

        if (!found) throw ErrorLoadingException("Tidak ada stream OK.ru yang bisa diputar")
    }

    /** Normalisasi URL ke bentuk embed: /video/<id> -> /videoembed/<id> */
    private fun toEmbedUrl(url: String): String {
        return when {
            url.contains("/videoembed/") -> url
            url.contains("/video/") -> url.replace("/video/", "/videoembed/")
            else -> url
        }
    }

    /**
     * Ambil data-options dari elemen [data-module=OKVideo], lalu parse JSON.
     * Jsoup otomatis mengubah &quot; -> " sehingga hasilnya JSON valid.
     */
    private fun parseMetadata(html: String): OkRuMetadata? {
        val document = Jsoup.parse(html)
        val optionsRaw = document.selectFirst("[data-module=OKVideo][data-options]")
            ?.attr("data-options")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val options = tryParseJson<OkRuOptions>(optionsRaw) ?: return null
        return options.flashvars?.metadata
    }

    private fun mapQuality(name: String?): String {
        return name?.uppercase()?.let {
            when {
                it.contains("MOBILE") -> "144p"
                it.contains("LOWEST") -> "240p"
                it.contains("LOW") -> "360p"
                it.contains("SD") -> "480p"
                it.contains("HD") -> "720p"
                it.contains("FULL") -> "1080p"
                it.contains("QUAD") -> "1440p"
                it.contains("ULTRA") -> "4k"
                else -> it
            }
        } ?: "Unknown"
    }

    companion object {
        private val REQUEST_HEADERS = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to USER_AGENT,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OkRuOptions(
        @JsonProperty("flashvars") val flashvars: OkRuFlashvars? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OkRuFlashvars(
        @JsonProperty("metadata") val metadata: OkRuMetadata? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OkRuMetadata(
        @JsonProperty("videos") val videos: List<OkRuVideo>? = null,
        @JsonProperty("hlsManifestUrl") val hlsManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OkRuVideo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
    )
}

// ---------------------------------------------------------------------------
// Rumble
// ---------------------------------------------------------------------------

/**
 * Rumble embed (rumble.com/embed/<id>).
 *
 * Struktur JSON halaman embed sekarang menyimpan HLS di
 * "hls":{"url":"https://rumble.com/hls-vod/<hash>/playlist.m3u8"}
 * (blok {"mp4":{...}} lama sudah tidak ada).
 */
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private val HLS_URL_REGEX = Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/", timeout = 15_000L)
        val script = response.document.selectFirst("script:containsData(mp4):containsData(hls)")?.data()
            ?: response.text

        val m3u8 = HLS_URL_REGEX.find(script)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
}

// ---------------------------------------------------------------------------
// DoodStream family (playmogo.com)
// ---------------------------------------------------------------------------

/**
 * Server DoodStream (playmogo.com) — dipakai animexin.dev sebagai salah satu mirror.
 *
 * Pola ekstraksi standar DoodStream:
 *  1. GET /e/<id>  -> cari path '/pass_md5/<token-id>/<file-hash>' di script
 *  2. GET https://<host>/pass_md5/<token-id>/<file-hash> (referer: halaman embed)
 *     -> balas URL direct MP4
 *  3. Tambahkan suffix acak + ?token=<file-hash>&expiry=<now>
 */
class DoodAnimexin : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    companion object {
        /** Contoh match: '/pass_md5/284073182-110-137-<hex>/<fileHash>' */
        private val PASS_MD5_REGEX = Regex("""'/pass_md5/([^']+)'\s*,\s*function""")
        private val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedPage = app.get(url, headers = mapOf("User-Agent" to EXTRACTOR_UA), timeout = 15_000L).text
        val passPath = PASS_MD5_REGEX.find(embedPage)?.groupValues?.get(1) ?: return
        val fileHash = passPath.substringAfterLast('/')
        val lotPath = passPath.substringBeforeLast('/')

        val direct = app.get(
            "$mainUrl/pass_md5/$lotPath/$fileHash",
            headers = mapOf("User-Agent" to EXTRACTOR_UA),
            referer = url,
            timeout = 15_000L
        ).text.trim()

        if (!direct.startsWith("http")) return
        val random = (1..10).map { CHARS.random() }.joinToString("")
        val finalUrl = "$direct$random?token=$fileHash&expiry=${System.currentTimeMillis()}"

        callback.invoke(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.headers = mapOf("User-Agent" to EXTRACTOR_UA)
            }
        )
    }
}
