package com.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * IDLIXProvider - 1 codebase multi-domain untuk IDLIX / LK21 / Rebahin
 * 
 * Semua 3 site menggunakan theme muvipro yang sama (mirip Dutamovie & Klikxxi),
 * jadi selector & loadLinks logic sama persis. Hanya mainUrl yang beda (domain rotate
 * karena Kominfo). Provider ini otomatis fallback ke mirror jika domain utama down.
 * 
 * Extractor mudah: FileMoon, Streamtape, Doodstream, Mixdrop, StreamWish, VidHide, Hxfile
 * semua support loadExtractor generic (MediaFlow-compatible).
 */
class IDLIXProvider : MainAPI() {

    companion object {
        /** Daftar domain mirror - urutan prioritas, yang paling stabil di atas */
        val domains = listOf(
            "https://tv4.idlixian.com",
            "https://tv3.idlixian.com",
            "https://idlixian.com",
            "https://idflix.my.id",
            "https://lk21official.wiki",
            "https://rebahinxxi.shop"
        )

        private val IMAGE_SIZE_REGEX = Regex("(-\\d+x\\d*)")
        private val EPISODE_NUM_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val SEASON_EP_CLEAN_REGEX = Regex("\\s*(Season|Episode)\\s*.*", RegexOption.IGNORE_CASE)
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val PERMALINK_REGEX = Regex("(?i)Permalink to\\s*")
    }

    override var mainUrl = domains.first()
    override var name = "IDLIX"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Terbaru",
        "trending/page/%d/" to "Trending",
        "genre/action/page/%d/" to "Action",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/animation/page/%d/" to "Animation",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/crime/page/%d/" to "Crime",
        "genre/drama/page/%d/" to "Drama",
        "genre/fantasy/page/%d/" to "Fantasy",
        "genre/horror/page/%d/" to "Horror",
        "genre/romance/page/%d/" to "Romance",
        "genre/sci-fi/page/%d/" to "Sci-Fi",
        "genre/thriller/page/%d/" to "Thriller",
        "country/korea/page/%d/" to "Korea",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/japan/page/%d/" to "Jepang",
        "country/china/page/%d/" to "China"
    )

    // Helper: coba fetch dengan fallback domain jika 403/timeout
    private suspend fun getDocument(url: String): Document? {
        // Try requested URL first
        runCatching { app.get(url, timeout = 15_000L).document }.getOrNull()?.let { return it }
        // Fallback: ganti host dengan mirror
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        for (domain in domains) {
            val mirrorHost = runCatching { URI(domain).host }.getOrNull() ?: continue
            if (uri.host == mirrorHost) continue
            val mirrorUrl = url.replace(uri.host, mirrorHost)
            runCatching { app.get(mirrorUrl, timeout = 15_000L).document }.getOrNull()?.let {
                // update mainUrl ke mirror yang berhasil
                mainUrl = "https://${mirrorHost}"
                return it
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val url = "$mainUrl/$path"
        val document = getDocument(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = document.select("article.item, article.item-infinite, div.gmr-item-modulepost, div.ml-item")
            .mapNotNull { it.toSearchItem() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title > a, h3.entry-title > a, a[title] h2, .entry-title a") ?: return null
        val title = titleEl.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(titleEl.attr("href").ifBlank { selectFirst("a")?.attr("href") ?: return null })
        val poster = fixUrlNull(
            selectFirst("a > img, img.wp-post-image, img.attachment-medium, img[data-src]")
                ?.getImageAttr()
        )?.fixImageQuality()

        val quality = selectFirst("div.gmr-qual, div.gmr-quality-item > a, span.quality, div.quality")?.text()?.trim()
        val typeText = selectFirst("div.gmr-posttype-item, .post-type, div.gmr-numbeps")?.text()
        val isSeries = href.contains("/tv/") || href.contains("/series/") ||
                typeText?.contains("TV", true) == true ||
                selectFirst("div.gmr-numbeps, span.episode-count") != null

        // Detect type via URL / quality presence (mirip DutaMovie.kt:71)
        return if (isSeries) {
            val ep = Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                if (ep != null) addSub(ep)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                if (!quality.isNullOrBlank()) addQuality(quality.replace("-", ""))
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // IDLIX/LK21 search param: ?s= & post_type[]=post & post_type[]=tv
        val urlsToTry = listOf(
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        for (url in urlsToTry) {
            val doc = getDocument(url) ?: continue
            val results = doc.select("article.item, article.item-infinite, div.result-item, div.ml-item")
                .mapNotNull { it.toSearchItem() }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url) ?: return newMovieLoadResponse("Error", url, TvType.Movie, url) {
            this.plot = "Gagal memuat halaman (semua mirror down)"
        }

        val title = document.selectFirst("h1.entry-title, h1.mvic-desc h3, div.mvic-desc h3, h1")
            ?.text()?.replace(SEASON_EP_CLEAN_REGEX, "")?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.substringBefore(" -")?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(
            document.selectFirst("figure.pull-left > img, div.thumb img, img.wp-post-image, .mvic-thumb img, .poster img")
                ?.getImageAttr()
        )?.fixImageQuality()

        val tags = document.select("div.gmr-moviedata a[href*=genre], div.genres a, a[href*=genre]").map { it.text() }.distinct()
        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, span.year a, a[href*=year], time[itemprop=dateCreated]")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tvType = if (url.contains("/tv/") || url.contains("/series/") || document.select("div.vid-episodes, div.gmr-listseries, div.episodelist, div#episode-list").isNotEmpty()) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p, div.synopsis p, .mvic-desc p")
            ?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, a.trailer, iframe[src*=youtube]")?.attr("href")?.takeIf { it.contains("youtube") }
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], span.imdb-r, div.rating b")?.text()?.trim()
        val actors = document.select("span[itemprop=actors] a, div.cast a, a[href*=cast]").map { it.text() }.distinct()
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration], span.runtime")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20, div.movies-list article, div.ml-item").mapNotNull { it.toSearchItem() }.take(12)

        return if (tvType == TvType.TvSeries) {
            val episodes = parseEpisodes(document, url, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String, poster: String?): List<Episode> {
        // IDLIX/LK21 episode biasanya di div.vid-episodes atau div.gmr-listseries
        val epElements = document.select("div.vid-episodes a, div.gmr-listseries a, div.episodelist a, ul.episodes a, div#episode-list a")
        if (epElements.isNotEmpty()) {
            return epElements.mapNotNull { el ->
                val href = fixUrl(el.attr("href").ifBlank { return@mapNotNull null })
                val rawTitle = el.attr("title").takeIf { it.isNotBlank() } ?: el.text()
                val cleanTitle = rawTitle.replaceFirst(PERMALINK_REGEX, "").trim().ifBlank { rawTitle }
                val epNum = EPISODE_NUM_REGEX.find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: cleanTitle.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                val formatted = epNum?.let { "Episode $it" } ?: cleanTitle
                newEpisode(href) {
                    this.name = formatted
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.filter { it.episode != null }.sortedBy { it.episode }
        }
        // Fallback: pagination episode via season block (mirip KlikxxiProvider.kt:253)
        val seasonBlocks = document.select("div.gmr-season-block")
        if (seasonBlocks.isNotEmpty()) {
            return seasonBlocks.flatMapIndexed { sIdx, block ->
                val seasonNum = block.selectFirst("h3, .season-name")?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: (sIdx + 1)
                block.select("a[href*=/episode/], div.gmr-season-episodes a").mapNotNull { a ->
                    val href = fixUrl(a.attr("href").ifBlank { return@mapNotNull null })
                    val name = a.text().trim().ifBlank { "Episode" }
                    val epNum = EPISODE_NUM_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(href) {
                        this.name = name
                        this.season = seasonNum
                        this.episode = epNum
                    }
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val referer = "$baseUrl/"

        val document = getDocument(data) ?: throw ErrorLoadingException("Gagal memuat video (semua mirror down)")

        var found = false

        // 1) Direct iframes (paling sering di IDLIX) - mirip DutaMovie.kt:182
        document.select("div.gmr-embed-responsive iframe, div.player-embed iframe, iframe[data-litespeed-src], iframe[src]").forEach { iframe ->
            val src = iframe.getIframeAttr()?.let { httpsify(it) }?.takeIf { it.isNotBlank() } ?: return@forEach
            // Filter youtube/trailer
            if (src.contains("youtube.com") || src.contains("youtu.be")) return@forEach
            found = true
            loadExtractor(src, referer, subtitleCallback) { link -> callback(link) }
        }

        // 2) muvipro_player_content via AJAX (DutaMovie.kt:188 & KlikxxiProvider.kt:317)
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
            ?: document.selectFirst("input#post_id, [data-post-id], #post_id")?.attr("value")
            ?: document.selectFirst("[data-id]")?.attr("data-id")

        if (!postId.isNullOrBlank()) {
            val tabs = document.select("div.tab-content-ajax, div.tab-pane")
            if (tabs.isNotEmpty()) {
                tabs.amap { tab ->
                    val tabId = tab.attr("id").ifBlank { tab.attr("data-tab") }.ifBlank { return@amap }
                    runCatching {
                        val ajaxDoc = app.post(
                            "$baseUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to tabId,
                                "post_id" to postId
                            ),
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                            timeout = 15_000L
                        ).document
                        ajaxDoc.select("iframe").forEach { iframe ->
                            iframe.getIframeAttr()?.let { src ->
                                val link = httpsify(src)
                                if (link.contains("youtube")) return@forEach
                                found = true
                                loadExtractor(link, referer, subtitleCallback) { callback(it) }
                            }
                        }
                        // Kadang response berisi <source> m3u8 langsung
                        ajaxDoc.select("source[src]").forEach { srcEl ->
                            val m3u8 = srcEl.attr("src")
                            if (m3u8.contains(".m3u8")) {
                                found = true
                                callback(
                                    newExtractorLink("IDLIX", "IDLIX Direct", m3u8, ExtractorLinkType.M3U8) {
                                        this.referer = referer
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Fallback: tabs via href (DutaMovie.kt:206)
                val tabUrls = document.select("ul.muvipro-player-tabs li a, ul.nav-tabs li a")
                    .map { fixUrl(it.attr("href")) }
                    .filter { it != data && it.isNotBlank() }
                for (tabUrl in tabUrls) {
                    runCatching {
                        val tabDoc = getDocument(tabUrl) ?: return@runCatching
                        tabDoc.select("div.gmr-embed-responsive iframe, iframe").forEach { iframe ->
                            iframe.getIframeAttr()?.let { src ->
                                val link = httpsify(src)
                                if (link.contains("youtube")) return@forEach
                                found = true
                                loadExtractor(link, referer, subtitleCallback) { callback(it) }
                            }
                        }
                    }
                }
            }
        }

        // 3) Generic fallback: cari semua link embed lain (short.icu, hxfile, etc)
        if (!found) {
            val pageHtml = document.html()
            // Cari pola https://xxx/e/xxx yang sering dipakai IDLIX
            Regex("""https?://[^"'\s<>]+\/(?:e|v|embed)\/[a-zA-Z0-9_-]+""").findAll(pageHtml).forEach { m ->
                val url = m.value
                if (url.contains("youtube") || url.contains("facebook")) return@forEach
                runCatching {
                    found = true
                    loadExtractor(url, referer, subtitleCallback) { callback(it) }
                }
            }
        }

        return found
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ").substringBefore(",")
        else -> attr("abs:src")
    }

    private fun Element.getIframeAttr(): String? =
        this.attr("data-litespeed-src").takeIf { it.isNotEmpty() } ?: this.attr("src").takeIf { it.isNotEmpty() } ?: this.attr("data-src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = IMAGE_SIZE_REGEX.find(this)?.value ?: return this
        return replace(match, "")
    }

    private fun getBaseUrl(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: mainUrl
}
