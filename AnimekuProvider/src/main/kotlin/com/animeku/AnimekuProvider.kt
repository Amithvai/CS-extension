package com.animeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimekuProvider : MainAPI() {
    override var mainUrl = "https://animeku.tv"
    override var name = "Animeku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?orderby=update" to "Latest Update",
        "$mainUrl/anime/?orderby=views" to "Populer",
        "$mainUrl/anime/?orderby=update&status-anime=tamat" to "Tamat",
        "$mainUrl/country-anime/jepang/" to "Anime",
        "$mainUrl/country-anime/china/" to "Donghua",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"
        val document = app.get(url, timeout = 15_000L).document
        val items = document.select("a[href*=/anime/]:has(img)").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("abs:href").takeIf { it.contains("/anime/") } ?: return null
        val title = selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: text().trim().ifBlank { null }
            ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("abs:src")
                ?: selectFirst("img")?.attr("abs:data-src")
        )
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded&post_type=anime", timeout = 15_000L).document
        return document.select("a[href*=/anime/]:has(img)").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 15_000L).document

        val title = document.selectFirst(".ak-series__title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(
            document.selectFirst(".ak-series__cover img")?.attr("abs:src")
        )

        val description = document.selectFirst(".ak-series__lead")?.text()?.trim()
            ?: document.selectFirst("#Synopsis .ak-prose")?.text()?.trim()

        val tags = document.select(".ak-series__genres a.ak-pill").map { it.text().trim() }

        val episodes = document.select("ol.ak-eplist li a.ak-eplist-card").mapNotNull { a ->
            val epHref = a.attr("abs:href")
            val epTitle = a.selectFirst(".ak-eplist-card__title")?.text()?.trim() ?: return@mapNotNull null
            val epNum = a.selectFirst(".ak-eplist-card__num")?.text()
                ?.replace("EP", "", true)?.trim()?.toIntOrNull()
            newEpisode(fixUrl(epHref)) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = poster
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 15_000L).document
        val currentUrl = fixUrl(data)
        val referer = "$mainUrl/"

        // Try quality selector first (direct MP4 from animeku CDN)
        val options = document.select("#Quality_Select option")
        if (options.isNotEmpty()) {
            var found = false
            for (opt in options) {
                val rawUrl = opt.attr("value").takeIf { it.startsWith("http") } ?: continue
                val resLabel = opt.attr("data-res").takeIf { it.isNotBlank() }
                    ?: opt.text().substringBefore("·").trim().takeIf { it.isNotBlank() }
                    ?: "Unknown"
                found = true
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} $resLabel",
                        url = fixUrl(rawUrl),
                        type = ExtractorLinkType.VIDEO  // animeku CDN serves direct MP4, not M3U8
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(resLabel)
                        this.headers = mapOf(
                            "Referer" to referer,
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            if (found) return true
        }

        // Fallback: try video tag directly
        val videoSrc = document.selectFirst("video.ak-vp__video")?.attr("abs:src")
        if (!videoSrc.isNullOrBlank()) {
            val quality = document.selectFirst("video.ak-vp__video")?.attr("data-res")
                ?: "Unknown"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixUrl(videoSrc),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(quality)
                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
            return true
        }

        throw ErrorLoadingException("Tidak ada sumber video di Animeku")
    }
}
