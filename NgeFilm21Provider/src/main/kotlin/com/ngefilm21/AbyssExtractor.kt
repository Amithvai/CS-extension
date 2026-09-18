package com.ngefilm21

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
 * Player Abyss (abyssplayer.com / abysscdn.com) — dipakai NgeFilm21 server 2.
 *
 * Halaman embed berisi `const datas = "<base64>"` yang memuat
 * {slug, user_id, md5_id, media}. Field `media` adalah JSON terenkripsi AES-CTR:
 *   key     = MD5 hex dari "user_id:slug:md5_id" (32 char = AES-256)
 *   counter = 16 karakter pertama dari hex tersebut
 * Hasil dekrip: {"mp4":{"sources":[...],"fristDatas":[{"res_id","url"},...]}}
 */
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abysscdn.com"
    override val requiresReferer = true

    companion object {
        private val DATAS_REGEX = Regex("""datas\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private const val PLAY_REFERER = "https://abysscdn.com/"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer ?: mainUrl, timeout = 15_000L).text
        val b64 = DATAS_REGEX.find(page)?.groupValues?.get(1) ?: return

        val rawBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        val record = tryParseJson<AbyssData>(String(rawBytes, Charsets.ISO_8859_1)) ?: return
        val slug = record.slug ?: return
        val userId = record.userId ?: return
        val md5Id = record.md5Id ?: return
        val mediaB64 = record.media ?: return

        val keyStr = "$userId:$slug:$md5Id"
        val md5Hex = MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
                    this.headers = mapOf("User-Agent" to UA)
                }
            )
        }
    }
}

class AbyssPlayerExtractor : AbyssExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://abyssplayer.com"
}
