package com.mwk.shared.utils

import com.lagradost.cloudstream3.utils.Qualities

private object QualityRegex {
    val QUALITY_PIXEL = Regex("(\\d{3,4})[pP]")
}

fun getIndexQuality(str: String?): Int {
    return QualityRegex.QUALITY_PIXEL.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P360.value
        "480p" -> Qualities.P480.value
        "720p" -> Qualities.P720.value
        "1080p" -> Qualities.P1080.value
        "1080p Ultra" -> Qualities.P1080.value
        else -> com.lagradost.cloudstream3.utils.getQualityFromName(str)
    }
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

fun getLanguageNameFromCode(code: String?): String? {
    return code?.split("_")?.first()?.let { langCode ->
        try {
            java.util.Locale(langCode).displayLanguage.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
            }
        } catch (e: Exception) {
            langCode
        }
    }
}

fun getVipLanguage(str: String): String {
    return when (str) {
        "in_ID" -> "Indonesian"
        "pt" -> "Portuguese"
        else -> str.split("_").first().let { code ->
            getLanguageNameFromCode(code) ?: code
        }
    }
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null || episode == null) {
        "" to ""
    } else {
        (if (season < 10) "0$season" else "$season") to (if (episode < 10) "0$episode" else "$episode")
    }
}

fun getTitleSlug(title: String? = null): Pair<String?, String?> {
    val slug = title.createSlug()
    return slug?.replace("-", "\\W") to title?.replace(" ", "_")
}
