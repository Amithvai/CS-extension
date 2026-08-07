package com.pencurimovie

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException


class PencurimovieProvider : MainAPI() {
    override var mainUrl = "https://ww99.pencurimovie.bond"
    override var name = "PencuriMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val DURATION_REGEX = Regex("(\\d+)")
        private val SEASON_REGEX = Regex("Season\\s*(\\d+)")
        private val EPISODE_REGEX = Regex("Episode\\s*(\\d+)")
    }

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "most-viewed" to "Most Viewed Movies",
        "top-imdb" to "Top IMDB Movies",
        "country/malaysia" to "Malaysia Movies",
        "country/indonesia" to "Indonesia Movies",
        "country/india" to "India Movies",
        "country/japan" to "Japan Movies",
        "country/thailand" to "Thailand Movies",
        "country/china" to "China Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page", timeout = 30_000L).document
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("a.next, a.page-numbers.next:not(.dots)") != null
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("oldtitle").substringBefore("(")
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = this.select("a img").attr("src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
        val quality = getQualityFromString(this.select("span.mli-quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}?s=$encodedQuery", timeout = 30_000L).document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    private fun infoParagraphs(doc: Element, key: String): List<Element> =
        doc.select("div.mvic-info p").filter { it.text().startsWith(key) }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30_000L).document
        val title = document.selectFirst("div.mvic-desc h3")?.text()?.trim()
            ?.substringBefore("(")?.trim() ?: ""
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.desc p.f-desc")?.text()?.trim()
        val tvType = if (document.select("div.tvseason").isNotEmpty()) TvType.TvSeries else TvType.Movie
        val trailer = document.select("meta[itemprop=embedUrl]").attr("content")
        val genre = infoParagraphs(document, "Genre").flatMap { it.select("a") }.map { it.text() }
        val rating = document.selectFirst("span.imdb-r[itemprop=ratingValue]")
            ?.text()?.toDoubleOrNull()
        val duration = document.selectFirst("span[itemprop=duration]")
            ?.text()?.let { DURATION_REGEX.find(it)?.value }?.toIntOrNull()
        val actors = infoParagraphs(document, "Actors").flatMap { it.select("a") }.map { it.text() }
        val year = infoParagraphs(document, "Release").flatMap { it.select("a") }.firstNotNullOfOrNull { it.text().toIntOrNull() }
        val recommendations = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tvseason").forEach { info ->
                val season = info.select("strong").text().let { text ->
                    SEASON_REGEX.find(text)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                }
                info.select("div.les-content a").forEach { elem ->
                    val epText = elem.text()
                    val href = elem.attr("href")
                    val episode = EPISODE_REGEX.find(epText)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                    val name = epText.substringAfter("-").trim()
                    episodes.add(
                        newEpisode(href) {
                            this.episode = episode
                            this.name = name
                            this.season = season
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addTrailer(trailer)
                addActors(actors)
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addTrailer(trailer)
                addActors(actors)
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, timeout = 30_000L).document

            document.select("track[kind=subtitles]").forEach { track ->
                val src = track.attr("src")
                val label = track.attr("label").ifBlank { "Subtitle" }
                if (src.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(src, label))
                }
            }

            val iframes = document.select("div.movieplay iframe").mapNotNull { iframe ->
                val href = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                href.takeIf { it.isNotBlank() }
            }

            iframes.amap { href ->
                try {
                    loadExtractor(href, subtitleCallback, callback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(e)
                }
            }

            iframes.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }
}

