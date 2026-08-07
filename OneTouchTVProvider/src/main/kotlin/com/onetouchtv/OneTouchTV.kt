package com.onetouchtv

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder

class OneTouchTV : MainAPI() {
    override var mainUrl = base64Decode("aHR0cHM6Ly9hcGkzLmRldmNvcnAubWU=")
    override var name = "OneTouchTV"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "vod/home" to "Home",
        "vod/movie?page=%d" to "Movie",
        "vod/filter?country=korean&page=%d" to "Korean",
        "vod/filter?country=chinese&page=%d" to "Chinese",
        "vod/filter?country=thai&page=%d" to "Thai",
        "vod/filter?country=japanese&page=%d" to "Japanese",
        "vod/filter?country=filipino&page=%d" to "Filipino",
        "vod/filter?country=taiwanese&page=%d" to "Taiwanese"
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/vod/search?page=$page&keyword=$encodedQuery"
        val responseText = try {
            app.get(url, referer = "$mainUrl/", timeout = 15_000L).text
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to fetch search data: ${e.message}")
        }
        val decryptedJson = try {
            decryptString(responseText)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to decrypt search response: ${e.message}")
        }
        val results: List<SearchResult> = if (decryptedJson.trim().startsWith("[")) {
            tryParseJson<Array<SearchResult>>(decryptedJson)?.toList()
        } else {
            tryParseJson<Search>(decryptedJson)?.result
        } ?: throw ErrorLoadingException("Failed to parse search results")
        if (results.isEmpty()) return null
        return results.map { result ->
            newTvSeriesSearchResponse(
                result.title ?: "Unknown",
                "$mainUrl/vod/${result.id}/detail",
                if (result.type.equals("movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries
            ) { posterUrl = result.image }
        }.toNewSearchResponseList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page)}"
        val rawResponse = try {
            app.get(url, timeout = 15_000L).text
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to fetch ${request.name}: ${e.message}")
        }
        val decryptedJson = try {
            decryptString(rawResponse)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to decrypt ${request.name} response: ${e.message}")
        }
        if (decryptedJson.isBlank()) throw ErrorLoadingException("${request.name}: empty response after decryption")

        return if (request.name == "Home") {
            val parser = tryParseJson<MediaResult>(decryptedJson)
                ?: throw ErrorLoadingException("Failed to parse home page data")
            val allMedia = buildList {
                addAll(parser.randomSlideShow ?: emptyList())
                addAll(parser.recents ?: emptyList())
            }
            val uniqueMedia = allMedia.distinctBy { it.id2 ?: it.id ?: it.title }
            val filteredMedia = uniqueMedia.filter { media ->
                settingsForProvider.enableAdult || !(media.type?.contains("RAW", ignoreCase = true) ?: false)
            }
            val groupedByCountry = filteredMedia.groupBy { it.country?.trim()?.lowercase() ?: "unknown" }
            val homeLists = groupedByCountry.mapNotNull { (country, items) ->
                if (items.size > 4) {
                    HomePageList(
                        name = country.replaceFirstChar { it.uppercase() },
                        list = items.map { it.toSearchResponse(mainUrl) },
                        isHorizontalImages = false
                    )
                } else null
            }
            newHomePageResponse(list = homeLists, hasNext = false)
        } else {
            val items = tryParseJson<Array<SearchResult>>(decryptedJson)?.toList()
                ?: throw ErrorLoadingException("Failed to parse ${request.name} data")
            val tvType = if (request.name == "Movie") TvType.Movie else TvType.TvSeries
            newHomePageResponse(
                list = listOf(HomePageList(
                    name = request.name,
                    list = items.map { item ->
                        newTvSeriesSearchResponse(item.title ?: "Unknown", "$mainUrl/vod/${item.id}/detail", tvType) { posterUrl = item.image }
                    },
                    isHorizontalImages = false
                )),
                hasNext = items.size >= 30
            )
        }
    }

    private fun MediaItem.toSearchResponse(mainUrl: String): SearchResponse =
        newTvSeriesSearchResponse(title ?: "Unknown", "$mainUrl/vod/${id2 ?: id ?: "0"}/detail", TvType.Movie) { posterUrl = image }

    override suspend fun load(url: String): LoadResponse {
        val rawResponse = try {
            app.get(url, timeout = 15_000L).text
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to fetch details: ${e.message}")
        }
        val decryptedJson = try {
            decryptString(rawResponse)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to decrypt detail response: ${e.message}")
        }
        val parser = tryParseJson<LoadData>(decryptedJson) ?: throw ErrorLoadingException("Failed to parse detail data")
        val title = parser.title ?: "Unknown"
        val poster = parser.image ?: ""
        val backgroundPoster = parser.poster?.replace("image-7wk.pages.dev", "image-v1.pages.dev")?.takeIf { it.isNotBlank() && it != "null" } ?: poster
        val description = parser.description ?: ""
        val year = parser.year?.toIntOrNull()
        val status = when (parser.status) {
            "Finished Airing" -> ShowStatus.Completed
            "ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val actors = parser.actors.map { ActorData(Actor(it.name ?: "", it.image ?: "")) }
        val tags = parser.genres.map { it.replaceFirstChar(Char::uppercase) }
        val episodes = parser.episodes.mapNotNull { ep ->
            val identifier = ep.identifier ?: return@mapNotNull null
            val playId = ep.playId ?: return@mapNotNull null
            newEpisode("$mainUrl/vod/$identifier/episode/$playId") { name = "Episode ${ep.episode ?: "?"}" }
        }
        val recommendation = fetchRecommendations()
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.backgroundPosterUrl = backgroundPoster
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.showStatus = status
            this.year = year
            this.actors = actors
            this.recommendations = recommendation
        }
    }

    private suspend fun fetchRecommendations(): List<SearchResponse> {
        return try {
            val rawTopResponse = app.get("$mainUrl/vod/top", timeout = 15_000L).text
            val topJson = decryptString(rawTopResponse)
            val topParser = tryParseJson<OneTouchTVParser>(topJson) ?: return emptyList()
            buildList {
                topParser.day?.forEach { add(it.toSearchResponse()) }
                topParser.week?.forEach { add(it.toSearchResponse()) }
                topParser.month?.forEach { add(it.toSearchResponse()) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun OneTouchTVParser.TopMedia.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(title ?: "Unknown", "$mainUrl/vod/${id2 ?: id ?: "0"}/detail", TvType.Movie) { posterUrl = image }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val rawResponse = try {
            app.get(data, timeout = 15_000L).text
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to fetch episode data: ${e.message}")
        }
        val decryptedJson = try {
            decryptString(rawResponse)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to decrypt episode response: ${e.message}")
        }
        val (sources, tracks) = parseSourcesAndTracks(decryptedJson)
        launch {
            tracks.forEach { track ->
                val file = track.file ?: return@forEach
                subtitleCallback(newSubtitleFile(track.name ?: "Unknown", file))
            }
        }
        launch {
            sources.forEach { src ->
                val url = src.url ?: return@forEach
                val sourceName = src.name?.replaceFirstChar { it.uppercase() } ?: "Source"
                callback(newExtractorLink(sourceName, sourceName, url, INFER_TYPE) {
                    this.quality = getQualityFromName(src.quality ?: "")
                    this.referer = "$mainUrl/"
                    this.headers = src.headers ?: emptyMap()
                })
            }
        }
        true
    }
}
