package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

private val IMAGE_SIZE_REGEX = Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))", RegexOption.IGNORE_CASE)

/** Placeholder lazy-load WordPress: data URI SVG 1x1 */
private fun String?.isPlaceholderImage(): Boolean =
    this.isNullOrBlank() || startsWith("data:image")

/** Normalisasi URL gambar: buang placeholder, ambil versi tanpa suffix ukuran */
private fun String?.asPosterUrl(): String? {
    if (this.isPlaceholderImage()) return null
    return this?.replace(IMAGE_SIZE_REGEX, "")
}

private fun Element?.getIframeAttr(): String? {
    return this?.let {
        val lsSrc = it.attr("data-litespeed-src")
        if (lsSrc.isNotEmpty()) lsSrc else it.attr("src")
    }
}

/**
 * Ambil URL poster asli dengan prioritas:
 *  1. srcset / data-lazy-srcset (resolusi terbaik, sudah absolut)
 *  2. data-lazy-src / data-src (lazy-load asli)
 *  3. src — hanya bila bukan placeholder data:image
 *
 * PENTING: KlikXXI memakai lazy-load plugin yang menaruh placeholder SVG
 * di `src` dan gambar asli di `data-lazy-src`/`data-lazy-srcset`.
 */
private fun Element?.getPosterImageUrl(): String? {
    if (this == null) return null

    // 1) srcset (pilih kandidat terakhir = resolusi terbesar)
    val srcsetRaw = when {
        hasAttr("data-lazy-srcset") -> attr("data-lazy-srcset")
        hasAttr("srcset") -> attr("srcset")
        else -> null
    }
    if (!srcsetRaw.isNullOrBlank()) {
        val best = srcsetRaw.trim().split(",")
            .map { it.trim().split(" ")[0] }
            .lastOrNull { !it.isPlaceholderImage() }
        best.asPosterUrl()?.let { return it }
    }

    // 2) data-lazy-src / data-src
    val dataSrc = when {
        hasAttr("data-lazy-src") -> attr("data-lazy-src")
        hasAttr("data-src") -> attr("data-src")
        else -> null
    }
    dataSrc.asPosterUrl()?.let { return it }

    // 3) src — tolak placeholder
    return attr("src").asPosterUrl()
}

class KlikxxiProvider : MainAPI() {
    companion object {
        private const val SEL_ARTICLE = "article.item, div.gmr-item-modulepost, article.item-infinite"
        private const val SEL_TITLE = "h1.entry-title, h2.entry-title, div.mvic-desc h3"
        private const val SEL_POSTER = "figure.pull-left > img, .mvic-thumb img, .poster img, .content-thumbnail img, img.wp-post-image, img[itemprop=image]"
        private const val SEL_DESC = "div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p"
        private const val SEL_RECOMMEND = "article.item.col-md-20, article.item-infinite.col-md-20, div.gmr-recent-posts-wrapper article"
        private const val SEL_SEASON_BLOCK = "div.gmr-season-block, .season-block"
        private const val SEL_EPISODE_LINK = "div.gmr-season-episodes a, .episode-list a, .gmr-listseries a"
        private const val SEL_PLAYER_ID = "div#muvipro_player_content_id, input#post_id"
        private const val SEL_TAB_CONTENT = "div.tab-content-ajax, .tab-pane"
        private val QUALITY_CLASS_REGEX = Regex("hd|sd|cam|ts|hdts|hdts2|hdrip|webrip|bluray|brrip|fhd|uhd|4k", RegexOption.IGNORE_CASE)
        private val DIGIT_REGEX = Regex("(\\d+)")
        private val EPISODE_NUM_REGEX = Regex("(?:E(?:p(?:isode)?)?|Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** Domain mirror KlikXXI, urutan prioritas.
         * klikxxi.me (lama) sekarang 301 → klikxxi.shop. */
        private val domains = listOf(
            "https://klikxxi.shop",
            "https://klikxxi.me",
        )

        /** Domain lama/baru untuk normalisasi URL tersimpan */
        private val KNOWN_HOSTS = listOf("klikxxi.me", "klikxxi.shop", "klikxxi.fit")

        private val deadDomains = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val DEAD_TTL_MS = 10 * 60 * 1000L

        private fun isDead(host: String?): Boolean {
            if (host == null) return false
            val markedAt = deadDomains[host] ?: return false
            if (System.currentTimeMillis() - markedAt > DEAD_TTL_MS) {
                deadDomains.remove(host)
                return false
            }
            return true
        }

        private fun markDead(host: String?) {
            if (host != null) deadDomains[host] = System.currentTimeMillis()
        }

        private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
    }

    override var mainUrl = domains.first()
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

        val document = fetchDocument(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = document.select(SEL_ARTICLE)
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    /** Fetch dokumen dengan fallback multi-domain + negative cache */
    private suspend fun fetchDocument(url: String): Document? {
        val host = hostOf(url)
        if (!isDead(host)) {
            runCatching { app.get(url, timeout = 15_000L).document }.getOrNull()?.let { return it }
            markDead(host)
        }
        for (domain in domains) {
            val mirrorHost = hostOf(domain) ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = url.replace(host ?: "", mirrorHost)
            val doc = runCatching { app.get(mirrorUrl, timeout = 15_000L).document }.getOrNull()
            if (doc != null) {
                mainUrl = domain
                return doc
            }
            markDead(mirrorHost)
        }
        return null
    }

    /** Normalisasi URL lama (klikxxi.me dll) ke domain aktif */
    private fun normalizeUrl(url: String): String {
        val host = hostOf(url) ?: return url
        if (host in KNOWN_HOSTS && !url.startsWith(mainUrl)) {
            return url.replace("https://$host", mainUrl).replace("http://$host", mainUrl)
        }
        return url
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("h2.entry-title a, h3.entry-title a") ?: return null

        val href = fixUrl(linkElement.attr("href"))

        val title = linkElement.text().trim()

        if (title.isBlank()) return null

        val posterUrl = this.selectFirst(
            ".wp-block-post-featured-image img, .wp-block-post-featured-image a img, " +
            "figure.wp-block-post-featured-image img, .content-thumbnail img, " +
            "img.wp-post-image, img.attachment-large, img.attachment-medium, img[itemprop=image]"
        )?.getPosterImageUrl()?.let { fixUrl(it) }
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
        val document = fetchDocument("$mainUrl/?s=$encodedQuery") ?: return emptyList()
        return document.select(SEL_ARTICLE)
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a, h3.entry-title a")?.text()?.trim() ?: return null
        val hrefAttr = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(hrefAttr)
        val posterUrl = this.selectFirst(
            ".wp-block-post-featured-image img, .wp-block-post-featured-image a img, " +
            "figure.wp-block-post-featured-image img, .content-thumbnail img, " +
            "img.wp-post-image, img.attachment-large, img.attachment-medium, img[itemprop=image]"
        )?.getPosterImageUrl()?.let { fixUrl(it) }
        
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
        val safeUrl = normalizeUrl(url)
        val document = fetchDocument(safeUrl)
            ?: return newMovieLoadResponse("Error", safeUrl, TvType.Movie, safeUrl) {
                this.plot = "Failed to load page: network error"
            }

        val title = cleanTitle(document.selectFirst(SEL_TITLE)?.text())

        val poster = document
            .selectFirst(SEL_POSTER)
            ?.getPosterImageUrl()
            ?.let { fixUrl(it) }

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
            newTvSeriesLoadResponse(title, safeUrl, TvType.TvSeries, episodes) {
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
            newMovieLoadResponse(title, safeUrl, TvType.Movie, safeUrl) {
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
        val safeData = normalizeUrl(data)
        val document = fetchDocument(safeData)
            ?: throw ErrorLoadingException("Gagal memuat video")
        
        val postId = document
            .selectFirst(SEL_PLAYER_ID)?.attr("data-id")
            ?: document.selectFirst("[data-post-id]")?.text()?.trim()
            ?: document.selectFirst("#post_id, input[name=post_id]")?.attr("value")
        
        if (postId.isNullOrBlank()) return false

        var foundAny = false
        val tabs = document.select(SEL_TAB_CONTENT)

        // AJAX paralel ke semua server (sebelumnya sequential = lambat)
        tabs.amap { tab ->
            val tabId = tab.attr("id").ifBlank { tab.attr("data-tab") }.ifBlank { tab.attr("name") }
            if (tabId.isBlank()) return@amap

            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to postId
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to safeData
                    ),
                    timeout = 12_000L
                ).document
            }.getOrNull() ?: return@amap

            val iframe = response.selectFirst("iframe")?.getIframeAttr()
                ?: response.selectFirst("source[src]")?.attr("src")
                ?: response.text().substringAfter("window.location.href = \"").substringBefore("\"")
            if (iframe.isNullOrBlank()) return@amap

            val link = httpsify(iframe)
            runCatching {
                loadExtractor(link, safeData, subtitleCallback) {
                    foundAny = true
                    callback(it)
                }
            }
        }

        // Fallback: cari iframe langsung di halaman (bila tab-content tidak ada)
        if (!foundAny) {
            document.select("div.gmr-embed-responsive iframe, .player-embed iframe, iframe[src]").amap { iframe ->
                val src = iframe.getIframeAttr() ?: return@amap
                if (src.contains("youtube")) return@amap
                runCatching {
                    loadExtractor(httpsify(src), safeData, subtitleCallback) {
                        foundAny = true
                        callback(it)
                    }
                }
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
