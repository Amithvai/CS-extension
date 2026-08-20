package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.net"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    companion object {
        private val EPISODE_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("\\((\\d{4})\\)")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)""""", RegexOption.IGNORE_CASE)
        private val BROKEN_IFRAME = Regex("""(?:mega\.nz/embed/|vidhidepro\.com/v/?$|about:blank)""", RegexOption.IGNORE_CASE)
        private val BLOCKED_HOST = Regex("""https?://winbu\.org""")

        fun String.toMain(): String = BLOCKED_HOST.replace(this, mainUrlOf)
        private val mainUrlOf = "https://winbu.net"
    }

    override val mainPage = mainPageOf(
        "animedonghua" to "Anime Donghua",
        "film" to "Film",
        "others" to "Lainnya",
        "tvshow" to "TV Show",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        val document = app.get(url, timeout = 15_000L).document
        val items = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("ul.pagination a:has(i.fa-caret-right)") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.ml-mask") ?: return null
        val href = fixUrl(link.attr("href")).toMain()
        val title = link.attr("title").ifBlank { selectFirst(".mli-info .judul")?.text().orEmpty() }.trim()
        if (title.isBlank() || href.isBlank()) return null

        val poster = selectFirst("img.mli-thumb")?.attr("src")?.let { fixUrl(it).toMain() }
        val rating = selectFirst("i.info-hidden")?.attr("data-rating")?.toIntOrNull()

        val type = when {
            href.contains("/anime/") -> TvType.Anime
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", timeout = 15_000L).document
        return document.select("div.a-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val safeUrl = url.toMain()
        val document = app.get(safeUrl, timeout = 15_000L).document

        val title = document.selectFirst(".m-info .mli-info .judul")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Winbu")?.substringBefore(" Sub Indo")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return newMovieLoadResponse(name, safeUrl, TvType.Movie, safeUrl)

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toMain()
        val description = document.selectFirst(".mli-desc p")?.text()?.trim()
        val tags = document.select(".mli-mvi a[rel=tag]").eachText()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull()
        val year = YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("meta[property=article:modified_time]")?.attr("content")?.take(4)?.toIntOrNull()

        val episodes = document.select("div.tvseason .les-content a").mapNotNull { elem ->
            val href = fixUrl(elem.attr("href")).toMain()
            val name = elem.text().trim()
            if (href.isBlank()) return@mapNotNull null
            val episode = EPISODE_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = name
                this.episode = episode
            }
        }

        val recommendations = document.select("div#movies .ml-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(30)

        return if (episodes.isNotEmpty()) {
            if (safeUrl.contains("/anime/")) {
                newAnimeLoadResponse(title, safeUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    if (year != null) this.year = year
                    this.recommendations = recommendations
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            } else {
                newTvSeriesLoadResponse(title, safeUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    if (year != null) this.year = year
                    this.recommendations = recommendations
                }
            }
        } else {
            newMovieLoadResponse(title, safeUrl, TvType.Movie, safeUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                if (year != null) this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeData = data.toMain()
        val document = app.get(safeData, timeout = 15_000L).document
        val players = document.select("div.east_player_option").mapNotNull { elem ->
            val post = elem.attr("data-post")
            val nume = elem.attr("data-nume")
            val type = elem.attr("data-type")
            val quality = elem.closest("div.dropdown")?.selectFirst("button.dropdown-toggle")?.text()
                ?.let { RESOLUTION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (post.isBlank() || nume.isBlank()) null else PlayerOption(post, nume, type, quality)
        }.distinctBy { it.post to it.nume }

        if (players.isEmpty()) return false

        var foundAny = false
        players.amap { player ->
            try {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to player.post,
                        "nume" to player.nume,
                        "type" to player.type
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to safeData,
                        "Accept" to "text/html, */*; q=0.01"
                    ),
                    timeout = 15_000L
                ).text

                val src = IFRAME_SRC_REGEX.find(response)?.groupValues?.get(1) ?: return@amap
                val url = httpsify(src).toMain()
                if (BROKEN_IFRAME.containsMatchIn(url)) return@amap

                loadExtractor(url, safeData, subtitleCallback) { link ->
                    player.quality?.let { link.quality = it }
                    foundAny = true
                    callback(link)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // abaikan server yang gagal, lanjut ke server berikutnya
            }
        }

        return foundAny
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val quality: Int?,
    )
}