package com.kuronime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URI

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    private val animekuUrl = "https://animeku.org"
    override var name = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val AES_KEY = "3&!Z0M,VIZ;dZW=="
        private const val TMDB_API_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val ANIZIP_API = "https://api.ani.zip/mappings"
        private const val SOURCES_API_PATH = "/api/v9/sources"
        private val VIDEO_ID_REGEX = Regex("""\bid\b\s*[:=]\s*["']([a-zA-Z0-9_-]+)["']""")
        private val BASE64_ID_REGEX = Regex("""_0x[a-f0-9]+\s*=\s*"([A-Za-z0-9+/=]+)"""")
        private val EPISODE_NUM_REGEX = Regex("(\\d+)")
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val NON_WORD_REGEX = Regex("\\W")
        private val HTML_TAG_REGEX = Regex("<.*?>")
        private val YEAR_REGEX = Regex("\\d, (\\d*)")
        private val SLUG_EPISODE_REGEX = Regex("nonton-(.+)-episode")
        private val SLUG_MOVIE_REGEX = Regex("nonton-(.+)-movie")

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/%d/?status=ongoing&order=update" to "Ongoing Anime",
        "$mainUrl/anime/page/%d/?status=completed&order=update" to "Complete Anime",
        "$mainUrl/anime/page/%d/?order=latest" to "New Anime Series",
        "$mainUrl/anime/page/%d/?order=popular" to "Most Popular",
        "$mainUrl/anime/page/%d/?type=Movie&order=update" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("%d", page.toString())
        val document = app.get(url, timeout = 15_000L).document
        val home = document.select(".listupd article").map {
            it.toSearchResult(mainUrl)
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun getProperAnimeLink(uri: String, baseUrl: String): String {
        if (uri.contains("/anime/")) return uri

        val slug = uri.trimEnd('/').substringAfterLast("/")
        val title = when {
            slug.contains("-episode") && !slug.contains("-movie") ->
                SLUG_EPISODE_REGEX.find(slug)?.groupValues?.get(1) ?: slug
            slug.contains("-movie") ->
                SLUG_MOVIE_REGEX.find(slug)?.groupValues?.get(1) ?: slug
            else -> slug
        }

        return "$baseUrl/anime/$title"
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResult(baseUrl: String): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")) ?: "", baseUrl)
        val title = this.selectFirst("h2, .bsuxtt, .tt > h4, .entry-title")?.text()?.trim() ?: "Unknown"

        val img = this.selectFirst("img[itemprop=image]") ?: this.select("img").lastOrNull()
        val posterUrl = fixUrlNull(img?.getImageAttr())

        val epNum = this.select(".ep").text().replace(NON_DIGIT_REGEX, "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span, .bt > .type")?.text() ?: "")

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), timeout = 15_000L
        ).text.let { tryParseJson<Search>(it) }?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle ?: "",
                it.postLink ?: return@mapNotNull null,
                TvType.Anime
            ) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 15_000L).document
        val currentBaseUrl = getBaseUrl(url)

        val title = document.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.l[itemprop=image] > img, .l > img")?.getImageAttr()
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val typeString = document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")?.trim() ?: "tv"
        val type = getType(typeString.lowercase())

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = YEAR_REGEX.find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()

        val statusElement = document.selectFirst(".infodetail > ul > li:nth-child(3)")
        val statusText = statusElement?.ownText()?.replace(NON_WORD_REGEX, "") ?: ""
        val status = getStatus(statusText)

        val description = document.select("span.const > p").text()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            runCatching {
                val syncMetaData = app.get("$ANIZIP_API?mal_id=$malId", timeout = 15_000L).text
                animeMetaData = tryParseJson<MetaAnimeData>(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            }.onFailure {
                // ani.zip API unavailable, continue without metadata
            }
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = TMDB_API_KEY,
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.bixbox.bxcl > ul > li").amap { element ->
            val link = element.selectFirst("a")?.attr("href") ?: return@amap null
            val name = element.selectFirst("a")?.text() ?: return@amap null
            var episodeNum = EPISODE_NUM_REGEX.find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
            }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.filterNotNull().reversed()

        val apiDescription = animeMetaData?.description?.replace(HTML_TAG_REGEX, "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview

        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data, timeout = 15_000L)
        val document = req.document
        val currentBaseUrl = getBaseUrl(req.url)

        val id = document.select("script")
            .map { it.data() }
            .firstNotNullOfOrNull { script ->
                VIDEO_ID_REGEX.find(script)?.groupValues?.get(1)
                    ?: BASE64_ID_REGEX.find(script)?.groupValues?.get(1)
            } ?: throw ErrorLoadingException("Video ID not found in page scripts")

        val servers = try {
            app.post(
                "$animekuUrl$SOURCES_API_PATH",
                requestBody = """{"id":"$id"}""".toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                ),
                referer = "$currentBaseUrl/",
                timeout = 15_000L
            ).text.let { tryParseJson<Servers>(it) }
        } catch (e: Exception) {
            null
        }

        if (servers == null) return false

        runAllAsync(
            {
                try {
                    val decrypt = AesHelper.cryptoAESHandler(
                        base64Decode(servers.src ?: return@runAllAsync),
                        AES_KEY.toByteArray(),
                        false,
                        "AES/CBC/PKCS5Padding"
                    )
                    val source =
                        tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\/", "/")
                    M3u8Helper.generateM3u8(
                        this.name,
                        source ?: return@runAllAsync,
                        "$animekuUrl/",
                        headers = mapOf("Origin" to animekuUrl)
                    ).forEach(callback)
                } catch (e: Exception) {
                    // Primary source unavailable, try mirror
                }
            },
            {
                try {
                    val decrypt = AesHelper.cryptoAESHandler(
                        base64Decode(servers.mirror ?: return@runAllAsync),
                        AES_KEY.toByteArray(),
                        false,
                        "AES/CBC/PKCS5Padding"
                    )
                    tryParseJson<Mirrors>(decrypt?.toJsonFormat())?.embed?.forEach { (version, entries) ->
                        entries.forEach { (_, entryUrl) ->
                            loadFixedExtractor(
                                entryUrl,
                                version.removePrefix("v"),
                                "$currentBaseUrl/",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Mirror source unavailable
                }
            }
        )

        return true
    }

    private fun String?.toJsonFormat(): String? {
        val s = this?.trim() ?: return null
        return if (s.startsWith("\"") && s.endsWith("\""))
            s.substring(1, s.length - 1).replace("\\\"", "\"")
        else s
    }

    private suspend fun loadFixedExtractor(
        url: String? = null,
        quality: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url ?: return, referer, subtitleCallback) { link ->
            kotlinx.coroutines.runBlocking {
                callback(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type,
                    ) {
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                        this.quality = getQualityFromName(quality)
                    }
                )
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )

    data class Sources(
        @JsonProperty("src") val src: String? = null,
    )

    data class Servers(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("mirror") val mirror: String? = null,
    )

    data class All(
        @JsonProperty("post_image") val postImage: String? = null,
        @JsonProperty("post_image_html") val postImageHtml: String? = null,
        @JsonProperty("ID") val ID: Int? = null,
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("post_genres") val postGenres: String? = null,
        @JsonProperty("post_type") val postType: String? = null,
        @JsonProperty("post_latest") val postLatest: String? = null,
        @JsonProperty("post_sub") val postSub: String? = null,
        @JsonProperty("post_link") val postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") val all: List<All> = emptyList(),
    )

    data class Search(
        @JsonProperty("anime") val anime: List<Anime> = emptyList()
    )
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

    val url = if (type == TvType.AnimeMovie)
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
