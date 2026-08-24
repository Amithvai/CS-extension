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
        /** Daftar domain mirror - urutan prioritas.
         * CATATAN: tv3/tv4.idlixian.com dihapus (NXDOMAIN per logcat 2026-08-24).
         * Domain mati akan otomatis di-skip via negative cache DEAD_TTL_MS. */
        val domains = listOf(
            "https://lk21official.wiki",
            "https://tv12.lk21official.cc",
            "https://idlixian.com",
            "https://idflix.my.id",
            "https://rebahinxxi.shop"
        )

        /** Negative-cache domain yang gagal (DNS/403/timeout): skip selama 10 menit */
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
        "latest/page/%d/" to "Terbaru",
        "populer/page/%d/" to "Populer",
        "rating/page/%d/" to "Top Rating",
        "release/page/%d/" to "Rilis Terbaru",
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
        "country/south-korea/page/%d/" to "Korea",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/japan/page/%d/" to "Jepang",
        "country/china/page/%d/" to "China"
    )

    // Helper: coba fetch dengan fallback domain jika 403/timeout/NXDOMAIN
    // Domain gagal di-mark dead selama 10 menit agar tidak retry storm (logcat: NXDOMAIN spam)
    private suspend fun getDocument(url: String): Document? {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host
        if (!isDead(host)) {
            runCatching { app.get(url, timeout = 15_000L).document }.getOrNull()?.let { return it }
            markDead(host)
        }
        // Fallback: ganti host dengan mirror yang masih hidup
        for (domain in domains) {
            val mirrorHost = runCatching { URI(domain).host }.getOrNull() ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = url.replace(host ?: "", mirrorHost)
            val doc = runCatching { app.get(mirrorUrl, timeout = 15_000L).document }.getOrNull()
            if (doc != null) {
                mainUrl = "https://$mirrorHost"
                return doc
            }
            markDead(mirrorHost)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        // page 1 for lk21: "/" juga valid, tapi "/latest/page/1/" juga valid; keep as-is
        val url = if (path.isBlank()) mainUrl else "$mainUrl/$path"
        val document = getDocument(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        // Support both muvipro (article.item) dan lk21official (gallery-grid article, li.slider)
        val selectors = listOf(
            "div#post-container article",
            "div.gallery-grid article",
            "article[itemscope]",
            "li.slider article",
            "article.item",
            "article.item-infinite",
            "div.gmr-item-modulepost",
            "div.ml-item"
        )
        val items = selectors.flatMap { sel -> document.select(sel).mapNotNull { it.toSearchItem() } }
            .distinctBy { it.url }
        // Fallback: jika masih kosong, coba selector generik figure a
        val finalItems = if (items.isEmpty()) {
            document.select("figure a[href]").mapNotNull { a ->
                val article = a.closest("article") ?: a.parent()?.parent() ?: return@mapNotNull null
                article.toSearchItem()
            }.distinctBy { it.url }
        } else items
        return newHomePageResponse(request.name, finalItems, hasNext = finalItems.isNotEmpty())
    }

    private fun Element.toSearchItem(): SearchResponse? {
        // Support lk21official: h3.poster-title di figcaption, muvipro: h2.entry-title
        val titleEl = selectFirst("h3.poster-title, h3, h2.entry-title > a, h3.entry-title > a, a[title] h2, .entry-title a, figcaption h3, .poster-title")
            ?: selectFirst("a[title]") ?: return null
        // Jika titleEl adalah <a>, ambil text nya; jika h3, ambil text h3
        val title = when {
            titleEl.tagName() == "a" -> titleEl.attr("title").ifBlank { titleEl.text() }.trim()
            else -> titleEl.text().trim()
        }.takeIf { it.isNotBlank() } ?: return null

        // Cari href: prioritas dari figure a, lalu dari titleEl parent a
        val hrefRaw = selectFirst("figure a[href], a[href]")?.attr("href")
            ?: titleEl.attr("href").takeIf { it.isNotBlank() }
            ?: selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(hrefRaw)

        val poster = fixUrlNull(
            selectFirst("img[data-src], img.lazyload, a > img, img.wp-post-image, img.attachment-medium, picture img, img[itemprop=image]")
                ?.getImageAttr()
        )?.fixImageQuality()

        val quality = selectFirst("span.label-HD, span.label, div.gmr-qual, div.gmr-quality-item > a, span.quality, div.quality, .label-HD")?.text()?.trim()
        val typeText = selectFirst("div.gmr-posttype-item, .post-type, div.gmr-numbeps, meta[itemprop=genre]")?.text()
            ?: selectFirst("meta[itemprop=genre]")?.attr("content")
        val isSeries = href.contains("/tv/") || href.contains("/series/") || href.contains("/nontondrama") ||
                typeText?.contains("TV", true) == true ||
                selectFirst("div.gmr-numbeps, span.episode-count") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
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
        val urlsToTry = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$encoded",
            "$mainUrl/?search=$encoded"
        )
        val selectors = listOf(
            "div#post-container article",
            "div.gallery-grid article",
            "article[itemscope]",
            "article.item",
            "article.item-infinite",
            "div.result-item",
            "div.ml-item",
            "figure a"
        )
        for (url in urlsToTry) {
            val doc = getDocument(url) ?: continue
            for (sel in selectors) {
                val results = doc.select(sel).mapNotNull {
                    // Untuk figure a, perlu context article
                    val el = if (sel == "figure a") it.closest("article") ?: it.parent()?.parent() ?: it else it
                    el.toSearchItem()
                }.distinctBy { it.url }
                if (results.isNotEmpty()) return results
            }
            // Juga cek apakah hasil search mengandung query di title (filter false positive homepage)
            val all = doc.select("article").mapNotNull { it.toSearchItem() }
            if (all.isNotEmpty()) {
                // Jika homepage ter-return (tidak filtered), tetap return tapi filter by query
                val filtered = all.filter { it.name.contains(query, ignoreCase = true) }
                if (filtered.isNotEmpty()) return filtered
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url) ?: return newMovieLoadResponse("Error", url, TvType.Movie, url) {
            this.plot = "Gagal memuat halaman (semua mirror down)"
        }

        // Try lk21official watch-history-data JSON first
        val watchData = document.selectFirst("script#watch-history-data")?.data()?.let { runCatching { it.trim() }.getOrNull() }
        val watchTitle = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(watchData ?: "")?.groupValues?.getOrNull(1)
        val watchYear = Regex("\"year\"\\s*:\\s*(\\d{4})").find(watchData ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        val watchPoster = Regex("\"poster\"\\s*:\\s*\"([^\"]+)\"").find(watchData ?: "")?.groupValues?.getOrNull(1)?.let { fixUrlNull(it) }
        val watchRating = Regex("\"rating\"\\s*:\\s*\"([^\"]+)\"").find(watchData ?: "")?.groupValues?.getOrNull(1)

        // Schema.org JSON-LD
        val schemaScript = document.select("script[type=application/ld+json]").joinToString { it.data() }
        val schemaTitle = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(schemaScript)?.groupValues?.getOrNull(1)?.let { it.substringBefore(" Sub Indo").trim() }
        val schemaPoster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val schemaDesc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(schemaScript)?.groupValues?.getOrNull(1)
        val schemaGenre = Regex("\"genre\"\\s*:\\s*\\[([^\\]]+)]").find(schemaScript)?.groupValues?.getOrNull(1)?.let {
            Regex("\"([^\"]+)\"").findAll(it).map { g -> g.groupValues[1] }.toList()
        } ?: emptyList()
        val schemaActors = Regex("\"actor\"\\s*:\\s*\\[([^\\]]+)]").find(schemaScript)?.let {
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(schemaScript).map { m -> m.groupValues[1] }.toList()
        } ?: emptyList()

        val title = watchTitle
            ?: schemaTitle
            ?: document.selectFirst("h1.entry-title, h1.mvic-desc h3, div.mvic-desc h3, h1, .player-section-wrapper h1, header h1")
                ?.text()?.replace(SEASON_EP_CLEAN_REGEX, "")?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.substringBefore(" -")?.substringBefore(" Lk21")?.trim()
            ?: "Unknown"

        val poster = watchPoster
            ?: schemaPoster
            ?: fixUrlNull(
                document.selectFirst("figure.pull-left > img, div.thumb img, img.wp-post-image, .mvic-thumb img, .poster img, .player-wrapper img, meta[property=og:image]")
                    ?.getImageAttr() ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            )?.fixImageQuality()

        val tags = if (schemaGenre.isNotEmpty()) schemaGenre else document.select("div.gmr-moviedata a[href*=genre], div.genres a, a[href*=genre], a[href*=/genre/]").map { it.text() }.distinct()
        val year = watchYear
            ?: document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, span.year a, a[href*=year], time[itemprop=dateCreated]")
                ?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("span.year, span[itemprop=datePublished]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tvType = if (url.contains("/tv/") || url.contains("/series/") || url.contains("/nontondrama") || document.select("div.vid-episodes, div.gmr-listseries, div.episodelist, div#episode-list").isNotEmpty()) TvType.TvSeries else TvType.Movie
        val description = schemaDesc
            ?: document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p, div.synopsis p, .mvic-desc p, p:has(strong:contains(Cap Farewell))")
                ?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, a.trailer, iframe[src*=youtube]")?.attr("href")?.takeIf { it.contains("youtube") }
        val rating = watchRating ?: document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], span.imdb-r, div.rating b, span[itemprop=ratingValue]")?.text()?.trim()
        val actors = if (schemaActors.isNotEmpty()) schemaActors else document.select("span[itemprop=actors] a, div.cast a, a[href*=cast]").map { it.text() }.distinct()
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration], span.runtime")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
            ?: Regex("\"runtime\"\\s*:\\s*\"([^\"]+)\"").find(watchData ?: "")?.groupValues?.getOrNull(1)?.let { it.replace(NON_DIGIT_REGEX, "").toIntOrNull() }
        val recommendations = document.select("article.item.col-md-20, div.movies-list article, div.ml-item, div.gallery-grid article").mapNotNull { it.toSearchItem() }.take(12)

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

        // 1) LK21Official specific: main-player + player-list + player-select (videonode.de)
        document.select("iframe#main-player, div.player-wrapper iframe, ul#player-list a[data-url], select#player-select option").forEach { el ->
            val src = when (el.tagName()) {
                "option" -> el.attr("value")
                "a" -> el.attr("data-url").ifBlank { el.attr("href") }
                else -> el.getIframeAttr()
            }?.let { httpsify(it) }?.takeIf { it.isNotBlank() } ?: return@forEach
            if (src.contains("youtube.com") || src.contains("youtu.be") || src.contains("facebook")) return@forEach
            // Only process videonode / embed URLs
            if (src.contains("videonode.de") || src.contains("/iframe/") || src.contains("/embed/") || src.contains("p2p") || src.contains("hydrax") || src.contains("turbovip")) {
                found = true
                loadExtractor(src, referer, subtitleCallback) { link -> callback(link) }
                // Also try to fetch videonode page for direct m3u8 fallback
                // Run async: let loadExtractor handle, but also generic regex fallback below will catch
            }
        }

        // 1b) Direct iframes (paling sering di IDLIX muvipro) - mirip DutaMovie.kt:182
        document.select("div.gmr-embed-responsive iframe, div.player-embed iframe, iframe[data-litespeed-src], iframe[src]").forEach { iframe ->
            val src = iframe.getIframeAttr()?.let { httpsify(it) }?.takeIf { it.isNotBlank() } ?: return@forEach
            // Filter youtube/trailer
            if (src.contains("youtube.com") || src.contains("youtu.be")) return@forEach
            // Skip if already handled above (videonode main-player)
            if (iframe.attr("id") == "main-player") return@forEach
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
