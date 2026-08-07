package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.ArrayList

class KisskhProvider : MainAPI() {
    companion object {
        private const val KEY_FETCH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        private const val SUB_FETCH_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
        private const val API_TIMEOUT = 30_000L
        private const val KEY_TIMEOUT = 10_000L
        private const val KEY_FETCH_RETRIES = 2
        private const val API_VERSION = "2.8.10"
        private val KEY_CACHE = mutableMapOf<String, String>()
        private val NON_ALPHANUM_REGEX = Regex("[^a-zA-Z0-9]")
    }

    override var mainUrl = "https://kisskh.ovh"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "&sub=0&country=0&status=0&order=2" to "Latest Update",
        "&sub=0&country=2&status=0&order=1" to "Top Kdrama",
        "&sub=0&country=1&status=0&order=1" to "Top Cdrama",
        "&type=3&sub=0&country=0&status=0&order=1" to "Anime",
        "&type=4&sub=0&country=0&status=0&order=1" to "Hollywood",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val res = app.get("$mainUrl/api/DramaList/List?page=$page${request.data}", timeout = API_TIMEOUT).text
            .let { tryParseJson<Responses>(it) }
        val data = res?.data
        val home = data?.mapNotNull { media -> media.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid JSON response")
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = data.size >= 20
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {

        return newAnimeSearchResponse(
            title ?: return null,
            "$title/$id",
            TvType.TvSeries,
        ) {
            this.posterUrl = thumbnail
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchResponse =
            app.get("$mainUrl/api/DramaList/Search?q=$encodedQuery&type=0", referer = "$mainUrl/", timeout = API_TIMEOUT).text
        return tryParseJson<ArrayList<Media>>(searchResponse)?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid JSON response")
    }

    private fun getTitle(str: String): String {
        return str.replace(NON_ALPHANUM_REGEX, "-")
    }

    override suspend fun load(url: String): LoadResponse? {
        val urlParts = url.split("/")
        if (urlParts.size < 2) throw ErrorLoadingException("Invalid URL format")
        val contentId = urlParts.last()
        val titleSlug = urlParts.dropLast(1).lastOrNull() ?: ""
        val res = app.get(
            "$mainUrl/api/DramaList/Drama/$contentId?isq=false",
            referer = "$mainUrl/Drama/$titleSlug?id=$contentId",
            timeout = API_TIMEOUT
        ).text.let { tryParseJson<MediaDetail>(it) }
            ?: throw ErrorLoadingException("Invalid JSON response")

        val episodes = res.episodes?.map { eps ->
            newEpisode(Data(res.title, eps.number, res.id, eps.id).toJson()) {
                this.episode = eps.number
            }
        } ?: throw ErrorLoadingException("No episodes found")

        return newTvSeriesLoadResponse(
            res.title ?: return null,
            url,
            when {
                res.type == "Movie" -> TvType.Movie
                res.type == "Anime" -> TvType.Anime
                episodes.size == 1 -> TvType.Movie
                else -> TvType.TvSeries
            },
            episodes
        ) {
            this.posterUrl = res.thumbnail
            this.year = res.releaseDate?.split("-")?.first()?.toIntOrNull()
            this.plot = res.description
            this.tags = listOf("${res.country}", "${res.status}", "${res.type}")
            this.showStatus = when (res.status) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun getLanguage(sub: Subtitle): String {
        return when {
            !sub.land.isNullOrBlank() -> sub.land
            !sub.label.isNullOrBlank() -> when (sub.label) {
                "Indonesia" -> "id"
                "English" -> "en"
                "Arabic" -> "ar"
                "Khmer" -> "km"
                "Malay" -> "ms"
                else -> sub.label
            }
            else -> "en"
        }
    }

    private fun inferQuality(url: String): Int {
        val lowerUrl = url.lowercase()
        return when {
            // Standard quality markers
            lowerUrl.contains("2160p") || lowerUrl.contains("4k") -> Qualities.P2160.value
            lowerUrl.contains("1080p") -> Qualities.P1080.value
            lowerUrl.contains("720p") -> Qualities.P720.value
            lowerUrl.contains("480p") -> Qualities.P480.value
            lowerUrl.contains("360p") -> Qualities.P360.value
            // Kisskh URL pattern: v{number} where higher = better quality
            // v765 = 720p (default), v1080 = 1080p, etc.
            lowerUrl.contains("v1080") -> Qualities.P1080.value
            lowerUrl.contains("v765") -> Qualities.P720.value
            lowerUrl.contains("v480") -> Qualities.P480.value
            lowerUrl.contains("v360") -> Qualities.P360.value
            // Default to 720p for kisskh streams
            lowerUrl.contains("hls") || lowerUrl.contains("cdnvideo") -> Qualities.P720.value
            else -> Qualities.P720.value
        }
    }

    private suspend fun fetchKey(apiUrl: String): String {
        val cached = KEY_CACHE[apiUrl]
        if (cached != null) return cached

        var lastException: Exception? = null
        repeat(KEY_FETCH_RETRIES) { attempt ->
            try {
                val key = app.get(apiUrl, timeout = KEY_TIMEOUT).text
                    .let { tryParseJson<Key>(it) }?.key
                if (!key.isNullOrEmpty()) {
                    KEY_CACHE[apiUrl] = key
                    return key
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < KEY_FETCH_RETRIES - 1) {
                    delay(1000L * (attempt + 1))
                }
            }
        }
        throw ErrorLoadingException(
            "Failed to fetch key after $KEY_FETCH_RETRIES attempts: ${lastException?.message ?: "Empty key"}"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<Data>(data) ?: throw ErrorLoadingException("Gagal memuat data")

        val (videoKey, subKey) = coroutineScope {
            val videoKeyDeferred = async { fetchKey("$KEY_FETCH_API${loadData.epsId}&version=$API_VERSION") }
            val subKeyDeferred = async { fetchKey("$SUB_FETCH_API${loadData.epsId}&version=$API_VERSION") }
            videoKeyDeferred.await() to subKeyDeferred.await()
        }

        app.get(
            "$mainUrl/api/DramaList/Episode/${loadData.epsId}.png?err=false&ts=&time=&kkey=$videoKey",
            referer = "$mainUrl/Drama/${getTitle(loadData.title ?: "")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100",
            timeout = API_TIMEOUT
        ).text.let { tryParseJson<Sources>(it) }?.let { source ->
            listOf(source.video, source.thirdParty).amap { link ->
                safeApiCall {
                    if (link?.contains(".m3u8") == true) {
                        M3u8Helper.generateM3u8(
                            this.name,
                            link,
                            referer = "$mainUrl/",
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    } else if (link?.contains("mp4") == true) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                url = fixUrl(link),
                                INFER_TYPE
                            ) {
                                this.referer = mainUrl
                                this.quality = inferQuality(link)
                            }
                        )
                    } else {
                        loadExtractor(
                            link?.substringBefore("=http") ?: return@safeApiCall,
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        processSubtitles(subKey, loadData.epsId, subtitleCallback)

        return true
    }

    private suspend fun processSubtitles(subKey: String, epsId: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        app.get("$mainUrl/api/Sub/$epsId?kkey=$subKey", timeout = API_TIMEOUT).text.let { res ->
            tryParseJson<List<Subtitle>>(res)?.forEach { sub ->
                val src = sub.src ?: return@forEach
                subtitleCallback.invoke(
                    newSubtitleFile(
                        getLanguage(sub),
                        src
                    )
                )
            }
        }
    }
// SubDecryptor Code from Thanks to https://github.com/Kohi-den/extensions-source/blob/515590ecfec6af2b915d23508266536f7f5a3ab8/src/en/kisskh/src/eu/kanade/tachiyomi/animeextension/en/kisskh/SubDecryptor.kt

    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder().build()
                val response = chain.proceed(request)
                if (response.request.url.toString().contains(".txt")) {
                    val contentType = response.body.contentType()
                    val responseBody = response.body.string()
                    val chunks = responseBody.split(CHUNK_REGEX1)
                        .filter(String::isNotBlank)
                        .map(String::trim)
                    val decrypted = chunks.mapIndexed { index, chunk ->
                        val parts = chunk.split("\n")
                        val text = parts.drop(1)
                        val d = text.map { decrypt(it) }.joinToString("\n")
                        arrayOf<Any>(index + 1, parts.first(), d).joinToString("\n")
                    }.joinToString("\n\n")
                    val newBody = decrypted.toResponseBody(contentType)
                    return response.newBuilder().body(newBody).build()
                }
                return response
            }
        }
    }


    data class Data(
        val title: String?,
        val eps: Int?,
        val id: Int?,
        val epsId: Int?,
    )

    data class Sources(
        @JsonProperty("Video") val video: String?,
        @JsonProperty("ThirdParty") val thirdParty: String?,
    )

    data class Subtitle(
        @JsonProperty("src") val src: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("land") val land: String?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("episodesCount") val episodesCount: Int?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("number") val number: Int?,
        @JsonProperty("sub") val sub: Int?,
    )

    data class MediaDetail(
        @JsonProperty("description") val description: String?,
        @JsonProperty("releaseDate") val releaseDate: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
    )

    data class Key(
        val id: String,
        val version: String,
        val key: String,
    )
}
