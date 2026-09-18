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
    override var mainUrl = "https://ww44.pencurimovie.baby"
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

        /** Domain mirror PencuriMovie, urutan prioritas.
         * ww99.pencurimovie.bond (lama) mati → ww44.pencurimovie.baby. */
        private val domains = listOf(
            "https://ww44.pencurimovie.baby",
            "https://ww43.pencurimovie.baby",
            "https://ww42.pencurimovie.baby",
        )

        /** Pola host lama/baru untuk normalisasi URL tersimpan */
        private val PENCURI_HOST_REGEX = Regex("""^https?://ww\d+\.pencurimovie\.(?:bond|baby|sbs)""", RegexOption.IGNORE_CASE)

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

        private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()
    }

    /** Fetch dokumen dengan fallback multi-domain + negative cache */
    private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val host = hostOf(url)
        if (!isDead(host)) {
            runCatching { app.get(url, timeout = 30_000L).document }.getOrNull()?.let { return it }
            markDead(host)
        }
        for (domain in domains) {
            val mirrorHost = hostOf(domain) ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = url.replace(host ?: "", mirrorHost)
            val doc = runCatching { app.get(mirrorUrl, timeout = 30_000L).document }.getOrNull()
            if (doc != null) {
                mainUrl = domain
                return doc
            }
            markDead(mirrorHost)
        }
        throw ErrorLoadingException("Semua domain PencuriMovie tidak dapat diakses")
    }

    /** Normalisasi URL lama (ww99.pencurimovie.bond dll) ke domain aktif */
    private fun normalizeUrl(url: String): String {
        if (PENCURI_HOST_REGEX.containsMatchIn(url) && !url.startsWith(mainUrl)) {
            return PENCURI_HOST_REGEX.replaceFirst(url, mainUrl)
        }
        return url
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
        val document = fetchDocument("$mainUrl/${request.data}/page/$page")
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
        val document = fetchDocument("${mainUrl}?s=$encodedQuery")
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    private fun infoParagraphs(doc: Element, key: String): List<Element> =
        doc.select("div.mvic-info p").filter { it.text().startsWith(key) }

    override suspend fun load(url: String): LoadResponse {
        val safeUrl = normalizeUrl(url)
        val document = fetchDocument(safeUrl)
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

            newTvSeriesLoadResponse(title, safeUrl, TvType.TvSeries, episodes) {
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
            newMovieLoadResponse(title, safeUrl, TvType.Movie, safeUrl) {
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
            val safeData = normalizeUrl(data)
            val document = fetchDocument(safeData)

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

