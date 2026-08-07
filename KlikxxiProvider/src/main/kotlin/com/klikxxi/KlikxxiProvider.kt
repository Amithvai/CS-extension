package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

private val IMAGE_SIZE_REGEX = Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))", RegexOption.IGNORE_CASE)

private fun Element?.getIframeAttr(): String? {
    return this?.let {
        val lsSrc = it.attr("data-litespeed-src")
        if (lsSrc.isNotEmpty()) lsSrc else it.attr("src")
    }
}

private fun Element?.getPosterImageUrl(): String? {
    if (this == null) return null

    if (hasAttr("srcset")) {
        val best = attr("srcset").trim().split(",")
            .map { it.trim().split(" ")[0] }
            .lastOrNull()
        if (!best.isNullOrBlank()) return best.replace(IMAGE_SIZE_REGEX, "")
    }

    val dataSrc = when {
        hasAttr("data-lazy-src") -> attr("data-lazy-src")
        hasAttr("data-src") -> attr("data-src")
        else -> null
    }
    if (!dataSrc.isNullOrBlank()) return dataSrc.replace(IMAGE_SIZE_REGEX, "")

    val src = attr("src")
    if (!src.isNullOrBlank()) return src.replace(IMAGE_SIZE_REGEX, "")

    return null
}

class KlikxxiProvider : MainAPI() {
    companion object {
        private const val SEL_ARTICLE = "article.item, div.gmr-item-modulepost"
        private const val SEL_TITLE = "h1.entry-title, h2.entry-title, div.mvic-desc h3"
        private const val SEL_POSTER = "figure.pull-left > img, .mvic-thumb img, .poster img, figcaption img[src*='klikxxi']"
        private const val SEL_DESC = "div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p"
        private const val SEL_RECOMMEND = "article.item.col-md-20, div.gmr-recent-posts-wrapper article"
        private const val SEL_SEASON_BLOCK = "div.gmr-season-block, .season-block"
        private const val SEL_EPISODE_LINK = "div.gmr-season-episodes a, .episode-list a"
        private const val SEL_PLAYER_ID = "div#muvipro_player_content_id, input#post_id"
        private const val SEL_TAB_CONTENT = "div.tab-content-ajax, .tab-pane"
        private val QUALITY_CLASS_REGEX = Regex("hd|sd|cam|ts|hdts|hdts2|hdrip|webrip|bluray|brrip|fhd|uhd|4k", RegexOption.IGNORE_CASE)
        private val DIGIT_REGEX = Regex("(\\d+)")
        private val EPISODE_NUM_REGEX = Regex("(?:E(?:p(?:isode)?)?|Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    }

    override var mainUrl = "https://klikxxi.me"
    override var name = "KlikXXI"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=&paged=%d" to "Latest Movie",
        "tv/page/%d/" to "TV Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/animation/page/%d/" to "Animation",
        "category/comedy/page/%d/" to "Comedy",
        "category/crime/page/%d/" to "Crime",
        "category/drama/page/%d/" to "Drama",
        "category/family/page/%d/" to "Family",
        "category/fantasy/page/%d/" to "Fantasy",
        "category/history/page/%d/" to "History",
        "category/horror/page/%d/" to "Horror",
        "category/music/page/%d/" to "Music",
        "category/mystery/page/%d/" to "Mystery",
        "category/romance/page/%d/" to "Romance",
        "category/science-fiction/page/%d/" to "Sci-Fi",
        "category/thriller/page/%d/" to "Thriller",
        "category/war/page/%d/" to "War",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.replace("page/%d/", "")
        val url = if (page <= 1) {
            "$mainUrl/$path"
        } else {
            "$mainUrl/${path.trimEnd('/')}/page/$page/"
        }.replace("//", "/")
         .replace(":/", "://")

        val document = runCatching { app.get(url, timeout = 15_000L).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = document.select(SEL_ARTICLE)
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("h2.entry-title a, h3.entry-title a") ?: return null

        val href = fixUrl(linkElement.attr("href"))

        val title = linkElement.text().trim()

        if (title.isBlank()) return null

        val posterUrl = this.selectFirst(".wp-block-post-featured-image img, .wp-block-post-featured-image a img, figure.wp-block-post-featured-image img, img[src*='klikxxi.shop'], img.wp-post-image, img.attachment-medium")
            ?.attr("src")
            ?.let { it?.let { url -> fixUrl(url) } }
            ?.ifBlank {
                attr("data-bg")?.let { fixUrl(it) }
            }

        val quality = extractQuality()
        val typeText = selectFirst(".gmr-posttype-item, .post-type, .movie-type")?.text()?.trim()
        val ratingText = selectFirst("div.gmr-rating-item, .rating, .imdb-rating")?.ownText()?.trim()
        val isSeries = typeText.equals("TV Show", ignoreCase = true) || selectFirst(".tv-series, .series-type") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    private fun Element.extractQuality(): String? {
        val el = selectFirst(".gmr-quality-item, .quality, .quality-tag, [class*='quality']") ?: return null
        return el.text().trim().ifBlank {
            el.selectFirst("a, span")?.text()?.trim()
        }?.ifBlank {
            classNames().firstOrNull { cls -> cls.matches(QUALITY_CLASS_REGEX) }?.uppercase()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = runCatching {
            app.get("$mainUrl/?s=$encodedQuery", timeout = 15_000L).document
        }.getOrNull() ?: return emptyList()
        return document.select(SEL_ARTICLE)
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a, h3.entry-title a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = this.selectFirst(".wp-block-post-featured-image img, .wp-block-post-featured-image a img, figure.wp-block-post-featured-image img, img.wp-post-image")
            ?.attr("src")
            ?.let { it?.let { url -> fixUrl(url) } }
        
        val typeText = selectFirst(".gmr-posttype-item, .post-type, .movie-type")?.text()?.trim()
        val isSeries = typeText.equals("TV Show", ignoreCase = true) || selectFirst(".tv-series, .series-type") != null
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching { app.get(url, timeout = 15_000L).document }.getOrNull()
            ?: return newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load page: network error"
            }

        val title = cleanTitle(document.selectFirst(SEL_TITLE)?.text())

        val poster = document
            .selectFirst(SEL_POSTER)
            ?.attr("src")
            ?.let { it?.let { s -> fixUrl(s) } }

        val description = document.selectFirst(SEL_DESC)?.text()?.trim()

        val tags = document.select("strong:contains(Genre) ~ a, .post-categories a, .movie-genres a").eachText()

        val year = document
            .select("div.gmr-moviedata strong:contains(Year:) > a, .release-date time, [itemprop=datePublished]")
            .text()
            .takeIf { it.matches(Regex("\\d{4}")) }
            ?.toIntOrNull()

        val trailer = document
            .selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, .trailer-link a")
            ?.attr("href")

        val rating = document
            .selectFirst("span[itemprop=ratingValue], .imdb-rating span, .rating-value")
            ?.text()
            ?.toDoubleOrNull()

        val actors = document
            .select("div.gmr-moviedata span[itemprop=actors] a, .cast-list a, .actor-name")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val recommendations = document
            .select(SEL_RECOMMEND)
            .mapNotNull { it.toRecommendResult() }

        val episodes = parseEpisodes(document)

        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val seasonBlocks = document.select(SEL_SEASON_BLOCK)
        val allEpisodes = mutableListOf<Episode>()

        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title, .season-name, h2")?.text()?.trim()
            var seasonNumber = DIGIT_REGEX
                .find(seasonTitle ?: "")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            
            if (seasonNumber == null) {
                seasonNumber = block.attr("data-season")?.toIntOrNull()
                    ?: block.parent()?.attr("data-season")?.toIntOrNull()
                    ?: 1
            }

            val eps = block.select(SEL_EPISODE_LINK)
                .takeIf { it.isNotEmpty() }
                ?: block.select("a[href*='/episode/'], a[href*='#ep-']")
                
            eps.filter { a ->
                val t = a.text().lowercase()
                !t.contains("view all") && !t.contains("batch") && !t.contains("load more")
            }.mapIndexedNotNull { index, epLink ->
                val hrefEp = fixUrl(epLink.attr("href"))

                if (hrefEp.isBlank()) return@mapIndexedNotNull null

                val name = epLink.text().trim().ifBlank {
                    epLink.parent()?.text()?.trim() ?: "Episode ${index + 1}"
                }

                val episodeNum = EPISODE_NUM_REGEX.find(name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: DIGIT_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                newEpisode(hrefEp) {
                    this.name = name
                    this.season = seasonNumber
                    this.episode = episodeNum
                }
            }.forEach { allEpisodes.add(it) }
        }

        return allEpisodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data, timeout = 15_000L).document }.getOrNull()
            ?: throw ErrorLoadingException("Gagal memuat video")
        
        var postId = document
            .selectFirst(SEL_PLAYER_ID)?.attr("data-id")
            ?: document.selectFirst("[data-post-id]")?.text()?.trim()
            ?: document.selectFirst("#post_id, input[name=post_id]")?.attr("value")
        
        if (postId.isNullOrBlank()) return false

        var foundAny = false
        document.select(SEL_TAB_CONTENT).amap { tab ->
            var tabId = tab.attr("id")
            
            if (tabId.isNullOrBlank()) {
                tabId = tab.attr("data-tab") ?: tab.attr("name")
            }
            
            if (tabId.isNullOrBlank()) return@amap

            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to postId
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    timeout = 15_000L
                ).document
            }.getOrNull() ?: return@amap

            val iframe = response.selectFirst("iframe")?.getIframeAttr() 
                ?: response.selectFirst("source[src]")?.attr("src")
                ?: response.text().substringAfter("window.location.href = \"").substringBefore("\"")
                
            if (iframe.isNullOrBlank()) return@amap
            
            val link = httpsify(iframe)

            loadExtractor(link, data, subtitleCallback) {
                foundAny = true
                callback(it)
            }
        }

        return foundAny
    }

    private fun cleanTitle(raw: String?): String {
        return raw
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()
    }
}
