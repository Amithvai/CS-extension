package com.animeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * AnimekuProvider - animeku.site (theme themesia, sama seperti Anichin)
 *
 * Perubahan penting dari versi lama (animeku.tv yang sudah mati):
 *  - Domain baru: animeku.site (multi-domain fallback)
 *  - Player baru "AnimeKu Studio": player.animeku.site/embed/<uuid>
 *    → API api.animeku.site/videos/embed/<uuid> → HLS di cdn.animeku.site
 *  - Server mirror lain tetap didukung via select.mirror (base64 iframe)
 */
class AnimekuProvider : MainAPI() {
    companion object {
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val YEAR_REGEX = Regex("(\\d{4})")
        private const val ANIMEKU_API = "https://api.animeku.site"

        /** Domain mirror Animeku, urutan prioritas */
        private val domains = listOf(
            "https://animeku.site",
            "https://animeku.top",
        )

        /** Domain lama yang perlu dinormalisasi ke domain aktif */
        private val KNOWN_HOSTS = listOf(
            "animeku.tv", "animeku.site", "animeku.top", "animeku.org", "animeku.net"
        )

        /** Negative-cache domain gagal: skip selama 10 menit */
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
    override var name = "Animeku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Path relatif agar tetap valid saat mainUrl berganti mirror
    override val mainPage = mainPageOf(
        "anime/?orderby=update" to "Latest Update",
        "anime/?orderby=views" to "Populer",
        "anime/?status=Completed&type=&order=update" to "Tamat",
        "anime/?type=Movie&order=update" to "Movie",
    )

    /**
     * Fetch dokumen dengan fallback multi-domain + negative cache.
     */
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
        throw ErrorLoadingException("Semua domain Animeku tidak dapat diakses")
    }

    /** Normalisasi URL lama (animeku.tv dll) ke domain aktif */
    private fun normalizeUrl(url: String): String {
        val host = hostOf(url) ?: return url
        if (host in KNOWN_HOSTS && !url.startsWith(mainUrl)) {
            return url.replace("https://$host", mainUrl).replace("http://$host", mainUrl)
        }
        return url
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst(".bsx > a, h2.entry-title a, .tt a") ?: return null
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        val title = anchor.attr("title").ifBlank {
            selectFirst("h2[itemprop=headline], .tt h2, .tt")?.text()?.trim().orEmpty()
        }
        if (title.isBlank() || href.isBlank()) return null

        val poster = selectFirst("img")?.getImageAttr()
        val episode = selectFirst(".epx")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val path = "${request.data}${separator}page=$page"
        val document = fetchDocument("$mainUrl/$path")
        val items = document.select(".listupd article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = fetchDocument("$mainUrl/?s=$encoded&post_type=anime")
        return document.select(".listupd article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(normalizeUrl(url))

        val title = document.selectFirst(".entry-title, h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = document.selectFirst(".thumb img, .bigcontent .thumb img")?.getImageAttr()
        val description = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst(".desc")?.text()?.trim()
        val tags = document.select(".genxed a, .spe .genxed a").map { it.text() }
        val statusText = document.select(".spe span").firstOrNull { it.text().contains("Status:", true) }?.text()
        val status = when {
            statusText?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            statusText?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
        val year = YEAR_REGEX.find(
            document.select(".spe span").firstOrNull { it.text().contains("Released:", true) }?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = document.select(".eplister ul li a, .episode-list a, [id*=episode] li a").mapNotNull { element ->
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val number = element.selectFirst(".epl-num")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
            val name = element.selectFirst(".epl-title")?.text()?.trim()
            newEpisode(fixUrl(href)) {
                this.episode = number
                this.name = name
                this.posterUrl = poster
            }
        }.reversed()

        val recommendations = document.select(".bixbox:has(h3:contains(Recommended Series)) .listupd article")
            .mapNotNull { it.toSearchResult() }

        return newAnimeLoadResponse(title, fixUrl(url), TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.showStatus = status
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
        val document = fetchDocument(normalizeUrl(data))
        val links = mutableSetOf<String>()

        document.select("#embed_holder iframe[src], .player-embed iframe[src], [id*=player] iframe[src]")
            .mapTo(links) { it.attr("abs:src").ifBlank { it.attr("src") } }
        document.select("select.mirror option[value]").amap { option ->
            val decoded = decodeServerHash(option.attr("value")) ?: return@amap
            Jsoup.parse(decoded).select("iframe[src]").mapTo(links) { it.attr("src") }
        }

        var found = false
        links.filter { it.isNotBlank() }.amap { link ->
            val fixedLink = fixUrl(link)
            if (fixedLink.contains("player.animeku.site") || fixedLink.contains("animeku.site/embed/")) {
                if (loadAnimekuStudio(fixedLink, callback)) found = true
            } else {
                loadExtractor(fixedLink, data, subtitleCallback, callback)
                found = true
            }
        }

        if (!found) throw ErrorLoadingException("Tidak ada sumber video di Animeku")
        return true
    }

    /**
     * AnimeKu Studio player: player.animeku.site/embed/<uuid>
     * Resolve via API: api.animeku.site/videos/embed/<uuid> → HLS master.m3u8.
     */
    private suspend fun loadAnimekuStudio(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = extractVideoId(url) ?: return false
        return try {
            val apiUrl = "$ANIMEKU_API/videos/embed/$videoId" +
                "?origin=https%3A%2F%2Fplayer.animeku.site&referer=https%3A%2F%2Fplayer.animeku.site"
            val json = app.get(apiUrl, referer = "https://player.animeku.site/", timeout = 15_000L).text
            val hls = Regex(""""hls"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?: return false
            if (hls.isBlank()) return false

            callback.invoke(
                newExtractorLink("AnimeKu", "AnimeKu", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.animeku.site/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "https://player.animeku.site/",
                        "Origin" to "https://player.animeku.site"
                    )
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ambil UUID video dari berbagai bentuk URL embed AnimeKu */
    private fun extractVideoId(url: String): String? {
        // contoh: https://player.animeku.site/embed/0fbe7958-...
        Regex("""/embed/([a-fA-F0-9-]{16,})""").find(url)?.let {
            return it.groupValues.getOrNull(1)
        }
        // contoh: ?video=<uuid>
        runCatching {
            URI(url).rawQuery?.split("&")
                ?.firstOrNull { it.startsWith("video=") }
                ?.substringAfter("=")
        }.getOrNull()?.let { if (it.isNotBlank()) return it }
        return null
    }

    private fun decodeServerHash(hash: String): String? {
        return try {
            String(android.util.Base64.decode(hash, android.util.Base64.DEFAULT))
        } catch (_: Throwable) {
            null
        }
    }
}
