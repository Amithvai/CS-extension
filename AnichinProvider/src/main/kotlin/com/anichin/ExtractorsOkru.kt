package com.anichin

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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class OkRuSSL : Odnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

/**
 * Extractor OK.ru (Odnoklassniki) yang diperbaiki total.
 *
 * ============================ MASALAH LAMA ============================
 * Extractor bawaan core (dan salinan lama di provider ini) mengambil
 * "videos":[...] dengan regex mentah dari HTML embed, padahal sejak OK.ru
 * memakai player HTML5 data video dipindah ke atribut:
 *
 *   <div data-module="OKVideo"
 *        data-options="{&quot;flashvars&quot;:{&quot;metadata&quot;:{
 *           &quot;videos&quot;:[...], &quot;hlsManifestUrl&quot;:&quot;...&quot;}}}">
 *
 * HTML-escape (&quot; \u0026) membuat regex `"videos":(\[[^]]*])` tidak
 * pernah match -> "Video not found". Sudah diverifikasi terhadap halaman
 * embed asli: regex lama 0 match, parser baru (unescape + JSON) sukses.
 *
 * ============================== SOLUSI ================================
 * 1. GET halaman /videoembed/<id> dengan header ala browser.
 * 2. Ambil atribut data-options pada elemen [data-module=OKVideo].
 *    Jsoup otomatis men-decode &quot; sehingga menjadi JSON valid.
 * 3. Parse JSON -> flashvars.metadata:
 *      - videos[]        : progressive MP4 (mobile..full) 144p–1080p
 *      - hlsManifestUrl  : master HLS 144p–1080p (satu stream, audio muxed)
 * 4. Emit progressive MP4 per kualitas (paling kompatibel + cepat), lalu
 *    tambahkan HLS master sebagai opsi terakhir.
 *
 * Catatan: HLS master ok.ru mengembalikan 6 variant dengan audio menyatu
 * di dalam TS segment (bukan audio track terpisah), jadi aman diputar.
 * Progressive MP4 juga sudah diverifikasi mengandung track 'vide'+'soun'.
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
        //    Urutan dari kecil ke besar (mobile..full) sesuai data OK.ru;
        //    CloudStream akan menampilkan kualitas dari getQualityFromName.
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
        //    Tetap disediakan sebagai alternatif bila MP4 progressive
        //    bermasalah di jaringan tertentu.
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
