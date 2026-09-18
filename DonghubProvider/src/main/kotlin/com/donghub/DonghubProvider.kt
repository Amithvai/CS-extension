package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * DonghubProvider - donghive.vip (dulu donghub.vip)
 *
 * Situs berpindah domain dari donghub.vip → donghive.vip.
 * Theme themesia, player iframe (Dailymotion/Okru/Archive.org/dll) via
 * #embed_holder dan select.mirror (base64).
 */
class DonghubProvider : MainAPI() {
    companion object {
        /** Domain mirror, urutan prioritas */
        private val domains = listOf(
            "https://donghive.vip",
            "https://donghub.vip",
        )

        /** Domain lama yang perlu dinormalisasi */
        private val KNOWN_HOSTS = listOf("donghub.vip", "donghive.vip")

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
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    // Path relatif agar tetap valid saat mainUrl berganti mirror
    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Releases",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?type=movie&order=update" to "Movie"
    )

    private suspend fun fetchDocument(url: String): Document {
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
        throw ErrorLoadingException("Semua domain Donghub tidak dapat diakses")
    }

    private fun normalizeUrl(url: String): String {
        val host = hostOf(url) ?: return url
        if (host in KNOWN_HOSTS && !url.startsWith(mainUrl)) {
            return url.replace("https://$host", mainUrl).replace("http://$host", mainUrl)
        }
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDocument("$mainUrl/${request.data}&page=$page")
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("a.next.page-numbers, .pagination .next, .next") != null
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a") ?: return null
        val title = anchor.attr("title").trim().ifBlank {
            selectFirst("h2[itemprop=headline], .tt h2")?.text()?.trim().orEmpty()
        }
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (title.isBlank() || href.isBlank()) return null
        val poster = selectFirst("img")?.getsrcAttribute()
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            try {
                val document = fetchDocument("$mainUrl/page/$i/?s=${URLEncoder.encode(query, "UTF-8")}")
                val result = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
                if (result.isEmpty()) break
                list.addAll(result)
            } catch (e: Exception) {
                break
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(normalizeUrl(url))
        val title = document.selectFirst("h1.entry-title, .entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", true)

        val poster = document.select("div.ime > img").first()?.getsrcAttribute()
            ?: document.select("meta[property=og:image]").attr("content")

        val epBlocks = document.select(".eplister li, div.list-episode .episode-item, #episodes a")

        return if (!isMovie && epBlocks.isNotEmpty()) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle.trim()
                    this.posterUrl = fixUrlNull(poster)
                }
            }.reversed()

            newTvSeriesLoadResponse(title, fixUrl(url), TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, fixUrl(url), TvType.Movie, movieLink) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(normalizeUrl(data))
        var foundAny = false

        val serverOptions = document.select(".mobius option, select.mirror option")
        if (serverOptions.isNotEmpty()) {
            for (item in serverOptions) {
                try {
                    val base64 = item.attr("value")
                    if (base64.isBlank()) continue

                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    val iframeSrc = doc.select("iframe").attr("src")
                    if (iframeSrc.isBlank()) continue

                    foundAny = true
                    loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        val directIframes = document.select(
            "div#embed_holder iframe, div.player iframe, div.embed-responsive iframe, " +
            "iframe[src*=dailymotion], iframe[src*=ok.ru], iframe[src*=archive.org], " +
            "iframe[src*=youtube], iframe[src*=rpmvid], div#player iframe, iframe.video-player"
        )
        for (iframe in directIframes) {
            try {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundAny = true
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        if (!foundAny) throw ErrorLoadingException("Tidak ada source tersedia")
        return true
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        return when {
            dataSrc.startsWith("http") -> dataSrc
            src.startsWith("http") -> src
            else -> ""
        }
    }
}
