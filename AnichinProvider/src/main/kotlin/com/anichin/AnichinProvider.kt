package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnichinProvider : MainAPI() {
    companion object {
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val YEAR_REGEX = Regex("(\\d{4})")
    }

    override var mainUrl = "https://anichin.cafe"
    override var name = "Anichin"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing/page/%d/" to "Ongoing",
        "$mainUrl/completed/page/%d/" to "Completed",
        "$mainUrl/seri/?page=%d&status=&type=&order=" to "Donghua List",
    )

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when {
            status?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            status?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private fun getType(type: String?): TvType {
        return when {
            type?.contains("movie", true) == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst(".bsx > a, h2.entry-title a, .tt a") ?: return null
        val href = anchor.attr("abs:href")
        val title = anchor.attr("title").ifBlank {
            selectFirst("h2[itemprop=headline], .tt h2, .tt")?.text()?.trim().orEmpty()
        }
        if (title.isBlank() || href.isBlank()) return null

        val poster = selectFirst("img")?.getImageAttr()
        val episode = selectFirst(".epx")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
        val type = getType(selectFirst(".typez")?.text())

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = request.data.replace("%d", page.toString())
            val document = app.get(url, timeout = 15_000L).document
            val results = document.select(".listupd article").mapNotNull { it.toSearchResult() }
            newHomePageResponse(
                HomePageList(request.name, results),
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to load main page: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", timeout = 15_000L).document
            document.select(".listupd article").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 15_000L).document
        val title = document.selectFirst(".entry-title, h1.entry-title, .post-title h1, [itemprop*=name] h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val poster = document.selectFirst(".thumb img, .bigcontent .thumb img")?.getImageAttr()
        val description = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst(".desc")?.text()?.trim()
        val tags = document.select(".genxed a").map { it.text() }
        val status = getStatus(document.select(".spe span").firstOrNull { it.text().contains("Status:", true) }?.text())
        val year = YEAR_REGEX.find(
            document.select(".spe span").firstOrNull { it.text().contains("Released:", true) }?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = document.select(".eplister ul li a, .episode-list a, [id*=episode] li a").mapNotNull { element ->
            val href = element.attr("abs:href").ifBlank { return@mapNotNull null }
            val number = element.selectFirst(".epl-num")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
            val name = element.selectFirst(".epl-title")?.text()?.trim()
            newEpisode(href) {
                this.episode = number
                this.name = name
            }
        }.reversed()

        val recommendations = document.select(".bixbox:has(h3:contains(Recommended Series)) .listupd article")
            .mapNotNull { it.toSearchResult() }

         return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.year = year
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, timeout = 15_000L).document
            val links = mutableSetOf<String>()

            document.select("#embed_holder iframe[src], .player-embed iframe[src], [id*=player] iframe[src]").mapTo(links) { it.attr("abs:src") }
            document.select("select.mirror option[value]").amap { option ->
                val decoded = decodeServerHash(option.attr("value")) ?: return@amap
                Jsoup.parse(decoded).select("iframe[src]").mapTo(links) { it.attr("src") }
            }

            links.filter { it.isNotBlank() }.amap { link ->
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }

            links.isNotEmpty()
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    private fun decodeServerHash(hash: String): String? {
        return try {
            String(Base64.decode(hash, Base64.DEFAULT))
        } catch (_: Throwable) {
            null
        }
    }
}
