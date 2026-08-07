package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URLEncoder
import org.jsoup.nodes.Element

class Nomat : MainAPI() {

    override var mainUrl = "https://nomat.asia"
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(
                TvType.Movie,
                TvType.TvSeries,
                TvType.Anime,
                TvType.AsianDrama
            )

    companion object {
        private val POSTER_URL_REGEX = Regex("url\\('(.*?)'\\)")
        private val EPISODE_TEXT_REGEX = Regex("Eps?.?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val EPISODE_PATH_REGEX = Regex("/episode-(\\d+)")
        private val EPISODE_LABEL_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val DIGITS_ONLY_REGEX = Regex("^\\s*(\\d+)\\s*$")
        private val SEASON_PATH_REGEX = Regex("/season-(\\d+)/")
    }

    override val mainPage = mainPageOf(
        "slug/film-terbaru/%d/" to "Terbaru",
        "slug/film-box-office/%d/" to "Box Office",
        "slug/film-serial-baru-terpopuler/%d/" to "TV Series",
        "category/genre/action/%d/" to "Action",
        "slug/film-movie-anime/%d/" to "Animation",
        "category/genre/history/%d/" to "History",
        "category/genre/horror/%d/" to "Horror",
        "category/genre/romance/%d/" to "Romance",
        "category/country/japan/%d/" to "Japan",
        "category/country/philippines/%d/" to "Philippines",
        "category/country/thailand/%d/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val document = app.get("$mainUrl/${request.data.format(page)}", timeout = 15_000L).document
            val items = document.select("a:has(.item-content)").mapNotNull { it.parseItem() }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/search/$encodedQuery/", timeout = 15_000L).document
            document.select("a:has(.item-content)").mapNotNull { it.parseItem() }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private fun Element.parseItem(): SearchResponse? {
        val href = this.attr("href")
        if (href.isNullOrBlank()) return null
        val title = this.selectFirst(".title")?.text()?.trim() ?: return null
        val posterStyle = this.selectFirst(".poster")?.attr("style").orEmpty()
        val poster = POSTER_URL_REGEX.find(posterStyle)?.groupValues?.get(1)
        val ratingText = this.selectFirst(".rtg")?.ownText()?.trim()
        val quality = this.selectFirst(".quality")?.text()?.trim()
        val epsText = this.selectFirst(".episode")?.text()?.trim()
        val episode = EPISODE_TEXT_REGEX
            .find(epsText ?: "")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        return if (episode != null || title.contains("Season", true) || title.contains("Episode", true)) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                addQuality(quality ?: "")
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                if (episode != null) addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                addQuality(quality ?: "")
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 15_000L).document
            val title = document.selectFirst("div.video-title h1")?.text()
                ?.substringBefore("Season")
                ?.substringBefore("Episode")
                ?.trim()
                ?: ""

            val poster = fixUrlNull(
                document.selectFirst("div.video-poster")?.attr("style")
                    ?.substringAfter("url('")
                    ?.substringBefore("')")
            )

            val tags = document.select("div.video-genre a").map { it.text() }
            val year = document.select("div.video-duration a[href*=/category/year/]").text().toIntOrNull()
            val description = document.selectFirst("div.video-synopsis")?.text()?.trim()
            val trailer = document.selectFirst("div.video-trailer iframe")?.attr("src")
            val rating = document.selectFirst("div.rtg")?.text()?.trim()
            val actors = document.select("div.video-actor a").map { it.text() }
            val recommendations = document.select("a:has(.item-content)").take(15).mapNotNull { it.parseItem() }

            val isSeries = url.contains("/serial-tv/") || document.select("div.video-episodes a").isNotEmpty()

            if (isSeries) {
                val episodes = document.select("div.video-episodes a").map { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val number = EPISODE_PATH_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: EPISODE_LABEL_REGEX.find(eps.text())?.groupValues?.get(1)?.toIntOrNull()
                        ?: DIGITS_ONLY_REGEX.find(eps.text())?.groupValues?.get(1)?.toIntOrNull()
                    val season = SEASON_PATH_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    val name = number?.let { "Episode $it" } ?: eps.text().trim()

                    newEpisode(href) {
                        this.name = name
                        this.episode = number
                        this.season = season
                        this.posterUrl = poster
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                    addScore(rating ?: "")
                }
            } else {
                val playUrl = document.selectFirst("a:has(.play-btn), a[href*='nontonhemat.link'], [data-play], a[href*='player'], a[href*='watch'], a[href*='stream']")?.attr("href")

                newMovieLoadResponse(title, url, TvType.Movie, playUrl ?: url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                    addScore(rating ?: "")
                }
            }
        } catch (e: Exception) {
            logError(e)
            newMovieLoadResponse("", url, TvType.Movie, url) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageUrl = data.ifBlank { mainUrl }

            // First attempt: fetch the embed page directly (nontonhemat.link)
            val embedDoc = app.get(pageUrl, referer = mainUrl, timeout = 30_000L).document
            var hasServers = parseEmbedPage(embedDoc, pageUrl, subtitleCallback, callback)

            // Fallback: if no servers found, look for a play button and follow it
            if (!hasServers) {
                val playHref = embedDoc.selectFirst("a:has(.play-btn), a[href*='nontonhemat.link']")?.attr("href")
                if (!playHref.isNullOrBlank()) {
                    val playDoc = app.get(playHref, referer = pageUrl, timeout = 30_000L).document
                    parseEmbedPage(playDoc, playHref, subtitleCallback, callback)
                }
            }

            true
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    private suspend fun parseEmbedPage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        doc.select("track[kind=subtitles], .subtitle-option, [data-subtitle]").forEach { subEl ->
            val subUrl = subEl.attr("src").takeIf { it.isNotBlank() }
                ?: subEl.attr("data-src").takeIf { it.isNotBlank() }
                ?: subEl.attr("data-subtitle").takeIf { it.isNotBlank() }
            val lang = subEl.attr("srclang").takeIf { it.isNotBlank() } ?: subEl.attr("data-lang").takeIf { it.isNotBlank() } ?: "id"
            val label = subEl.attr("label").takeIf { it.isNotBlank() } ?: subEl.attr("data-label").takeIf { it.isNotBlank() } ?: lang
            subUrl?.let { subtitleCallback(newSubtitleFile(label, it)) }
        }

        val serverItems = doc.select("div.server-item")
        serverItems.amap { el ->
            val encoded = el.attr("data-url")
            if (encoded.isNotBlank()) {
                try {
                    val decoded = base64Decode(encoded)
                    loadExtractor(decoded, pageUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        return serverItems.isNotEmpty()
    }
}
