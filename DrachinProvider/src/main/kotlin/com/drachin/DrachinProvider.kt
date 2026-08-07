package com.drachin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URLEncoder

class Drachin : MainAPI() {

    override var mainUrl = "https://dramachina.my.id"
    override var name = "Drachin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    companion object {
        private val HEADERS = mapOf("User-Agent" to "Mozilla/5.0")
        private val VIDEO_DATA_REGEX = Regex("const videoData\\s*=\\s*\\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL)
        private val SERVERS_REGEX = Regex("servers:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
        private val TITLE_REGEX = Regex("title:\\s*\"([^\"]*)\"")
        private val YT_REGEX = Regex("youtube\\.com/embed/([^\"?]+)")
        private val OG_IMG_REGEX = Regex("og:image\"\\s+content=\"([^\"]+)\"")
        private val EP_LINK_REGEX = Regex("href=\"watch\\.php\\?id=(\\d+)&ep=(\\d+)\"")
        private val EP_LABEL_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val ID_REGEX = Regex("watch\\.php\\?id=(\\d+)")
    }

    override val mainPage = mainPageOf(
        "" to "Beranda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data.format(page)}"
            val document = app.get(url, headers = HEADERS, timeout = 15_000L).document
            val items = document.select("a[href*='watch.php?id=']")
                .mapNotNull { parseSearch(it) }
                .distinctBy { it.url }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseSearch(a: org.jsoup.nodes.Element): SearchResponse? {
        val href = a.attr("href")
        if (!href.contains("watch.php?id=") || href.contains("&ep=")) return null
        val abs = fixUrl(href)
        val title = a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: a.selectFirst(".video-title, .title, h3, h2, .name")?.text()?.trim()
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(a.selectFirst("img")?.attr("src") ?: a.selectFirst("img")?.attr("data-src"))
        return newAnimeSearchResponse(title, abs, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/search.php?q=$encoded", headers = HEADERS, timeout = 15_000L).document
            document.select("a[href*='watch.php?id=']")
                .mapNotNull { parseSearch(it) }
                .distinctBy { it.url }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val html = app.get(url, headers = HEADERS, timeout = 15_000L).text
            val id = ID_REGEX.find(url)?.groupValues?.get(1) ?: "0"

            val videoData = VIDEO_DATA_REGEX.find(html)?.groupValues?.get(1) ?: ""
            val title = TITLE_REGEX.find(videoData)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() } ?: "Drachin"

            val poster = fixUrlNull(OG_IMG_REGEX.find(html)?.groupValues?.get(1))

            val epLinks = EP_LINK_REGEX.findAll(html)
                .map { "${mainUrl}/watch.php?id=${it.groupValues[1]}&ep=${it.groupValues[2]}" }
                .toSet()

            val episodes = if (epLinks.isNotEmpty()) {
                epLinks.map { epUrl ->
                    val num = EP_LINK_REGEX.find(epUrl)?.groupValues?.get(2)?.toIntOrNull()
                    newEpisode(fixUrl(epUrl)) {
                        this.episode = num
                        this.name = num?.let { n -> "Episode $n" } ?: "Episode"
                        this.posterUrl = poster
                    }
                }
            } else {
                val maxEp = EP_LABEL_REGEX.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                if (maxEp != null && maxEp > 1) {
                    (1..maxEp).map { n ->
                        newEpisode("$mainUrl/watch.php?id=$id&ep=$n") {
                            this.episode = n
                            this.name = "Episode $n"
                            this.posterUrl = poster
                        }
                    }
                } else {
                    emptyList()
                }
            }

            if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                    this.posterUrl = poster
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            logError(e)
            newMovieLoadResponse("Drachin", url, TvType.Movie, url) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(data, headers = HEADERS, timeout = 15_000L).text
            val videoMatch = VIDEO_DATA_REGEX.find(html)
            val serversJson = if (videoMatch != null) {
                SERVERS_REGEX.find(videoMatch.groupValues[1])?.groupValues?.get(1) ?: "[]"
            } else {
                "[]"
            }
            val servers = tryParseJson<List<ServerData>>(serversJson) ?: emptyList()

            var found = false
            for (server in servers) {
                val code = server.embed_code ?: continue
                val ytId = YT_REGEX.find(code)?.groupValues?.get(1) ?: continue
                found = true
                loadExtractor("https://www.youtube.com/watch?v=$ytId", data, subtitleCallback, callback)
            }

            if (!found) throw ErrorLoadingException("Tidak ada server video di Drachin")
            true
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video Drachin")
        }
    }

    data class ServerData(
        val name: String? = null,
        val embed_code: String? = null,
        val type: String? = null,
    )
}
