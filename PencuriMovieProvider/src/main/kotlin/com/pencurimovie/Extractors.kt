package com.pencurimovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Voe
import java.net.URI


class Playmogo : DoodLaExtractor() {
    override var name = "Doodstream"
    override var mainUrl = "https://playmogo.com"
}

class MixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

class VoeSx : Voe() {
    override var mainUrl = "https://voe.sx"
}

class StreamTapeCom : StreamTape() {
    override var mainUrl = "https://streamtape.com"
}

/**
 * Mesin player "vplayer" keluarga VidHide/Hgcloud: halaman embed menyimpan
 * sources dalam JS ter-obfuscate Dean Edwards packer
 * `eval(function(p,a,c,k,e,d){...}('...',36,N,'..'.split('|'))`.
 * Hasil unpack: var links={"hls4":"/stream/.../master.m3u8", "hls3":"...master.txt",
 * "hls2":"...master.m3u8"} — jwplayer memilih hls4||hls3||hls2.
 * hls4 berupa path relatif (di-proxy lewat host player), hls2=CDN langsung yang
 * kerap 403, hls3=playlist HLS ber-extension .txt. Subtitle VTT (eng/ind/may/chi)
 * ada di tracks[] hasil unpack.
 */
open class VidhidePacked : ExtractorApi() {
    override val name = "Vidhide"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    companion object {
        private val PACKED_REGEX =
            Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',\s*\d+,\s*\d+,\s*'(.*?)'\.split\('\|'\)""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
        private val LINKS_REGEX = Regex("""(hls\d)":"([^"]+)""")
        private val QUALITY_REGEX = Regex("""qualityLabels[^{]*\{"\d+":"(\d+)p""")
        private val SUBTITLE_REGEX = Regex("""file:"(https?://[^"]+?\.vtt[^"]*)"[^}]*?label:"([^"]+)"""")
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

    /** Parse satu halaman player ter-unpack; true jika link berhasil dikirim. */
    protected suspend fun extractFromPage(
        page: String,
        pageUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val packed = PACKED_REGEX.find(page) ?: return false
        val unpacked = unpack(packed.groupValues[1], packed.groupValues[2].split('|'))

        val links = LINKS_REGEX.findAll(unpacked)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()

        // Urutan sama dengan setup jwplayer: links.hls4||links.hls3||links.hls2.
        // hls4 bisa path relatif -> resolve terhadap host halaman player.
        val raw = links["hls4"] ?: links["hls3"] ?: links["hls2"] ?: return false
        val master = if (raw.startsWith("http")) raw
            else "https://${URI(pageUrl).host}$raw"
        val quality = QUALITY_REGEX.find(unpacked)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        SUBTITLE_REGEX.findAll(unpacked).forEach { m ->
            subtitleCallback(newSubtitleFile(m.groupValues[1], m.groupValues[2]))
        }

        val playerReferer = referer ?: "https://${URI(pageUrl).host}/"
        callback.invoke(
            newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                this.referer = playerReferer
                this.quality = quality
            }
        )
        return true
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer ?: "$mainUrl/", timeout = 30_000L).text
        extractFromPage(page, url, referer, subtitleCallback, callback)
    }
}

/**
 * morencius.com/embed/<code> — player vplayer langsung tanpa gate.
 */
class Morencius : VidhidePacked() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}

/**
 * hgcloud.to/e/<code> kini berupa halaman gate JS ("Page is loading...") yang
 * memilih host player acak lalu navigasi — tanpa JS tidak ada sources. Player
 * vplayer lengkap tetap tersedia untuk path /e/<code> yang sama di host-host
 * klon; vibuxer.com terverifikasi menyajikannya, dua lainnya hasil decode gate.
 */
class Hgcloud : VidhidePacked() {
    override var name = "Hgcloud"
    override var mainUrl = "https://hgcloud.to"

    private val fileCodeRegex = Regex("""/e/(\w+)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = fileCodeRegex.find(url)?.groupValues?.get(1) ?: return
        val candidates = listOf(url) + PLAYER_HOSTS.map { "https://$it/e/$code" }
        for (candidate in candidates) {
            val page = runCatching {
                app.get(candidate, referer = referer ?: "$mainUrl/", timeout = 30_000L).text
            }.getOrNull() ?: continue
            if (extractFromPage(page, candidate, referer, subtitleCallback, callback)) return
        }
    }

    companion object {
        private val PLAYER_HOSTS = listOf("vibuxer.com", "niramirus.com", "hgplaycdn.com")
    }
}
