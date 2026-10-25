package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import kotlin.math.roundToInt
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://movieboxhd.net"
    private val apiUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    private val defaultPlayerUrl = "https://mzfi.me"
    override val instantLinkLoading = true
    override var name = "MovieBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // Catalog menus paginate deep (filter: ~150k+ items, trending: 200+ pages);
    // rank: menus are short curated lists kept for the popular categories.
    override val mainPage: List<MainPageData> = mainPageOf(
        "filter:1" to "Movies",
        "filter:2" to "TV Series",
        "trending:ONEROOM_MOVIE" to "Trending Movies",
        "trending:ONEROOM_ANIMATION" to "Animation",
        "rank:8049887023940913872" to "Box Office",
        "rank:6528093688173053896" to "Indonesian Movies",
        "rank:5283462032510044280" to "Indonesian Drama",
        "rank:4230655749032234008" to "Asian Movies",
        "rank:4380734070238626200" to "K-Drama",
        "rank:8624142774394406504" to "C-Drama",
        "rank:4133256925603588432" to "J-Drama",
        "rank:1164329479448281992" to "Thai-Drama",
        "rank:62133389738001440" to "Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val (kind, value) = request.data.split(":", limit = 2)
        val items = when (kind) {
            "filter" -> {
                val body = mapOf(
                    "channelId" to (value.toIntOrNull() ?: 1),
                    "page" to page,
                    "perPage" to 28,
                ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                app.post("$apiUrl/subject/filter", requestBody = body, timeout = TIMEOUT)
                    .text.let { AppUtils.tryParseJson<Media>(it) }?.data?.items
            }
            "trending" -> {
                app.get("$apiUrl/subject/trending?tabId=$value&page=$page&perPage=18", timeout = TIMEOUT)
                    .text.let { AppUtils.tryParseJson<Media>(it) }?.data?.subjectList
            }
            else -> {
                app.get("$apiUrl/ranking-list/content?id=$value&page=$page&perPage=12", timeout = TIMEOUT)
                    .text.let { AppUtils.tryParseJson<Media>(it) }?.data?.subjectList
            }
        }?.map { it.toSearchResponse(this) }
            ?: throw ErrorLoadingException("No Data Found")
        return newHomePageResponse(request.name, items)
    }

    // Search requires a guest JWT that the site obtains from the x-user response
    // header on any anonymous API call; cache it for the session.
    @Volatile
    private var guestToken: String? = null

    private suspend fun ensureGuestToken(): String? {
        guestToken?.let { return it }
        return runCatching {
            val res = app.get("$apiUrl/country-code", timeout = TIMEOUT)
            val raw = res.headers["x-user"] ?: return@runCatching null
            AppUtils.tryParseJson<TokenUser>(raw)?.token?.also { guestToken = it }
        }.getOrNull()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = try {
        val token = ensureGuestToken()
        val headers = buildMap<String, String> {
            put("content-type", "application/json")
            token?.let { put("Authorization", "Bearer $it") }
        }
        app.post(
            "$apiUrl/subject/search",
            requestBody = mapOf(
                "keyword" to query,
                "page" to 1,
                "perPage" to 20,
                "subjectType" to 0,
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            headers = headers,
            timeout = TIMEOUT,
        ).text.let { AppUtils.tryParseJson<Media>(it) }
            ?.data?.items
            ?.map { it.toSearchResponse(this) }
            ?: emptyList()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val detailPath = url.removePrefix("$mainUrl/").substringAfterLast('/')
        if (detailPath.isBlank()) return@coroutineScope newMovieLoadResponse("Invalid URL", url, TvType.Movie, "") {}

        val documentDeferred = async {
            runCatching {
                app.get("$apiUrl/detail?detailPath=$detailPath", timeout = TIMEOUT)
                    .text.let { AppUtils.tryParseJson<MediaDetail>(it) }?.data
            }.getOrNull()
        }

        val document = documentDeferred.await() ?: throw ErrorLoadingException("Failed to load details")
        val subject = document.subject ?: throw ErrorLoadingException("Subject not found")
        val subjectId = subject.subjectId.orEmpty()
        val title = subject.title ?: ""
        val poster = subject.cover?.url
        val tags = subject.genre?.split(",")?.map { it.trim() }
        val year = subject.releaseDate?.take(4)?.toIntOrNull()
        val tvType = mapTvType(subject.subjectType)
        val description = subject.description
        val trailer = subject.trailer?.videoAddress?.url
        val rating = subject.imdbRatingValue?.toDoubleOrNull()?.roundToInt()
        val actors = document.stars?.mapNotNull { cast ->
            val name = cast.name ?: return@mapNotNull null
            ActorData(Actor(name, cast.avatarUrl), roleString = cast.character)
        }?.distinctBy { it.actor }
        val recommendations = if (subjectId.isNotEmpty()) {
            runCatching {
                app.get("$apiUrl/subject/detail-rec?subjectId=$subjectId&page=1&perPage=12", timeout = TIMEOUT)
                    .text.let { AppUtils.tryParseJson<Media>(it) }
                    ?.data?.items
                    ?.map { it.toSearchResponse(this@MovieboxProvider) }
            }.getOrNull()
        } else {
            null
        }

        if (tvType == TvType.TvSeries) {
            val episodes = document.resource?.seasons?.mapNotNull { season ->
                val maxEp = season.maxEp ?: return@mapNotNull null
                val episodeNumbers = if (season.allEp.isNullOrEmpty()) {
                    (1..maxEp).toList()
                } else {
                    season.allEp.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..maxEp }
                }
                episodeNumbers.map { ep ->
                    newEpisode(LoadData(subjectId, season.se, ep, detailPath).toJson()) {
                        this.season = season.se
                        this.episode = ep
                    }
                }
            }?.flatten().orEmpty()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(title, url, tvType, LoadData(subjectId, detailPath = detailPath).toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    // The player domain is served dynamically; the SPA referer must match it exactly
    // (host + /spa/videoPlayPage/movies/<detailPath>?id=<subjectId>&...) or the play
    // API silently returns empty streams.
    @Volatile
    private var playerBase: String? = null

    private suspend fun ensurePlayerBase(): String {
        playerBase?.let { return it }
        val fetched = runCatching {
            app.get("$apiUrl/media-player/get-domain", timeout = TIMEOUT)
                .text.let { AppUtils.tryParseJson<DomainResponse>(it) }?.data
                ?.trimEnd('/')?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: defaultPlayerUrl
        playerBase = fetched
        return fetched
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val media = AppUtils.tryParseJson<LoadData>(data) ?: throw ErrorLoadingException("Gagal memuat data video")
        val mediaId = media.id.orEmpty()
        val detailPath = media.detailPath.orEmpty()
        if (mediaId.isEmpty() || detailPath.isEmpty()) {
            throw ErrorLoadingException("Data video tidak lengkap")
        }
        val season = media.season ?: 0
        val episode = media.episode ?: 0
        val base = ensurePlayerBase()
        val referer = "$base$SPA_VIDEO_PAGE/$detailPath?id=$mediaId&detailSe=&detailEp=&lang=en&type=%2Fmovie%2Fdetail"

        val play = runCatching {
            app.get(
                "$base/wefeed-h5api-bff/subject/play" +
                    "?subjectId=$mediaId&se=$season&ep=$episode&detailPath=$detailPath" +
                    "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1",
                referer = referer,
                timeout = TIMEOUT,
            ).text.let { AppUtils.tryParseJson<Media>(it) }?.data
        }.getOrNull()

        val mp4Streams = play?.streams.orEmpty()
        val dashStreams = play?.dash.orEmpty()
        if (mp4Streams.isEmpty() && dashStreams.isEmpty()) {
            throw ErrorLoadingException(
                "Stream tidak tersedia. Endpoint MovieBox mungkin berubah atau konten ini premium."
            )
        }

        val captionJob = async {
            val captionStream = dashStreams.firstOrNull() ?: mp4Streams.firstOrNull()
            val streamId = captionStream?.id ?: return@async
            val format = captionStream.format ?: return@async
            runCatching {
                app.get(
                    "$apiUrl/subject/caption?format=$format&id=$streamId&subjectId=$mediaId&detailPath=$detailPath",
                    referer = "$base/",
                    timeout = TIMEOUT,
                ).text.let { AppUtils.tryParseJson<Media>(it) }?.data?.captions
            }.getOrNull().orEmpty().forEach { subtitle ->
                val url = subtitle.url ?: return@forEach
                subtitleCallback.invoke(newSubtitleFile(subtitle.lanName.orEmpty(), url))
            }
        }

        mp4Streams.forEach { source ->
            val url = source.url ?: return@forEach
            callback.invoke(
                newExtractorLink(this@MovieboxProvider.name, this@MovieboxProvider.name, url, INFER_TYPE) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }
        dashStreams.forEach { source ->
            val url = source.url ?: return@forEach
            callback.invoke(
                newExtractorLink("${this@MovieboxProvider.name} DASH", this@MovieboxProvider.name, url, INFER_TYPE) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(source.resolutions)
                    val cookie = source.signCookie.orEmpty()
                    if (cookie.isNotEmpty()) this.headers = mapOf("Cookie" to cookie)
                }
            )
        }
        captionJob.await()
        true
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
    )

    companion object {
        private const val TIMEOUT = 15_000L
        private const val SPA_VIDEO_PAGE = "/spa/videoPlayPage/movies"

        fun mapTvType(subjectType: Int?): TvType = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            else -> TvType.Movie
        }
    }
}

// -- API response models --

data class TokenUser(
    @JsonProperty("token") val token: String? = null,
)

data class DomainResponse(
    @JsonProperty("data") val data: String? = null,
)

data class Media(
    @JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @JsonProperty("subjectList") val subjectList: List<Items>? = null,
        @JsonProperty("items") val items: List<Items>? = null,
        @JsonProperty("streams") val streams: List<Stream>? = null,
        @JsonProperty("dash") val dash: List<Stream>? = null,
        @JsonProperty("captions") val captions: List<Caption>? = null,
    ) {
        data class Stream(
            @JsonProperty("id") val id: String? = null,
            @JsonProperty("format") val format: String? = null,
            @JsonProperty("url") val url: String? = null,
            @JsonProperty("resolutions") val resolutions: String? = null,
            @JsonProperty("signCookie") val signCookie: String? = null,
        )

        data class Caption(
            @JsonProperty("lan") val lan: String? = null,
            @JsonProperty("lanName") val lanName: String? = null,
            @JsonProperty("url") val url: String? = null,
        )
    }
}

data class MediaDetail(
    @JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @JsonProperty("subject") val subject: Items? = null,
        @JsonProperty("stars") val stars: List<Star>? = null,
        @JsonProperty("resource") val resource: Resource? = null,
    ) {
        data class Star(
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("character") val character: String? = null,
            @JsonProperty("avatarUrl") val avatarUrl: String? = null,
        )

        data class Resource(
            @JsonProperty("seasons") val seasons: List<Season>? = null,
        ) {
            data class Season(
                @JsonProperty("se") val se: Int? = null,
                @JsonProperty("maxEp") val maxEp: Int? = null,
                @JsonProperty("allEp") val allEp: String? = null,
            )
        }
    }
}

data class Items(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("duration") val duration: Long? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("cover") val cover: Cover? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
    @JsonProperty("countryName") val countryName: String? = null,
    @JsonProperty("trailer") val trailer: Trailer? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
) {
    data class Cover(@JsonProperty("url") val url: String? = null)

    data class Trailer(
        @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null,
    ) {
        data class VideoAddress(@JsonProperty("url") val url: String? = null)
    }
}

private fun Items.toSearchResponse(provider: MovieboxProvider): SearchResponse {
    val path = detailPath ?: subjectId
    return provider.newMovieSearchResponse(
        title ?: "",
        "${provider.mainUrl}/moviedetail/$path",
        MovieboxProvider.mapTvType(subjectType),
        false,
    ) {
        this.posterUrl = cover?.url
    }
}
