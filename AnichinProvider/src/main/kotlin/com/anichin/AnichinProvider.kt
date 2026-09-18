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

        /** Header untuk load gambar (hotlink protection) */
        private val POSTER_HEADERS = mapOf(
            "Referer" to "https://anichin.moe/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
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
        // Utamakan srcset (resolusi lebih baik), lalu data-src (lazy), fallback src
        return when {
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ").takeIf { it.isNotBlank() }
            hasAttr("data-src") -> attr("abs:data-src").takeIf { it.isNotBlank() }
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            else -> attr("abs:src").takeIf { it.isNotBlank() }
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
            posterHeaders = POSTER_HEADERS
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
        val safeUrl = normalizeUrl(url)
        val document = fetchDocument(safeUrl)
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

        // Episode list: support theme themesia (.eplister) + fallback lain.
        // Penting: ambil href absolut via fixUrl agar tidak bergantung attr abs: (jsoup abs
        // hanya valid bila base URI diketahui; di sini kita normalisasi manual).
        val episodes = document.select(".eplister ul li a, .episode-list a, [id*=episode] li a").mapNotNull { element ->
            val rawHref = element.attr("href").ifBlank { element.attr("abs:href") }
            if (rawHref.isBlank()) return@mapNotNull null
            val number = element.selectFirst(".epl-num")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
            val name = element.selectFirst(".epl-title")?.text()?.trim()
            newEpisode(fixUrl(rawHref)) {
                this.episode = number
                this.name = name
                this.posterUrl = poster
            }
        }.reversed()

        val recommendations = document.select(".bixbox:has(h3:contains(Recommended Series)) .listupd article")
            .mapNotNull { it.toSearchResult() }

        return newAnimeLoadResponse(title, safeUrl, TvType.Anime) {
            posterUrl = poster
            posterHeaders = POSTER_HEADERS
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
            val safeData = normalizeUrl(data)
            val document = fetchDocument(safeData)
            val links = linkedSetOf<String>()

            // 1) Iframe utama (#embed_holder / .player-embed) — ambil src apa adanya
            document.select("#embed_holder iframe[src], .player-embed iframe[src], [id*=player] iframe[src]")
                .mapTo(links) { it.attr("src").ifBlank { it.attr("abs:src") } }

            // 2) Server mirror: base64-encoded HTML berisi iframe
            document.select("select.mirror option[value], .mobius option[value]").amap { option ->
                val decoded = decodeServerHash(option.attr("value")) ?: return@amap
                Jsoup.parse(decoded).select("iframe[src]").mapTo(links) { it.attr("src") }
            }

            if (links.isEmpty()) {
                throw ErrorLoadingException("Tidak ada server video di halaman ini")
            }

            var found = false
            links.filter { it.isNotBlank() }.amap { link ->
                val fixedLink = fixUrl(link)
                when {
                    // Player lama (mati) tetap didukung bila muncul kembali
                    fixedLink.contains("anichin.stream") -> {
                        if (loadAnichinStream(fixedLink, callback)) found = true
                    }
                    // Player baru: anichin-player.web.id membungkus Dailymotion
                    fixedLink.contains("anichin-player.web.id") -> {
                        if (loadAnichinPlayer(fixedLink, callback, subtitleCallback)) found = true
                    }
                    else -> {
                        val ok = runCatching {
                            loadExtractor(fixedLink, safeData, subtitleCallback, callback)
                            true
                        }.getOrDefault(false)
                        if (ok) found = true
                    }
                }
            }

            found
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    // Player anichin.stream (lama) memakai JWPlayer dengan sumber HLS di path /hls/<id>.m3u8
    // yang nilainya sama dengan parameter ?id= pada iframe embed.
    private suspend fun loadAnichinStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val streamId = URI(url).rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("id=") }
            ?.substring(3)
            ?: return false

        if (streamId.isBlank()) return false

        callback.invoke(
            newExtractorLink("Anichin", "Anichin", "https://anichin.stream/hls/$streamId.m3u8", ExtractorLinkType.M3U8) {
                this.referer = "https://anichin.stream/"
            }
        )
        return true
    }

    /**
     * Player baru Anichin (anichin-player.web.id/index.php?video=<dailymotionId>).
     * Halaman ini hanya membungkus iframe geo.dailymotion.com.
     * Strategi:
     *  1. Fetch halaman (wajib pakai Referer anichin.moe, jika tidak → 403)
     *  2. Ambil src iframe dailymotion
     *  3. Serahkan ke loadExtractor (Dailymotion extractor)
     *  4. Fallback: rakit URL dari parameter ?video=
     */
    private suspend fun loadAnichinPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val videoId = URI(url).rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("video=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }

        val doc = runCatching {
            app.get(url, referer = "$mainUrl/", timeout = 15_000L).document
        }.getOrNull()

        val iframeSrc = doc?.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val ok = runCatching {
                loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback, callback)
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        // Fallback: rakit URL Dailymotion langsung dari parameter ?video=
        if (videoId == null) return false
        return runCatching {
            loadExtractor(
                "https://geo.dailymotion.com/player.html?video=$videoId",
                "$mainUrl/",
                subtitleCallback,
                callback
            )
            true
        }.getOrDefault(false)
    }

    private fun decodeServerHash(hash: String): String? {
        return try {
            String(Base64.decode(hash, Base64.DEFAULT))
        } catch (_: Throwable) {
            null
        }
    }
}
