package com.phisher98

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val qualityPixelRegex = Regex("(\\d{3,4})[pP]")
private val qualityCommonRegex = Regex("""\b(2160p|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)
private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)

fun getIndexQuality(str: String?): Int {
    return qualityPixelRegex.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getLanguage(language: String?): String? {
    return SubtitleHelper.fromTagToEnglishLanguageName(language ?: return null)
        ?: SubtitleHelper.fromTagToEnglishLanguageName(language.substringBefore("-"))
}

data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("fileIdx") val fileIdx: Int? = null
)

data class DebianRoot(
    @JsonProperty("streams") val streams: List<Stream> = emptyList(),
    @JsonProperty("cacheMaxAge") val cacheMaxAge: Long = 0,
    @JsonProperty("staleRevalidate") val staleRevalidate: Long = 0,
    @JsonProperty("staleError") val staleError: Long = 0
)

data class Stream(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("title") val title: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints = BehaviorHints()
)

data class BehaviorHints(
    @JsonProperty("bingeGroup") val bingeGroup: String? = null,
    @JsonProperty("filename") val filename: String? = null
)

data class AniZipEpisodes(
    val episodes: Map<String, AniZipEpisode>? = null
)
data class AniZipEpisode(
    val anidbEid: Int? = null
)

fun getAnidbEid(jsonString: String, episodeNumber: Int?): Int? {
    if (episodeNumber == null) return null
    return try {
        val response = tryParseJson<AniZipEpisodes>(jsonString)
        response?.episodes?.get(episodeNumber.toString())?.anidbEid
    } catch (e: Exception) {
        null
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @param:JsonProperty("themoviedb_id") val themoviedbId: String? = null,
    @param:JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @param:JsonProperty("imdb_id") val imdbId: String? = null,
    @param:JsonProperty("mal_id") val malId: Int? = null,
    @param:JsonProperty("anilist_id") val anilistId: Int? = null,
    @param:JsonProperty("kitsu_id") val kitsuid: String? = null,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageData(
    @param:JsonProperty("coverType") val coverType: String?,
    @param:JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @param:JsonProperty("episode") val episode: String?,
    @param:JsonProperty("airdate") val airdate: String?,
    @param:JsonProperty("airDateUtc") val airDateUtc: String?,
    @param:JsonProperty("length") val length: Int?,
    @param:JsonProperty("runtime") val runtime: Int?,
    @param:JsonProperty("image") val image: String?,
    @param:JsonProperty("title") val title: Map<String, String>?,
    @param:JsonProperty("overview") val overview: String?,
    @param:JsonProperty("rating") val rating: String?,
    @param:JsonProperty("finaleType") val finaleType: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @param:JsonProperty("titles") val titles: Map<String, String>? = null,
    @param:JsonProperty("images") val images: List<ImageData>? = null,
    @param:JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null,
    @param:JsonProperty("mappings") val mappings: MetaMappings? = null
)

fun extractResolutionFromDescription(description: String?): String? {
    if (description.isNullOrBlank()) return null
    return qualityCommonRegex.find(description)?.value
}

fun getDate(): TmdbDate {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val now = Calendar.getInstance()
    val today = formatter.format(now.time)

    val nextWeekCal = Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, 1) }
    val nextWeek = formatter.format(nextWeekCal.time)

    val lastWeekCal = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        add(Calendar.WEEK_OF_YEAR, -1)
    }
    val lastWeekStart = formatter.format(lastWeekCal.time)

    val monthCal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    val monthStart = formatter.format(monthCal.time)

    return TmdbDate(today, nextWeek, lastWeekStart, monthStart)
}

data class TmdbImagesResponse(
    val logos: List<TmdbLogo>? = null
)
data class TmdbLogo(
    @param:JsonProperty("aspect_ratio") val aspectRatio: Double? = null,
    val height: Int? = null,
    @param:JsonProperty("iso_639_1") val iso6391: String? = null,
    @param:JsonProperty("file_path") val filePath: String? = null,
    @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    @param:JsonProperty("vote_count") val voteCount: Int? = null,
    val width: Int? = null
)

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
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

data class TorrentsDBResponse(
    val streams: List<TorrentsDBStream>?
)

data class TorrentsDBStream(
    val name: String?,
    val title: String?,
    val infoHash: String,
    val fileIdx: Int?,
    val behaviorHints: TorrentsDBBehaviorHints?,
    val sources: List<String>?
)

data class TorrentsDBBehaviorHints(
    val bingeGroup: String?,
    val filename: String?
)

fun filteredCallback(
    sharedPref: SharedPreferences,
    callback: (ExtractorLink) -> Unit
): (ExtractorLink) -> Unit {

    val excludedQualities = sharedPref.getString("qualityfilter", "")
        ?.lowercase()
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    val maxSize = sharedPref.getString("sizefilter", "")?.toDoubleOrNull()

    return fun(link: ExtractorLink) {

        val detectedQuality = when (link.quality) {
            in 2000..3000 -> "4k"
            in 1080..1999 -> "1080p"
            in 720..1079 -> "720p"
            in 480..719 -> "480p"
            else -> "other"
        }

        if (detectedQuality in excludedQualities) return

        val sizeMatch = sizeRegex.find(link.name)

        val sizeValue = sizeMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val sizeUnit = sizeMatch?.groupValues?.get(2)

        val sizeGB = when (sizeUnit?.uppercase()) {
            "GB" -> sizeValue
            "MB" -> sizeValue?.div(1024)
            else -> null
        }
        if (maxSize != null && sizeGB != null && sizeGB > maxSize) return
        callback(link)
    }
}

fun buildTorrentioApiUrl(sharedPref: SharedPreferences, mainUrl: String): String {
    val sort = sharedPref.getString("sort", "qualitysize")
    val languageOption = sharedPref.getString("language", "")
    val qualityFilter = sharedPref.getString("qualityfilter", "")
    val limit = sharedPref.getString("limit", "")
    val sizeFilter = sharedPref.getString("sizefilter", "")
    val debridProvider = sharedPref.getString("debrid_provider", "")
    val debridKey = sharedPref.getString("debrid_key", "")

    val params = mutableListOf<String>()
    if (!sort.isNullOrEmpty()) params += "sort=${sort?.lowercase()}"
    val provider = sharedPref.getString("provider", "")
    if (!provider.isNullOrEmpty()) params += "providers=${provider?.lowercase()}"
    if (!languageOption.isNullOrEmpty()) params += "language=${languageOption?.lowercase()}"
    if (!qualityFilter.isNullOrEmpty()) params += "qualityfilter=${qualityFilter?.lowercase()}"
    val limitStr = limit?.toIntOrNull()
    if (limitStr != null && limitStr in 1..999) params += "limit=$limitStr"
    if (!sizeFilter.isNullOrEmpty()) params += "sizefilter=$sizeFilter"
    if (!debridProvider.isNullOrEmpty() && !debridKey.isNullOrEmpty() && debridProvider?.lowercase() != "none") {
        params += "${debridProvider?.lowercase()}=$debridKey"
    }

    val query = params.joinToString("%7C")
    return "$mainUrl/$query"
}

fun buildMeteorUrl(sharedPref: SharedPreferences, baseUrl: String): String {
    val debridProvider = sharedPref.getString("debrid_provider", "") ?: ""
    val debridKey = sharedPref.getString("debrid_key", "") ?: ""
    val languagesPref = sharedPref.getString("language", "") ?: ""
    val limit = sharedPref.getString("limit", "0") ?: "0"
    val sizeFilter = sharedPref.getString("sizefilter", "0") ?: "0"

    val preferredLangs = if (languagesPref.isNotEmpty()) {
        languagesPref.split(",").joinToString(",") { "\"${it.lowercase()}\"" }
    } else {
        "\"en\",\"multi\""
    }

    val jsonStr = """{"debridService":"${debridProvider.lowercase()}","debridApiKey":"$debridKey","cachedOnly":false,"removeTrash":true,"removeSamples":true,"removeAdult":false,"exclude3D":false,"enableSeaDex":false,"minSeeders":0,"maxResults":${limit.toIntOrNull() ?: 0},"maxResultsPerRes":0,"maxSize":${sizeFilter.toIntOrNull() ?: 0},"resolutions":[],"languages":{"preferred":[$preferredLangs],"required":[],"exclude":[]},"resultFormat":["title","quality","size","audio"],"sortOrder":["cached","resolution","quality","seeders","size","pack","language","seadex"]}"""

    val encoded = android.util.Base64.encodeToString(
        jsonStr.toByteArray(),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )

    return "$baseUrl/$encoded"
}
