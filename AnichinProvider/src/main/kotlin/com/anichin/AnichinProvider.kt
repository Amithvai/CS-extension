package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AnichinProvider : MainAPI() {
    companion object {
        private val NON_DIGIT_REGEX = Regex("\\D")
        private val YEAR_REGEX = Regex("(\\d{4})")

        /** Domain mirror resmi Anichin, urutan prioritas.
         * anichin.moe = website utama (per landing page resmi anichin.care).
         * Domain mati akan otomatis di-skip via negative cache. */
        private val domains = listOf(
            "https://anichin.moe",
            "https://anichin.care",
            "https://anichin.id",
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

        private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

        /** Semua domain lama/baru Anichin yang pernah dipakai provider ini */
        private val KNOWN_HOSTS = listOf(
            "anichin.cafe", "anichin.moe", "anichin.care", "anichin.id",
            "anichin.site", "anichin.stream"
        )
    }

    override var mainUrl = domains.first()
    override var name = "Anichin"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    // Path relatif agar tetap valid saat mainUrl berganti ke mirror
    override val mainPage = mainPageOf(
        "ongoing/page/%d/" to "Ongoing",
        "completed/page/%d/" to "Completed",
        "seri/?page=%d&status=&type=&order=" to "Donghua List",
    )

    /**
     * Fetch dokumen dengan fallback multi-domain.
     * Jika host gagal (DNS/403/timeout), host akan di-mark dead 10 menit
     * lalu dicoba mirror berikutnya. mainUrl ikut di-update ke mirror yang hidup.
     */
    private suspend fun fetchDocument(url: String, referer: String? = null): Document {
        val host = hostOf(url)
        if (!isDead(host)) {
            runCatching { app.get(url, referer = referer, timeout = 15_000L).document }.getOrNull()
                ?.let { return it }
            markDead(host)
        }
        for (domain in domains) {
            val mirrorHost = hostOf(domain) ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = url.replace(host ?: "", mirrorHost)
            val doc = runCatching { app.get(mirrorUrl, referer = referer, timeout = 15_000L).document }
                .getOrNull()
            if (doc != null) {
                mainUrl = domain
                return doc
            }
            markDead(mirrorHost)
        }
        throw ErrorLoadingException("Semua domain Anichin tidak dapat diakses")
    }

    /** Normalisasi URL lama (domain mati) ke mainUrl yang aktif */
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
        val href = anchor.attr("abs:href").let { if (it.isBlank()) anchor.attr("href") else it }
        val title = anchor.attr("title").ifBlank {
            selectFirst("h2[itemprop=headline], .tt h2, .tt")?.text()?.trim().orEmpty()
        }
        if (title.isBlank() || href.isBlank()) return null

        val poster = selectFirst("img")?.getImageAttr()
        val episode = selectFirst(".epx")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
        val type = getType(selectFirst(".typez")?.text())

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.replace("%d", page.toString())
        val document = fetchDocument("$mainUrl/$path")
        val results = document.select(".listupd article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, results),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = fetchDocument("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}")
            document.select(".listupd article").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(normalizeUrl(url))
        val title = document.selectFirst(".entry-title, h1.entry-title, .post-title h1, [itemprop*=name] h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")
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
            newEpisode(fixUrl(href)) {
                this.episode = number
                this.name = name
            }
        }.reversed()

        val recommendations = document.select(".bixbox:has(h3:contains(Recommended Series)) .listupd article")
            .mapNotNull { it.toSearchResult() }

        return newAnimeLoadResponse(title, fixUrl(url), TvType.Anime) {
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
            val document = fetchDocument(normalizeUrl(data))
            val links = mutableSetOf<String>()

            document.select("#embed_holder iframe[src], .player-embed iframe[src], [id*=player] iframe[src]")
                .mapTo(links) { it.attr("abs:src").ifBlank { it.attr("src") } }
            document.select("select.mirror option[value]").amap { option ->
                val decoded = decodeServerHash(option.attr("value")) ?: return@amap
                Jsoup.parse(decoded).select("iframe[src]").mapTo(links) { it.attr("src") }
            }

            links.filter { it.isNotBlank() }.amap { link ->
                val fixedLink = fixUrl(link)
                when {
                    // Player lama (mati) tetap didukung bila muncul kembali
                    fixedLink.contains("anichin.stream") -> loadAnichinStream(fixedLink, callback)
                    // Player baru: anichin-player.web.id membungkus Dailymotion
                    fixedLink.contains("anichin-player.web.id") -> loadAnichinPlayer(fixedLink, callback, subtitleCallback)
                    else -> loadExtractor(fixedLink, data, subtitleCallback, callback)
                }
            }

            links.isNotEmpty()
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    // Player anichin.stream (lama) memakai JWPlayer dengan sumber HLS di path /hls/<id>.m3u8
    // yang nilainya sama dengan parameter ?id= pada iframe embed.
    private suspend fun loadAnichinStream(url: String, callback: (ExtractorLink) -> Unit) {
        val streamId = URI(url).rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("id=") }
            ?.substring(3)
            ?: return

        if (streamId.isBlank()) return

        callback.invoke(
            newExtractorLink("Anichin", "Anichin", "https://anichin.stream/hls/$streamId.m3u8", ExtractorLinkType.M3U8) {
                this.referer = "https://anichin.stream/"
            }
        )
    }

    /**
     * Player baru Anichin (anichin-player.web.id/index.php?video=<dailymotionId>).
     * Halaman ini hanya membungkus iframe geo.dailymotion.com, jadi kita ekstrak
     * src iframe-nya lalu serahkan ke loadExtractor (Dailymotion extractor).
     */
    private suspend fun loadAnichinPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val doc = runCatching {
            app.get(url, referer = "$mainUrl/", timeout = 15_000L).document
        }.getOrNull()

        val iframeSrc = doc?.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback, callback)
            return
        }

        // Fallback: rakit URL Dailymotion dari parameter ?video=
        val videoId = URI(url).rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("video=") }
            ?.substringAfter("=")
            ?: return
        if (videoId.isBlank()) return
        loadExtractor(
            "https://geo.dailymotion.com/player.html?video=$videoId",
            "$mainUrl/",
            subtitleCallback,
            callback
        )
    }

    private fun decodeServerHash(hash: String): String? {
        return try {
            String(Base64.decode(hash, Base64.DEFAULT))
        } catch (_: Throwable) {
            null
        }
    }
}
