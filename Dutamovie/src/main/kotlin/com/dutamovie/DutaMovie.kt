package com.dutamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DutaMovie : MainAPI() {
    companion object {
        private val EPISODE_SEARCH_REGEX = Regex("Episode\\s?([0-9]+)")
        private val SEASON_EP_CLEAN_REGEX = Regex("\\s*(Season|Episode)\\s*.*", RegexOption.IGNORE_CASE)
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val PERMALINK_REGEX = Regex("(?i)Permalink to\\s*")
        private val EPISODE_NUM_REGEX = Regex("Episode\\s*(\\d+)")
        private val IMAGE_SIZE_REGEX = Regex("(-\\d*x\\d*)")
    }

    override var mainUrl = "https://seoulschool.org"
    override var name = "Dutamovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "box-office/page/%d/" to "Box Office",
        "serial-tv/page/%d/" to "TV Series",
        "action/page/%d/" to "Action",
        "adventure/page/%d/" to "Adventure",
        "animation/page/%d/" to "Animation",
        "comedy/page/%d/" to "Comedy",
        "crime/page/%d/" to "Crime",
        "drama/page/%d/" to "Drama",
        "fantasy/page/%d/" to "Fantasy",
        "horror/page/%d/" to "Horror",
        "mystery/page/%d/" to "Mystery",
        "romance/page/%d/" to "Romance",
        "science-fiction/page/%d/" to "Sci-Fi",
        "thriller/page/%d/" to "Thriller",
        "country/china/page/%d/" to "China",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/philippines/page/%d/" to "Philippines",
        "country/thailand/page/%d/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}", timeout = 15_000L).document
        val items = document.select("article.item").mapNotNull { it.toSearchItem() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("h2.entry-title > a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a")
            .text().trim().replace("-", "")
        val ratingText = selectFirst("div.gmr-rating-item")?.ownText()?.trim()

        return if (quality.isEmpty()) {
            val episode = EPISODE_SEARCH_REGEX.find(title)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: select("div.gmr-numbeps > span").text().toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl?s=$encodedQuery&post_type[]=post&post_type[]=tv", timeout = 15_000L).document
        return document.select("article.item-infinite").mapNotNull { it.toSearchItem() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url, timeout = 15_000L)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(SEASON_EP_CLEAN_REGEX, "")?.trim()
            .orEmpty()

        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a")
            .text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue]")
            ?.text()?.trim()
        val actors = document.select("span[itemprop=actors] a").map { it.text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
            ?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20").mapNotNull { it.toSearchItem() }
        return if (tvType == TvType.TvSeries) {
            val episodes = parseEpisodes(document, poster)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    private fun parseEpisodes(document: Document, poster: String?): List<Episode> {
        return document.select("div.vid-episodes a, div.gmr-listseries a")
            .mapNotNull { eps ->
                val href = fixUrl(eps.attr("href"))
                val rawTitle = eps.attr("title").takeIf { it.isNotBlank() } ?: eps.text()
                val cleanTitle = rawTitle.replaceFirst(PERMALINK_REGEX, "").trim()

                val epNum = EPISODE_NUM_REGEX.find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: cleanTitle.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()

                val formattedName = epNum?.let { "Episode $it" } ?: cleanTitle

                newEpisode(href) {
                    this.name = formattedName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.filter { it.episode != null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val referer = "$baseUrl/"

        val response = try {
            app.get(data, timeout = 15_000L)
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
        val document = response.document

        document.select("div.gmr-embed-responsive iframe").forEach { iframe ->
            iframe.getIframeAttr()?.let { src ->
                loadExtractor(httpsify(src), referer, subtitleCallback, callback)
            }
        }

        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!id.isNullOrEmpty()) {
            document.select("div.tab-content-ajax").forEach { ele ->
                try {
                    val server = app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to ele.attr("id"),
                            "post_id" to id
                        ),
                        timeout = 15_000L
                    ).document.select("iframe").attr("src").let { httpsify(it) }
                    loadExtractor(server, referer, subtitleCallback, callback)
                } catch (_: Exception) {
                }
            }
        } else {
            val tabs = document.select("ul.muvipro-player-tabs li a")
                .map { fixUrl(it.attr("href")) }
                .filter { it != data }

            for (tabUrl in tabs) {
                try {
                    val tabDoc = app.get(tabUrl, timeout = 15_000L).document
                    tabDoc.select("div.gmr-embed-responsive iframe").forEach { iframe ->
                        iframe.getIframeAttr()?.let { src ->
                            loadExtractor(httpsify(src), referer, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val findResult = IMAGE_SIZE_REGEX.find(this)?.value ?: return this
        return replace(findResult, "")
    }

    private fun getBaseUrl(url: String): String =
        URI(url).let { "${it.scheme}://${it.host}" }
}
