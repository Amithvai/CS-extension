package com.winbu

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Player P2P Winbu (winbu.strp2p.com) — diproses oleh core extractor VidStack. */
class Winbustrp2p : VidStack() {
    override var name = "WinbuP2P"
    override var mainUrl = "https://winbu.strp2p.com"
    override var requiresReferer = true
}

/**
 * Player Filedon — halaman embed menyimpan URL file (presigned R2) di props
 * Inertia `#app[data-page]` yang sudah HTML-unescape oleh Jsoup.
 */
class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = true

    private val hlsUrlRegex = Regex("""\"hls_url\":\"(https:\\/\\/[^"]+\.m3u8[^"]*)""")
    private val videoUrlRegex = Regex("""\"url\":\"(https:\\/\\/[^"]+\.mp4[^"]*)""")
    private val nameRegex = Regex("""\"name\":\"([^"]+\.mp4)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: "$mainUrl/", timeout = 30_000L).document
        val json = document.selectFirst("#app")?.attr("data-page") ?: return

        hlsUrlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/")?.let { hlsUrl ->
            generateM3u8(
                name,
                fixUrl(hlsUrl),
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).forEach(callback)
            return
        }

        val rawUrl = videoUrlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        val fileName = nameRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        val quality = fileName?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

        callback.invoke(
            newExtractorLink(name, name, rawUrl) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )
    }
}

// ---------------- Abyss (abysscdn.com / abyssplayer.com) ----------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class AbyssData(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("md5_id") val md5Id: Long? = null,
    @JsonProperty("user_id") val userId: Long? = null,
    @JsonProperty("media") val media: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AbyssSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("res_id") val resId: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AbyssFristData(
    @JsonProperty("res_id") val resId: Int? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AbyssMp4(
    @JsonProperty("sources") val sources: List<AbyssSource>? = null,
    @JsonProperty("fristDatas") val fristDatas: List<AbyssFristData>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AbyssMediaRoot(
    @JsonProperty("mp4") val mp4: AbyssMp4? = null
)

/**
 * Player Abyss — halaman embed berisi `const datas = "<base64>"` yang memuat
 * {slug, user_id, md5_id, media}. Media adalah JSON terenkripsi AES-CTR:
 *   key     = MD5("user_id:slug:md5_id") hex string (32 byte UTF-8, AES-256)
 *   counter = 16 karakter pertama dari hex tersebut
 * Hasil dekrip: {"mp4":{"sources":[...],"fristDatas":[{"res_id","url"},...]}}
 */
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abysscdn.com"
    override val requiresReferer = true

    companion object {
        private val DATAS_REGEX = Regex("""datas\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        /** Referer yang dipakai player saat fetch file .fd */
        const val PLAY_REFERER = "https://abysscdn.com/"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer ?: mainUrl, timeout = 30_000L).text
        val b64 = DATAS_REGEX.find(page)?.groupValues?.get(1) ?: return

        val rawBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        val record = tryParseJson<AbyssData>(String(rawBytes, Charsets.ISO_8859_1)) ?: return
        val slug = record.slug ?: return
        val userId = record.userId ?: return
        val md5Id = record.md5Id ?: return
        val mediaB64 = record.media ?: return

        // Derive kunci: MD5 hex dari "user_id:slug:md5_id"
        val keyStr = "$userId:$slug:$md5Id"
        val md5Hex = MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // media: 1 char latin-1 = 1 byte -> kembalikan ke bytes
        val mediaBytes = ByteArray(mediaB64.length) { mediaB64[it].code.toByte() }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(md5Hex.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(md5Hex.substring(0, 16).toByteArray(Charsets.UTF_8))
        )
        val plain = cipher.doFinal(mediaBytes)
        val root = tryParseJson<AbyssMediaRoot>(String(plain, Charsets.UTF_8)) ?: return
        val mp4 = root.mp4 ?: return

        // res_id -> label (480p/720p/1080p)
        val labelByRes = mp4.sources?.mapNotNull { s ->
            val rid = s.resId ?: return@mapNotNull null
            rid to (s.label ?: "unknown")
        }?.toMap() ?: emptyMap()

        mp4.fristDatas.orEmpty().forEach { fd ->
            val link = fd.url ?: return@forEach
            val label = labelByRes[fd.resId] ?: "${fd.resId ?: 0}p"
            callback.invoke(
                newExtractorLink(name, "$name $label", link, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(label)
                    this.referer = PLAY_REFERER
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
        }
    }
}

class AbyssPlayerExtractor : AbyssExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://abyssplayer.com"
}