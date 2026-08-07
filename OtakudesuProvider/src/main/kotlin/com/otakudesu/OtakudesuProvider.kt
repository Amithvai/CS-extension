package com.otakudesu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "OtakuDesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf(
            "Mega",
            "MegaUp",
            "Otakufiles",
        )
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val YEAR_REGEX = Regex("\\d, (\\d*)")
        private val EPISODE_SLUG_REGEX = Regex("Episode\\s?(\\d+)")
        private val HTML_TAG_REGEX = Regex("<.*?>")
        private val NONCE_ACTION_REGEX = Regex("""(?:data:\{|action:\s*")[^"]*"([a-f0-9]+)"""")
        private val DATA_ACTION_REGEX = Regex("""data:\{action:"([^"]+)"""")
        private val EMBED_ACTION_REGEX = Regex("""nonce[^,]*,\s*action:\s*"([a-f0-9]+)"""")
        private val NONCE_ACTION2_REGEX = Regex("""nonce:[^,]+,action:"([^"]+)"""")
        private val FILE_ID_REGEX = Regex("""(?:/f/|/file/)(\w+)""")
        private val QUALITY_PIXEL_REGEX = Regex("(\\d{3,4})[pP]")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Complete Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page, timeout = 15_000L).document
        val home = document.select("div.venz > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val hasNext = document.selectFirst("a.next.page-numbers, .pagination .next") != null
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.select("div.thumbz > img").attr("src")
        val epNum = this.selectFirst("div.epz")?.ownText()?.replace(NON_DIGIT_REGEX, "")?.trim()
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            app.get("$mainUrl/?s=$encodedQuery&post_type=anime", timeout = 15_000L).document.select("ul.chivsrc > li")
                .mapNotNull {
                    val title = it.selectFirst("h2 > a")?.ownText()?.trim() ?: return@mapNotNull null
                    val href = it.selectFirst("h2 > a")?.attr("href") ?: return@mapNotNull null
                    val posterUrl = it.selectFirst("img")?.attr("src") ?: ""
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 15_000L).document
        val infoRows = document.select("div.infozingle > p")

        val title = infoRows.firstOrNull { it.text().contains("Judul", true) }
            ?.selectFirst("span")?.ownText()?.replace(":", "")?.trim()
            ?: "Unknown Title"
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = infoRows.firstOrNull { it.text().contains("Genre", true) }
            ?.select("span > a")?.map { it.text() } ?: emptyList()
        val type = getType(
            infoRows.firstOrNull { it.text().contains("Tipe", true) }
                ?.selectFirst("span")?.ownText()?.replace(":", "")?.trim() ?: "tv"
        )

        val year = YEAR_REGEX.find(
            infoRows.firstOrNull { it.text().contains("Rilis", true) }
                ?.selectFirst("span")?.text().orEmpty()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            infoRows.firstOrNull { it.text().contains("Status", true) }
                ?.selectFirst("span")?.ownText()?.replace(":", "")?.trim() ?: "Completed"
        )
        val description = document.select("div.sinopc > p").text()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var kitsuid: String? = null

        if (malId != null) {
            val syncMetaData = runCatching {
                app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 15_000L).text
            }.getOrNull()
            if (syncMetaData != null) {
                animeMetaData = tryParseJson<MetaAnimeData>(syncMetaData)
                kitsuid = animeMetaData?.mappings?.kitsuId
            }
        }

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodeLists = document.select("div.episodelist")
        val episodesContainer = episodeLists.getOrNull(1) ?: episodeLists.firstOrNull()
        val episodes = episodesContainer?.select("ul > li")?.amap { element ->
            val name = element.selectFirst("a")?.text() ?: return@amap null
            val epRegex = EPISODE_SLUG_REGEX
            val epMatch = epRegex.find(name)
            var episodeNum = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val fallbackName = epMatch?.groupValues?.getOrNull(0) ?: name
            val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@amap null)

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
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: fallbackName
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }?.filterNotNull()?.reversed() ?: emptyList()

        val recommendations =
            document.select("div.isi-recommend-anime-series > div.isi-konten").mapNotNull {
                val recName = it.selectFirst("span.judul-anime > a")?.text() ?: return@mapNotNull null
                val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val recPosterUrl = it.selectFirst("a > img")?.attr("src") ?: ""
                newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                    this.posterUrl = recPosterUrl
                }
            }

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
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.plot = finalPlot
            this.tags = tags
            this.recommendations = recommendations
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            if (kitsuid != null) addKitsuId(kitsuid)
        }
    }


    data class ResponseSources(
        @JsonProperty("id") val id: String,
        @JsonProperty("i") val i: String,
        @JsonProperty("q") val q: String,
    )

    data class ResponseData(
        @JsonProperty("data") val data: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = runCatching { app.get(data, timeout = 15_000L).document }.getOrNull() ?: throw ErrorLoadingException("Gagal memuat video")

        runAllAsync(
            {
                val scriptData = document.select("script:containsData(__x__nonce)").firstOrNull()?.data()
                    ?: document.select("script:containsData(mirrorstream)").firstOrNull()?.data()
                    ?: document.select("script:containsData(admin-ajax)").lastOrNull()?.data()
                    ?: ""

                val nonceAction = NONCE_ACTION_REGEX.find(scriptData)
                    ?.groupValues?.getOrNull(1)
                    ?: DATA_ACTION_REGEX.find(scriptData)?.groupValues?.getOrNull(1)
                val embedAction = EMBED_ACTION_REGEX.find(scriptData)
                    ?.groupValues?.getOrNull(1)
                    ?: NONCE_ACTION2_REGEX.find(scriptData)?.groupValues?.getOrNull(1)

                if (nonceAction != null && embedAction != null) {
                    val nonce = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to nonceAction), timeout = 15_000L)
                        .text.let { tryParseJson<ResponseData>(it) }?.data ?: return@runAllAsync

                    document.select("div.mirrorstream > ul > li").amap { li ->
                        val dataContent = li.select("a").attr("data-content")
                        if (dataContent.isNotBlank()) {
                            val decodedData = runCatching { base64Decode(dataContent) }.getOrNull() ?: return@amap null
                            val res = tryParseJson<ResponseSources>(decodedData) ?: return@amap null

                            val embedData = app.post(
                                "${mainUrl}/wp-admin/admin-ajax.php", data = mapOf(
                                    "id" to res.id,
                                    "i" to res.i,
                                    "q" to res.q,
                                    "nonce" to nonce,
                                    "action" to embedAction
                                ),
                                timeout = 15_000L
                            ).text.let { tryParseJson<ResponseData>(it) }?.data ?: return@amap null

                            val decodedEmbed = runCatching { base64Decode(embedData) }.getOrNull() ?: return@amap null
                            val sources = Jsoup.parse(decodedEmbed).select("iframe").attr("src")

                            loadCustomExtractor(sources, data, subtitleCallback, callback, getQuality(res.q))
                        }
                    }
                }
            },
            {
                document.select("div.download li").map { ele ->
                    val quality = getQuality(ele.select("strong").text())
                    ele.select("a").map {
                        it.attr("href") to it.text()
                    }.filter {
                        !inBlacklist(it.first) && quality != Qualities.P360.value
                    }.amap {
                        val link = runCatching { app.get(it.first, referer = "$mainUrl/", timeout = 15_000L).url }.getOrNull() ?: return@amap null
                        loadCustomExtractor(
                            fixedIframe(link),
                            data,
                            subtitleCallback,
                            callback,
                            quality
                        )
                    }
                }
            }
        )

        return true
    }

    private suspend fun loadCustomExtractor(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun fixedIframe(url: String): String {
        return when {
            url.startsWith(acefile) -> {
                val id = FILE_ID_REGEX.find(url)?.groupValues?.getOrNull(1)
                "${acefile}/player/$id"
            }

            else -> fixUrl(url)
        }
    }

    private fun inBlacklist(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun getQuality(str: String?): Int {
        return QUALITY_PIXEL_REGEX.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
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
}

