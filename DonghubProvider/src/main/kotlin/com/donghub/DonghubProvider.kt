package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class DonghubProvider : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Releases",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page", timeout = 15_000L).document
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("a.next.page-numbers, .pagination .next, .next") != null
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a") ?: return null
        val title = anchor.attr("title").trim()
        val href = fixUrl(anchor.attr("href"))
        if (title.isBlank() || href.isBlank()) return null
        val poster = fixUrlNull(anchor.selectFirst("img")?.getsrcAttribute())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            try {
                val document = app.get("$mainUrl/page/$i/?s=${URLEncoder.encode(query, "UTF-8")}", timeout = 15_000L).document
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
        val document = app.get(url, timeout = 15_000L).document
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", true)

        var poster = document.select("div.ime > img").first()?.getsrcAttribute()
            ?: document.select("meta[property=og:image]").attr("content")

        val epBlocks = document.select(".eplister li, div.list-episode .episode-item, #episodes a")

        return if (!isMovie) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle.trim()
                    this.posterUrl = fixUrlNull(poster)
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieLink, TvType.Movie, movieLink) {
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
        val document = app.get(data, timeout = 15_000L).document
        var foundAny = false

        val serverOptions = document.select(".mobius option")
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
                    loadExtractor(fixUrl(iframeSrc), subtitleCallback, callback)
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
                    loadExtractor(fixUrl(src), subtitleCallback, callback)
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
