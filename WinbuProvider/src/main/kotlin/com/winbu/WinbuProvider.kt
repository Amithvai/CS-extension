package com.winbu

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.net"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    companion object {
        private val EPISODE_REGEX = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("\\((\\d{4})\\)")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)""""", RegexOption.IGNORE_CASE)
        private val BROKEN_IFRAME = Regex("""(?:mega\.nz/embed/|vidhidepro\.com/v/?$|about:blank)""", RegexOption.IGNORE_CASE)
        private val BLOCKED_HOST = Regex("""https?://winbu\.org""")

        fun String.toMain(): String = BLOCKED_HOST.replace(this, mainUrlOf)
        private val mainUrlOf = "https://winbu.net"
    }

    override val mainPage = mainPageOf(
        "animedonghua" to "Anime Donghua",
        "film" to "Film",
        "others" to "Lainnya",
        "tvshow" to "TV Show",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        val document = app.get(url, timeout = 15_000L).document
        val items = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("ul.pagination a:has(i.fa-caret-right)") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.ml-mask") ?: return null
        val href = fixUrl(link.attr("href")).toMain()
        val title = link.attr("title").ifBlank { selectFirst(".mli-info .judul")?.text().orEmpty() }.trim()
        if (title.isBlank() || href.isBlank()) return null

        val poster = selectFirst("img.mli-thumb")?.attr("src")?.let { fixUrl(it).toMain() }
        val rating = selectFirst("i.info-hidden")?.attr("data-rating")?.toIntOrNull()

        val type = when {
            href.contains("/anime/") -> TvType.Anime
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating.toDouble())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", timeout = 15_000L).document
        return document.select("div.a-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val safeUrl = url.toMain()
        val document = app.get(safeUrl, timeout = 15_000L).document

        val title = document.selectFirst(".m-info .mli-info .judul")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Winbu")?.substringBefore(" Sub Indo")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return newMovieLoadResponse(name, safeUrl, TvType.Movie, safeUrl)

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toMain()
        val description = document.selectFirst(".mli-desc p")?.text()?.trim()
        val tags = document.select(".mli-mvi a[rel=tag]").eachText()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull()
        val year = YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("meta[property=article:modified_time]")?.attr("content")?.take(4)?.toIntOrNull()

        val episodes = document.select("div.tvseason .les-content a").mapNotNull { elem ->
            val href = fixUrl(elem.attr("href")).toMain()
            val name = elem.text().trim()
            if (href.isBlank()) return@mapNotNull null
            val episode = EPISODE_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = name
                this.episode = episode
            }
        }

        val recommendations = document.select("div#movies .ml-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(30)

        return if (episodes.isNotEmpty()) {
            if (safeUrl.contains("/anime/")) {
                newAnimeLoadResponse(title, safeUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    if (year != null) this.year = year
                    this.recommendations = recommendations
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            } else {
                newTvSeriesLoadResponse(title, safeUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    if (year != null) this.year = year
                    this.recommendations = recommendations
                }
            }
        } else {
            newMovieLoadResponse(title, safeUrl, TvType.Movie, safeUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                if (year != null) this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeData = data.toMain()
        Log.d("Winbu", "loadLinks data=$data safeData=$safeData")
        val document = runCatching { app.get(safeData, timeout = 15_000L).document }.getOrNull()
            ?: run { Log.d("Winbu", "loadLinks: app.get failed $safeData"); return false }

        if (document.title().contains("Just a moment") || document.html().contains("challenges.cloudflare.com")) {
            Log.d("Winbu", "loadLinks: Cloudflare challenge detected for $safeData")
        }

        val players = document.select("div.east_player_option").mapNotNull { elem ->
            val post = elem.attr("data-post")
            val nume = elem.attr("data-nume")
            val type = elem.attr("data-type").ifBlank { "schtml" }
            val quality = elem.closest("div.dropdown")?.selectFirst("button.dropdown-toggle")?.text()
                ?.let { RESOLUTION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (post.isBlank() || nume.isBlank()) null else PlayerOption(post, nume, type, quality)
        }.distinctBy { it.post to it.nume }

        Log.d("Winbu", "loadLinks: players found=${players.size} for $safeData")

        // Fallback: halaman film tanpa east_player_option tapi punya iframe langsung
        if (players.isEmpty()) {
            Log.d("Winbu", "loadLinks: no east_player_option, trying direct iframe fallback")
            val directIframes = document.select("div.movieplay iframe, #content-embed iframe, iframe[src]")
                .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") } }
                .map { httpsify(it).toMain() }
                .filterNot { BROKEN_IFRAME.containsMatchIn(it) }
            Log.d("Winbu", "loadLinks: directIframes=${directIframes.size}")
            if (directIframes.isNotEmpty()) {
                var anyDirect = false
                for (u in directIframes) {
                    Log.d("Winbu", "loadLinks: trying direct iframe $u")
                    if (u.endsWith("/#") || u.endsWith("/v/")) continue
                    var produced = false
                    runCatching { loadExtractor(u, safeData, subtitleCallback) { produced = true; anyDirect = true; callback(it) } }
                    Log.d("Winbu", "loadLinks: direct loadExtractor produced=$produced for $u")
                    if (!produced && u.contains("filedon.co")) {
                        val ok = extractFiledonInline(u, safeData, callback)
                        Log.d("Winbu", "loadLinks: direct filedon inline ok=$ok for $u")
                        if (ok) anyDirect = true
                    }
                }
                return anyDirect
            }
            return false
        }

        var foundAny = false
        for (player in players) {
            try {
                Log.d("Winbu", "loadLinks: player_ajax post=${player.post} nume=${player.nume} type=${player.type}")
                val response = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "player_ajax",
                            "post" to player.post,
                            "nume" to player.nume,
                            "type" to player.type
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to safeData,
                            "Accept" to "text/html, */*; q=0.01"
                        ),
                        timeout = 15_000L
                    ).text
                }.getOrNull() ?: run { Log.d("Winbu", "player_ajax null response nume=${player.nume}"); continue }

                Log.d("Winbu", "player_ajax ok nume=${player.nume} len=${response.length} preview=${response.take(150)}")
                val src = IFRAME_SRC_REGEX.find(response)?.groupValues?.get(1) ?: run { Log.d("Winbu", "no iframe src in player_ajax nume=${player.nume}"); continue }
                val url = httpsify(src).toMain().trim()
                Log.d("Winbu", "player_ajax iframe url=$url")
                if (url.endsWith("/#") || url.endsWith("/v/")) { Log.d("Winbu", "skip empty id $url"); continue }
                if (BROKEN_IFRAME.containsMatchIn(url)) { Log.d("Winbu", "skip BROKEN $url"); continue }

                var produced = false
                runCatching {
                    loadExtractor(url, safeData, subtitleCallback) { link ->
                        player.quality?.let { link.quality = it }
                        produced = true
                        foundAny = true
                        Log.d("Winbu", "loadExtractor produced ${link.url} quality=${link.quality}")
                        callback(link)
                    }
                }.onFailure { Log.d("Winbu", "loadExtractor exception for $url : ${it.message}") }
                Log.d("Winbu", "loadExtractor produced=$produced for $url")
                // Inline fallback untuk host yang extractor core mungkin gagal
                if (!produced && url.contains("filedon.co")) {
                    val ok = extractFiledonInline(url, safeData, callback)
                    Log.d("Winbu", "filedon inline fallback ok=$ok for $url")
                    if (ok) foundAny = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("Winbu", "player loop exception nume=${player.nume}: ${e.message}")
                continue
            }
        }

        Log.d("Winbu", "loadLinks final foundAny=$foundAny")
        return foundAny
    }

    // Inline extractor Filedon — fallback jika loadExtractor tidak menemukan handler
    private suspend fun extractFiledonInline(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d("Winbu", "extractFiledonInline start url=$url")
            val doc = app.get(url, referer = referer, timeout = 30_000L).document
            // Coba #app[data-page] dulu (Inertia), fallback ke html mentah
            var json = doc.selectFirst("#app")?.attr("data-page")
            if (json.isNullOrBlank()) {
                Log.d("Winbu", "extractFiledonInline: #app[data-page] empty, fallback to doc.html()")
                json = doc.html()
            } else {
                Log.d("Winbu", "extractFiledonInline: #app json len=${json.length}")
            }
            if (json.isNullOrBlank()) return false
            val hlsMatch = Regex("""\"hls_url\":\"(https:\\/\\/[^"]+\.m3u8[^"]*)""").find(json)
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1].replace("\\/", "/")
                Log.d("Winbu", "extractFiledonInline: hlsUrl=$hlsUrl")
                com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8(
                    "Filedon",
                    fixUrl(hlsUrl),
                    referer = "https://filedon.co/",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).forEach(callback)
                return true
            }
            val m = Regex("""\"url\":\"(https:\\/\\/[^"]+\.mp4[^"]*)""").find(json)
            Log.d("Winbu", "extractFiledonInline: mp4 match found=${m != null}")
            val rawUrl = m?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
            Log.d("Winbu", "extractFiledonInline: rawUrl len=${rawUrl.length}")
            val fileName = Regex("""\"name\":\"([^"]+\.mp4)""").find(json)
                ?.groupValues?.get(1)?.replace("\\/", "/")
            Log.d("Winbu", "extractFiledonInline: fileName=$fileName")
            val quality = fileName?.let { getQualityFromName(it) }
                ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            callback(
                newExtractorLink("Filedon", "Filedon", rawUrl) {
                    this.referer = "https://filedon.co/"
                    this.quality = quality
                }
            )
            true
        } catch (e: Exception) {
            Log.d("Winbu", "extractFiledonInline exception: ${e.message}")
            false
        }
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val quality: Int?,
    )
}