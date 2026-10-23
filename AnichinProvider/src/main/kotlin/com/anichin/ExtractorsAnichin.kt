package com.anichin

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** UA browser — playmogo/nunadrama menolak UA default OkHttp */
private const val EXTRACTOR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * Server DoodStream (playmogo.com) — dipakai Anichin sebagai salah satu mirror.
 *
 * Pola ekstraksi standar DoodStream:
 *  1. GET /e/<id>  → cari path '/pass_md5/<token-id>/<file-hash>' di script
 *  2. GET https://<host>/pass_md5/<token-id>/<file-hash> (referer: halaman embed)
 *     → balas URL direct MP4
 *  3. Tambahkan suffix acak + ?token=<file-hash>&expiry=<now>
 *     (mirip fungsi makePlay() di JS mereka)
 *
 * Token pass_md5 dari server hidup 2026-10-23 (diverifikasi manual):
 * balasannya URL https://cc527fo.cloudatacdn.com/.../<name>~ dan berlaku
 * setelah redirect 302 → CloudStream mengikuti redirect, aman.
 */
open class DoodAnichin : ExtractorApi() {
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

/**
 * Player perantara player.nunadrama.sbs — halaman embed memakai JS untuk
 * menampilkan video.
 *
 * Hasil fetch server hidup (2026-10-23): halaman /index.php?video=<id>
 * berisi hanya satu iframe geo.dailymotion.com, jadi cukup ambil src iframe
 * lalu setelah itu serahkan ke loadExtractor (Geodailymotion).
 */
class NunaDrama : ExtractorApi() {
    override val name = "Nunadrama"
    override val mainUrl = "https://player.nunadrama.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, headers = mapOf("User-Agent" to EXTRACTOR_UA), referer = referer ?: "https://anichin.moe/", timeout = 15_000L).document
        val iframeSrc = page.selectFirst("iframe[src]")?.attr("src") ?: return
        val finalSrc = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl/${iframeSrc.removePrefix("/")}"
        loadExtractor(finalSrc, mainUrl, subtitleCallback, callback)
    }
}

/**
 * Player Abyss (player.abyssplayer.com) — dipakai Anichin sebagai salah satu mirror.
 * (Port dari AbyssExtractor milik NgeFilm21; sama player, domain berbeda.)

 * Halaman embed berisi `const datas = "<base64>"` yang memuat
 * {slug, user_id, md5_id, media}. Field `media` adalah JSON terenkripsi AES-CTR:
 *   key     = MD5 hex dari "user_id:slug:md5_id" (32 char = AES-256)
 *   counter = 16 karakter pertama dari hex tersebut
 * Hasil dekrip: {"mp4":{"sources":[...],"fristDatas":[{"res_id","url"},...]}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnichinAbyssData(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("md5_id") val md5Id: Long? = null,
    @JsonProperty("user_id") val userId: Long? = null,
    @JsonProperty("media") val media: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnichinAbyssSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("res_id") val resId: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnichinAbyssFristData(
    @JsonProperty("res_id") val resId: Int? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnichinAbyssMp4(
    @JsonProperty("sources") val sources: List<AnichinAbyssSource>? = null,
    @JsonProperty("fristDatas") val fristDatas: List<AnichinAbyssFristData>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnichinAbyssMediaRoot(
    @JsonProperty("mp4") val mp4: AnichinAbyssMp4? = null
)

open class AbyssAnichin : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abysscdn.com"
    override val requiresReferer = true

    companion object {
        private val DATAS_REGEX = Regex("""datas\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private const val PLAY_REFERER = "https://abysscdn.com/"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, headers = mapOf("User-Agent" to EXTRACTOR_UA), referer = referer ?: mainUrl, timeout = 15_000L).text
        val b64 = DATAS_REGEX.find(page)?.groupValues?.get(1) ?: return

        val rawBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        val record = tryParseJson<AnichinAbyssData>(String(rawBytes, Charsets.ISO_8859_1)) ?: return
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
        val root = tryParseJson<AnichinAbyssMediaRoot>(String(plain, Charsets.UTF_8)) ?: return
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
                    this.referer = PLAY_REFERER
                    this.headers = mapOf("User-Agent" to EXTRACTOR_UA)
                }
            )
        }
    }
}

class AbyssPlayerAnichin : AbyssAnichin() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
}
