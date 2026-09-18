package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Nomat : MainAPI() {

    companion object {
        private val POSTER_URL_REGEX = Regex("url\\('(.*?)'\\)")
        private val EPISODE_TEXT_REGEX = Regex("Eps?.?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val EPISODE_PATH_REGEX = Regex("/episode-(\\d+)")
        private val EPISODE_LABEL_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val DIGITS_ONLY_REGEX = Regex("^\\s*(\\d+)\\s*$")
        private val SEASON_PATH_REGEX = Regex("/season-(\\d+)/")

        /** Domain mirror Nomat, urutan prioritas.
         * nomat.asia (lama) sekarang 301 → nomat.shop. */
        private val domains = listOf(
            "https://nomat.shop",
            "https://nomat.asia",
        )

        /** Domain lama/baru untuk normalisasi URL tersimpan */
        private val KNOWN_HOSTS = listOf("nomat.asia", "nomat.shop")

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
            val document = fetchDocument("$mainUrl/${request.data.format(page)}")
            val items = document.select("a:has(.item-content)").mapNotNull { it.parseItem() }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    /** Fetch dokumen dengan fallback multi-domain + negative cache */
    private suspend fun fetchDocument(url: String, referer: String? = null): Document {
        val host = hostOf(url)
        if (!isDead(host)) {
            runCatching { app.get(url, referer = referer, timeout = 15_000L).document }.getOrNull()?.let { return it }
            markDead(host)
        }
        for (domain in domains) {
            val mirrorHost = hostOf(domain) ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = url.replace(host ?: "", mirrorHost)
            val doc = runCatching { app.get(mirrorUrl, referer = referer, timeout = 15_000L).document }.getOrNull()
            if (doc != null) {
                mainUrl = domain
                return doc
            }
            markDead(mirrorHost)
        }
        throw ErrorLoadingException("Semua domain Nomat tidak dapat diakses")
    }

    /** Normalisasi URL lama (nomat.asia) ke domain aktif */
    private fun normalizeUrl(url: String): String {
        val host = hostOf(url) ?: return url
        if (host in KNOWN_HOSTS && !url.startsWith(mainUrl)) {
            return url.replace("https://$host", mainUrl).replace("http://$host", mainUrl)
        }
        return url
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = fetchDocument("$mainUrl/search/$encodedQuery/")
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
            val safeUrl = normalizeUrl(url)
            val document = fetchDocument(safeUrl)
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

            val isSeries = safeUrl.contains("/serial-tv/") || document.select("div.video-episodes a").isNotEmpty()

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

                newTvSeriesLoadResponse(title, safeUrl, TvType.TvSeries, episodes) {
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

                newMovieLoadResponse(title, safeUrl, TvType.Movie, playUrl ?: safeUrl) {
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
            newMovieLoadResponse("", normalizeUrl(url), TvType.Movie, normalizeUrl(url)) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val safeData = normalizeUrl(data)
            val pageUrl = safeData.ifBlank { mainUrl }

            // Halaman detail nomat → cari tombol play (link nontonhemat.link)
            val detailDoc = fetchDocument(pageUrl, referer = mainUrl)

            // Kasus 1: halaman yang diminta SUDAH halaman player (punya server-item)
            var hasServers = parseEmbedPage(detailDoc, pageUrl, subtitleCallback, callback)
            if (hasServers) return true

            // Kasus 2: follow tombol play ke nontonhemat.link
            val playHref = detailDoc
                .selectFirst("a:has(.play-btn), a[href*='nontonhemat.link']")
                ?.attr("href")
                ?.let { fixUrl(it) }

            if (!playHref.isNullOrBlank()) {
                // PENTING: nontonhemat.link menolak request tanpa Referer origin
                // pemanggil (nomat.shop). Referer URL lengkap halaman juga ditolak
                // ("Invalid Credentials"), jadi gunakan origin + trailing slash.
                val originReferer = runCatching {
                    val u = URI(pageUrl)
                    "${u.scheme}://${u.host}/"
                }.getOrNull() ?: "$mainUrl/"

                val playDoc = runCatching {
                    app.get(playHref, referer = originReferer, timeout = 15_000L).document
                }.getOrNull()

                if (playDoc != null) {
                    hasServers = parseEmbedPage(playDoc, playHref, subtitleCallback, callback)
                }
            }

            hasServers
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
