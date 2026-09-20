package com.anichin

import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

/**
 * Extractor Dailymotion dengan perbaikan SUARA HILANG di CloudStream.
 *
 * ============================ AKAR MASALAH ============================
 * Dailymotion menyajikan video sebagai fMP4 HLS dengan AUDIO TERPISAH:
 *
 *   Master playlist berisi:
 *     #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="0_aac_q2",...,URI="...aac_q2_0/manifest.m3u8"
 *     #EXT-X-STREAM-INF:...,RESOLUTION=848x480,AUDIO="0_aac_q2"
 *     https://vod3.cf.dmcdn.net/.../h264_hq/3/manifest.m3u8
 *
 *   Variant playlist (h264_hq) HANYA berisi segmen video — init.mp4-nya
 *   cuma punya handler track 'vide' (tanpa 'soun'), sudah diverifikasi.
 *
 * Extractor Dailymotion bawaan core mengemit URL MASTER ke player, dan
 * player/ExoPlayer pada sebagian build CloudStream tidak mengaitkan
 * #EXT-X-MEDIA:TYPE=AUDIO ke variant (audio group diabaikan) sehingga video
 * berjalan tanpa suara. Helper core M3u8Helper2 juga hanya membaca
 * parsed.getVariants() dan membuang audio group sepenuhnya.
 *
 * ============================== SOLUSI ================================
 * 1. Fetch metadata JSON -> dapat URL master m3u8.
 * 2. Fetch master playlist -> ekstrak:
 *      - URL audio track (#EXT-X-MEDIA:TYPE=AUDIO)
 *      - URL subtitle (#EXT-X-MEDIA:TYPE=SUBTITLES)
 *      - daftar variant video + RESOLUTION (kualitas).
 * 3. Emit TIAP variant sebagai ExtractorLink (video-only) dan lampirkan
 *    audio track lewat field `audioTracks` (List<AudioFile>).
 *    CS3IPlayer menggabungkan video + audio memakai MergingMediaSource
 *    (didukung resmi core: getAudioSources() -> MergingMediaSource).
 *
 * Catatan: CDN Dailymotion (vod3.cf.dmcdn.net) TIDAK memerlukan Referer
 * maupun User-Agent khusus untuk master/variant/segmen — sudah diverifikasi
 * dengan beberapa skenario header. Jadi referer hanya dipasang sebagai
 * jaga-jaga.
 */
open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return

        // 1) Metadata JSON -> master m3u8 (+ subtitle SRT cadangan)
        val metadataText = runCatching {
            app.get("$baseUrl/player/metadata/video/$id", referer = embedUrl, timeout = 15_000L).text
        }.getOrNull()

        val masterUrl = metadataText?.let { text ->
            QUALITY_URL_REGEX.findAll(text)
                .map { it.groupValues[1].replace("\\/", "/") }
                .firstOrNull { it.contains(".m3u8") }
        }

        // Subtitle dari metadata (SRT — paling kompatibel).
        // Label dilacak agar tidak dobel dengan subtitle WebVTT dari master.
        val emittedSubs = mutableSetOf<String>()
        if (metadataText != null) {
            emitMetadataSubtitles(metadataText, subtitleCallback, emittedSubs)
        }

        if (masterUrl.isNullOrBlank()) {
            // Tidak ada master? Tidak bisa lanjut.
            return
        }

        // 2) Fetch master playlist -> variant + audio + subtitle
        val master = runCatching {
            app.get(masterUrl, referer = embedUrl, timeout = 15_000L).text
        }.getOrNull() ?: return

        // Audio per GROUP-ID. Dailymotion bisa punya >1 audio group
        // (mis. 0_aac_q1 untuk 288p, 0_aac_q2 untuk 480p). Pasangkan
        // sesuai AUDIO="<group-id>" pada tiap variant supaya sinkron.
        val audioByGroup = mutableMapOf<String, AudioFile>()
        for (match in AUDIO_MEDIA_REGEX.findAll(master)) {
            val groupId = match.groupValues[1]
            val audioUrl = match.groupValues[2].takeIf { it.isNotBlank() } ?: continue
            audioByGroup[groupId] = newAudioFile(audioUrl)
        }
        // Fallback: kalau tidak ada group yang cocok, pakai audio apa pun.
        val anyAudio = audioByGroup.values.firstOrNull()

        // Subtitle WebVTT dari master playlist (hanya bila belum ada dari metadata)
        for (match in SUBTITLE_MEDIA_REGEX.findAll(master)) {
            val line = match.value
            val uri = URI_ATTR_REGEX.find(line)?.groupValues?.get(1) ?: continue
            val label = NAME_ATTR_REGEX.find(line)?.groupValues?.get(1) ?: "Subtitle"
            if (uri.isNotBlank() && emittedSubs.add(label)) {
                subtitleCallback(newSubtitleFile(label, uri))
            }
        }

        // 3) Emit tiap variant video + audio track yang cocok
        var found = false
        for (match in VARIANT_REGEX.findAll(master)) {
            val attrs = match.groupValues[1]
            val variantUrl = match.groupValues[2].trim()
            if (variantUrl.isBlank()) continue

            val height = RESOLUTION_REGEX.find(attrs)
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
            val qualityName = if (height != null && height > 0) "${height}p" else this.name

            val link = newExtractorLink(this.name, qualityName, variantUrl, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.quality = getQualityFromName(qualityName)
            }
            val groupId = AUDIO_ATTR_REGEX.find(attrs)?.groupValues?.get(1)
            val audio = (groupId?.let { audioByGroup[it] }) ?: anyAudio
            if (audio != null) {
                link.audioTracks = listOf(audio)
            }
            callback(link)
            found = true
        }

        // Fallback: kalau master tidak punya variant sama sekali, kirim master
        // apa adanya (masih lebih baik daripada tidak ada link).
        if (!found) {
            val link = newExtractorLink(this.name, this.name, masterUrl, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
            }
            if (anyAudio != null) {
                link.audioTracks = audioByGroup.values.toList()
            }
            callback(link)
        }
    }

    /** Subtitle dari metadata JSON: {"data":{"id-auto":{"label":"...","urls":["...srt"]}}} */
    private suspend fun emitMetadataSubtitles(
        response: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emittedLabels: MutableSet<String>
    ) {
        for (match in META_SUBTITLE_REGEX.findAll(response)) {
            val label = match.groupValues[1]
            val url = match.groupValues[2].replace("\\/", "/")
            if (url.isNotBlank() && emittedLabels.add(label)) {
                subtitleCallback(newSubtitleFile(label, url))
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=").substringBefore("&")
            if (videoId.isBlank()) return null
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val id = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    companion object {
        /** URL m3u8 di dalam metadata JSON (key "url" pada qualities). */
        private val QUALITY_URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")

        /**
         * Satu blok variant:
         *   #EXT-X-STREAM-INF:<atribut>
         *   <url>
         * Group 1 = atribut (untuk RESOLUTION), Group 2 = URL (tanpa fragmen).
         */
        private val VARIANT_REGEX = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n[^\S\n]*([^\n#\s]+)""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")

        /**
         * Audio track terpisah:
         *   #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="0_aac_q2",...,URI="..."
         * Group 1 = GROUP-ID, Group 2 = URI.
         */
        private val AUDIO_MEDIA_REGEX = Regex(
            """#EXT-X-MEDIA:TYPE=AUDIO[^\n]*?GROUP-ID="([^"]+)"[^\n]*?URI="([^"]+)""""
        )

        /** AUDIO="<group-id>" pada baris #EXT-X-STREAM-INF */
        private val AUDIO_ATTR_REGEX = Regex("""AUDIO="([^"]+)"""")

        /** Subtitle track: #EXT-X-MEDIA:TYPE=SUBTITLES,... */
        private val SUBTITLE_MEDIA_REGEX =
            Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*""")
        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")
        private val NAME_ATTR_REGEX = Regex("""NAME="([^"]*)"""")

        /** Subtitle SRT dari metadata: "label":"...","urls":["..."] */
        private val META_SUBTITLE_REGEX =
            Regex(""""label"\s*:\s*"([^"]+)"\s*,\s*"urls"\s*:\s*\[\s*"([^"]+)"""")
    }
}
