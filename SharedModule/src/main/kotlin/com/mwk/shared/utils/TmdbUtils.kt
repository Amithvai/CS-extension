package com.mwk.shared.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.mwk.shared.data.TmdbImagesResponse
import com.mwk.shared.data.TmdbLogo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private object TmdbUtilsRegex {
    val FULL_TAG = Regex("(?i)(.*)\\.(?:mkv|mp4|avi)")
    val QUALITY_TAG = Regex("(?i)\\d{3,4}[pP]\\.?(.*?)\\.(mkv|mp4|avi)")
    val UHD_TAG = Regex("\\d{3,4}[Pp]\\.?(.*?)\\[")
    val FILE_SIZE = Regex("(?i)([\\d.]+\\s*(?:gb|mb))")
    val NON_ALPHA_NUM = Regex("[^a-zA-Z\\d]")
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie || type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val imageResponse = runCatching {
        app.get(url, timeout = 15_000L).text.let { tryParseJson<TmdbImagesResponse>(it) }
    }.getOrNull() ?: return null
    val logos = imageResponse.logos?.filter { !it.filePath.isNullOrBlank() } ?: return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: TmdbLogo) = o.filePath ?: ""
    fun isSvg(o: TmdbLogo) = path(o).endsWith(".svg", true)
    fun urlOf(o: TmdbLogo) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: TmdbLogo? = null

    for (logo in logos) {
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.iso6391?.trim()?.lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: TmdbLogo? = null
    var bestSvg: TmdbLogo? = null

    fun voted(o: TmdbLogo) = (o.voteAverage ?: 0.0) > 0 && (o.voteCount ?: 0) > 0

    fun better(a: TmdbLogo?, b: TmdbLogo): Boolean {
        if (a == null) return true
        val aAvg = a.voteAverage ?: 0.0
        val aCnt = a.voteCount ?: 0
        val bAvg = b.voteAverage ?: 0.0
        val bCnt = b.voteCount ?: 0
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (logo in logos) {
        if (!voted(logo)) continue
        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }
    return null
}

fun getIndexQualityTags(str: String?, fullTag: Boolean = false): String {
    return if (fullTag) TmdbUtilsRegex.FULL_TAG.find(str ?: "")?.groupValues?.get(1)
        ?.trim() ?: str ?: "" else TmdbUtilsRegex.QUALITY_TAG.find(
        str ?: ""
    )?.groupValues?.getOrNull(1)
        ?.replace(".", " ")?.trim() ?: str ?: ""
}

fun getUhdTags(str: String?): String {
    return TmdbUtilsRegex.UHD_TAG.find(str ?: "")?.groupValues?.getOrNull(1)
        ?.replace(".", " ")?.trim()
        ?: str ?: ""
}

fun getIndexSize(str: String?): String? {
    return TmdbUtilsRegex.FILE_SIZE.find(str ?: "")?.groupValues?.getOrNull(1)?.trim()
}

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        System.currentTimeMillis() < dateTime
    } catch (t: Throwable) {
        false
    }
}

fun getSeason(month: Int?): String? {
    val seasons = arrayOf(
        "Winter", "Winter", "Spring", "Spring", "Spring", "Summer",
        "Summer", "Summer", "Fall", "Fall", "Fall", "Winter"
    )
    if (month == null) return null
    return seasons.getOrNull(month - 1)
}

fun bytesToGigaBytes(number: Double): Double = number / 1024000000

fun getKisskhTitle(str: String?): String? {
    return str?.replace(TmdbUtilsRegex.NON_ALPHA_NUM, "-")
}
