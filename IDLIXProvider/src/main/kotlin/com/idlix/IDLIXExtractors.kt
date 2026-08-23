package com.idlix

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.fixUrl

/**
 * Thin wrappers untuk hoster yang paling sering dipakai IDLIX/LK21/Rebahin.
 * loadExtractor generic sudah handle FileMoon/Streamtape/Doodstream,
 * tapi kita register explicit untuk memastikan prioritas & referer benar.
 */

class FileMoonExtractor : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // FileMoon via cloudstream's built-in FileMoon extractor logic (packed js)
        val res = app.get(url, referer = referer ?: mainUrl).text
        // Try to extract m3u8 via packed extractor helper
        val m3u8 = Regex("""file:\s*"(https[^"]+\.m3u8[^"]*)"""").find(res)?.groupValues?.get(1)
            ?: Regex("""sources.*?file.*?\"(https[^\"]+m3u8[^\"]*)\"""").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            M3u8Helper.generateM3u8(name, fixUrl(m3u8), referer ?: mainUrl).forEach(callback)
            return
        }
        // Fallback to generic loadExtractor will be handled if this fails; try iframe src
        val iframeSrc = Regex("""iframe[^>]+src="([^"]+)"""").find(res)?.groupValues?.get(1)
        if (iframeSrc != null && iframeSrc != url) {
            getUrl(iframeSrc, referer, subtitleCallback, callback)
        }
    }
}

class StreamtapeExtractor : Filesim() {
    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false
}

class DoodstreamExtractor : ExtractorApi() {
    override val name = "Doodstream"
    override val mainUrl = "https://doodstream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Doodstream extractor similar to cloudstream's DoodStream
        val res = app.get(url, referer = referer).text
        val md5 = Regex("""/pass_md5/([^']+)""").find(res)?.groupValues?.get(1)
        if (md5 != null) {
            val token = md5.substring(maxOf(0, md5.length - 10))
            // Actual dood logic is handled by generic extractor; delegate
        }
        // Delegate to generic
        com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
    }
}

class MixDropExtractor : MixDrop() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.co"
}

class StreamWishExtractorIdlix : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class HxfileExtractor : ExtractorApi() {
    override val name = "Hxfile"
    override val mainUrl = "https://hxfile.co"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer).text
        val m3u8 = Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                }
            )
        }
    }
}
