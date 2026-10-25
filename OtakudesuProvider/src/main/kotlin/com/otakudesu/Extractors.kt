package com.otakudesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

/**
 * Player milik OtakuDesu sendiri di desustream.net / desustream.com.
 * Semua varian menyimpan URL video langsung di HTML halaman:
 *  - /dstream/odcdn/        : const videoURL = "https://cdn.odcloud.net/...mp4"
 *  - /dstream/otakuwatch7/  : <video><source src="https://...googlevideo...mp4">
 *  - /dstream/arcg/         : playerjs dengan placeholder archive.org (mati) -> diabaikan
 */
open class DesuStreamPlayer : ExtractorApi() {
    override val name = "DesuStream"
    override val mainUrl = "https://desustream.net"
    override val requiresReferer = true

    private val videoUrlRegex = Regex("""videoURL\s*=\s*"(https?://[^"]+)""")
    private val sourceRegex = Regex("""<source[^>]+src="(https?://[^"]+)""")
    private val fileRegex = Regex("""file\s*:\s*"(https?://[^"]+)""")
    private val deadHostRegex = Regex("""archive\.org|xxx\.mp4""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: "https://otakudesu.blog/", timeout = 30_000L).text

        val videoUrl = videoUrlRegex.find(html)?.groupValues?.get(1)
            ?: sourceRegex.find(html)?.groupValues?.get(1)
            ?: fileRegex.find(html)?.groupValues?.get(1)
            ?: return

        if (deadHostRegex.containsMatchIn(videoUrl)) return

        val fixed = videoUrl.replace("&amp;", "&")
        callback.invoke(
            newExtractorLink(name, name, fixed, ExtractorLinkType.VIDEO) {
                this.referer = referer ?: "https://otakudesu.blog/"
                this.quality = getQualityFromName(fixed)
            }
        )
    }
}

class DesuStreamCom : DesuStreamPlayer() {
    override var name = "DesuStream"
    override var mainUrl = "https://desustream.com"
}

/**
 * Player Filedon (filedon.co) — halaman embed menyimpan URL file (presigned R2)
 * di props Inertia `#app[data-page]` yang sudah HTML-unescape oleh Jsoup
 * (quote polos, escape slash `\/`). Link download mengarah ke /view/ yang tidak
 * memuat URL file — dikonversi ke /embed/.
 */
class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = true

    private val hlsUrlRegex = Regex(""""hls_url":"(https:[^"]+\.m3u8[^"]*)""")
    private val videoUrlRegex = Regex(""""url":"(https:[^"]+\.mp4[^"]*)""")
    private val nameRegex = Regex(""""name":"([^"]+\.mp4)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if ("/view/" in url) url.replaceFirst("/view/", "/embed/") else url
        val document = app.get(embedUrl, referer = referer ?: "$mainUrl/", timeout = 30_000L).document
        val json = document.selectFirst("#app")?.attr("data-page") ?: return

        val hlsUrl = hlsUrlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        if (hlsUrl != null) {
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
            newExtractorLink(name, name, rawUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )
    }
}

/**
 * Mesin VidHide/EarnVids generasi baru (odvidhide.com, vidhidefast.com):
 * halaman menyimpan sources dalam JS ter-obfuscate Dean Edwards packer
 * `eval(function(p,a,c,k,e,d){...}('...',36,N,'..'.split('|'))`.
 * Hasil unpack: var links={"hls2":master.m3u8, "hls3":master.txt, ...}
 * dipilih dengan urutan jwplayer hls4||hls3||hls2.
 */
open class VidhidePacked : ExtractorApi() {
    override val name = "Vidhide"
    override val mainUrl = "https://odvidhide.com"
    override val requiresReferer = true

    companion object {
        private val PACKED_REGEX =
            Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',\s*\d+,\s*\d+,\s*'(.*?)'\.split\('\|'\)""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
        private val LINKS_REGEX = Regex("""(hls\d)":"(https?://[^"]+)""")
        private val QUALITY_REGEX = Regex("""qualityLabels[^{]*\{"\d+":"(\d+)p""")
    }

    private fun unpack(packed: String, keywords: List<String>): String {
        val tokenRegex = Regex("""\b[a-z0-9]+\b""")
        val sb = StringBuilder()
        var last = 0
        for (m in tokenRegex.findAll(packed)) {
            sb.append(packed, last, m.range.first)
            val idx = m.value.toIntOrNull(36)
            sb.append(if (idx != null && idx < keywords.size) keywords[idx] else m.value)
            last = m.range.last + 1
        }
        sb.append(packed.substring(last))
        return sb.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer ?: "$mainUrl/", timeout = 30_000L).text
        val packed = PACKED_REGEX.find(page) ?: return
        val unpacked = unpack(packed.groupValues[1], packed.groupValues[2].split('|'))

        val links = LINKS_REGEX.findAll(unpacked)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()

        // Urutan sama dengan setup jwplayer: links.hls4||links.hls3||links.hls2
        val master = links["hls4"] ?: links["hls3"] ?: links["hls2"] ?: return
        val quality = QUALITY_REGEX.find(unpacked)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback.invoke(
            newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                this.referer = referer ?: "$mainUrl/"
                this.quality = quality
            }
        )
    }
}

class OdVidHide : VidhidePacked() {
    override var name = "OdVidHide"
    override var mainUrl = "https://odvidhide.com"
}

class VidHideFast : VidhidePacked() {
    override var name = "VidHideFast"
    override var mainUrl = "https://vidhidefast.com"
}
